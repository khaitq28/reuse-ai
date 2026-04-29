# Stock Exchange / Matching Engine — Deep Dive

## Problem Statement

Design a stock exchange system where traders can place buy/sell orders, orders are matched in real-time by a matching engine using price-time priority, and trades are executed with microsecond-to-millisecond latency — similar to the internals of NYSE, NASDAQ, or a crypto exchange like Binance.

---

## Why This Problem Matters

The stock exchange matching engine is one of the most demanding real-time systems in software engineering. Interviewers use this problem to test:

1. **Extreme latency requirements** — NYSE matches orders in under 10 microseconds. Binance targets under 1 millisecond. Every design decision (data structures, threading model, networking, serialization) is evaluated through the lens of latency.
2. **Correctness under concurrency** — Price-time priority is a legal requirement for exchanges. If two orders at the same price arrive in a specific order, they must be matched in that exact order. This requirement eliminates naive concurrent designs.
3. **Data structure selection** — The order book is a specific data structure with specific performance requirements. Knowing which structure (TreeMap + Deque) achieves O(log P + 1) per operation versus why a HashMap would be wrong reveals deep algorithmic thinking.
4. **Exactly-once semantics in finance** — Duplicate trade executions cause real financial harm. Understanding how to guarantee exactly-once execution without sacrificing throughput is critical.
5. **LMAX Disruptor** — This is a well-known pattern in low-latency Java systems. Understanding why it outperforms a standard queue (no locking, cache-friendly ring buffer, mechanical sympathy) demonstrates deep systems knowledge.

The interviewer is testing: data structures for the order book, single-threaded vs. multi-threaded matching, LMAX Disruptor pattern, order lifecycle management, risk controls, and market data distribution.

---

## Key Insight Before Diving In

**A single-threaded matching engine per symbol is faster and more correct than a multi-threaded one.**

This is counterintuitive. In most systems, multi-threading increases throughput. For a matching engine, single-threading per symbol is optimal because:

1. **No locks needed** — locks have overhead (CAS, cache coherency traffic). A single thread never contends with itself.
2. **Deterministic ordering** — price-time priority requires FIFO within a price level. With multiple threads, enforcing arrival order requires coordination that eliminates the throughput benefit.
3. **Cache efficiency** — a single thread working on one order book keeps the entire order book in L1/L2 CPU cache. Context switching and multi-thread cache invalidation destroy this locality.
4. **Sequential processing** — an order book update (add, match, cancel) consists of multiple operations that must be atomic from the perspective of the market. Single-threading gives atomicity for free.

The result is that world-class matching engines (LMAX Exchange, NYSE MatchPoint) use a single-threaded event processing loop per instrument, fed by a lock-free ring buffer (LMAX Disruptor). They achieve under 1ms latency at 100k orders/second — higher throughput and lower latency than any multi-threaded design.

---

## Requirements

### Functional
- Place market orders (execute immediately at best available price)
- Place limit orders (execute at specified price or better; rest in book if unmatched)
- Place stop orders (become market orders when trigger price is reached)
- Cancel pending orders
- Order types by time-in-force: GTC (Good Till Cancelled), IOC (Immediate Or Cancel), FOK (Fill Or Kill), DAY (expire at end of trading day)
- Real-time market data: full order book (L2), last trade price, volume, OHLC
- Trade history per account
- Account balance and position management
- Pre-trade and post-trade risk checks

### Non-Functional
- Matching latency: P99 under 1ms, P50 under 100 microseconds
- Throughput: 100,000 orders per second per symbol
- Strict price-time priority (FIFO within same price level, no exceptions)
- Exactly-once trade execution — no duplicate fills
- Full audit trail of every order and trade
- Circuit breakers: halt trading if price moves beyond defined thresholds

---

## Capacity Estimation

```
Orders:
  100k orders/sec total across all symbols
  10k symbols → 10 orders/sec/symbol on average
  Hot symbols (AAPL, TSLA): 10k orders/sec during peak

Matching latency budget (1ms target):
  Network ingress:        ~50μs
  Order Gateway validate: ~10μs
  Risk check:             ~20μs
  Queue transit:          ~5μs (Disruptor ring buffer)
  Matching engine:        ~50μs (the actual matching)
  Trade publication:      ~30μs
  Network egress:         ~50μs
  Total:                  ~215μs P50 (well under 1ms)

Order book memory:
  Top 1000 price levels × 2 sides × average 10 orders/level
  Each order: ~200 bytes → 10 × 200 = 2KB per price level
  1000 levels × 2 sides × 2KB = 4MB per symbol
  10k symbols → 40GB (fits in modern servers with 512GB RAM)
  Hot symbols: keep in L1/L2 cache by CPU affinity pinning

Trades:
  50k trades/sec × 300 bytes = 15MB/sec
  50k × 3600 × 8 hours = 1.44B trades/day → 432GB/day
  Cassandra time-series storage: compresses to ~100GB/day
```

---

## Core Concept: The Order Book

The order book is the central data structure of any exchange. It maintains two sorted lists:
- **Bids (buy orders)**: sorted descending by price (highest bid first — this buyer will pay the most)
- **Asks (sell orders)**: sorted ascending by price (lowest ask first — this seller will accept the least)

Within each price level, orders are sorted by arrival time (FIFO — earliest order gets filled first).

```
Symbol: AAPL
Timestamp: 2026-04-29 09:30:01.234567

╔═══════════════════════════════════════════════════════════╗
║                    ORDER BOOK                             ║
╠══════════════════════════╦════════════════════════════════╣
║   BUY SIDE (BIDS)        ║   SELL SIDE (ASKS)             ║
║   Highest price first    ║   Lowest price first           ║
╠══════════╦═══════╦═══════╬══════════╦═══════╦════════════╣
║  Price   ║  Qty  ║ Orders║  Price   ║  Qty  ║ Orders     ║
╠══════════╬═══════╬═══════╬══════════╬═══════╬════════════╣
║ $182.55  ║  500  ║ [B5]  ║ $182.60  ║  800  ║ [S1]       ║
║ $182.50  ║ 1500  ║[B3,B4]║ $182.65  ║ 1200  ║ [S2, S3]   ║
║ $182.45  ║ 2000  ║ [B2]  ║ $182.70  ║  400  ║ [S4]       ║
║ $182.40  ║  700  ║ [B1]  ║ $182.80  ║ 2000  ║ [S5, S6]   ║
╚══════════╩═══════╩═══════╩══════════╩═══════╩════════════╝
                    ↑
             Spread = $182.60 - $182.55 = $0.05
             (no immediate match — best bid < best ask)
```

When a new buy order at $182.60 arrives:
- Best ask = $182.60 → price crosses → match!
- Fill 500 shares at $182.60 against S1
- Trade executes at $182.60 (price of the resting order)

The matching price is always the resting order's price, not the incoming order's price. This is the price-time priority rule: the order that arrived first at the best price gets filled at that price.

---

## Matching Engine Data Structure

### Why TreeMap + ArrayDeque?

The matching engine needs:
- O(log P) to find the best price level (P = number of price levels) — TreeMap
- O(1) to access the first order in a price level (FIFO) — ArrayDeque (double-ended queue)
- O(1) to add a new order to the back of a price level — ArrayDeque
- O(1) to remove a filled order from the front — ArrayDeque.poll()
- O(log P) to remove an empty price level — TreeMap.remove()

```java
/**
 * Order book implementation using TreeMap + ArrayDeque.
 *
 * TreeMap: self-balancing BST, O(log n) operations.
 * For bids: reversed comparator (highest price first, firstEntry = best bid).
 * For asks: natural comparator (lowest price first, firstEntry = best ask).
 *
 * ArrayDeque at each price level: FIFO queue for time priority within a price level.
 * ArrayDeque is faster than LinkedList (array-backed, cache-friendly, no node allocation).
 */
public class OrderBook {
    private final String symbol;

    // Bids: sorted highest to lowest. firstEntry() = best bid (highest price).
    private final TreeMap<BigDecimal, ArrayDeque<Order>> bids =
        new TreeMap<>(Comparator.reverseOrder());

    // Asks: sorted lowest to highest. firstEntry() = best ask (lowest price).
    private final TreeMap<BigDecimal, ArrayDeque<Order>> asks = new TreeMap<>();

    // Fast O(1) order lookup by ID for cancellation
    private final HashMap<UUID, Order> orderIndex = new HashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Match an incoming limit order against the order book.
     * Returns the list of trades generated.
     * Any unfilled quantity is added to the book (resting order).
     */
    public List<Trade> matchLimitOrder(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        TreeMap<BigDecimal, ArrayDeque<Order>> oppositeBook =
            incoming.getSide() == Side.BUY ? asks : bids;

        // Keep matching while:
        // 1. The incoming order has unfilled quantity
        // 2. There are orders on the opposite side
        // 3. The prices cross (buy price >= best ask price, or sell price <= best bid price)
        while (incoming.getRemainingQty() > 0 && !oppositeBook.isEmpty()) {
            Map.Entry<BigDecimal, ArrayDeque<Order>> bestLevel = oppositeBook.firstEntry();
            BigDecimal bestPrice = bestLevel.getKey();

            // Check if prices cross
            boolean pricesCross = incoming.getSide() == Side.BUY
                ? incoming.getPrice().compareTo(bestPrice) >= 0  // buy price >= best ask
                : incoming.getPrice().compareTo(bestPrice) <= 0; // sell price <= best bid

            if (!pricesCross) break;  // no match at current price level

            // Fill against orders at this price level, respecting FIFO
            ArrayDeque<Order> ordersAtLevel = bestLevel.getValue();
            while (!ordersAtLevel.isEmpty() && incoming.getRemainingQty() > 0) {
                Order resting = ordersAtLevel.peek();

                // Fill quantity = min(incoming remaining, resting remaining)
                long fillQty = Math.min(incoming.getRemainingQty(), resting.getRemainingQty());

                // Trade price = resting order's price (price-time priority)
                // The resting order was there first — it gets its requested price
                BigDecimal tradePrice = resting.getPrice();

                // Record the trade
                Trade trade = Trade.builder()
                    .id(UUID.randomUUID())
                    .symbol(symbol)
                    .buyOrderId(incoming.getSide() == Side.BUY ? incoming.getId() : resting.getId())
                    .sellOrderId(incoming.getSide() == Side.SELL ? incoming.getId() : resting.getId())
                    .price(tradePrice)
                    .quantity(fillQty)
                    .executedAt(Instant.now())
                    .build();

                trades.add(trade);

                // Update fill quantities
                incoming.fill(fillQty);
                resting.fill(fillQty);

                // Remove fully filled resting order
                if (resting.isFilled()) {
                    ordersAtLevel.poll();  // O(1) — front of deque
                    orderIndex.remove(resting.getId());
                }
            }

            // Remove empty price level from the book
            if (ordersAtLevel.isEmpty()) {
                oppositeBook.remove(bestPrice);  // O(log P)
            }
        }

        // Handle time-in-force for remaining quantity
        if (!incoming.isFilled()) {
            switch (incoming.getTimeInForce()) {
                case GTC, DAY -> addToBook(incoming);   // rest in book
                case IOC -> { /* Immediate Or Cancel: discard remaining */ }
                case FOK -> {
                    // Fill Or Kill: if not completely filled, cancel ALL trades
                    // (FOK matching requires a two-phase approach: simulate, then execute)
                    // In this simplified version, FOK that doesn't fill completely = cancel
                    trades.clear();  // undo all trades for this order
                }
            }
        }

        return trades;
    }

    /**
     * Match a market order against the book.
     * Market orders have no price limit — they match at whatever price is available.
     * If the book is empty, the remaining quantity is cancelled (no resting market orders).
     */
    public List<Trade> matchMarketOrder(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        TreeMap<BigDecimal, ArrayDeque<Order>> oppositeBook =
            incoming.getSide() == Side.BUY ? asks : bids;

        while (incoming.getRemainingQty() > 0 && !oppositeBook.isEmpty()) {
            Map.Entry<BigDecimal, ArrayDeque<Order>> bestLevel = oppositeBook.firstEntry();
            ArrayDeque<Order> ordersAtLevel = bestLevel.getValue();

            Order resting = ordersAtLevel.peek();
            long fillQty = Math.min(incoming.getRemainingQty(), resting.getRemainingQty());
            BigDecimal tradePrice = resting.getPrice();

            trades.add(Trade.builder()
                .symbol(symbol).price(tradePrice).quantity(fillQty)
                .buyOrderId(incoming.getSide() == Side.BUY ? incoming.getId() : resting.getId())
                .sellOrderId(incoming.getSide() == Side.SELL ? incoming.getId() : resting.getId())
                .executedAt(Instant.now()).build());

            incoming.fill(fillQty);
            resting.fill(fillQty);

            if (resting.isFilled()) {
                ordersAtLevel.poll();
                orderIndex.remove(resting.getId());
                if (ordersAtLevel.isEmpty()) oppositeBook.remove(bestLevel.getKey());
            }
        }

        // Remaining market order quantity if book is empty is simply cancelled
        // (market orders cannot rest in the book)
        return trades;
    }

    /**
     * Add a resting order to the book.
     * O(log P) for TreeMap.computeIfAbsent, O(1) for ArrayDeque.offer.
     */
    private void addToBook(Order order) {
        TreeMap<BigDecimal, ArrayDeque<Order>> book =
            order.getSide() == Side.BUY ? bids : asks;
        book.computeIfAbsent(order.getPrice(), p -> new ArrayDeque<>()).offer(order);
        orderIndex.put(order.getId(), order);
    }

    /**
     * Cancel a resting order. O(1) lookup, O(n) removal from deque in worst case.
     * For production: use a doubly-linked list inside the deque for O(1) removal,
     * or mark as cancelled and skip during matching (lazy deletion).
     */
    public boolean cancel(UUID orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null) return false;  // not in book
        order.cancel();
        // Lazy deletion: mark as cancelled; matching engine skips cancelled orders
        // This avoids O(n) removal from the middle of the deque
        return true;
    }
}
```

---

## LMAX Disruptor: The Secret to Microsecond Latency

### Why Not a Standard BlockingQueue?

A `LinkedBlockingQueue` (standard Java) has several performance problems:
1. **Lock contention** — producers and consumers share a lock. At 100k messages/sec, this lock becomes a bottleneck.
2. **Memory allocation** — each enqueue allocates a new `Node` object. This triggers garbage collection. GC pauses of even 1ms destroy latency targets.
3. **Cache miss** — linked list nodes are scattered in memory. The CPU cache cannot prefetch them effectively.
4. **Memory barrier overhead** — volatile reads/writes for visibility across threads require memory fences, which flush CPU store buffers.

### LMAX Disruptor Ring Buffer

The Disruptor solves all these problems:

```
Ring buffer: fixed-size, pre-allocated array of Event slots
             Size must be a power of 2 (for fast modulo: index = sequence & (size-1))

Producer                     Consumer
   │                              │
   │  1. Claim next sequence       │
   │  2. Write event to slot       │
   │  3. Publish sequence          │
   │                               │  4. Wait for new sequence
   │                               │  5. Read event from slot
   │                               │  6. Process event
   │                               │  7. Advance consumer sequence
   │
   ╔════════════════════════════════════════╗
   ║  [slot 0][slot 1][slot 2]...[slot 1023]║  ← Ring buffer
   ║     ↑                    ↑             ║
   ║  Consumer cursor       Producer cursor ║
   ╚════════════════════════════════════════╝
```

```java
/**
 * LMAX Disruptor setup for the order matching pipeline.
 *
 * Key decisions:
 * - SINGLE_PRODUCER: one Order Gateway thread produces orders → no producer coordination needed
 * - YIELDING_WAIT: consumer yields CPU instead of sleeping (lower latency, higher CPU usage)
 * - Ring buffer size 65536: large enough to absorb bursts, power of 2 for fast index math
 *
 * The entire pipeline is lock-free and allocation-free at steady state.
 * Pre-allocated event objects in the ring buffer avoid GC pressure.
 */
public class MatchingEnginePipeline {

    private final Disruptor<OrderEvent> disruptor;
    private final RingBuffer<OrderEvent> ringBuffer;

    public MatchingEnginePipeline(String symbol) {
        // Pre-allocate all event objects in the ring buffer
        // This is the key GC-avoidance trick: we reuse event objects, never allocate new ones
        EventFactory<OrderEvent> factory = OrderEvent::new;

        disruptor = new Disruptor<>(
            factory,
            65536,                          // ring buffer size (must be power of 2)
            DaemonThreadFactory.INSTANCE,
            ProducerType.SINGLE,            // single producer (Order Gateway thread)
            new YieldingWaitStrategy()      // yield instead of sleep: lower latency at cost of CPU
        );

        // Single handler: the matching engine for this symbol
        // Single-threaded consumer → no locks needed inside the matching engine
        MatchingEngineHandler handler = new MatchingEngineHandler(symbol);
        disruptor.handleEventsWith(handler);

        ringBuffer = disruptor.start();
    }

    /**
     * Publish an order to the matching engine. Called by the Order Gateway.
     * This is the "hot path" — must be as fast as possible.
     *
     * The sequence claim is a CAS operation (Compare-And-Swap) — lock-free, not wait-free.
     * For SINGLE_PRODUCER, no CAS is needed — producer just increments a counter.
     */
    public void publishOrder(Order order) {
        // Claim a slot in the ring buffer
        long sequence = ringBuffer.next();
        try {
            // Get the pre-allocated event object for this slot
            OrderEvent event = ringBuffer.get(sequence);
            // Copy order data into the pre-allocated slot (no new allocation)
            event.setOrder(order);
        } finally {
            // Publish: make this slot visible to the consumer
            ringBuffer.publish(sequence);
        }
    }
}

/**
 * The matching engine event handler — runs in a single dedicated thread.
 * All access to the OrderBook is single-threaded → no synchronization needed.
 * The CPU core running this thread should be isolated and pinned
 * (Linux: isolcpus, taskset) to eliminate OS scheduling jitter.
 */
public class MatchingEngineHandler implements EventHandler<OrderEvent> {
    private final OrderBook orderBook;
    private final TradePublisher tradePublisher;
    private final MarketDataPublisher marketDataPublisher;

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        Order order = event.getOrder();

        List<Trade> trades;
        switch (order.getType()) {
            case MARKET -> trades = orderBook.matchMarketOrder(order);
            case LIMIT  -> trades = orderBook.matchLimitOrder(order);
            case CANCEL -> { orderBook.cancel(order.getId()); trades = List.of(); }
            default -> throw new UnknownOrderTypeException(order.getType());
        }

        // Publish trades to post-trade processing
        // endOfBatch: true = this is the last event in the current batch
        // Batch trade publication avoids per-trade overhead
        if (!trades.isEmpty()) {
            tradePublisher.publish(trades);
        }

        // Publish market data update (order book changed)
        marketDataPublisher.publishBookUpdate(orderBook.getTopOfBook(), endOfBatch);
    }
}
```

---

## System Architecture

```
Trader API (REST / WebSocket / FIX Protocol)
        │
        ▼
Order Gateway
  - Authenticate client
  - Validate order format (symbol exists, valid price, valid quantity)
  - Assign order ID and timestamp (nanosecond precision)
  - Idempotency check (prevent duplicate orders on network retry)
  - Route to correct matching engine by symbol
        │
        ▼ (via Disruptor ring buffer — per symbol)
Order Management System (OMS)
  - Pre-trade risk check (buying power, position limits, circuit breaker)
  - Update order lifecycle state
  - If passes → forward to matching engine
  - If fails → reject immediately
        │
        ▼ (single-threaded per symbol)
Matching Engine (in-memory OrderBook + Disruptor consumer)
  - Match or rest the order
  - Emit Trade events
        │
        ▼ (asynchronous post-trade pipeline)
Post-Trade Processing (fan-out)
  ├── Trade Persistence Service → Cassandra (time-series trades)
  ├── Settlement Service → update account balances and positions
  ├── Market Data Publisher → Kafka → WebSocket broadcast
  └── Compliance/Surveillance → audit trail
```

---

## Pre-Trade Risk Checks

Risk checks run BEFORE the order reaches the matching engine. They must be extremely fast (< 20 microseconds) to maintain low end-to-end latency.

```java
/**
 * Pre-trade risk engine — validates that the order is safe to execute.
 * Checks are in-memory: account data is cached and updated asynchronously.
 * Never make synchronous DB calls in the risk engine hot path.
 */
public class PreTradeRiskEngine {

    private final ConcurrentHashMap<UUID, AccountState> accountCache;
    private final CircuitBreakerRegistry circuitBreakers;

    public RiskCheckResult check(Order order) {
        AccountState account = accountCache.get(order.getAccountId());
        if (account == null) return RiskCheckResult.reject("Account not found");
        if (!account.isActive()) return RiskCheckResult.reject("Account suspended");

        switch (order.getSide()) {
            case BUY -> {
                // Check buying power: cash available minus reserved for open buy orders
                BigDecimal orderValue = order.getPrice().multiply(
                    BigDecimal.valueOf(order.getQuantity())
                );
                if (account.getBuyingPower().compareTo(orderValue) < 0) {
                    return RiskCheckResult.reject("Insufficient buying power: have "
                        + account.getBuyingPower() + " need " + orderValue);
                }
                // Check single order size limit (regulatory or internal)
                if (order.getQuantity() > account.getMaxOrderSize()) {
                    return RiskCheckResult.reject("Order size exceeds limit");
                }
                // Reserve buying power (optimistic — released if order cancelled or filled)
                account.reserveBuyingPower(orderValue);
            }
            case SELL -> {
                // Check position: can only sell shares you own
                long position = account.getPosition(order.getSymbol());
                if (position < order.getQuantity()) {
                    return RiskCheckResult.reject("Insufficient position: have "
                        + position + " shares, trying to sell " + order.getQuantity());
                }
            }
        }

        // Circuit breaker check: is trading halted for this symbol?
        CircuitBreaker cb = circuitBreakers.get(order.getSymbol());
        if (cb != null && cb.isOpen()) {
            return RiskCheckResult.reject("Trading halted for " + order.getSymbol()
                + ": " + cb.getReason());
        }

        // Price sanity check: order price must be within N% of last trade price
        // Prevents accidental "fat finger" orders (order at $1 when stock is $182)
        BigDecimal lastPrice = marketDataCache.getLastPrice(order.getSymbol());
        if (lastPrice != null && order.getType() == OrderType.LIMIT) {
            double deviation = order.getPrice().subtract(lastPrice)
                .divide(lastPrice, 4, RoundingMode.HALF_UP)
                .abs().doubleValue();
            if (deviation > 0.20) {  // 20% away from last trade price
                return RiskCheckResult.reject("Price " + order.getPrice()
                    + " is more than 20% from last price " + lastPrice);
            }
        }

        return RiskCheckResult.pass();
    }
}
```

---

## Order Types and Time-In-Force

### Order Types

```
MARKET:  Execute immediately at best available price.
         No price specified. May get a worse price than expected (slippage).
         Guaranteed to execute (unless book is empty).

LIMIT:   Execute at specified price or better.
         Buy at $182.50 or cheaper; sell at $182.60 or more expensive.
         If not immediately matchable → rest in book as a resting order.

STOP:    Become a market order when the trigger price is hit.
         Sell stop at $180.00: if last trade price falls to $180.00 → submit market sell.
         Used for stop-loss orders.

STOP_LIMIT: Become a limit order when the trigger price is hit.
            Sell stop-limit: trigger $180.00, limit $179.50.
            When $180.00 is hit → submit limit sell at $179.50.
```

### Time-In-Force

```
GTC  (Good Till Cancelled): stays in book until filled or explicitly cancelled.
     Default for most limit orders.

DAY:  Cancelled at end of trading day if not filled.
      The exchange's EOD process sweeps all DAY orders.

IOC  (Immediate Or Cancel): execute as much as possible immediately; cancel the rest.
     E.g., buy 100 shares IOC at $182.60: if only 60 shares available at that price,
     fill 60 and cancel the remaining 40. Never rests in the book.

FOK  (Fill Or Kill): fill the ENTIRE quantity immediately or cancel the whole order.
     E.g., buy 100 shares FOK at $182.60: if only 60 shares available → cancel all 100.
     Used by institutions that need a guaranteed full fill (e.g., index rebalancing).
```

```java
// FOK implementation requires a dry-run simulation before execution
public List<Trade> matchFOK(Order incoming) {
    // Phase 1: Simulate matching without modifying the book
    long simulatedFill = simulateFillQty(incoming, asks);

    // Phase 2: Only execute if full fill is achievable
    if (simulatedFill < incoming.getQuantity()) {
        // Cannot fill completely — cancel the entire order
        return List.of();  // no trades
    }

    // Phase 3: Execute the full fill (modify the book)
    return matchLimitOrder(incoming);  // will fully fill since we verified qty
}

private long simulateFillQty(Order incoming,
        TreeMap<BigDecimal, ArrayDeque<Order>> oppositeBook) {
    long totalFillable = 0;
    for (Map.Entry<BigDecimal, ArrayDeque<Order>> level : oppositeBook.entrySet()) {
        BigDecimal price = level.getKey();
        boolean pricesCross = incoming.getSide() == Side.BUY
            ? incoming.getPrice().compareTo(price) >= 0
            : incoming.getPrice().compareTo(price) <= 0;
        if (!pricesCross) break;

        for (Order resting : level.getValue()) {
            totalFillable += resting.getRemainingQty();
            if (totalFillable >= incoming.getQuantity()) return totalFillable;
        }
    }
    return totalFillable;
}
```

---

## Circuit Breakers for Market Stability

Circuit breakers halt trading when prices move abnormally — preventing flash crashes and protecting market integrity.

```java
/**
 * Circuit breaker implementation.
 *
 * Level 1 (Single symbol): triggered when price moves > 5% in 5 minutes.
 *   → Trading halted for that symbol for 5 minutes.
 *   → Matching engine rejects all new orders (risk check blocks them).
 *   → Market data continues broadcasting the halt status.
 *
 * Level 2 (Market-wide): triggered when S&P 500 falls > 7% in a day.
 *   → All symbols halted for 15 minutes.
 *   → SEC Level 1 circuit breaker (actual NYSE rule).
 *
 * Level 3 (Market-wide): S&P 500 falls > 20% in a day.
 *   → Market closes for the day.
 */
@Scheduled(fixedRate = 1000)  // check every second
public void monitorCircuitBreakers() {
    for (String symbol : activeSymbols) {
        PriceHistory history = marketDataCache.getPriceHistory(symbol, Duration.ofMinutes(5));
        if (history.isEmpty()) continue;

        BigDecimal recentHigh = history.getMax();
        BigDecimal recentLow = history.getMin();
        BigDecimal lastPrice = history.getLast();

        // Check for > 5% drop from 5-minute high
        double dropPct = recentHigh.subtract(lastPrice)
            .divide(recentHigh, 4, RoundingMode.HALF_UP)
            .doubleValue();

        if (dropPct > 0.05) {
            CircuitBreaker cb = new CircuitBreaker(
                symbol,
                CircuitBreakerType.SINGLE_SYMBOL,
                "Price dropped " + (dropPct * 100) + "% in 5 minutes",
                Instant.now().plus(Duration.ofMinutes(5))  // reopen after 5 min
            );
            circuitBreakers.put(symbol, cb);
            marketDataPublisher.publishHalt(symbol, cb);
            log.warn("Circuit breaker triggered for {}: {}% drop", symbol, dropPct * 100);
        }
    }
}
```

---

## Market Data: Real-Time Order Book Distribution

```
On each order book change (new order, trade, cancellation):
  1. Matching engine publishes L2 snapshot to Kafka topic: market.{symbol}.book
  2. Market Data Service consumes from Kafka:
     - Maintains in-memory order book state per symbol
     - Broadcasts WebSocket messages to all subscribers

Throttling: merge multiple book updates within 100ms into one broadcast
  (at 100k orders/sec, broadcasting every update would overwhelm clients)

Message formats:
  L1 (top of book):   { bid: 182.55, ask: 182.60, last: 182.58, volume: 1.2M }
  L2 (full depth):    { bids: [[182.55, 500], [182.50, 1500]], asks: [[182.60, 800], ...] }
  Trades:             { price: 182.58, qty: 100, side: BUY, time: 1714392060123456 }
```

```java
// WebSocket handler for market data
@Component
public class MarketDataWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, Set<WebSocketSession>> symbolSubscribers = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Client subscribes: { "action": "subscribe", "symbol": "AAPL", "channel": "L2" }
        SubscribeRequest req = parse(message);
        symbolSubscribers
            .computeIfAbsent(req.getSymbol(), s -> ConcurrentHashMap.newKeySet())
            .add(session);
        // Send current order book snapshot immediately upon subscription
        sendSnapshot(session, req.getSymbol());
    }

    // Called by Market Data Service when book changes
    public void broadcast(String symbol, OrderBookUpdate update) {
        String json = serialize(update);
        Set<WebSocketSession> subscribers = symbolSubscribers.getOrDefault(
            symbol, Set.of()
        );
        // Send to all subscribers — use virtual threads for non-blocking I/O
        subscribers.parallelStream().forEach(session -> {
            try {
                session.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                subscribers.remove(session);  // clean up disconnected clients
            }
        });
    }
}
```

---

## Data Model

### orders (PostgreSQL — for OMS lifecycle tracking)
```sql
CREATE TABLE orders (
  id              UUID        PRIMARY KEY,
  account_id      UUID        NOT NULL,
  symbol          VARCHAR(20) NOT NULL,
  side            CHAR(1)     NOT NULL,      -- B=Buy, S=Sell
  type            VARCHAR(10) NOT NULL,      -- MARKET, LIMIT, STOP, STOP_LIMIT
  price           DECIMAL(19, 8),            -- null for market orders
  stop_price      DECIMAL(19, 8),            -- for stop orders
  quantity        BIGINT      NOT NULL,
  filled_qty      BIGINT      NOT NULL DEFAULT 0,
  avg_fill_price  DECIMAL(19, 8),
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  -- PENDING: accepted, queued for risk check
  -- OPEN:    in order book (resting)
  -- PARTIAL: partially filled, remainder in book
  -- FILLED:  fully executed
  -- CANCELLED: cancelled by user or IOC/FOK
  -- REJECTED:  failed risk check
  -- EXPIRED:   DAY order expired
  time_in_force   VARCHAR(10) NOT NULL DEFAULT 'GTC',
  client_order_id VARCHAR(100),              -- client's own order ID (idempotency)
  placed_at       TIMESTAMP   NOT NULL,
  updated_at      TIMESTAMP   NOT NULL,
  UNIQUE (account_id, client_order_id)      -- idempotency: same client order ID → same result
);

CREATE INDEX idx_orders_account ON orders (account_id, placed_at DESC);
CREATE INDEX idx_orders_symbol_open ON orders (symbol, status)
  WHERE status IN ('PENDING', 'OPEN', 'PARTIAL');
```

### trades (Cassandra — time-series, high-volume append)
```sql
CREATE TABLE trades (
  symbol       VARCHAR(20),
  executed_at  TIMESTAMP,
  trade_id     UUID,
  buy_order_id UUID,
  sell_order_id UUID,
  price        DECIMAL,
  quantity     BIGINT,
  PRIMARY KEY ((symbol), executed_at, trade_id)    -- partition by symbol, cluster by time
) WITH CLUSTERING ORDER BY (executed_at DESC);     -- most recent trades first

-- For user trade history
CREATE TABLE account_trades (
  account_id   UUID,
  executed_at  TIMESTAMP,
  trade_id     UUID,
  symbol       VARCHAR(20),
  side         CHAR(1),
  price        DECIMAL,
  quantity     BIGINT,
  PRIMARY KEY ((account_id), executed_at, trade_id)
) WITH CLUSTERING ORDER BY (executed_at DESC);
```

### accounts and positions
```sql
CREATE TABLE accounts (
  id              UUID        PRIMARY KEY,
  user_id         UUID        NOT NULL,
  cash_balance    DECIMAL(19, 4) NOT NULL DEFAULT 0,
  buying_power    DECIMAL(19, 4) NOT NULL DEFAULT 0,
  -- buying_power = cash_balance - sum(reserved for open buy orders)
  status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, SUSPENDED, CLOSED
  updated_at      TIMESTAMP   NOT NULL
);

CREATE TABLE positions (
  account_id  UUID        NOT NULL,
  symbol      VARCHAR(20) NOT NULL,
  quantity    BIGINT      NOT NULL DEFAULT 0,  -- shares held
  avg_cost    DECIMAL(19, 8),                  -- weighted average cost basis
  updated_at  TIMESTAMP   NOT NULL,
  PRIMARY KEY (account_id, symbol)
);
```

---

## API Design

```
# Order management
POST /orders
{
  "symbol": "AAPL",
  "side": "BUY",
  "type": "LIMIT",
  "price": 182.50,
  "quantity": 100,
  "timeInForce": "GTC",
  "clientOrderId": "my-order-001"   // idempotency key
}
→ 201 Created { "orderId": "uuid", "status": "PENDING" }

DELETE /orders/{orderId}            → cancel (if in OPEN or PARTIAL status)
GET    /orders/{orderId}            → order status
GET    /accounts/{id}/orders?status=OPEN&symbol=AAPL
GET    /accounts/{id}/trades?symbol=AAPL&from=2026-04-01

# Market data (REST for snapshots)
GET /market/{symbol}/orderbook      → L2 snapshot (top 20 levels each side)
GET /market/{symbol}/trades         → recent trades (last 50)
GET /market/{symbol}/ohlc?period=1d → candlestick data

# Market data (WebSocket for streaming)
WS /market/{symbol}/stream          → subscribe to L1/L2/trades in real time
WS /accounts/{id}/orders            → personal order/fill notifications
```

---

## Edge Cases and Failure Scenarios

**1. Matching engine crash (in-memory state loss)**
The order book lives in memory. If the matching engine process crashes, the order book is lost. Recovery strategy: replay orders from the OMS database in `OPEN` or `PARTIAL` status, sorted by `placed_at` ascending. This rebuilds the order book to its pre-crash state. The recovery time depends on the number of open orders; for most symbols, this is under 1 second. During recovery, the circuit breaker blocks new orders for this symbol.

**2. Duplicate order submission (client retries on timeout)**
The `client_order_id` field is unique per account. If a client submits the same order twice (network retry), the second insert fails with a duplicate key error, and the API returns the original order's status. This is idempotent behavior at the API gateway level.

**3. Fat finger order (wrong price or quantity)**
The 20% price deviation check in the risk engine catches most fat finger errors. Additionally, maximum order size limits per account prevent absurdly large quantity mistakes. Some exchanges require a secondary confirmation for orders above a threshold value (e.g., orders worth more than $1M).

**4. Self-trade prevention**
An account should not fill its own buy order against its own sell order (wash trading — illegal in many jurisdictions). The matching engine checks: if the incoming order's `account_id` matches the resting order's `account_id`, skip that resting order (or cancel both, depending on the configured STP action).

**5. Price level with thousands of orders (queue depth)**
If a highly liquid price level has 10,000 orders, walking through them during a large market order takes time. The matching engine handles this efficiently because ArrayDeque.poll() is O(1) per order. Worst case: a market order consuming 10,000 resting orders processes them sequentially in the single-threaded event loop — this might take 1-2ms for the largest orders. Circuit breakers prevent runaway cascading fills.

---

## Tech Stack

| Component | Technology | Why |
|---|---|---|
| Matching Engine | Java 17 (JVM with GC tuning) + LMAX Disruptor | Lock-free ring buffer, single-threaded per symbol |
| Order Store | PostgreSQL | ACID, idempotency via unique client_order_id |
| Trade Store | Apache Cassandra | Time-series, high write throughput, append-only |
| Market Data | Kafka → WebSocket (Spring WebFlux) | Fan-out to thousands of subscribers |
| Account/Risk | PostgreSQL + in-memory cache | ACID for balance, cache for sub-millisecond risk checks |
| Network Protocol | FIX protocol for institutional traders; REST + WebSocket for retail | Industry standard for institutional connectivity |
| JVM tuning | -XX:+UseZGC, -XX:MaxGCPauseMillis=1, CPU affinity pinning | Minimize GC pauses and OS scheduling jitter |

---

## Interview Q&A

**Q1: Why is a single-threaded matching engine per symbol faster than a multi-threaded one?**

Multi-threading introduces two performance killers: lock contention and cache invalidation. When multiple threads share access to the order book, they need synchronization (locks or CAS operations), and every cache line written by one thread must be invalidated in other threads' CPU caches. At 100k orders/second, these costs dominate. A single-threaded engine has zero synchronization overhead and keeps the entire order book (typically 4-8MB for a hot symbol) in L1/L2 cache across many operations. This cache residency effect is a multiplier — processing an order that touches warm cached data is 10-100x faster than processing one that causes cache misses. LMAX Exchange demonstrated this empirically in their original disruptor paper (2011): a single-threaded ring buffer consumer outperformed a multi-threaded approach by 2-3 orders of magnitude at similar hardware.

**Q2: How does the LMAX Disruptor achieve sub-microsecond latency? What makes it fundamentally different from a BlockingQueue?**

Four key differences: (1) Pre-allocation eliminates GC — ring buffer slots are allocated once at startup; producers fill pre-existing objects rather than creating new ones. No allocation means no garbage collection. (2) No locks — the producer claims the next sequence number with a CAS operation (single producer: no CAS at all, just increment). The consumer reads the sequence counter; if it matches the published sequence, the event is ready. No lock acquisition, no context switching. (3) Cache line padding — the producer sequence, consumer sequence, and ring buffer entries are placed in separate cache lines (64 bytes) to prevent false sharing. Without padding, updating the producer counter would invalidate the cache line containing the consumer counter, causing unnecessary cache misses. (4) Wait strategy — YieldingWaitStrategy spins (consuming CPU) for maximum throughput, then yields. No OS scheduling overhead, no wakeup latency. This combination — no allocation, no locks, no false sharing, no OS involvement — achieves deterministic sub-microsecond latency.

**Q3: How does price-time priority work, and what data structure enforces it?**

Price-time priority means: among all orders to buy, the one willing to pay the highest price gets priority. Among orders at the same price, the one that arrived earliest gets priority. The TreeMap enforces price priority: for bids, it is sorted in reverse order so `firstEntry()` always returns the highest price. For asks, natural order makes `firstEntry()` the lowest price. Within each price level, the ArrayDeque enforces time priority: new orders go to the back with `offer()`, and filled orders are removed from the front with `poll()`. This is a strict FIFO queue at each price level. The composite data structure gives O(log P) to find the best price level and O(1) to access the next order in time order — exactly what price-time priority requires, with no additional comparison or sorting at match time.

**Q4: How do you handle order cancellations efficiently? An order deep in the order book needs to be removed.**

There are two approaches. Eager deletion: when a cancel request arrives, find the order in the orderIndex HashMap (O(1)), then search for it in the ArrayDeque at its price level and remove it (O(n) in the worst case where n is the number of orders at that price level). For price levels with thousands of orders, this is slow. Lazy deletion: mark the order as CANCELLED in the orderIndex without removing it from the ArrayDeque. When the matching engine encounters a cancelled order during matching (order.isCancelled() check), it simply skips it and polls the next order. The lazy deletion cost is distributed across matching events rather than concentrated in one cancellation operation. Production exchanges typically use lazy deletion for its constant-time cancellation acknowledgment, with periodic cleanup sweeps to remove accumulated cancelled orders from the queues.

**Q5: What are the implications of market orders when the book is thin (few resting orders)?**

A market order with no price limit will consume all available resting orders regardless of price. In a thin book, this causes extreme slippage — a buy market order for 1000 shares might execute at prices ranging from $182.60 to $200.00 as it walks up the ask side. Most exchanges protect against this with a "market order protection price" — the system only fills the market order at prices within N% of the last trade price. Orders that would fill beyond this bound are rejected or partially filled. For retail investors, this is particularly important: a market order during pre-market hours (when the book is very thin) can result in catastrophically bad execution prices. This is why sophisticated traders almost always use limit orders.

**Q6: How do you prevent a "flash crash" scenario where algorithmic trading causes prices to collapse in milliseconds?**

Multiple layers of protection: (1) Per-symbol circuit breakers that halt trading when price moves more than 5% in 5 minutes. (2) Per-account order rate limits that prevent a single algorithm from submitting thousands of orders per second. (3) "Clearly erroneous trade" rules — the exchange can bust (cancel) trades that executed at prices obviously outside fair value. (4) LULD (Limit Up-Limit Down) bands — the exchange calculates a reference price (e.g., 5-minute average) and only allows trades within ±5% of that reference. Orders outside the band are rejected at the risk engine. These are actual rules from the 2012 SEC Market Access Rule (Rule 15c3-5), introduced after the 2010 Flash Crash. When circuit breakers trigger, a cooling-off period allows market participants to reassess and re-quote, preventing the cascade from continuing.

**Q7: How does your system guarantee exactly-once trade execution (no duplicate trades)?**

The single-threaded matching engine processes one event at a time from the ring buffer. Since there is no concurrency within the engine, a single order can only trigger one set of trades — the engine processes it and moves to the next event. The ring buffer guarantees each event is delivered to the consumer exactly once (single consumer, sequential sequence numbers).

For persistence: trades are written to Cassandra with a UUID trade ID. If the write fails and retries, the UUID idempotency in Cassandra prevents duplicate rows. For settlement: account balance updates use optimistic concurrency (expected version) — if a settlement fails and retries, the version check detects the duplicate and skips it. The combination of single-threaded execution, UUID trade IDs, and optimistic concurrency in settlement provides exactly-once semantics end-to-end.

**Q8: How would you design the stop order activation mechanism?**

Stop orders require monitoring last trade prices and activating when the trigger condition is met. Approach: maintain a sorted data structure of pending stop orders per symbol, indexed by stop price. When a trade executes at price P, check two collections: (1) sell stop orders with stop_price >= P (price fell to trigger), (2) buy stop orders with stop_price <= P (price rose to trigger). Activated stop orders are immediately submitted as market orders (or limit orders for stop-limit) to the matching engine via the same ring buffer. This check runs in the matching engine thread after each trade — no additional synchronization needed. Data structure: use two TreeMaps per symbol (one for sell stops, one for buy stops) with stop_price as key and order IDs as values. Range queries on these TreeMaps find all activated stops in O(log P + K) where K is the number of activated stops.

**Q9: How would you design the settlement process to update account balances after trades?**

Settlement updates two accounts (buyer and seller) atomically for each trade: debit buyer's cash and credit shares; credit seller's cash and debit shares. The challenge is doing this correctly when settlement happens asynchronously after the trade executes.

Approach: publish trades to a settlement Kafka topic. A settlement consumer reads trades in order and processes them with optimistic concurrency against the accounts table. Each account has a `version` column. The settlement update: `UPDATE accounts SET cash_balance = cash_balance - tradeValue, version = version + 1 WHERE id = buyerAccountId AND version = expectedVersion`. If the version doesn't match (another settlement updated this account concurrently), retry.

Critical constraint: buy-side settlement (debit cash) must happen before sell-side settlement (credit cash and shares), in case the buyer checks their balance between the two. Most exchanges use a T+2 or T+1 settlement cycle — real-time settlement is only for crypto exchanges. Traditional exchanges separate trade execution (microseconds) from settlement (day-end batch processing).

**Q10: How do you handle a scenario where the matching engine processes 100,000 orders per second but the database can only handle 50,000 writes per second?**

The matching engine writes two types of data: order status updates and trade records. The solution is asynchronous persistence with backpressure. The matching engine writes to the Disruptor output ring buffer at full speed. A persistence handler consumes from this buffer and batches writes to the database — instead of writing each trade individually, it accumulates 1000 trades and writes them in a single batch insert. Cassandra is particularly good at batch writes and easily handles 100k+ writes/second with proper replication and compaction settings. For PostgreSQL (order status): use COPY command or batch JDBC updates to maximize throughput. If the persistence layer falls behind, the ring buffer fills up — this is the backpressure mechanism. When the ring buffer is full, the Order Gateway blocks (or rejects new orders), which is the appropriate response when the system is overloaded.

**Q11: How would you implement "market hours" — only allowing trading during certain hours?**

Trading hours are enforced at the Order Gateway layer (before orders reach the matching engine). The gateway checks the current time against the trading schedule for the symbol's exchange. For pre-market and after-hours trading, a subset of order types may be allowed (typically limit orders only, no market orders, with tighter price bands). Implementation: a `TradingSessionManager` service maintains the current session state (PRE_MARKET, OPEN, POST_MARKET, CLOSED) per symbol. It transitions states based on wall clock time (with timezone awareness for international symbols). The risk engine checks the session state and rejects orders with specific reason codes for each session. At market open, any orders received during pre-market that weren't matched are fed into the opening auction — a special matching mechanism that determines the opening price by finding the price that maximizes traded volume.

**Q12: How do you test the matching engine for correctness?**

Testing a matching engine requires verifying price-time priority, correct trade price calculation, correct fill quantities, proper handling of partial fills, and order type semantics. The testing approach:

Property-based testing: generate random sequences of orders and verify invariants — the bid/ask spread is always non-negative, total filled quantities equal total trade quantities, no unfilled orders at prices that should match. Use a tool like QuickCheck (JQwik in Java).

Simulation testing: build a reference implementation (simple, correct, slow) and compare its output against the optimized implementation for the same input sequence. If they produce the same trades for all inputs, the optimized implementation is correct.

Replay testing: capture production order sequences and replay them against the matching engine, comparing outputs. This catches bugs that random testing misses.

Concurrency testing: since the matching engine is single-threaded, concurrency bugs are impossible inside the engine. Test the thread safety of the Disruptor boundary (multiple threads publishing orders) with stress tests and tools like jcstress.

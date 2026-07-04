# Electronic Trading Demo — Java

A working implementation of the core components found inside a front-office trading system: order book, matching engine, pre-trade risk, market making strategy, and a low-latency Disruptor pipeline — exposed over a Spring Boot REST API with Swagger UI.

---

## Run

```bash
# REST API (Swagger UI at http://localhost:8080/swagger-ui/index.html)
mvn spring-boot:run

# Original console demo (6 scenarios, no Spring)
mvn compile exec:java -Dexec.mainClass="com.trading.demo.TradingSystemDemo"

# Tests
mvn test
```

---

## Architecture

```
REST Client / Algo Strategy
        │
        ▼
 OrderController  (Spring REST layer)
        │
        ▼
 OrderManagementSystem  (OMS — orchestrates the flow)
        │
        ├──► RiskEngine         (pre-trade risk gate)
        │
        └──► OrderBook          (CLOB matching engine)
                   │
                   └──► Trade   (fill record)

 DisruptorOrderPipeline  (lock-free async path — alternative to OMS direct call)
   RingBuffer → RiskCheckHandler → OMSHandler
```

---

## Components — Trading Concepts

### 1. Order (`model/Order.java`)

Represents a single instruction to buy or sell an instrument. Models the key FIX Protocol fields:

| Field | FIX Tag | Meaning |
|---|---|---|
| `clientOrderId` | 11 | Your reference — exchange echoes it back |
| `symbol` | 55 | Instrument (AAPL, MSFT...) |
| `side` | 54 | BUY or SELL |
| `orderType` | 40 | LIMIT or MARKET |
| `price` | 44 | Limit price (0 for MARKET) |
| `quantity` | 38 | Number of shares |

**Lifecycle states** (mirrors FIX ExecutionReport OrdStatus field 39):

```
NEW → PENDING_NEW → ACCEPTED → PARTIALLY_FILLED → FILLED
                                                 → CANCELLED
                                                 → REJECTED
```

**Key implementation detail:** uses `volatile` for `status`, `filledQuantity`, `avgFillPrice` — visibility across threads without a lock. `avgFillPrice` is recalculated on every partial fill using a weighted average:

```java
avgFillPrice = (avgFillPrice * filledQuantity + fillPrice * fillQty) / newFilled;
```

---

### 2. Order Book / CLOB (`market/OrderBook.java`)

**CLOB = Central Limit Order Book.** All major exchanges (NYSE, Euronext, LSE) operate a CLOB. It maintains two sorted sides:

```
ASKS (sell orders):
  152.00  500    ← highest ask
  151.50  300
  151.00  200    ← best ask (lowest)
  ─── spread ───
  150.00  200    ← best bid (highest)
  149.50  300
  149.00  500    ← lowest bid
BIDS (buy orders):
```

**Matching rule: price-time priority (FIFO)**
1. Best price matches first (highest bid vs lowest ask)
2. At the same price, first-in order fills first

**Implementation:** `TreeMap<Double, Deque<Order>>`
- `TreeMap` gives O(log n) price-sorted access — best bid/ask is always `firstKey()`
- `Deque<Order>` at each price level preserves arrival order (FIFO)

**When does a match occur?**
- An incoming BUY limit order matches if `bestAsk <= buyPrice`
- An incoming SELL limit order matches if `bestBid >= sellPrice`
- MARKET orders match unconditionally at whatever price is available

**Partial fills:** if incoming quantity > resting quantity, the incoming order sweeps through multiple price levels until filled or no more matching orders remain. Each level consumed generates one `Trade`.

---

### 3. Trade (`model/Trade.java`)

A `Trade` is created for every match. It records:
- `buyOrderId` / `sellOrderId` — both sides of the match
- `quantity` / `price` — what was executed
- `notional` = quantity × price — the cash value of the trade

In FIX terms, this corresponds to an **ExecutionReport (35=8)** with `ExecType=F` (Fill).

In a real system, every Trade is:
1. Journaled to a trade-store (Sybase IQ / Oracle)
2. Published to a message bus (Kafka / Solace) for downstream consumers (risk, P&L, settlement)
3. Reported to the exchange for clearing

---

### 4. Risk Engine (`risk/RiskEngine.java`)

**Pre-trade risk** = validation that runs *before* every order reaches the exchange. Required by MiFID II (Europe) and Dodd-Frank (US). Four checks, ordered cheapest → most expensive (fail-fast):

| # | Check | Limit | Why it exists |
|---|---|---|---|
| 1 | **Notional** | $1M per order | Prevents accidentally sending an order 1000x too large |
| 2 | **Fat-finger** | ±10% from market | Catches typos — e.g. 1500.00 instead of 150.00 |
| 3 | **Position limit** | ±100k shares | Controls max long/short exposure per symbol |
| 4 | **Rate limit** | 100 orders/sec | Prevents runaway algo flooding the exchange |

**Position tracking:** net position per symbol is tracked in `ConcurrentHashMap<String, AtomicLong>`. Positive = long, negative = short. Updated on every fill.

**In a real bank:** limits are stored in a risk management database, differ per trader/desk/book, and are hot-reloaded without restart. Breaches trigger alerts to the risk desk and may force cancel-on-disconnect.

Risk limits are externalised in this project — configure in `application.yml`:

```yaml
trading:
  risk:
    max-notional-per-order: 1_000_000
    max-position-per-symbol: 100_000
    max-price-deviation-pct: 0.10
    max-orders-per-second: 100
```

---

### 5. Order Management System — OMS (`service/OrderManagementSystem.java`)

The OMS is the central hub. Every order passes through it:

```
submitOrder(order)
    ├── riskEngine.check(order)       ← fail fast if rejected
    ├── order.setStatus(ACCEPTED)
    ├── orderBook.addOrder(order)     ← matching engine
    ├── riskEngine.updatePosition()   ← update net position on fills
    └── listener.onExecution()        ← notify downstream (REST response, Kafka...)
```

**In a real bank** there are multiple OMS tiers:
- **Buy-side OMS** — used by portfolio managers / algo desks to manage orders across many venues
- **Sell-side OMS** — routes client orders to exchanges, handles FIX connectivity
- **Exchange OMS** — the matching engine itself (this project models the latter two)

---

### 6. Market Making Strategy (`engine/MarketMaker.java`)

A **market maker** continuously quotes both a bid and an ask simultaneously. Profit comes from capturing the **spread** (sell at ask, buy at bid).

```
Mid price = 140.00
halfSpread = 0.25

→ Quote BID at 139.75 (buy from clients)
→ Quote ASK at 140.25 (sell to clients)

If both sides fill: profit = 0.50 per share × quoteSize
```

**The `requote` cycle:**
1. Cancel existing bid/ask quotes
2. Calculate new bid = mid − halfSpread, ask = mid + halfSpread
3. Submit new limit orders

**Key risk for market makers: adverse selection / inventory risk**
- If price moves up sharply, the bid fills but the ask does not → long position at a bad price
- Real market makers skew quotes based on inventory (lean away from the exposure) and widen spread in volatile markets
- This demo uses fixed spread — a real implementation would use volatility-adjusted spreads

---

### 7. LMAX Disruptor Pipeline (`engine/DisruptorOrderPipeline.java`)

Standard Java concurrency uses `BlockingQueue` between threads. For low-latency trading this is too slow — `synchronized` causes context switches and GC pressure from object allocation.

**The Disruptor replaces BlockingQueue with a pre-allocated ring buffer:**

```
Producer (Order publisher)
       │
       ▼
 ┌──────────────────────────────────┐
 │  RingBuffer [1024 pre-allocated  │
 │  OrderEvent slots]               │
 └──────────────────────────────────┘
       │                │
       ▼                ▼
 RiskCheckHandler   (runs first)
       │
       ▼
 OMSHandler         (runs after risk)
```

**Why it's fast:**
- **No allocation on hot path** — `OrderEvent` objects are created once at startup and reused
- **No locks** — sequence numbers use CAS (compare-and-swap), not `synchronized`
- **CPU cache-friendly** — ring buffer is a contiguous array; sequential access = cache hits
- **BusySpinWaitStrategy** — handlers spin instead of blocking (burns CPU, lowest latency)

**In production**, LMAX Exchange processes 6M+ orders/second with this pattern. JPMorgan, Goldman Sachs, and other banks use Disruptor in their order routing and risk systems.

**Ring buffer size must be a power of 2** — this allows `sequence % bufferSize` to be replaced by the faster bitwise `sequence & (bufferSize - 1)`.

---

## REST API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/orders` | Submit order — returns status + fills |
| `DELETE` | `/api/orders/{id}` | Cancel resting order |
| `GET` | `/api/orders/{id}` | Get order status |
| `GET` | `/api/positions/{symbol}` | Net position (LONG / SHORT / FLAT) |
| `GET` | `/api/orderbook/{symbol}` | Best bid, best ask, spread, mid-price |
| `PUT` | `/api/market-prices/{symbol}` | Set reference price (seeds fat-finger check) |
| `GET` | `/actuator/health` | Health check |

**Swagger UI:** `http://localhost:8080/swagger-ui/index.html`

**Quick demo sequence:**
```bash
# 1. Seed market price
curl -X PUT localhost:8080/api/market-prices/AAPL \
  -H "Content-Type: application/json" -d '{"price": 150.00}'

# 2. Submit a resting limit buy
curl -X POST localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"clientOrderId":"B1","symbol":"AAPL","side":"BUY","orderType":"LIMIT","price":149.50,"quantity":100}'

# 3. Submit a matching sell — response will contain the fill in trades[]
curl -X POST localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"clientOrderId":"S1","symbol":"AAPL","side":"SELL","orderType":"LIMIT","price":149.50,"quantity":100}'

# 4. Check net position
curl localhost:8080/api/positions/AAPL
```

---

## Interview Q&A — Key Trading Concepts

These are the questions commonly asked in front-office Java developer interviews at banks and trading firms.

### Order Book

**Q: What is price-time priority?**
At a given price level, the order that arrived *first* gets filled first (FIFO). If two orders both want to buy at $150.00, the one submitted earlier takes priority. This is the standard rule on most exchanges. Some exchanges also support pro-rata allocation.

**Q: What is the difference between passive and aggressive orders?**
- **Passive (maker):** rests in the book waiting to be matched. Adds liquidity. Usually earns a maker rebate from the exchange.
- **Aggressive (taker):** immediately matches against resting orders. Removes liquidity. Usually pays a taker fee.

A LIMIT order can be either — it's passive if it doesn't immediately match, aggressive if it does. A MARKET order is always aggressive.

**Q: What happens to a MARKET order if the book is empty?**
It cannot be filled and is either rejected or expired (depending on exchange rules). This is why IOC (Immediate Or Cancel) and FOK (Fill Or Kill) order types exist.

**Q: What is the spread and why does it matter?**
`spread = bestAsk - bestBid`. It's the cost of immediately buying and selling. Tight spreads mean liquid markets. Wide spreads mean illiquid or volatile markets. Market makers profit from the spread; takers pay it.

---

### Risk

**Q: What is pre-trade risk vs post-trade risk?**
- **Pre-trade:** validation *before* the order reaches the exchange — notional limits, fat-finger, position limits, rate limits. Prevents sending bad orders.
- **Post-trade:** P&L calculation, trade reporting, regulatory reporting (MiFID II, EMIR), settlement confirmation. Runs after execution.

**Q: What is a fat-finger check?**
Detects orders where the price deviates significantly from the current market price — a sign of a typo. E.g. if AAPL is at $150 and you enter $1500, the check rejects it. Threshold in this project: 10% deviation.

**Q: What is a position limit?**
The maximum net exposure allowed on a single instrument (e.g. ±100k shares). If you are already long 80k AAPL and try to buy another 30k, the risk engine rejects the order because the resulting 110k position exceeds the limit.

---

### Low-Latency Java

**Q: Why use `volatile` on Order fields instead of `synchronized`?**
`volatile` guarantees visibility (changes are flushed to main memory immediately) without mutual exclusion. For fields like `status` and `filledQuantity` that are written by one thread and read by another, it's sufficient and cheaper than a lock.

**Q: Why use primitives (`double`, `long`) instead of `BigDecimal` for prices?**
GC. `BigDecimal` allocates heap objects on every operation. In a system processing thousands of orders per second, this creates constant GC pressure. Primitives live on the stack — zero allocation. The trade-off is floating-point precision, managed by rounding at display boundaries.

**Q: Why is the Disruptor ring buffer size a power of 2?**
It allows replacing the modulo operation `sequence % bufferSize` with the faster bitwise AND `sequence & (bufferSize - 1)`. At millions of events per second, this matters.

**Q: What is false sharing and how does the Disruptor avoid it?**
When two threads write to different variables that happen to sit on the same CPU cache line (64 bytes), every write by one thread invalidates the other's cache — even though they're not sharing data. The Disruptor pads its sequence numbers with extra bytes to ensure each lives on its own cache line.

**Q: What is BusySpinWaitStrategy and when do you use it?**
Instead of blocking a thread when no events are available, it spins in a tight loop consuming CPU. This eliminates thread scheduling latency (which can be 10–50 µs on Linux). Used in HFT when you have dedicated CPU cores and latency is more important than CPU efficiency. `YieldingWaitStrategy` and `BlockingWaitStrategy` are lower-latency alternatives that don't burn CPU.

---

### Market Microstructure

**Q: What is adverse selection?**
When a market maker gets filled, it's often because the market is about to move against them. If a smart counterparty (an HFT with better information) hits a market maker's bid, it's usually because they know the price is about to fall. The market maker bought at a bad time — they were "adversely selected".

**Q: What is inventory risk for a market maker?**
If the market moves in one direction, the market maker accumulates a one-sided position (all bids fill, no asks fill). They're now long in a falling market or short in a rising market. Real market makers hedge inventory risk by skewing quotes (move bid and ask in the direction that reduces the position).

**Q: What is the difference between DMA and algorithmic trading?**
- **DMA (Direct Market Access):** client sends orders directly to exchange through the broker's infrastructure. No intervention. Fast but manual.
- **Algorithmic trading:** software decides when and how to slice a large order (TWAP, VWAP, POV). Reduces market impact.
- **HFT (High-Frequency Trading):** algorithms that profit from tiny edges at very high speed — market making, arbitrage, latency arbitrage. Holding period: microseconds to seconds.

---

## What this project is missing vs production

| Missing | Real system |
|---------|-------------|
| FIX connectivity | QuickFIX/J sending `NewOrderSingle (D)` and receiving `ExecutionReport (8)` |
| Persistence | Every order and trade journaled to Sybase IQ / PostgreSQL |
| Kafka events | `TradeEvent` published after each fill for P&L, risk, settlement consumers |
| P&L service | Realized P&L on closed positions, unrealized on open positions |
| Multi-venue routing | SOR (Smart Order Routing) — route to best venue by price/liquidity |
| Market data feed | Bloomberg B-PIPE / Refinitiv TREP subscription replacing the manual price seed |
| Algo strategies | TWAP, VWAP, Implementation Shortfall, POV |
| Authentication | Trader identity, book permissions, role-based access |
| Audit trail | Immutable log of every order event (MiFID II requirement) |

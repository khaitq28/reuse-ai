# Electronic Trading Demo — Java

A hands-on demo project covering the core components of an electronic trading system.

## What's inside

| Component | Class | Concept |
|---|---|---|
| Order model | `model/Order.java` | FIX order fields, lifecycle states |
| Order book | `market/OrderBook.java` | CLOB, price-time priority matching |
| Risk engine | `risk/RiskEngine.java` | Pre-trade checks: notional, fat-finger, position |
| OMS | `service/OrderManagementSystem.java` | Order routing, position tracking |
| Market Maker | `engine/MarketMaker.java` | Quote strategy, spread capture |
| Disruptor | `engine/DisruptorOrderPipeline.java` | LMAX ring buffer, lock-free pipeline |
| Latency | `util/LatencyTimer.java` | Nanosecond latency measurement |
| Demo runner | `TradingSystemDemo.java` | 6 runnable scenarios |

## Run

```bash
mvn compile exec:java -Dexec.mainClass="com.trading.demo.TradingSystemDemo"
```

## Test

```bash
mvn test
```

## Scenarios

1. **Order Lifecycle** — limit buy, partial fill, full fill with market order
2. **Order Book Matching** — multi-level book, aggressive sweep
3. **Risk Checks** — fat finger, notional limit, position limit
4. **Market Making** — requoting on price updates, spread capture
5. **Disruptor Pipeline** — ring buffer async processing
6. **Latency Measurement** — nanosecond timing of order submission

## Key Concepts

- **Price-Time Priority**: orders at same price matched in arrival order (FIFO)
- **Passive vs Aggressive**: resting orders are passive; incoming orders are aggressive
- **Pre-trade risk**: runs before every order is sent to the exchange
- **LMAX Disruptor**: ring buffer replaces `BlockingQueue` — no locks, no GC overhead

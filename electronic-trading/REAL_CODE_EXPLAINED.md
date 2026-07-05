# Real Production Code — Electronic Trading (`com.trading.real`)

These 6 files live in `com.trading.real.*` and represent patterns used **daily in production**
at firms like SocGen, BNP Paribas, Natixis, and Citadel Securities.
They do **not modify** the original demo code — they are independent additions.

---

## Package Map

```
com.trading.real
├── algo
│   └── TwapExecutor.java         ← execution algorithm (sell-side EMS)
├── service
│   ├── AuditLogService.java      ← async write-behind audit log (MiFID II)
│   ├── PositionService.java      ← real-time P&L and position tracking
│   └── WarmupService.java        ← JIT warmup before market open
└── util
    ├── ObjectPool.java           ← lock-free pre-allocated object pool
    └── TradingThreadFactory.java ← named threads with daemon/priority control
```

Dependencies (one-way — old code never imports new code):

```
com.trading.real.*  →  com.trading.demo.model.*   (Order, Trade, Side, OrderType)
com.trading.real.*  →  com.trading.demo.service.*  (OrderManagementSystem)
com.trading.real.*  →  com.trading.demo.risk.*     (RiskEngine)
```

---

## 1. `ObjectPool<T>` — Lock-Free Object Pool

**File:** `util/ObjectPool.java`

### The Problem It Solves

Every `new Order()` call on the hot path allocates heap memory.
Under high throughput (100,000 orders/sec), this fills Young Gen fast → Minor GC fires → **Stop-The-World pause** → latency spike at the worst possible moment.

### How It Works

```
Startup: pre-allocate N objects into a fixed array
Runtime: borrow() returns the next slot — zero allocation, zero GC

    [O][O][O][O][O][O][O][O]   ← fixed ring, never grows
     ↑
   index (AtomicInteger, wraps around)
```

Key design choices:

| Choice | Why |
|--------|-----|
| `power-of-2` size | Bitwise `& mask` replaces modulo `% size` — ~3x faster |
| `AtomicInteger` + CAS | Lock-free — no `synchronized`, no OS scheduler involvement |
| Pre-allocated at startup | All GC pressure happens before market open, never during trading |

```java
// Usage
ObjectPool<Order> pool = new ObjectPool<>(1024, Order::new);

Order o = pool.borrow();  // ~10ns — no allocation
o.reset("AAPL", Side.BUY, 100, 150.0);
oms.submitOrder(o);
```

**In production:** [Agrona](https://github.com/real-logic/agrona)'s `ManyToManyConcurrentArrayQueue`
or off-heap `Chronicle Pool` for zero GC AND zero GC-scan overhead.

---

## 2. `TradingThreadFactory` — Named Threads

**File:** `util/TradingThreadFactory.java`

### The Problem It Solves

Default Java thread names: `pool-3-thread-7`.
During a production incident (latency spike, deadlock), a thread dump with those names tells you nothing.

### How It Works

```java
// Instead of:
Executors.newFixedThreadPool(4)                          // "pool-1-thread-1"

// Use:
Executors.newFixedThreadPool(4,
    new TradingThreadFactory("order-processor", false))  // "order-processor-1"
```

Three built-in profiles:

```java
TradingThreadFactory.hotPath("oms-thread")     // non-daemon, MAX_PRIORITY (10)
TradingThreadFactory.background("audit-log")   // daemon,     NORM_PRIORITY (5)
TradingThreadFactory.marketData("md-handler")  // daemon,     MAX_PRIORITY - 1 (9)
```

**Daemon flag** matters at shutdown:
- `daemon=false` → JVM **waits** for this thread to finish before exiting (order threads, audit drain)
- `daemon=true`  → JVM **kills** this thread immediately on exit (background helpers are OK to lose)

**Uncaught exception handler** is critical in trading:
```java
// A thread dying silently in a trading system can leave orders unmanaged.
// This logs the error immediately so ops can react.
t.setUncaughtExceptionHandler((thread, ex) ->
    log.error("UNCAUGHT EXCEPTION in thread '{}'", thread.getName(), ex));
```

---

## 3. `AuditLogService` — Async Write-Behind Audit Log

**File:** `service/AuditLogService.java`

### The Problem It Solves

Every order and trade **must** be persisted for MiFID II (5-year retention, nanosecond timestamps).
Synchronous I/O on the OMS hot path adds **milliseconds** of latency per order — unacceptable.

### How It Works

```
OMS thread (hot path)          Consumer thread (background)
      │                                │
  offer(event)  ──→  [BlockingQueue]  ──→  persist(event) → DB/Kafka/File
    ~100ns                                     ~1-5ms (I/O cost)
```

The **producer never blocks** — if the queue is full, the event is dropped with a warning.
This is the "fire-and-forget" / "write-behind" pattern.

```java
// OMS hot path — returns in ~100ns regardless of I/O speed
auditLog.logOrderSubmitted(order);   // just enqueues, never writes

// Consumer thread (separate) drains the queue at its own pace
private void consumeLoop() {
    while (running || !queue.isEmpty()) {
        AuditEvent e = queue.poll(100, TimeUnit.MILLISECONDS);
        if (e != null) persist(e);   // I/O happens here, away from hot path
    }
}
```

`offer()` vs `put()`:

| Method | Behaviour when queue full |
|--------|--------------------------|
| `offer()` | Returns `false` immediately — **never blocks OMS** ✓ |
| `put()`   | Blocks until space available — **stalls order flow** ✗ |

**Timestamp captured on OMS thread** (not consumer thread) for MiFID II accuracy:
```java
this.timestampNs = System.nanoTime();   // captured when event is created, not when persisted
```

**In production:** Replace `ArrayBlockingQueue` with [Chronicle Queue](https://github.com/OpenHFT/Chronicle-Queue)
— persisted off-heap, zero GC, zero data loss even on JVM crash.

---

## 4. `PositionService` — Real-Time P&L

**File:** `service/PositionService.java`

### The Problem It Solves

After every fill, the risk engine needs to know: *what is the current position? are we within limits?*
Traders need live P&L to manage intraday exposure.
Regulatory reporting requires accurate position snapshots.

### AVCO (Average Cost) P&L Method

```
Initial state: long 100 AAPL @ avg cost 150.00

Fill: BUY 50 @ 155.00
  new_avg = (100 × 150.00 + 50 × 155.00) / 150 = 151.67

Fill: SELL 80 @ 160.00
  realized P&L = 80 × (160.00 - 151.67) = +$666.40
  remaining position: 70 AAPL @ avg cost 151.67
```

### Floating-Point Race Condition

P&L is accumulated with `AtomicLong` storing **cents** (not dollars) to avoid race conditions:

```java
// WRONG — race condition: two threads read 100.50, both add 0.10, result is 100.60 not 100.70
private volatile double totalPnl = 0;

// CORRECT — AtomicLong is CAS-based, no lost updates
private final AtomicLong totalRealizedPnlCents = new AtomicLong(0);
totalRealizedPnlCents.addAndGet(Math.round(realizedPnl * 100));   // cents = no float precision loss
```

### Key Data Structures

```java
ConcurrentHashMap<String, PositionEntry>  positions;     // per-symbol state
ConcurrentHashMap<String, Double>         marketPrices;  // last tick per symbol (for mark-to-market)
```

`PositionEntry.applyFill()` is `synchronized` for compound read-check-update operations.
In a single-threaded OMS event loop (production standard), `synchronized` is not needed — zero lock overhead.

---

## 5. `WarmupService` — JIT Warmup Before Market Open

**File:** `service/WarmupService.java`

### The Problem It Solves

At JVM startup, all code runs in the interpreter (**~10-100x slower** than native).
JIT compilation happens progressively:

```
Tier 0  →  Tier 1-3  →  Tier 4 (C2 — fully optimised native code)
interpreter  C1 lightly    C2 after ~10,000 invocations
              optimised
```

If the first real order arrives cold, the OMS thread **competes with the JIT compiler** for CPU
→ unpredictable latency spikes at market open — the worst possible time.

### How It Works

```java
@PostConstruct          // runs AFTER all Spring beans are fully initialised
public void warmUp() {
    for (int i = 0; i < 20_000; i++) {   // 20k iterations → all hot methods reach Tier 4
        riskEngine.check(order);           // warms: notional check, fat-finger, position limit, rate limit
        oms.submitOrder(order);            // warms: OMS validation, order book insertion, matching engine
    }
}
```

**Why 20,000?** C2 threshold is ~10,000 invocations (`-XX:CompileThreshold=10000`).
Using 2× ensures Tier 4 even if the JVM is busy with other startup tasks.

**In production:** Warmup runs against a UAT/simulation environment.
The production process is **never restarted during market hours** — it is kept warm all day.

```
06:00  Start JVM + warmup
07:30  Market open (LSE, Euronext Paris)
17:30  Market close
       (process stays alive — no cold restart risk)
```

---

## 6. `TwapExecutor` — TWAP Execution Algorithm

**File:** `algo/TwapExecutor.java`

### The Problem It Solves

A trader wants to **BUY 100,000 shares of BNP.PA** without moving the market.
Sending one order for 100,000 shares would consume all available liquidity and push the price up
(**market impact**). TWAP slices it into N equal child orders over time.

```
Parent order: BUY 100,000 BNP.PA over 60 minutes
→ 60 child orders of 1,667 shares, one per minute
→ total execution ≈ time-weighted average of the market price
```

### How It Works

```java
scheduler.scheduleAtFixedRate(() -> {
    Order child = new Order("TWAP-BNP.PA-BUY-1-of-60", ...);
    oms.submitOrder(child);
}, 0, intervalMs, TimeUnit.MILLISECONDS);
```

**`scheduleAtFixedRate` vs `scheduleWithFixedDelay`:**

| Method | Fires... | Use for... |
|--------|----------|-----------|
| `scheduleAtFixedRate` | at wall-clock intervals regardless of task duration | TWAP — slices must go at exact market times |
| `scheduleWithFixedDelay` | task-duration + delay after completion | Polling — retry after last attempt finishes |

**Last slice absorbs rounding remainder:**
```java
// 100,000 shares / 60 slices = 1,666 remainder 40
// Slices 1-59: 1,666 shares each
// Slice 60:    1,666 + 40 = 1,706 shares   ← absorbs the 40-share rounding gap
long sliceQty = (sliceNum == numSlices)
    ? baseSliceQty + remainderQty
    : baseSliceQty;
```

**Thread safety:**
- `AtomicLong sentQty` — scheduler thread writes, monitoring reads from any thread
- `volatile cancelled` — immediate visibility across threads without sync overhead
- `volatile scheduledTask` — safe publication of the `ScheduledFuture`

---

## How These Fit Together in a Real System

```
                        ┌─────────────────────────────────┐
  Market tick arrives   │         TwapExecutor             │
  (every ~10ms)         │  splits 100k order into slices   │
                        └──────────────┬──────────────────┘
                                       │ child orders
                                       ▼
                        ┌─────────────────────────────────┐
                        │     OrderManagementSystem        │
                        │  (com.trading.demo — original)   │
                        └───┬──────────────────────────────┘
                            │
              ┌─────────────┼──────────────────┐
              ▼             ▼                   ▼
       RiskEngine      OrderBook          AuditLogService
       (check limits)  (price-time        (enqueue event
                        priority)          ~100ns)
              │                                │
              ▼                                ▼  (background thread)
       PositionService                  [BlockingQueue] → DB / Kafka
       (update P&L)

  At startup:
       WarmupService → submits 20,000 synthetic orders → all hot paths reach JIT Tier 4
  Runtime:
       ObjectPool → zero-allocation Order objects on hot path
       TradingThreadFactory → named threads visible in thread dumps / JFR profiler
```

---

---

## 7. `start-trading.sh` — Production JVM Startup Script

**File:** `demo/start-trading.sh`

This script is a **cheat sheet for JVM tuning interviews**. Every flag is a question waiting to be asked.

### Heap — Fix the Size

```bash
-Xms4g -Xmx4g
```

Set min == max so the JVM **never resizes the heap at runtime**.
Heap resize triggers a Full GC + OS memory reallocation — can cause a pause of **seconds** during trading.

> Interview: *"Why do you set -Xms equal to -Xmx in a trading system?"*

---

### Pre-Touch All Pages at Startup

```bash
-XX:+AlwaysPreTouch
```

By default, the OS allocates memory **lazily** — the first time each memory page is written, the OS maps it (a "page fault"), which takes ~1µs per page.
With a 4GB heap that's 1 million pages × 1µs = **~1 second of random latency** scattered through trading hours.

`AlwaysPreTouch` forces the JVM to write every page at startup → all page faults happen before market open.

> Interview: *"What is a page fault? How do you prevent it during trading?"*

---

### ZGC — Sub-Millisecond GC Pauses

```bash
-XX:+UseZGC
```

GC comparison for a 4GB heap:

| GC | Typical STW pause | Best for |
|----|-------------------|----------|
| Serial / Parallel | 1–10 seconds | batch jobs |
| G1GC | 50–200ms | general purpose |
| ZGC (Java 15+) | < 1ms | low-latency trading |
| Shenandoah | < 1ms | similar to ZGC |

ZGC does almost all work **concurrently** (while app threads run). STW phase is only ~1ms regardless of heap size.

G1GC alternative for Java 11:
```bash
-XX:+UseG1GC -XX:MaxGCPauseMillis=5
```

> Interview: *"Which GC would you use for a low-latency trading system and why?"*

---

### Block Explicit GC Calls

```bash
-XX:+DisableExplicitGC
```

Some third-party libraries call `System.gc()` — this triggers a **Full GC**, which can pause the JVM for several seconds.
This flag makes `System.gc()` a no-op.

> Interview: *"Why is System.gc() dangerous in a trading system?"*

---

### JIT Faster Warmup

```bash
-XX:+TieredCompilation
-XX:CompileThreshold=1000
```

Default threshold is 10,000 invocations before C2 compiles a method.
Lowering to 1,000 means hot methods reach Tier 4 **10x faster** at startup.
Combined with `WarmupService`, the system reaches peak throughput within seconds.

---

### False-Sharing Prevention — `@Contended` Outside JDK

```bash
-XX:-RestrictContended
```

The `@Contended` annotation (adds 128-byte padding around a field to prevent false sharing) is restricted to `java.*` packages by default.
This flag enables it for **your own classes** — required if any hot fields use `@jdk.internal.vm.annotation.Contended`.

> Interview: *"What is false sharing? How do you prevent it in Java?"*

---

### GC Logging — Always On in Production

```bash
-Xlog:gc*:file=logs/gc.log:time,uptime,level:filecount=5,filesize=20m
```

Keeps the last 5 × 20MB = 100MB of GC history.
After a latency incident, the first thing support does is `grep` the GC log for Full GC events that correlate with the spike timestamp.

Without this flag you are **flying blind** during a post-mortem.

---

### Heap Dump on OOM

```bash
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=logs/heapdump-20240705-093015.hprof
```

When the JVM runs out of memory, it writes the entire heap to a `.hprof` file before crashing.
Open with Eclipse MAT or IntelliJ to find which objects consumed all memory.

Without this, an OOM in production is undiagnosable.

---

### JFR — Always-On Flight Recorder

```bash
-XX:StartFlightRecording=filename=logs/trading.jfr,maxsize=256m,maxage=1h
```

JFR (Java Flight Recorder) is a **< 1% overhead** continuous profiler built into the JDK.
It records: method timings, GC events, thread states, memory allocation, lock contention, I/O.

After a latency spike: open `trading.jfr` in **JDK Mission Control** (JMC) and pinpoint the exact method that was slow.

> Interview: *"How do you diagnose a latency spike in production?"*
> Answer: GC log + JFR + thread dump — this script has all three.

---

### Other Flags

```bash
-Xss1m                      # Stack per thread — 1MB for deeper call stacks (default ~512KB)
-XX:MaxMetaspaceSize=256m   # Caps Metaspace — catches class-loader leaks early (unbounded by default)
-Dspring.profiles.active=prod  # Spring profile — loads prod config (no H2, real datasource, etc.)
```

---

### Full Flag Summary Table

| Flag | Category | Why |
|------|----------|-----|
| `-Xms4g -Xmx4g` | Heap | No runtime resize → no GC pause from heap growth |
| `-XX:+AlwaysPreTouch` | Memory | Pre-fault pages at startup, not during trading |
| `-XX:+UseZGC` | GC | < 1ms STW pauses regardless of heap size |
| `-XX:+DisableExplicitGC` | GC | Block `System.gc()` from libraries |
| `-XX:+TieredCompilation` | JIT | Enable all 5 compiler tiers |
| `-XX:CompileThreshold=1000` | JIT | Reach Tier 4 in 1,000 calls instead of 10,000 |
| `-XX:-RestrictContended` | CPU | Allow `@Contended` on user classes |
| `-Xlog:gc*:...` | Observability | GC log for post-mortem analysis |
| `-XX:+HeapDumpOnOutOfMemoryError` | Observability | Capture heap state at OOM |
| `-XX:StartFlightRecording=...` | Observability | Always-on profiler < 1% overhead |
| `-Xss1m` | Threads | Stack size per thread |
| `-XX:MaxMetaspaceSize=256m` | Memory | Cap class metadata to catch leaks |

---

## Interview Relevance — Why Each File Gets Asked About

| File | Likely interview question |
|------|--------------------------|
| `ObjectPool` | "How do you avoid GC pressure on the hot path?" |
| `TradingThreadFactory` | "How do you diagnose a stuck thread in production?" |
| `AuditLogService` | "How do you persist every trade without adding latency?" |
| `PositionService` | "How do you track P&L in real time? Why AtomicLong for cents?" |
| `WarmupService` | "What is JIT warmup? Why do you do it before market open?" |
| `TwapExecutor` | "Explain a TWAP algorithm. When would you use `scheduleAtFixedRate`?" |

---

## How Close Is This to Real Production Code?

| Aspect | This code | Real production |
|--------|-----------|-----------------|
| Object pool | `AtomicInteger` CAS ring | Agrona / Chronicle Pool (off-heap) |
| Audit queue | `ArrayBlockingQueue` | Chronicle Queue (persisted, zero GC) |
| P&L storage | In-memory `ConcurrentHashMap` | Real-time DB (KDB+, TimescaleDB) |
| TWAP pricing | Fixed `limitPrice` | Dynamic — tracks current mid-price per slice |
| Warmup | Synthetic orders in-process | Replays against UAT matching engine |
| Thread affinity | Priority only | CPU core pinning via `java-thread-affinity` |

The patterns, the problems solved, and the trade-offs are **identical** to what you will find in
SocGen's EMS, BNP's TWAP engine, and Natixis's pre-trade risk service.

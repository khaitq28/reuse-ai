# Java Developer — Front Office / Electronic Trading Interview Prep

> Based on the code in this project. Study this alongside `ELECTRONIC_TRADING_JAVA.md`.

---

## What This Project Demonstrates (Know It Cold)

| Component | File | Key Concept |
|---|---|---|
| **OrderBook** (CLOB) | `market/OrderBook.java` | `TreeMap<Double, Deque<Order>>` for price-time priority matching |
| **RiskEngine** | `risk/RiskEngine.java` | Pre-trade checks: notional, fat-finger, position, rate-limit |
| **OMS** | `service/OrderManagementSystem.java` | `ConcurrentHashMap`, order lifecycle, fill → position update |
| **Disruptor Pipeline** | `engine/DisruptorOrderPipeline.java` | Ring buffer, pre-allocated events, `BusySpinWaitStrategy` |
| **MarketMaker** | `engine/MarketMaker.java` | Bid/ask quoting, spread logic |

---

## Priority Topics to Study — Ranked

### 1. JVM Internals (Guaranteed to Be Asked)

**Know this diagram by heart:**
```
Heap:         Eden → S0/S1 → Old Gen
Stack:        Per-thread, method frames, primitives
Metaspace:    Class metadata, static fields (not heap in Java 8+)
PC Register:  Current bytecode instruction (per thread)
```

**Key distinctions interviewers love:**
- `volatile` → visibility + ordering, NOT atomicity
- `AtomicLong` → CAS, atomic single-variable ops
- `synchronized` → mutual exclusion + visibility, blocks threads
- Stack stores **references**, heap stores **objects**
- `double`/`long` reads are not atomic on 32-bit JVM without `volatile`

**What this project uses:** `RiskEngine` uses `AtomicLong` for position counters and `volatile` for the rate-limit window. Understand WHY `volatile long windowStart` is not enough for `ordersInWindow` (compound read-modify-write → needs `AtomicLong`).

---

### 2. Garbage Collection (Critical for Trading Roles)

**The question they WILL ask:** *"How do you avoid GC pauses in a latency-sensitive system?"*

**Answer framework:**
1. **Avoid allocation on hot path** — no `new` objects per tick
2. **Object pooling** — reuse `Order`, `Trade` objects (this project creates new ones; in HFT you'd pool them)
3. **Fix heap size** — `-Xms == -Xmx`, prevents resizing GC pause
4. **Choose ZGC** for sub-millisecond pauses (Java 15+)
5. **`AlwaysPreTouch`** — pre-fault heap pages at startup
6. **Off-heap** — `ByteBuffer.allocateDirect()` for market data buffers

**GC algorithm comparison to memorize:**
| GC | Pause | Use case |
|---|---|---|
| Serial | 100s ms STW | Never in trading |
| Parallel | 10–50ms STW | Batch / overnight |
| G1 | 1–10ms | OMS, back-office |
| ZGC | < 1ms | Latency-sensitive |
| Shenandoah | < 1ms | Alternative to ZGC |

**Weakness in this project:** `OrderBook` uses `new ArrayList<>()` per `addOrder()` call — this allocates on every tick. In production, you'd reuse a thread-local list or pre-allocated array.

---

### 3. The LMAX Disruptor (Will Definitely Come Up)

The `DisruptorOrderPipeline.java` is your anchor here. Know:

**Why Disruptor beats `BlockingQueue`:**
- Pre-allocated ring buffer → **zero GC** (events reused, not created)
- Cache-line padded sequence numbers → **no false sharing**
- `BusySpinWaitStrategy` → no thread sleep/wake overhead
- Single producer CAS is cheaper than lock
- Consumers process in declared dependency order

**Key interview questions:**
- *Why must ring buffer size be a power of 2?* → Bitwise modulo (`seq & (size-1)`) instead of expensive `%`
- *What is a `WaitStrategy`?* → `BusySpinWaitStrategy` (lowest latency, burns CPU), `BlockingWaitStrategy` (low CPU, higher latency), `YieldingWaitStrategy` (compromise)
- *What is `ProducerType.SINGLE` vs `MULTI`?* → SINGLE skips CAS on publish (faster)
- *How does the Disruptor avoid false sharing?* → `@Contended` / manual padding on `Sequence` objects

---

### 4. Order Book Data Structures (Coding Interview)

The `OrderBook` in this project uses `TreeMap<Double, Deque<Order>>`. Interviewers will ask you to explain or implement this.

**Price-time priority (FIFO) matching:**
```
Bids (buy side):  TreeMap descending  → highest bid = first entry
Asks (sell side): TreeMap ascending   → lowest ask  = first entry
```

**Matching logic (know this):**
- BUY crosses: if `bestAsk <= buyLimitPrice` → match
- SELL crosses: if `bestBid >= sellLimitPrice` → match
- Match quantity = `min(aggressive.remaining, passive.remaining)`

**Why `TreeMap` is NOT production-grade:**
- `O(log n)` per lookup — too slow at microsecond level
- Cache-unfriendly (tree node pointer chasing)
- In HFT: use array-indexed by price level (price ladder), O(1) lookup

**Follow-up coding tasks to practice:**
1. Implement `addOrder()` with price-time priority
2. Implement `cancelOrder()` by orderId
3. Calculate market impact of a large order
4. Implement an iceberg order

---

### 5. Concurrency Patterns in This Codebase

**`RiskEngine` issues to spot (interviewers may ask "what's wrong with this code"):**
```java
// Not atomic — race condition under concurrent access:
if (now - windowStart > 1000) {
    windowStart = now;           // Two threads can both enter here
    ordersInWindow.set(0);       // Both reset the counter
}
```
Better: use `AtomicReference<WindowState>` with a CAS swap, or a single-threaded risk thread.

**`ConcurrentHashMap` limitations (know for interview):**
- `get()` is lock-free (volatile reads)
- `put()` locks only the bucket
- `size()` is approximate (uses `CounterCell` like `LongAdder`)
- Iteration is **weakly consistent** — not a point-in-time snapshot
- `computeIfAbsent()` is atomic but the lambda must be side-effect free

---

### 6. Low-Latency Java — Key Techniques

Must be able to explain these without hesitation:

| Technique | Why |
|---|---|
| **Object pooling** | Avoid GC allocation on hot path |
| **Off-heap (`ByteBuffer.allocateDirect`)** | Outside GC, for market data buffers |
| **`volatile` flag for kill switch** | ~5ns read, immediately visible to all threads |
| **Spin-wait (`Thread.onSpinWait()`)** | Avoid thread scheduling latency |
| **CPU affinity (thread pinning)** | Dedicated core = no context switching |
| **JIT warmup** | Send 50k synthetic orders before market open |
| **`@Contended` padding** | Prevent false sharing on hot counters |
| **`-Xms == -Xmx`** | No heap resize during trading |
| **`-XX:+AlwaysPreTouch`** | Pre-fault memory pages at startup |
| **`-XX:+DisableExplicitGC`** | Prevent `System.gc()` from causing pauses |

---

### 7. FIX Protocol (Domain Knowledge)

For front office Java roles — even if you don't implement FIX, know what it is.

**Essential FIX fields:**
```
35=D         New Order Single
35=8         Execution Report (fill/reject notification)
35=F         Cancel Request
54=1/2       Side: 1=Buy, 2=Sell
40=1/2       OrdType: 1=Market, 2=Limit
39=0/1/2     OrdStatus: 0=New, 1=PartFill, 2=Filled
150=F        ExecType: F=Trade (fill)
14=          CumQty (total filled quantity so far)
151=         LeavesQty (remaining)
```

**QuickFIX/J** — the Java FIX engine. Know it exists even if you haven't used it.

---

## Popular Interview Questions — Front Office Java

### Technical — JVM/GC/Performance

---

**Q1. What is the difference between heap and stack? What lives where?**

> **Stack** (one per thread):
> - Stores **stack frames** — each method call pushes one frame
> - Inside a frame: local variables (primitives + object references), method parameters, return address
> - Lifetime = duration of the method call. When the method returns, the frame is popped — no GC needed
> - Error when exceeded: `StackOverflowError` (e.g., infinite recursion)
> - Size: small, ~512KB–1MB per thread by default (`-Xss` to tune)
>
> **Heap** (shared across all threads):
> - Stores **all objects** created with `new`, arrays, and their instance fields
> - Managed by the Garbage Collector — objects live until no reference points to them
> - Error when full: `OutOfMemoryError: Java heap space`
> - Size: large, configured with `-Xms` (initial) and `-Xmx` (max)
>
> **Key rules to remember:**
> ```java
> public void processOrder(int qty, double price) {
>     // qty, price       → Stack (primitives)
>     double notional = qty * price;   // notional → Stack (local primitive)
>
>     Order order = new Order(...);    // order reference → Stack
>                                      // Order object itself → Heap
>     // order.symbol, order.price     → Heap (instance fields live with the object)
> }
> // Method returns → stack frame popped, 'order' reference gone
> // Order object on heap remains until GC finds no references → collected
> ```
>
> **In trading:** heavy `new` allocation on the hot path (e.g., `new ArrayList<>()` per tick in `OrderBook.addOrder()`) puts pressure on Young Gen → frequent Minor GC → latency spikes. Solution: object pooling or pre-allocated arrays.

---

**Q2. Explain Stop-The-World. Why is even 10ms catastrophic in trading?**

> A **Stop-The-World (STW) pause** is a period during garbage collection where the JVM **suspends all application threads**. This is necessary so the GC can safely traverse and modify the heap without objects being changed concurrently.
>
> During an STW pause — even 10ms — in an electronic trading system:
> ```
> Market is moving:   ~100 price ticks arrive per second on a single symbol
>                     → in 10ms: ~1 tick per ms = up to 10 ticks completely missed
>
> Open orders:        No risk checks run → unmanaged exposure
>
> FIX session:        HeartBeat interval is typically 30s, but if the exchange
>                     sees no messages during a spike → may terminate the session
>
> Strategy:           Signal based on stale price → wrong order sent when resumed
>
> Regulatory:         MiFID II requires order-to-trade ratio compliance — missed
>                     cancellations during pause can breach limits
> ```
>
> In HFT (High-Frequency Trading), even **1ms** is unacceptable. Competitors operating at microsecond latency will have already moved the market before your system resumes.
>
> **How to eliminate STW:**
> - Use **ZGC** (`-XX:+UseZGC`) — concurrent GC, STW < 1ms regardless of heap size
> - Fix heap size (`-Xms == -Xmx`) — no heap-resize GC
> - Minimize allocation on the hot path — fewer GC cycles overall
> - Pre-touch heap pages at startup (`-XX:+AlwaysPreTouch`) — no OS page-fault pauses

---

**Q3. Walk me through GC from `new Order()` to garbage collected.**

> ```
> new Order() → [Eden] → Minor GC → [Survivor S0] → Minor GC → [S1] → ... → [Old Gen] → Major GC → collected
> ```
>
> **Step by step:**
>
> **1. Allocation in Eden (Young Generation):**
> `new Order(...)` allocates the object in **Eden** space using a **TLAB (Thread-Local Allocation Buffer)** — each thread has its own private slice of Eden so allocation is just a pointer bump, no locking needed. Very fast (~10 cycles).
>
> **2. Minor GC triggered (Eden full):**
> When Eden fills up, a Minor GC runs. It's fast because most objects are already dead (generational hypothesis: 90%+ of short-lived `Order` objects are dead within milliseconds). Live objects are **copied** to Survivor space S0. Dead objects' memory is reclaimed instantly.
>
> **3. Survivor spaces S0 / S1:**
> Live objects bounce between S0 and S1 on each Minor GC. Each time an object survives, its **age counter** increments. In this project: a completed order (FILLED/REJECTED) quickly becomes unreachable → dies in Eden or early Survivor.
>
> **4. Promotion to Old Generation:**
> When an object's age reaches `MaxTenuringThreshold` (default 15 for G1), it is **promoted** to Old Gen. In a trading system: long-lived objects like the `orders` map in `OrderManagementSystem`, position tables, and the order book itself end up here.
>
> **5. Major / Full GC:**
> When Old Gen fills up → Major GC runs. For G1: incremental mixed collection. For older collectors: Full GC with long STW. This is the dangerous pause. Avoid by: not creating too many long-lived objects, capping collections with eviction policies.
>
> **6. Collection:**
> Once no **GC root** references the object (thread stacks, static fields, JNI refs), it is unreachable → eligible for collection → memory reclaimed at next GC.

---

**Q4. Why would you choose ZGC over G1 for a trading application?**

> | | **G1GC** | **ZGC** |
> |---|---|---|
> | STW pause | 1–10ms (tunable target) | < 1ms (consistently) |
> | Heap size | Works well up to ~32GB | Scales to 16TB |
> | Mechanism | Incremental region collection | Fully concurrent + colored pointers |
> | Java version | Java 9+ (default in Java 9+) | Java 15+ (production-ready) |
> | CPU overhead | Low | Slightly higher (concurrent GC threads) |
>
> **Why ZGC wins for trading:**
>
> G1's `MaxGCPauseMillis=5` is a **best-effort target, not a guarantee**. Under high allocation pressure (e.g., 100k orders/second), G1 can exceed its target. A 5–10ms pause still causes missed ticks and potential position exposure.
>
> ZGC achieves sub-millisecond pauses by doing **all expensive GC work concurrently** while application threads run:
> - **Concurrent marking** — traces live objects without stopping the app
> - **Concurrent relocation** — moves objects while app runs (using load barriers)
> - Only requires STW for a brief **initial mark** and **final remark** — typically < 1ms
>
> ZGC uses **colored pointers** (metadata bits in the 64-bit pointer itself) and **load barriers** (code inserted at every object read) to track which objects have been moved.
>
> **Practical flags:**
> ```bash
> -XX:+UseZGC
> -Xms8g -Xmx8g          # fix size — no resize pause
> -XX:+AlwaysPreTouch     # pre-fault all pages at startup
> -XX:+DisableExplicitGC  # block System.gc() calls
> ```

---

**Q5. What is JIT tiered compilation? Why must a trading system warm up?**

> The JVM does not compile Java to native code at startup. It uses a **progressive compilation pipeline**:
>
> | Tier | Compiler | What happens |
> |---|---|---|
> | 0 | Interpreter | Executes bytecode directly — slowest (10–100x vs native) |
> | 1–2 | C1 (Client) | Fast compile, basic optimizations, starts collecting profiling data |
> | 3 | C1 | Full profiling: branch frequencies, type profiles, call site stats |
> | 4 | C2 (Server) | Aggressive optimizations using the profile data collected at Tier 3 |
>
> C2 at Tier 4 applies: **inlining** (eliminates method call overhead), **loop unrolling**, **dead code elimination**, **escape analysis** (stack-allocate short-lived objects), **speculative optimizations** (devirtualize based on observed types).
>
> **Method becomes "hot"** after ~10,000 invocations (configurable via `-XX:CompileThreshold`) → JVM submits it for C2 compilation.
>
> **Why warmup is critical in trading:**
> ```
> 08:59:00 — System starts, market opens in 1 minute
> 08:59:00–09:00:00 — All hot paths run through Interpreter / C1 Tier 1–2
>                      → submitOrder() takes 500µs instead of 5µs
>                      → Risk check takes 200µs instead of 2µs
>
> 09:00:00 — Market opens, first real orders arrive
>            → Latency is 100x worse than expected
>            → JIT compilation competing with trading threads for CPU
> ```
>
> **Solution — synthetic warmup before market open:**
> ```java
> @PostConstruct
> public void warmUp() {
>     log.info("Warming up JIT...");
>     for (int i = 0; i < 50_000; i++) {
>         Order o = new Order("WARMUP-" + i, "AAPL", Side.BUY,
>                              OrderType.LIMIT, 150.0, 100);
>         oms.submitOrder(o);       // forces Tier 4 compilation of entire hot path
>         riskEngine.check(o);
>     }
>     log.info("JIT warmup complete — all paths at Tier 4");
> }
> ```
> After 50k iterations, every method on the critical path is compiled at C2 Tier 4 → consistent low-latency from market open.

---

**Q6. What is false sharing? How do you fix it?**

> **False sharing** is a multi-core performance problem where two threads on different CPU cores modify **different variables** that happen to sit on the **same CPU cache line** (typically 64 bytes).
>
> The CPU cache coherence protocol (MESI) treats the cache line as the atomic unit of synchronization. When Thread A writes to `buyCount` and Thread B writes to `sellCount`, even though they are logically independent, the CPU sees them as sharing the same 64-byte line and forces cache invalidation across cores.
>
> **Result:** Every write by one thread causes a **cache miss** on the other thread's core, forcing a re-fetch from L3 cache or main memory (~100–200 CPU cycles vs ~4 for L1). In HFT, this adds tens of nanoseconds per operation on every tick.
>
> **Example in trading:**
> ```java
> // BAD — both counters share one 64-byte cache line:
> public class TradingStats {
>     long buyOrderCount;   // bytes 0–7
>     long sellOrderCount;  // bytes 8–15  ← same cache line!
> }
> // Thread A (buy side) and Thread B (sell side) constantly invalidate each other's cache
> ```
>
> **Fix 1: Manual padding (always works)**
> ```java
> public class TradingStats {
>     long buyOrderCount;
>     long p1, p2, p3, p4, p5, p6, p7;  // 7 × 8 bytes = 56 bytes padding
>     // buyOrderCount now occupies its own 64-byte cache line
>     long sellOrderCount;
>     long q1, q2, q3, q4, q5, q6, q7;
> }
> ```
>
> **Fix 2: `@Contended` annotation (Java 8+, cleanest)**
> ```java
> import sun.misc.Contended;
>
> public class TradingStats {
>     @Contended long buyOrderCount;   // JVM automatically pads to own cache line
>     @Contended long sellOrderCount;
> }
> // Also requires JVM flag: -XX:-RestrictContended
> ```
>
> **Fix 3: Thread-local state (best for trading hot path)**
> — Each thread only touches its own counters. No sharing at all. Zero synchronization cost.
>
> The LMAX Disruptor in this project uses cache-line padding on its `Sequence` objects precisely to eliminate false sharing between producer and consumer sequence tracking.

---

**Q7. What is the Java Memory Model? What does `happens-before` guarantee?**

> The **Java Memory Model (JMM)** defines the rules for how threads interact through memory. Without JMM rules, CPUs and compilers are free to reorder instructions for performance — a write on Thread A may never be visible to Thread B, or may appear in a different order.
>
> A **happens-before (HB)** relationship guarantees: if action A HB action B, then all side effects of A (memory writes) are guaranteed to be **visible** to B.
>
> **Core happens-before rules:**
> | Rule | Guarantee |
> |---|---|
> | Program order | Each action in a thread HB every later action in the same thread |
> | `volatile` write → read | Writing `volatile x` HB any subsequent read of `volatile x` |
> | Monitor unlock → lock | `unlock(m)` HB any subsequent `lock(m)` |
> | `Thread.start()` | `t.start()` HB any action in thread `t` |
> | `Thread.join()` | All actions in thread `t` HB `t.join()` returning |
> | Transitivity | If A HB B and B HB C, then A HB C |
>
> **Why it matters in trading — the missing volatile bug:**
> ```java
> // Thread A (market data parser) — no synchronization:
> order.symbol   = "AAPL";      // (1)
> order.price    = 150.0;       // (2)
> order.quantity = 100;         // (3)
> newOrderReady  = true;        // (4) plain boolean — NOT volatile
>
> // Thread B (order sender) — CPU may reorder (4) before (2):
> if (newOrderReady) {          // (5) sees true
>     send(order.price);        // (6) might see 0.0 — CPU reordered write (2) AFTER (4)!
> }
> ```
>
> **Fix — volatile write establishes happens-before:**
> ```java
> volatile boolean newOrderReady = false;
>
> // Thread A: volatile write flushes ALL prior writes
> order.price   = 150.0;
> newOrderReady = true;     // HB barrier: everything above is visible to any reader of newOrderReady
>
> // Thread B: volatile read sees all prior writes
> if (newOrderReady) {
>     send(order.price);   // guaranteed to see 150.0
> }
> ```
>
> Missing HB relationships in trading cause **intermittent, nearly unreproducible bugs** — a strategy may trade on a stale price, or an OMS may see a partially initialized order. These bugs are the most dangerous in production.

---

### Technical — Concurrency

---

**Q8. What is the difference between `volatile`, `synchronized`, and `AtomicLong`?**

> These provide three levels of synchronization with different cost/guarantee tradeoffs:
>
> | | `volatile` | `AtomicLong` | `synchronized` |
> |---|---|---|---|
> | **Visibility** | Yes | Yes | Yes |
> | **Ordering** | Yes (memory barrier) | Yes | Yes |
> | **Atomicity** | No (read/write only) | Yes (CAS) | Yes (entire block) |
> | **Mutual exclusion** | No | No | Yes |
> | **Blocking** | No | No (spin retry) | Yes (OS thread suspend) |
> | **Cost** | ~5ns (memory fence) | ~15ns uncontended | ~25ns uncontended / µs+ contended |
>
> **`volatile`** — guarantees every read sees the latest write. Correct for: simple flags, single-writer published values.
> ```java
> private volatile boolean sessionActive = true;   // safe: one writer, many readers
> private volatile double lastBidPrice;             // safe: one market-data thread writes
>
> // WRONG use — NOT atomic:
> volatile int counter = 0;
> counter++;  // read(0) + increment + write(1) — three ops, not one → race condition
> ```
>
> **`AtomicLong`** — uses a single **CAS instruction** (hardware-guaranteed atomic). Correct for: counters, sequence numbers, single-variable state transitions.
> ```java
> AtomicLong orderId = new AtomicLong(0);
> long nextId = orderId.incrementAndGet();      // atomic — safe from any number of threads
> orderId.compareAndSet(expected, newValue);   // CAS — only writes if current == expected
> ```
>
> **`synchronized`** — OS-level mutual exclusion. Only one thread enters the block at a time; all others block. Correct for: compound operations (read-check-write), multi-variable state updates.
> ```java
> public synchronized void applyFill(long qty, double fillPrice) {
>     // No other thread can enter any synchronized method on 'this'
>     if (filledQty + qty > totalQty) return;
>     filledQty   += qty;
>     avgFillPrice = recalcAvg(filledQty, qty, fillPrice);  // multi-step: safe
> }
> ```
>
> **When to use which in trading:**
> | Scenario | Choice |
> |---|---|
> | Kill switch flag | `volatile boolean` |
> | Order ID generator | `AtomicLong.incrementAndGet()` |
> | Fill quantity counter | `AtomicLong.addAndGet()` |
> | Position update (fill → position) | `AtomicLong` per symbol in `ConcurrentHashMap` |
> | Multi-field order state update | `synchronized` or single-threaded |
> | Hot path (no contention possible) | None — single-threaded event loop |

---

**Q9. What is a race condition? Show me one in an OMS context.**

> A **race condition** occurs when the correctness of a program depends on the **relative timing of thread execution**, and at least one timing scenario produces a wrong result.
>
> **Classic trading race: the double-fill (overfill) problem**
> ```java
> // Order: totalQty = 100
> public class Order {
>     private long filledQty = 0;
>     private final long totalQty = 100;
>
>     // BROKEN — race condition between two fill threads:
>     public void applyFill(long qty) {
>         if (filledQty + qty <= totalQty) {    // Thread A: sees filledQty=0, 0+80<=100 ✓
>                                                // Thread B: also sees filledQty=0, 0+80<=100 ✓
>             filledQty += qty;                 // Thread A writes 80
>                                               // Thread B also writes 80 (lost update!)
>             // Result: filledQty=80 (lost B's fill) OR 160 (both writes land = overfill)
>         }
>     }
> }
> ```
> An overfill means you've confirmed more shares than you have — a regulatory and financial disaster.
>
> **Fix 1: `synchronized` — simplest**
> ```java
> public synchronized void applyFill(long qty) {
>     if (filledQty + qty <= totalQty) {
>         filledQty += qty;
>     }
> }  // only one thread in the method at a time — no race
> ```
>
> **Fix 2: CAS loop with `AtomicLong` — lock-free, non-blocking**
> ```java
> private final AtomicLong filledQty = new AtomicLong(0);
>
> public boolean applyFill(long qty) {
>     long current, updated;
>     do {
>         current = filledQty.get();
>         updated = current + qty;
>         if (updated > totalQty) return false;  // reject overfill
>         // CAS: only writes if nobody else changed filledQty since we read 'current'
>         // If CAS fails (another thread changed it) → retry with fresh 'current'
>     } while (!filledQty.compareAndSet(current, updated));
>     return true;
> }
> ```
>
> **Fix 3: Single-threaded event loop — best for trading hot path**
> ```java
> // All fill events go through a single-threaded queue — no two threads ever
> // touch the same Order simultaneously → zero synchronization cost
> singleThreadExecutor.submit(() -> order.applyFill(qty));
> ```
> This is the design used in the `DisruptorOrderPipeline` in this project — `OMSHandler.onEvent()` runs on a single consumer thread.

---

**Q10. What is a deadlock? How do you prevent it?**

> A **deadlock** is a state where two or more threads are permanently blocked, each waiting for a lock that another holds. No thread can proceed — the system freezes.
>
> **Four conditions required (Coffman conditions) — all must hold:**
> 1. **Mutual exclusion** — at least one resource is held exclusively
> 2. **Hold and wait** — a thread holds one lock while waiting for another
> 3. **No preemption** — locks cannot be forcibly taken away
> 4. **Circular wait** — Thread A waits on B, Thread B waits on A
>
> **Classic trading deadlock — cross-symbol hedging:**
> ```java
> // Thread A: trading AAPL, hedging with MSFT
> synchronized (aaplLock) {        // Thread A holds AAPL lock
>     synchronized (msftLock) {    // Thread A waits for MSFT — Thread B has it
>         executeAaplBuyMsftSell();
>     }
> }
>
> // Thread B: trading MSFT, hedging with AAPL (opposite order)
> synchronized (msftLock) {        // Thread B holds MSFT lock
>     synchronized (aaplLock) {    // Thread B waits for AAPL — Thread A has it → DEADLOCK
>         executeMsftBuyAaplSell();
>     }
> }
> ```
>
> **Prevention strategies:**
>
> **1. Consistent lock ordering (break circular wait):**
> ```java
> // Always acquire locks in alphabetical order — both threads use same order
> private void executePair(String sym1, String sym2) {
>     String first  = sym1.compareTo(sym2) < 0 ? sym1 : sym2;
>     String second = sym1.compareTo(sym2) < 0 ? sym2 : sym1;
>     synchronized (getLock(first)) {
>         synchronized (getLock(second)) {
>             // safe — both threads acquire in the same canonical order
>         }
>     }
> }
> ```
>
> **2. `tryLock` with timeout — never wait forever:**
> ```java
> boolean acquired = lock1.tryLock(1, TimeUnit.MICROSECONDS);
> if (acquired) {
>     try {
>         if (lock2.tryLock(1, TimeUnit.MICROSECONDS)) {
>             try { doWork(); }
>             finally { lock2.unlock(); }
>         }
>     } finally { lock1.unlock(); }
> }
> // If either tryLock fails → back off and retry → no deadlock
> ```
>
> **3. Single-threaded event loop (best for trading) — no locks, no deadlock possible.**
>
> **Detection at runtime:**
> ```java
> ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
> long[] deadlocked = mxBean.findDeadlockedThreads();
> if (deadlocked != null) {
>     killSwitch.halt("Deadlock detected — halting trading");
> }
> ```

---

**Q11. How does `ConcurrentHashMap` work internally?**

> `ConcurrentHashMap` (CHM, Java 8+) provides thread-safe map operations without the coarse lock of `Hashtable` or `Collections.synchronizedMap()`.
>
> **Internal structure:**
> - A `Node[]` array — each index is a **bucket** (hash slot)
> - When a bucket has few entries: singly-linked list of `Node` objects
> - When a bucket exceeds 8 entries: converted to a **red-black tree** (`TreeNode`) for O(log n) worst-case
>
> **Read path (`get`)** — completely lock-free:
> - The `Node` array and `Node.val`/`Node.next` fields are `volatile`
> - Multiple threads can read concurrently with zero contention — just volatile reads
>
> **Write path (`put`)** — fine-grained locking:
> - Empty bucket: use CAS to set the first node (no lock needed)
> - Non-empty bucket: `synchronized` on the **bucket's head node only** — other buckets are unaffected
> - Two writes to different buckets never block each other
>
> **Resize** — incremental and cooperative:
> - Threads that encounter a `ForwardingNode` sentinel during `put` help with the resize
> - No single long-blocking resize
>
> **`size()`** — approximate, uses `CounterCell` arrays (like `LongAdder`) to avoid contention
>
> **Key limitations for trading:**
> ```java
> // 1. Compound operations are NOT atomic — still need care:
> if (!orders.containsKey(id)) { orders.put(id, order); }  // BROKEN: race between check and put
> orders.putIfAbsent(id, order);                            // CORRECT: atomic
> orders.computeIfAbsent(id, k -> new Order(...));          // CORRECT: atomic
>
> // 2. Iteration is weakly consistent — not a point-in-time snapshot
> //    A snapshot read while another thread inserts may or may not see the new entry
>
> // 3. Still has synchronization cost — for the true hot path, use single-threaded design
> //    with a plain HashMap that only one thread ever touches
> ```
>
> **In this project:** `OrderManagementSystem` uses CHM for `orders` and `books` — correct because multiple REST threads may submit orders concurrently. But on the Disruptor hot path, a single consumer thread processes events, so no locking is needed there.

---

**Q12. What is lock-free programming? Explain CAS and the ABA problem.**

> **Lock-free programming** achieves thread safety without OS-level blocking (no `synchronized`, no `ReentrantLock`). Threads never suspend — they either succeed immediately or retry, but always make progress overall.
>
> The core primitive is **CAS (Compare-And-Swap)** — a single, indivisible CPU instruction (`CMPXCHG` on x86):
> ```
> CAS(address, expected, newValue):
>     if memory[address] == expected:
>         memory[address] = newValue
>         return true   (success)
>     else:
>         return false  (someone changed it — retry)
> // The check + write is ONE atomic hardware instruction — cannot be interrupted
> ```
>
> **CAS retry loop — how `AtomicLong.incrementAndGet()` works internally:**
> ```java
> public long incrementAndGet() {
>     long current, next;
>     do {
>         current = get();         // volatile read
>         next    = current + 1;
>         // CAS: only writes if nobody changed 'current' in the meantime
>         // If another thread changed it → current is stale → retry with fresh value
>     } while (!compareAndSet(current, next));
>     return next;
> }
> // Under low contention: succeeds on first try (~15ns)
> // Under high contention: retries — but no thread ever sleeps
> ```
>
> **The ABA Problem:**
> CAS checks equality — but a value can change A → B → A while you weren't looking. Your CAS sees A, assumes nothing changed, and succeeds — but the logical state has changed:
> ```
> Thread A reads order reference → points to Order#1001
> Thread B: Order#1001 is filled and returned to object pool
>           A new order is created — reuses same memory → also called Order#1001
> Thread A: CAS(Order#1001, Order#1001, newOrder) SUCCEEDS
>           But it's a COMPLETELY DIFFERENT order! ABA trap.
> ```
>
> **Fix: `AtomicStampedReference` — pairs value with a version stamp:**
> ```java
> AtomicStampedReference<Order> ref = new AtomicStampedReference<>(order, 0);
>
> int[] stampHolder = new int[1];
> Order current = ref.get(stampHolder);   // gets value AND stamp atomically
> int   stamp   = stampHolder[0];
>
> // CAS only succeeds if BOTH reference AND stamp match
> // Even if reference cycles back to same value, stamp is always incrementing
> ref.compareAndSet(current, newOrder, stamp, stamp + 1);
> ```
>
> **In this project:** `RiskEngine` uses `AtomicLong` for position per symbol — safe because the value is a simple counter (no ABA risk — a position of 100 is always just 100, regardless of how it got there).

---

**Q13. Why does the LMAX Disruptor outperform `BlockingQueue`?**

> The `DisruptorOrderPipeline` in this project demonstrates the key design. Here is why it is fundamentally faster than a `BlockingQueue<Order>`:
>
> **Problem 1 — `BlockingQueue` allocates:**
> ```java
> // BlockingQueue: new Order object put in queue, garbage collected after consumer processes it
> queue.put(new Order(...));  // allocation → GC pressure on hot path
> ```
> **Disruptor fix — pre-allocated ring buffer:**
> ```java
> // Disruptor: OrderEvent objects are pre-allocated once at startup (BUFFER_SIZE=1024 in this project)
> // Publisher writes INTO an existing slot — zero allocation, zero GC
> ringBuffer.publishEvent(translator, order);  // no new object — reuses pre-allocated OrderEvent
> ```
>
> **Problem 2 — `BlockingQueue` blocks threads:**
> - `take()` / `put()` use OS-level `wait()` / `notify()` → thread context switch (~1–10µs)
>
> **Disruptor fix — `BusySpinWaitStrategy`:**
> ```java
> // Consumer spins in a tight loop — no thread sleep, no context switch
> new BusySpinWaitStrategy()  // used in DisruptorOrderPipeline — burns CPU but sub-microsecond
> ```
>
> **Problem 3 — `BlockingQueue` has false sharing:**
> - Head and tail pointers share cache lines → producers and consumers invalidate each other's cache
>
> **Disruptor fix — cache-line padded `Sequence`:**
> - Each `Sequence` object is padded with 7 longs on each side (56 bytes) → sits alone on its 64-byte cache line → zero false sharing between producer sequence and consumer sequences
>
> **Problem 4 — `BlockingQueue` uses locks:**
> - `ArrayBlockingQueue` uses a `ReentrantLock` for both head and tail → lock contention
>
> **Disruptor fix — CAS publish + `ProducerType.SINGLE`:**
> - With `ProducerType.SINGLE` (as in this project): no CAS even needed — single writer increments sequence directly without atomic operation
>
> **Net result:** LMAX benchmarks: 25M+ events/second vs ~5M for `ArrayBlockingQueue`. Consistent sub-microsecond latency vs occasional millisecond spikes.

---

### Technical — Order Book / Domain

---

**Q14. Explain price-time priority matching. Walk me through matching a buy order.**

> **Price-time priority** (also called FIFO matching) is the standard algorithm used by most electronic exchanges. Two rules:
> 1. **Price priority** — the best price is matched first (highest bid / lowest ask)
> 2. **Time priority** — at the same price, the oldest order is matched first (FIFO queue)
>
> **Data structure:**
> ```
> Bids side:  TreeMap<Double, Deque<Order>> — descending (highest bid first)
> Asks side:  TreeMap<Double, Deque<Order>> — ascending  (lowest ask first)
> ```
>
> **Walk-through — incoming BUY limit order: 100 shares @ $150.10:**
> ```
> Ask book before order arrives:
>   $150.05  →  [Order A: 40 shares]  ← bestAsk
>   $150.08  →  [Order B: 30 shares]
>   $150.12  →  [Order C: 80 shares]
> ```
>
> **Step 1:** Incoming buy price ($150.10) ≥ bestAsk ($150.05) → crossable → match begins.
>
> **Step 2:** Match against `Order A` at $150.05:
> - Match qty = min(100 remaining, 40) = **40 shares** at $150.05
> - `Order A` → FILLED, removed from book
> - Buy order remaining = 60 shares
>
> **Step 3:** Move to next ask level $150.08. Buy limit $150.10 ≥ $150.08 → still crossable.
> Match against `Order B`:
> - Match qty = min(60, 30) = **30 shares** at $150.08
> - `Order B` → FILLED
> - Buy order remaining = 30 shares
>
> **Step 4:** Move to next ask level $150.12. Buy limit $150.10 < $150.12 → NOT crossable → stop.
>
> **Step 5:** Buy order has 30 shares remaining → resting in book, added to bid side at $150.10.
>
> **Result:** 2 trades generated (40 @ $150.05, 30 @ $150.08). 30 shares resting on bid side.
> This is exactly what `OrderBook.matchAgainstAsks()` does in this project.

---

**Q15. What data structure would you use for a production order book? Why not TreeMap?**

> **Why `TreeMap` is not production-grade:**
> - `O(log n)` per lookup — too slow at microsecond latency (a tick can require 10+ lookups)
> - Tree nodes are scattered in heap memory → **cache misses** on every pointer traversal (~200 cycles each)
> - `Double` keys risk floating-point equality issues in extreme edge cases
> - Allocation of `Map.Entry` objects on every insert → GC pressure
>
> **Production solution: Price Ladder (Array-backed)**
> ```
> Concept: Represent prices as integer ticks (price * 100 = tick index)
>
> int tickIndex = (int)(price * 100);      // $150.05 → tick 15005
> Level[] bids = new Level[MAX_TICKS];     // fixed array, pre-allocated
> Level level  = bids[tickIndex];          // O(1) — just array index, cache-friendly
> ```
>
> **At each price level — doubly-linked list (O(1) cancel):**
> ```java
> class Level {
>     Order head;   // oldest order (matched first)
>     Order tail;   // newest order (added to back)
>     long totalQty;
> }
>
> class Order {
>     Order prev, next;  // doubly-linked in the level
>     // Cancel: O(1) — just relink prev/next, no search needed
> }
> ```
>
> **Operation complexity comparison:**
> | Operation | `TreeMap` | Array Price Ladder |
> |---|---|---|
> | Best bid/ask | O(1) via `firstEntry()` | O(1) — track min/max index |
> | Insert order | O(log n) | O(1) |
> | Cancel order by ID | O(n) scan | O(1) with HashMap<id, Order> |
> | Match (walk levels) | O(log n) per level | O(1) per level |
>
> **Additional production techniques:**
> - Pre-allocate `Order` and `Level` objects — **object pooling** to avoid GC
> - Use `long` for quantities and integer ticks for prices — avoid `Double` boxing and floating-point errors
> - Separate read path (market data queries) from write path (matching) with lock-free structures

---

**Q16. What pre-trade risk checks would you implement? In what order?**

> Pre-trade risk checks must complete in **< 1 microsecond** in HFT. The golden rule: order checks **cheapest → most expensive**, fail fast on the first violation.
>
> **The four checks in this project's `RiskEngine.check()` (already in optimal order):**
>
> **1. Notional limit — 1 multiply + compare (~5ns)**
> ```java
> double notional = order.getPrice() * order.getQuantity();
> if (notional > maxNotionalPerOrder) return reject("Notional exceeded");
> // Catches fat-dollar errors: sending $10M when you meant $10k
> ```
>
> **2. Fat-finger price check — 1 divide + compare (~10ns)**
> ```java
> double deviation = Math.abs(order.getPrice() - marketPrice) / marketPrice;
> if (deviation > maxPriceDeviationPct) return reject("Price too far from market");
> // Catches typos: $1500 instead of $150 for AAPL
> ```
>
> **3. Position limit — 1 addition + compare (~10ns)**
> ```java
> long newPosition = currentPosition + (isBuy ? +qty : -qty);
> if (Math.abs(newPosition) > maxPositionPerSymbol) return reject("Position limit");
> // Prevents naked short, excessive long exposure per symbol
> ```
>
> **4. Order rate limit — 1 atomic increment + compare (~15ns)**
> ```java
> if (ordersInWindow.incrementAndGet() > maxOrdersPerSecond) return reject("Rate limit");
> // Exchange-enforced: too many orders per second = connection ban
> ```
>
> **Additional checks in a full production system:**
> - **Credit check** — sufficient buying power / margin available
> - **Duplicate order check** — same `clientOrderId` sent twice
> - **Self-trade prevention** — order would cross with your own resting order
> - **Symbol validation** — instrument is tradeable, not halted, correct exchange hours
> - **Market hours check** — no orders outside trading hours
>
> **Key principle:** All checks use only in-memory data with no I/O, no DB calls, no blocking. The `RiskEngine` in this project stores positions in `ConcurrentHashMap<String, AtomicLong>` — a single `get()` + `get()` = two volatile reads = ~10ns total.

---

**Q17. Explain market making. What risks does a market maker face?**

> A **market maker** is a participant that continuously quotes both a **bid** (buy price) and an **ask** (sell price) for an instrument, profiting from the **spread** (ask − bid) while providing liquidity to the market.
>
> **How it works (as in `MarketMaker.java` in this project):**
> ```
> Mid price: $150.00
> Spread:     $0.10
>
> Market maker quotes:
>   BID: $149.95  (buy at 5 cents below mid)
>   ASK: $150.05  (sell at 5 cents above mid)
>
> If someone buys from the MM (hits the ask):  MM sells at $150.05 → long cash, short stock
> If someone sells to the MM (hits the bid):   MM buys at $149.95 → long stock, short cash
> If both sides fill: MM bought at $149.95, sold at $150.05 → $0.10/share profit (the spread)
> ```
>
> **Risks a market maker faces:**
>
> **1. Inventory risk** — if only one side fills, the MM accumulates a position:
> - Filled 10,000 buy orders but no sellers → long 10,000 shares
> - If price falls, MM loses money on inventory before being able to sell
> - Mitigation: **skew quotes** (move bid/ask to attract offsetting flow), set position limits
>
> **2. Adverse selection** — informed traders know the fair price better than the MM:
> - An informed trader buys from the MM's ask → MM sells at $150.05 → price jumps to $151 immediately
> - The MM got "picked off" — sold too cheap
> - Mitigation: widen the spread, reduce size, detect informed flow, cancel quotes on news events
>
> **3. Latency risk** — a faster competitor or exchange update makes the MM's quotes stale:
> - MM quoting $150.05 when the real market has moved to $151 → MM sells at $150.05 for a loss
> - Mitigation: ultra-low latency market data feed, fastest possible quote update cycle
>
> **4. Fat-finger / runaway algo risk** — a bug causes the MM to quote wrong prices or sizes
> - Mitigation: pre-trade risk checks (notional limits, fat-finger checks), kill switch

---

**Q18. What is the FIX protocol? Name 3 message types.**

> **FIX (Financial Information eXchange)** is the dominant industry-standard messaging protocol for electronic trading. It defines a text-based (FIX 4.x) or binary (FIXT/SBE) format for communicating orders, executions, and market data between trading firms, brokers, and exchanges.
>
> **Message format — tag=value pairs, pipe-delimited:**
> ```
> 8=FIX.4.4 | 35=D | 49=TRADINGFIRM | 56=EXCHANGE | 11=ORD-001 |
> 55=AAPL   | 54=1  | 38=100         | 40=2         | 44=150.00  | 10=XXX
>
> Tag 8  = BeginString (protocol version)
> Tag 35 = MsgType
> Tag 49 = SenderCompID
> Tag 56 = TargetCompID
> Tag 11 = ClOrdID (client's unique order ID)
> Tag 55 = Symbol
> Tag 54 = Side (1=Buy, 2=Sell)
> Tag 38 = OrderQty
> Tag 40 = OrdType (1=Market, 2=Limit)
> Tag 44 = Price
> Tag 10 = Checksum
> ```
>
> **3 critical message types to know:**
>
> | MsgType | Name | Description |
> |---|---|---|
> | `D` | **New Order Single** | Client sends a new order to the broker/exchange |
> | `8` | **Execution Report** | Exchange/broker responds with fill, reject, or status update |
> | `F` | **Order Cancel Request** | Client requests cancellation of an existing order |
>
> **Execution Report (tag 35=8) carries:**
> - `39` (OrdStatus): 0=New, 1=PartialFill, 2=Filled, 4=Cancelled, 8=Rejected
> - `150` (ExecType): 0=New, F=Trade (fill), 4=Cancelled
> - `14` (CumQty): total quantity filled so far
> - `151` (LeavesQty): remaining unfilled quantity
>
> **QuickFIX/J** is the standard open-source Java FIX engine. In real banks, you'd also encounter proprietary FIX engines and binary alternatives like **ITCH** (NASDAQ native protocol — binary, ultra-low latency) and **SBE** (Simple Binary Encoding — zero-GC binary codec for FIX messages).

---

### Behavioral / System Design

---

**Q19. Design a low-latency order execution system. Walk me through the components.**

> ```
> [Exchange / Broker Feed]
>         │ (raw binary: ITCH / FIX / SBE)
>         ▼
> [Feed Handler Thread]  — parse, normalize to internal Order/Tick model
>         │ (volatile publish or Disruptor ring buffer)
>         ▼
> [Strategy / Signal Thread]  — react to ticks, compute buy/sell signals
>         │ (internal queue or direct call if same thread)
>         ▼
> [Risk Engine]  — pre-trade checks: notional, fat-finger, position, rate limit
>         │ (synchronous, in-process — must be < 1µs)
>         ▼
> [OMS Thread]  — manage order lifecycle, assign IDs, track state
>         │ (FIX session)
>         ▼
> [Exchange / Venue]
>         │ (Execution Report / fill)
>         ▼
> [Position / P&L Update]  — async, after fill confirmed
>
> Async side channels:
>   → [Persistence Thread]  — write-behind audit log (Chronicle Queue)
>   → [Risk Reconciliation Thread]  — async position checks
>   → [Kill Switch Listener]  — always live, independently monitored
> ```
>
> **Key design decisions:**
>
> **1. Single-threaded hot path** — from signal to order sent: one thread, no locking, no blocking. Zero synchronization cost. Deterministic latency.
>
> **2. Disruptor ring buffer** for hand-off between Feed Handler → Strategy: pre-allocated events (no GC), `BusySpinWaitStrategy` (no context switch), cache-line padded sequences (no false sharing).
>
> **3. ZGC** (`-XX:+UseZGC -Xms8g -Xmx8g -XX:+AlwaysPreTouch`) — sub-millisecond GC pauses.
>
> **4. Object pooling** for `Order` and `Trade` objects — reuse from a pool instead of `new` on every tick.
>
> **5. Off-heap buffers** (`ByteBuffer.allocateDirect`) for market data parsing — outside GC scope.
>
> **6. CPU affinity** — pin the trading thread to a dedicated core (`java-thread-affinity` library). No OS scheduling interruptions.
>
> **7. JIT warmup** — replay 50k synthetic orders at startup before market open.
>
> **8. Kill switch** — `volatile boolean halted`, checked before every order submission.
>
> **9. FIX session management** on a separate I/O thread (Netty NIO) — never blocks the strategy thread.

---

**Q20. A trading system suddenly has 200ms latency spikes every few minutes. How do you diagnose?**

> **Systematic diagnosis approach — from most likely to least likely:**
>
> **Step 1: Check GC logs first (most common cause)**
> ```bash
> # If not already enabled, restart with:
> -Xlog:gc*:file=gc.log:time,uptime
>
> # Look for in gc.log:
> [2.345s] GC(12) Pause Full (Ergonomics)  200ms   ← culprit found
> [2.345s] GC(12) Pause Young (Normal)     8ms     ← also investigate
> ```
> If GC pause matches the latency spike → fix: switch to ZGC, fix heap size, find and reduce allocations on hot path. Use JFR to find allocation hot spots.
>
> **Step 2: Thread dump — check for lock contention**
> ```bash
> kill -3 <pid>     # sends SIGQUIT → JVM prints thread dump to stdout
> # OR
> jstack <pid>
>
> # Look for: threads in BLOCKED state waiting for a lock
> # "order-thread" prio=10 tid=... BLOCKED on <0x00000007...> (a java.util.concurrent.locks.ReentrantLock)
> ```
> If trading thread is BLOCKED → identify the lock holder → fix: remove the lock (single-threaded design) or reduce critical section.
>
> **Step 3: Check network / OS jitter**
> ```bash
> # Check for TCP retransmits to exchange:
> netstat -s | grep retransmit
>
> # Check OS scheduler jitter (Linux):
> perf stat -e context-switches,cpu-migrations -p <pid>
> ```
> High CPU migrations → pin thread to a core. High context switches → use `BusySpinWaitStrategy`.
>
> **Step 4: JFR profiling — find the root cause in code**
> ```bash
> jcmd <pid> JFR.start duration=60s filename=spike.jfr
> # Open in JDK Mission Control
> # Look for: method profiling, memory allocation, I/O blocking, lock contention
> ```
>
> **Step 5: Market data staleness / feed disconnect**
> If the exchange feed drops packets or reconnects → system may queue up and process a burst of stale ticks on reconnect → looks like a spike. Check feed handler metrics.
>
> **Common causes in order of frequency in trading systems:**
> 1. GC pause (most common) → ZGC + allocation reduction
> 2. Lock contention → single-threaded hot path
> 3. JIT recompilation (if no warmup) → synthetic warmup
> 4. OS CPU scheduling jitter → CPU affinity + `isolcpus`
> 5. Network packet loss / retransmit → kernel bypass (DPDK / Solarflare) in HFT

---

**Q21. How would you implement a kill switch?**

> A kill switch immediately halts all trading. Requirements: **always reachable** (even if trading threads are stuck), **instantly visible to all threads**, **idempotent**, **triggers automatic safeguards**.
>
> **Core implementation:**
> ```java
> @Component
> public class KillSwitch {
>
>     // volatile: one write is immediately visible to ALL threads reading this field
>     // A volatile boolean read costs ~5ns — acceptable on the hot path
>     private static volatile boolean halted = false;
>
>     private final OrderManagementSystem oms;
>
>     public void halt(String reason) {
>         halted = true;                      // (1) stop new orders — instant, visible to all threads
>         log.error("KILL SWITCH ACTIVATED: {}", reason);
>         oms.cancelAllOpenOrders();          // (2) cancel everything resting in the market
>         fixSession.logout();                // (3) disconnect from exchange gracefully
>         alerting.sendUrgent("HALT: " + reason);  // (4) notify operations team
>     }
>
>     // Static so it can be called from anywhere with zero Spring context overhead
>     public static boolean isHalted() { return halted; }
>
>     public void resume() {
>         // Only re-enable after manual human confirmation
>         halted = false;
>         log.info("Kill switch cleared — trading resumed");
>     }
> }
> ```
>
> **Check on every order — hot path:**
> ```java
> public Order submitOrder(Order order) {
>     if (KillSwitch.isHalted()) {              // one volatile read = ~5ns overhead
>         return OrderResult.rejected("Kill switch active");
>     }
>     // ... normal flow
> }
> ```
>
> **Automated triggers (critical for unattended trading):**
> ```java
> @Scheduled(fixedDelay = 1000)
> public void checkRiskLimits() {
>     // P&L breach
>     if (pnlService.getDailyPnL() < -DAILY_LOSS_LIMIT) {
>         killSwitch.halt("Daily loss limit breached");
>     }
>     // Position breach
>     for (String symbol : monitoredSymbols) {
>         if (Math.abs(riskEngine.getPosition(symbol)) > HARD_POSITION_LIMIT) {
>             killSwitch.halt("Hard position limit breached: " + symbol);
>         }
>     }
>     // Market data staleness
>     if (feedHandler.getSecondsSinceLastTick() > 5) {
>         killSwitch.halt("Market data stale — possible feed disconnect");
>     }
> }
> ```
>
> **REST endpoint for manual operator control:**
> ```java
> @PostMapping("/trading/halt")
> public ResponseEntity<String> halt(@RequestParam String reason,
>                                    @RequestParam String operatorId) {
>     auditLog.record(operatorId, "MANUAL_HALT", reason);
>     killSwitch.halt(reason);
>     return ResponseEntity.ok("Trading halted");
> }
> ```

---

**Q22. How do you test latency in a Java application?**

> **Never use `System.currentTimeMillis()` for latency measurement** — millisecond resolution is too coarse. Use `System.nanoTime()`. But even that has pitfalls — naive benchmarks mislead because of JIT warmup, dead code elimination, and coordinated omission.
>
> **Tool 1: JMH (Java Microbenchmark Harness) — for micro-benchmarks**
> ```java
> @BenchmarkMode(Mode.AverageTime)
> @OutputTimeUnit(TimeUnit.NANOSECONDS)
> @Warmup(iterations = 5, time = 1)      // 5 × 1s warmup iterations (JIT reaches Tier 4)
> @Measurement(iterations = 10, time = 1)
> @Fork(2)                               // run in fresh JVM to eliminate startup bias
> @State(Scope.Thread)
> public class RiskEngineBenchmark {
>
>     private RiskEngine riskEngine;
>     private Order order;
>
>     @Setup
>     public void setup() {
>         riskEngine = new RiskEngine();
>         order = new Order("TEST-001", "AAPL", Side.BUY, OrderType.LIMIT, 150.0, 100);
>     }
>
>     @Benchmark
>     public RiskEngine.RiskResult riskCheck() {
>         return riskEngine.check(order);  // JMH prevents dead-code elimination via Blackhole
>     }
> }
> // Run: mvn package && java -jar target/benchmarks.jar
> // Output: Avg: 285 ns/op ± 12 ns
> ```
>
> **Tool 2: HDR Histogram — capture the full latency distribution (especially tail)**
> ```java
> // HDR Histogram avoids coordinated omission — captures ALL samples including spikes
> Histogram histogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3); // 3 sig figs
>
> for (int i = 0; i < 100_000; i++) {
>     long start = System.nanoTime();
>     oms.submitOrder(order);
>     histogram.recordValue(System.nanoTime() - start);
> }
>
> // In trading, ALWAYS report p99 and p999 — the tail is what kills you:
> System.out.printf("p50  (median):      %,d ns%n", histogram.getValueAtPercentile(50));
> System.out.printf("p99  (worst 1%%):   %,d ns%n", histogram.getValueAtPercentile(99));
> System.out.printf("p999 (worst 0.1%%): %,d ns%n", histogram.getValueAtPercentile(99.9));
> System.out.printf("max:               %,d ns%n", histogram.getMaxValue());
>
> // Example output:
> // p50  = 1,200 ns   → most orders are fast
> // p99  = 8,500 ns   → 1 in 100 takes 8.5µs — investigate
> // p999 = 45,000 ns  → 1 in 1000 takes 45µs — likely a GC or scheduling event
> // max  = 210,000 ns → one spike of 210µs — almost certainly a GC pause
> ```
>
> **Tool 3: JFR (Java Flight Recorder) — production profiling**
> ```bash
> # Start recording (very low overhead — safe in production):
> jcmd <pid> JFR.start duration=60s filename=trading.jfr settings=profile
>
> # Open in JDK Mission Control:
> #   Method Profiling → find hot methods consuming CPU
> #   Allocation Profiling → find what's creating objects on the hot path
> #   GC Events → visualize pause distribution
> #   Thread Activity → find blocking, lock waits
> ```
>
> **Coordinated omission — the hidden benchmark trap:**
> If measuring latency in a loop, a slow response (GC pause) delays the *next* measurement's start time — hiding the true impact. HDR Histogram's `recordValueWithExpectedInterval()` corrects for this by injecting phantom samples for missed deadlines. Always use it for load-test scenarios.
>
> **Rule for trading:** always report **p99 and p999**, not just average. An average of 5µs is meaningless if p999 is 50ms — that 50ms spike is what costs you money.

---

## What Was Added to This Project — and Why

These components were added to make the project closer to a real front office system.
Each one is a direct answer to an interview question.

---

### `util/ObjectPool.java` — Object Pooling
- **What:** Generic pre-allocated object pool using a circular array + `AtomicInteger`
- **Why in trading:** `new Order()` on every tick = constant Young Gen pressure = Minor GC every few seconds. A pool eliminates allocation entirely on the hot path
- **Interview Q:** *"How do you reduce GC pressure on the hot path?"* → "I pre-allocate a pool of N objects at startup and reuse them — zero allocation at runtime"
- **Key concept:** Power-of-2 sizing + bitwise `& mask` instead of `% size` — same trick as the Disruptor ring buffer
- **Limitation to mention:** Simple ring — works for single-threaded OMS event loop. For multi-threaded: use `ManyToManyConcurrentArrayQueue` from Agrona

---

### `service/WarmupService.java` — JIT Warmup
- **What:** `@PostConstruct` bean that replays 20,000 synthetic orders through the full hot path before market open
- **Why in trading:** Without warmup, the first real orders run at interpreter speed (10–100× slower). JIT compilation on a live system causes latency spikes exactly at market open — the worst possible time
- **Interview Q:** *"How do you ensure consistent latency from market open?"* → "We run a warmup phase before going live — synthetic traffic forces C2 Tier-4 compilation of all critical methods"
- **Key concept:** Methods compile to Tier 4 after ~10,000 invocations (`-XX:CompileThreshold`). Warmup exercises every branch: BUY/SELL, LIMIT/MARKET, pass/fail risk checks

---

### `service/PositionService.java` — Real-Time P&L Tracking
- **What:** Tracks net position, average cost, realized P&L, and unrealized (mark-to-market) P&L per symbol
- **Why in trading:** Every trading system must know in real-time: "am I long or short, by how much, and what is my P&L?" — both for risk limits and for trader visibility
- **Interview Q:** *"How do you calculate P&L in real-time?"* → "Average cost method (AVCO): on each buy, recalculate weighted average cost; on each sell, realize P&L = qty × (fill_price − avg_cost)"
- **Key concept:** `totalRealizedPnlCents` stored as `AtomicLong` in integer cents — avoids floating-point race conditions on the accumulator
- **MiFID II link:** Accurate real-time positions are required for transaction reporting and best execution proof

---

### `algo/TwapExecutor.java` — TWAP Execution Algorithm
- **What:** Splits a large parent order into N equal child orders dispatched at fixed time intervals using `ScheduledExecutorService`
- **Why in trading:** Sending 100,000 shares at once moves the market against you (market impact). TWAP minimises impact by blending into normal market flow over time
- **Interview Q:** *"What execution algorithms have you worked with?"* or *"Implement a TWAP"*
- **Key concept:** `scheduleAtFixedRate` vs `scheduleWithFixedDelay` — fixed-rate is correct here (we want slices at wall-clock intervals, not interval-after-completion)
- **Production concern to mention:** "In production I'd add randomised timing (±10% jitter) so predatory HFT algorithms cannot detect and front-run the pattern"

---

### `service/AuditLogService.java` — Async Write-Behind Audit Log
- **What:** OMS thread writes events to a `BlockingQueue` in ~100ns; a dedicated consumer thread persists them asynchronously
- **Why in trading:** Database writes take milliseconds. Synchronous persistence would add milliseconds of latency to every order — completely unacceptable. The queue decouples the OMS from I/O
- **Interview Q:** *"How do you persist trades without impacting latency?"* → "Write-behind: put a lightweight event on an in-memory queue (non-blocking), let a background thread do the I/O"
- **Key concept:** `offer()` (non-blocking, drops if full) vs `put()` (blocking). Always use `offer()` on the hot path — the OMS thread must never block on persistence
- **MiFID II link:** All orders and trades must be logged with nanosecond timestamps and retained 5 years — this service is the integration point for that obligation
- **Production upgrade:** Replace `ArrayBlockingQueue` with Chronicle Queue — persisted, off-heap, zero GC, survives JVM crash

---

### `util/TradingThreadFactory.java` — Named Thread Factory
- **What:** Creates threads with meaningful names, controlled daemon flags, set priority, and uncaught exception handlers
- **Why in trading:** When diagnosing a latency spike with `jstack`, "pool-3-thread-7" tells you nothing. "order-processor-1" tells you exactly which component is stuck
- **Interview Q:** *"How do you structure your threads in a trading system?"* → "Every thread pool uses a named factory. Hot-path threads are non-daemon with MAX_PRIORITY. Background workers are daemon with NORM_PRIORITY. All threads have an uncaught exception handler that alerts operations"
- **Key concept:** `setUncaughtExceptionHandler` — a silent thread death in a trading system leaves orders unmanaged. You MUST log and alert on any unexpected thread termination
- **Daemon flag rule:** Non-daemon = thread must finish before JVM exits (use for OMS, audit log — so they drain). Daemon = JVM can exit while thread is running (use for market data handlers, background monitors)

---

### `start-trading.sh` — Production JVM Startup Script
- **What:** Shell script with all production JVM flags
- **Why in trading:** JVM defaults are tuned for general-purpose applications, not ultra-low-latency trading. Every flag in this script exists to solve a specific trading problem
- **Interview Q:** *"What JVM flags would you use for a trading system?"* — this script is the answer
- **Key flags to memorise:**

| Flag | Problem it solves |
|---|---|
| `-Xms == -Xmx` | Prevent heap resize GC pause during trading |
| `-XX:+AlwaysPreTouch` | Pre-fault OS memory pages at startup — no page-fault latency at runtime |
| `-XX:+UseZGC` | Sub-millisecond GC pauses (Java 15+) |
| `-XX:+DisableExplicitGC` | Block library calls to `System.gc()` |
| `-Xlog:gc*` | GC logging — essential for diagnosing latency spikes post-mortem |
| `-XX:+HeapDumpOnOutOfMemoryError` | Capture heap state at the moment of OOM |
| `-XX:StartFlightRecording` | Always-on JFR profiling for latency incident analysis |
| `-XX:-RestrictContended` | Enable `@Contended` padding on custom classes |

---

## Topics NOT in `ELECTRONIC_TRADING_JAVA.md` — Study These Too

### JMH — Java Microbenchmark Harness
```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class OrderBookBenchmark {
    private OrderBook book;

    @Setup
    public void setup() { book = new OrderBook("AAPL"); }

    @Benchmark
    public List<Trade> addLimitOrder() {
        Order o = new Order(/* ... */);
        return book.addOrder(o);
    }
}
```
JMH handles JIT warmup, dead code elimination, and gives accurate ns-level measurements.

---

### HDR Histogram (Latency Measurement)
```java
Histogram histogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);

long start = System.nanoTime();
oms.submitOrder(order);
histogram.recordValue(System.nanoTime() - start);

// After test:
System.out.printf("p50  = %d ns%n", histogram.getValueAtPercentile(50));
System.out.printf("p99  = %d ns%n", histogram.getValueAtPercentile(99));
System.out.printf("p999 = %d ns%n", histogram.getValueAtPercentile(99.9));
```

---

### Escape Analysis (JIT optimization)
```java
// JIT may allocate this on the stack instead of heap (escape analysis):
public double calcPnl(long qty, double price) {
    BigDecimal result = new BigDecimal(qty).multiply(BigDecimal.valueOf(price));
    // If 'result' doesn't escape this method → JIT can stack-allocate it → no GC
    return result.doubleValue();
}
```
Enable with `-XX:+DoEscapeAnalysis` (default on). Verify with `-XX:+PrintEscapeAnalysis`.

---

### Java 21+ Virtual Threads (Project Loom)
```java
// In non-latency-critical trading services (REST APIs, reporting):
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
executor.submit(() -> generateTradeReport(portfolioId)); // cheap virtual thread
```
Virtual threads are lightweight (not pinned to OS threads). For I/O-heavy back-office services, they replace reactive patterns. **NOT** for the hot path — use dedicated platform threads there.

---

### Chronicle Queue (Persisted Off-Heap Queue)
Used in production trading systems for durable, zero-GC message passing:
```java
// Writing
try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary("/data/orders").build()) {
    ExcerptAppender appender = queue.acquireAppender();
    try (DocumentContext dc = appender.writingDocument()) {
        dc.wire().write("order").marshallable(order);
    }
}
// Reading — zero-copy, off-heap, microsecond latency
ExcerptTailer tailer = queue.createTailer();
```

---

## JVM Flags Cheat Sheet for Interviews

```bash
# Heap
-Xms8g -Xmx8g              # Fix heap size (no resize pause)
-XX:+AlwaysPreTouch         # Pre-fault all pages at startup

# GC
-XX:+UseZGC                 # Best for < 1ms pauses (Java 15+)
-XX:+UseG1GC                # General purpose
-XX:MaxGCPauseMillis=5      # G1 pause target
-XX:+DisableExplicitGC      # Block System.gc() calls

# GC Logging (always enable)
-Xlog:gc*:file=gc.log:time,uptime

# Heap dump on OOM (always enable)
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/heapdump.hprof

# JIT
-XX:+TieredCompilation       # Default on
-XX:CompileThreshold=500     # Compile sooner (faster warmup)

# Padding (false sharing)
-XX:-RestrictContended       # Allow @Contended outside java.* packages

# Profiling
-XX:StartFlightRecording=duration=60s,filename=app.jfr
```

---

## Live Coding Exercises

> These are the actual coding tasks given in interviews at trading firms and bank tech teams in Paris.
> Each exercise has: the problem statement, what the interviewer is really testing, and a clean solution.

---

### CATEGORY A — Java Core / Concurrency

---

#### Exercise 1 — Thread-Safe Order ID Generator

**Problem:** Implement a class that generates unique, monotonically increasing order IDs. Multiple threads will call `nextId()` concurrently. No ID must ever be duplicated.

**What they test:** Do you reach for `synchronized` (correct but suboptimal) or `AtomicLong` (correct and fast)?

<details>
<summary>▶ Solution</summary>

```java
// Version 1 — synchronized (correct, but blocks threads)
public class OrderIdGenerator {
    private long counter = 0;

    public synchronized long nextId() {
        return ++counter;
    }
}

// Version 2 — AtomicLong (correct AND lock-free, preferred answer)
public class OrderIdGenerator {
    private final AtomicLong counter = new AtomicLong(0);

    public long nextId() {
        return counter.incrementAndGet();  // CAS — atomic, no blocking
    }
}

// Version 3 — if asked "can you make it faster?" (single-threaded hot path)
// Don't share the generator at all — one generator per thread, prefix with thread ID
public class OrderIdGenerator {
    private final long threadPrefix;   // e.g., thread 1 → IDs: 1_000001, 1_000002
    private long localCounter = 0;

    public OrderIdGenerator(long threadId) {
        this.threadPrefix = threadId * 1_000_000L;
    }

    public long nextId() {
        return threadPrefix + (++localCounter);  // zero synchronization
    }
}
```

</details>

**Key point to say:** "In a trading system I'd prefer `AtomicLong` for a shared generator, but ideally each thread owns its own generator to avoid any synchronization on the hot path."

---

#### Exercise 2 — Spot the Race Condition

**Problem:** The interviewer shows you this code and asks: "Is this correct? What can go wrong?"

```java
public class PositionTracker {
    private final Map<String, Long> positions = new HashMap<>();

    // Called from multiple threads after each fill
    public void updatePosition(String symbol, long delta) {
        Long current = positions.get(symbol);
        long newPos  = (current == null ? 0L : current) + delta;
        positions.put(symbol, newPos);
    }

    public long getPosition(String symbol) {
        return positions.getOrDefault(symbol, 0L);
    }
}
```

<details>
<summary>▶ Answer — 3 problems + fix</summary>

**Problems:**
```
Problem 1: HashMap is not thread-safe at all.
  - Concurrent puts can corrupt the internal array (infinite loop in Java 7, data loss in Java 8+)

Problem 2: Even with ConcurrentHashMap, updatePosition() has a race:
  - Thread A reads positions.get("AAPL") = 100
  - Thread B reads positions.get("AAPL") = 100
  - Thread A writes 100 + 50 = 150
  - Thread B writes 100 + 50 = 150   ← lost Thread A's update! Should be 200

Problem 3: Boxed Long → unboxing → NullPointerException risk if map returns null
  and there's a concurrent remove (unlikely here but shows awareness)
```

**Correct fix:**
```java
public class PositionTracker {
    // ConcurrentHashMap + AtomicLong per symbol = lock-free, correct
    private final ConcurrentHashMap<String, AtomicLong> positions = new ConcurrentHashMap<>();

    public void updatePosition(String symbol, long delta) {
        // computeIfAbsent is atomic — no race on first insert
        positions.computeIfAbsent(symbol, k -> new AtomicLong(0))
                 .addAndGet(delta);   // AtomicLong.addAndGet is atomic — no lost update
    }

    public long getPosition(String symbol) {
        AtomicLong pos = positions.get(symbol);
        return pos == null ? 0L : pos.get();
    }
}
```

</details>

---

#### Exercise 3 — Implement a Bounded Blocking Queue (from scratch)

**Problem:** "Implement a thread-safe bounded queue with `put()` (blocks if full) and `take()` (blocks if empty). Do NOT use `java.util.concurrent`."

**What they test:** `wait()` / `notifyAll()` mechanics, monitor pattern, producer-consumer.

<details>
<summary>▶ Solution</summary>

```java
public class BoundedQueue<T> {
    private final Object[] buffer;
    private final int      capacity;
    private int head = 0;   // next read position
    private int tail = 0;   // next write position
    private int size = 0;

    public BoundedQueue(int capacity) {
        this.capacity = capacity;
        this.buffer   = new Object[capacity];
    }

    // Producer: blocks if queue is full
    public synchronized void put(T item) throws InterruptedException {
        while (size == capacity) {      // while (not if) — re-check after wakeup
            wait();                     // releases lock, suspends this thread
        }
        buffer[tail] = item;
        tail = (tail + 1) % capacity;  // ring buffer wrap-around
        size++;
        notifyAll();                    // wake up any waiting consumers
    }

    // Consumer: blocks if queue is empty
    @SuppressWarnings("unchecked")
    public synchronized T take() throws InterruptedException {
        while (size == 0) {             // while (not if) — spurious wakeups
            wait();
        }
        T item = (T) buffer[head];
        buffer[head] = null;            // help GC — don't hold stale reference
        head = (head + 1) % capacity;
        size--;
        notifyAll();                    // wake up any waiting producers
        return item;
    }

    public synchronized int size() { return size; }
}
```

</details>

**Follow-up they will ask:** "Why `while` and not `if` around `wait()`?"
→ Because of **spurious wakeups** — a thread can be woken up by the JVM without `notifyAll()` being called. You must re-check the condition after every wakeup.

**Follow-up 2:** "How would you improve this for lower latency?"
→ Use two separate locks — one for head, one for tail — so producers and consumers don't block each other. This is how `LinkedBlockingQueue` works internally.

---

#### Exercise 4 — Producer-Consumer with Disruptor-style Ring Buffer

**Problem:** "Implement a simple single-producer, single-consumer ring buffer for passing orders between threads. No blocking, no locking."

**What they test:** Understanding of lock-free design, power-of-2 indexing, `volatile` for visibility.

<details>
<summary>▶ Solution</summary>

```java
public class OrderRingBuffer {
    private final int      mask;       // size - 1 (for bitwise modulo)
    private final Order[]  buffer;

    // Each on its own cache line in a real implementation (@Contended)
    private volatile long producerSeq = -1;  // last published sequence
    private volatile long consumerSeq = -1;  // last consumed sequence

    public OrderRingBuffer(int size) {
        // Size MUST be power of 2
        if (Integer.bitCount(size) != 1) throw new IllegalArgumentException("Size must be power of 2");
        this.mask   = size - 1;
        this.buffer = new Order[size];
    }

    // Producer thread — non-blocking publish
    public boolean tryPublish(Order order) {
        long next = producerSeq + 1;
        // Check if slot is free (consumer has read the previous entry at this index)
        if (next - consumerSeq > mask) {
            return false;  // buffer full — back-pressure
        }
        buffer[(int)(next & mask)] = order;  // write to slot
        producerSeq = next;                  // volatile write — makes order visible to consumer
        return true;
    }

    // Consumer thread — non-blocking poll
    public Order tryConsume() {
        long next = consumerSeq + 1;
        if (next > producerSeq) {            // volatile read — see producer's writes
            return null;                     // buffer empty
        }
        Order order = buffer[(int)(next & mask)];
        consumerSeq = next;                  // volatile write — signals producer slot is free
        return order;
    }
}
```

</details>

**Key points to explain:**
- `size & mask` instead of `size % capacity` — bitwise AND is ~1 CPU cycle vs integer divide (~20–40 cycles)
- `volatile` on sequence numbers provides the happens-before guarantee — no `synchronized` needed
- Producer writes data THEN updates `producerSeq` (volatile write = memory barrier, ensures order is visible before sequence)

---

#### Exercise 5 — Implement `cancelOrder` without Locking

**Problem:** "You have a concurrent order map. How do you implement cancel so that you never cancel an already-filled order, without using `synchronized`?"

**What they test:** CAS, `AtomicReference`, state machine thinking.

<details>
<summary>▶ Solution</summary>

```java
public enum OrderStatus { NEW, ACCEPTED, FILLED, CANCELLED }

public class Order {
    private final String id;
    // AtomicReference for lock-free status transitions
    private final AtomicReference<OrderStatus> status =
            new AtomicReference<>(OrderStatus.NEW);

    public Order(String id) { this.id = id; }

    // Returns true if cancel succeeded, false if already filled/cancelled
    public boolean cancel() {
        // CAS: only transitions to CANCELLED if current status is NEW or ACCEPTED
        // If order was FILLED concurrently, CAS fails → return false
        return status.compareAndSet(OrderStatus.NEW,      OrderStatus.CANCELLED)
            || status.compareAndSet(OrderStatus.ACCEPTED, OrderStatus.CANCELLED);
    }

    // Returns true if fill succeeded, false if already cancelled
    public boolean fill() {
        return status.compareAndSet(OrderStatus.ACCEPTED, OrderStatus.FILLED);
    }

    public OrderStatus getStatus() { return status.get(); }
}

// Usage — concurrent cancel + fill race:
// Thread A (exchange fill arrives):  order.fill()   — CAS ACCEPTED → FILLED, returns true
// Thread B (user cancel request):    order.cancel() — CAS fails (status is now FILLED), returns false
// Result: fill wins, cancel correctly rejected
```

</details>

---

### CATEGORY B — Data Structures & Algorithms

---

#### Exercise 6 — Implement a Simple Order Book

**Problem (30-minute coding exercise):** "Implement a limit order book for a single instrument. Support: `addOrder(side, price, qty)`, `cancelOrder(id)`, `getBestBid()`, `getBestAsk()`. Return matched trades when orders cross."

**Skeleton they give you:**
```java
public class OrderBook {
    // You choose the data structure

    public List<Trade> addOrder(long orderId, String side, double price, long qty) { }
    public boolean     cancelOrder(long orderId) { }
    public double      getBestBid() { }
    public double      getBestAsk() { }
}
```

<details>
<summary>▶ Solution</summary>

```java
public class OrderBook {

    // --- Internal order node ---
    private static class Order {
        final long   id;
        final double price;
        long         remainingQty;

        Order(long id, double price, long qty) {
            this.id = id; this.price = price; this.remainingQty = qty;
        }
    }

    // --- Book sides ---
    // Bids: highest price first (reverse order)
    private final TreeMap<Double, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    // Asks: lowest price first (natural order)
    private final TreeMap<Double, Deque<Order>> asks = new TreeMap<>();
    // Fast cancel lookup: orderId → (side, price)
    private final Map<Long, Order>  orderIndex = new HashMap<>();
    private final Map<Long, String> sideIndex  = new HashMap<>();

    public List<Trade> addOrder(long orderId, String side, double price, long qty) {
        Order incoming = new Order(orderId, price, qty);
        orderIndex.put(orderId, incoming);
        sideIndex.put(orderId, side);

        List<Trade> trades = new ArrayList<>();

        if ("BUY".equals(side)) {
            // Match against asks
            while (incoming.remainingQty > 0 && !asks.isEmpty()) {
                Map.Entry<Double, Deque<Order>> bestAsk = asks.firstEntry();
                if (bestAsk.getKey() > price) break;  // not crossable

                Order passive = bestAsk.getValue().peek();
                long matchQty = Math.min(incoming.remainingQty, passive.remainingQty);
                trades.add(new Trade(orderId, passive.id, matchQty, bestAsk.getKey()));
                incoming.remainingQty -= matchQty;
                passive.remainingQty  -= matchQty;

                if (passive.remainingQty == 0) {
                    bestAsk.getValue().poll();
                    if (bestAsk.getValue().isEmpty()) asks.pollFirstEntry();
                }
            }
            // Rest resting quantity in book
            if (incoming.remainingQty > 0) {
                bids.computeIfAbsent(price, k -> new ArrayDeque<>()).offer(incoming);
            }
        } else {  // SELL — symmetric
            while (incoming.remainingQty > 0 && !bids.isEmpty()) {
                Map.Entry<Double, Deque<Order>> bestBid = bids.firstEntry();
                if (bestBid.getKey() < price) break;

                Order passive = bestBid.getValue().peek();
                long matchQty = Math.min(incoming.remainingQty, passive.remainingQty);
                trades.add(new Trade(passive.id, orderId, matchQty, bestBid.getKey()));
                incoming.remainingQty -= matchQty;
                passive.remainingQty  -= matchQty;

                if (passive.remainingQty == 0) {
                    bestBid.getValue().poll();
                    if (bestBid.getValue().isEmpty()) bids.pollFirstEntry();
                }
            }
            if (incoming.remainingQty > 0) {
                asks.computeIfAbsent(price, k -> new ArrayDeque<>()).offer(incoming);
            }
        }
        return trades;
    }

    public boolean cancelOrder(long orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null) return false;

        String side = sideIndex.remove(orderId);
        TreeMap<Double, Deque<Order>> book = "BUY".equals(side) ? bids : asks;
        Deque<Order> level = book.get(order.price);
        if (level != null) {
            level.remove(order);  // O(n) at level — acceptable for interview
            if (level.isEmpty()) book.remove(order.price);
        }
        return true;
    }

    public double getBestBid() { return bids.isEmpty() ? Double.NaN : bids.firstKey(); }
    public double getBestAsk() { return asks.isEmpty() ? Double.NaN : asks.firstKey(); }

    record Trade(long buyOrderId, long sellOrderId, long qty, double price) {}
}
```

</details>

**Complexity to state out loud:**
- `addOrder`: O(k log n) where k = number of levels crossed, n = levels in book
- `cancelOrder`: O(n) at the price level — mention you'd use a `HashSet` or doubly-linked list for O(1)
- `getBestBid/Ask`: O(1) via `TreeMap.firstKey()`

---

#### Exercise 7 — Compute Rolling VWAP

**Problem:** "Given a stream of trades (price, quantity), compute the VWAP (Volume-Weighted Average Price) over a rolling window of the last N trades."

**What they test:** Can you implement a sliding window? Do you avoid recomputing from scratch on each update?

> **What is VWAP (no finance knowledge needed):**
> `VWAP = sum(price × qty) / sum(qty)` — the average price weighted by trade size.
> Used by traders to measure execution quality: "did I buy below VWAP?" = good execution.

<details>
<summary>▶ Solution</summary>

```java
public class RollingVwap {
    private final int windowSize;

    // Ring buffer to evict old trades as new ones arrive
    private final double[] prices;
    private final long[]   quantities;
    private int    head      = 0;   // oldest entry index
    private int    count     = 0;   // current entries in window
    private double sumPxQty  = 0;   // sum(price * qty) — maintained incrementally
    private long   sumQty    = 0;   // sum(qty)         — maintained incrementally

    public RollingVwap(int windowSize) {
        this.windowSize = windowSize;
        this.prices     = new double[windowSize];
        this.quantities = new long[windowSize];
    }

    public double update(double price, long qty) {
        int slot = (head + count) % windowSize;

        if (count == windowSize) {
            // Window full — evict the oldest entry first
            sumPxQty -= prices[head] * quantities[head];
            sumQty   -= quantities[head];
            head      = (head + 1) % windowSize;
            count--;
        }

        // Add new trade
        prices[slot]     = price;
        quantities[slot] = qty;
        sumPxQty += price * qty;
        sumQty   += qty;
        count++;

        return sumQty == 0 ? 0 : sumPxQty / sumQty;
    }

    public double getVwap() {
        return sumQty == 0 ? 0 : sumPxQty / sumQty;
    }
}

// Test:
RollingVwap vwap = new RollingVwap(3);
vwap.update(100.0, 10);   // VWAP = 100.0
vwap.update(102.0, 20);   // VWAP = (1000 + 2040) / 30 = 101.33
vwap.update(101.0, 30);   // VWAP = (1000 + 2040 + 3030) / 60 = 101.17
vwap.update(105.0, 10);   // evicts first trade → VWAP = (2040 + 3030 + 1050) / 60 = 101.17... recalc
```

</details>

**Key point to say:** "I maintain running sums `sumPxQty` and `sumQty` and update them incrementally on each add/evict — O(1) per update instead of O(N) recomputation."

---

#### Exercise 8 — Rate Limiter (Token Bucket)

**Problem:** "Implement a rate limiter that allows at most N orders per second. Thread-safe."

**What they test:** Time-windowed counting, CAS, practical thinking about exchange rules.

<details>
<summary>▶ Solution</summary>

```java
// Simple sliding window counter (used in this project's RiskEngine)
public class RateLimiter {
    private final long    maxPerSecond;
    private volatile long windowStart  = System.currentTimeMillis();
    private final AtomicLong count     = new AtomicLong(0);

    public RateLimiter(long maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }

    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= 1000) {
            // New second — reset window
            // Note: this has a race (two threads can both reset) — acceptable for soft limit
            windowStart = now;
            count.set(0);
        }
        return count.incrementAndGet() <= maxPerSecond;
    }
}

// Better version — token bucket (smoother, no burst at window boundary)
public class TokenBucketRateLimiter {
    private final long    maxTokens;        // bucket capacity
    private final long    refillRateNs;     // nanoseconds per token
    private long          tokens;
    private long          lastRefillNs;

    public TokenBucketRateLimiter(long maxPerSecond) {
        this.maxTokens    = maxPerSecond;
        this.refillRateNs = 1_000_000_000L / maxPerSecond;  // ns between tokens
        this.tokens       = maxPerSecond;
        this.lastRefillNs = System.nanoTime();
    }

    // Single-threaded version (call from OMS event loop — no synchronization needed)
    public boolean tryAcquire() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNs;
        // Refill tokens proportional to elapsed time
        long newTokens = elapsed / refillRateNs;
        if (newTokens > 0) {
            tokens       = Math.min(maxTokens, tokens + newTokens);
            lastRefillNs = now;
        }
        if (tokens > 0) {
            tokens--;
            return true;
        }
        return false;  // rate limit exceeded
    }
}
```

</details>

**When they ask "which is better":**
→ Token bucket: smoother pacing, no boundary burst. Exchange rate limits are typically measured per-second but checked continuously — token bucket matches that model better.

---

#### Exercise 9 — LRU Cache for Market Data

**Problem:** "Implement a fixed-size LRU cache for the last N instrument prices. When full, evict the least recently used."

**What they test:** `LinkedHashMap` knowledge, or ability to build doubly-linked list + HashMap.

<details>
<summary>▶ Solution</summary>

```java
// Clean solution using LinkedHashMap's built-in LRU mode
public class PriceCache {
    private final int capacity;
    private final Map<String, Double> cache;

    public PriceCache(int capacity) {
        this.capacity = capacity;
        // accessOrder=true: get() and put() move the entry to the tail (most recently used)
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Double> eldest) {
                return size() > capacity;  // evict LRU entry when over capacity
            }
        };
    }

    public synchronized void put(String symbol, double price) {
        cache.put(symbol, price);
    }

    public synchronized Double get(String symbol) {
        return cache.get(symbol);  // also promotes to MRU position
    }
}

// Follow-up: thread-safe without synchronized?
// → Use ConcurrentHashMap + explicit LRU tracking, or Caffeine cache library
// → For trading: instruments are typically a small fixed set (<10,000) — a simple
//   ConcurrentHashMap without eviction is usually fine
```

</details>

---

### CATEGORY C — Basic Financial Calculations

> No deep finance formulas needed — these test that you can translate a simple rule into clean code.

---

#### Exercise 10 — Calculate P&L

**Problem:** "Given a list of trades (buy/sell, qty, price), calculate the realized P&L and current position."

<details>
<summary>▶ Solution</summary>

```java
public class PnlCalculator {

    public record Trade(String side, long qty, double price) {}

    public record PnlResult(long position, double realizedPnl, double avgCostPrice) {}

    public PnlResult calculate(List<Trade> trades) {
        long   position    = 0;
        double totalCost   = 0;   // cost basis of current position
        double realizedPnl = 0;

        for (Trade t : trades) {
            if ("BUY".equals(t.side())) {
                totalCost += t.qty() * t.price();
                position  += t.qty();

            } else {  // SELL
                if (position > 0) {
                    // Realize P&L on the sold portion: sell_price - avg_cost_price
                    double avgCost = totalCost / position;
                    realizedPnl   += t.qty() * (t.price() - avgCost);
                    totalCost     -= t.qty() * avgCost;  // reduce cost basis proportionally
                }
                position -= t.qty();
            }
        }

        double avgCostPrice = position == 0 ? 0 : totalCost / position;
        return new PnlResult(position, realizedPnl, avgCostPrice);
    }
}

// Example:
// BUY  100 @ 10.00  → position=100, totalCost=1000, avg=10.00
// BUY  100 @ 12.00  → position=200, totalCost=2200, avg=11.00
// SELL 150 @ 13.00  → realized = 150 × (13.00 - 11.00) = +300
//                   → position=50,  totalCost=550 (50 × 11.00)
```

</details>

**Key phrase:** "I'm using average cost (FIFO would be another option) — average cost is simpler and common for intraday trading books."

---

#### Exercise 11 — Detect a Crossed Market

**Problem:** "Given a bid price and an ask price, detect if the market is crossed (invalid state) or locked (bid == ask)."

<details>
<summary>▶ Solution</summary>

```java
public class MarketStateChecker {

    public enum MarketState { NORMAL, LOCKED, CROSSED }

    public MarketState check(double bestBid, double bestAsk) {
        if (bestBid > bestAsk)  return MarketState.CROSSED;  // invalid: bid above ask
        if (bestBid == bestAsk) return MarketState.LOCKED;   // rare: bid equals ask
        return MarketState.NORMAL;
    }

    // A crossed/locked market in production means:
    // - Data feed error (stale/incorrect prices)
    // - Self-cross risk (your own orders cross each other)
    // → Correct action: pause quoting, alert risk team, do not trade against crossed market
}
```

</details>

---

#### Exercise 12 — Spread and Mid-Price

**Problem:** "Given an order book, calculate: mid-price, spread, and spread in basis points."

<details>
<summary>▶ Solution</summary>

```java
public class MarketMetrics {

    // Mid-price: the fair value between bid and ask
    public double midPrice(double bestBid, double bestAsk) {
        return (bestBid + bestAsk) / 2.0;
        // e.g., bid=149.95, ask=150.05 → mid=150.00
    }

    // Spread: cost of immediately buying then selling
    public double spread(double bestBid, double bestAsk) {
        return bestAsk - bestBid;
        // e.g., 150.05 - 149.95 = 0.10 → you "pay" 10 cents to round-trip
    }

    // Spread in basis points: relative spread (1 bp = 0.01%)
    // Used to compare spreads across instruments with different price levels
    public double spreadBps(double bestBid, double bestAsk) {
        double mid = midPrice(bestBid, bestAsk);
        return (spread(bestBid, bestAsk) / mid) * 10_000;
        // e.g., spread=0.10, mid=150.00 → 0.10/150.00 × 10000 = 6.67 bps
        // A liquid stock like AAPL: ~1-3 bps. Illiquid bond: 50-200 bps.
    }
}
```

</details>

---

#### Exercise 13 — Notional Value and Position Limit Check

**Problem:** "A trader wants to buy 500 shares of AAPL at $195.50. Check: (1) notional value, (2) whether it would breach the position limit of 10,000 shares, (3) total portfolio notional after the trade."

<details>
<summary>▶ Solution</summary>

```java
public class RiskChecker {

    public record RiskCheckResult(boolean passed, String reason, double notional) {}

    private static final double MAX_NOTIONAL_PER_ORDER = 500_000;   // $500k
    private static final long   MAX_POSITION           = 10_000;    // shares

    public RiskCheckResult check(long currentPosition, long orderQty, double orderPrice) {
        // 1. Notional value of this order
        double notional = orderQty * orderPrice;
        // 500 × 195.50 = $97,750

        if (notional > MAX_NOTIONAL_PER_ORDER) {
            return new RiskCheckResult(false,
                String.format("Notional $%.0f exceeds limit $%.0f", notional, MAX_NOTIONAL_PER_ORDER),
                notional);
        }

        // 2. Position limit after trade
        long newPosition = currentPosition + orderQty;
        // e.g., currently long 9,600 + 500 = 10,100 → breach

        if (newPosition > MAX_POSITION) {
            return new RiskCheckResult(false,
                String.format("Position %d would breach limit %d", newPosition, MAX_POSITION),
                notional);
        }

        return new RiskCheckResult(true, "OK", notional);
    }
}
```

</details>

---

### Tips for Live Coding in the Interview

1. **Talk while you code** — interviewers want to hear your reasoning. Say "I'm using a TreeMap here because I need sorted order by price, though I'd switch to an array-indexed ladder in production for O(1) access."

2. **State complexity out loud** — after writing a method, say its time and space complexity. It signals you care about performance.

3. **Mention the production concern** — after writing the naive version, say "in production I would X because Y". Shows depth without over-engineering on a whiteboard.

4. **Handle edge cases visibly** — empty book, zero quantity, null inputs. Mention them even if you don't fully implement them.

5. **Don't reach for `synchronized` on everything** — it signals you don't know lock-free alternatives. Use `AtomicLong`, CAS, or say "single-threaded event loop" first.

6. **Know your Big-O** — `TreeMap`: O(log n) insert/lookup. `HashMap`: O(1) average. `ArrayDeque`: O(1) head/tail. `LinkedList.remove()`: O(n).

---

> "In a trading system, the hot path — from market data tick to order sent — must complete in microseconds. Java achieves this by: using a **single-threaded event loop** (no synchronization on the critical path), passing data between threads via a **Disruptor ring buffer** (pre-allocated, lock-free, no GC), choosing **ZGC** to keep GC pauses under 1ms, fixing the heap size to prevent resize pauses, and **warming up** the JIT before market open so all hot paths run at C2 Tier-4. Risk checks are ordered cheapest-first and complete in under 1 microsecond. The kill switch is a `volatile boolean` — one read is ~5ns overhead."

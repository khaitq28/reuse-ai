# Electronic Trading with Java

## 1. What is Electronic Trading?

Electronic trading is the automated buying and selling of financial instruments (stocks, bonds, FX, derivatives, commodities) through electronic systems without manual intervention. It replaces traditional open-outcry floor trading with computer networks, algorithms, and electronic exchanges.

**Key segments:**
| Segment | Description |
|---|---|
| **Algorithmic Trading** | Rules-based automated order execution |
| **High-Frequency Trading (HFT)** | Ultra-low latency trading, thousands of orders/second |
| **Market Making** | Continuously quote bid/ask to provide liquidity |
| **Direct Market Access (DMA)** | Client sends orders directly to exchange |
| **Dark Pools** | Private exchanges for large block trades |
| **Systematic / Quant Trading** | Model-driven strategy execution |

---

## 2. Core Concepts You Must Know

### Order Types
- **Market Order** — Execute immediately at best available price
- **Limit Order** — Execute only at a specified price or better
- **Stop Order** — Triggered when price reaches a threshold
- **IOC (Immediate or Cancel)** — Fill what you can, cancel the rest
- **FOK (Fill or Kill)** — Fill entire order or cancel entirely
- **GTC (Good Till Cancelled)** — Stays open until filled or manually cancelled
- **Iceberg Order** — Large order split into smaller visible chunks

### Order Lifecycle (FIX States)
```
New → PendingNew → Accepted → PartiallyFilled → Filled
                            → Rejected
                            → Cancelled
                            → Expired
```

### Market Data
- **Level 1** — Best bid/ask (top of book)
- **Level 2 (Order Book)** — Full depth of market
- **Tick Data** — Every individual trade/quote event
- **OHLCV** — Open, High, Low, Close, Volume (bar data)

### Key Metrics
| Metric | Meaning |
|---|---|
| **Latency** | Time from signal to order sent (microseconds in HFT) |
| **Throughput** | Orders processed per second |
| **Slippage** | Difference between expected and actual fill price |
| **Fill Rate** | % of orders successfully filled |
| **P&L** | Profit and Loss |
| **Drawdown** | Peak-to-trough decline in portfolio value |
| **Sharpe Ratio** | Risk-adjusted return |

---

## 3. Industry Protocols & Standards

### FIX Protocol (Financial Information eXchange)
The dominant messaging standard for order routing and execution.

```
8=FIX.4.4|35=D|49=CLIENT|56=BROKER|11=ORD001|55=AAPL|54=1|38=100|40=2|44=150.00|10=XXX
```
- `35=D` → New Order Single
- `54=1` → Buy side
- `40=2` → Limit order
- `44=150.00` → Price

**Common FIX message types:**
| MsgType | Description |
|---|---|
| D | New Order Single |
| F | Order Cancel Request |
| G | Order Cancel/Replace |
| 8 | Execution Report |
| V | Market Data Request |
| W | Market Data Snapshot |
| X | Market Data Incremental Refresh |

### Other Protocols
- **ITCH / OUCH** — NASDAQ native protocols, binary, ultra-low latency
- **FAST (FIX Adapted for Streaming)** — Compressed market data
- **SBE (Simple Binary Encoding)** — Zero-GC binary encoding for FIX
- **WebSocket / REST** — Used in modern crypto exchanges
- **AMQP / Kafka** — Internal messaging between trading systems

---

## 4. Java in Electronic Trading

### Why Java?
- Strong ecosystem (Disruptor, Aeron, Chronicle)
- JVM tuning for low-latency (GC tuning, off-heap memory)
- Type safety for complex financial models
- Excellent concurrency primitives
- QuickFIX/J for FIX protocol
- Widely used in banks (Goldman Sachs, JPMorgan, Deutsche Bank)

### Critical Java Techniques for Low-Latency

#### Avoid GC Pauses
```java
// BAD: Creates objects → triggers GC
String key = orderId + "-" + symbol; // String concatenation = new objects

// GOOD: Reuse objects with object pooling
ObjectPool<Order> orderPool = new ObjectPool<>(Order::new, 1000);
Order order = orderPool.borrow();
```

#### Off-Heap Memory
```java
// Use Direct ByteBuffer to avoid GC
ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024); // 1MB off-heap
```

#### Lock-Free Data Structures
```java
// CAS-based queue — no locking
ConcurrentLinkedQueue<Order> queue = new ConcurrentLinkedQueue<>();

// LMAX Disruptor — ring buffer, fastest inter-thread communication
Disruptor<OrderEvent> disruptor = new Disruptor<>(
    OrderEvent::new, 1024, DaemonThreadFactory.INSTANCE
);
```

#### CPU Affinity & Thread Pinning
```java
// Pin trading thread to dedicated CPU core (via JNA/affinity library)
AffinityLock lock = AffinityLock.acquireCore();
```

#### Busy-Wait (Spin Loop)
```java
// HFT: Spin instead of blocking — avoids thread scheduling latency
while (!orderReady.get()) {
    Thread.onSpinWait(); // hint CPU, avoids power-wasting spin
}
```

### Key Java Libraries
| Library | Purpose |
|---|---|
| **QuickFIX/J** | FIX protocol engine |
| **LMAX Disruptor** | Ultra-fast inter-thread messaging (ring buffer) |
| **Aeron** | Low-latency messaging/IPC (used in HFT) |
| **Chronicle Queue** | Persisted off-heap queue, microsecond latency |
| **Agrona** | Lock-free data structures, off-heap primitives |
| **SBE (Simple Binary Encoding)** | Zero-GC binary codec for market data |
| **Netty** | Async non-blocking network I/O |
| **RxJava / Project Reactor** | Reactive streams for market data pipelines |
| **Apache Kafka** | Event streaming for trade/market data |

---

## 5. Architecture of a Trading System

```
┌─────────────────────────────────────────────────────────┐
│                  TRADING SYSTEM                         │
│                                                         │
│  Market Data Feed                                       │
│  (FIX/ITCH/WebSocket) ──► Feed Handler ──► Order Book  │
│                                                ▼        │
│                                        Strategy Engine  │
│                                                ▼        │
│                                         Risk Engine     │
│                                         (Pre-trade)     │
│                                                ▼        │
│                                         Order Router    │
│                                                ▼        │
│                                         OMS / EMS       │
│                                                ▼        │
│                               Exchange / Broker (FIX)   │
│                                                ▼        │
│                               Execution Reports ──► PnL │
└─────────────────────────────────────────────────────────┘
```

**Components:**
- **Feed Handler** — Receives and normalizes raw market data
- **Order Book** — Maintains bid/ask levels for each instrument
- **Strategy Engine** — Signal generation (alpha), decides when to trade
- **Risk Engine** — Pre-trade checks (position limits, notional limits)
- **OMS (Order Management System)** — Manages order lifecycle
- **EMS (Execution Management System)** — Smart order routing, algos

---

## 6. Risk Management (Pre-Trade)

Pre-trade risk checks must complete in < 1 microsecond in HFT:
- **Position limit check** — Don't exceed max position per instrument
- **Notional limit** — Don't exceed max trade value (e.g., $10M per order)
- **Rate limiting** — Max orders per second (exchange rules)
- **Fat finger check** — Price too far from market (e.g., > 10% away)
- **Credit check** — Sufficient buying power / margin

---

## 7. Interview Questions — Java Developer in Electronic Trading

---

### A. JVM Architecture

**Q1: Explain the JVM architecture. What are its main components?**

> The JVM (Java Virtual Machine) is an abstract computing machine that runs Java bytecode. Its main components are:
>
> ```
> ┌─────────────────────────────────────────────────┐
> │                    JVM                          │
> │                                                 │
> │  ┌────────────┐   ┌──────────────────────────┐  │
> │  │  Class     │   │     Runtime Data Areas   │  │
> │  │  Loader    │──►│  Method Area (Metaspace) │  │
> │  │  Subsystem │   │  Heap                    │  │
> │  └────────────┘   │  JVM Stack (per thread)  │  │
> │                   │  PC Register (per thread)│  │
> │  ┌────────────┐   │  Native Method Stack     │  │
> │  │ Execution  │   └──────────────────────────┘  │
> │  │  Engine    │                                 │
> │  │ (JIT/GC)  │                                 │
> │  └────────────┘                                 │
> └─────────────────────────────────────────────────┘
> ```
>
> **Class Loader Subsystem** — Loads `.class` files into memory in three phases:
> - **Loading**: reads `.class` bytecode from disk/network
> - **Linking**: verifies bytecode, prepares static fields (default values), resolves symbolic references
> - **Initialization**: runs static initializers, sets static field values
>
> Three built-in class loaders:
> - **Bootstrap ClassLoader** — loads `java.lang.*`, `java.util.*` (JDK core) from `rt.jar`
> - **Platform ClassLoader** (Extension in Java 8) — loads `javax.*`, extensions
> - **Application ClassLoader** — loads your application classes from the classpath
> Parent delegation model: before loading, always asks parent first — prevents malicious code from replacing core classes.
>
> **Runtime Data Areas** — Memory regions used during execution (see Q2/Q3).
>
> **Execution Engine** — Converts bytecode to native machine instructions:
> - **Interpreter**: executes bytecode line by line — slow but starts immediately
> - **JIT Compiler (C1/C2)**: compiles hot methods to native code at runtime — fast after warmup
> - **Garbage Collector**: manages heap memory automatically

---

**Q2: What is the difference between Heap and Stack in the JVM? What lives in each?**

> | | **Heap** | **Stack** |
> |---|---|---|
> | **Scope** | Shared across all threads | One per thread (private) |
> | **What lives there** | All objects, arrays, instance variables | Stack frames: local variables, method parameters, return addresses |
> | **Lifetime** | Until GC collects (no references) | Lives and dies with the method call |
> | **Size** | Large (configured with `-Xmx`) | Small, fixed per thread (default ~512KB–1MB) |
> | **Error** | `OutOfMemoryError` | `StackOverflowError` |
> | **Speed** | Slower (GC overhead, fragmentation) | Faster (LIFO push/pop, no GC) |
>
> **What goes on the Stack:**
> ```java
> public double calculatePnl(long qty, double price) {
>     // qty, price — stored on stack (primitives)
>     double cost = qty * price;  // cost — stored on stack
>     return cost;                // return value
> } // stack frame popped — all local vars gone
> ```
>
> **What goes on the Heap:**
> ```java
> Order order = new Order("ORD-001", "AAPL", ...);
> // The Order object → Heap
> // The reference variable 'order' → Stack (points to Heap)
> ```
>
> **Key rule:** Primitives declared inside a method → Stack. Objects (`new`) → always Heap. Instance variables (fields of an object) → Heap, alongside their object.
>
> **In trading:** heavy object allocation on the hot path → frequent GC → latency spikes. Solution: reuse objects (object pools), use primitives instead of boxed types, or use off-heap memory (`ByteBuffer.allocateDirect`) which is outside the GC-managed heap entirely.

---

**Q3: What are all the JVM memory regions? Explain each one.**

> The JVM has 5 runtime memory regions:
>
> **1. Heap** — GC-managed, shared by all threads. Divided into:
> - **Young Generation (Eden + 2 Survivor spaces S0/S1)**: new objects allocated here. Minor GC runs here frequently.
> - **Old Generation (Tenured)**: long-lived objects promoted from Young Gen. Major/Full GC cleans this.
>
> **2. Method Area / Metaspace** — Stores class metadata:
> - Class definitions, method bytecode, static variables, constant pool
> - In Java 8+: called **Metaspace**, stored in native memory (not heap) — can grow dynamically
> - In Java 7 and earlier: called **PermGen**, on heap — notorious for `OutOfMemoryError: PermGen space`
>
> **3. JVM Stack** (per thread) — Stores stack frames. Each method call pushes a frame containing:
> - Local variables (primitives + object references)
> - Operand stack (working area for bytecode instructions)
> - Reference to current method's constant pool
>
> **4. PC Register** (per thread) — Program Counter: holds the address of the currently executing JVM instruction. Zero-overhead, just a pointer.
>
> **5. Native Method Stack** (per thread) — Used for native (`JNI`) method calls. Works like the JVM stack but for C/C++ native code.
>
> **Memory diagram:**
> ```
> ┌──────────────────────────────────────────────┐
> │                  HEAP                        │
> │  ┌─────────────────────┐  ┌───────────────┐  │
> │  │   Young Generation  │  │  Old Gen      │  │
> │  │  ┌─────┬────┬────┐  │  │  (long-lived) │  │
> │  │  │Eden │ S0 │ S1 │  │  │               │  │
> │  │  └─────┴────┴────┘  │  └───────────────┘  │
> │  └─────────────────────┘                     │
> └──────────────────────────────────────────────┘
>
> ┌──────────────┐  ┌──────────────┐  ┌──────────┐
> │  Metaspace   │  │  JVM Stack   │  │  PC Reg  │
> │ (class meta) │  │(per thread)  │  │(per thrd)│
> └──────────────┘  └──────────────┘  └──────────┘
> ```

---

**Q4: Walk me through the full lifecycle of an object in the JVM heap — from creation to garbage collection.**

> ```
> new Order() → Eden → [Minor GC] → Survivor S0 → [Minor GC] → S1 → ... → Old Gen → [Major GC] → collected
> ```
>
> **Step by step:**
>
> 1. **Allocation in Eden**: `new Order(...)` — object allocated in the Eden space of Young Generation using a fast **TLAB (Thread-Local Allocation Buffer)**. Each thread has its own TLAB slice so multiple threads can allocate without contention.
>
> 2. **Minor GC (Young Gen collection)**: When Eden fills up, a Minor GC runs. It's fast because most objects die young (most orders are short-lived). Live objects are copied to Survivor space S0. Dead objects are reclaimed instantly — their memory is freed.
>
> 3. **Survivor spaces (S0 / S1)**: Live objects bounce between S0 and S1 on each Minor GC. Each time an object survives a GC, its **age counter** increments.
>
> 4. **Promotion to Old Generation**: When an object's age reaches `MaxTenuringThreshold` (default 15), it is promoted to the Old Generation (Tenured space). In trading: long-lived objects like the OMS order map, position table, risk limits tend to end up here.
>
> 5. **Major / Full GC**: When Old Gen fills up, a Major GC (or Full GC) runs — this is expensive. Full GC compacts the entire heap, which can cause Stop-The-World (STW) pauses of hundreds of milliseconds. Catastrophic in trading.
>
> 6. **Collection**: Once no references point to the object (it becomes unreachable from any GC root — thread stacks, static fields, JNI refs), it is eligible for collection. The GC reclaims its memory.
>
> **GC roots** (objects the GC always considers alive):
> - Local variables on any active thread's stack
> - Static fields of loaded classes
> - JNI references
> - Active threads themselves

---

### B. Garbage Collection

**Q5: How does Garbage Collection work in Java? Explain mark-and-sweep and generational hypothesis.**

> Java GC is based on **automatic reachability analysis** — the GC periodically finds all reachable objects starting from GC roots, and reclaims everything else.
>
> **Mark-and-Sweep algorithm (conceptual basis):**
> 1. **Mark phase**: Traverse all object references from GC roots. Mark every reachable object.
> 2. **Sweep phase**: Scan the entire heap. Any object not marked → dead → memory reclaimed.
> 3. **Compact phase** (Mark-Sweep-Compact): Move live objects together to eliminate fragmentation. Fragmentation wastes memory and slows allocation.
>
> **Generational Hypothesis:**
> Empirical observation: *most objects die young*. In a trading system, an `Order` object might live for milliseconds — it's created, matched, and discarded. Only a few objects (OMS state, position map) live long.
>
> This is why Java splits the heap into Young and Old generations:
> - Minor GC only scans Young Gen — fast, because it's small and most objects are dead
> - Old Gen GC is rare and expensive — but needed less often
>
> ```
> Object survival rate (typical):
> After 1st GC:   ~70% of objects are already dead
> After 2nd GC:   ~90% dead
> After 3rd GC:   ~97% dead
> Only ~3% ever reach Old Gen
> ```
>
> **Tri-color marking (used by G1, ZGC, Shenandoah):**
> - **White**: not yet visited — potentially garbage
> - **Grey**: discovered but children not yet scanned
> - **Black**: fully scanned — definitely alive
> The GC processes grey objects until none remain. Remaining white objects are dead.

---

**Q6: Compare the major Java GC algorithms. When would you choose each in a trading system?**

> | GC | Pause Type | Pause Duration | Throughput | Best For |
> |---|---|---|---|---|
> | **Serial GC** | STW | Hundreds of ms | Low | Single-threaded / tiny heap — not for trading |
> | **Parallel GC** | STW | Tens of ms | High | Batch processing, overnight jobs |
> | **G1GC** | Mostly concurrent + short STW | 1–10ms | Good | General purpose, large heaps (4–32GB) |
> | **ZGC** | Concurrent, tiny STW | < 1ms | Good | Low-latency trading apps, Java 15+ |
> | **Shenandoah** | Concurrent | < 1ms | Good | Alternative to ZGC (RedHat, Java 12+) |
>
> **Serial GC** (`-XX:+UseSerialGC`): Single-threaded. Stops everything. Not viable in trading.
>
> **Parallel GC** (`-XX:+UseParallelGC`): Multiple GC threads, but still STW. Good for high throughput batch work (end-of-day processing, report generation).
>
> **G1GC** (`-XX:+UseG1GC`): Splits heap into equal-sized **regions** (~1–32MB). Collects the most-garbage regions first ("Garbage First"). Mixed collections handle Old Gen incrementally. STW pauses are short and predictable. Good choice for most trading back-office and OMS applications.
> ```
> Key G1 flags:
> -XX:MaxGCPauseMillis=5    ← target pause (not guaranteed)
> -XX:G1HeapRegionSize=16m  ← region size
> -XX:InitiatingHeapOccupancyPercent=45  ← when to start concurrent marking
> ```
>
> **ZGC** (`-XX:+UseZGC`, Java 15+): Entirely concurrent — GC work happens while the application runs. Uses **colored pointers** and **load barriers** to track object state. Sub-millisecond STW pauses regardless of heap size (tested up to 16TB). Best choice for latency-sensitive trading applications.
> ```
> -XX:+UseZGC
> -Xms8g -Xmx8g          ← fix heap to avoid resizing
> -XX:+AlwaysPreTouch     ← pre-fault all pages at startup
> ```
>
> **Shenandoah**: Similar to ZGC but uses a different concurrent compaction approach (Brooks pointers). Developed by RedHat. Also sub-millisecond. Good alternative when ZGC is not available.

---

**Q7: What is a Stop-The-World (STW) pause? Why is it catastrophic in electronic trading?**

> A **Stop-The-World pause** is a period during GC where the JVM suspends ALL application threads — including the trading thread — so the GC can safely traverse and manipulate the heap without objects being modified concurrently.
>
> During an STW pause:
> - No orders can be submitted
> - No market data can be processed
> - No risk checks can run
> - Exchange connectivity may time out
>
> **Why it's catastrophic in trading:**
> ```
> Timeline:
>   ... order processing ... [STW: 200ms] ... order processing ...
>                             ↑
>                      During this 200ms:
>                      - 10,000 price ticks missed
>                      - Open orders not managed
>                      - FIX heartbeat may timeout → session dropped
>                      - Market moved 0.5% against your position
> ```
> Even a 50ms pause can mean the difference between profit and loss. In HFT, a 1ms pause is unacceptable.
>
> **How modern GCs minimize STW:**
> - **Concurrent marking**: scan live objects while app threads run (G1, ZGC)
> - **Incremental collection**: collect small regions at a time (G1)
> - **Concurrent compaction**: compact heap while app runs (ZGC, Shenandoah)
> - **Short required STW**: only a brief initial-mark and final remark phase need STW
>
> **In practice:** even with ZGC, a trading system should:
> - Keep heap small and fixed size (`-Xms == -Xmx`)
> - Minimize allocations on the hot path (no new objects per tick)
> - Pre-touch the heap at startup (`-XX:+AlwaysPreTouch`)

---

**Q8: What is the difference between Minor GC, Major GC, and Full GC?**

> | | **Minor GC** | **Major GC** | **Full GC** |
> |---|---|---|---|
> | **Region collected** | Young Gen only | Old Gen only | Entire heap + Metaspace |
> | **Frequency** | Frequent (seconds) | Infrequent (minutes) | Rare, often triggered by failure |
> | **Duration** | Fast (1–10ms) | Slower (10–100ms) | Slowest (100ms–seconds) |
> | **STW** | Yes, but short | Varies (G1: incremental) | Yes, longest pause |
> | **Trigger** | Eden space full | Old Gen threshold | Explicit `System.gc()`, promotion failure, Metaspace full |
>
> **Promotion failure** (triggers Full GC): Minor GC tries to promote an object from Young → Old Gen, but Old Gen has no room → emergency Full GC. Avoid by: not creating too many long-lived objects, setting `NewRatio` appropriately.
>
> **In trading:** aim for Minor GC only. If Major/Full GC occurs during market hours, investigate what is being promoted to Old Gen. Common culprits:
> - Large collections (order maps) that never shrink
> - Caches without eviction policies
> - Threads that hold references to order objects longer than needed
> - String interning or excessive logging

---

**Q9: What causes `OutOfMemoryError`? How do you diagnose and fix it in a trading application?**

> `OutOfMemoryError` (OOM) has several distinct forms:
>
> **1. `java.lang.OutOfMemoryError: Java heap space`**
> - Heap is full, GC cannot free enough memory
> - Causes: memory leak (objects held in static maps/caches), undersized heap, data structure growing unboundedly
> - Fix: increase `-Xmx`, fix the leak, add eviction to caches
>
> **2. `java.lang.OutOfMemoryError: Metaspace`**
> - Too many class definitions loaded (class loader leak, heavy runtime code generation)
> - Common in apps using reflection, proxies, CGLIB (Spring)
> - Fix: `-XX:MaxMetaspaceSize=256m` to set a limit; investigate class loader leaks
>
> **3. `java.lang.OutOfMemoryError: GC overhead limit exceeded`**
> - JVM spent > 98% of time doing GC but recovered < 2% heap — effectively a live-lock
> - Fix: reduce allocation rate on hot path, increase heap, fix memory leak
>
> **4. `java.lang.OutOfMemoryError: Direct buffer memory`**
> - Off-heap `ByteBuffer.allocateDirect()` exceeded limit
> - Fix: `-XX:MaxDirectMemorySize=2g`, check for unreleased direct buffers
>
> **5. `java.lang.StackOverflowError`** (not OOM, but related)
> - Stack depth exceeded (infinite recursion)
> - Fix: `-Xss2m` to increase stack size, fix the recursion
>
> **Diagnosis steps:**
> ```bash
> # 1. Get heap dump on OOM (always set this in production)
> -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/trading/heapdump.hprof
>
> # 2. Analyze with Eclipse MAT or VisualVM
> #    Look for: largest retained heap, memory leak suspects, dominator tree
>
> # 3. Enable GC logging to watch trends
> -Xlog:gc*:file=gc.log:time,uptime
>
> # 4. Use JFR (Java Flight Recorder) for live profiling
> -XX:StartFlightRecording=duration=60s,filename=trading.jfr
> ```
>
> **In a trading system:** the most common OOM is an unbounded order cache — orders are inserted but never removed after fill/cancel. Always enforce a max size or TTL eviction on any in-memory store.

---

**Q10: What is JIT compilation? Explain tiered compilation and how it affects trading system warmup.**

> The JVM does not compile Java source directly to native code at startup. Instead it uses a two-phase approach:
>
> **Phase 1 — Interpreter**: Bytecode is executed line by line immediately. Slow, but starts instantly with no warmup needed.
>
> **Phase 2 — JIT Compiler (Just-In-Time)**: The JVM monitors which methods are called frequently ("hot methods"). When a method exceeds a call threshold, the JIT compiles it to optimized native machine code. Future calls go directly to native code — very fast.
>
> **Tiered Compilation** (`-XX:+TieredCompilation`, default since Java 8):
> | Tier | Compiler | What it does |
> |---|---|---|
> | 0 | Interpreter | Execute bytecode |
> | 1–2 | C1 (client compiler) | Fast compile, light optimizations, start profiling |
> | 3 | C1 | Full profiling (branch frequencies, type profiles) |
> | 4 | C2 (server compiler) | Aggressive optimizations based on profile data |
>
> C2 optimizations include: inlining, loop unrolling, escape analysis (stack allocation instead of heap), dead code elimination, speculative optimizations.
>
> **Why warmup matters in trading:**
> - At startup, the trading thread runs on the interpreter — 10–100x slower than native
> - The first ~10,000 calls to a hot method run at lower optimization tiers
> - A live trading system going through JIT compilation during market open = unpredictable latency spikes
>
> **Solution — warmup before market open:**
> ```java
> // Replay synthetic market data before live trading starts
> // This forces JIT to compile all hot paths at Tier 4
> for (int i = 0; i < 50_000; i++) {
>     Order warmupOrder = new Order("WARMUP-" + i, "AAPL", Side.BUY,
>                                    OrderType.LIMIT, 150.0, 100);
>     oms.submitOrder(warmupOrder);   // forces JIT of submitOrder + all callees
>     riskEngine.check(warmupOrder);  // forces JIT of risk checks
> }
> // Now all hot paths are compiled at Tier 4 — consistent latency
> ```

---

**Q11: What is GC tuning? What are the most important JVM flags for a trading application?**

> GC tuning is the process of configuring the JVM so that GC pauses are minimized during market hours. Key principles: **fix heap size, choose the right GC, reduce allocation rate, warm up before trading**.
>
> ```bash
> # ── Heap size: always set Xms == Xmx to prevent heap resizing pauses ──
> -Xms8g -Xmx8g
>
> # ── Pre-touch heap pages at startup (avoids OS page faults during trading) ──
> -XX:+AlwaysPreTouch
>
> # ── GC selection ──
> -XX:+UseZGC                          # Best for latency-sensitive (Java 15+)
> # OR
> -XX:+UseG1GC                         # Good for general use
> -XX:MaxGCPauseMillis=5               # G1 pause target (best-effort)
> -XX:G1HeapRegionSize=16m             # Tune region size for your heap
>
> # ── Young generation sizing ──
> -XX:NewRatio=2                       # Old:Young = 2:1  (33% Young)
> -XX:SurvivorRatio=8                  # Eden:Survivor = 8:1
>
> # ── Tenuring: how many GCs before promoting to Old Gen ──
> -XX:MaxTenuringThreshold=4           # Promote faster if objects ARE long-lived
>
> # ── Prevent full GC from explicit calls ──
> -XX:+DisableExplicitGC
>
> # ── GC logging (always enable in production) ──
> -Xlog:gc*:file=/var/log/gc.log:time,uptime:filecount=5,filesize=20m
>
> # ── Heap dump on OOM (always enable in production) ──
> -XX:+HeapDumpOnOutOfMemoryError
> -XX:HeapDumpPath=/var/log/heapdump.hprof
>
> # ── JIT tuning: force early compilation ──
> -XX:CompileThreshold=500             # Lower threshold = compile sooner (faster warmup)
> -XX:+TieredCompilation               # Default on, keep it on
> ```
>
> **Most impactful single change in trading:** switching from default GC to ZGC. Eliminates multi-millisecond pauses with essentially zero configuration change.

---

### C. Core Java Concurrency

**Q12: Explain `volatile`, `AtomicLong`, and `synchronized` in detail. What guarantees does each provide?**

> These are three levels of synchronization in Java, with different cost/guarantee tradeoffs:
>
> **`volatile`**
> - Guarantees **visibility**: a write by Thread A is immediately visible to Thread B
> - Guarantees **ordering**: prevents CPU/compiler reordering across the volatile access
> - Does NOT guarantee **atomicity**: `counter++` is read-modify-write — three operations, not one
> - Implementation: CPU memory barrier (MFENCE on x86) — very cheap
>
> ```java
> private volatile boolean orderReady = false;
> private double price;
>
> // Thread A
> price = 150.0;        // (1) write price
> orderReady = true;    // (2) volatile write — flush everything before this
>
> // Thread B
> if (orderReady) {     // (3) volatile read — see all writes before (2)
>     use(price);       // (4) guaranteed to see 150.0
> }
>
> // BAD use of volatile:
> volatile int count = 0;
> count++;  // NOT atomic: read(0) + add(1) + write(1) — two threads can both read 0
> ```
>
> **`AtomicLong` / `AtomicInteger` / `AtomicReference`**
> - Guarantees **visibility + atomicity** for single-variable operations
> - Uses **CAS (Compare-And-Swap)** — a single CPU instruction that is atomic by hardware guarantee
> - No blocking, no thread suspension — just a retry loop
> - Slightly more expensive than `volatile` (CAS has retry cost under contention), much cheaper than `synchronized`
>
> ```java
> AtomicLong orderId = new AtomicLong(0);
> orderId.incrementAndGet();       // atomic: safe from multiple threads
> orderId.compareAndSet(5, 6);     // CAS: only sets to 6 if current value is 5
>
> AtomicLong filledQty = new AtomicLong(0);
> // Safe partial fill accumulation:
> filledQty.addAndGet(fillAmount); // atomic add — no race condition
> ```
>
> **`synchronized`**
> - Guarantees **visibility + atomicity + mutual exclusion** for an entire block
> - Only one thread can hold the monitor at a time — others block (OS-level)
> - Most expensive: thread suspension, context switch, cache coherence flush
> - Safe for compound operations (check-then-act, read-modify-write on multiple variables)
>
> ```java
> // Synchronized method — 'this' is the monitor
> public synchronized void applyFill(long qty, double fillPrice) {
>     if (filledQty + qty > totalQty) return;  // safe: no other thread can enter
>     filledQty += qty;
>     avgFillPrice = recalculateAvg(filledQty, qty, fillPrice); // multi-step: safe
> }
> ```
>
> **When to use what in trading:**
> | Scenario | Use |
> |---|---|
> | Simple flag (kill switch, session status) | `volatile` |
> | Counter (order ID, fill count) | `AtomicLong` |
> | Object hand-off between threads | `AtomicReference` |
> | Multi-step compound operation | `synchronized` or `ReentrantLock` |
> | Hot path with no contention | Single-threaded (no synchronization needed) |

---

**Q13: What is false sharing? How does it affect a trading system and how do you fix it?**

> **False sharing** occurs when two threads on different CPU cores modify different variables that happen to reside on the same CPU cache line (typically 64 bytes).
>
> The CPU cache coherence protocol (MESI) treats the cache line as the unit of synchronization. When Thread A writes variable `a` and Thread B writes variable `b`, even though they are independent, the CPU invalidates the entire cache line on the other core — forcing it to re-fetch from main memory.
>
> **Impact:**
> - Each write by one thread causes a cache miss on the other thread's core
> - Performance drops to near-main-memory speed (~200 cycles vs 4 cycles for L1 cache)
> - In HFT: a 200 cycle miss = ~100ns extra latency per operation = microseconds of unnecessary overhead per tick
>
> **Classic trading example:**
> ```java
> // BAD: two frequently updated counters sharing a cache line
> public class OrderStats {
>     long buyOrderCount;   // bytes 0-7
>     long sellOrderCount;  // bytes 8-15
>     // Both fields fit in same 64-byte cache line!
>     // Thread A (buy processor) and Thread B (sell processor) fight over the line
> }
> ```
>
> **Fix 1: Padding manually**
> ```java
> public class OrderStats {
>     long buyOrderCount;
>     long p1, p2, p3, p4, p5, p6, p7; // 7 longs = 56 bytes padding
>     // Now buyOrderCount occupies its own 64-byte cache line
>     long sellOrderCount;
>     long q1, q2, q3, q4, q5, q6, q7;
> }
> ```
>
> **Fix 2: `@Contended` annotation (Java 8+)**
> ```java
> import sun.misc.Contended;
>
> public class OrderStats {
>     @Contended
>     long buyOrderCount;  // JVM pads this field to its own cache line automatically
>
>     @Contended
>     long sellOrderCount;
> }
> // Must also add JVM flag: -XX:-RestrictContended
> ```
>
> **Fix 3: Separate objects on separate threads** — the cleanest solution. If `buyOrderCount` is only ever touched by the buy thread, keep it in a thread-local or a class that only that thread owns.

---

**Q14: Explain the Java Memory Model (JMM) and `happens-before`. Why is it critical in trading?**

> The **Java Memory Model** defines the rules for how threads interact through memory. Without it, CPUs and compilers are free to reorder operations — what you write in Java may not execute in that order.
>
> A **happens-before** relationship guarantees: if action A happens-before action B, then all side effects of A (writes to memory) are visible to B.
>
> **Happens-before rules (Java spec):**
> 1. **Program order**: each action in a thread happens-before every subsequent action in that thread
> 2. **Monitor lock**: `unlock(m)` happens-before every subsequent `lock(m)` — `synchronized` visibility
> 3. **Volatile**: write to `volatile x` happens-before every subsequent read of `x`
> 4. **Thread start**: `t.start()` happens-before any action in thread `t`
> 5. **Thread join**: all actions in thread `t` happen-before `t.join()` returns
> 6. **Transitivity**: if A hb B and B hb C, then A hb C
>
> **Why it matters in trading — example without happens-before:**
> ```java
> // Thread A (market data parser) — no synchronization
> order.symbol = "AAPL";         // (1)
> order.price  = 150.0;          // (2)
> order.quantity = 100;          // (3)
> newOrderAvailable = true;      // (4) simple boolean, not volatile
>
> // Thread B (order sender) — may see this:
> if (newOrderAvailable) {       // (5) sees true — but...
>     send(order.price);         // (6) might see 0.0 !! CPU reordered (2) after (4)
> }
> ```
>
> **Fix — establish happens-before with volatile:**
> ```java
> // Write all order fields BEFORE the volatile write
> order.symbol = "AAPL";
> order.price  = 150.0;
> order.quantity = 100;
> volatile newOrderAvailable = true;  // volatile write: flushes ALL prior writes
>
> // Thread B
> if (newOrderAvailable) {            // volatile read: guaranteed to see all writes above
>     send(order.price);              // sees 150.0 — safe
> }
> ```
>
> Missing happens-before in a trading system can cause: stale prices, partial order state visible to sender, phantom orders from CPU reordering. These bugs are **intermittent and extremely hard to reproduce** in testing.

---

**Q15: What is a race condition? Show a concrete trading example and three ways to fix it.**

> A race condition occurs when the correctness of a program depends on the relative timing/interleaving of threads, and some interleavings produce wrong results.
>
> **Trading example: double fill (the most dangerous race in OMS)**
> ```java
> // An Order object is processed by two fill threads simultaneously
> public class Order {
>     private long filledQty = 0;
>     private final long totalQty;
>
>     // BROKEN — race condition:
>     public void applyFill(long qty) {
>         if (filledQty + qty <= totalQty) {  // Thread A: filledQty=0, check passes (0+80<=100)
>                                              // Thread B: filledQty=0, check passes (0+80<=100) — SAME!
>             filledQty += qty;               // Thread A writes 80, Thread B also writes 80
>             // Result: filledQty = 80 (lost update) or 160 (overfill) depending on timing
>         }
>     }
> }
> ```
>
> **Fix 1: `synchronized` — simplest, safest**
> ```java
> public synchronized void applyFill(long qty) {
>     // Only one thread enters at a time — no race possible
>     if (filledQty + qty <= totalQty) {
>         filledQty += qty;
>     }
> }
> ```
> Downside: blocks contending threads — not suitable for hot path.
>
> **Fix 2: CAS loop with `AtomicLong` — lock-free**
> ```java
> private final AtomicLong filledQty = new AtomicLong(0);
>
> public boolean applyFill(long qty) {
>     long current, newValue;
>     do {
>         current = filledQty.get();
>         newValue = current + qty;
>         if (newValue > totalQty) return false; // reject: would overfill
>         // compareAndSet atomically: only writes if value is still 'current'
>         // If another thread changed filledQty, CAS fails → retry with fresh value
>     } while (!filledQty.compareAndSet(current, newValue));
>     return true;
> }
> ```
> Advantages: no blocking, no thread suspension. Works well under low-to-medium contention.
>
> **Fix 3: Single-threaded event loop — eliminates the problem entirely**
> ```java
> // Route ALL fill events through a single thread via a queue
> // No two threads ever touch the same Order object simultaneously
> // Zero synchronization cost — the best option for the hot path
> ExecutorService singleThread = Executors.newSingleThreadExecutor();
> singleThread.submit(() -> order.applyFill(qty));
> ```

---

**Q16: What is `ReentrantLock` vs `ReadWriteLock` vs `StampedLock`? When would you use each?**

> All three are in `java.util.concurrent.locks` and offer more control than `synchronized`.
>
> **`ReentrantLock`**
> - Same mutual exclusion as `synchronized` but with extra features: `tryLock()`, `lockInterruptibly()`, timed lock, fairness policy
> - **Reentrant**: same thread can acquire it multiple times without deadlock (must release same number of times)
> ```java
> ReentrantLock lock = new ReentrantLock();
>
> // try-lock with timeout — useful in trading to avoid blocking forever
> if (lock.tryLock(1, TimeUnit.MICROSECONDS)) {
>     try {
>         updateOrderBook(order);
>     } finally {
>         lock.unlock(); // ALWAYS unlock in finally
>     }
> } else {
>     // couldn't acquire — handle gracefully (log, retry, reject)
> }
> ```
>
> **`ReadWriteLock` (`ReentrantReadWriteLock`)**
> - Two locks: read lock (shared — multiple threads concurrently) + write lock (exclusive)
> - Optimized for read-heavy workloads: many readers never block each other
> - Only blocks when a writer holds the lock
> ```java
> ReadWriteLock rwl = new ReentrantReadWriteLock();
>
> // Many threads reading position simultaneously — no blocking
> rwl.readLock().lock();
> try { return positionMap.get(symbol); }
> finally { rwl.readLock().unlock(); }
>
> // One thread writing after fill — blocks readers briefly
> rwl.writeLock().lock();
> try { positionMap.put(symbol, newPosition); }
> finally { rwl.writeLock().unlock(); }
> ```
> **In trading:** good for position cache (hundreds of reads per second from risk checks, one write per fill).
>
> **`StampedLock`**
> - Java 8+. Three modes: write lock, read lock, **optimistic read** (no lock at all)
> - Optimistic read: read without acquiring a lock, then validate. If no write happened → zero overhead. If a write happened → fall back to read lock.
> - NOT reentrant — do not call `readLock()` while already holding it
> ```java
> StampedLock sl = new StampedLock();
>
> // Optimistic read — fastest possible read path
> long stamp = sl.tryOptimisticRead();
> double price = lastBidPrice;    // read without lock
> double qty   = lastBidQty;
>
> if (!sl.validate(stamp)) {      // check if a write happened during our read
>     // validation failed — fall back to real read lock
>     stamp = sl.readLock();
>     try {
>         price = lastBidPrice;
>         qty   = lastBidQty;
>     } finally {
>         sl.unlockRead(stamp);
>     }
> }
> ```
> **In trading:** ideal for market data snapshots (updated on every tick, read hundreds of times between updates).
>
> **Summary:**
> | | `ReentrantLock` | `ReadWriteLock` | `StampedLock` |
> |---|---|---|---|
> | Read concurrency | No | Yes | Yes + optimistic |
> | Reentrant | Yes | Yes | No |
> | Try-lock | Yes | Yes | Yes |
> | Performance | Medium | Good for reads | Best for reads |
> | Complexity | Low | Medium | High |

---

**Q17: How does `ConcurrentHashMap` work internally? What are its limitations in trading?**

> `ConcurrentHashMap` (CHM) in Java 8+ is a thread-safe hash map that avoids the coarse-grained locking of `Hashtable`.
>
> **Internal structure:**
> - A `Node[]` array (the "table") where each slot is a hash bucket
> - Each bucket is independently lockable — fine-grained locking
> - When a bucket has few entries: linked list of `Node` objects
> - When a bucket exceeds 8 entries: converted to a **red-black tree** (`TreeNode`) for O(log n) lookups
>
> **Read path (`get`)**: entirely lock-free — uses `volatile` reads on node references. Multiple threads can read simultaneously with zero contention.
>
> **Write path (`put`)**: uses `synchronized` on the **bucket head node** only — a single array slot. Other buckets are unaffected. CAS is used to set an empty bucket's first node.
>
> **Resize (`transfer`)**: incremental — threads helping with resize are detected via `ForwardingNode` sentinel objects. Uses CAS to claim resize stripes atomically.
>
> **`size()`**: uses `CounterCell` arrays to avoid contention — similar to `LongAdder` — so `size()` is approximate under high concurrency.
>
> **Limitations in trading:**
>
> 1. **Iteration is weakly consistent** — does not guarantee a point-in-time snapshot. Iterating while another thread inserts/removes may or may not see those changes. For a position map, this means a snapshot of positions may be inconsistent.
>
> 2. **Compound operations are not atomic** — `check-then-act` requires external locking:
> ```java
> // BROKEN — race condition despite using CHM:
> if (!orders.containsKey(id)) {   // Thread A: key not present
>     orders.put(id, order);       // Thread B also inserts between check and put!
> }
>
> // CORRECT — atomic putIfAbsent:
> Order existing = orders.putIfAbsent(id, order);
> // or: orders.computeIfAbsent(id, k -> createOrder(k));
> ```
>
> 3. **Still has lock overhead** — even fine-grained, `synchronized` on bucket head is not zero cost. On the nanosecond-sensitive hot path, use a single-threaded design with a plain `HashMap` instead.

---

**Q18: What is lock-free programming? Explain CAS (Compare-And-Swap) and the ABA problem.**

> Lock-free programming achieves thread safety without OS-level locking (no `synchronized`, no `ReentrantLock`). Instead it uses **CPU atomic instructions** — hardware guarantees that certain operations complete without interruption.
>
> **CAS (Compare-And-Swap):**
> ```
> CAS(memAddress, expectedValue, newValue):
>   if (memory[memAddress] == expectedValue) {
>       memory[memAddress] = newValue
>       return SUCCESS
>   } else {
>       return FAILURE  // someone else changed it
>   }
> // The above is ONE indivisible CPU instruction (CMPXCHG on x86)
> ```
>
> **CAS retry loop pattern (used by all Atomic* classes):**
> ```java
> AtomicLong sequenceNum = new AtomicLong(0);
>
> public long nextSequence() {
>     long current, next;
>     do {
>         current = sequenceNum.get();    // read current value
>         next    = current + 1;          // compute desired new value
>         // CAS: only write if nobody changed current in the meantime
>     } while (!sequenceNum.compareAndSet(current, next));
>     // If two threads compete: one wins the CAS, the other retries with the updated current
>     return next;
> }
> ```
>
> **ABA Problem:**
> CAS checks value equality — but a value can change from A→B→A while you weren't looking. Your CAS sees A, assumes nothing changed, and proceeds — but the object's logical state has changed:
> ```
> Thread A: reads orderId = 1001
> Thread B: order 1001 cancelled, new order allocated with same id 1001 (from pool!)
> Thread A: CAS(1001, 1001) SUCCEEDS — but it's a completely different order!
> ```
>
> **Fix: `AtomicStampedReference`** — pairs the value with a monotonically increasing version stamp:
> ```java
> AtomicStampedReference<Order> ref = new AtomicStampedReference<>(order, 0);
>
> int[] stampHolder = new int[1];
> Order current = ref.get(stampHolder);  // get value AND stamp together
> int stamp = stampHolder[0];
>
> // CAS only succeeds if BOTH value AND stamp match
> ref.compareAndSet(current, newOrder, stamp, stamp + 1);
> // Even if value cycles back to original, stamp never decrements → ABA detected
> ```
>
> **In trading:**
> - `AtomicLong` — order IDs, sequence numbers, fill counters
> - `AtomicReference` — publishing immutable market snapshots between threads
> - CAS retry loops — lock-free position updates

---

**Q19: How does multithreading work in a trading system? Describe the thread model and why the hot path is usually single-threaded.**

> **Yes, trading systems use multithreading extensively** — but with deliberate isolation. The goal is parallelism at the I/O boundary, not inside the critical path.
>
> **Typical thread model:**
> | Thread | Responsibility | Sync mechanism |
> |---|---|---|
> | **Market Data Thread** | Parse tick data from exchange connection | `volatile` / `AtomicReference` to publish |
> | **Strategy / Signal Thread** | React to ticks, compute signals | Single-threaded |
> | **Order Management Thread** | Submit orders, track state, risk checks | Single-threaded event loop |
> | **Network I/O Thread(s)** | FIX/TCP session management | Java NIO selector |
> | **Risk Reconciliation Thread** | Async position/P&L calculations | `ConcurrentHashMap` for position store |
> | **Persistence Thread** | Write-behind logging, trade records | Queue hand-off |
>
> ```
> [Exchange Feed] ──► [MD Thread] ──► [volatile/queue] ──► [Strategy Thread]
>                                                                    │
>                                                          [Order Thread] ──► [Exchange]
>                                                                    │
>                                                    (async) [Risk Thread]
>                                                    (async) [Log Thread]
> ```
>
> **Why the hot path is single-threaded:**
>
> Locking costs in nanoseconds:
> - `volatile` read/write: ~5ns
> - `AtomicLong` CAS (uncontended): ~15ns
> - `synchronized` (uncontended): ~25ns
> - `synchronized` (contended — thread blocks): ~1,000–10,000ns (context switch)
>
> On a single-threaded event loop, **zero synchronization** is needed — one thread owns all state. A tick arriving at the market data thread is published via a single `volatile` write. The strategy thread reads it with a `volatile` read. Everything else is single-threaded and cache-hot.
>
> The tradeoff: you cannot parallelize the critical path. But in low-latency trading, deterministic low-latency beats parallel-but-jittery throughput every time.

---

**Q20: What is `ExecutorService`? Compare `FixedThreadPool`, `CachedThreadPool`, and `ScheduledThreadPool`. How are they used in trading?**

> `ExecutorService` decouples task submission from thread management. Instead of creating threads manually, you submit tasks (`Runnable`/`Callable`) to a pool.
>
> **Thread pool types:**
>
> **`Executors.newFixedThreadPool(n)`**
> - Fixed number of threads, unbounded task queue (`LinkedBlockingQueue`)
> - If all threads busy: tasks queue up — queue can grow without bound → OOM risk
> - Use in trading: parallel risk calculation (known bounded parallelism)
> ```java
> ExecutorService riskPool = Executors.newFixedThreadPool(8);
> List<Future<RiskResult>> results = portfolios.stream()
>     .map(p -> riskPool.submit(() -> riskEngine.calculate(p)))
>     .collect(toList());
> ```
>
> **`Executors.newCachedThreadPool()`**
> - Creates new threads on demand, reuses idle ones (60s keepalive)
> - No bound on thread count — can create thousands of threads under load → danger
> - Use in trading: **avoid** — unbounded thread creation is a resource risk
>
> **`Executors.newScheduledThreadPool(n)`**
> - Runs tasks with fixed-rate or fixed-delay scheduling
> - Use in trading: market data staleness check, heartbeat sender, position reconciliation
> ```java
> ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
>
> // Check market data freshness every 100ms
> scheduler.scheduleAtFixedRate(
>     () -> checkMarketDataStaleness(),
>     0, 100, TimeUnit.MILLISECONDS
> );
>
> // Send FIX heartbeat every 30 seconds
> scheduler.scheduleWithFixedDelay(
>     () -> fixSession.sendHeartbeat(),
>     30, 30, TimeUnit.SECONDS
> );
> ```
>
> **Best practice — always use explicit `ThreadPoolExecutor`:**
> ```java
> // Give threads meaningful names for debugging ("why is this thread consuming CPU?")
> ThreadFactory namedFactory = new ThreadFactory() {
>     AtomicInteger count = new AtomicInteger();
>     public Thread newThread(Runnable r) {
>         Thread t = new Thread(r, "risk-worker-" + count.incrementAndGet());
>         t.setDaemon(true); // don't prevent JVM shutdown
>         return t;
>     }
> };
>
> ExecutorService executor = new ThreadPoolExecutor(
>     4,                            // core pool size
>     8,                            // max pool size
>     60, TimeUnit.SECONDS,         // keepAlive
>     new ArrayBlockingQueue<>(1000), // bounded queue — prevents OOM
>     namedFactory,
>     new ThreadPoolExecutor.CallerRunsPolicy() // back-pressure: caller runs if full
> );
> ```

---

**Q21: What is `CompletableFuture`? How does it compare to `Future`? Show a trading use case.**

> `Future<T>` (Java 5) represents a result that will be available in the future. The only way to get the result is `future.get()` — which **blocks** the calling thread until done. No callbacks, no chaining.
>
> `CompletableFuture<T>` (Java 8) is a non-blocking, composable alternative:
> - Supports callbacks (`thenApply`, `thenAccept`, `thenCompose`)
> - Can be completed explicitly (`complete()`, `completeExceptionally()`)
> - Supports combining multiple futures (`allOf`, `anyOf`)
> - Can run on a custom executor
>
> **`Future` — the old way:**
> ```java
> Future<RiskResult> future = executor.submit(() -> riskEngine.check(order));
> RiskResult result = future.get(); // BLOCKS calling thread — bad for trading
> ```
>
> **`CompletableFuture` — non-blocking pipeline:**
> ```java
> CompletableFuture
>     .supplyAsync(() -> marketDataService.getSnapshot("AAPL"), dataFetchPool)
>     .thenApplyAsync(snapshot -> strategy.generateSignal(snapshot), strategyPool)
>     .thenApplyAsync(signal -> riskEngine.check(signal), riskPool)
>     .thenAcceptAsync(riskResult -> {
>         if (riskResult.isPassed()) oms.submitOrder(riskResult.getOrder());
>     }, orderPool)
>     .exceptionally(ex -> {
>         log.error("Pipeline failed", ex);
>         return null;
>     });
> // No blocking — the calling thread is free to do other work
> ```
>
> **Combining multiple futures — parallel risk for multiple instruments:**
> ```java
> List<CompletableFuture<RiskResult>> checks = instruments.stream()
>     .map(sym -> CompletableFuture.supplyAsync(() -> riskEngine.check(sym), pool))
>     .collect(toList());
>
> CompletableFuture.allOf(checks.toArray(new CompletableFuture[0]))
>     .thenRun(() -> {
>         boolean allPassed = checks.stream()
>             .map(CompletableFuture::join)
>             .allMatch(RiskResult::isPassed);
>         if (allPassed) proceedWithStrategy();
>     });
> ```
>
> **In trading:** `CompletableFuture` is used for background/async operations (end-of-day reporting, async order acknowledgment handling, parallel pre-trade checks across multiple venues). Not used on the sub-millisecond hot path.

---

**Q22: What is the producer-consumer pattern? How do you implement it in Java without blocking?**

> The producer-consumer pattern decouples the thread that generates data (producer) from the thread that processes it (consumer) via a shared queue.
>
> **Standard `BlockingQueue` — has blocking:**
> ```java
> BlockingQueue<Order> queue = new ArrayBlockingQueue<>(1024);
>
> // Producer thread
> queue.put(order);      // blocks if queue is full — backpressure
>
> // Consumer thread
> Order o = queue.take(); // blocks if queue is empty — waits for data
> ```
> Blocking = thread suspension = context switch = latency spike. Bad for trading.
>
> **Non-blocking with `ConcurrentLinkedQueue`:**
> ```java
> ConcurrentLinkedQueue<Order> queue = new ConcurrentLinkedQueue<>();
>
> // Producer — never blocks (unbounded queue, watch for OOM)
> queue.offer(order);
>
> // Consumer — spin loop, never blocks
> while (running) {
>     Order o = queue.poll(); // returns null if empty — no blocking
>     if (o != null) {
>         processOrder(o);
>     } else {
>         Thread.onSpinWait(); // hint CPU we're spinning — saves power
>     }
> }
> ```
>
> **Non-blocking with `ArrayBlockingQueue` + `poll(timeout)` — compromise:**
> ```java
> BlockingQueue<Order> queue = new ArrayBlockingQueue<>(10_000);
>
> // Consumer: poll with a very short timeout instead of blocking forever
> Order o = queue.poll(100, TimeUnit.MICROSECONDS);
> if (o != null) processOrder(o);
> // If null: queue was empty within 100µs — loop again
> ```
>
> **Non-blocking with `java.util.concurrent.LinkedTransferQueue` (Java 7+):**
> - Lock-free, supports `tryTransfer` (hand-off directly to waiting consumer) — low latency for order hand-off between threads
>
> **Rule:** For anything latency-sensitive in trading (market data → strategy), use a non-blocking queue with a spin-waiting consumer on a dedicated thread. For back-office tasks, `BlockingQueue` is fine.

---

**Q23: What is a deadlock? How do you detect and prevent it in a trading system?**

> A **deadlock** occurs when two or more threads are permanently blocked, each waiting for a lock that another thread holds.
>
> **Classic deadlock in trading — cross-locking two instruments:**
> ```java
> // Thread A: buying AAPL and hedging MSFT
> synchronized (aaplLock) {              // Thread A holds AAPL
>     synchronized (msftLock) {          // Thread A waits for MSFT
>         execute(aaplBuy, msftSell);
>     }
> }
>
> // Thread B: buying MSFT and hedging AAPL (different order!)
> synchronized (msftLock) {              // Thread B holds MSFT
>     synchronized (aaplLock) {          // Thread B waits for AAPL — DEADLOCK!
>         execute(msftBuy, aaplSell);
>     }
> }
> ```
>
> **Deadlock requires four conditions (Coffman conditions):**
> 1. Mutual exclusion — resource held exclusively
> 2. Hold and wait — holding one, waiting for another
> 3. No preemption — lock can't be taken forcibly
> 4. Circular wait — cycle of threads each waiting on the next
>
> **Prevention strategies:**
>
> **1. Lock ordering — always acquire locks in the same global order:**
> ```java
> // Define a global canonical order (e.g., alphabetical by symbol)
> private void lockBoth(Object lockA, Object lockB) {
>     Object first  = lockA.hashCode() < lockB.hashCode() ? lockA : lockB;
>     Object second = lockA.hashCode() < lockB.hashCode() ? lockB : lockA;
>     synchronized (first) {
>         synchronized (second) {
>             // safe: both threads acquire in the same order
>         }
>     }
> }
> ```
>
> **2. Try-lock with timeout (never wait forever):**
> ```java
> ReentrantLock lock1 = new ReentrantLock();
> ReentrantLock lock2 = new ReentrantLock();
>
> boolean acquired = false;
> while (!acquired) {
>     if (lock1.tryLock(1, TimeUnit.MICROSECONDS)) {
>         try {
>             if (lock2.tryLock(1, TimeUnit.MICROSECONDS)) {
>                 try {
>                     // do work
>                     acquired = true;
>                 } finally { lock2.unlock(); }
>             }
>         } finally { lock1.unlock(); }
>     }
>     // If we didn't get both, retry
> }
> ```
>
> **3. Single-threaded event loop** — best for trading. No locks = no deadlock possible.
>
> **Detection:**
> ```java
> // Detect deadlocks programmatically with ThreadMXBean
> ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
> long[] deadlockedThreadIds = mxBean.findDeadlockedThreads();
> if (deadlockedThreadIds != null) {
>     // Alert operations, trigger kill switch, dump thread info
>     ThreadInfo[] info = mxBean.getThreadInfo(deadlockedThreadIds, true, true);
>     for (ThreadInfo ti : info) log.error("DEADLOCK: {}", ti);
>     killSwitch.halt("Deadlock detected");
> }
> ```

---

### D. Spring Boot in Electronic Trading

**Q24: How is Spring Boot used in electronic trading systems? What components are most relevant?**

> Spring Boot is widely used in the **back-office and middle-office** layers of trading systems — order management, trade reporting, risk dashboards, position services, and regulatory reporting. It is NOT typically used on the nanosecond-sensitive hot path (matching engine, HFT strategies) where Spring's DI and AOP overhead is not acceptable.
>
> **Where Spring Boot fits:**
> ```
> [HFT Engine / Matching]   ← Plain Java, no frameworks
>         ↓
> [OMS / EMS]               ← Spring Boot: DI, lifecycle management
>         ↓
> [Risk Service]            ← Spring Boot + Spring Data
>         ↓
> [Trade Reporting]         ← Spring Boot + REST + JPA
>         ↓
> [Back-Office / Booking]   ← Spring Boot + Kafka + DB
> ```
>
> **Most relevant Spring Boot components in trading:**
>
> **1. Dependency Injection** — wire together OMS, risk engine, position service, market data service cleanly:
> ```java
> @Service
> public class OrderManagementService {
>     private final RiskEngine riskEngine;
>     private final PositionService positionService;
>     private final OrderRepository orderRepository;
>
>     // Constructor injection — best practice (immutable, testable)
>     public OrderManagementService(RiskEngine riskEngine,
>                                   PositionService positionService,
>                                   OrderRepository orderRepository) {
>         this.riskEngine = riskEngine;
>         this.positionService = positionService;
>         this.orderRepository = orderRepository;
>     }
>
>     public OrderResult submitOrder(OrderRequest req) {
>         var riskResult = riskEngine.check(req);
>         if (!riskResult.isPassed()) return OrderResult.rejected(riskResult.getReason());
>         var order = orderRepository.save(Order.from(req));
>         positionService.reservePosition(order);
>         return OrderResult.accepted(order);
>     }
> }
> ```
>
> **2. Spring Data JPA** — persist trade records, order history, positions to database
>
> **3. Spring REST / WebMVC** — expose order entry, position query, risk query APIs
>
> **4. Spring Kafka** — consume/produce market data events, trade events
>
> **5. Spring Actuator** — health checks, metrics, readiness probes for trading service monitoring
>
> **6. Spring Scheduling** — scheduled tasks (position reconciliation, EOD processing)

---

**Q25: What is Spring Boot auto-configuration? How does it work internally?**

> Auto-configuration is Spring Boot's mechanism to automatically configure beans based on what is on the classpath and what properties are set — eliminating most boilerplate XML/Java config.
>
> **How it works step by step:**
>
> 1. `@SpringBootApplication` includes `@EnableAutoConfiguration`
>
> 2. At startup, Spring Boot reads all `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` files from every jar on the classpath (In Spring Boot 2.x: `META-INF/spring.factories`)
>
> 3. Each entry is an `@AutoConfiguration` class (e.g., `DataSourceAutoConfiguration`, `KafkaAutoConfiguration`)
>
> 4. Each class is annotated with `@ConditionalOn*` — only activated if conditions are met:
> ```java
> @AutoConfiguration
> @ConditionalOnClass(DataSource.class)        // only if DataSource is on classpath
> @ConditionalOnMissingBean(DataSource.class)  // only if user hasn't defined their own
> @ConditionalOnProperty(prefix = "spring.datasource", name = "url") // only if URL set
> public class DataSourceAutoConfiguration {
>     @Bean
>     public DataSource dataSource(DataSourceProperties props) {
>         return DataSourceBuilder.create()
>             .url(props.getUrl())
>             .username(props.getUsername())
>             .build();
>     }
> }
> ```
>
> 5. If all conditions pass → the beans are registered → you get a working `DataSource` with zero configuration from your side
>
> **Overriding auto-configuration:**
> ```java
> // Define your own bean → @ConditionalOnMissingBean prevents auto-config from firing
> @Configuration
> public class TradingDataSourceConfig {
>     @Bean
>     public DataSource dataSource() {
>         // Custom HikariCP config tuned for trading workload
>         HikariConfig config = new HikariConfig();
>         config.setJdbcUrl("jdbc:postgresql://localhost/trading");
>         config.setMaximumPoolSize(20);
>         config.setConnectionTimeout(3000); // 3s max wait for connection
>         return new HikariDataSource(config);
>     }
> }
> ```

---

**Q26: How do you implement async processing in Spring Boot? What is `@Async` and how does it work?**

> Spring's `@Async` annotation runs a method in a separate thread from a configured `TaskExecutor`. The calling thread returns immediately without waiting for the method to complete.
>
> **Setup:**
> ```java
> @SpringBootApplication
> @EnableAsync  // required — activates async processing
> public class TradingApp { ... }
>
> // Configure the thread pool (important — use explicit config, not default)
> @Configuration
> @EnableAsync
> public class AsyncConfig implements AsyncConfigurer {
>
>     @Override
>     public Executor getAsyncExecutor() {
>         ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
>         executor.setCorePoolSize(5);
>         executor.setMaxPoolSize(10);
>         executor.setQueueCapacity(500);
>         executor.setThreadNamePrefix("async-trading-");
>         executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
>         executor.initialize();
>         return executor;
>     }
>
>     @Override
>     public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
>         return (ex, method, params) ->
>             log.error("Async error in {}: {}", method.getName(), ex.getMessage(), ex);
>     }
> }
> ```
>
> **Usage in trading:**
> ```java
> @Service
> public class TradeReportingService {
>
>     // Called by OMS after every fill — must not slow down order processing
>     @Async
>     public CompletableFuture<Void> sendTradeConfirmation(Trade trade) {
>         // Runs in async-trading thread pool
>         emailService.sendConfirmation(trade);
>         bookingService.bookToBackOffice(trade);
>         regulatoryReporter.report(trade); // MiFID II / Dodd-Frank reporting
>         return CompletableFuture.completedFuture(null);
>     }
>
>     @Async
>     public CompletableFuture<RiskReport> generateRiskReport(String portfolioId) {
>         RiskReport report = riskCalculator.calculate(portfolioId); // expensive
>         return CompletableFuture.completedFuture(report);
>     }
> }
>
> // Caller — returns immediately, does not wait for confirmation to be sent
> tradeReportingService.sendTradeConfirmation(trade);
> ```
>
> **Important limitations:**
> - `@Async` only works when called from OUTSIDE the bean (Spring proxy limitation — self-invocation bypasses the proxy)
> - The method must be `public`
> - Return type must be `void` or `Future<T>`/`CompletableFuture<T>` — never a direct value

---

**Q27: Explain the Spring Bean lifecycle. What are the key lifecycle callbacks?**

> A Spring bean goes through a well-defined lifecycle from instantiation to destruction:
>
> ```
> 1. Instantiation      → new MyBean()
> 2. Dependency inject  → setters / constructor / field injection
> 3. BeanNameAware      → setBeanName(name)
> 4. BeanFactoryAware   → setBeanFactory(factory)
> 5. ApplicationContextAware → setApplicationContext(ctx)
> 6. BeanPostProcessor  → postProcessBeforeInitialization()
> 7. @PostConstruct     → your init method
> 8. InitializingBean   → afterPropertiesSet()
> 9. @Bean(initMethod)  → custom init
> 10. BeanPostProcessor → postProcessAfterInitialization()
>     ─── Bean is ready for use ───
> 11. @PreDestroy       → your cleanup method
> 12. DisposableBean    → destroy()
> 13. @Bean(destroyMethod) → custom destroy
> ```
>
> **In trading — `@PostConstruct` and `@PreDestroy` are critical:**
> ```java
> @Service
> public class MarketDataService {
>
>     private volatile boolean running = false;
>     private Thread feedThread;
>
>     @PostConstruct
>     public void startFeed() {
>         // Runs after all dependencies are injected and bean is fully initialized
>         running = true;
>         feedThread = new Thread(this::consumeFeed, "market-data-feed");
>         feedThread.setDaemon(false);
>         feedThread.start();
>         log.info("Market data feed started");
>     }
>
>     @PreDestroy
>     public void stopFeed() {
>         // Runs before bean is destroyed — on application shutdown
>         running = false;
>         feedThread.interrupt();
>         try { feedThread.join(5000); } catch (InterruptedException e) { /* ok */ }
>         log.info("Market data feed stopped cleanly");
>         // CRITICAL in trading: cancel all open orders before shutdown
>         orderManager.cancelAllOpenOrders();
>     }
>
>     private void consumeFeed() {
>         while (running) { /* process market data */ }
>     }
> }
> ```

---

**Q28: How do you configure and use transactions in Spring? What isolation level would you use for trade persistence?**

> Spring manages transactions declaratively with `@Transactional`. The `PlatformTransactionManager` handles the actual commit/rollback with the underlying resource (JDBC, JPA, JMS).
>
> **Basic usage:**
> ```java
> @Service
> @Transactional // default: applies to all public methods
> public class TradeService {
>
>     @Transactional(
>         isolation  = Isolation.READ_COMMITTED,   // see below
>         propagation = Propagation.REQUIRED,       // join existing tx or create new
>         timeout    = 5,                           // 5-second transaction timeout
>         rollbackFor = TradingException.class      // explicit rollback trigger
>     )
>     public void persistTrade(Trade trade) {
>         tradeRepository.save(trade);
>         positionRepository.updatePosition(trade.getSymbol(), trade.getQuantity());
>         auditLogRepository.log(trade);
>         // If any of the above fail → all three are rolled back atomically
>     }
>
>     @Transactional(readOnly = true) // optimization hint: no dirty check, no flush
>     public List<Trade> getTradesForDay(LocalDate date) {
>         return tradeRepository.findByTradeDate(date);
>     }
> }
> ```
>
> **Isolation levels — important for trading:**
>
> | Level | Dirty Read | Non-Repeatable Read | Phantom Read | Use in trading |
> |---|---|---|---|---|
> | `READ_UNCOMMITTED` | Yes | Yes | Yes | Never — could read uncommitted fills |
> | `READ_COMMITTED` | No | Yes | Yes | **Standard for trade data** |
> | `REPEATABLE_READ` | No | No | Yes | Position checks (same position within tx) |
> | `SERIALIZABLE` | No | No | No | Risk limits — prevent concurrent modification |
>
> **`READ_COMMITTED`** is the right choice for most trade persistence — prevents reading uncommitted data (dirty reads) while allowing good concurrency. Used by default in PostgreSQL and Oracle.
>
> **Propagation types most used in trading:**
> ```java
> @Transactional(propagation = Propagation.REQUIRED)
> // Join existing transaction, or create one if none exists — most common
>
> @Transactional(propagation = Propagation.REQUIRES_NEW)
> // ALWAYS creates a new transaction — suspends current one
> // Use for: audit logging (must persist even if outer tx rolls back)
>
> @Transactional(propagation = Propagation.NOT_SUPPORTED)
> // Run without a transaction — for read-heavy reporting queries
> ```
>
> **Common mistake:** `@Transactional` only works on public methods called from outside the bean (Spring proxy). Self-invocation (calling a `@Transactional` method from within the same class) bypasses the proxy and the transaction.

---

**Q29: How do you implement a kill switch in Spring Boot for a trading application?**

> A kill switch immediately halts all trading activity. It must be always reachable — even if the trading threads are stuck — and visible to all threads instantly.
>
> ```java
> @Component
> public class KillSwitch {
>
>     // volatile: write by any thread is immediately visible to all readers
>     // A single volatile read on the hot path has near-zero overhead (~5ns)
>     private static volatile boolean halted = false;
>
>     private final OrderManagementService oms;
>     private final ApplicationEventPublisher eventPublisher;
>
>     public KillSwitch(OrderManagementService oms, ApplicationEventPublisher publisher) {
>         this.oms = oms;
>         this.eventPublisher = publisher;
>     }
>
>     public void halt(String reason) {
>         halted = true;                    // immediately visible to ALL threads
>         log.error("KILL SWITCH: {}", reason);
>         oms.cancelAllOpenOrders();        // cancel everything immediately
>         eventPublisher.publishEvent(new KillSwitchEvent(reason)); // notify listeners
>     }
>
>     public static boolean isHalted() { return halted; } // static — callable from anywhere
>
>     public void resume() {
>         halted = false;
>         log.info("Kill switch cleared — resuming trading");
>     }
> }
>
> // Expose via REST for operator use
> @RestController
> @RequestMapping("/trading/control")
> public class TradingControlController {
>
>     private final KillSwitch killSwitch;
>
>     @PostMapping("/halt")
>     public ResponseEntity<String> halt(@RequestParam String reason) {
>         killSwitch.halt(reason);
>         return ResponseEntity.ok("Trading halted: " + reason);
>     }
>
>     @PostMapping("/resume")
>     public ResponseEntity<String> resume() {
>         killSwitch.resume();
>         return ResponseEntity.ok("Trading resumed");
>     }
> }
>
> // In OMS hot path — checked before EVERY order submission
> public OrderResult submitOrder(OrderRequest req) {
>     if (KillSwitch.isHalted()) {         // one volatile read — ~5ns overhead
>         return OrderResult.rejected("Kill switch active");
>     }
>     // ... normal order processing
> }
> ```
>
> **Triggers to wire up via `@Scheduled` or event listeners:**
> ```java
> @Scheduled(fixedDelay = 1000) // check every second
> public void checkPnLLimit() {
>     double dailyPnL = positionService.getDailyPnL();
>     if (dailyPnL < -500_000.0) {
>         killSwitch.halt("Daily loss limit breached: $" + dailyPnL);
>     }
> }
> ```

---

## 8. Recommended Learning Path

1. **Java Performance** — "Java Performance" by Scott Oaks, "Optimizing Java" by Ben Evans
2. **JVM Internals** — "Inside the Java Virtual Machine" by Bill Venners
3. **Java Concurrency** — "Java Concurrency in Practice" by Brian Goetz
4. **Spring Boot** — Official Spring documentation, "Spring in Action" by Craig Walls
5. **Order Book** — Implement a matching engine (see demo project)
6. **Market Microstructure** — "Trading and Exchanges" by Larry Harris
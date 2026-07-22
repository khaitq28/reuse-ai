# Java Trading Developer — Study Guide

> Interview preparation for **Front Office / Electronic Trading** Java positions.
> Each topic below includes the key concepts and the interview questions you must be able to answer.
> Answers are intentionally omitted — work them out, then verify against `ELECTRONIC_TRADING_JAVA.md` and `INTERVIEW_PREP.md`.

---

## Table of Contents

- [1. Java Concurrency](#1-java-concurrency)
  - [JMM — Java Memory Model](#jmm--java-memory-model)
  - [volatile](#volatile)
  - [synchronized](#synchronized)
  - [CAS — Compare-And-Swap](#cas--compare-and-swap)
  - [Atomic Classes](#atomic-classes)
  - [ConcurrentHashMap](#concurrenthashmap)
  - [ReentrantLock](#reentrantlock)
- [2. Async & Threading](#2-async--threading)
  - [ThreadPoolExecutor](#threadpoolexecutor)
  - [CompletableFuture](#completablefuture)
  - [BlockingQueue](#blockingqueue)
  - [Virtual Threads — Java 21](#virtual-threads--java-21)
- [3. JVM Internals](#3-jvm-internals)
  - [Object Allocation](#object-allocation)
  - [Garbage Collection](#garbage-collection)
  - [G1 GC](#g1-gc)
  - [JFR — Java Flight Recorder](#jfr--java-flight-recorder)
  - [async-profiler](#async-profiler)
- [4. Low Latency](#4-low-latency)
  - [Throughput vs Latency](#throughput-vs-latency)
  - [CPU Cache](#cpu-cache)
  - [False Sharing](#false-sharing)
  - [Lock Contention](#lock-contention)
  - [Mechanical Sympathy](#mechanical-sympathy)
  - [LMAX Disruptor](#lmax-disruptor)
- [5. Netty & NIO](#5-netty--nio)
  - [Java NIO](#java-nio)
  - [Netty Architecture](#netty-architecture)
  - [Netty in Trading](#netty-in-trading)
- [6. System Design](#6-system-design)
  - [Order Book](#order-book)
  - [Market Data Fan-Out](#market-data-fan-out)
  - [Rate Limiter](#rate-limiter)
  - [Position Service](#position-service)
  - [FIX Gateway](#fix-gateway)
- [7. Coding Problems — Code Review](#7-coding-problems--code-review)

---

## 1. Java Concurrency

> The most tested area in any trading Java interview. Every topic here has been asked in production-level interviews.

---

### JMM — Java Memory Model

The JMM defines the rules for **when and how writes made by one thread become visible to other threads**. Without understanding it, all concurrency reasoning is guesswork.

**Interview Questions**

1. What problem does the Java Memory Model solve? Why can't you just trust the CPU to do the right thing?
2. What is the **happens-before** relationship? Give two concrete examples.
3. Thread A writes to a field. Thread B reads it. Is Thread B guaranteed to see the latest value? Under what conditions?
4. What does the JMM guarantee when a thread exits a `synchronized` block?
5. What is instruction reordering? Can the JVM reorder writes that appear sequential in your source code?
6. What is the difference between **visibility** and **atomicity**? Are they related?

---

### volatile

`volatile` is the simplest synchronization mechanism in Java — and also the most misused.

**Interview Questions**

1. What does `volatile` guarantee? What does it **not** guarantee?
2. Is this thread-safe? Why or why not?
   ```java
   volatile int counter;
   counter++;
   ```
3. When is `volatile` sufficient and when do you need `AtomicInteger` instead?
4. How does `volatile` prevent instruction reordering across a write?
5. In `RiskEngine`, why is `volatile long windowStart` not enough for `ordersInWindow`? What is used instead?
6. Can a `volatile` reference to an object guarantee thread-safety of the object's fields?

---

### synchronized

`synchronized` is the original Java mutual exclusion mechanism. Understand its cost — it matters in trading.

**Interview Questions**

1. What is the difference between a synchronized **method** and a synchronized **block**? Which is preferable in a trading context and why?
2. What is an **intrinsic lock** (monitor)?
3. What is **lock re-entrance**? Why does Java support it? Give an example where it matters.
4. What are the risks of using `synchronized` in a latency-sensitive system?
5. What is the difference between `wait()`, `notify()`, and `notifyAll()`? Where do you call them?
6. Can two threads hold locks on different objects simultaneously? Can they deadlock?

---

### CAS — Compare-And-Swap

CAS is the hardware primitive that enables **lock-free programming**. All `Atomic*` classes are built on it.

**Interview Questions**

1. Explain CAS in one sentence. What CPU instruction underlies it on x86?
2. Walk through what `AtomicInteger.compareAndSet(expected, update)` does step by step.
3. What is the **ABA problem**? Give a concrete example. How do you solve it?
4. Why is CAS preferred over locks in a low-latency trading engine?
5. What is the cost of a **failed CAS**? Does the thread block? What happens next?
6. Is CAS always cheaper than a lock? In what scenario could a lock outperform CAS?

---

### Atomic Classes

`AtomicInteger`, `AtomicLong`, `AtomicReference`, `LongAdder` — know when to use each.

**Interview Questions**

1. How does `AtomicLong.incrementAndGet()` work under the hood?
2. What is the difference between `AtomicLong` and `LongAdder`? When should you use each?
3. What is `AtomicReference`? Give an example where you would use it in an order book.
4. Can you use an `AtomicLong` to implement a thread-safe rate limiter? How?
5. What is `AtomicStampedReference` and what problem does it solve?
6. Is `AtomicInteger` faster than `synchronized`? Always? Under high contention?

---

### ConcurrentHashMap

The go-to concurrent map in any trading OMS or position tracker.

**Interview Questions**

1. How does `ConcurrentHashMap` achieve thread safety without locking the entire map?
2. What is the difference between `putIfAbsent()` and `computeIfAbsent()`? Which is safer under concurrency?
3. Is `size()` on a `ConcurrentHashMap` accurate? When might it return a stale value?
4. Why should you prefer `ConcurrentHashMap` over `Collections.synchronizedMap(new HashMap<>())`?
5. Is this sequence atomic?
   ```java
   if (!map.containsKey(key)) {
       map.put(key, value);
   }
   ```
   If not, how do you fix it?
6. What changed internally in `ConcurrentHashMap` between Java 7 and Java 8?

---

### ReentrantLock

More powerful than `synchronized` — essential for trading scenarios requiring fairness or timed locking.

**Interview Questions**

1. What advantages does `ReentrantLock` have over `synchronized`?
2. What is `tryLock(timeout)` and why is it critical in a trading system to avoid blocking indefinitely?
3. What is a **fair lock**? What is the performance trade-off vs an unfair lock?
4. When would you use `ReentrantReadWriteLock` in a trading context? Give a concrete example.
5. What happens if you forget to call `unlock()` in a `finally` block?
6. Can `ReentrantLock` cause deadlock? Under what conditions?

---

## 2. Async & Threading

> Trading systems process thousands of events per second across many threads. Thread management is critical.

---

### ThreadPoolExecutor

Every production trading service uses a thread pool. You must understand its internals, not just `Executors.newFixedThreadPool()`.

**Interview Questions**

1. What are the **5 key constructor parameters** of `ThreadPoolExecutor`? What does each control?
2. What happens when the queue is full **and** all threads are at max? Walk through the execution path.
3. Why is `Executors.newFixedThreadPool(n)` considered dangerous in production? What can go wrong?
4. What is the difference between `execute()` and `submit()`?
5. How would you size a thread pool for a **CPU-bound** market data processor vs an **IO-bound** settlement service?
6. What is a `RejectedExecutionHandler`? Which implementation would you use in a trading system and why?

---

### CompletableFuture

The modern API for async composition in Java. Know it deeply — it appears constantly in trading backends.

**Interview Questions**

1. What is the difference between `thenApply()` and `thenCompose()`?
2. What thread executes the callback in `thenApply()` if no executor is explicitly provided?
3. How do you combine the results of two independent `CompletableFuture` instances?
4. How do you handle exceptions in a `CompletableFuture` chain? What is the difference between `exceptionally()` and `handle()`?
5. How do you implement a **timeout** on a `CompletableFuture`? (Java 9+)
6. What is the difference between `allOf()` and `anyOf()`? Which would you use to implement a "first quote wins" pattern in an RFQ system?

---

### BlockingQueue

The classic inter-thread communication mechanism. Used in order pipelines, market data fans, and settlement queues.

**Interview Questions**

1. What is the difference between `put()`, `offer()`, and `add()` on a `BlockingQueue`? Which blocks? Which throws? Which returns false?
2. What is the difference between `ArrayBlockingQueue` and `LinkedBlockingQueue` in terms of memory layout and throughput?
3. How does `BlockingQueue` naturally implement the **producer-consumer** pattern? Why does it prevent busy-waiting?
4. What is `PriorityBlockingQueue`? When would you use it in an Order Management System?
5. What is `SynchronousQueue`? What makes it different from other queues?
6. What happens in your trading pipeline if the consumer is slower than the producer and the queue is bounded?

---

### Virtual Threads — Java 21

> Java 21 virtual threads are appearing in interviews for any Java 17+ trading stack. Know when they help and — critically — when they do **not**.

**Interview Questions**

1. What is a virtual thread? How does it differ from a platform (OS) thread?
2. Virtual threads are cheap — does that mean you should use them for CPU-bound order matching? Why or why not?
3. What is **thread pinning** in the context of virtual threads? When does it happen and why is it dangerous?
4. When would you choose `Executors.newVirtualThreadPerTaskExecutor()` over a fixed thread pool in a trading backend?
5. `CompletableFuture.supplyAsync()` without an explicit executor uses the **common ForkJoinPool**. What happens when all ForkJoinPool threads are blocked on IO? How do virtual threads change this?
6. Are virtual threads a replacement for reactive programming (e.g., Reactor/WebFlux) in a trading service?

---

## 3. JVM Internals

> In trading, a GC pause of 50ms can mean missed opportunities worth millions. JVM knowledge is non-negotiable.

---

### Object Allocation

Understanding where objects come from — and what to do to avoid allocating them on the hot path.

**Interview Questions**

1. Where is a new object allocated in the JVM? Walk through the path from `new MyObject()` to memory.
2. What is a **TLAB** (Thread-Local Allocation Buffer)? How does it make allocation nearly free?
3. What is **escape analysis**? How can the JVM avoid heap allocation entirely for a short-lived object?
4. What is the cost of object allocation in terms of GC pressure in a high-frequency trading system?
5. What is **object pooling** and when is it worth the added complexity?
6. How would you redesign an `Order` processing pipeline to produce zero garbage on the hot path?

---

### Garbage Collection

The biggest source of unpredictable latency in JVM-based trading systems.

**Interview Questions**

1. What is a **Stop-The-World** pause? Why is it catastrophic in an electronic trading context?
2. What is the difference between a **minor GC** and a **major GC (full GC)**?
3. Name the main GC algorithms available in modern JVMs. What are their pause characteristics?

   | GC | Typical Pause | Suitable for |
   |----|--------------|--------------|
   | Serial | ? | ? |
   | Parallel | ? | ? |
   | G1 | ? | ? |
   | ZGC | ? | ? |

4. What JVM flags would you set to **fix heap size** at startup and why?
5. What is `AlwaysPreTouch` and why do trading systems use it?
6. What is **off-heap memory** (`ByteBuffer.allocateDirect()`)? Why would a market data system use it?

---

### G1 GC

The default GC for most modern Java applications, including OMS and back-office systems.

**Interview Questions**

1. How does G1 differ from the older CMS and Parallel GC in how it manages heap space?
2. What are G1 **regions**? How many are there and what sizes do they come in?
3. What is the `MaxGCPauseMillis` target in G1? Is it a hard limit or a hint?
4. What is a **mixed collection** in G1? When does it trigger?
5. When would you choose **ZGC over G1** for a trading application?
6. What G1 JVM flags would you tune first for a latency-sensitive order management service?

---

### JFR — Java Flight Recorder

The built-in, low-overhead profiling and diagnostics framework. Essential for diagnosing production latency issues.

**Interview Questions**

1. What is JFR and what types of events does it capture?
2. How do you enable JFR on a running JVM **without restarting it**?
3. What is the performance overhead of JFR in continuous recording mode?
4. What would you look for in a JFR recording to diagnose a **GC-related latency spike**?
5. What is the difference between JFR and JMC (Java Mission Control)?
6. What JFR events are most useful for profiling a **market data processing pipeline**?

---

### async-profiler

The most accurate profiler for Java production systems. Avoids the safepoint bias that makes standard profilers misleading.

**Interview Questions**

1. What is async-profiler and how does it differ from JVM-based sampling profilers (JFR, VisualVM)?
2. What is **safepoint bias**? How does it distort profiling results from standard profilers?
3. What is a **flame graph**? How do you read one? What does width represent?
4. What is the difference between a **CPU flame graph** and an **allocation flame graph**?
5. How would you use async-profiler to find the cause of high CPU usage in a live pricing engine?
6. Can you attach async-profiler to a production process without restarting? What are the risks?

---

## 4. Low Latency

> This section separates senior trading developers from mid-level ones. These concepts directly impact the performance of order books, pricing engines and FIX gateways.

---

### Throughput vs Latency

Understanding the fundamental tension that drives every architecture decision in electronic trading.

**Interview Questions**

1. Define **throughput** and **latency**. What unit do you measure each in?
2. Why do throughput and latency often **trade off** against each other? Give a concrete trading example.
3. What is **tail latency** (p99, p99.9, p99.99)? Why does it matter more than average latency in a trading context?
4. What is **Little's Law**? How does it relate concurrency, throughput, and latency?
5. A market data feed processes 1 million messages/sec with average latency of 10µs. Is this good? What else do you need to know?
6. How do you measure latency in a trading system? What tools and techniques would you use?

---

### CPU Cache

The gap between CPU speed and memory speed is the dominant performance constraint in low-latency systems.

**Interview Questions**

1. What are the **L1, L2, L3 caches**? What are typical sizes and access latencies for each?
2. What is a **cache line**? How many bytes is it on modern x86 hardware?
3. What is **spatial locality**? How does the layout of your Java objects affect cache efficiency?
4. Why is sequential array iteration significantly faster than random access for large arrays?
5. What is a **cache miss**? What are the three types of cache misses (cold, capacity, conflict)?
6. How would you design an order book data structure to maximize CPU cache utilization?

---

### False Sharing

One of the most insidious performance bugs in multithreaded systems — invisible in code, devastating in production.

**Interview Questions**

1. What is **false sharing**? Explain it at the CPU cache level.
2. Give a concrete example of false sharing in a trading system — two threads, two variables, one cache line.
3. What is the observable symptom of false sharing? How do you distinguish it from ordinary lock contention?
4. How do you **detect** false sharing? (tools, JFR events, profilers)
5. How do you **fix** false sharing in Java? What is `@Contended`? What does manual padding look like?
6. What is the Disruptor's solution to false sharing between producer and consumer cursors?

---

### Lock Contention

The enemy of scalability. In a high-frequency order book, contention on a single lock can serialize all threads.

**Interview Questions**

1. What is **lock contention**? How does it manifest as latency in a trading system?
2. What is the difference between **lock-free** and **wait-free** algorithms?
3. How do you **measure** lock contention in a running JVM?
4. What is **lock striping**? How does `ConcurrentHashMap` use it?
5. How would you redesign a shared position tracker to eliminate a central lock bottleneck?
6. What is a **spin lock**? When is it faster than a mutex in a low-latency context?

---

### Mechanical Sympathy

Writing code that works *with* the hardware, not against it. The mindset that distinguishes trading systems engineers.

**Interview Questions**

1. What does **"mechanical sympathy"** mean in software development? Who coined the term?
2. How does **CPU branch prediction** work? How can you write code that benefits from it?
3. What is **NUMA** (Non-Uniform Memory Access)? Why does it matter for a multi-socket trading server?
4. What is **memory-mapped I/O**? Why do some trading systems use it for market data?
5. Why does an order book built on an `ArrayList<Order>` at a given price level have better cache behavior than `LinkedList<Order>`?
6. What is the **LMAX Disruptor**? What hardware-level optimizations does it exploit?



---

### LMAX Disruptor

> If you are interviewing for an electronic trading position, you **will** be asked about the Disruptor. It is the standard for inter-thread messaging in low-latency Java systems (used at LMAX, banks, HFT firms).

**Interview Questions**

1. What problem does the Disruptor solve that `BlockingQueue` cannot? What is the core performance difference?
2. What is a **ring buffer**? Why must its size be a **power of 2**? What trick does this enable?
3. What is a **sequence**? What is a **sequence barrier**? How does a consumer know when a slot is ready to read?
4. What are **wait strategies**? Compare `BusySpinWaitStrategy`, `YieldingWaitStrategy`, and `BlockingWaitStrategy` — when do you use each in a trading system?
5. How does the Disruptor eliminate false sharing between the producer sequence and consumer sequence?
6. How do you set up a **pipeline** (A → B → C) vs **parallel handlers** (A → B and A → C simultaneously) in a Disruptor?

---

## 5. Netty & NIO

> Every FIX engine, market data gateway, and low-latency trading server in Paris is built on Netty or raw NIO. Non-negotiable for CIB and HFT interviews.

---

### Java NIO

Java NIO (New I/O) is the foundation of all non-blocking networking in Java. Understand why it exists before learning Netty.

**Interview Questions**

1. What is the difference between **blocking I/O** (java.io) and **non-blocking I/O** (java.nio)? Why does it matter for a trading gateway handling 10,000 connections?
2. What are the three core abstractions of Java NIO? Explain **Channel**, **Buffer**, and **Selector** in one sentence each.
3. How does a `Selector` work? Walk through the event loop: register → select → iterate keys → handle.
4. What is the difference between `ByteBuffer.allocate()` and `ByteBuffer.allocateDirect()`? Why does a market data parser prefer direct buffers?
5. What is **zero-copy**? How does `FileChannel.transferTo()` exploit it, and where would you use it in a trading system?
6. What is the difference between `flip()`, `compact()`, and `clear()` on a `ByteBuffer`? What happens if you forget `flip()` before reading?

---

### Netty Architecture

Netty wraps Java NIO with a production-ready, pipeline-based architecture. Know it at the component level.

**Interview Questions**

1. What is an **EventLoop** in Netty? How many threads does one `NioEventLoopGroup` use by default?
2. What is a **Channel Pipeline**? How do `ChannelInboundHandler` and `ChannelOutboundHandler` differ?
3. What is a **ByteBuf**? How does it differ from `ByteBuffer`? What is reference counting and why does Netty need it?
4. What is the difference between `ChannelHandlerContext.write()` and `ChannelHandlerContext.writeAndFlush()`? What is the cost of calling `flush()` too often?
5. What is `@Sharable` on a `ChannelHandler`? What is the risk if a non-sharable handler is shared across channels?
6. What is the **boss / worker group** pattern in a Netty server? What does each group do?

---

### Netty in Trading

How Netty is actually used in front office systems — the level interviewers expect.

**Interview Questions**

1. A FIX engine receives messages over TCP. How would you structure the Netty pipeline to: (a) frame FIX messages by delimiter, (b) decode bytes to a FIX object, (c) dispatch to an OMS handler?
2. What is a **codec** in Netty? What is the difference between `ByteToMessageDecoder` and `MessageToByteEncoder`?
3. How do you handle **slow consumers** in a Netty server? What happens if a client cannot read fast enough and the write buffer fills up?
4. What is `ChannelOption.TCP_NODELAY`? Why must it be enabled on every trading connection?
5. How would you implement **heartbeat / session timeout** detection in a Netty FIX gateway?
6. What is the performance difference between `NioEventLoopGroup` and `EpollEventLoopGroup`? When would you use Epoll in a Paris trading server?

---

## 6. System Design

> Every senior Java interview in front office includes at least one system design question. These are open-ended — there is no single correct answer. The goal is to show structured thinking, awareness of trade-offs, and trading-specific constraints (latency, ordering, exactly-once).

**How to answer:** always start with **requirements** (throughput, latency target, consistency), then **data model**, then **concurrency strategy**, then **failure handling**.

---

### Real Interview Questions — as asked in Paris CIB / HFT interviews

> These are verbatim-style questions collected from front office interviews. Each maps to a design problem below.

**Order Book**
- *"Design a thread-safe in-memory order book that handles 1 million order updates per second."*
- *"How would you implement an order book in Java? Walk me through your data structures."*
- *"Your matching engine has p99 latency of 500µs. Production target is 100µs. What do you change?"*

**Market Data**
- *"Design a market data distribution system that fans out to 200 consumers with no slow-consumer impact."*
- *"We receive 2M price ticks per second. How do you distribute them to downstream services without dropping data?"*
- *"How would you build a last-value cache for market data that is safe for concurrent readers?"*

**Rate Limiter**
- *"Implement a rate limiter: 1,000 orders per second per client. It must add less than 1 microsecond of overhead."*
- *"How do you prevent a single client from flooding your trading gateway?"*
- *"Your rate limiter is deployed across 10 instances. How do you enforce a global limit?"*

**Position / Risk**
- *"Design a real-time position tracker. 50 desks, 200 fills per second each. Risk reads every 10ms."*
- *"How do you ensure a fill is never counted twice if your service crashes and restarts?"*
- *"A fill arrives out of order due to network reordering. How does your position service handle it?"*

**FIX Gateway**
- *"Design a FIX gateway in Java. 50 buy-side clients, 100,000 messages per second inbound."*
- *"How do you parse FIX messages at high throughput without allocating objects on the hot path?"*
- *"A client reconnects after a disconnect with a sequence number gap. What does your gateway do?"*

**General / Surprise questions**
- *"You have a shared `HashMap` accessed by 16 threads. Production is throwing `ConcurrentModificationException`. What do you do — walk me through your investigation."*
- *"We have a service that works perfectly in staging but shows 10x higher latency in production. How do you diagnose it?"*
- *"Design a thread-safe object pool for `Order` objects to eliminate GC pressure on the hot path."*

---

### Order Book

**Interview question:** *"Design a thread-safe in-memory order book for a single instrument. It must support add, cancel, and match at 1M messages/sec with p99 latency under 100µs."*

**Questions to drive your answer:**

1. What data structure do you use for the bid side and ask side? (`TreeMap<Price, Queue<Order>>` — why? what are the trade-offs vs array-based approaches?)
2. How do you handle concurrency? Single-threaded with Disruptor? `ReentrantReadWriteLock`? Why does a single writer thread outperform locking at this throughput?
3. How do you implement **price-time priority** (FIFO at each price level)?
4. What does a match produce? How do you notify the OMS of a fill without blocking the matching thread?
5. How do you handle **cancel** efficiently? (hint: O(1) cancellation requires a `HashMap<orderId, Order>` alongside the price-level structure)
6. How would you **benchmark** this — what metrics, what tools?

---

### Market Data Fan-Out

**Interview question:** *"Design a market data distribution system. A feed delivers 2M price updates/sec. Fan out to 200 subscriber services. No subscriber must be able to slow down the feed."*

**Questions to drive your answer:**

1. One thread per subscriber vs. Disruptor with multiple consumers — which scales better and why?
2. How do you ensure **no subscriber blocks the feed** if it is slow? (hint: bounded queue, drop policy, or back-pressure)
3. How do you handle subscribers at different speeds? Should a slow analytics consumer affect a fast risk consumer?
4. What is a **multicast** approach? When would you use UDP multicast instead of TCP for this?
5. How do you measure fan-out latency per subscriber? What is the right tool?
6. What happens if a subscriber dies — how does the system detect and recover?

---

### Rate Limiter

**Interview question:** *"Implement a rate limiter for a trading API gateway: max 1,000 orders/sec per client. The check must add less than 1µs of latency. It runs on 10 gateway instances."*

**Questions to drive your answer:**

1. **Token bucket** vs **sliding window** vs **fixed window** — explain each. Which do you choose for a trading API and why?
2. How do you implement a token bucket with `AtomicLong` and CAS — no locks?
3. How do you handle **burst** — a client sending 500 orders in 1ms then nothing for 999ms? Should it be allowed?
4. If the rate limiter must be distributed (shared across 10 gateway instances), what changes? (hint: Redis INCR, Lua script for atomicity)
5. How do you test that the rate limiter is accurate under 32 concurrent threads?
6. What is the difference between **reject** and **queue** strategies when the limit is exceeded? Which is correct for a trading gateway?

---

### Position Service

**Interview question:** *"Design a real-time position tracking service. 50 trading desks send 200 fills/sec each. A downstream risk engine reads current positions every 10ms. The service must survive restarts without losing state."*

**Questions to drive your answer:**

1. What is the data model? `Map<Desk, Map<Instrument, Position>>` — what are the concurrency requirements per cell?
2. `ConcurrentHashMap` with `compute()` vs `ReentrantReadWriteLock` vs single-writer thread — compare for this use case.
3. How do you ensure a fill is **never double-counted** if the service restarts mid-stream? (hint: sequence numbers, idempotent apply)
4. How do you snapshot positions for end-of-day reconciliation without blocking live updates?
5. Downstream risk polls every 10ms. Is polling the right pattern? What is the alternative and its trade-off?
6. How do you handle a fill arriving **out of order** (network reorder, replay scenario)?

---

### FIX Gateway

**Interview question:** *"Design a FIX gateway in Java. It accepts NewOrderSingle messages from 50 buy-side clients at 100,000 msg/sec total, and routes them to an internal OMS. It must handle client disconnects and reconnects with sequence number recovery."*

**Questions to drive your answer:**

1. How many Netty threads do you need? What does the boss group do vs the worker group?
2. How do you parse a FIX message efficiently — avoid `String.split()` at 100,000 msg/sec?
3. How do you guarantee **message ordering** per client session while processing sessions in parallel?
4. What happens if the OMS is down — do you reject, queue, or replay? What does FIX protocol say about this?
5. How do you handle a client that reconnects with a sequence number gap? (FIX session recovery — ResendRequest)
6. What is your **monitoring** strategy — what metrics do you expose, what alerts do you set?

---

## 7. Coding Problems — Code Review

> Mỗi bài là một đoạn code thực tế từ trading system. Tìm bug, giải thích tại sao, và đề xuất fix.

---

### Bài 1 — Thread-safe Price Cache

**Context**

- Một service nhận giá chứng khoán từ nhiều nguồn khác nhau
- Mỗi giây có khoảng 20.000 price update
- Nhiều thread cùng gọi `update()` và `getPrice()`
- Khách hàng thỉnh thoảng nhận được giá cũ hoặc `NullPointerException`

**Review đoạn code sau:**

```java
public class PriceCache {

    private final Map<String, Double> prices = new HashMap<>();

    public void update(String symbol, double price) {
        prices.put(symbol, price);
    }

    public Double getPrice(String symbol) {
        return prices.get(symbol);
    }
}
```

---

### Bài 2 — Latest Price Only

**Context**

Hai feed gửi cùng một symbol nhưng đến theo thứ tự không đảm bảo do network delay:

```
Feed A  →  AAPL  seq=100  price=189
Feed B  →  AAPL  seq=98   price=187   ← message cũ có thể đến SAU
```

**Yêu cầu:**

- Luôn giữ giá mới nhất (seq cao nhất)
- Update cũ phải bị bỏ qua
- Nhiều thread update đồng thời

**Code hiện tại:**

```java
public class PriceBook {

    private final ConcurrentHashMap<String, Price> prices =
            new ConcurrentHashMap<>();

    public void update(Price p) {
        Price current = prices.get(p.getSymbol());

        if (current == null || p.getSequence() > current.getSequence()) {
            prices.put(p.getSymbol(), p);
        }
    }
}
```

---

### Bài 3 — Producer Consumer

**Context**

- Trading Gateway nhận message từ FIX
- Producer đọc socket
- Consumer ghi database
- System chạy khoảng **8 Producer** và **6 Consumer**
- Thỉnh thoảng mất message

**Code hiện tại:**

```java
public class MessageProcessor {

    private final Queue<Message> queue = new LinkedList<>();

    public void receive(Message msg) {
        queue.add(msg);
    }

    public Message next() {
        return queue.poll();
    }
}
```

---

### Bài 4 — ExecutorService

**Context**

- Service cần gọi đồng thời 5 market providers
- Mỗi provider mất khoảng 100–500ms
- Sau vài ngày production: server bắt đầu hết thread, khách hàng timeout

**Code hiện tại:**

```java
ExecutorService executor = Executors.newFixedThreadPool(5);

List<Future<Price>> futures = new ArrayList<>();

for (Provider provider : providers) {
    futures.add(executor.submit(() -> provider.getPrice(symbol)));
}

List<Price> prices = new ArrayList<>();

for (Future<Price> future : futures) {
    prices.add(future.get());
}
```

---

### Bài 5 — CompletableFuture

**Context**

- Có 3 service độc lập: `Market`, `Risk`, `Inventory`
- Developer muốn gọi song song để giảm latency
- System chạy đúng, nhưng latency rất cao
- Dưới load cao (100+ concurrent requests), latency đột ngột tăng gấp 10x

**Code hiện tại:**

```java
CompletableFuture<Market> market =
        CompletableFuture.supplyAsync(() -> marketService.load());

CompletableFuture<Risk> risk =
        CompletableFuture.supplyAsync(() -> riskService.load());

CompletableFuture<Inventory> inventory =
        CompletableFuture.supplyAsync(() -> inventoryService.load());

Portfolio portfolio = new Portfolio(
        market.get(),
        risk.get(),
        inventory.get());
```

> **Hint:** có 2 bug độc lập. Bug 1 liên quan đến cách combine kết quả. Bug 2 liên quan đến thread pool mà `supplyAsync()` dùng khi không có explicit executor.

---

### Bài 6 — Counter

**Context**

- Trading Engine cần thống kê số order mỗi giây
- Service chạy khoảng **32 threads**, **300.000 orders/sec**

**Code hiện tại:**

```java
public class OrderCounter {

    private long count = 0;

    public void increment() {
        count++;
    }

    public long value() {
        return count;
    }
}
```

---

### Bài 7 — Memory Leak

**Context**

- Application chạy khoảng 2 ngày thì Full GC liên tục
- Heap dump cho thấy hàng triệu object

**Code hiện tại:**

```java
public class InstrumentCache {

    private final Map<String, Instrument> cache = new ConcurrentHashMap<>();

    public Instrument get(String isin) {
        Instrument instrument = cache.get(isin);

        if (instrument == null) {
            instrument = repository.load(isin);
            cache.put(isin, instrument);
        }

        return instrument;
    }
}
```

---

### Bài 8 — Synchronization

**Context**

- Developer muốn thread-safe cho `PositionService`
- System chạy đúng
- Nhưng CPU chỉ khoảng 20% và latency tăng gấp đôi

**Code hiện tại:**

```java
public class PositionService {

    private final Map<String, Position> positions = new HashMap<>();

    public synchronized void update(Position position) {
        positions.put(position.getSymbol(), position);
    }

    public synchronized Position get(String symbol) {
        return positions.get(symbol);
    }
}
```

---

### Bài 9 — Allocation

**Context**

- Method dưới đây được gọi khoảng **10 triệu lần / phút**
- Khách hàng báo GC tăng và latency tăng

**Code hiện tại:**

```java
public String buildKey(Order order) {
    return order.getTrader()
            + "-"
            + order.getDesk()
            + "-"
            + order.getInstrument()
            + "-"
            + order.getId();
}
```

> **Câu hỏi thêm:** Fix với `StringBuilder` có đủ không? Nếu vẫn không đủ (zero-GC requirement), production trading system sẽ làm gì khác — gợi ý: nghĩ về key như một `byte[]` pre-allocated, hoặc dùng `long` composite key.

---

### Bài 10 — Hot Loop

**Context**

- Matching Engine liên tục duyệt order book
- Method này được gọi **hàng triệu lần mỗi giây**

**Code hiện tại:**

```java
public Order findOrder(List<Order> orders, long id) {

    for (Order order : orders) {
        if (order.getId() == id) {
            return order;
        }
    }

    return null;
}
```

---

### Bài 11 — ThreadLocal Memory Leak

**Context**

- Trading Gateway xử lý mỗi request trên một thread từ thread pool
- Developer dùng `ThreadLocal` để lưu correlation ID theo từng request
- Sau vài giờ: heap tăng liên tục, GC không giải phóng được, heap dump có hàng trăm nghìn object lạ

**Code hiện tại:**

```java
public class RequestContext {

    private static final ThreadLocal<String> correlationId = new ThreadLocal<>();

    public static void set(String id) {
        correlationId.set(id);
    }

    public static String get() {
        return correlationId.get();
    }
}

// Trong filter / handler:
public void handleRequest(Request req) {
    RequestContext.set(req.getCorrelationId());
    processOrder(req);
    // ... không có cleanup
}
```

> **Câu hỏi thêm:** Tại sao `ThreadLocal` gây memory leak trong thread pool nhưng KHÔNG gây leak nếu mỗi request dùng một thread riêng (per-request thread model)?
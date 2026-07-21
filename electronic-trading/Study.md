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

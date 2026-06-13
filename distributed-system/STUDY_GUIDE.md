# Distributed Systems — Study Guide for Java Developers

## 1. Foundations

### 1.1 What Is a Distributed System?
A distributed system is a collection of autonomous computers that communicate over a network and appear to users as a single coherent system.

**Key properties to reason about:**
- **Partial failure** — some nodes can fail while others keep running
- **Concurrency** — events happen simultaneously without a global clock
- **No shared memory** — nodes communicate only by message passing
- **Asynchrony** — messages can be delayed, reordered, or lost

---

### 1.2 The CAP Theorem
> A distributed store can guarantee at most **two** of: Consistency, Availability, Partition tolerance.

| Combination | Example systems | Trade-off |
|-------------|----------------|-----------|
| CP | ZooKeeper, HBase, etcd | Sacrifice availability during partitions |
| AP | Cassandra, DynamoDB, CouchDB | Sacrifice strong consistency; use eventual consistency |
| CA | Traditional RDBMS (single node) | Not partition-tolerant — not truly distributed |

**In Java:** When choosing between Hazelcast (CP-ish) vs. Apache Ignite, or Redis Cluster vs. a single Redis node, CAP governs the trade-off.

---

### 1.3 PACELC Extension
PACELC extends CAP: even when there is **no** partition, you still trade **Latency vs. Consistency**.

| System | P → | ELC |
|--------|-----|-----|
| DynamoDB | AP | EL (low latency) |
| Spanner | CP | EC (strong consistency) |

---

### 1.4 Consistency Models (weakest → strongest)
```
Eventual Consistency
  → Monotonic Reads
  → Monotonic Writes
  → Read-your-Writes
  → Causal Consistency
  → Sequential Consistency
  → Linearizability  (strongest; every op appears instantaneous)
  → Serializability  (strongest for transactions)
```

**Java angle:** JPA/Hibernate on a distributed DB — be explicit about which isolation level you actually get.

---

## 2. Time & Ordering

### 2.1 Logical Clocks
**Lamport Clock** — scalar counter; increments on each local event and on message receipt.
```java
// Lamport clock in Java
class LamportClock {
    private final AtomicLong time = new AtomicLong(0);

    public long tick() {
        return time.incrementAndGet();
    }

    public long update(long receivedTime) {
        return time.updateAndGet(t -> Math.max(t, receivedTime) + 1);
    }
}
```

**Vector Clock** — one counter per node; captures causal relationships.
```java
class VectorClock {
    private final Map<String, Long> clock = new ConcurrentHashMap<>();
    private final String nodeId;

    public void increment() {
        clock.merge(nodeId, 1L, Long::sum);
    }

    public void merge(Map<String, Long> other) {
        other.forEach((k, v) -> clock.merge(k, v, Math::max));
        increment();
    }

    public boolean happensBefore(Map<String, Long> other) {
        return clock.entrySet().stream()
            .allMatch(e -> e.getValue() <= other.getOrDefault(e.getKey(), 0L))
            && !clock.equals(other);
    }
}
```

### 2.2 Hybrid Logical Clocks (HLC)
Combines physical time + logical counter — used by CockroachDB. Gives you real-time ordering with bounded clock skew.

---

## 3. Replication

### 3.1 Single-Leader Replication
- One leader accepts writes; followers replicate.
- **Synchronous** replication: strong durability, higher latency.
- **Asynchronous** replication: lower latency, risk of data loss on failover.

**Java libs:** Spring Data with read replicas (routing `DataSource`), R2DBC for reactive read scaling.

### 3.2 Multi-Leader Replication
- Multiple leaders accept writes (active-active).
- Conflict resolution required: last-write-wins (LWW), CRDTs, application-level merge.

### 3.3 Leaderless Replication (Dynamo-style)
```
Write quorum: W  |  Read quorum: R  |  Replicas: N
For strong consistency: W + R > N
Typical: N=3, W=2, R=2
```
**Java:** Cassandra driver `ConsistencyLevel.QUORUM` / `LOCAL_QUORUM`.

### 3.4 CRDTs (Conflict-free Replicated Data Types)
Data structures that merge automatically without conflicts.
- G-Counter, PN-Counter, G-Set, 2P-Set, LWW-Register, OR-Set
- **Java libs:** Akka Distributed Data, Redis (via CRDT-like structures)

```java
// G-Counter example
class GCounter {
    private final Map<String, Long> counts = new HashMap<>();

    public void increment(String nodeId) {
        counts.merge(nodeId, 1L, Long::sum);
    }

    public long value() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    public void merge(GCounter other) {
        other.counts.forEach((k, v) -> counts.merge(k, v, Math::max));
    }
}
```

---

## 4. Consensus

### 4.1 Why Consensus Is Hard
**FLP Impossibility:** In a fully asynchronous system, no deterministic consensus algorithm can tolerate even one crash failure.

**Practical answer:** Use timeouts (partial synchrony assumption) → Paxos, Raft.

### 4.2 Raft (most approachable)
**Roles:** Leader, Follower, Candidate

**Election:**
1. Follower times out → becomes Candidate, increments term, votes for self.
2. Candidate wins majority → becomes Leader.
3. Leader sends heartbeats to prevent new elections.

**Log replication:**
1. Client sends command to Leader.
2. Leader appends to log, sends `AppendEntries` to followers.
3. Once a majority ACKs, Leader commits and responds to client.

**Java:** Apache Ratis (used in Apache Ozone), etcd client (via JEtcd).

### 4.3 Paxos (classic)
Three phases: Prepare → Promise → Accept → Accepted.
Multi-Paxos collapses phases for a stable leader.
**Used by:** Google Chubby, Zookeeper (ZAB — a Paxos variant).

### 4.4 Two-Phase Commit (2PC)
```
Coordinator → PREPARE → Participants
Participants → VOTE (YES/NO) → Coordinator
Coordinator → COMMIT/ABORT → Participants
```
**Problem:** Coordinator failure after PREPARE leaves participants blocked.
**Fix:** 3PC (adds pre-commit phase), or use Saga pattern for microservices.

---

## 5. Distributed Transactions & Patterns

### 5.1 Saga Pattern
Long-lived transaction decomposed into local transactions with compensating actions.
```
T1 → T2 → T3
       ↓ fail
C3 → C2 → C1 (compensations)
```
**Java/Spring:** Spring Modular Monolith + Saga, Axon Framework, Eventuate Tram.

### 5.2 Outbox Pattern
Atomically write an event to an outbox table with the business record; a relay publishes to the message broker.
```java
// In the same DB transaction:
orderRepo.save(order);
outboxRepo.save(new OutboxEvent("OrderCreated", order.getId(), payload));
// A CDC process (Debezium) or polling relay publishes the event
```

### 5.3 Two-Phase Locking (2PL) vs. MVCC
- **2PL:** Readers and writers block each other. Strong serializability.
- **MVCC:** Readers never block writers. Used by PostgreSQL, MySQL InnoDB.

---

## 6. Messaging & Event Streaming

### 6.1 Message Brokers vs. Event Logs
| | RabbitMQ / ActiveMQ | Kafka / Pulsar |
|-|---------------------|----------------|
| Model | Queue (consumed once) | Log (replay, retention) |
| Ordering | Per-queue | Per-partition |
| Throughput | Moderate | Very high |
| Use case | Task queues, RPC | Event sourcing, CDC, analytics |

### 6.2 Kafka Key Concepts (Java)
```java
// Producer with idempotence + transactions
Properties props = new Properties();
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "tx-1");

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.initTransactions();
producer.beginTransaction();
try {
    producer.send(new ProducerRecord<>("orders", key, value));
    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction();
}
```

**Key concepts:**
- **Partitions** — unit of parallelism and ordering
- **Consumer groups** — each partition consumed by exactly one consumer per group
- **Offsets** — deterministic replay
- **Exactly-once semantics** — idempotent producer + transactions

### 6.3 Backpressure
Slow consumer overwhelmed by fast producer.
- **Java:** Project Reactor `Flux.onBackpressureDrop()` / `onBackpressureBuffer()`
- **Kafka:** `max.poll.records`, `fetch.max.bytes` tuning

---

## 7. Service Discovery & Load Balancing

### 7.1 Service Discovery
- **Client-side:** Service queries registry (Eureka), picks instance, calls directly.
- **Server-side:** Load balancer (AWS ALB, nginx) queries registry.

**Java:** Spring Cloud Netflix Eureka, Consul + Spring Cloud Consul.

### 7.2 Load Balancing Algorithms
| Algorithm | Best for |
|-----------|----------|
| Round Robin | Homogeneous services |
| Least Connections | Variable request duration |
| Consistent Hashing | Stateful/cache-aware routing |
| Power of Two Choices | High-traffic, low-latency |

### 7.3 Consistent Hashing
Maps both servers and keys onto a ring; adding/removing nodes only remaps `K/N` keys.
```java
class ConsistentHashRing<T> {
    private final TreeMap<Long, T> ring = new TreeMap<>();
    private final int virtualNodes;

    public void addNode(T node) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hash(node.toString() + "#" + i);
            ring.put(hash, node);
        }
    }

    public T getNode(String key) {
        long hash = hash(key);
        Map.Entry<Long, T> entry = ring.ceilingEntry(hash);
        return (entry != null ? entry : ring.firstEntry()).getValue();
    }

    private long hash(String key) {
        // MurmurHash or MD5 truncated
    }
}
```

---

## 8. Fault Tolerance Patterns

### 8.1 Circuit Breaker
States: **CLOSED** (normal) → **OPEN** (failing, reject calls) → **HALF_OPEN** (probe recovery).
**Java:** Resilience4j
```java
CircuitBreaker cb = CircuitBreaker.ofDefaults("inventory");
Supplier<String> decorated = CircuitBreaker
    .decorateSupplier(cb, inventoryService::getStock);
Try.ofSupplier(decorated).recover(ex -> "fallback");
```

### 8.2 Bulkhead
Isolate failures: separate thread pools / semaphores per service dependency.
**Java:** Resilience4j `Bulkhead`, Hystrix thread-pool isolation.

### 8.3 Retry & Exponential Backoff with Jitter
```java
RetryConfig config = RetryConfig.custom()
    .maxAttempts(3)
    .waitDuration(Duration.ofMillis(500))
    .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(500, 2.0, 0.5))
    .retryExceptions(IOException.class)
    .build();
```

### 8.4 Timeouts & Deadlines
Always set timeouts on every network call. Propagate deadlines across service boundaries (gRPC `Deadline`).

---

## 9. Distributed Data Structures & Algorithms

### 9.1 Bloom Filter
Space-efficient probabilistic set — false positives possible, no false negatives.
```java
BloomFilter<String> filter = BloomFilter.create(
    Funnels.stringFunnel(UTF_8), 1_000_000, 0.01);
filter.put("user-123");
boolean mightExist = filter.mightContain("user-123");
```
**Use case:** Prevent unnecessary DB lookups (Cassandra SSTable bloom filters).

### 9.2 HyperLogLog
Approximate cardinality with O(log log N) space.
**Use case:** Count unique visitors. Redis `PFADD`/`PFCOUNT`.

### 9.3 Count-Min Sketch
Approximate frequency counting with bounded error.
**Use case:** Rate limiting, heavy hitter detection.

---

## 10. Distributed Caching

### 10.1 Cache Patterns
| Pattern | Description | Problem avoided |
|---------|-------------|-----------------|
| Cache-aside | App loads on miss | Stale data risk |
| Write-through | Write to cache + DB together | Cache miss on read |
| Write-behind | Write to cache, async to DB | Data loss risk |
| Read-through | Cache fetches from DB on miss | App complexity |

### 10.2 Cache Invalidation
- TTL-based (simple, may serve stale data)
- Event-driven invalidation (pub/sub on write)
- Tag-based invalidation (group keys under a tag, invalidate all)

### 10.3 Java: Redis with Lettuce / Jedis
```java
// Lettuce reactive
RedisClient client = RedisClient.create("redis://localhost");
StatefulRedisConnection<String, String> conn = client.connect();
RedisReactiveCommands<String, String> cmds = conn.reactive();

cmds.set("key", "value", SetArgs.Builder.ex(60))
    .then(cmds.get("key"))
    .subscribe(System.out::println);
```

---

## 11. Distributed Locking

### 11.1 Redis Redlock Algorithm
1. Try to acquire lock on N/2+1 Redis nodes within validity time.
2. Lock is valid only if majority acquired and time taken < TTL.
3. Release on all nodes (even failed ones) on done.

```java
// Spring Integration / Redisson
RLock lock = redisson.getLock("order-lock-" + orderId);
try {
    if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
        // critical section
    }
} finally {
    lock.unlock();
}
```

### 11.2 ZooKeeper Ephemeral Nodes
Ephemeral node disappears when the client disconnects — natural lock release.
```
/locks/order-lock → ephemeral sequential nodes
Node with lowest sequence number holds the lock
```

---

## 12. Rate Limiting

### 12.1 Algorithms
| Algorithm | Pros | Cons |
|-----------|------|------|
| Token Bucket | Smooth bursts allowed | State per client |
| Leaky Bucket | Strict rate | No burst |
| Fixed Window Counter | Simple | Spike at window boundary |
| Sliding Window Log | Accurate | High memory |
| Sliding Window Counter | Good approximation | Slight imprecision |

### 12.2 Java Implementation — Token Bucket
```java
class TokenBucket {
    private final long capacity;
    private final long refillRate; // tokens per second
    private double tokens;
    private long lastRefill;

    public synchronized boolean tryConsume(int n) {
        refill();
        if (tokens >= n) {
            tokens -= n;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefill) / 1e9;
        tokens = Math.min(capacity, tokens + elapsed * refillRate);
        lastRefill = now;
    }
}
```

---

## 13. Observability in Distributed Systems

### 13.1 The Three Pillars
- **Logs** — structured (JSON), correlation IDs, log levels
- **Metrics** — counters, gauges, histograms (Micrometer → Prometheus → Grafana)
- **Traces** — distributed tracing, spans, context propagation (OpenTelemetry)

### 13.2 Java — OpenTelemetry + Spring Boot
```java
// Auto-instrumented with spring-boot-starter-actuator + micrometer-tracing
// Manual span:
Tracer tracer = openTelemetry.getTracer("my-service");
Span span = tracer.spanBuilder("processOrder").startSpan();
try (Scope s = span.makeCurrent()) {
    // work
    span.setAttribute("order.id", orderId);
} finally {
    span.end();
}
```

### 13.3 Correlation IDs
Pass a `X-Request-ID` header across all service calls; include in every log entry.
**Java:** MDC (Mapped Diagnostic Context) in SLF4J.
```java
MDC.put("requestId", request.getHeader("X-Request-ID"));
log.info("Processing order {}", orderId);
MDC.clear();
```

---

## 14. Security in Distributed Systems

- **mTLS** — mutual TLS for service-to-service authentication (Istio, Linkerd sidecar)
- **JWT** — stateless auth token; validate signature on each service
- **Secret management** — HashiCorp Vault, AWS Secrets Manager (never hardcode)
- **Network policies** — Kubernetes NetworkPolicy to restrict pod-to-pod traffic

---

## 15. Key Java Libraries & Frameworks Summary

| Category | Library / Framework |
|----------|-------------------|
| Microservices | Spring Boot, Quarkus, Micronaut |
| Service mesh | Istio (Java app is transparent) |
| gRPC | grpc-java + protobuf |
| Messaging | Spring Kafka, Spring AMQP |
| Resilience | Resilience4j, Sentinel |
| Distributed cache | Lettuce (Redis), Hazelcast, Infinispan |
| Consensus/coordination | Apache Curator (ZooKeeper), JEtcd |
| Event sourcing | Axon Framework, Eventuate |
| Reactive | Project Reactor, RxJava, Vert.x |
| Observability | Micrometer, OpenTelemetry Java agent |
| Serialization | Protocol Buffers, Avro, Jackson |

---

## 16. Mental Models & Interview Cheat-Sheet

```
Scale reads      → Replication + Caching
Scale writes     → Sharding / Partitioning
Strong guarantees → Consensus (Raft/Paxos) + 2PL or Serializable SI
High availability → Leaderless + Quorum reads/writes
Async decoupling → Message broker (Kafka/RabbitMQ)
Long transactions → Saga + Outbox
Fault tolerance  → Circuit breaker + Retry + Bulkhead + Timeout
Ordering events  → Kafka partitions + monotonic offsets
Unique IDs       → Snowflake, ULIDs, Twitter Flake
```

---

## 17. Recommended Learning Path

1. **Read:** *Designing Data-Intensive Applications* (Kleppmann) — chapters in order
2. **Watch:** MIT 6.824 Distributed Systems lectures (free, YouTube)
3. **Code:** Implement Raft from scratch (paper: *In Search of an Understandable Consensus Algorithm*)
4. **Practice:** Build each exercise in `EXERCISES.md`
5. **Review:** Real systems — read Kafka, Cassandra, ZooKeeper documentation internals

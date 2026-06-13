# Distributed Systems — Exercises & Practice Problems

> Each exercise below maps to a small Java project. Start with the basic case, then follow the scaling path.
> Hints are intentionally sparse so you reason first. Expected interviewer focus is noted per exercise.

---

## Exercise 1 — Key-Value Store

**Description:**
Build an in-memory key-value store exposed over HTTP (REST or gRPC).
This is one of the most common system design interview questions. It covers almost every distributed systems concept: storage, concurrency, replication, partitioning, consistency, and failure handling. Interviewers use it to probe how deep your knowledge goes — they will keep asking "what happens if…" until you hit a wall.

---

### Stage 1 — Single Node (Basic)

**Requirements:**
- `PUT /keys/{key}` with a string body → stores the value
- `GET /keys/{key}` → returns the value or 404
- `DELETE /keys/{key}` → removes the key
- Single JVM, everything in memory

**What the interviewer is really checking here:**
- Do you reach for `HashMap` or `ConcurrentHashMap`? If you say `HashMap`, they will ask: *"what happens under concurrent requests?"* — you must know that `HashMap` is not thread-safe and can corrupt its own internal structure under concurrent writes (infinite loop in Java 7, data loss in Java 8+).
- Do you know the difference between `ConcurrentHashMap` (fine-grained segment locking) and `Collections.synchronizedMap` (full lock on every operation)?
- Do you expose a clean API contract — what HTTP status codes do you return? 200, 201, 204, 404, 409?
- Can you explain the time complexity of your reads and writes (O(1) average)?

**Expected Java code shape:**
```java
@RestController
@RequestMapping("/keys")
public class KeyValueController {
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    @PutMapping("/{key}")
    public ResponseEntity<Void> put(@PathVariable String key, @RequestBody String value) {
        store.put(key, value);
        return ResponseEntity.noContent().build(); // 204
    }

    @GetMapping("/{key}")
    public ResponseEntity<String> get(@PathVariable String key) {
        String value = store.get(key);
        return value != null ? ResponseEntity.ok(value) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        store.remove(key);
        return ResponseEntity.noContent().build();
    }
}
```

**Follow-up questions interviewers will ask at this stage:**
- *"What if two clients PUT the same key at the same time?"* — Last write wins in ConcurrentHashMap. Is that acceptable? Could you add a version/ETag for conditional writes?
- *"What is the memory limit? What happens when the store is full?"* — You need an eviction policy (LRU, LFU, FIFO). Implement LRU with `LinkedHashMap(capacity, 0.75f, true)` in access-order mode, wrapped with `synchronizedMap`.
- *"How would you add a TTL (time-to-live) per key?"* — Store `(value, expiresAt)` pairs; run a background `ScheduledExecutorService` to sweep expired keys; or use lazy expiration on `GET`.

---

### Stage 2 — Persistence (Durability)

**Requirements:**
- The store must survive a JVM crash and restart
- On startup, restore all keys from disk
- Writes must be durable: a `PUT` that returns 200 must not be lost

**What the interviewer is really checking here:**
- Do you understand why in-memory alone is not durable?
- Do you know what a Write-Ahead Log (WAL) is and why it is the standard pattern (used by PostgreSQL, Kafka, RocksDB)?
- Can you explain the fsync problem: writing to a file buffer is not durable unless you call `fsync()` (or `FileChannel.force(true)` in Java) to flush to disk hardware.
- Do you know the difference between a WAL and a snapshot? WAL = every individual change logged; snapshot = full state serialized periodically.

**WAL design:**
```
[WRITE] key=user:1 value={"name":"Alice"} timestamp=1718000000001
[WRITE] key=user:2 value={"name":"Bob"}   timestamp=1718000000002
[DELETE] key=user:1                        timestamp=1718000000003
```
On restart: replay entries top to bottom. Result: only `user:2` exists.

**Hybrid approach (WAL + periodic snapshot):**
- WAL captures every write — replay from scratch is O(total writes ever).
- Snapshot captures current full state — restart replays from latest snapshot + WAL tail only.
- This is exactly how Redis AOF + RDB works.

**Follow-up questions:**
- *"How do you handle a corrupt WAL entry?"* — Write a checksum (CRC32) per entry; skip or truncate on corruption.
- *"Can you batch WAL writes for throughput?"* — Yes: group commit. Buffer writes for 1ms, fsync once. Trade: slightly higher latency in exchange for much higher write throughput.
- *"Where is the durability vs. throughput trade-off?"* — fsync on every write = durable but slow (~1-5ms per write). fsync async = fast but risk of losing last N ms of data on crash.

---

### Stage 3 — Partitioning (Horizontal Scale)

**Requirements:**
- Data is too large for one node → split (shard) across 3 nodes
- Each node owns a subset of the keyspace
- A client can talk to any node; it must be routed to the correct one

**What the interviewer is really checking here:**
- Do you know why `hash(key) % N` is naïve? (Changing N remaps ~all keys — huge reshuffling cost.)
- Can you explain consistent hashing: map both nodes and keys onto a ring; a key belongs to the first node clockwise from its hash position. Adding a node only remaps `1/N` of keys.
- Do you understand virtual nodes: each physical node owns many ring positions → more even distribution → fewer hot spots.

**Consistent hash ring (Java sketch):**
```java
class ConsistentHashRing {
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private static final int VNODES = 150;

    public void addNode(String node) {
        for (int i = 0; i < VNODES; i++) {
            ring.put(hash(node + "#" + i), node);
        }
    }

    public String getNode(String key) {
        if (ring.isEmpty()) throw new IllegalStateException("no nodes");
        Long h = ring.ceilingKey(hash(key));
        return ring.get(h != null ? h : ring.firstKey());
    }
}
```

**Routing strategies:**
1. **Client-side routing** — client holds the ring, computes the target node, calls it directly. Fast, but every client must stay updated on cluster membership.
2. **Proxy/coordinator routing** — client calls any node; that node forwards to the owner. Simpler client, extra network hop.
3. **Gossip-based membership** — nodes share ring state via gossip protocol; eventual consistency on ring view.

**Follow-up questions:**
- *"What happens during a rebalance when a node is added?"* — Keys on the successor node that now belong to the new node must be migrated. How do you migrate without downtime? (Answer: dual-write during transition, then cut over.)
- *"What if two clients compute different rings because gossip is lagging?"* — One will route wrong → the receiving node either proxies or returns a redirect (HTTP 301 with the correct node URL).
- *"How do you handle hot keys (celebrity problem)?"* — One key gets 100% of traffic on one node. Solutions: key splitting (`user:1#shard0`, `user:1#shard1`), read replicas for hot keys, or a dedicated cache tier.

---

### Stage 4 — Replication (High Availability)

**Requirements:**
- Each partition has 1 leader + 2 follower replicas
- Reads can go to any replica (AP trade-off) or only to the leader (CP)
- The system stays available if one node crashes
- No data is lost when a follower crashes

**What the interviewer is really checking here:**
- Do you understand leader-follower replication? Leader handles all writes; followers replicate asynchronously or synchronously.
- Do you know the durability / availability / latency triangle?
  - Synchronous replication: write returns only after followers ACK → zero data loss, higher latency.
  - Asynchronous replication: write returns immediately → lower latency, risk of data loss if leader crashes before follower catches up.
- Do you know what a quorum is? `W + R > N` guarantees you always read the latest write.

**Quorum example with N=3, W=2, R=2:**
```
Write:  leader writes, waits for 1 follower to ACK → W=2 satisfied → return success
Read:   contact 2 nodes, take the value with the highest version → always sees the latest write
Proof:  at least 1 node in R must have seen the write (2+2 > 3, so they overlap by at least 1)
```

**Leader failover steps:**
1. Followers detect leader silence (heartbeat timeout ~3-5s).
2. Candidate follower starts election — votes from majority needed.
3. New leader elected; followers redirect writes to the new leader.
4. Old leader rejoins as a follower and resyncs.

**Follow-up questions:**
- *"What if the leader crashes right after writing but before replicating?"* — With async replication: data loss. Mitigation: use semi-sync (wait for at least 1 follower ACK before responding to client).
- *"Can two nodes both think they are the leader (split-brain)?"* — Yes, if there is a network partition. Solution: fencing tokens — the storage layer rejects writes from a leader whose term is stale.
- *"How does the new leader know what was committed vs. what was in-flight?"* — Use log sequence numbers (LSN). The new leader only applies entries up to the last committed LSN.

---

### Stage 5 — LSM-Tree & Compaction (Advanced)

**Requirements:**
- Write throughput is the bottleneck — optimize for write-heavy workloads
- Implement a Log-Structured Merge Tree (LSM-tree): the storage engine used by RocksDB, Cassandra, LevelDB

**What the interviewer is really checking here:**
- Do you understand why random writes to disk are slow (seek time ~10ms for HDD) and sequential writes are fast (~500MB/s)?
- Can you explain the LSM-tree write path: writes go to memory (MemTable) → flushed as immutable SSTable files → background compaction merges SSTables and removes tombstones.
- Read path: check MemTable → L0 SSTables → L1 → … (deeper = older, larger). Bloom filter on each SSTable to skip files that don't contain the key.

**LSM write path:**
```
PUT key=A → MemTable (in memory, sorted map)
PUT key=B → MemTable
... (MemTable hits size limit, e.g. 64MB)
→ Flush MemTable → SSTable file on disk (immutable, sorted)
→ New MemTable starts
... (too many L0 SSTables)
→ Compaction: merge-sort L0 files → L1 files (larger, fewer)
→ Old SSTables deleted
```

**Follow-up questions:**
- *"What is write amplification?"* — Data is written multiple times due to compaction. Each byte written by the client may be rewritten 10-30x to disk during compaction. Trade-off vs. B-tree (lower write amp, higher read amp in write-heavy workload).
- *"How do deletes work?"* — Write a tombstone marker (`DELETE key=A`). The actual deletion happens during compaction when the tombstone and the original key are in the same merge window.
- *"How do Bloom filters help reads?"* — Before scanning an SSTable, check its Bloom filter. If the filter says the key is absent, skip the file entirely — saves disk I/O.

---

**Scale path (summary):**
1. Add a Write-Ahead Log (WAL) for crash recovery
2. Partition data across 3 nodes using consistent hashing
3. Add replication (1 leader per partition, 2 followers)
4. Implement read quorum `R=2` / write quorum `W=2` with `N=3`
5. Add compaction: merge SSTables (LSM-tree approach)

**Overall interviewer expectations — what separates a good answer from a great one:**
- **Good:** Can implement a working single-node store, knows ConcurrentHashMap, can describe replication at a high level.
- **Great:** Reasons about failure modes at every step, knows the consistency/availability/latency trade-offs by name, can draw the data flow (client → ring → leader → followers → WAL → MemTable → SSTable), explains what happens during a network partition, and proposes monitoring metrics (write latency P99, replication lag, compaction queue depth).

---

## Exercise 2 — Distributed Counter

**Description:**
Implement a counter that can be incremented from multiple nodes concurrently and always returns the correct total.

**Basic case:**
- Single-node `AtomicLong`, REST endpoint to increment and read
- Demonstrate the race condition when two nodes use a shared DB counter

**Scale path:**
1. Implement a **G-Counter** (Grow-only CRDT) — each node owns its own slot
2. Add periodic gossip between nodes to synchronize state
3. Extend to a **PN-Counter** (increment + decrement)
4. Add bounded counter: refuse increment when total ≥ max (requires coordination)

**Hints:**
- Gossip: every 500ms, pick a random peer and exchange your vector of counts
- Merge rule: `max(local[i], remote[i])` for each node `i`
- For bounded counter you need consensus — try a simple token-ring approach

**What interviewers look for:**
- Understanding of eventual consistency vs. strong consistency
- Why CRDTs avoid coordination costs
- Edge case: network partition — what does each side see?

---

## Exercise 3 — Distributed Task Queue

**Description:**
Build a simple job queue: producers submit tasks, workers pull and execute them, results are stored.

**Basic case:**
- In-memory blocking queue, single producer, single consumer thread
- Tasks are simple `Runnable` with a string payload

**Scale path:**
1. Persist the queue to disk (SQLite or append-only file) so tasks survive crashes
2. Multiple consumer nodes — each task delivered to exactly one worker (at-least-once delivery)
3. Add acknowledgment + re-enqueue on timeout (no ack within 30s → retry)
4. Deduplication: idempotency key per task to prevent double execution
5. Priority queue: high-priority tasks jump the line
6. Replace custom queue with Kafka; demonstrate at-least-once vs. exactly-once

**Hints:**
- Use `DelayQueue<ScheduledTask>` for retry scheduling
- Idempotency: store `(idempotencyKey, status)` in a small DB table; check before executing
- For Kafka exactly-once: producer transactions + consumer `isolation.level=read_committed`

**What interviewers look for:**
- How do you prevent a task from being lost if the worker crashes mid-execution?
- What is the difference between at-most-once, at-least-once, exactly-once?
- How do you scale consumers without double-processing?

---

## Exercise 4 — Distributed Rate Limiter

**Description:**
Build a rate limiter that enforces `N requests per second per user` across multiple API server instances.

**Basic case:**
- Single-node token bucket: `100 req/s per userId`
- All state in a `ConcurrentHashMap<String, TokenBucket>`

**Scale path:**
1. Move state to Redis: `INCR` + `EXPIRE` for fixed window, Lua script for sliding window
2. Implement sliding window counter using two Redis keys (current + previous window)
3. Add a global rate limit shared across all users (total system capacity)
4. Handle Redis failure gracefully: fallback to local in-memory limiter
5. Implement token bucket via Redis + Lua script for atomicity

**Hints:**
- Sliding window: `rate = prev_count * (1 - elapsed/window) + curr_count`
- Redis Lua: `EVAL script 1 key tokens rate window now` — runs atomically
- For fallback: use a local approximate limiter + alert on Redis downtime

**What interviewers look for:**
- Why is the fixed window naive — where does the spike happen?
- How do you guarantee atomic check-and-decrement across requests?
- How do you handle the Redis node going down without dropping all traffic?

---

## Exercise 5 — Consistent Hashing Load Balancer

**Description:**
Implement a load balancer that routes requests to backend servers using consistent hashing, so adding/removing a server remaps the minimum number of keys.

**Basic case:**
- Static list of 3 servers, route by `hash(key) % N`
- Show how many keys remap when you add a 4th server

**Scale path:**
1. Implement a consistent hash ring with virtual nodes (150 vnodes per server)
2. Add live server registration/deregistration (simulate with REST endpoints)
3. Implement health checks: mark server unhealthy, reroute its keys
4. Add weighted consistent hashing (faster servers get more vnodes)
5. Compare consistent hashing vs. rendezvous (highest random weight) hashing

**Hints:**
- Use `TreeMap<Long, Server>` as the ring; `ceilingKey()` for lookup
- Virtual nodes: for server `S`, add `hash("S#0")` through `hash("S#149")`
- For rendezvous hashing: `score(server, key) = hash(server + key)` — pick max

**What interviewers look for:**
- What happens to in-flight requests when a node leaves?
- How do virtual nodes improve key distribution uniformity?
- What is the trade-off between more vnodes and memory/CPU overhead?

---

## Exercise 6 — Leader Election

**Description:**
Implement a leader election protocol so that among N nodes exactly one is elected leader at all times, even under node failures.

**Basic case:**
- Bully algorithm: nodes have IDs; highest alive ID wins
- 3 nodes, single JVM with threads, simulate crash by interrupting a thread

**Scale path:**
1. Implement Raft leader election (terms + randomized election timeouts)
2. Use ZooKeeper ephemeral sequential nodes for election (real cluster)
3. Handle split-brain: ensure only one leader per term with fencing tokens
4. Handle re-election when leader is slow (not crashed) — leader lease

**Hints:**
- Bully: on leader failure, all nodes with higher ID send ELECTION message; highest wins
- Raft: election timeout = random between 150-300ms; heartbeat every 50ms
- Fencing token: monotonically increasing token from election; storage layer rejects stale tokens

**What interviewers look for:**
- What happens if two nodes both think they are the leader (split-brain)?
- What is the difference between a crash and a slow node? Why does it matter?
- How do you prevent a node from acting on a stale leadership claim?

---

## Exercise 7 — Distributed Cache with Invalidation

**Description:**
Build a caching layer in front of a database that keeps multiple cache nodes consistent when the underlying data changes.

**Basic case:**
- Single Redis node, cache-aside pattern, TTL of 60s
- Demonstrate stale read between write and cache expiry

**Scale path:**
1. Event-driven invalidation: on DB write, publish `INVALIDATE key` to Redis pub/sub
2. Multi-region cache: invalidation message must reach all regions
3. Implement cache stampede prevention: probabilistic early recomputaton (XFetch)
4. Implement two-level cache: L1 local Caffeine + L2 Redis; invalidate both
5. Add write-through: write to cache and DB in the same transaction (Outbox)

**Hints:**
- XFetch: recompute early with probability `P = exp((TTL_remaining - β*log(delta)) / TTL)`
- Two-level: on L1 miss, check L2; on L2 miss, query DB and populate both
- For multi-region: use a Kafka topic `cache-invalidations`; each region consumes it

**What interviewers look for:**
- What is a cache stampede and how do you prevent it?
- What are the consistency implications of each cache pattern?
- How do you handle the case where Redis is down?

---

## Exercise 8 — Saga Pattern (Distributed Transactions)

**Description:**
Implement an order placement flow spanning 3 services (Order, Payment, Inventory) using the Saga pattern with compensating transactions.

**Basic case:**
- Choreography saga: each service publishes events, next service listens
- Happy path: OrderCreated → PaymentProcessed → InventoryReserved → OrderConfirmed

**Scale path:**
1. Add failure handling: PaymentFailed → CancelOrder event back to Order service
2. Implement Orchestration saga: a central `OrderSagaOrchestrator` drives the steps
3. Add idempotency: re-delivered events must not double-charge or double-reserve
4. Add the Outbox pattern so events are never lost even if the service crashes mid-saga
5. Add a saga state machine with persistence (store saga state in DB)

**Hints:**
- Choreography: use Spring Kafka; each service has its own topic
- Orchestrator: it sends commands and listens for replies; state machine via Axon or manual
- Outbox: one table per service; a relay polls and publishes; use `FOR UPDATE SKIP LOCKED`
- Idempotency key: include `sagaId + stepName` in the command; check before processing

**What interviewers look for:**
- What is the difference between choreography and orchestration? When to use each?
- How do you handle partial failures mid-saga?
- How do you avoid compensations creating new failures?
- What happens if the orchestrator itself crashes?

---

## Exercise 9 — Distributed Search / Inverted Index

**Description:**
Build a distributed full-text search engine that indexes documents across multiple shards and returns ranked results.

**Basic case:**
- Single-node inverted index: `Map<String, List<DocId>>` built from documents
- BM25 scoring for result ranking

**Scale path:**
1. Shard the index across N nodes by term hash (scatter-gather query)
2. Add document-based sharding: all terms of a doc on one shard (simpler for updates)
3. Add a coordinator: fan out query to all shards, merge and rank results
4. Add near-real-time indexing: buffer new docs in memory, merge with disk index
5. Add replication per shard; handle primary failure with replica promotion

**Hints:**
- Scatter-gather: coordinator sends query to all shards in parallel (CompletableFuture.allOf)
- Merge results: use a min-heap (priority queue) to merge N sorted lists
- Near-real-time: keep an in-memory segment; refresh every 1s (like Elasticsearch)

**What interviewers look for:**
- How does query fan-out latency grow with N shards? (It doesn't — it's bounded by slowest shard)
- How do you re-shard when adding nodes?
- What is the trade-off between term-based and document-based sharding?

---

## Exercise 10 — Time-Series Metrics Store

**Description:**
Build a write-heavy time-series database for storing and querying metrics (like a tiny Prometheus/InfluxDB).

**Basic case:**
- Single node, in-memory `Map<MetricName, TreeMap<Timestamp, double>>>`
- Write metric point, query range with aggregation (avg, max, sum)

**Scale path:**
1. Persist to disk using columnar storage (one file per metric, sorted by time)
2. Partition by time range (one file per hour): enables fast range scans
3. Add compression: delta encoding for timestamps, XOR encoding for float values
4. Shard metrics across nodes (consistent hash by metric name)
5. Add a downsampling/rollup job: summarize per-minute data into per-hour data
6. Add out-of-order write handling with a short buffer window

**Hints:**
- Delta encoding: store `[t0, Δt1, Δt2, ...]` — timestamps are usually monotonic
- XOR float encoding: XOR consecutive doubles — leading/trailing zeros are common
- Out-of-order: `Set<DataPoint>` sorted by timestamp; flush when `now - minTimestamp > 60s`

**What interviewers look for:**
- How do you efficiently scan a time range without reading all data?
- What is the write amplification cost of your storage approach?
- How do you handle a metric that is written to 10k times/second?

---

## Exercise 11 — Distributed Lock Service

**Description:**
Build a distributed locking service that ensures mutual exclusion across multiple JVM processes.

**Basic case:**
- Two threads in the same JVM using `ReentrantLock`; show race condition without it
- Move to `synchronized` block, then explain why this fails across JVMs

**Scale path:**
1. Implement pessimistic locking via database row lock (`SELECT FOR UPDATE`)
2. Implement via Redis `SET key uuid NX PX ttl` + fencing token
3. Implement Redlock across 3 Redis nodes
4. Implement via ZooKeeper ephemeral sequential nodes
5. Handle lock holder crash: TTL auto-release for Redis; session expiry for ZooKeeper
6. Add lock re-entrancy: same thread can re-acquire (with count tracking)

**Hints:**
- Redis: always `SET nx px` in one command (atomic); never `GET` + `SET`
- Fencing token: return the Redis `INCR` value as the lock token; pass to resource; resource rejects stale tokens
- ZooKeeper: create `/locks/resource-SEQUENTIAL`; watch the node with the next lower sequence

**What interviewers look for:**
- What happens if the lock holder pauses (GC pause) past the TTL?
- How does a fencing token protect against this?
- What is the Martin Kleppmann critique of Redlock?
- When would you use ZooKeeper over Redis for locking?

---

## Exercise 12 — Event Sourcing + CQRS

**Description:**
Build a bank account system where the state is derived entirely from an ordered event log.

**Basic case:**
- `Account` aggregate with events: `AccountOpened`, `MoneyDeposited`, `MoneyWithdrawn`
- Replay all events to get current balance

**Scale path:**
1. Persist events to an append-only event store (PostgreSQL with optimistic concurrency)
2. Add snapshotting: save state every 100 events to avoid full replay
3. Implement a read model (projection): `AccountSummary` table updated by event handlers
4. Separate write model (command side) and read model (query side) — CQRS
5. Add an event bus (Kafka): events published after being stored; read models consume async
6. Handle eventual consistency in the read model: how to tell if a read is "fresh enough"?

**Hints:**
- Optimistic concurrency: store `expectedVersion` on command; check before appending
- Snapshot: store `(aggregateId, version, stateJson)` alongside events
- Read model lag: include `eventTimestamp` in projections; expose `lastProcessedEventId`
- For querying stale reads: `GET /accounts/123?requireVersion=45` — poll until caught up

**What interviewers look for:**
- How do you guarantee event ordering per aggregate?
- What is the trade-off between snapshotting frequency and replay time?
- How does CQRS help with scaling reads vs. writes independently?
- How do you handle schema evolution of events?

---

## Progression Summary

```
Level 1 (Basics):    Exercises 1, 2, 4          → Data structures, concurrency, CAP
Level 2 (Patterns):  Exercises 3, 5, 7, 11       → Queue, hashing, cache, locking
Level 3 (Protocols): Exercises 6, 8, 12          → Consensus, Saga, Event Sourcing
Level 4 (Systems):   Exercises 9, 10             → Full system design and storage
```

**For each exercise, after coding:**
1. Draw the architecture diagram
2. Identify failure modes (what breaks, what data is lost, what becomes inconsistent)
3. Define the SLO (latency P99, availability target)
4. Describe how you would test it (unit, integration, chaos)
5. Estimate the throughput bottleneck and where you would scale next

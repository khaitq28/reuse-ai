# Distributed Cache — Deep Design Guide

## Problem Statement

Design a distributed in-memory cache like Redis or Memcached — a system that stores frequently accessed data in memory to dramatically reduce database load and serve high-throughput reads with sub-millisecond latency.

---

## Why This Problem Matters

Caching is the single most impactful optimization available in distributed systems. A well-designed cache can reduce database load by 90%+, reduce P99 latency from 50ms to 0.5ms, and allow a system to scale 10x without hardware changes. But caching is also one of the most common sources of subtle bugs:

- **Stale data**: Users see outdated information after updates
- **Cache stampede**: A popular key expiring causes thousands of simultaneous DB queries
- **Cache penetration**: Requests for non-existent keys bypass the cache and hammer the DB
- **Cache avalanche**: Many keys expiring simultaneously overwhelms the DB
- **Consistency bugs**: Cache and DB diverge in ways that corrupt user data

**What interviewers are testing**: Whether you understand the tradeoffs between the three main caching patterns (cache-aside, write-through, write-behind), whether you know all three cache failure modes and their distinct solutions, whether you understand consistent hashing deeply (not just "it distributes load"), and whether you think about cache as a system component with failure modes and operational concerns.

---

## Key Insight Before Diving In

**A cache is not a faster database. It is an intentionally lossy, approximated view of your database that trades consistency for speed.**

The most important design decisions in caching are not about the cache itself — they are about the **invalidation strategy**: when does the cache stop being the source of truth? How quickly does it need to catch up? What happens to user experience when it diverges?

The second key insight: **caching in a distributed system introduces a second writer problem**. In a single-server system, cache invalidation is trivial. In a 20-server cluster, when server A updates the DB and server B is serving a stale cached value from 3 seconds ago — that is not a bug, that is the expected behavior. Your system must be designed to tolerate this.

---

## Requirements

### Functional
- GET / SET / DELETE key-value pairs with arbitrary payloads
- TTL (time-to-live) per key — automatic expiry
- Multiple eviction policies (LRU, LFU, TTL-based)
- Atomic operations: INCR, SETNX (set if not exists), GETSET
- Pub/Sub for event notifications
- Cluster mode with automatic sharding across nodes
- Persistence options (RDB snapshots, AOF write-ahead log)

### Non-Functional
- < 1ms P99 read/write latency (in-memory, not dependent on disk)
- 99.999% availability (cache unavailability degrades but doesn't stop the app)
- Handle 1M ops/sec across the cluster
- Horizontal scaling: add nodes without downtime
- Replication: each shard has at least 1 replica for fault tolerance

---

## Capacity Estimation

```
Throughput: 1M ops/sec
  Single Redis node: ~100-200k ops/sec (single-threaded command processing)
  Need: 6-10 nodes for 1M ops/sec

Memory planning:
  Working dataset: 100GB (hot data that fits in RAM)
  Replication factor: 2 → 200GB total across cluster
  Node memory: 32GB each → need 7 nodes (primary) + 7 replicas = 14 nodes

  Key sizing rule of thumb:
    String key + overhead: ~90 bytes minimum
    String value 1KB:      ~1090 bytes per entry
    Hash with 10 fields:   ~650 bytes
    Sorted set 1000 items: ~80KB

  Example: 10M user session objects × 2KB each = 20GB → 1 Redis node

Network:
  1M ops/sec × 1KB avg payload = 1GB/sec
  Each 10GbE NIC handles ~1.2GB/sec → 1 NIC per node is sufficient
```

---

## Data Partitioning: Why Consistent Hashing?

### The Problem with Modulo Hashing

```
naive: node_index = hash(key) % N

Scenario: 4 nodes, then add a 5th node

key "user:123": hash = 47
  Before: 47 % 4 = 3 → Node 3
  After:  47 % 5 = 2 → Node 2 (DIFFERENT!)

Result: virtually every key maps to a different node when N changes.
  → Cache miss rate spikes to ~80% on scaling events
  → Database gets 80% of traffic → potential overload
  → This is a cache avalanche triggered by scaling
```

### Consistent Hashing — The Solution

```
Hash ring: circular space from 0 to 2^32 (4 billion points)

Physical nodes placed at specific ring positions (by hashing node name):
  Node A: hash("NodeA") = 0x3F2A → position 1,059,114,496
  Node B: hash("NodeB") = 0x8C4E → position 2,354,315,264
  Node C: hash("NodeC") = 0xD1F7 → position 3,523,379,200

Key lookup:
  key = "user:123": hash = 0x6B3C → position 1,797,291,008
  Walk clockwise → first node encountered = Node B → that's the owner

Adding Node D at position 0xA2B3 → position 2,729,082,880 (between B and C):
  Only keys between B and D (currently owned by C) move to D
  → Only 1/N of keys affected (25% with 4 nodes → now 20% with 5 nodes)
  → Cache miss rate: 20%, not 80%
```

### Virtual Nodes (Vnodes) — Solving Uneven Distribution

```
Problem: with 3 physical nodes and random ring positions, distribution may be:
  Node A: owns 60% of ring
  Node B: owns 30% of ring
  Node C: owns 10% of ring
  → Hot spot on Node A, underutilized Node C

Solution: each physical node → 150 virtual nodes (by hashing "NodeA-1", "NodeA-2", ..., "NodeA-150")
→ 3 physical nodes × 150 = 450 points on ring
→ Each physical node owns ~33% of ring (statistically uniform)
→ Adding a new physical node: its 150 vnodes take ~33% from each existing node
→ Perfect load distribution
```

---

## Eviction Policies — When Cache Is Full

When Redis memory is full and a new key is written, Redis must evict something. The policy determines what gets evicted:

```
allkeys-lru:         Evict least recently used key from all keys
                     Best for: general-purpose caching (80% of use cases)
                     Why: recently used keys are more likely to be needed again

volatile-lru:        Evict LRU key only among keys WITH an expiry set
                     Best for: mixing expirable cache and permanent config data

allkeys-lfu:         Evict least frequently used key
                     Best for: access patterns with clear hot/cold data
                     Why: a key accessed 1000x/sec and a key accessed 1x/sec
                          → LRU might evict the hot key if not recently accessed
                          → LFU always keeps the frequently accessed key

allkeys-random:      Evict a random key
                     Best for: when you don't care much about eviction quality
                               (fast, simple, no overhead)

noeviction:          Reject writes when memory full → OOM error to client
                     Best for: never for caches; for durability-critical queues
```

### LRU Implementation Detail

```java
// O(1) LRU cache: HashMap + Doubly Linked List
// HashMap: O(1) key lookup → find the node in the linked list
// Doubly Linked List: O(1) move-to-head and remove-from-tail

class LRUCache {
    private final int capacity;
    private final Map<String, Node> map = new HashMap<>();
    private final Node head = new Node(null, null); // dummy head (most recent)
    private final Node tail = new Node(null, null); // dummy tail (least recent)

    // On GET: move node to head (mark as recently used)
    public String get(String key) {
        Node node = map.get(key);
        if (node == null) return null; // cache miss
        moveToHead(node);              // O(1): update prev/next pointers
        return node.value;
    }

    // On SET: add to head; if over capacity, evict from tail
    public void set(String key, String value) {
        Node existing = map.get(key);
        if (existing != null) {
            existing.value = value;
            moveToHead(existing);
        } else {
            Node node = new Node(key, value);
            map.put(key, node);
            addToHead(node);
            if (map.size() > capacity) {
                Node evicted = removeTail(); // O(1)
                map.remove(evicted.key);
                // Eviction callback: log, increment counter, notify if needed
            }
        }
    }
}
```

---

## Caching Patterns — The Three Strategies

### Cache-Aside (Lazy Loading) — Most Common

```
Application controls cache explicitly. Cache is a side-effect, not the system of record.

READ:
  value = cache.get(key)
  if value == null:            // cache miss
    value = db.query(key)      // expensive DB read
    cache.set(key, value, ttl) // populate cache for next request
  return value

WRITE:
  db.update(key, newValue)
  cache.delete(key)            // INVALIDATE, don't update
  // On next read, cache will be populated with fresh data

Why invalidate instead of update on write?
  Race condition if you update:
    Thread 1: db.update(key, A)
    Thread 2: db.update(key, B)
    Thread 2: cache.set(key, B)  ← B is correct
    Thread 1: cache.set(key, A)  ← A is now stale in cache!
  Invalidation avoids this: delete is safe, next read gets fresh value.

Pros:
  Cache only contains data that's actually been requested
  DB is the system of record — cache failure = degraded performance, not failure
  Resilient: cache can restart without affecting correctness

Cons:
  First access after miss is slow (cold start)
  Cache miss under high load causes "thundering herd" (many concurrent misses)
```

### Write-Through

```
Every write goes to DB AND cache simultaneously (synchronously).

WRITE:
  db.update(key, value)     // write to DB
  cache.set(key, value)     // write to cache in same operation

READ:
  value = cache.get(key)    // always hits (if key exists)
  return value              // no DB read needed

Pros:
  Cache is always consistent with DB after a write
  Read performance: nearly every read is a cache hit

Cons:
  Write latency doubles (must wait for both DB and cache)
  Cache fills with data that may never be read (write-heavy keys that are never queried)
  If cache goes down during write: write-through fails → must handle cache unavailability
```

### Write-Behind (Write-Back)

```
Writes go to cache immediately; DB write is async (batched later).

WRITE:
  cache.set(key, value)         // instant return
  queue.publish(WriteEvent(key, value))  // async

DB writer worker:
  event = queue.consume()
  db.update(event.key, event.value)

Pros:
  Write latency = cache latency only (sub-millisecond)
  Batching reduces DB write amplification (100 updates → 1 batch write)

Cons:
  If cache crashes between write and DB persistence → DATA LOSS
  DB may be stale → read from DB != read from cache
  Complexity: must track "dirty" keys, handle write ordering, failure recovery

Use case: gaming leaderboards (losing 1 score update is acceptable),
          analytics counters (approximate is fine),
          NOT financial data (never use write-behind for money)
```

---

## Cache Failure Modes — The Three Classic Problems

These are the three most common cache failure scenarios and MUST be understood precisely. Each has a distinct cause and a distinct solution.

### Problem 1: Cache Stampede (Thundering Herd)

```
Cause:
  Popular key (e.g., "trending-products") expires.
  At the moment of expiry, 10,000 concurrent requests all get a cache miss.
  All 10,000 hit the database simultaneously to reload the key.
  Database is overwhelmed → slow → timeouts → cascading failure.

This is most dangerous on keys that are:
  - Very popular (many concurrent readers)
  - Expensive to compute/query (slow DB query)
  - Have a specific expiry time (all miss at exact same second)

Solutions:

A) Probabilistic Early Expiration (PER):
   Don't wait for expiry. Start refreshing a bit before.
   Each request checks:
     if (remaining_ttl < threshold AND random() < probability):
       refresh_cache_in_background()
   → Cache never actually expires for popular keys;
     1 lucky thread refreshes while others serve stale data

B) Mutex Lock (Cache Lock):
   On cache miss, try to acquire a distributed lock:
     if redis.setnx("lock:trending-products", 1, ex=10):
       value = db.query()     // I got the lock, I query DB
       cache.set(key, value)
       redis.del("lock:trending-products")
     else:
       time.sleep(50ms)       // Someone else is querying, wait and retry
       value = cache.get(key) // Should be populated by now
   → Only 1 DB query, others wait briefly

C) Stale-While-Revalidate:
   Serve stale data while refreshing in background:
     value, age = cache.get_with_age(key)
     if age > soft_ttl:
       background_refresh(key)  // async, non-blocking
     return value               // immediately return stale value
   → Zero wait for user; DB refresh happens in parallel
```

### Problem 2: Cache Penetration

```
Cause:
  Requests for keys that DO NOT EXIST in the database.
  Examples:
    - User queries product ID 99999999 (doesn't exist)
    - Attacker queries random non-existent IDs
    - Stale references in old bookmarks/links

  cache.get("product:99999999") → MISS
  db.query("product:99999999") → NOT FOUND (returns null)
  cache does NOT cache the null → next request = MISS again
  → DB gets every single request for this non-existent key

Solutions:

A) Cache Null Values:
   cache.set("product:99999999", "NULL", ttl=5min)
   On next request: cache.get → "NULL" → return 404 immediately
   Tradeoff: 5-minute delay for detecting real data creation

B) Bloom Filter (Better):
   Before cache lookup, check if key MIGHT exist in DB:
     if !bloomFilter.mightContain(key):
       return 404  // definitely not in DB, no cache or DB call

   Bloom filter is built at startup from DB primary keys.
   Updated when new records are created.
   False positive rate: ~0.1% (a tiny fraction of non-existent keys get through)
   False negative rate: 0% (if in DB, always passes the filter)
   → 99.9% of non-existent key requests are rejected at the bloom filter
   → DB receives virtually zero invalid queries
```

### Problem 3: Cache Avalanche

```
Cause:
  Many keys expire at the same time (or cache restarts).
  Examples:
    - All product prices set with TTL=1800s at 09:00 → all expire at 09:30 simultaneously
    - Cache server restarts → all cached data lost at once (cold start)
    - Periodic batch job that sets many keys with the same TTL

  All these keys miss simultaneously → DB overwhelmed.
  Different from stampede (single key) → here it's MANY keys at once.

Solutions:

A) TTL Jitter (Randomization):
   Instead of:  cache.set(key, value, ttl=3600)
   Use:        cache.set(key, value, ttl=3600 + random(-360, 360))
   → Keys expire at different times (spread over ±10% window)
   → No synchronized mass expiry

B) Circuit Breaker on DB:
   If DB response time > threshold, fail fast and serve stale cache
   → Even avalanche-level miss rate doesn't crash DB

C) Warm-Up Strategy (for restart):
   Before switching to new cache: pre-populate with hot keys
   Use a "cache warmer" job that loads top-N most-queried keys from DB
   → New cache node starts warm, not cold
```

---

## Redis Cluster Internals

```
Redis Cluster uses hash slots (not consistent hashing):
  16,384 total hash slots (0 to 16383)
  Each shard (master node) owns a range of hash slots

Key → slot:  slot = CRC16(key) % 16384

Example (3-node cluster):
  Node A: slots 0 - 5461      (33% of slots)
  Node B: slots 5462 - 10922  (33% of slots)
  Node C: slots 10923 - 16383 (34% of slots)

Adding Node D (resharding):
  Move slots from A, B, C to D:
    A: 0 - 4095    (25%)
    B: 5462 - 9556 (25%)
    C: 10923 - 14016 (25%)
    D: 4096 - 5461 + 9557 - 10922 + 14017 - 16383 (25%)
  No downtime: slots are moved one at a time; clients get MOVED redirects during transition

Replication:
  Each master has 1+ replica
  Replicas serve reads (with stale data tolerance)
  On master failure: replica is promoted automatically (Sentinel or Cluster failover)

Client awareness:
  Redis Cluster client (Jedis, Lettuce) maintains slot map
  Knows which node owns which slots
  On wrong-node request: server replies MOVED → client retries correct node
  Client caches slot map, refreshes on MOVED response
```

---

## Key Design Patterns

```
Naming convention: {service}:{entity}:{id}:{aspect}

Examples:
  user:session:abc-token-123       → session data (JSON), TTL=1800s
  user:profile:uuid-456            → profile JSON, TTL=3600s
  user:perm:uuid-456               → permission set, TTL=300s
  product:detail:uuid-789          → product JSON, TTL=86400s
  product:search:hash-of-query     → search result, TTL=60s
  rate:limit:user:uuid-456:api     → integer counter, TTL=window
  order:status:uuid-123            → status string, TTL=300s
  lock:booking:seat-id-789         → distributed lock, TTL=10s

Anti-patterns to avoid:
  ❌ cache:data                     → too generic, name collision risk
  ❌ user_data_123                  → no structure, hard to group/analyze
  ❌ really:long:key:name:that:wastes:memory → key stored in RAM too
  ❌ key with spaces or special chars → quoting issues in Redis CLI
```

---

## Tech Stack

- **Cache**: Redis 7.x (cluster mode, 6+ nodes)
- **Java Client**: Lettuce (reactive, connection pooling) or Jedis (synchronous, simpler)
- **Spring Integration**: Spring Data Redis (Repository abstraction, RedisTemplate)
- **Serialization**: JSON (readable) or Kryo (compact, fast) — avoid Java Serialization
- **Monitoring**: Redis Exporter → Prometheus → Grafana
- **Key metrics**: hit_rate, memory_used, evicted_keys/sec, latency_percentiles

---

## Interview Q&A

**Q1: What is the difference between cache stampede, cache penetration, and cache avalanche? Why are they different problems?**

A: They are three distinct failure modes with different causes, effects, and solutions. **Cache stampede** (thundering herd): a single popular key expires, and many concurrent readers all miss and hit the DB simultaneously — solutions are mutex locking or probabilistic early expiration. **Cache penetration**: requests for keys that don't exist in the DB at all; the cache can never be populated, so every request hits the DB — solutions are caching null values or using a bloom filter. **Cache avalanche**: many keys expire at the same time (synchronized TTL or full cache restart), causing a mass miss event — solution is TTL jitter. The mistake is thinking they're the same problem with the same solution. A bloom filter won't help stampede; a mutex won't help avalanche; jitter won't help penetration.

---

**Q2: Explain consistent hashing and why it's better than modulo hashing for a distributed cache.**

A: Modulo hashing (`hash(key) % N`) is simple but catastrophically bad when `N` changes — adding or removing even one node changes the mapping for roughly `(N-1)/N` of all keys (e.g., adding the 5th node remaps ~80% of keys). This means a scale-up event causes a cache miss avalanche followed by DB overload. Consistent hashing places both nodes and keys on a circular hash ring. A key is owned by the first node clockwise from its ring position. When a node is added, only the keys between the new node and its predecessor need to remigrate — roughly `1/N` of all keys. With virtual nodes (150 vnodes per physical node), load distribution is statistically uniform regardless of the raw ring positions of physical nodes.

---

**Q3: When would you choose write-through over cache-aside, and what are the tradeoffs?**

A: Choose **write-through** when: (1) read-heavy workload where cache hit rate matters most — every key written is immediately cached so subsequent reads are always hits; (2) consistency is critical and you can tolerate higher write latency; (3) the working set (what gets read) is roughly equal to what gets written (no wasteful caching of write-only data). Choose **cache-aside** when: (1) the read set is a small subset of the write set (many DB writes for data rarely read — write-through would waste cache memory); (2) you want the cache to be optional infrastructure (app works correctly even when cache is down); (3) writes are frequent and write latency must be minimal. Cache-aside is the most common pattern because it naturally implements "cache what gets read," avoiding wasted memory. The key drawback of cache-aside is the thundering herd on popular key expiry.

---

**Q4: How do you handle cache invalidation in a microservices architecture where multiple services share the same data?**

A: This is one of the hardest problems in distributed caching. Options: (1) **TTL-based**: let caches expire naturally. Simplest, but allows stale data for up to TTL duration. Acceptable for most use cases. (2) **Event-driven invalidation**: when a service updates data, it publishes an event (Kafka) that other services consume to invalidate their caches. More complex, but near-instant consistency. Example: User Service updates a user's name → publishes `user.updated` → Order Service, Feed Service, Chat Service all consume and delete their cached user objects. (3) **Cache-per-service**: each service owns its cache and doesn't share cache keys with others. Avoids cross-service invalidation entirely. Slightly more memory but cleaner boundaries. Recommendation: prefer option 3 (service-owned caches) with option 2 as the invalidation mechanism.

---

**Q5: A cache key is accessed 100,000 times per second (a "hot key"). How do you handle this?**

A: A single Redis key is always owned by one shard (slot owner). At 100k ops/sec, that shard becomes a hotspot: its CPU, memory bandwidth, and network are saturated while other shards are idle. Solutions: (1) **Local in-process cache** (Caffeine): cache the hot key in JVM heap on each app server. Top 100 hot keys × 10 app servers = zero Redis calls. TTL 1-5 seconds (very short to avoid stale data). (2) **Key replication**: store the same key under multiple names (`product:top:1`, `product:top:2`, ...) and randomly pick one on each read — spreads the load across multiple shards. (3) **Redis cluster read from replicas**: configure `READONLY` commands to be served by replica nodes, distributing the read load. (4) **CDN/application-level caching**: for truly hot public data (homepage banner, global config), cache at the CDN edge — zero Redis calls.

---

**Q6: Redis is single-threaded. How does it handle 200,000 ops/sec?**

A: Redis uses an event-driven I/O multiplexing model (similar to nginx) — a single thread processes commands, but uses `epoll`/`kqueue` to handle thousands of simultaneous network connections without blocking. The single thread eliminates lock contention entirely (no mutex, no CAS operations), which is why Redis outperforms multi-threaded caches in many benchmarks. The bottleneck is not CPU cycles per command (a GET takes ~1 microsecond), it's network round-trips. At 200k ops/sec × 1μs/op = 200ms of CPU time per second — plenty of headroom on a modern core. For truly high throughput, Redis Cluster distributes commands across multiple nodes (each with its own event loop), effectively multi-threading at the cluster level. Redis 6.0+ also added multi-threaded I/O for network reads/writes while keeping command processing single-threaded.

---

**Q7: How would you implement a distributed lock using Redis, and what are its failure modes?**

A: The standard pattern is `SETNX` (SET if Not eXists): `SET lock:resource-id {client-id} NX EX 30` — atomically sets the key only if it doesn't exist, with a 30-second expiry. Failure modes: (1) **Lock held too long**: if the holder crashes after acquiring the lock, the lock is held for up to 30 seconds (TTL). Other clients must wait. Mitigation: use a lock renewal (heartbeat) to extend TTL while the operation is in progress. (2) **Holder crashes mid-operation**: lock expires, another client acquires, both operate concurrently. Mitigation: idempotent operations (at-most-once via unique operation IDs). (3) **Redis crashes during failover**: the new master may not have the lock key if replication was async. Mitigation: Redlock algorithm (acquire on majority of 5 independent Redis nodes). For most use cases, single-node Redis lock is sufficient; Redlock for high-stakes distributed coordination.

---

**Q8: What is the difference between Redis eviction and Redis expiry? Why does it matter operationally?**

A: **Expiry** (TTL): a key is set to automatically delete after N seconds, regardless of memory. This is time-based. The application explicitly controls when data becomes stale. Keys with expired TTLs are deleted lazily (on next access) and periodically in a background sweep. **Eviction**: when total memory usage exceeds `maxmemory`, Redis proactively deletes keys according to the eviction policy (LRU, LFU, etc.), regardless of their TTL. Eviction is memory pressure-driven. The operational concern: if your eviction policy is `noeviction` (default), Redis will return OOM errors to clients when memory is full — your application must handle this. Setting `allkeys-lru` is almost always the right choice for a cache: Redis silently evicts the least recently used keys, maintaining a "hot set" in memory. Monitor `evicted_keys/sec` in production — if it's non-zero, you're running hot and should add memory or nodes.

---

**Q9: How would you design a cache warming strategy to avoid cold start after a cache restart?**

A: Cold start (empty cache after restart) causes a thundering herd as all requests miss and hit the DB. Strategies: (1) **Pre-warming script**: before the new cache goes live, run a job that queries the top-N most-accessed DB records (from access logs/analytics) and populates the cache. Run this 5 minutes before the switchover. (2) **Lazy warming**: let the cache self-warm under production traffic, but protect the DB with a circuit breaker that limits concurrency. This is the simplest approach but causes a brief performance degradation. (3) **Canary warm-up**: route 1% of traffic to the new cache instance for 10 minutes before switching all traffic. The 1% warms the most popular keys. (4) **Persistent cache**: Redis with AOF (Append-Only File) persistence can reload its entire dataset on restart — no cold start at all, but takes time to reload and uses disk. Best strategy: combine (1) for the top-1000 critical keys and (2) for the long tail.

---

**Q10: A user's profile is cached across 20 app servers (local in-process cache, TTL 60s). The user changes their name. How do you propagate this change?**

A: Local in-process caches (Caffeine) are invisible to other servers — you cannot reach in and invalidate them remotely. Options: (1) **Accept TTL staleness**: wait up to 60 seconds. If the use case tolerates this (e.g., profile display in a feed), this is the simplest and most operationally reliable choice. (2) **Pub/Sub invalidation**: when the profile is updated, publish an invalidation message to a Redis Pub/Sub channel (`cache.invalidate.user:uuid`). All 20 app servers subscribe and immediately evict that key from their local caches. Latency: < 10ms for invalidation to propagate. Reliability: if a server misses the pub/sub message (network hiccup), it serves stale for up to 60s (TTL fallback). (3) **Short TTL**: reduce TTL to 5 seconds. Staleness is bounded without complex invalidation. Trade-off: more Redis traffic. Recommendation: option 2 for real-time correctness, option 1 for simplicity, option 3 as a middle ground.

---

**Q11: How do you monitor a Redis cluster in production and what do you alert on?**

A: Key metrics and alert thresholds:

| Metric | Alert threshold | Why |
|---|---|---|
| `used_memory` / `maxmemory` | > 80% | Less than 20% headroom → impending eviction or OOM |
| `evicted_keys` (per min) | > 0 | Any eviction = data loss; may indicate memory undersized |
| `keyspace_hits` / total ops | < 70% | Hit rate under 70% = cache not effective |
| `connected_clients` | > 90% of `maxclients` | Connection exhaustion imminent |
| `latency_ms` P99 | > 1ms | Redis getting slow; check slow queries with `SLOWLOG` |
| `replication_lag` | > 100ms | Replica falling behind; data freshness risk |
| `cluster_state` | != `ok` | Cluster health degraded; investigate immediately |
| `aof_rewrite_in_progress` duration | > 5min | AOF rewrite stuck; disk I/O issue |

Use Redis Exporter + Prometheus + Grafana for dashboards. Alert via PagerDuty/OpsGenie for `used_memory > 85%` (page on-call), `cluster_state != ok` (page immediately), and `evicted_keys > 1000/min` (ticket for investigation).

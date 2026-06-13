# Distributed Systems — Exercises & Practice Problems

> Each exercise below maps to a small Java project. Start with the basic case, then follow the scaling path.
> Hints are intentionally sparse so you reason first. Expected interviewer focus is noted per exercise.

---

## Table of Contents

- [Exercise 1 — Key-Value Store](#exercise-1--key-value-store)
  - [Stage 1 — Single Node (Basic)](#stage-1--single-node-basic)
  - [Stage 1 Follow-ups — Depth Drills](#stage-1-follow-ups--depth-drills)
    - [Follow-up 1: Conditional Writes with Versioning](#stage-1b--conditional-writes-with-versioning-etag--optimistic-locking) ✅
    - [Follow-up 2: LRU Eviction Policy](#follow-up-2-lru-eviction-policy) ✅
    - [Follow-up 3: TTL (Time-to-Live) per Key](#follow-up-3-ttl-time-to-live-per-key) ✅
  - [Stage 2 — Persistence (Durability)](#stage-2--persistence-durability)
  - [Stage 3 — Partitioning (Horizontal Scale)](#stage-3--partitioning-horizontal-scale)
  - [Stage 4 — Replication (High Availability)](#stage-4--replication-high-availability)
  - [Stage 5 — LSM-Tree & Compaction (Advanced)](#stage-5--lsm-tree--compaction-advanced)

> **How to read this:**
> Stage 1 → Stage 2 → … are the big architectural leaps (new file per stage).
> Stage 1 Follow-ups are depth drills the interviewer asks before letting you move forward — all implemented in `KeyValueExtendApi.java`.

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

**1. Why not `HashMap`?**

`HashMap` is not thread-safe. Under concurrent writes it can corrupt its own internal structure:
- Java 7: infinite loop during resize (two threads rehash simultaneously, creating a cycle in the linked list)
- Java 8+: data loss (entries silently dropped during concurrent put)

The interviewer expects you to say `ConcurrentHashMap` immediately, without being asked. If you say `HashMap`, you've already failed the concurrency check.

**2. Why `ConcurrentHashMap` and not `Collections.synchronizedMap(new HashMap<>())`?**

| | `ConcurrentHashMap` | `Collections.synchronizedMap` |
|---|---|---|
| Locking | Fine-grained (per bucket/segment) | Single global lock on every operation |
| Concurrent reads | Lock-free | Blocked by any write |
| Throughput | High | Low under contention |
| Atomic ops | `putIfAbsent`, `replace`, `compute` | Only individual method calls |
| Null keys/values | Not allowed | Allowed |

Use `ConcurrentHashMap` as the default for concurrent maps. Use `synchronizedMap` only when you need a feature that `ConcurrentHashMap` doesn't support (e.g., LRU access-order via `LinkedHashMap`).

**3. HTTP status codes — interviewers check this:**
- `PUT` that creates or updates → `204 No Content` (not `200` — there is no response body)
- `GET` found → `200 OK`
- `GET` not found → `404 Not Found`
- Conditional write conflict → `409 Conflict`
- `DELETE` → `204 No Content` (even if key didn't exist — idempotent)

**4. Time complexity:**
- `get` / `put` / `remove` → O(1) average (hash table). Worst case O(n) on hash collision, but Java 8+ converts buckets to balanced trees at threshold 8, capping worst case at O(log n).
- Space: O(n) entries in memory.

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

### Stage 1b — Conditional Writes with Versioning (ETag / Optimistic Locking)

> **Why this question is popular in interviews**
> It tests three things at once: HTTP protocol knowledge (ETag/If-Match), concurrency reasoning (race conditions), and API design (what do you return when the write is rejected?). It's also directly relevant to real systems — S3, DynamoDB, Etcd, and Redis all support conditional writes. Interviewers use it to separate candidates who know ConcurrentHashMap from candidates who understand *why* thread safety is not enough.

**The problem: silent data loss under concurrent writes**

`ConcurrentHashMap.put()` is thread-safe but last-write-wins. Two clients can both read version 3, both modify it, both write — one write is silently lost. No error, no warning. In a config store this is a production bug.

**Real-world examples where this matters:**
- **Config store**: two services both read `timeout=30`, one writes `60`, the other writes `15` — last writer wins silently. The service that wrote `60` has no idea its change was overwritten.
- **Inventory system**: two users both see `stock=1`, both buy — both writes succeed, stock goes to -1.
- **Distributed lock**: two nodes both think they acquired the lock because both read `locked=false` before either wrote `locked=true`.
- **Kubernetes**: resource updates use `resourceVersion` (same concept) — if you send a stale version, the API server rejects with `409 Conflict`.

**The solution: optimistic locking**

Don't lock before writing. Instead, detect the conflict at write time using a version number.

| Operation | Behaviour |
|---|---|
| `GET /keys/{key}` | Returns `{ "value": "...", "version": 3 }` |
| `PUT /keys/{key}` + `If-Match: 3` | Succeeds only if current version is still 3, increments to 4 |
| `PUT /keys/{key}` + `If-Match: 2` (stale) | Returns `409 Conflict` |
| `PUT /keys/{key}` (no `If-Match`) | Unconditional write, still increments version |

**Why `If-Match` as a header, not `?version=3` query param?**

Headers carry metadata about the request (conditions, auth, content type). The version is a condition on whether to accept the write, not part of the resource itself. HTTP spec uses headers for this (`ETag` / `If-Match` pattern).

**Data structure**

```java
record Value(String value, int version) {}
private final Map<String, Value> store = new ConcurrentHashMap<>();
```

**The subtle race condition — read-check-write is not atomic**

This pattern is broken:
```java
Value existing = store.get(key);       // (1) read
if (existing.version != ifMatch) ...   // (2) check
store.put(key, new Value(...));        // (3) write  ← another thread can slip in between (2) and (3)
```

Two threads both pass the version check, both write — back to the original problem.

**Fix: use `ConcurrentHashMap`'s atomic methods**

- New key: `store.putIfAbsent(key, newValue)` — inserts only if absent, returns `null` on success or the existing value if someone beat you.
- Update: `store.replace(key, expectedOldValue, newValue)` — atomically swaps only if the current value `==` the expected object (by `equals()`). Returns `true` if swapped, `false` if someone else changed it first.

**Unconditional write must retry, not 409**

If `replace` returns `false` on an unconditional write, the client didn't ask for a condition — returning `409` is wrong. Instead, spin and retry with the freshest snapshot:

```java
Value current = existing;
while (!store.replace(key, current, new Value(value, current.version + 1))) {
    current = store.get(key);  // re-read; use `current` not `existing` as expected value
}
```

Common mistake: forgetting to update the expected value inside the loop (`existing` stays stale → `replace` never succeeds → infinite loop).

**Final working shape**

```java
record Value(String value, int version) {}

private final Map<String, Value> store = new ConcurrentHashMap<>();

@PutMapping("/keys/{key}")
public ResponseEntity<Void> putKey(@PathVariable String key, @RequestBody String value,
        @RequestHeader(value = "If-Match", required = false) Long versionToUpdate) {

    Value existing = store.putIfAbsent(key, new Value(value, 1));
    if (existing == null) return ResponseEntity.noContent().build(); // new key, done

    if (versionToUpdate == null) {
        // unconditional: retry until we win the CAS race
        Value current = existing;
        while (!store.replace(key, current, new Value(value, current.version + 1))) {
            current = store.get(key);
        }
        return ResponseEntity.noContent().build();
    }

    if (existing.version != versionToUpdate) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build(); // stale If-Match
    }
    if (!store.replace(key, existing, new Value(value, existing.version + 1))) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build(); // lost CAS race
    }
    return ResponseEntity.noContent().build();
}
```

**Key takeaways**
- `ConcurrentHashMap` makes individual operations thread-safe, not multi-step sequences.
- Optimistic locking = detect conflict at write time, not at read time.
- `putIfAbsent` and `replace(key, old, new)` are your atomic primitives.
- Unconditional writes retry; conditional writes return `409` on conflict.

---

### Follow-up 2: LRU Eviction Policy

**The problem: unbounded memory growth**

Without a size limit, the store grows until the JVM runs out of heap and crashes. In production, this is an OOM kill — silent and hard to debug.

**Why LRU?**

LRU (Least Recently Used) evicts the key that was accessed furthest in the past. The intuition: if a key hasn't been used recently, it's unlikely to be needed soon. It's the most common eviction policy because it approximates real access patterns well (temporal locality).

Other policies for comparison:
| Policy | Evicts | When to use |
|---|---|---|
| LRU | Least recently *accessed* | General purpose, good locality |
| LFU | Least *frequently* accessed | When hot keys are accessed repeatedly |
| FIFO | Oldest *inserted* key | Simple, no access tracking needed |
| TTL | Expired keys | When data has a natural lifetime |

**Java implementation: `LinkedHashMap` in access-order mode**

`LinkedHashMap(capacity, loadFactor, accessOrder)`:
- `capacity = 100` — initial bucket count
- `loadFactor = 0.75f` — resize when 75% full (Java default)
- `accessOrder = true` — reorders entries on every `get`/`put` so the tail is always the most recently used

Override `removeEldestEntry` to auto-evict when over capacity:

```java
private final Map<String, Value> store = Collections.synchronizedMap(
    new LinkedHashMap<>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Value> eldest) {
            return size() > 100;
        }
    }
);
```

**Why not `ConcurrentHashMap` for LRU?**

`ConcurrentHashMap` has no access-order tracking — it deliberately dropped it to avoid the synchronization cost. `LinkedHashMap` maintains a doubly-linked list that gets reordered on every `get`. That mutation is not thread-safe, so you must wrap it.

**The thread safety trade-off**

`Collections.synchronizedMap` puts a single global lock on every operation. Under high concurrency, all threads queue up on that one lock — much worse throughput than `ConcurrentHashMap`.

| | `ConcurrentHashMap` | `synchronizedMap(LinkedHashMap)` |
|---|---|---|
| Thread safety | ✅ | ✅ |
| LRU ordering | ❌ | ✅ |
| Concurrency | High (segment locks) | Low (single lock) |

**Interview answer for the trade-off:**
> "I'm aware this is a single global lock and a bottleneck under high concurrency. For production I'd use **Caffeine** — a library that implements lock-free LRU approximation using a ring buffer. But `synchronizedMap(LinkedHashMap)` is the standard Java answer for this question."

**Why `record Value` matters for the CAS retry loop**

`store.replace(key, oldValue, newValue)` compares using `equals()`. Java `record` auto-generates `equals()` based on all fields, so `new Value("x", 3).equals(new Value("x", 3))` is `true`. A plain class without `equals()` override would compare by reference — the CAS would always fail and the retry loop would spin forever.

---

### Follow-up 3: TTL (Time-to-Live) per Key

> Status: **TODO** — implement in a new Java file `KeyValueTtlApi.java`

**The problem: keys that should expire**

Some data has a natural lifetime — a session token valid for 30 minutes, a distributed lock that should release after 10 seconds, a cache entry that goes stale after 5 minutes. Without TTL, you either leak memory or need the client to manually delete keys.

**Real-world examples:**
- Redis: every key supports `EXPIRE <key> <seconds>`
- HTTP cache: `Cache-Control: max-age=300`
- JWT tokens: `exp` claim
- Kubernetes leases: node heartbeats with TTL, if missed → node considered dead

**Two expiration strategies:**

**Lazy expiration (on GET):** check `expiresAt` at read time, return 404 if expired. Simple, no background thread. Downside: expired keys stay in memory until someone reads them — memory leak for write-heavy, read-light patterns.

**Eager expiration (background sweep):** a `ScheduledExecutorService` runs every N seconds, scans all keys, removes expired ones. Memory is reclaimed proactively. Downside: extra thread, sweep cost proportional to store size.

**Production systems (Redis) use both:** lazy check on every access + a background sampler that randomly picks a bucket and sweeps expired keys. This bounds both memory waste and CPU cost.

**API contract:**
```
PUT /keys/{key}?ttl=30    → stores the value, expires in 30 seconds
PUT /keys/{key}           → stores the value with no expiry
GET /keys/{key}           → returns 404 if expired (same as missing)
DELETE /keys/{key}        → removes immediately
```

**Data structure:**
```java
record Value(String value, long expiresAt) {}
// expiresAt = System.currentTimeMillis() + ttlSeconds * 1000
// expiresAt = 0  → no expiry (never expires)
```

**Strategy 1 — Lazy expiration (on GET)**

Check `expiresAt` at read time. If expired, delete and return `404`. Zero background threads, zero CPU when idle.

```java
@GetMapping("/keys/{key}")
public ResponseEntity<String> getKey(@PathVariable String key) {
    Value value = store.get(key);
    if (value != null && value.expiresAt > 0 && value.expiresAt <= System.currentTimeMillis()) {
        store.remove(key);
        value = null;
    }
    return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value.value());
}
```

Critical: the `expiresAt > 0` guard is mandatory — without it, keys with no TTL (`expiresAt = 0`) would immediately expire since `0 <= now` is always true.

**Strategy 2 — Background sweep**

A `@Scheduled` method runs every 60 seconds and bulk-removes all expired entries.

```java
@Scheduled(fixedRate = 60_000)
private void evictExpiredKeys() {
    long now = System.currentTimeMillis();
    store.entrySet().removeIf(e -> e.getValue().expiresAt > 0 && e.getValue().expiresAt <= now);
}
```

Two details:
- Capture `now` once before the sweep — don't call `System.currentTimeMillis()` inside the lambda, as it would use a different timestamp for each entry.
- `removeIf` on a `ConcurrentHashMap`'s `entrySet()` is safe under concurrent reads and writes — `ConcurrentHashMap` supports concurrent modification on its entry set iterator.
- `@Scheduled` requires `@EnableScheduling` on the Spring application class — without it, the method never runs (silent failure, no error).

**Why both strategies together?**

| | Lazy only | Sweep only | Both |
|---|---|---|---|
| Memory leak for unread keys | Yes | No | No |
| CPU when idle | Zero | Periodic | Periodic |
| Expiry precision | Exact on read | ±sweep interval | Best of both |

Redis uses both: lazy check on every access + a background sampler that randomly picks 20 keys per bucket every 100ms and removes expired ones.

**Key takeaways**
- `expiresAt = 0` as sentinel for "no expiry" — avoids `Optional` or null in the record.
- Lazy expiration is sufficient for read-heavy workloads; add the sweep for write-heavy or rarely-read keys.
- `@Scheduled` is Spring's abstraction over `ScheduledExecutorService` — in a non-Spring app you'd wire it manually: `Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::evictExpiredKeys, 60, 60, TimeUnit.SECONDS)`.

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
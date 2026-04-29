# Rate Limiter

## Why This Problem Matters

Every public API needs protection. Without rate limiting, a single misbehaving client — whether a buggy script, an aggressive scraper, or a DDoS attacker — can consume all server resources and degrade service for every other user. Rate limiting is also fundamental to **monetization tiers** (free vs paid plans), **fair usage**, and **abuse prevention**.

This is one of the most common system design interview problems because it touches many real concerns: distributed state, atomicity, latency, and consistency tradeoffs. Understanding it deeply demonstrates that you think beyond "just add Redis".

---

## What Interviewers Actually Want to See

- You know multiple algorithms and can explain the tradeoffs
- You understand that in a distributed system, a naive counter doesn't work
- You think about failure modes: what if Redis is down?
- You think about UX: how do you communicate limits to the client?
- You can differentiate between per-user, per-IP, per-endpoint limits

---

## Requirements

### Functional
- Limit requests per identity (user ID, API key, IP address) per time window
- Support different limits per endpoint, tier, or user group
- Return HTTP 429 (Too Many Requests) when limit exceeded
- Include headers to inform client of remaining quota and retry time
- Support burst tolerance (allow short bursts within limit)

### Non-Functional
- Ultra-low overhead: < 1ms per check (must not slow down every request)
- Highly available: rate limiter failure must not block legitimate traffic
- Consistent in distributed deployments (20 API servers must share state)
- Accurate: no significant over-counting or under-counting
- Configurable: limits must be changeable without redeployment

---

## Capacity Estimation

```
10M active users
Peak: 100,000 requests/sec across all users
Each rate limit check: 1 Redis read + 1 write
Redis throughput: ~200k ops/sec per node
→ Need Redis cluster for sustained 100k checks/sec with headroom
Memory per user: 1 counter key × ~100 bytes = ~1GB for 10M users (trivial)
```

---

## Algorithm Deep Dive

Understanding the algorithms is the heart of this problem. Each solves a different version of "counting requests over time."

### Algorithm 1: Fixed Window Counter

**Concept**: Divide time into fixed windows (e.g., each minute). Count requests in the current window. Reset at window boundary.

```
Window:  |--- 00:00 to 00:59 ---|--- 01:00 to 01:59 ---|
Counter: 0...1...2...99...100   | 0...1...2...
```

**Implementation**:
```java
String key = "rate:" + userId + ":" + (System.currentTimeMillis() / 60000);
long count = redis.incr(key);
if (count == 1) redis.expire(key, 60); // set TTL on first request
return count <= 100;
```

**The Fatal Flaw — Boundary Burst**:
```
Limit: 100 req/min
User sends 100 requests at 00:59 → all allowed (window 1)
User sends 100 requests at 01:00 → all allowed (window 2)
Result: 200 requests in 2 seconds — 2x the intended limit!
```
This is a real problem for abuse scenarios.

---

### Algorithm 2: Sliding Window Log

**Concept**: Store a timestamp for every request in the last N seconds. Count how many exist in the window.

```
Redis Sorted Set: { timestamp: 1714392010 → member: req-uuid }
Window: last 60 seconds
Check: ZCOUNT key (now - 60) now
```

**Implementation**:
```java
long now = System.currentTimeMillis();
long windowStart = now - 60_000;
String key = "rate:log:" + userId;

redis.zremrangeByScore(key, 0, windowStart);  // clean old entries
long count = redis.zcard(key);

if (count < 100) {
    redis.zadd(key, now, UUID.randomUUID().toString());
    redis.expire(key, 60);
    return true; // allowed
}
return false; // rejected
```

**Pros**: Perfectly accurate — no boundary burst.
**Cons**: High memory. Each request stores a log entry. 10M users × 100 requests = 1B sorted set members.

---

### Algorithm 3: Token Bucket

**Concept**: Each user has a bucket with N tokens. Each request consumes 1 token. Tokens are added at rate R per second. Requests fail when bucket is empty.

```
Bucket capacity: 10 tokens
Refill rate: 1 token/second
State: { tokens: 7, last_refill: 1714392010 }

On request:
  elapsed = now - last_refill
  tokens = min(capacity, tokens + elapsed × rate)
  if tokens >= 1: tokens -= 1; allow
  else: reject
```

**Pros**: Allows controlled bursting. Smooth output rate.
**Cons**: Race conditions in distributed env. Need atomic updates (Lua script or Redis module).

---

### Algorithm 4: Sliding Window Counter (Recommended)

**Concept**: Hybrid of fixed window + sliding. Uses two window counters and weights them by overlap.

```
Limit: 100 req/min
Current time: 01:15 (15 seconds into new minute)

prev_window count: 80 (01:00 - 01:59)
curr_window count: 20 (01:00 - 01:15)
overlap ratio: (60 - 15) / 60 = 0.75 (75% of prev window still in our 60s view)

estimated_count = 20 + 80 × 0.75 = 80
→ 80 < 100 → allowed
```

**Why this is better**:
- O(1) memory: just 2 counters per user per window
- Accurate enough: ~0.003% error rate (proven mathematically)
- No boundary burst problem
- No log storage

**Atomic Lua Script** (ensures read-modify-write is atomic on Redis):
```lua
local curr_key = KEYS[1]   -- current window key
local prev_key = KEYS[2]   -- previous window key
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])  -- in seconds
local now = tonumber(ARGV[3])
local curr_window_start = math.floor(now / window) * window
local elapsed = now - curr_window_start
local overlap = 1 - (elapsed / window)

local curr = tonumber(redis.call('GET', curr_key) or 0)
local prev = tonumber(redis.call('GET', prev_key) or 0)

local estimate = curr + (prev * overlap)
if estimate >= limit then
    return 0  -- REJECTED
end

redis.call('INCR', curr_key)
redis.call('EXPIRE', curr_key, window * 2)
return 1  -- ALLOWED
```

---

## High-Level Architecture

```
                    ┌──────────────────────┐
                    │    Client Request     │
                    └──────────┬───────────┘
                               │
                    ┌──────────▼───────────┐
                    │     API Gateway      │
                    │  ┌────────────────┐  │
                    │  │ Rate Limiter   │  │
                    │  │  Middleware    │  │
                    │  └───────┬────────┘  │
                    └──────────┼───────────┘
                               │
              ┌────────────────▼─────────────────┐
              │         Redis Cluster             │
              │  ┌────────┐  ┌────────┐           │
              │  │Shard A │  │Shard B │  ...      │
              │  └────────┘  └────────┘           │
              └──────────────────────────────────┘
                               │
                    ┌──────────▼───────────┐
                    │   Backend Service    │
                    └──────────────────────┘
```

**Design decision: where to enforce?**
- **At API Gateway** (Kong, AWS API GW, Spring Cloud Gateway): centralized, consistent, one place to update rules
- **At each service**: flexible per-service, but duplicated logic, harder to coordinate

**Recommendation**: API Gateway for global limits + per-service for fine-grained internal limits.

---

## What If Redis Goes Down?

This is a critical failure scenario. You have three choices:

| Strategy | Behavior | When to use |
|---|---|---|
| **Fail open** | Let all requests through | When availability > security |
| **Fail closed** | Reject all requests | When protection is critical (financial) |
| **Local fallback** | Use in-process counter (imprecise) | Best balance for most APIs |

**Local fallback implementation**:
```java
try {
    return checkRedisRateLimit(userId);
} catch (RedisException e) {
    log.warn("Redis unavailable, using local fallback limiter");
    return localRateLimiter.check(userId); // Guava RateLimiter or in-memory
}
```
The local limiter is per-server, so it's imprecise across servers — but it's better than letting everything through or blocking everything.

---

## Data Model

```
Redis keys:
  rate:{userId}:{windowTimestamp}         → integer count (sliding window current)
  rate:{userId}:{prevWindowTimestamp}     → integer count (sliding window previous)
  rate:config:{userId}                    → JSON { limit, window } (user-specific limits)

Configuration DB (PostgreSQL):
  CREATE TABLE rate_limit_configs (
    id         UUID PRIMARY KEY,
    identifier VARCHAR(255),   -- user_id, api_key, IP, or 'tier:FREE'
    limit_count INT,
    window_sec  INT,
    endpoint    VARCHAR(255),  -- null = applies to all
    created_at  TIMESTAMP
  );
```

---

## Response Headers

These headers are the standard way to communicate rate limit state to clients:

```http
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1714392060
X-RateLimit-Window: 60

HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1714392060
Retry-After: 23
Content-Type: application/json

{ "error": "rate_limit_exceeded", "message": "100 requests per minute allowed", "retry_after_sec": 23 }
```

---

## Tiered Limits Example

```yaml
rate_limits:
  FREE:
    default: 60/minute
    /api/search: 10/minute

  PRO:
    default: 1000/minute
    /api/search: 100/minute

  ENTERPRISE:
    default: unlimited
```

Implementation: load user tier from JWT claims, look up config from cache.

---

## Edge Cases to Consider

- **Distributed clock skew**: servers may have slightly different times → use Redis server time (`TIME` command) as authoritative source
- **IPv6 /64 blocks**: rate limit by /64 subnet, not individual IPv6 (attackers rotate within their block)
- **Authenticated vs anonymous**: stricter limits for anonymous (IP-based) than authenticated users
- **Retry amplification**: rejected clients retrying aggressively makes the problem worse → exponential backoff guidance in Retry-After header

---

## Tech Stack
- **Rate Limiter**: Redis Cluster (Sliding Window Counter via Lua)
- **Middleware**: Spring Boot OncePerRequestFilter or Spring Cloud Gateway filter
- **Config**: PostgreSQL + Redis cache (TTL 5min)
- **Fallback**: Guava RateLimiter (in-process)
- **Monitoring**: Prometheus counter `rate_limit_rejected_total{user_tier, endpoint}`

---

## Interview Q&A

**Q1: Why not just use a database for counting instead of Redis?**

A: Databases are optimized for durable, relational storage — not for sub-millisecond atomic counters at 100k ops/sec. A typical PostgreSQL `UPDATE counter = counter + 1 WHERE user_id = ?` takes 1-5ms including round-trip and lock contention. Redis `INCR` is ~0.1ms and lock-free. At high throughput, a DB-backed rate limiter would itself become a bottleneck. Redis is specifically designed for this: in-memory, single-threaded (no race conditions), with atomic commands like INCR, and TTL built-in.

---

**Q2: How does the sliding window counter handle the boundary problem vs fixed window?**

A: Fixed window resets abruptly at boundaries, allowing 2× the limit in a short span (end of one window + start of next). Sliding window counter uses a weighted estimate: `current_count + previous_count × (time_remaining_in_prev_window / window_size)`. This smooths the transition — as you move further into the current window, the influence of the previous window decreases proportionally. The result is a near-perfect sliding window with O(1) memory instead of O(requests) like the log approach.

---

**Q3: How do you handle rate limiting in a multi-region deployment?**

A: You have a spectrum of options:
- **Strict global**: single Redis cluster (adds cross-region latency — bad for latency-sensitive apps)
- **Per-region**: each region enforces its own limit (users can exceed global limit by hitting multiple regions)
- **Approximate global**: each region enforces `limit/N` (where N = regions), accepts slight inaccuracy
- **Sticky routing**: route user to same region consistently (via GeoDNS or consistent hashing) — single-region enforcement works

For most applications, per-region limiting with a generous limit is acceptable. For strict financial APIs, per-region with synchronization or a centralized rate limit service.

---

**Q4: What is the difference between rate limiting and throttling?**

A: They're often used interchangeably, but technically:
- **Rate limiting**: hard limit — requests above threshold are rejected with 429
- **Throttling**: soft control — requests are queued/delayed rather than rejected; throughput is controlled but not refused

Rate limiting protects server resources. Throttling ensures a smooth consumption rate. In practice, most systems use rate limiting (reject) for public APIs and internal throttling (queue/delay) for background batch processing.

---

**Q5: How would you implement rate limiting per endpoint with different limits?**

A: Use composite keys that include the endpoint:
```
key = "rate:{userId}:{endpoint_hash}:{window}"
e.g., "rate:user123:hash(/api/search):1714392000"
```
Load per-endpoint configs from a config store (DB backed, Redis cached). On each request, determine which config applies: most specific wins (user+endpoint > user > endpoint > global default).

---

**Q6: A client is retrying aggressively after hitting 429, making the problem worse. How do you handle this?**

A: Several strategies:
1. **Retry-After header**: tell clients exactly when to retry — well-behaved clients respect this
2. **Exponential backoff guidance**: document in API docs that clients should backoff exponentially
3. **Penalty box**: if a client ignores 429 and keeps hitting the API, escalate to longer ban (e.g., first hit = 1min, second = 10min, third = 1hour)
4. **Circuit breaker on client side**: good SDK clients implement circuit breakers

---

**Q7: How do you avoid Redis being a single point of failure for rate limiting?**

A:
1. **Redis Cluster**: data sharded across multiple nodes, failure of one shard only affects keys on that shard
2. **Redis Sentinel**: monitors master, auto-promotes replica on failure (15-30s failover)
3. **Fail-open with local fallback**: if Redis is unavailable, fall back to per-process rate limiting (imprecise but better than blocking all traffic)
4. **Read from replicas**: for read-heavy checks, distribute reads to replicas while writes go to master

---

**Q8: How would you implement rate limiting without Redis (stateless)?**

A: For a single-server deployment or as a fallback, you can use **in-memory structures** like Guava's `RateLimiter` (token bucket) or a `ConcurrentHashMap<String, AtomicLong>` with scheduled cleanup. But this doesn't work across multiple servers. An alternative is **consistent hashing**: route requests by user ID to the same server — that server maintains the counter locally. This is more complex to operate but eliminates the Redis dependency. In practice, Redis is the standard choice.

---

**Q9: What happens to the sliding window estimate accuracy as the window size changes?**

A: The sliding window counter approximation error is bounded by `(1 - overlap_ratio) × prev_count`. Worst case: if a user sent exactly `limit` requests all at the end of the previous window, then 0 requests in current window, and now sends requests at the start of the current window — the system may slightly over-allow. In practice, Cloudflare published research showing the error rate is < 0.003% for real traffic patterns. Smaller windows (e.g., 1 second vs 1 minute) have lower absolute error but require more frequent key creation/expiry.

---

**Q10: How would you design rate limiting for a GraphQL API where each query has different "cost"?**

A: Instead of counting requests (1 per request), implement **cost-based rate limiting**:
1. Assign a cost to each operation type and depth (simple field = 1, list = 10, nested list = 100)
2. Calculate query cost before execution using AST analysis
3. Deduct cost from user's budget (e.g., 1000 cost units/minute)
4. Reject if cost would exceed remaining budget

```
query { users { orders { items { product { reviews } } } } }
cost = 1 × 100 × 10 × 1 × 10 = 10,000  → reject immediately
```
This prevents expensive queries from being used as a DoS vector.

---

**Q11: How do you test a rate limiter?**

A:
- **Unit tests**: test each algorithm in isolation with mocked Redis, verify correct counts at boundaries
- **Integration tests**: real Redis, send N+1 requests and verify the Nth+1 is rejected
- **Boundary tests**: test exactly at window boundaries (the fixed window flaw)
- **Concurrent tests**: send 1000 concurrent requests from same user, verify exactly N are allowed
- **Chaos tests**: kill Redis mid-test, verify fail-open/fail-closed behavior is correct
- **Load tests**: k6 or Gatling sending 100k req/sec, verify < 1ms overhead added by limiter

---

**Q12: What's the difference between rate limiting by IP vs by user ID, and when do you use each?**

A:
- **IP-based**: works for anonymous users, no auth needed. But a single IP can be shared by thousands (corporate NAT, mobile carrier NAT). One misbehaving user behind a NAT blocks everyone.
- **User ID-based**: precise, fair — each authenticated user gets their own limit. Requires authentication.
- **API Key-based**: best for developer/B2B APIs. Allows per-key billing and usage analytics.

**Recommendation**: layered approach:
1. IP-based strict limit for unauthenticated endpoints (prevents scan/abuse without login)
2. User ID-based for authenticated endpoints (fair per-user)
3. API Key-based for programmatic access (B2B, developer portal)

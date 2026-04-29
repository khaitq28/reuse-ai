# URL Shortener

## Why This Problem Matters

URL shorteners seem trivially simple — "just store a mapping from short code to long URL" — but that simplicity is deceptive. The moment you scale to billions of URLs and hundreds of thousands of redirects per second, every design decision has cascading consequences. This problem is a favorite in interviews because:

- It requires reasoning about **read vs write throughput asymmetry** (1000:1 ratio)
- It exposes **ID generation** challenges at scale (uniqueness without coordination)
- It tests **caching strategy** thinking (what to cache, for how long, eviction)
- It raises **301 vs 302** tradeoffs (analytics vs performance)
- It introduces **hash collision** and **custom alias** edge cases

Real-world users: bit.ly processes 10B+ clicks/month. TinyURL has been running since 2002. These are not toy problems.

---

## Requirements

### Functional
- Given a long URL, generate a unique short URL (~7 characters)
- Visiting the short URL redirects to the original long URL
- Optional: user-defined custom alias (e.g., `short.ly/my-brand`)
- Optional: expiry date on short URLs
- Optional: analytics — click count, referrer, device type, geo

### Non-Functional
- Write: 100M URLs/day → 1,200 writes/sec
- Read: 10B redirects/day → 115,000 reads/sec (read-heavy — 10,000:1)
- Redirect latency: < 10ms P99 (users expect instant redirect)
- Data retention: 5 years minimum
- Short URLs must never collide (two long URLs never share the same short code)
- Short codes must not be predictable/enumerable (security)

---

## Capacity Estimation

```
Writes:
  100M URLs/day ÷ 86,400 sec = ~1,200 writes/sec
  Peak: 10× = 12,000 writes/sec

Reads:
  10B redirects/day ÷ 86,400 sec = ~115,000 reads/sec
  Peak: 10× = 1.15M reads/sec

Storage (5 years):
  100M URLs/day × 365 × 5 = 182.5B URL records
  Each record: 500 bytes (URL + metadata)
  Total: 182.5B × 500B = ~91TB

Cache:
  20% of URLs receive 80% of traffic (Pareto principle)
  Hot URLs: 100M × 20% = 20M entries
  20M × 500B = 10GB → fits comfortably in Redis
```

---

## Short Code Generation — The Core Challenge

This is where most candidates go wrong. There are several approaches, each with hidden pitfalls.

### Option A: Hash the Long URL (MD5/SHA256 + Truncate)

```
longUrl = "https://www.example.com/very/long/path?param=value"
hash    = MD5(longUrl) = "5d41402abc4b2a76b9719d911017c592"
shortCode = first 7 chars = "5d41402"
```

**The collision problem**:
When you take only the first 7 chars of a 32-char MD5, the probability of collision rises dramatically (Birthday Problem). With 182B URLs, you will have many collisions. Detection + retry adds complexity. Also, different long URLs can produce the same 7-char prefix.

**Verdict**: Don't use this approach for production.

---

### Option B: Random String Generation

```java
String shortCode = RandomStringUtils.randomAlphanumeric(7); // Base62
// Insert with UNIQUE constraint
// On unique violation → retry with new random code
```

**Problem**: With 182B entries and 62^7 = 3.5T possibilities, collision probability approaches 5% after 100B URLs (Birthday Problem again). Retry logic adds latency. Not deterministic.

**Verdict**: Acceptable for small scale, not for billions of URLs.

---

### Option C: Auto-Increment ID + Base62 Encode (Recommended)

**The insight**: Auto-increment IDs are guaranteed unique by the database. Encode that integer into Base62 to get a short, compact string.

```
Base62 alphabet: 0-9 a-z A-Z (62 characters)
62^7 = 3,521,614,606,208 ≈ 3.5 trillion unique codes

ID 1       → "0000001"
ID 100     → "0000027"
ID 1000000 → "0004c92"
ID 1B      → "15ftgf"  (6 chars)
```

```java
private static final String BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

public String toBase62(long id) {
    if (id == 0) return "0";
    StringBuilder sb = new StringBuilder();
    while (id > 0) {
        sb.insert(0, BASE62.charAt((int)(id % 62)));
        id /= 62;
    }
    // Pad to 7 chars
    while (sb.length() < 7) sb.insert(0, '0');
    return sb.toString();
}

public long fromBase62(String code) {
    long result = 0;
    for (char c : code.toCharArray()) {
        result = result * 62 + BASE62.indexOf(c);
    }
    return result;
}
```

**The problem with a single DB auto-increment**: At 12,000 writes/sec, a single PostgreSQL sequence is a bottleneck and a single point of failure.

---

### Option D: Distributed ID Generation (Snowflake)

For high write throughput, generate IDs without a centralized DB:

```
64-bit Snowflake ID layout:
┌─────────────────────────────────────────────────────────────┐
│ 1 bit │ 41 bits timestamp │ 10 bits machine ID │ 12 bits seq │
└─────────────────────────────────────────────────────────────┘

Timestamp: milliseconds since epoch (gives 69 years of IDs)
Machine ID: assigned to each server (up to 1024 servers)
Sequence: 4096 IDs per millisecond per server
Max throughput: 1024 servers × 4096/ms = 4.19B IDs/sec
```

Then Base62-encode the Snowflake ID to get a short code.

**Or simpler**: use a pre-allocated ID range per server:
```
ID Generator Service assigns ranges:
  Server A: IDs 1–10,000
  Server B: IDs 10,001–20,000
  ...
Each server uses its local range until exhausted, then fetches next range
→ No coordination per ID, just one DB call per 10k IDs
```

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         Write Path                           │
│                                                             │
│  POST /urls  →  Write API  →  ID Generator  →  PostgreSQL  │
│                     │                              │         │
│                     └──→  Redis Cache (warm hot)  ←┘        │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                         Read Path (Redirect)                 │
│                                                             │
│  GET /{code}  →  CDN (CloudFront)                          │
│                      │ cache miss                           │
│                  Redirect API                               │
│                      │                                      │
│                  Redis Cache  ──hit──→  HTTP 302            │
│                      │ miss                                 │
│                  PostgreSQL  →  Redis  →  HTTP 302          │
└─────────────────────────────────────────────────────────────┘
```

---

## Database Design

```sql
CREATE TABLE short_urls (
  id           BIGSERIAL PRIMARY KEY,         -- auto-increment, source of truth for ID
  short_code   VARCHAR(10) UNIQUE NOT NULL,   -- Base62 encoded ID
  long_url     TEXT NOT NULL,
  user_id      UUID,                          -- null for anonymous
  title        VARCHAR(255),                  -- extracted from page <title>
  expires_at   TIMESTAMP,                     -- null = never expires
  click_count  BIGINT DEFAULT 0,
  is_custom    BOOLEAN DEFAULT FALSE,         -- true if user chose the alias
  created_at   TIMESTAMP DEFAULT NOW()
);

-- Critical index: every redirect hits this
CREATE UNIQUE INDEX idx_short_code ON short_urls(short_code);

-- For user dashboard: "show me all my URLs"
CREATE INDEX idx_user_id ON short_urls(user_id, created_at DESC);

-- For cleanup job: find expired URLs
CREATE INDEX idx_expires ON short_urls(expires_at) WHERE expires_at IS NOT NULL;
```

---

## Caching Architecture (The Read Path)

Since 99%+ of traffic is reads (redirects), caching is critical. The strategy must handle the Pareto distribution: 20% of URLs get 80% of traffic.

```
Layer 1: CDN (CloudFront / Akamai)
  Cache the HTTP redirect itself at edge nodes worldwide
  For 301: browser caches → zero server load after first hit
  For 302: CDN caches → still fast, retains analytics accuracy
  TTL: 24 hours for stable URLs, 1 hour for custom/expiring

Layer 2: Redis (application-level)
  Key:   shortcode:{code}      → JSON { long_url, expires_at, active }
  TTL:   24 hours (refresh on access for hot URLs)
  Size:  20M hot URLs × 200B = 4GB → fits on single Redis node

Layer 3: Application in-process (Caffeine)
  Hot URLs cached in JVM heap: top 100k URLs (< 100MB)
  TTL: 60 seconds
  Eliminates Redis round-trip for viral links
```

**Cache hit flow**:
```
Request → CDN hit → 302 redirect (0 backend calls)
Request → CDN miss → App → Caffeine hit → 302 (0 DB/Redis calls)
Request → CDN miss → App → Redis hit → 302 (0 DB calls)
Request → CDN miss → App → Redis miss → DB → cache warm → 302
```

---

## 301 vs 302 Redirect — A Critical Decision

```
301 Permanent Redirect:
  Browser caches the redirect permanently
  Subsequent requests never reach our servers
  Analytics data lost (we never see return visits)
  CDN load: very low after initial caching

302 Temporary Redirect:
  Browser does NOT cache
  Every click hits our servers (or CDN)
  Full analytics: we see every click, referrer, user-agent
  CDN load: higher, but CDN can still cache 302s

Decision matrix:
  Need click analytics     → 302
  Maximum performance      → 301
  Branded/custom links     → 302 (might change destination)
  Deep links in email      → 302 (track open rates)
```

**Recommendation**: Use 302 for all user-facing short links. Use 301 only for system-generated, never-changing, high-volume technical links where analytics don't matter.

---

## Analytics Design

Analytics must not slow down the redirect (which must be < 10ms).

```
Redirect happens → async fire event:
  Kafka topic: url.clicked
  Event: { short_code, long_url, timestamp, ip, user_agent, referrer }

Consumers process asynchronously:
  1. Geo enricher: IP → country/city (MaxMind DB)
  2. Device parser: user_agent → device/browser/OS
  3. ClickHouse writer: append to analytics table (columnar, fast aggregation)
  4. Redis INCR: update click_count for dashboard

Never block redirect for analytics!
```

---

## Custom Alias Handling

```
User requests: POST /urls { long_url: "...", alias: "my-brand" }

Validation:
  1. Length: 3–20 characters
  2. Characters: alphanumeric + hyphen only (no special chars)
  3. Reserved words: blacklist [admin, api, www, login, register, ...]
  4. Availability: SELECT COUNT(*) FROM short_urls WHERE short_code = 'my-brand'
  5. User quota: max 10 custom aliases per free user

On conflict:
  HTTP 409 Conflict: { "error": "alias_taken", "suggestion": "my-brand-2" }
```

---

## URL Validation & Security

```java
public ValidationResult validate(String url) {
    // 1. Parse and normalize URL
    URI uri = URI.create(url);  // throws if malformed

    // 2. Check scheme (only http/https)
    if (!uri.getScheme().matches("https?")) return invalid("only http/https allowed");

    // 3. Block private IPs (SSRF prevention)
    InetAddress addr = InetAddress.getByName(uri.getHost());
    if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()) {
        return invalid("private IP addresses not allowed");
    }

    // 4. Blocklist check (phishing, malware domains)
    if (blocklist.contains(uri.getHost())) return invalid("domain is blocked");

    // 5. Max URL length
    if (url.length() > 2048) return invalid("URL too long");

    return valid();
}
```

---

## Tech Stack
- **Backend**: Java 17, Spring Boot
- **Database**: PostgreSQL (primary), read replicas
- **Cache L2**: Redis Cluster
- **Cache L3**: Caffeine (in-process)
- **CDN**: AWS CloudFront
- **ID Generation**: PostgreSQL BIGSERIAL + Snowflake for multi-DB
- **Analytics**: Kafka → ClickHouse
- **URL Safety**: Google Safe Browsing API / internal blocklist

---

## Interview Q&A

**Q1: Why use Base62 instead of Base64 for encoding?**

A: Base64 uses `+`, `/`, and `=` characters which are special characters in URLs and require percent-encoding. Base62 uses only `[0-9a-zA-Z]` which are all URL-safe without encoding. The slightly smaller alphabet (62 vs 64) means codes are 1-2% longer — completely negligible. URL safety and readability are worth the tradeoff. Some systems also exclude visually ambiguous characters (0/O, 1/l/I) for human-readable links, resulting in Base56 or similar.

---

**Q2: How would you prevent someone from enumerating all shortened URLs by trying sequential codes?**

A: Several mitigations:
1. **Don't use sequential codes directly**: encode a shuffled or salted ID, not the raw increment
2. **Add a random salt before encoding**: `encode(id XOR random_salt)` — sequential IDs produce non-sequential codes
3. **Minimum code length**: 7+ characters makes brute force impractical (62^7 = 3.5T combinations)
4. **Rate limit the redirect endpoint**: max 100 lookups/minute per IP
5. **Private URLs**: support password-protected or authenticated-only URLs for sensitive links
6. **Audit logging**: alert on sequential access patterns

---

**Q3: The database is the bottleneck at 12,000 writes/sec. How do you scale it?**

A: Several strategies:
1. **Pre-allocated ID ranges**: each app server holds a batch of IDs (e.g., 10k). Only needs DB for batch refresh, not every write. Reduces DB writes by 10,000×.
2. **Async writes with queue**: accept write, return short code immediately (computed from pre-allocated ID), persist to DB asynchronously via Kafka. Risk: data loss if worker crashes before DB write.
3. **Sharding**: shard PostgreSQL by user_id or short_code prefix. Adds operational complexity.
4. **Dedicated ID service**: a lightweight service (using Snowflake) generates IDs in memory, DB only stores the mapping. The ID service is horizontally scalable.

---

**Q4: How would you handle URL expiry at scale (182B URLs, clean up expired ones)?**

A: You can't run `DELETE WHERE expires_at < NOW()` on a 182B-row table — that's a full scan.

Better approaches:
1. **Indexed cleanup job**: `CREATE INDEX idx_expires ON short_urls(expires_at) WHERE expires_at IS NOT NULL`. A scheduled job runs `DELETE WHERE expires_at < NOW() LIMIT 1000` every minute — small batches, uses the index.
2. **Redis TTL as first line**: set Redis TTL to match expires_at. Expired URLs fail at cache level without hitting DB.
3. **Soft delete**: set `active = false` on expiry, add to WHERE clause in all queries. Actual DELETE happens in background.
4. **Partition by time**: partition the table by `created_at` month. Dropping an entire partition (DROP PARTITION) is O(1) vs deleting millions of rows.

---

**Q5: How do you handle the same long URL being shortened multiple times?**

A: Two approaches:
1. **No dedup (simple)**: each call to POST /urls creates a new short code, even for the same long URL. Simple, supports different expiry/ownership per shortening. Wastes some storage.
2. **Dedup**: hash the long URL, check if a short code exists for that hash. If yes, return existing code. Saves storage, but loses per-user ownership semantics.

**Recommendation**: No dedup by default. If a user shortens the same URL twice, they get the same code (check by user_id + long_url hash). Different users shorten the same URL? They each get their own code — they want their own analytics and ownership.

---

**Q6: What's your caching strategy for URLs that go viral?**

A: Viral URLs are a specific problem: a single URL suddenly gets millions of hits, all missing cache simultaneously (thundering herd). Solutions:

1. **Lock-based cache population**: only one thread fetches from DB on cache miss; others wait for the result (see: `Cache-Aside with distributed lock`)
2. **In-process L3 cache**: top 100k URLs cached in JVM heap — zero network calls, can serve 1M req/sec from a single JVM
3. **CDN for public URLs**: the CDN absorbs the viral traffic entirely at edge nodes worldwide
4. **Probabilistic early expiration**: slightly randomize TTL so not all instances expire simultaneously

---

**Q7: How would you add analytics without impacting redirect latency?**

A: The redirect must complete in < 10ms. Analytics processing (geo lookup, device parse, DB write) takes much longer. Solution: **fire-and-forget async pipeline**.

```
1. Redirect happens instantly (read from Redis, send 302)
2. In parallel (non-blocking): publish to Kafka topic { code, ip, ua, ts, referrer }
3. Kafka consumers process asynchronously:
   - MaxMind geo lookup (runs in separate JVM)
   - User-agent parser
   - ClickHouse OLAP DB insert (optimized for analytics queries)
   - Redis INCR for real-time click count dashboard
```

The redirect never waits for analytics. Latency impact: near zero (just async Kafka publish).

---

**Q8: Designing a URL shortener for a company where links must remain valid for 10+ years. What changes?**

A: Long-term durability changes many assumptions:
1. **Storage**: 10 years × 100M URLs/day = 365B records → need horizontal DB sharding from day 1
2. **Backup strategy**: daily snapshots + continuous WAL shipping to S3, replicated to 3 regions
3. **Domain continuity**: the short domain (`short.ly`) must never expire or change hands — contractual/legal safeguard
4. **ID space**: 62^7 = 3.5T covers 182B URLs comfortably, but if write rate increases, monitor fill rate
5. **Technology migration**: design storage layer as an abstraction so you can swap DB without invalidating URLs
6. **Regulatory**: some industries require 7-10 year retention of all links for compliance audit

---

**Q9: How would you implement custom domain support? (e.g., `brand.co/abc` instead of `short.ly/abc`)**

A: Each customer registers their domain. The system routes based on the `Host` header:

```
Request: GET /abc HTTP/1.1
Host: brand.co

→ Look up: brand.co → customer_id = 456
→ Look up: short_code "abc" for customer 456 → long URL
→ 302 redirect
```

Implementation:
```sql
CREATE TABLE custom_domains (
  domain      VARCHAR(255) PRIMARY KEY,
  user_id     UUID NOT NULL,
  verified    BOOLEAN DEFAULT FALSE,
  ssl_cert_id VARCHAR(255)  -- Let's Encrypt cert reference
);
```

SSL: use Let's Encrypt with DNS challenge or Cloudflare for Workers with wildcard certs.
Routing: API Gateway or Nginx proxy routes to the same backend, passing Host header.

---

**Q10: If you had to design this from scratch for 10,000x more scale (1 trillion URLs), what changes fundamentally?**

A: At 1 trillion URLs:
1. **Storage**: single PostgreSQL won't work → shard by short_code hash across 1000+ shards or use Cassandra (distributed by design)
2. **ID generation**: move entirely to Snowflake-style distributed IDs — no central DB sequence
3. **Index size**: a B-tree index on 1T rows won't fit in memory → use LSM-tree storage (RocksDB, Cassandra) where indexes are naturally distributed
4. **Cache**: even 1% of URLs = 10B entries — Redis alone won't hold this → tiered caching (hot in Redis, warm in memcached, cold in DB)
5. **Read path**: pure CDN + edge computing (Cloudflare Workers) — most redirects never reach origin servers
6. **Write path**: globally distributed write clusters (DynamoDB Global Tables / CockroachDB)

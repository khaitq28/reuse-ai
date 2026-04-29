# Web Crawler — Deep Design Guide

## Problem Statement

Design a distributed web crawler that systematically browses the internet, fetching and indexing web pages for a search engine — similar to Googlebot. Starting from a set of seed URLs, it discovers new URLs through links, downloads page content, and stores it for indexing.

---

## Why This Problem Matters

A web crawler is the canonical example of a distributed systems problem that is simultaneously simple in concept and deeply complex in implementation. It appears in interviews because it forces you to reason about:

- **Scale that is genuinely staggering**: the web has 50+ billion indexable pages. A crawler must operate at hundreds of pages per second for years to cover it.
- **Politeness as a first-class concern**: Googlebot fetching too aggressively can overwhelm a small website. This is not just etiquette — it's a legal and reputational concern for the crawling company.
- **Deduplication at billion-page scale**: exact URL deduplication, near-duplicate content detection, and redirect handling must all happen efficiently without storing petabytes of lookup data.
- **Distributed coordination without a single coordinator**: no single machine can manage state for 50B URLs. The coordination must itself be distributed.

**What interviewers are testing**: Whether you understand the URL Frontier as a two-level priority+politeness queue, Bloom filters (why they work and what their false positive rate means), SimHash for near-duplicate content, robots.txt compliance as a design constraint, and how to distribute crawl work across 1000 workers without coordination overhead.

---

## Key Insight Before Diving In

**The URL Frontier is not a simple queue — it is a two-level structure that simultaneously handles priority (what to crawl first) and politeness (don't hammer the same server).**

A naive FIFO queue would instantly send 1000 requests to the same domain (wherever the links happened to point), which would get the crawler IP banned and potentially take down small sites. The URL Frontier separates these concerns: the front queues handle priority, the back queues enforce per-domain rate limiting, and a selector ensures crawl-delay between requests to the same domain.

The second insight: **store less, derive more**. You cannot store every URL you've seen in a database — there are too many. A Bloom filter (12GB for 10B URLs) tells you with 99.9% certainty whether you've seen a URL, using only bits in memory. The 0.1% false positive rate (occasionally re-skipping a URL you haven't seen) is acceptable — no system requires 100% coverage of all URLs.

---

## Requirements

### Functional
- Start from seed URLs, discover new URLs via links, crawl recursively
- Respect robots.txt (Disallow rules, Crawl-delay directives)
- Deduplicate URLs (don't crawl the same URL twice)
- Detect and skip near-duplicate content (mirror sites, syndicated content)
- Store raw HTML + metadata for the indexing pipeline
- Re-crawl pages on a freshness schedule (popular pages more frequently)
- Support configurable depth limit, domain allowlist/blocklist

### Non-Functional
- Crawl 400 pages/second → 1B pages/month
- Politeness: minimum 1 request per domain per 10 seconds
- Deduplication: < 0.1% false positive rate at 10B URLs
- Fault tolerance: worker failure loses no URLs (at-least-once crawl)
- Distributed: 1000+ worker nodes without central coordination

---

## Capacity Estimation

```
Throughput target:
  400 pages/sec × 86400 sec/day = 34.5M pages/day
  34.5M × 30 days = 1.03B pages/month ✓

Bandwidth:
  400 pages/sec × 500KB avg = 200MB/sec ingress bandwidth
  → 200MB/sec × 86400 = 17TB/day of raw HTML

Storage:
  17TB/day compressed (LZ4 ~4:1) = 4.25TB/day
  Full web index (30-day rolling): 4.25TB × 30 = 127TB
  → 30-node Cassandra cluster with 5TB per node (3-node replication)

URL Bloom Filter:
  10B unique URLs, 1% false positive rate
  m = -n × ln(p) / (ln2)² = -10B × ln(0.01) / (ln2)² ≈ 95.8B bits ≈ 12GB
  k = -log₂(p) = -log₂(0.01) ≈ 7 hash functions
  → 12GB RAM → fits in Redis (single large node) or in-memory on each worker

DNS cache:
  Every URL requires DNS lookup → bottleneck
  Cache DNS results: TTL=1 hour
  At 400 pages/sec across 1000 workers: ~0.4 DNS lookups/sec/worker
  → 10k DNS servers (one per 100 domains) or public DNS with caching
```

---

## High-Level Architecture

```
                     ┌────────────────────┐
                     │   Seed URLs (DB)   │
                     │   (top 1M websites)│
                     └──────────┬─────────┘
                                │ initial population
                                ▼
┌───────────────────────────────────────────────────────────┐
│                      URL Frontier                          │
│                                                           │
│  Front Queue Layer (Priority Queues):                     │
│  ┌──────────────────────────────────────────────────┐    │
│  │  F1: HIGH priority   (news, recently updated)    │    │
│  │  F2: MEDIUM priority (established websites)      │    │
│  │  F3: LOW priority    (rarely updated, long tail) │    │
│  └──────────────────────────────────────────────────┘    │
│                          │ Router assigns domains         │
│  Back Queue Layer (Per-Domain Queues):                    │
│  ┌─────────────────────────────────────────────────┐     │
│  │  B1: nytimes.com URLs  (crawl-delay: 10s)       │     │
│  │  B2: bbc.co.uk URLs    (crawl-delay: 5s)        │     │
│  │  B3: ...                                         │     │
│  └─────────────────────────────────────────────────┘     │
│                          │ Selector picks available queue │
└──────────────────────────┬────────────────────────────────┘
                           │
         ┌─────────────────┴───────────────────┐
         ▼                 ▼                   ▼
  Fetcher Worker 1  Fetcher Worker 2    Fetcher Worker N
         │                 │                   │
         ▼                 ▼                   ▼
     Content Parser (extract links, metadata, content hash)
         │
         ├─── New URLs → URL Dedup Filter (Bloom Filter) → Frontier
         │
         ├─── Raw HTML → S3 storage
         │
         └─── Metadata → Cassandra + ElasticSearch (for indexing)
```

---

## URL Frontier — The Two-Level Queue in Depth

### Front Queues (Priority Assignment)

When a new URL is discovered, it's assigned a priority:

```java
public FrontQueueType assignPriority(String url) {
    String domain = extractDomain(url);

    // High priority: authoritative news sources, government, Wikipedia
    if (highPriorityDomains.contains(domain)) return F1_HIGH;

    // Medium: established domains with good PageRank equivalent
    if (pageRank(domain) > HIGH_THRESHOLD) return F2_MEDIUM;

    // Check recency: domains with recent content updates
    if (lastModifiedRecently(domain)) return F1_HIGH;

    // Default: long tail
    return F3_LOW;
}
```

Priority queues ensure the crawler's limited bandwidth goes to the most valuable content. The front queue distributor reads from F1 60% of the time, F2 30%, F3 10%.

### Back Queues (Politeness Enforcement)

```
Each back queue: holds URLs for ONE domain
Invariant: only one active request to any domain at a time

Selector logic:
  timer_map: { domain → next_allowed_fetch_time }

  while true:
    for each back queue:
      domain = queue.peek_domain()
      if timer_map[domain] <= NOW():
        url = queue.dequeue()
        dispatch_to_fetcher(url)
        timer_map[domain] = NOW() + crawl_delay(domain)
        break

  crawl_delay(domain):
    robots = robotsCache.get(domain)
    if robots.hasCrawlDelay(): return robots.crawlDelay()
    return DEFAULT_CRAWL_DELAY (10 seconds)
```

This guarantees no domain receives more than 1 request per crawl_delay seconds — regardless of how many URLs are queued for that domain.

---

## URL Deduplication: Bloom Filter

### What a Bloom Filter Is

A Bloom filter is a space-efficient probabilistic data structure. It answers "have I seen this element?" with:
- **False negatives: impossible** (if it says "no", it definitely hasn't been seen)
- **False positives: possible** (if it says "yes", it might be wrong with probability p)

For crawling, a 0.1% false positive rate means 1 in 1000 URLs is incorrectly reported as "already seen" and skipped. This is perfectly acceptable — we'll miss 0.1% of new pages, not a problem for a search engine.

```
Data structure: a bit array of m bits + k hash functions

INSERT("https://example.com"):
  h1 = hash1("https://example.com") % m → set bit[h1] = 1
  h2 = hash2("https://example.com") % m → set bit[h2] = 1
  ...
  h7 = hash7("https://example.com") % m → set bit[h7] = 1

QUERY("https://example.com"):
  check bit[h1], bit[h2], ..., bit[h7]
  if ALL are 1 → "probably seen" (possible false positive)
  if ANY is 0 → "definitely not seen"

Memory: 12GB for 10B URLs at 1% FPR (vs. 800GB for a HashSet)
Throughput: millions of lookups/second (just bit reads)
```

```java
// Using Guava's BloomFilter
BloomFilter<String> seenUrls = BloomFilter.create(
    Funnels.stringFunnel(Charset.forName("UTF-8")),
    10_000_000_000L,  // expected insertions: 10B
    0.001            // desired FPR: 0.1%
);

boolean isNew = !seenUrls.mightContain(url);
if (isNew) {
    seenUrls.put(url);
    frontier.enqueue(url);
}
```

For a distributed crawler, the Bloom filter is shared via Redis (using RedisBloom module) so all 1000 workers share the same filter state.

---

## Content Deduplication: SimHash

Two different URLs may serve identical or near-identical content (mirror sites, syndicated articles, printer-friendly versions). Storing and indexing duplicates wastes resources.

### How SimHash Works

SimHash converts a document into a 64-bit fingerprint. Similar documents have similar fingerprints (differ by few bits). Hamming distance ≤ 3 bits = near-duplicate.

```java
public long simHash(String text) {
    // Step 1: tokenize into n-grams or words
    List<String> tokens = tokenize(text);

    // Step 2: for each token, compute its hash and get 64-bit bit representation
    // weighted by term frequency
    long[] v = new long[64]; // accumulator: +weight if bit=1, -weight if bit=0

    for (String token : tokens) {
        long tokenHash = MurmurHash3.hash64(token);
        long weight = termFrequency(token); // TF score

        for (int i = 0; i < 64; i++) {
            if (((tokenHash >> i) & 1) == 1) {
                v[i] += weight; // bit is 1 → add weight
            } else {
                v[i] -= weight; // bit is 0 → subtract weight
            }
        }
    }

    // Step 3: reduce v to 64-bit fingerprint
    long fingerprint = 0;
    for (int i = 0; i < 64; i++) {
        if (v[i] > 0) fingerprint |= (1L << i); // majority positive → set bit
    }
    return fingerprint;
}

// Near-duplicate detection
boolean isNearDuplicate(long fp1, long fp2) {
    return Long.bitCount(fp1 ^ fp2) <= 3; // Hamming distance ≤ 3 bits
}
```

### SimHash Lookup at Scale

Finding near-duplicates among 10B fingerprints by comparing each pair is O(N²) — impossible. Solution: **divide the 64-bit fingerprint into 4 × 16-bit blocks**. Store fingerprints in 4 hash tables, indexed by each 16-bit block. Two fingerprints differing by ≤ 3 bits must agree on at least one block. This reduces lookup to 4 hash table lookups — O(1).

---

## robots.txt Compliance

```
Every domain must be checked before crawling:
  1. Fetch https://{domain}/robots.txt
  2. Parse rules for User-agent: * (our generic bot) and User-agent: MyCrawler (specific)
  3. Cache in Redis: robots:{domain} → parsed rules, TTL=24 hours

robots.txt example:
  User-agent: *
  Disallow: /admin/
  Disallow: /private/
  Crawl-delay: 10
  Sitemap: https://example.com/sitemap.xml

  User-agent: MyCrawler
  Disallow: /     ← completely blocks our crawler from this domain

Sitemap discovery:
  If robots.txt contains Sitemap: directive → fetch sitemap
  XML sitemap lists all pages with lastmod timestamps
  → prioritize recently modified pages in the frontier
```

```java
public boolean isAllowed(String url, RobotsRules rules) {
    String path = URI.create(url).getPath();
    // Rules are checked in order; last matching rule wins (Google's interpretation)
    for (RobotsRule rule : rules.getRulesForUserAgent("MyCrawler")) {
        if (path.startsWith(rule.getPattern())) {
            return rule.isAllow();
        }
    }
    return true; // no rule matches → allowed by default
}
```

---

## Fetcher Worker

```java
public CrawlResult fetch(String url) throws IOException {
    // 1. Validate URL (scheme check, private IP check — SSRF protection)
    if (!isAllowed(url)) return CrawlResult.blocked(url);

    // 2. Check robots.txt (cached in Redis)
    RobotsRules rules = robotsCache.getOrFetch(extractDomain(url));
    if (!isAllowed(url, rules)) return CrawlResult.robotsBlocked(url);

    // 3. HTTP request with conditional GET (bandwidth savings)
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", "MyCrawler/1.0 (+https://mycrawler.com/about)")
        .timeout(Duration.ofSeconds(30));

    // If we've crawled this URL before, use conditional GET
    String lastModified = crawlHistory.getLastModified(url);
    if (lastModified != null) {
        builder.header("If-Modified-Since", lastModified);
    }

    HttpResponse<String> response = httpClient.send(builder.GET().build(),
        HttpResponse.BodyHandlers.ofString());

    // 304 Not Modified → content unchanged, skip processing
    if (response.statusCode() == 304) return CrawlResult.notModified(url);

    // 4. Respect redirect chain (limit to 5 hops to prevent infinite redirect loops)
    if (response.statusCode() >= 300 && response.statusCode() < 400) {
        String location = response.headers().firstValue("Location").orElse(null);
        if (location != null && redirectDepth < 5) return fetch(resolve(url, location));
        return CrawlResult.error(url, "Too many redirects");
    }

    if (response.statusCode() != 200) return CrawlResult.error(url, response.statusCode() + "");

    // 5. Content deduplication via SimHash
    String html = response.body();
    long contentHash = simHasher.hash(html);
    if (seenContentHashes.mightContain(contentHash)) return CrawlResult.duplicate(url);
    seenContentHashes.put(contentHash);

    // 6. Extract links and add to frontier
    Set<String> outlinks = linkExtractor.extract(url, html);
    outlinks.stream()
        .filter(link -> !seenUrls.mightContain(link))
        .forEach(link -> { seenUrls.put(link); frontier.enqueue(link, priority(link)); });

    return CrawlResult.success(url, html, response.headers(), contentHash);
}
```

---

## Re-crawl Strategy (Freshness)

```
Different pages change at different rates:

Page type               Change rate     Re-crawl interval
News homepage           Every minute    15 minutes
Product pages (Amazon)  Daily           24 hours
Blog articles           Weekly          7 days
Wikipedia articles      Daily           24 hours
Static/archive pages    Yearly          30 days

Adaptive re-crawl:
  Track: how often a page actually changed across last N crawls
  change_rate = changes_detected / total_crawls
  next_crawl_interval = last_interval × (1 + change_rate - 0.5)
  → Page that changed 80% of the time: interval shrinks (crawl more often)
  → Page that changed 10% of the time: interval grows (crawl less often)

Implementation:
  After each crawl, compare new content hash to stored hash
  If different: update stored hash, decrement next_crawl_interval
  If same: increment next_crawl_interval (up to max cap)
  Schedule next crawl: UPDATE pages SET next_crawl_at = NOW() + next_interval WHERE url = ?
```

---

## Data Model

```sql
-- Crawled pages (Cassandra — append-heavy, keyed by URL hash)
CREATE TABLE pages (
  url_hash          BLOB PRIMARY KEY,        -- SHA256(normalized_url)
  url               TEXT,
  domain            TEXT,
  content_hash      BIGINT,                  -- SimHash fingerprint
  raw_html_s3_path  TEXT,                    -- s3://raw-html-bucket/{url_hash}
  status_code       INT,
  content_type      TEXT,
  page_rank_score   DECIMAL(10,6),
  last_crawled_at   TIMESTAMP,
  next_crawl_at     TIMESTAMP,
  etag              TEXT,
  last_modified     TEXT,
  crawl_depth       INT,
  outlink_count     INT
);

-- Domain politeness state (Redis)
-- robots:{domain}              → JSON rules, TTL=24h
-- crawl:next:{domain}          → Unix timestamp of next allowed crawl, TTL=crawl_delay
-- dns:{domain}                 → IP address, TTL=1h

-- URL frontier (Kafka — partitioned by domain hash)
-- Topic: url.frontier
-- Partition key: hash(domain) % 1000
-- → All URLs for same domain go to same partition → ordered crawling per domain
-- Message: { url, priority, discovered_from, discovered_at }
```

---

## Distributed Coordination via Kafka

```
Workers are stateless — all coordination via Kafka:

1. URL frontier: Kafka topic with 1000 partitions
   - Partition key: hash(domain) → all same-domain URLs in same partition
   - Each worker consumes assigned partitions
   - Worker failure: Kafka rebalances partitions automatically

2. Politeness: per-partition delay enforced by consumer
   - Consumer for partition P: before fetching, check last_fetch_time for domain
   - If too soon: put message back (re-publish with delay) or sleep briefly

3. No central coordinator needed:
   - Bloom filter: shared via Redis
   - Domain politeness timers: Redis per domain
   - Worker assignment: Kafka consumer group (built-in)
   - Storage: S3 (raw HTML) + Cassandra (metadata)
```

---

## Tech Stack

- **URL Queue**: Kafka (partitioned by domain for politeness + natural load distribution)
- **Bloom Filter**: Redis with RedisBloom module (shared across all workers)
- **Content Store**: S3 (raw HTML, compressed), Cassandra (metadata, crawl state)
- **HTTP Client**: Java 17 HttpClient (async, virtual threads, connection pooling per domain)
- **HTML Parser**: JSoup (link extraction, metadata parsing)
- **robots.txt**: crawlercommons library (battle-tested, handles malformed files)
- **SimHash**: custom implementation (64-bit, 7 hash functions)
- **Content Type Detection**: Apache Tika (don't rely on Content-Type header alone)
- **Monitoring**: Prometheus (pages/sec, domain queue depths, error rates) + Grafana

---

## Interview Q&A

**Q1: How does a Bloom filter avoid storing 10 billion URLs explicitly, and what is the mathematical basis for its space efficiency?**

A: A Bloom filter represents a set using a bit array of m bits and k hash functions. Inserting an element sets k bits (one per hash function). Querying checks all k bits — if any is 0, the element is definitely absent; if all are 1, it's probably present (rare false positive). The space savings come from the fact that you're storing a probabilistic fingerprint (k bit positions) rather than the element itself. For 10B URLs at 1% false positive rate, the optimal parameters are m ≈ 96B bits (12GB) and k = 7. By contrast, storing 10B URLs as strings in a HashSet requires ~800GB. The 66× space reduction is the core value proposition. The mathematical relationship: false positive probability p ≈ (1 - e^(-kn/m))^k, where n = elements inserted. For p=1% and k=7: m/n ≈ 9.6 bits per element — far less than any string encoding.

---

**Q2: How do you handle relative URLs and URL normalization to prevent crawling the same page twice?**

A: URL normalization converts all forms of the same URL to a canonical form before deduplication: (1) **Resolve relative**: `../about` relative to `https://example.com/blog/post` → `https://example.com/about`; (2) **Lowercase scheme and host**: `HTTP://Example.COM/` → `http://example.com/`; (3) **Default port removal**: `http://example.com:80/` → `http://example.com/`; (4) **Remove fragment**: `https://example.com/page#section2` → `https://example.com/page` (fragments are client-side only); (5) **Normalize path**: `https://example.com/blog/../page` → `https://example.com/page`; (6) **Sort query parameters**: `?b=2&a=1` → `?a=1&b=2`; (7) **Remove tracking parameters**: strip `utm_source`, `fbclid`, `ref` etc. (commonly 50+ known tracking params). After normalization, SHA256 hash is the dedup key.

---

**Q3: A website's robots.txt says "Crawl-delay: 60" but you're targeting 400 pages/sec globally. How do you manage this?**

A: Crawl-delay is per-domain, not global. The 400 pages/sec is across all domains. The URL Frontier back queues ensure exactly one request per domain per crawl-delay period. So: `bbc.co.uk` gets 1 request per 60 seconds. But simultaneously, 1000 other domains each get their 1 request per 10 seconds, achieving the 400 pages/sec global rate from the other domains. The crawl-delay only limits throughput for that specific domain, not the crawler globally. If a domain with a long crawl-delay has many pages to crawl, they simply queue up in its back queue and are processed slowly over days. The back queue for that domain might have 10,000 URLs but only processes 1 per minute = ~7 days to exhaust. This is correct behavior — you're being polite to that domain.

---

**Q4: How do you detect and handle "crawler traps" — infinite URL spaces designed to trap crawlers?**

A: Crawler traps are URLs that generate infinite variations (e.g., a calendar that creates a URL for every date going back to 1970, or a session ID in every URL). Detection: (1) **URL depth limit**: don't crawl URLs more than N links deep from the seed (e.g., depth ≤ 8); (2) **Path cycle detection**: if the URL path contains a repeated segment (e.g., `/a/b/a/b/a/b/`), it's likely a trap; (3) **Query parameter explosion**: if a domain generates millions of unique URLs with varying query parameters (e.g., `?id=1` through `?id=10000000`), cap the number of URL variations per domain; (4) **Domain URL budget**: impose a maximum number of pages per domain (e.g., 1M pages max from any single domain); (5) **Content similarity check**: if the last 100 pages from a domain are near-identical (SimHash distance ≤ 3), flag the domain and reduce its crawl priority.

---

**Q5: How does conditional GET (If-Modified-Since / ETag) save bandwidth during re-crawls?**

A: When we first crawl a page, we store the `Last-Modified` and `ETag` headers from the response. On re-crawl, we send these in the request: `If-Modified-Since: {stored_date}` and `If-None-Match: {stored_etag}`. If the page hasn't changed, the server returns `304 Not Modified` with an empty body — we skip downloading the content entirely. This saves bandwidth on re-crawls. For a news site with 100k articles where 95% haven't changed since last crawl: 95k requests return 304 (just a response header, < 1KB) instead of 200 (full HTML, 50-500KB). Bandwidth savings: 95k × 250KB = 23GB saved per crawl cycle for that one site. At scale, this is crucial for maintaining crawl freshness without proportional bandwidth increases.

---

**Q6: How would you prioritize crawling pages that are likely to rank highly in search results?**

A: PageRank approximation without running full PageRank (which requires crawling the entire web first): (1) **Inlink count**: how many discovered URLs link to this page? More inlinks = higher authority → higher priority. Track in a counter map: `inlinks:{url_hash}` → count. Pages above threshold get HIGH priority. (2) **Domain authority**: established domains (Wikipedia, NYT, BBC) get HIGH priority for all their pages, even before crawling them. Maintain a known high-authority domain list. (3) **Sitemap priority**: sitemaps include `<priority>` and `<lastmod>` tags. Use these as crawl priority signals. (4) **Content freshness signals**: `lastmod` in sitemap, `Last-Modified` HTTP header, `<meta name="last-modified">`. Recently updated pages are more valuable to crawl. (5) **URL structure signals**: `/blog/2024/news/` is likely more valuable than `/archived/2010/old/`.
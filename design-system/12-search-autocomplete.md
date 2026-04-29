# Search Autocomplete (Typeahead) — Deep Design Guide

## Problem Statement

Design a real-time search autocomplete system — as users type each character, return the top 5-10 most relevant suggestions within 100 milliseconds. Used in Google, Amazon, YouTube, and virtually every search interface at scale.

---

## Why This Problem Matters

Autocomplete looks deceptively simple: "just search for prefixes." But at scale, it exposes multiple deep challenges simultaneously:

- **Extreme latency requirement**: 100ms end-to-end includes network round-trips. The actual computation budget is < 10ms. This rules out any approach that involves slow data structures or network calls to slow stores.
- **Trie vs. inverted index**: Most candidates immediately say "use a Trie." But a 100M-term Trie doesn't fit in memory on one machine, can't be distributed easily, and has terrible cache locality. The practical solution is more nuanced.
- **Update frequency vs. serving latency**: Search term frequencies change continuously (trending topics change by the minute). But rebuilding a Trie from scratch takes hours. How do you keep suggestions fresh without sacrificing serving performance?
- **Personalization**: "ap" might suggest "apple" for most users but "applecare" for someone who just bought a Mac. Personalizing at query time without adding latency is a design challenge.

**What interviewers are testing**: Whether you can articulate the Trie optimization (storing top-K at each node — this is the key insight), why Redis Sorted Sets are a practical alternative, how you handle frequency updates without downtime, and how fuzzy matching works (edit distance, n-gram).

---

## Key Insight Before Diving In

**The naive Trie retrieves top-K suggestions by DFS traversal from the prefix node — O(P × N) where P is prefix length and N is subtree size. The optimization is to cache top-K at every internal node — making lookup O(P): just traverse to the prefix node and return the cached list.**

This is the single most important algorithmic insight for this problem. Without it, autocomplete requires an expensive tree traversal for every query. With it, query time is proportional only to prefix length — regardless of how large the dictionary is.

The trade-off: every term insertion/update requires propagating up the tree to update top-K caches at all ancestor nodes. This is O(P × K log K) per update. For a write-heavy dictionary with frequent frequency updates, this is expensive. The practical solution is to rebuild the top-K caches **periodically** (hourly batch job) rather than on every update.

---

## Requirements

### Functional
- Return top 5-10 suggestions for any prefix (e.g., "ap" → ["apple", "application", "apply", ...])
- Suggestions ranked by global popularity (search frequency)
- Optional: personalized suggestions (user's recent search history boosts relevant terms)
- Optional: fuzzy matching (tolerate 1-2 character typos)
- Optional: filter by category (e.g., suggest only product names, not articles)
- Update suggestions as search frequencies change (near real-time trending)

### Non-Functional
- P99 latency: < 100ms end-to-end (including network); < 10ms server-side
- Throughput: 10M autocomplete requests/day (peak: 50k req/sec)
- Dictionary size: 1M unique search terms (common internet-scale target)
- Suggestions update lag: < 1 hour (trending terms appear within 1 hour)

---

## Capacity Estimation

```
Queries:
  500M DAU × 5 searches/day × 5 keystrokes avg = 12.5B autocomplete calls/day
  12.5B ÷ 86400 = ~145k req/sec average
  Peak (3x): ~435k req/sec

This is extremely high — every req/sec must be served from cache.

Trie memory (single node):
  1M unique terms × 26 chars avg × 10 bytes/node = 260MB (fits in memory)
  With top-K caches at each node: 1M nodes × 10 suggestions × 20 bytes = 200MB more
  Total: ~460MB — fits in a single Redis node or JVM heap

Redis Sorted Set approach:
  Index all prefixes for all terms:
    "apple" → prefixes: "a", "ap", "app", "appl", "apple"
    1M terms × 10 avg chars = 10M prefix-term pairs
    10M × 50B avg = 500MB

  With replication: 1.5GB — fits in single Redis node (16-32GB)
  For 50k req/sec: need Redis cluster (1 node handles ~100k simple ops/sec)
```

---

## Data Structure Option 1: Trie with Top-K Cache

### Basic Trie (What Everyone Knows)

```
Terms: "apple" (freq=1M), "application" (freq=800k), "apply" (freq=500k)

              root
               │
               a ─────────────────────────────────────┐
               │                                       │
               p                                       n (ant, any, ...)
               │
               p ← [topK: apple, application, apply, ...]
              /│\
             /  │  \
            l   l   l
            │   │   │
            e   y   i
(apple)        (apply)  │
                        c
                        │
                        a
                        │
                        t
                        │
                        i
                        │
                        o
                        │
                        n (application)
```

Query for "app":
- Traverse: root → a → p → p (3 hops, O(prefix_length))
- **Without cache**: DFS through subtree to find all words, sort by freq — O(subtree_size)
- **With cached top-K**: just read the `topK` field at the "app" node — O(1) after traversal

### Top-K Cache Maintenance

```java
class TrieNode {
    Map<Character, TrieNode> children = new HashMap<>();
    boolean isWord;
    long frequency;

    // The KEY optimization: cache top-K at this node
    // Top-K for prefix = best K suggestions for any query reaching this node
    List<String> topKSuggestions;  // sorted by frequency, max K entries
}

// When inserting/updating term frequency:
void updateFrequency(String term, long newFreq) {
    // Walk the path from root to the term's leaf node
    // At each ancestor node, update topK if this term belongs there
    TrieNode curr = root;
    for (char c : term.toCharArray()) {
        curr = curr.children.get(c);
        // Update this node's topK: insert term if freq > min(topK)
        updateTopK(curr, term, newFreq);
    }
}

void updateTopK(TrieNode node, String term, long freq) {
    // Keep sorted list of size K
    // If term already in list: update freq and re-sort
    // If not in list and freq > minimum: add and remove minimum
    // This is O(K log K) per node update
}
```

### Why Batch Rebuild Instead of Real-Time Update

At 1M terms and 1k prefix depth per term, updating top-K on every frequency change = 1M × 10 nodes × O(K log K) = ~10M operations per second if search trends change continuously. This is expensive for the Trie's write path.

**Solution**: decouple writes from the Trie structure:
1. Track frequencies in a separate counter store (Redis INCR)
2. Every hour: batch job reads top-N terms by frequency → rebuilds entire Trie
3. Atomic hot-swap: old Trie serves traffic while new Trie is built → swap reference

```java
// Batch rebuild (runs every 60 minutes)
TrieNode newTrie = new TrieNode(); // build fresh
List<TermFreq> topTerms = frequencyStore.getTopN(100_000); // top 100k terms from Redis
for (TermFreq tf : topTerms) {
    insertWithFreq(newTrie, tf.term, tf.frequency);
}
// Atomic reference swap — no lock needed, reference update is atomic in Java
trieRef.set(newTrie);
// Old Trie becomes garbage-collected
```

---

## Data Structure Option 2: Redis Sorted Sets (Practical Production Approach)

For many systems, Redis Sorted Sets per prefix are simpler to scale than an in-memory Trie.

### How It Works

```
For each term, index ALL its prefixes:
  "apple" (freq=1,000,000) →
    ZADD prefix:a     1000000 "apple"
    ZADD prefix:ap    1000000 "apple"
    ZADD prefix:app   1000000 "apple"
    ZADD prefix:appl  1000000 "apple"
    ZADD prefix:apple 1000000 "apple"

Query for "app":
  ZREVRANGE prefix:app 0 4   ← top 5 by score (score = frequency)

Result: ["apple", "application", "apply", "appstore", "appointment"]
Time: O(log N + K) ≈ < 1ms

Update frequency (when user searches "apple"):
  ZINCRBY prefix:a     1 "apple"
  ZINCRBY prefix:ap    1 "apple"
  ZINCRBY prefix:app   1 "apple"
  ZINCRBY prefix:appl  1 "apple"
  ZINCRBY prefix:apple 1 "apple"
  → Real-time frequency update, no batch rebuild needed
```

### Memory Analysis

```
1M unique terms × avg 10 chars = 10M (prefix, term) pairs
Each entry in Redis Sorted Set: ~50 bytes
Total: 10M × 50B = 500MB — fits in one Redis node

But we only index TOP-50k terms (long tail < 0.1% of queries):
  50k terms × 10 chars = 500k pairs
  500k × 50B = 25MB — trivially fits
  → Memory is not a problem for practical term counts
```

### Tradeoffs: Trie vs Redis Sorted Set

| | Trie (in-memory) | Redis Sorted Set |
|---|---|---|
| Query latency | 0.01ms (pure memory) | 0.5-1ms (network) |
| Scalability | Limited to one machine | Horizontally scalable |
| Update model | Batch rebuild (hourly) | Real-time ZINCRBY |
| Fuzzy match | Requires extension | Pair with ElasticSearch |
| Personalization | Requires per-user trie | Add user score to global |
| Operational complexity | Complex (hot-swap) | Simple (Redis) |

**Recommendation**: Redis Sorted Sets for most production systems. In-memory Trie as L1 cache on top of Redis for ultra-low latency (sub-millisecond).

---

## System Architecture

```
User types "appl"
     │
     ▼
Browser local cache
  (last 100 prefixes, 30s TTL in localStorage)
  Hit? → return immediately (< 1ms, zero network)
     │ Miss
     ▼
API Gateway → Autocomplete Service
                   │
              ┌────▼──────────────────────────────┐
              │    L1: In-Process Cache (Caffeine) │
              │    Top 10k prefixes in JVM heap    │
              │    TTL: 5 seconds                  │
              └────┬──────────────────────────────┘
                   │ Miss
              ┌────▼──────────────────────────────┐
              │    L2: Redis Sorted Sets           │
              │    All indexed prefixes            │
              │    TTL: N/A (persistent)           │
              └────┬──────────────────────────────┘
                   │ Miss (new prefix never queried)
              ┌────▼──────────────────────────────┐
              │    L3: Trie (in-memory, primary)  │
              │    Rebuilt hourly from top-100k   │
              │    terms by frequency              │
              └────────────────────────────────────┘
                   │
              ← return suggestions, populate L2 and L1 caches
```

---

## Fuzzy Matching (Typo Tolerance)

For "aplee" (typo for "apple"), exact prefix matching fails. Options:

### Option A: Edit Distance (Levenshtein)

```
Edit distance = minimum insertions, deletions, substitutions to convert one string to another
"aplee" → "apple": 1 transposition = distance 1

BK-Tree: spatial index for edit distance
  Allows "find all words within edit_distance ≤ 2" efficiently
  Time: O(D^k × log N) where D = alphabet size, k = max distance

For autocomplete: too slow at prefix query time.
Better used for spell correction (post-search, not during typing).
```

### Option B: N-gram Index (Practical)

```
Index each term as overlapping trigrams:
  "apple" → ["app", "ppl", "ple", "^ap", "le$"]  (with boundary markers)
  Query "aplee" → ["apl", "ple", "lee", "^ap", "ee$"]
  Shared trigrams: ["ple", "^ap"] → apple is a candidate

Score by Jaccard similarity:
  similarity = |shared_trigrams| / |union_trigrams|
  Higher similarity = better match

Fast lookup: Redis Set per trigram containing all terms with that trigram
  SMEMBERS trigram:app → {apple, application, approve, ...}
  SMEMBERS trigram:ple → {apple, people, ...}
  SINTER trigram:app trigram:ple → candidates
```

### Option C: ElasticSearch Fuzzy (Recommended for Full Fuzzy)

```
GET /terms/_search
{
  "suggest": {
    "term-suggest": {
      "prefix": "aplee",
      "completion": {
        "field": "suggest",
        "fuzzy": {
          "fuzziness": 1
        }
      }
    }
  }
}
```

ElasticSearch completion suggester with fuzzy is purpose-built for this. Add as a secondary path: exact prefix → ElasticSearch (Redis), fuzzy prefix → ElasticSearch fuzzy.

---

## Frequency Tracking Pipeline

```
User submits full search query:
  POST /search?q=apple

Event published to Kafka: search.submitted
  { query: "apple", user_id: "...", timestamp: ... }

Stream Processor (Apache Flink / Kafka Streams):
  Tumbling 1-hour windows:
    GROUP BY query
    COUNT submissions
    Emit: { query: "apple", count_last_hour: 45000 }

Aggregated results → Frequency Store (Redis sorted set):
  ZADD terms:global 45000 "apple"

Hourly job:
  Fetch top-100k terms: ZREVRANGE terms:global 0 99999 WITHSCORES
  → Rebuild Trie with updated frequencies
  → Update Redis prefix sorted sets with new scores
```

---

## Personalization

User's personal search history provides a personalization boost:

```
Redis per user:
  Key: user:{userId}:searches
  Type: Sorted Set (score=timestamp, member=query)
  TTL: 90 days
  Max size: 200 entries

  ZADD user:xyz:searches {now} "apple"
  ZREMRANGEBYRANK user:xyz:searches 0 -201  ← keep last 200

At query time:
  global_results = redis.ZREVRANGE(prefix:app, 0, 9)
  user_history = redis.ZRANGEBYSCORE(user:xyz:searches, -inf, +inf)

  personal_score(term) = count of times user searched for term (or its prefix)

  final_score(term) = 0.7 × global_frequency + 0.3 × personal_score
  → Re-rank global results by final_score

This adds one Redis call (user history fetch) and an in-memory merge/re-rank.
Total overhead: < 1ms
```

---

## API Design

```
GET /autocomplete?q=app&limit=5&user_id=xyz&category=products
Headers: Accept: application/json

Response 200 (< 100ms SLA):
{
  "query": "app",
  "suggestions": [
    { "text": "apple",       "frequency": 1000000, "category": "general" },
    { "text": "application", "frequency": 800000,  "category": "general" },
    { "text": "apple iphone","frequency": 700000,  "category": "products" },
    { "text": "apply",       "frequency": 500000,  "category": "general" },
    { "text": "appointment", "frequency": 300000,  "category": "general" }
  ],
  "personalized": true,
  "latency_ms": 8
}
```

---

## Tech Stack

- **Trie**: Java in-memory (HashMap + List, ~460MB, rebuilt hourly)
- **Prefix Store**: Redis Sorted Sets (`ZREVRANGE` for prefix queries)
- **L1 Cache**: Caffeine (JVM heap, top 10k prefixes, TTL 5s)
- **L2 Cache**: Redis (all indexed prefixes, sub-ms latency)
- **Fuzzy Search**: ElasticSearch (completion suggester with fuzziness)
- **Frequency Tracking**: Kafka → Flink → Redis Sorted Set
- **Personalization**: Redis per-user sorted set (search history)
- **API**: Java 17, Spring Boot (reactive WebFlux for minimal thread overhead)

---

## Interview Q&A

**Q1: Why store top-K results at each Trie node rather than performing DFS traversal on each query?**

A: DFS traversal visits every node in the subtree below the prefix node to find the best K results. For a common prefix like "a" in a 1M-term dictionary, the subtree contains hundreds of thousands of nodes. Even at 100ns per node, that's tens of milliseconds — far beyond the 10ms budget. By caching the top-K at each node, query time is O(prefix_length): traverse P nodes to reach the prefix, read the cached list — constant time regardless of subtree size. The trade-off is write complexity: every insertion or frequency update must propagate up the path, updating top-K at each ancestor. This is why bulk updates (hourly batch rebuild) are preferred over real-time updates — rebuilding the Trie once an hour avoids continuous write amplification.

---

**Q2: How does the browser-side caching help, and why is it important at the scale of 50k req/sec?**

A: At 50k autocomplete requests/sec, each character typed generates a request. If a user types "apple" (5 chars), that's 5 requests. But many users type at similar speeds and query similar prefixes. Browser localStorage caching (or in-memory JS cache) means: if the user typed "app" and got results, typing backspace and typing "app" again returns the cached result instantly — zero server call. More importantly, if 10,000 users are all typing "apple" at the same time, each user's browser caches the result for "a", "ap", "app", "appl", "apple" — their subsequent keystrokes hit cache, not the server. This can reduce actual server-side QPS by 40-60% for typical usage patterns.

---

**Q3: How do you handle a search term that goes viral and needs to appear in suggestions within minutes, not hours?**

A: The hourly batch rebuild has up to 60 minutes of lag. For viral/trending terms, use a **real-time frequency overlay**:

1. Stream processor emits trending terms (threshold: 10x normal frequency in last 5 minutes)
2. For trending terms: immediately update the Redis Sorted Set: `ZINCRBY prefix:{each_prefix} {boost} "{term}"`
3. This bypasses the hourly batch and makes the term appear in suggestions within seconds

The trending overlay coexists with the batch-built Trie: the Trie serves as the base layer (stable top-100k), and Redis Sorted Sets provide real-time updates. At query time, merge both: take union of Trie results and Redis results, re-rank by combined score.

---

**Q4: The autocomplete must support multiple languages (French, English, Arabic). How does the architecture change?**

A: Multiple languages require: (1) **Separate data structures per language**: a Trie/Redis sorted set for French terms, one for English, one for Arabic. Query language detection from browser `Accept-Language` header or user preference. (2) **RTL (Right-to-Left) languages**: Arabic is right-to-left — the character sequencing for prefix matching is still left-to-right in memory, but the display order is reversed. The algorithm doesn't change; only the UI rendering changes. (3) **Unicode normalization**: French "café" and "cafe" should match. Normalize to NFC/NFD before indexing. (4) **Language-specific tokenization**: Chinese/Japanese don't have word spaces — different tokenization strategy needed. (5) **Memory**: 5 languages × 460MB per Trie = 2.3GB — still fits in memory on a modern server. With Redis: multiple key namespaces per language.

---

**Q5: How do you prevent malicious or inappropriate terms from appearing in suggestions?**

A: Multiple layers: (1) **Blocklist filter**: a set of prohibited terms (profanity, slurs, brand names in certain contexts) that are checked against every suggestion before returning. Implemented as a Redis Set: `SISMEMBER blocklist {term}`. O(1) check per suggestion. (2) **Frequency threshold**: only index terms with frequency > N (e.g., 100 searches/day). Random abusive one-off searches never appear. (3) **Safe search toggle**: apply a secondary blocklist for explicit content when `safe_search=true`. (4) **Reactive removal**: ops team can immediately `ZREM prefix:{all_prefixes} {term}` to remove a specific term from Redis suggestions. The Trie is eventually rebuilt without it. (5) **ML classifier**: score each candidate term for harm/safety before including it in the index. Run offline during batch rebuild.

---

**Q6: What is the difference between an autocomplete system and a full-text search system? Can you use the same infrastructure?**

A: Autocomplete (prefix matching + ranking by popularity) and full-text search (relevance matching, TF-IDF, BM25) have fundamentally different requirements. Autocomplete must be < 10ms — it runs on every keystroke. Full-text search can be 100-500ms — it runs on a full query submission. Autocomplete ranks by popularity (what did most people search for?). Full-text search ranks by relevance (how well does the document match the query?). You can use ElasticSearch for both, but with different query types: completion suggester for autocomplete (exact prefix, fast), query string/BM25 for full-text (exact relevance, slower). For high-scale autocomplete, ElasticSearch alone is often too slow; Redis Sorted Sets or in-memory Trie are used instead, with ElasticSearch only for the fuzzy fallback path.

---

**Q7: How do you test the latency of the autocomplete system under load?**

A: Performance testing approach: (1) **Baseline latency**: run the service alone with a single thread, measure 1000 queries — this is the minimum achievable latency. Target: < 5ms. (2) **Load test**: use k6 or Gatling to generate 50k req/sec against the service. Measure: P50/P95/P99 latency, error rate, Redis CPU, JVM GC pauses. (3) **Cache effectiveness**: instrument cache hit/miss rates at each layer (Caffeine, Redis). Target: L1 hit rate > 50%, L2 hit rate > 90%. (4) **Cold start**: restart the service, measure how quickly it warms up. (5) **Rebuild latency**: measure how long the hourly Trie rebuild takes, and whether it causes GC pauses that affect serving latency (use off-heap storage or double-buffering). (6) **Realistic query distribution**: use actual search logs to generate realistic prefix distributions — popular prefixes receive 100x more traffic than rare ones. A uniform random test doesn't reveal hot-prefix bottlenecks.

# News Feed System

## Problem Statement
Design a news feed system like Twitter/LinkedIn — where users see a ranked, personalized feed of posts from people they follow, updated in near real-time.

---

## Why This Problem Matters

The news feed problem is one of the most common system design interview questions because it forces you to think through a genuinely hard distributed systems tradeoff: **write amplification vs. read amplification**. Every major social platform — Twitter, Instagram, Facebook, LinkedIn — has had to solve this exact tension, and each made different choices based on their scale and user patterns.

What interviewers are really testing:
- Do you understand the difference between **fan-out on write** (push) vs **fan-out on read** (pull) and *why* each approach breaks down at scale?
- Can you identify the **celebrity/whale problem** and propose the hybrid solution without prompting?
- Do you understand why a pre-computed feed in Redis sorted sets beats on-demand database queries by orders of magnitude?
- Can you reason about **feed consistency vs. latency** tradeoffs — when is stale data acceptable?
- Do you understand **cursor-based pagination** and why offset-based pagination breaks at scale?
- Can you discuss how ranking algorithms interact with the caching strategy?

The naive answer ("just query all posts from everyone you follow, sorted by time") fails at any real scale, and a good candidate recognizes this immediately and proposes the pre-computed feed architecture.

---

## Key Insight Before Diving In

**The fundamental insight is: reads vastly outnumber writes in a social network, so it makes economic sense to do expensive computation at write time to make reads cheap.**

A user posts once. That post gets read potentially millions of times. If you do the work to compute each reader's feed at read time, you pay the cost millions of times. If you pre-compute (push the post ID into each follower's feed at write time), you pay once — even if that one payment is expensive (fanout to 10k followers), it's still far cheaper than 10k expensive reads.

The counter-intuition is the celebrity problem: for users with 10M followers, write-time fanout becomes catastrophically expensive (10M Redis writes per post). This is why the solution is hybrid — push for normal users, pull for celebrities — and merging the two at read time.

---

## Requirements

### Functional
- User posts content (text, image, video)
- User follows/unfollows other users
- Home feed: see posts from followed users, reverse-chronological or ranked
- Like, comment, repost/share
- Trending topics / hashtags
- Notification on new likes/comments

### Non-Functional
- 500M daily active users
- Feed load < 200ms end-to-end
- Post write propagation < 5 seconds to followers
- Handle celebrities (users with 100M+ followers)
- Feed must be paginated with stable cursors (no duplicate/missing posts on scroll)

---

## Capacity Estimation

```
Posts written:
  500M DAU × 2 posts/day = 1B posts/day
  1B / 86400 = ~12,000 writes/sec
  Peak (2x): ~24,000 writes/sec

Feed reads:
  500M DAU × 20 feed loads/day = 10B reads/day
  10B / 86400 = ~115,000 reads/sec
  Peak (3x): ~350,000 reads/sec

Read:Write ratio ≈ 10:1 → design must be heavily read-optimized

Storage:
  Posts: 1B/day × 1KB avg = 1TB/day raw
  With 3x replication = 3TB/day
  7-year retention = ~7.5PB (compressed, tiered storage)

Feed store (Redis):
  500M users × 1000 post IDs × 8 bytes = 4TB
  → Requires Redis cluster, not a single node
  → Sharded by user_id

Media:
  20% posts have media
  200M media items/day × 2MB avg = 400TB/day → S3 with CDN
```

The 10:1 read/write ratio is the architectural north star: **every design decision should optimize for read performance, even at significant write cost.**

---

## High-Level Architecture

```
Client (Mobile / Web)
       │
       ▼
  API Gateway (rate limiting, auth, routing)
       │
       ├─── Post Service ──────────────► PostgreSQL (posts metadata)
       │         │                       S3 (media files)
       │         │ publishes event
       │         ▼
       │    Kafka: posts.created
       │         │
       │         ▼
       │    Fanout Service (async consumer)
       │         │
       │         ├─► Redis Sorted Sets (pre-built feeds)
       │         └─► Push Notification Service
       │
       ├─── Feed Service ──────────────► Redis (pre-built feeds, O(1) read)
       │         │                       Cassandra (post content lookup)
       │         └── Celebrity Pull ──► Cassandra (celebrity's posts)
       │
       ├─── User/Follow Service ───────► PostgreSQL (follow graph)
       │         │                       Redis Cache (follower lists)
       │
       ├─── Timeline Service ──────────► Cassandra (user_posts table)
       │
       └─── Social Graph Service ──────► Graph DB / PostgreSQL
```

The key architectural principle: **the critical read path (Feed Service → Redis) never touches the main PostgreSQL database**. PostgreSQL is only hit during writes and cache misses.

---

## Feed Generation: Push vs Pull — The Deep Tradeoff

### Option 1: Pull (Fan-out on Read)

```
When a user loads their feed:
  1. Fetch followee list: SELECT followee_id FROM follows WHERE follower_id = ?
     → If user follows 500 people: 500 followee IDs

  2. For each followee, fetch recent posts:
     SELECT * FROM posts WHERE user_id = followee_id
       ORDER BY created_at DESC LIMIT 20
     → 500 sequential DB queries, or one IN() query that's catastrophic

  3. Merge all results in memory, sort by timestamp, paginate
     → Merge sort of 500 × 20 = 10,000 posts in-flight

  4. For each post, fetch engagement metrics (likes, comments)
     → This is the N+1 query problem: 20 posts × 2 queries = 40 more queries
```

**Why Pull fails at scale:**
- A user following 1000 people triggers 1000+ DB queries per feed load
- With 500M users loading feeds 20x/day, that's 10 trillion DB queries/day — impossible
- Even with caching, the cache miss path is unacceptably slow (> 2 seconds)
- The database becomes the bottleneck for every read operation
- Sorting 10,000 posts in memory on every request wastes CPU

**When Pull works:**
- Small scale (< 1M users) where DB can handle the load
- Users who follow very few people (< 50)
- When feed freshness is critical and you cannot tolerate any stale data
- Internal admin dashboards, analytics feeds

```java
// Pull implementation — simple but doesn't scale
public List<Post> getFeedPull(UUID userId, int limit) {
    // Step 1: Get followee IDs
    // This is a DB call already — and this list can be 1000+ entries
    List<UUID> followeeIds = followRepository.getFolloweeIds(userId);

    // Step 2: This generates a massive IN() query — DB killer
    // SELECT * FROM posts WHERE user_id IN (?, ?, ... x1000)
    // ORDER BY created_at DESC LIMIT 20
    // Problem: DB must scan ALL recent posts for ALL 1000 followees
    return postRepository.findRecentPostsByUsers(followeeIds, limit);
    // This gets slow around 50+ followees and breaks at 500+
}
```

---

### Option 2: Push (Fan-out on Write) — Recommended for Regular Users

```
When User A (100 followers) posts:
  1. Post written to DB → Kafka event published
  2. Fanout Service consumes event:
     a. Load followers: [follower1, follower2, ..., follower100]
     b. For each follower, push post_id into their Redis feed:
        ZADD feed:follower1 <timestamp> <post_id>
        ZADD feed:follower2 <timestamp> <post_id>
        ... (100 Redis operations, batched in pipeline)
  3. Each follower's feed is now pre-computed

When Follower B loads feed:
  ZREVRANGE feed:B 0 19  → 20 post IDs, returned in microseconds
  Batch fetch post content: GET posts:{id} from Redis/Cassandra
  → Total latency: < 10ms
```

**Why Push wins on reads:**
- Feed read = single Redis sorted set range query = O(log N) ≈ microseconds
- No joins, no sorting at read time, no database involvement
- 115,000 feed reads/sec is trivially handled by Redis cluster
- Followers always see fresh data (< 5s propagation via async fanout)

**The write amplification problem:**
- User with 10,000 followers → 10,000 Redis writes per post
- User with 1,000,000 followers → 1,000,000 Redis writes per post
- At 12,000 posts/sec, with average 500 followers each: 6,000,000 Redis writes/sec
- This is manageable (Redis handles millions of ops/sec) for normal users
- But a celebrity with 100M followers posting once = 100M writes in < 5 seconds — impossible

```java
// Push fanout implementation with pipeline optimization
@KafkaListener(topics = "posts.created", groupId = "fanout-service")
public void onPostCreated(PostCreatedEvent event) {
    long score = event.getTimestamp(); // Unix ms as score for ordering
    String postId = event.getPostId().toString();

    // Load followers — this might be paginated for large followee counts
    // We deliberately SKIP this for celebrities (handled at read time)
    List<UUID> followers = getFollowersForFush(event.getUserId());

    // Batch into groups of 500 to avoid Redis pipeline timeouts
    // Each pipeline sends 500 ZADD commands in one network round trip
    List<List<UUID>> batches = Lists.partition(followers, 500);

    for (List<UUID> batch : batches) {
        // Redis pipeline: send all commands in one TCP packet, get all responses at once
        // Without pipelining: 500 round trips × 1ms = 500ms
        // With pipelining: 1 round trip ≈ 2-5ms for 500 commands
        redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
            for (UUID followerId : batch) {
                byte[] feedKey = ("feed:" + followerId).getBytes();
                // ZADD feed:{followerId} {timestamp} {postId}
                // score = timestamp ensures reverse-chronological order via ZREVRANGE
                conn.zAdd(feedKey, score, postId.getBytes());

                // Keep only the latest 1000 posts per user feed
                // ZREMRANGEBYRANK removes lowest-scored (oldest) entries beyond 1000
                // This prevents unbounded memory growth in Redis
                conn.zRemRangeByRank(feedKey, 0, -1001);
            }
            return null;
        });
    }
}

private List<UUID> getFollowersForFush(UUID userId) {
    // Key design decision: skip fanout for celebrities
    // "Celebrity" threshold: typically 10k-100k followers depending on scale
    long followerCount = followRepository.getFollowerCount(userId);
    if (followerCount > CELEBRITY_THRESHOLD) {
        // Return empty list — celebrity posts are pulled at read time
        return Collections.emptyList();
    }
    return followRepository.getFollowerIds(userId);
}
```

---

### Option 3: Hybrid Approach — The Production Solution

The hybrid approach is what Twitter, Instagram, and most large-scale social networks use. The key insight is that the push and pull strategies fail at opposite ends of the follower count distribution:

- Push fails for high-follower users (write amplification)
- Pull fails for users following many high-follower users (read amplification)
- The hybrid merges pre-built push feeds with just-in-time celebrity posts

```
Thresholds (tune based on your system's Redis write capacity):
  Regular users (< 10k followers): Pure push fanout
  Semi-celebrities (10k - 1M followers): Push fanout with backpressure
  Celebrities (> 1M followers): Skip fanout entirely, pull on read

At read time, merge the two streams:
  1. Read pre-built push feed from Redis: ZREVRANGE feed:{userId} 0 99
  2. Identify celebrities this user follows
  3. For each celebrity, pull their recent posts: SELECT ... WHERE user_id = celebrity_id
  4. Merge the two lists (merge-sort by timestamp)
  5. Return top 20, store cursor for pagination
```

```java
public FeedResult getFeed(UUID userId, String cursor, int limit) {
    // Step 1: Load pre-built feed from Redis
    // This is always fast — O(log N) Redis operation
    List<String> pushedPostIds = loadPushedFeed(userId, cursor, limit + 50);
    // Load extra to have room after merging with celebrity posts

    // Step 2: Identify which celebrities this user follows
    // This list is small (users follow few celebrities) and cached in Redis
    List<UUID> followedCelebrities = getCelebrityFollowees(userId);

    List<Post> mergedFeed;
    if (followedCelebrities.isEmpty()) {
        // Fast path: no celebrities followed, use pre-built feed directly
        mergedFeed = enrichPostIds(pushedPostIds);
    } else {
        // Slow-ish path: pull celebrity posts and merge
        List<Post> pushedPosts = enrichPostIds(pushedPostIds);
        List<Post> celebrityPosts = pullCelebrityPosts(followedCelebrities, cursor);

        // Merge sort by score (timestamp or ranking score)
        // O((N+M) log(N+M)) where N, M are small (< 200 each)
        mergedFeed = mergeSortedByScore(pushedPosts, celebrityPosts);
    }

    // Apply ranking if not pure chronological
    List<Post> rankedFeed = rankFeed(mergedFeed, userId);

    // Paginate: take top 'limit' posts
    List<Post> page = rankedFeed.subList(0, Math.min(limit, rankedFeed.size()));

    // Generate cursor from last post in page for stable pagination
    String nextCursor = page.isEmpty() ? null
        : encodeCursor(page.get(page.size() - 1));

    return new FeedResult(page, nextCursor);
}
```

**Why this is elegant:**
- Regular users get the fast path (pure push) 99% of the time
- Celebrity followers see a slightly slower path but still < 50ms total
- Celebrity posts are not stored redundantly in millions of feeds
- The system degrades gracefully: even if the celebrity pull times out, the pushed feed still loads

---

## Data Model

### posts (Cassandra — time-series optimized)

**Why Cassandra over PostgreSQL for posts?**
- Posts are written once, read many times — Cassandra's LSM tree excels at write-heavy workloads
- Posts are naturally time-series data: partition by user_id, cluster by time gives efficient range scans
- Cassandra handles large volumes without expensive B-tree rebalancing
- No joins needed: read patterns are simple key lookups

```sql
-- Primary posts table: look up a post by ID (from feed post IDs)
CREATE TABLE posts (
  post_id     TIMEUUID PRIMARY KEY,  -- TIMEUUID encodes timestamp, no separate created_at needed
  user_id     UUID,
  content     TEXT,
  media_urls  LIST<TEXT>,
  like_count  COUNTER,
  comment_count COUNTER,
  repost_count  COUNTER,
  hashtags    SET<TEXT>,
  created_at  TIMESTAMP
);

-- User's own timeline (profile page: "see all posts by user X")
-- Separate table because Cassandra models are query-driven
CREATE TABLE user_posts (
  user_id     UUID,
  post_id     TIMEUUID,
  content_preview TEXT,  -- denormalized for profile list — avoids extra lookup
  PRIMARY KEY (user_id, post_id)
) WITH CLUSTERING ORDER BY (post_id DESC);
-- post_id is TIMEUUID which sorts by time, so DESC = newest first

-- Efficient lookup for celebrity pull (recent posts by user in time range)
-- Used at feed-read time for celebrity posts
-- Same as user_posts but separate for clarity and different TTL policies
CREATE TABLE celebrity_posts (
  user_id     UUID,
  post_id     TIMEUUID,
  PRIMARY KEY (user_id, post_id)
) WITH CLUSTERING ORDER BY (post_id DESC)
  AND default_time_to_live = 604800;  -- auto-expire after 7 days (feeds only care about recent)
```

### follows (PostgreSQL)

**Why PostgreSQL for the follow graph?**
- Follow/unfollow requires ACID transactions (can't have partial states)
- The follow graph needs strong consistency — user must see the same state everywhere
- Follower counts are frequently read — materialized or cached
- Graph size (users × follows) is bounded enough for PostgreSQL at most scales

```sql
CREATE TABLE follows (
  follower_id UUID NOT NULL,
  followee_id UUID NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
  PRIMARY KEY (follower_id, followee_id)  -- composite PK prevents duplicate follows
);

-- Index for "who follows user X" lookups (fan-out on write needs this)
CREATE INDEX idx_follows_followee ON follows(followee_id);
-- This index is the hot path — scanned for every post fanout

-- Separate counter table to avoid expensive COUNT(*) queries
-- Updated transactionally with follow/unfollow operations
CREATE TABLE user_stats (
  user_id        UUID PRIMARY KEY,
  follower_count BIGINT DEFAULT 0,
  following_count BIGINT DEFAULT 0,
  post_count     BIGINT DEFAULT 0,
  updated_at     TIMESTAMP
);
```

### Feed Store (Redis Sorted Sets)

**Why Redis Sorted Sets for the feed?**
- Sorted Set stores (member, score) pairs, where score determines order
- ZADD is O(log N) — fast even with 1000 entries per feed
- ZREVRANGE returns members in score order — perfect for newest-first feed
- ZRANGEBYSCORE supports cursor-based pagination by score
- TTL on feed keys handles inactive users automatically

```
Key schema:   feed:{user_id}
Value type:   Sorted Set
Score:        Unix timestamp in milliseconds (for ordering)
Member:       post_id as string

Operations:
  Write (fanout):  ZADD feed:{userId} {timestamp_ms} {post_id}
  Read (feed):     ZREVRANGE feed:{userId} 0 19 WITHSCORES
  Paginate:        ZREVRANGEBYSCORE feed:{userId} {cursor_score} -inf LIMIT 0 20
  Trim:            ZREMRANGEBYRANK feed:{userId} 0 -1001  (keep only 1000 newest)
  Size check:      ZCARD feed:{userId}

Memory calculation:
  1000 entries × (8 bytes score + 36 bytes UUID member + overhead) ≈ 90KB per user
  500M users × 90KB = 45TB total
  But: only 20-30% of users are active on any given day
  Active users: 150M × 90KB = 13.5TB → 2-3 Redis cluster nodes (with 6TB RAM each)
  Inactive user feeds: expire after 7 days of inactivity (TTL)
```

**Cursor-based pagination — why not offset?**

```
Offset-based (WRONG for feeds):
  Page 1: ZREVRANGE feed:user1 0 19
  Page 2: ZREVRANGE feed:user1 20 39  ← OFFSET 20
  Problem: if 3 new posts arrive between page 1 and page 2,
           offset 20 now points to different posts → duplicates or gaps

Score-based cursor (CORRECT):
  Page 1: ZREVRANGE feed:user1 0 19 WITHSCORES
          → last post has score = 1680000000000 (timestamp)
  Page 2: ZREVRANGEBYSCORE feed:user1 1679999999999 -inf LIMIT 0 20
          → reads from score LESS THAN the last seen score
          → new posts (higher scores) don't affect the cursor
          → stable, no duplicates even as new posts arrive
```

---

## Fanout Service — Async Architecture Deep Dive

The Fanout Service is the heart of the push model. It must be:
- **Asynchronous**: Posts should return to the user immediately; fanout happens in background
- **Scalable**: Process millions of fanout operations per second during peak
- **Fault-tolerant**: If fanout fails partway through (server crash), it must resume without duplicating work

```
Post created
     │
     ▼
Post Service: write to Cassandra + PostgreSQL (synchronous)
     │
     │ publish to Kafka (async, non-blocking to user)
     ▼
Kafka topic: posts.created
     │
     ├─► Fanout Consumer Group (5-10 instances)
     │         │
     │         ├─► Redis: ZADD to each follower's feed
     │         ├─► Notification Service: push notification to active followers
     │         └─► Search Indexing: index post content in ElasticSearch
     │
     └─► Analytics Consumer: real-time engagement tracking
```

**Why Kafka between Post Service and Fanout?**
- The post write should feel instant to the user — Kafka decouples the fast write from slow fanout
- Kafka provides durability: if fanout service crashes, events are not lost (replayed from offset)
- Kafka allows multiple consumers: fanout, notifications, search indexing all consume the same event
- Backpressure: if fanout is slow, messages queue in Kafka — no data loss, just latency increase

```java
// Complete fanout service with error handling and monitoring
@Service
public class FanoutService {

    private static final int CELEBRITY_THRESHOLD = 10_000;
    private static final int BATCH_SIZE = 500;
    private static final int MAX_FEED_SIZE = 1000;

    @KafkaListener(
        topics = "posts.created",
        groupId = "fanout-service",
        concurrency = "5"  // 5 consumer threads per instance, matching partition count
    )
    public void handlePostCreated(PostCreatedEvent event,
                                   Acknowledgment ack) {
        try {
            long authorFollowerCount = userStatsCache.getFollowerCount(event.getUserId());

            if (authorFollowerCount <= CELEBRITY_THRESHOLD) {
                // Regular user: full push fanout
                performPushFanout(event);
            }
            // Celebrities: no fanout — pulled at read time
            // This is intentional: we deliberately do nothing here for celebrities

            // Only commit offset AFTER successful processing
            // If we crash here, Kafka replays the message — at-least-once semantics
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Fanout failed for post {}", event.getPostId(), e);
            // Do NOT ack — Kafka will redeliver to another consumer
            // After max retries, message goes to DLQ for manual inspection
            throw e;
        }
    }

    private void performPushFanout(PostCreatedEvent event) {
        String postId = event.getPostId().toString();
        double score = event.getTimestamp(); // timestamp as Redis sorted set score

        // Stream followers in pages to handle large but sub-celebrity follower counts
        // A user with 5000 followers would still be processed in 10 batches of 500
        followerRepository.streamFollowerIds(event.getUserId(), BATCH_SIZE)
            .forEach(batch -> pushToFollowerBatch(batch, postId, score));
    }

    private void pushToFollowerBatch(List<UUID> followerBatch,
                                      String postId,
                                      double score) {
        // Redis pipeline: all ZADD commands sent in one network round trip
        // Critical for performance: without pipeline, 500 followers = 500 network hops
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (UUID followerId : followerBatch) {
                byte[] feedKey = feedKey(followerId);
                byte[] member = postId.getBytes(StandardCharsets.UTF_8);

                // Add post to follower's feed with timestamp score
                connection.zAdd(feedKey, score, member);

                // Trim feed to MAX_FEED_SIZE to control memory
                // This removes the oldest (lowest score = earliest timestamp) entries
                // Users who scroll beyond 1000 posts will see "load more" from Cassandra
                connection.zRemRangeByRank(feedKey, 0, -(MAX_FEED_SIZE + 1));

                // Refresh TTL: active user feeds stay alive for 7 days
                // Inactive user feeds automatically expire, reclaiming Redis memory
                connection.expire(feedKey, Duration.ofDays(7).toSeconds());
            }
            return null;
        });
    }

    private byte[] feedKey(UUID userId) {
        return ("feed:" + userId.toString()).getBytes(StandardCharsets.UTF_8);
    }
}
```

---

## The N+1 Query Problem in Feed Enrichment

After retrieving 20 post IDs from Redis, you need the actual post content. The naive approach causes N+1:

```java
// WRONG: N+1 queries — terrible at scale
public List<PostDTO> enrichFeed(List<String> postIds) {
    return postIds.stream()
        .map(id -> postRepository.findById(id))  // N separate DB queries!
        .collect(Collectors.toList());
}
// For 20 posts: 20 separate Cassandra queries × 2ms = 40ms just for enrichment
```

The correct approach: batch fetch with a single multi-get, using Redis as an L1 cache:

```java
// CORRECT: Batch fetch with Redis cache layer
public List<PostDTO> enrichFeed(List<String> postIds) {
    // Step 1: Check Redis cache for each post (most posts are cached)
    // MGET post:uuid1 post:uuid2 ... post:uuid20 — ONE Redis command
    List<PostDTO> cached = redisCacheService.mget(postIds, PostDTO.class);

    // Step 2: Find which post IDs were cache misses
    List<String> cacheMisses = postIds.stream()
        .filter(id -> cached.get(postIds.indexOf(id)) == null)
        .collect(Collectors.toList());

    if (!cacheMisses.isEmpty()) {
        // Step 3: Single batch read from Cassandra for cache misses
        // Cassandra handles IN queries efficiently (parallel reads across nodes)
        Map<String, PostDTO> fromDb = postRepository.findAllByIds(cacheMisses);

        // Step 4: Backfill cache
        redisCacheService.mset(fromDb, Duration.ofHours(24));

        // Step 5: Merge cached and DB results
        for (int i = 0; i < postIds.size(); i++) {
            if (cached.get(i) == null) {
                cached.set(i, fromDb.get(postIds.get(i)));
            }
        }
    }

    // Preserve the order from the feed (Redis returned them in score order)
    // DB lookup doesn't preserve order, so we must re-sort
    Map<String, PostDTO> byId = cached.stream()
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(PostDTO::getId, p -> p));
    return postIds.stream()
        .map(byId::get)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
}
```

---

## Ranking Algorithm

Simple reverse-chronological feeds are easy to implement but create poor user experience (spam from prolific posters buries everything else). Real systems use ranking signals:

```
score = base_recency_score(created_at)
      + engagement_velocity_boost  // likes/comments in first hour
      + relationship_strength       // how often you interact with this author
      + content_affinity_score     // do you like similar content?
      - diversity_penalty          // penalize if same author appears 3+ times

base_recency_score:
  Half-life decay: score = 1 / (1 + age_in_hours)^1.5
  → 1 hour old post: score = 0.35
  → 24 hour old post: score = 0.002
  → Ensures very old posts sink regardless of engagement

engagement_velocity_boost:
  velocity = (likes + comments × 2 + shares × 3) / sqrt(age_in_minutes + 1)
  → Hacker News-style velocity calculation
  → Rewards rapidly gaining engagement, not just absolute numbers
  → Prevents old viral posts from permanently dominating

relationship_strength (learned, stored per user-pair):
  strong_tie_boost = 1.5 if (DM_count > 10 OR mutual_interactions > 20)
  weak_tie_boost = 1.0 (default)

diversity_penalty:
  if author appears 3+ times in top 10 → score × 0.5 for subsequent posts
  → Prevents power users from flooding your feed
```

**Important architectural note:** Ranking cannot be done at fanout time because engagement signals change after the post is in the feed. The ranking must happen at read time, which is why feed enrichment fetches post metadata (including live like counts) rather than just storing the post content in the feed.

---

## Trending Topics

```
Architecture:
  Every post publish + hashtag → Kafka event
  Flink stream processor: windowed count per hashtag
  30-minute sliding window, updated every minute

  Redis Sorted Set per time window:
    ZINCRBY trending:20240115-1400 1 "#worldcup"  // increment count
    ZREVRANGE trending:20240115-1400 0 9           // top 10

  Why time-bucketed keys?
    - Clean up old buckets with Redis EXPIRE
    - Can aggregate multiple windows (1h, 24h) by merging sorted sets: ZUNIONSTORE
    - No complex cleanup logic — expired keys disappear automatically

  API:
    GET /trending?period=1h&limit=10&region=US
    → Read from trending:{current_hour_bucket}
```

---

## API Design

```
POST /posts
Authorization: Bearer {token}
Content-Type: application/json

{
  "content": "Hello world! #technology",
  "media_ids": ["media_id_from_upload"],
  "reply_to_post_id": null
}

Response 201:
{
  "post_id": "uuid",
  "created_at": "2024-01-15T14:30:00Z"
}

GET /feed?limit=20&cursor=eyJzY29yZSI6MTY4MDAwMDAwMDAwMH0
  cursor: base64-encoded JSON { "score": <last_post_timestamp> }
Response 200:
{
  "posts": [
    {
      "post_id": "uuid",
      "author": { "id": "uuid", "username": "alice", "avatar_url": "..." },
      "content": "Hello world!",
      "media_urls": [],
      "like_count": 142,
      "comment_count": 23,
      "repost_count": 5,
      "created_at": "2024-01-15T14:30:00Z",
      "liked_by_viewer": false
    }
  ],
  "next_cursor": "eyJzY29yZSI6MTY3OTk5OTk5OTk5fQ==",
  "has_more": true
}

POST /posts/{postId}/likes     → like a post
DELETE /posts/{postId}/likes   → unlike a post

GET /trending?period=1h&limit=10
```

---

## Edge Cases and Failure Scenarios

### Scenario 1: Fanout Service Crashes Mid-Fanout

```
Problem: Post has 5000 followers. Fanout processes 2000 before crash.
         3000 followers never receive the post in their feed.

Solution: Idempotent fanout with progress tracking
  - Kafka provides exactly-one message delivery to a partition
  - Fanout consumer uses manual offset commit (ack after completion)
  - On restart, consumer re-reads from last committed offset
  - Re-executing ZADD for already-processed followers is safe (idempotent)
  - The same post_id with same score gets overwritten (no duplicate in sorted set)

Risk: If fanout is NOT idempotent (e.g., sends push notifications),
      the 2000 already-processed followers get duplicate notifications.
      Solution: separate the idempotent Redis writes from non-idempotent notifications
```

### Scenario 2: Celebrity Posts an Extremely Popular Content

```
Problem: Celebrity with 50M followers posts viral content.
         Even with no fanout, every follower loads the feed simultaneously.
         50M read requests hit the celebrity_posts Cassandra table.

Solution: Multi-layer caching for celebrity posts
  - Cache celebrity's recent posts in Redis: celebrity_posts:{userId}
    → Set of recent post IDs, cached for 60 seconds
  - Individual posts cached in Redis after first read: post:{postId}
  - Celebrity post list updates propagate via short TTL (60s acceptable stale)
  - CDN caching for media assets (S3 + CloudFront)
  - Result: Cassandra sees a fraction of the actual traffic
```

### Scenario 3: User Follows/Unfollows While Fanout is In-Flight

```
Problem: User A unfollows User B. While the unfollow transaction commits,
         an in-flight fanout is pushing User B's post to User A's feed.
         After unfollow completes, User A still sees User B's post.

Solution: Eventual consistency is acceptable here
  - The post will appear once (the in-flight fanout completes)
  - Feed reads apply a "followee filter" on returned posts
  - Any posts from unfollowed users are filtered out at read time
  - Since feed is limited to 1000 entries, the erroneous entry ages out quickly
  - This is the Twitter/Instagram behavior — occasional stale posts after unfollow
```

### Scenario 4: Redis Feed Eviction (Cache Miss)

```
Problem: User hasn't opened the app in 8 days. Their Redis feed has expired.
         User opens app — Redis key does not exist.

Solution: Cold start rebuilding
  1. Feed Service detects missing key (ZCARD = 0 or key doesn't exist)
  2. Fall back to pull-model: query Cassandra for posts from each followee
  3. Rebuild the Redis feed in the background while returning first page
  4. Future loads hit Redis again

Cold start is slow (500-2000ms) but rare — only for inactive users.
The user experience tradeoff is acceptable: inactive users don't expect instant loads.
```

### Scenario 5: Hot Shard in Redis

```
Problem: Multiple high-follower users post simultaneously.
         Their followers happen to be clustered on one Redis shard.
         That shard becomes CPU-bound.

Solution: Consistent hashing with virtual nodes
  - Redis Cluster distributes keys via CRC16 hash slots (16384 slots)
  - feed:{userId} distributes by userId hash — generally uniform
  - If hotspot detected: increase replica count for hot shards
  - Read feeds from replicas (feed reads are acceptable from slightly stale replica)
  - Writes (ZADD) still go to primary but reads spread across replicas
```

---

## Scalability Summary

| Concern | Problem | Solution |
|---|---|---|
| Celebrity fanout | Write amplification: 100M writes/post | Hybrid push/pull: skip fanout, pull at read time |
| Feed read latency | 115k reads/sec | Redis pre-built feed: O(log N), < 1ms |
| Follower list lookup | DB bottleneck during fanout | Paginated streaming from DB, follower count cache |
| Feed cold start | Redis miss after inactivity | Lazy rebuild with pull fallback |
| Media storage | 400TB/day | S3 + CloudFront CDN (edge caching) |
| Like counts | 10M likes/sec atomic updates | Redis INCR per post (lock-free), async persist to Cassandra |
| Trending | Real-time hashtag counting | Kafka + Flink + Redis windowed sorted sets |
| Pagination stability | New posts shift page offsets | Score-based cursor (not offset) |
| Redis memory | 45TB for all users | TTL on inactive feeds, keep only 1000 posts per feed |

---

## Tech Stack

- **Post/Follow DB**: PostgreSQL (ACID, relational integrity for follows)
- **Post content**: Cassandra (time-series, high write throughput, no join needed)
- **Feed store**: Redis Cluster with Sorted Sets (pre-built feeds)
- **Fanout coordination**: Apache Kafka (durable event queue, multi-consumer)
- **Fanout workers**: Java 17, Spring Boot, Spring Kafka
- **Media**: AWS S3 + CloudFront CDN
- **Search/Trending**: ElasticSearch + Apache Flink
- **Notifications**: Firebase Cloud Messaging / Apple Push Notification Service
- **API layer**: Java 17, Spring Boot, behind API Gateway

---

## Interview Q&A

### Q1: Why use Redis Sorted Sets for the feed instead of a simple Redis List?

A Redis List (LPUSH/LRANGE) stores items in insertion order, which works for purely chronological feeds. However, Sorted Sets are superior for several reasons. First, Sorted Sets associate each member with a numeric score, which enables efficient range queries by score — essential for cursor-based pagination (ZRANGEBYSCORE) and for inserting posts out of chronological order (e.g., if a fanout worker is delayed and inserts a 3-minute-old post). Second, Sorted Sets are inherently deduplicated: ZADD with the same member simply updates the score, so if a post is fanout-pushed twice (due to worker retry), it only appears once in the feed. Third, for ranked feeds where the score is a computed ranking value rather than a timestamp, Sorted Sets provide the correct ordering automatically. The tradeoff is slightly higher memory usage (storing score alongside member) and slightly higher computational cost, but both are negligible compared to the benefits.

### Q2: How do you handle the case where a celebrity user unfollows someone — do you clean up their feed immediately?

Feed cleanup on unfollow is a classic eventual consistency vs. complexity tradeoff. Immediate cleanup would require knowing every post in the user's feed that came from the unfollowed account and removing them one by one from the Redis sorted set — this is O(N) and complex. Instead, most systems apply a server-side filter at read time: when serving the feed, check if each post's author is still followed, and silently drop posts from unfollowed accounts. This filter is cheap (the followed-set is cached in Redis as a SET, and SISMEMBER is O(1)). The user will not see posts from the unfollowed account immediately after unfollowing because the next page load applies the filter. Old posts already displayed on screen persist until the user refreshes. This eventual consistency is acceptable because the social contract of unfollowing doesn't guarantee instant disappearance from all contexts. The Redis feed key naturally ages out stale entries as new posts replace them over the TTL window.

### Q3: How would you design the ranking algorithm to avoid creating filter bubbles?

Filter bubbles occur when the ranking algorithm exclusively surfaces content from creators whose previous posts the user engaged with, starving exposure to new or diverse content. The solution is to explicitly inject diversity signals into the ranking score. Concretely, you apply a "novelty bonus" to posts from authors the user hasn't interacted with recently (author_not_seen_in_7_days → score × 1.3). You also enforce a "recency diversity" rule: cap any single author to at most 20% of any 20-post feed, applying a diminishing multiplier for subsequent posts from the same author. Separately, a "discovery injection" mechanism explicitly inserts 1-2 posts per feed load from outside the user's immediate social graph — posts recommended by a collaborative filtering model. The ranking weights themselves are tuned via A/B testing against engagement metrics, but with guard rails: a pure click-maximization objective would create addiction feedback loops, so platforms add explicit diversity and well-being constraints to the objective function.

### Q4: What happens when a user with 1M followers posts for the first time — how does your system handle the thundering herd?

A first-time post from a 1M-follower account triggers a complex cascade. If the user was previously below the celebrity threshold, they would have been in the push-fanout path. Suddenly doing 1M Redis writes would spike the fanout queue. The system should handle this gracefully through several mechanisms: First, follower counts are monitored, and accounts crossing the celebrity threshold are reclassified asynchronously (their future posts skip fanout). Second, if a reclassification hasn't happened yet and a spike of 1M ZADD operations lands in the fanout queue, Kafka provides natural buffering — the fanout workers process at their maximum throughput over several minutes rather than all at once. Third, Redis pipeline batching limits the actual write rate. Finally, the fanout queue has rate limiting per source user: if one user is generating excessive fanout volume, the system throttles their fanout speed (their followers may see the post 30 seconds late instead of 5 seconds) to prevent starving other users' fanout operations.

### Q5: Explain cursor-based pagination in detail and why offset-based pagination fails for live feeds.

Offset-based pagination (LIMIT 20 OFFSET 20) is fundamentally broken for live data: when you read page 1 and then request page 2, new posts have been inserted at the top of the feed. The items that were at offsets 20-39 are now at offsets 23-42 (because 3 new posts pushed everything down). You either skip 3 posts or see 3 duplicate posts from page 1. At high-traffic times with thousands of new posts per minute, this creates severe pagination artifacts. Cursor-based pagination instead uses the score (timestamp or ranking score) of the last seen post as the next page marker. For Redis Sorted Sets, the next-page query is ZREVRANGEBYSCORE feed:{userId} {last_score - 1} -inf LIMIT 0 20 — "give me 20 posts with score less than the last one I saw." New posts (with higher scores) don't affect this query at all, giving perfectly stable pagination. The cursor is typically base64-encoded and opaque to the client, allowing the server to change the internal format without breaking clients.

### Q6: How does your system handle the N+1 query problem when enriching a feed of 20 post IDs?

The N+1 problem occurs when you have 20 post IDs and make 20 individual database calls to fetch each post. The solution is batch fetching at every layer. Redis supports MGET for batch key lookups — one command fetches 20 cached posts in a single network round trip. For cache misses, Cassandra efficiently handles SELECT ... WHERE post_id IN (uuid1, uuid2, ...) because Cassandra distributes data across nodes and can fetch each UUID from the appropriate node in parallel, returning results faster than 20 sequential queries. The fetched posts are then cached in Redis for future reads. For engagement metrics (like counts), rather than fetching them separately, you store a denormalized counter in the post's cache entry and update it asynchronously via Redis INCR with periodic sync to Cassandra. The key principle is: minimize the number of distinct I/O operations, and parallelize the ones you must make. A well-implemented feed enrichment pipeline adds at most 5-15ms of latency for a 20-post page.

### Q7: How would you implement "liked by people you follow" signals in the feed?

Showing social proof ("Alice and 3 others liked this") requires a different data model than simple like counts. For each post, maintain a Redis Set of recent likers: SADD post:{postId}:likers {userId}. Keep this set capped at the 100 most recent likers (trim old entries). At feed serve time, for each post in the feed, run SINTER post:{postId}:likers user:{viewerId}:following — intersect the post's likers with who the viewer follows. SINTER returns common members, giving you the social liker list. The SINTER operation is O(N) where N is the smaller set size — since the following set might be large (user follows 1000 people), cache the following set in Redis and keep it size-bounded. This adds 1-3ms to feed serve time if done efficiently with pipelines, and dramatically increases engagement by adding social context to the feed. Limit the social signal computation to the top 10 posts in the feed rather than all 20 to control latency.

### Q8: What database would you use for storing the social graph (follows), and why not a graph database?

PostgreSQL is the right choice for the follow graph despite this being a "graph" problem. The access patterns are simple: "get all followers of user X" and "get all followees of user Y" — both are single-table scans on indexed columns. Graph databases like Neo4j shine for multi-hop traversals (friends-of-friends, 6 degrees of separation), but feed systems rarely need more than 1-hop queries. PostgreSQL with proper indexes (index on followee_id for follower lookup) handles billions of follow relationships efficiently. The advantages of PostgreSQL are: ACID transactions for follow/unfollow (preventing partial states), simple schema, excellent operational tooling, and the ability to join with user metadata. At Facebook scale, specialized graph stores (TAO) become necessary, but these are built on top of MySQL with aggressive caching — not dedicated graph databases. If you truly need 2-hop graph queries (mutual friends feature), a read replica running with graph-specific indexes, or a cache materialized into a graph structure, handles this without replacing the primary store.

### Q9: How would you handle feed generation for a new user with no follows (cold start problem)?

A new user with zero follows has an empty feed, which creates a poor onboarding experience. The solution involves multiple layers. Immediately at signup, pre-populate the feed with: (1) trending posts in their geographic region, (2) posts from recommended accounts based on their stated interests, and (3) content from verified/official accounts in relevant categories. These "seed" posts are pushed into their Redis feed with slightly lower scores than real follows (so genuine follows dominate once established). The recommendation system uses collaborative filtering: find users with similar demographic/interest profiles, identify what accounts they followed first, and suggest those same accounts. As the user makes follows, the fanout system immediately starts populating their feed. The cold-start experience also includes guided onboarding ("follow 5 people to see your personalized feed") with curated suggestions. Measuring cold-start success is crucial: track day-1 retention vs. number of follows made in first session — this data drives onboarding improvements.

### Q10: How would you monitor and alert on feed system health?

Feed system health requires monitoring across multiple dimensions. Latency: track P50/P95/P99 of feed load time end-to-end, broken down by: Redis hit vs. cold start, celebrity merge path vs. pure push path. Alert if P99 > 500ms (2x our 200ms SLA). Fanout lag: measure Kafka consumer group lag on the fanout topic. If lag grows beyond 60 seconds worth of messages, alert — followers are seeing delayed feeds. Redis memory: monitor used memory vs. capacity across Redis shards. Alert at 75% — shards need to be added before OOM occurs. Feed freshness: for a sample of users, verify that posts appear in their feed within 10 seconds of creation. If freshness degrades, indicates fanout issues. Error rates: track ZADD failures, Cassandra timeouts in post enrichment, and celebrity pull timeouts separately — each indicates a different failure mode. Use distributed tracing (Jaeger/Zipkin) to trace individual feed requests end-to-end, making it possible to identify which component (Redis, Cassandra, celebrity pull) is contributing latency. Set SLO-based alerting: if feed success rate (< 200ms, no errors) drops below 99.5% over 5 minutes, page on-call.

### Q11: How would you design the system differently for an Instagram-style feed vs. a Twitter-style feed?

Instagram and Twitter have meaningfully different feed architectures due to different content volumes and consumption patterns. Twitter's feed is high-velocity (many short posts, users post dozens of times per day) — the feed must handle rapid fanout and the celebrity problem is acute (accounts with 100M followers). Instagram's feed has lower posting frequency but larger post sizes (images/videos) and a stronger ranked (non-chronological) feed. For Twitter-style: push-pull hybrid with 10k celebrity threshold, strict chronological ordering as baseline, and Redis sorted set keyed by timestamp. For Instagram-style: feed ranking becomes more important than chronological order, requiring a re-ranking ML model that runs at read time — the Redis feed stores "candidate pool" of ~100 posts and the ranking model selects the top 20 for display. Instagram also uses an "interest graph" beyond the follow graph — posts from accounts you don't follow can surface based on content affinity. The unified architecture difference: Twitter optimizes for recency and volume (throughput), Instagram optimizes for relevance (ML ranking on smaller candidate sets).

### Q12: If Redis goes down, how does your feed system degrade gracefully?

Redis is the single most critical dependency in the feed system, so graceful degradation is essential. The strategy is progressive fallback: (1) If a user's specific feed key is missing (expired or eviction), fall back to the pull model — query Cassandra for recent posts from followees. This is slow (500-2000ms) but correct. (2) If Redis is completely unavailable, route all feed requests to the pull model with aggressive DB-level caching (Caffeine in-process cache per API server, TTL 30 seconds). (3) If even Cassandra is struggling under the load, serve a "reduced feed" — only the user's own posts and 5 most-followed accounts, fetched with simple queries. (4) Deploy a Redis read replica: feed reads can be served from a replica even if the primary is down. Redis Cluster with at least 3 primary shards and 3 replicas means partial Redis failure (1 shard down) only affects 1/3 of users' feeds, not all. The key operations to monitor during degradation: compare served-from-cache rate with served-from-fallback rate. If fallback rate spikes above 5%, alert immediately — the system is under stress. Communicate feed degradation to users via an in-app notice rather than serving confusing empty feeds.

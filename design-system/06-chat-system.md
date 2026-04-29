# Real-Time Chat System — Deep Design Guide

## Problem Statement

Design a real-time chat system supporting 1-on-1 and group messaging, with message history,
online presence, and read receipts — comparable to WhatsApp or Slack. The system must deliver
messages in under 100ms to online users and guarantee no message loss for offline users.

---

## Why This Problem Matters

Chat systems are the canonical example for real-time system design because they force you to
reason about problems that do not appear in request/response architectures:

- **Persistent connections**: HTTP was designed for stateless request/response. Real-time
  delivery requires the server to push to the client without a prior request. Every technology
  choice in the system flows from how you solve this problem.
- **Message ordering in a distributed system**: Two users sending messages simultaneously
  to the same chat creates a race condition. Whose message appears first? Different chat
  gateway servers may process messages in different orders. Getting this right requires
  careful thought about sequence numbers and distributed clocks.
- **Fanout at scale**: In WhatsApp, a message to a group of 500 people must be delivered to
  all 500 members, potentially spread across hundreds of different gateway servers. This fanout
  is the most expensive operation in the system and dominates the capacity model.
- **Presence system**: "Is this user online?" seems simple but at 100M users with 1M concurrent
  connections, the naive approach (query a central store per message) creates 50k presence
  queries per second. You need a heartbeat-based TTL system, not a real-time query.

**What interviewers are testing**: Whether you understand why WebSocket is necessary (not just
"it's faster"), the data model rationale for Cassandra (time-series partitioning), the fanout
problem for large groups, how presence works at scale, and how offline delivery integrates with
the real-time path.

---

## Key Insight Before Diving In

**The core insight: a chat system is two systems merged into one — a real-time delivery network
(for online users) and a durable message store (for offline users and history). Both paths must
work independently and produce the same result.**

Message flow for an online recipient:
```
Sender → WebSocket → Gateway → [write to Cassandra] → [route to recipient gateway] → WebSocket → Recipient
```

Message flow for an offline recipient:
```
Sender → WebSocket → Gateway → [write to Cassandra] → [send push notification] → FCM/APNS → Recipient wakes up
                                                                                             → loads history from Cassandra
```

The offline user gets the exact same message — just via a different delivery mechanism. This
dual-path design is why Cassandra (not Redis) is the primary message store: Redis is fast but
volatile. Cassandra is the ground truth that both online delivery and offline recovery read from.

---

## Requirements

### Functional
- 1-on-1 messaging and group chat (up to 500 members)
- Message delivery status: sent → delivered → read receipts
- Message history with cursor-based pagination (load older messages)
- Online/offline presence indicator
- Typing indicators
- Media messages: image, video, file (URL-based, stored in object storage)
- Message reactions (emoji reactions on messages)
- Push notifications when user is offline
- Message search (within conversations)

### Non-Functional
- 100M daily active users (DAU)
- < 100ms message delivery latency (P99 for online-to-online)
- Messages must never be lost (at-least-once delivery, dedup on client)
- Support 1M concurrent WebSocket connections
- Messages stored for 5 years
- 99.99% uptime

---

## Capacity Estimation

```
Users: 100M DAU
Messages: each user sends ~40 messages/day = 4B messages/day
Peak: 50,000 messages/second

Message size breakdown:
  Avg text message: 100 bytes
  Total: 4B × 100 bytes = 400GB/day = ~4.6MB/sec

WebSocket connections:
  Assume 1% of DAU online simultaneously = 1M concurrent connections
  Each connection = 1 TCP socket + ~10KB memory in gateway
  1M connections → 10GB RAM for connection state → 100 gateway servers × 100K connections each

Cassandra storage:
  400GB/day × 365 × 5 = ~730TB raw
  With compression (Cassandra LZ4): ~200TB
  Number of Cassandra nodes: 200TB / 4TB per node = ~50 nodes (with replication factor 3: 150 nodes)

Presence system:
  1M online users → 1M Redis keys
  Each key: ~50 bytes → 50MB (trivial)
  Heartbeat rate: 1M × 1 heartbeat/10s = 100k writes/sec to Redis

Read receipts:
  Assume 50% of messages trigger a read receipt = 2B receipts/day
  Each receipt: ~50 bytes → 100GB/day (stored with message metadata)
```

---

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                     Client (Mobile / Web)                            │
│  WebSocket connection  │  REST API calls (history, media upload)     │
└──────────┬─────────────┴──────────────────────────────────────────────┘
           │ WebSocket (WSS/TLS)
           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                   Chat Gateway Cluster                               │
│  (100 servers, each handling 10K concurrent WebSocket connections)   │
│                                                                      │
│  connection_map: { user_id → websocket_session }                    │
│  Manages: message receive, route, heartbeat, presence                │
└─────┬──────────────────────┬────────────────────────────────────────┘
      │                      │
      │ Kafka publish         │ Redis lookup (where is recipient?)
      ▼                      ▼
┌─────────────┐      ┌─────────────────────────────────────────────┐
│   Kafka     │      │               Redis Cluster                  │
│  (message   │      │  user:{id}:gateway → "gateway-host-42"      │
│   events)   │      │  user:{id}:status  → "online" (TTL=30s)    │
└──────┬──────┘      └─────────────────────────────────────────────┘
       │
       ├─────────────────────────┐
       ▼                         ▼
┌─────────────────┐     ┌─────────────────────────────────────────┐
│  Message        │     │  Fanout Service                          │
│  Service        │     │  (for group messages: reads member list,  │
│                 │     │   routes to each member's gateway)       │
│  Persist to     │     └─────────────────────────────────────────┘
│  Cassandra      │
│                 │
│  Update read    │     ┌─────────────────────────────────────────┐
│  receipts       │     │  Push Notification Service               │
└─────────────────┘     │  (for offline users: FCM / APNS)        │
                        └─────────────────────────────────────────┘
```

---

## Why WebSocket Over Alternatives

### Option 1: Short Polling
```
Client: GET /messages?since=<timestamp>  (every 3 seconds)
Server: returns new messages

Problems:
- 3-second delay is noticeable in real-time chat
- 1M users × 1 request/3s = 333k HTTP requests/second — massive overhead
- Each request opens a new TCP connection (or keeps-alive drain resources)
- Server cannot distinguish "no new messages" from "server is down"
```

### Option 2: Long Polling
```
Client: GET /messages (holds open until message arrives or timeout)
Server: holds connection, returns when message available

Better than short polling, but:
- Still HTTP — each "poll" requires HTTP overhead (headers, TLS handshake per reconnect)
- Hard to scale: 1M held-open HTTP connections require careful server tuning
- Timeout/reconnect creates delivery gaps
- Server cannot push multiple messages simultaneously
```

### Option 3: WebSocket (Correct Choice)
```
Client: Upgrade HTTP → WebSocket (one-time handshake)
Server: bidirectional binary/text framing over same TCP connection

Advantages:
- True bidirectional: server pushes without client request
- < 10 bytes overhead per message frame (vs. 500+ bytes for HTTP headers)
- Single TCP connection per user — no reconnect overhead
- Protocol supports ping/pong for keepalive (heartbeat)
- Scales to millions of concurrent connections with async I/O (Netty, Node.js)
```

```java
// WebSocket frame overhead:
// 2-byte minimum header (FIN=1, opcode=1, MASK=1, payload length)
// vs HTTP header overhead of ~500 bytes minimum
// At 50k messages/sec, this saves 50k × 500B = 25MB/sec in header overhead alone
```

### Option 4: Server-Sent Events (SSE)
SSE is unidirectional (server → client only). Good for notifications but not chat where the
client also needs to send messages efficiently. Chat requires bidirectional communication.

---

## WebSocket Connection Management

```java
@Component
public class ChatGateway {

    // In-memory map of connected users on THIS gateway instance
    // ConcurrentHashMap for thread-safety (many concurrent WebSocket threads)
    private final ConcurrentHashMap<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();

    // Redis for cross-gateway routing: tells other gateways where a user is connected
    private final RedisTemplate<String, String> redis;

    @OnOpen
    public void onConnect(WebSocketSession session, String userId) {
        // Register connection locally
        sessionMap.put(userId, session);

        // Announce to the cluster: "User X is on THIS gateway"
        // Other gateways will use this to route messages to this instance
        String gatewayHost = System.getenv("POD_NAME"); // Kubernetes pod name
        redis.opsForValue().set("user:" + userId + ":gateway", gatewayHost, Duration.ofHours(24));

        // Update presence: user is now online
        redis.opsForValue().set("user:" + userId + ":status", "online", Duration.ofSeconds(30));

        // Deliver any messages that arrived while user was offline (unread inbox)
        deliverOfflineMessages(userId, session);

        log.info("User {} connected to gateway {}", userId, gatewayHost);
    }

    @OnClose
    public void onDisconnect(WebSocketSession session, String userId) {
        sessionMap.remove(userId);
        // Don't delete Redis key immediately — let it expire naturally (30s TTL)
        // This avoids a race condition where onConnect fires before onClose's DELETE
        // After 30s of no heartbeat, the key expires and user appears offline
        log.info("User {} disconnected", userId);
    }

    @OnMessage
    public void onMessage(WebSocketSession session, String userId, String rawMessage) {
        ChatMessage msg = parseMessage(rawMessage);

        switch (msg.getType()) {
            case "HEARTBEAT" -> handleHeartbeat(userId);
            case "MESSAGE"   -> handleChatMessage(userId, msg);
            case "TYPING"    -> handleTypingIndicator(userId, msg);
            case "READ"      -> handleReadReceipt(userId, msg);
        }
    }

    private void handleHeartbeat(String userId) {
        // Refresh TTL — user is still online
        // If no heartbeat for 30s → key expires → user appears offline
        redis.expire("user:" + userId + ":status", Duration.ofSeconds(30));
        redis.opsForValue().set("user:" + userId + ":last_seen",
            String.valueOf(Instant.now().getEpochSecond()));
    }

    // Deliver a message to a user connected to THIS gateway
    public boolean deliverToLocalUser(String userId, ChatMessage msg) {
        WebSocketSession session = sessionMap.get(userId);
        if (session == null || !session.isOpen()) {
            return false; // user not connected here
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
            return true;
        } catch (IOException e) {
            log.warn("Failed to deliver message to user {}", userId, e);
            return false;
        }
    }
}
```

---

## Data Model

### Cassandra — Why and How

**Why Cassandra for messages?**

Messages have a time-series access pattern: you always read the most recent N messages for
a specific conversation. Cassandra's data model — partition key + clustering key — is
perfectly suited for this:

- **Partition key**: `chat_id` — all messages for a chat live on the same Cassandra partition
  nodes, making a single-chat read a single-partition scan (extremely fast)
- **Clustering key**: `message_id` (TIMEUUID) — messages within a partition are stored in
  time order on disk, making "get last 50 messages" a sequential read (disk I/O friendly)

Contrast with a relational database (PostgreSQL): a `WHERE chat_id = X ORDER BY created_at DESC LIMIT 50`
query requires either a full table scan (slow) or an index seek. At 4B messages/day, a
PostgreSQL table would have trillions of rows and even indexed queries would be slow.
Cassandra distributes those rows across nodes and keeps each partition's data physically
co-located and sorted.

**The hot partition problem**: If a very popular chat (1M members) has all its messages in
one Cassandra partition, that partition node becomes a bottleneck. Solution: add a "bucket"
to the partition key:

```sql
-- Simple partition (hot partition risk for popular chats):
PRIMARY KEY (chat_id, message_id)

-- Bucketed partition (distributes load, bounded partition size):
-- bucket = message_timestamp / (1000 * 60 * 60 * 24) = day-level bucket
PRIMARY KEY ((chat_id, bucket), message_id)

-- Now: read last 50 messages in chat_id=X for today:
SELECT * FROM messages
WHERE chat_id = 'abc' AND bucket = 20260429
ORDER BY message_id DESC LIMIT 50;

-- For pagination across day boundary, query previous bucket too
```

```sql
-- ==========================================
-- MESSAGES TABLE
-- Primary read: "get last 50 messages in chat X"
-- ==========================================
CREATE TABLE messages (
  chat_id     UUID,
  bucket      INT,        -- day bucket: epoch_day = floor(timestamp_ms / 86400000)
                          -- bounds partition size to ~max_daily_messages per chat
  message_id  TIMEUUID,   -- globally unique + time-ordered
                          -- TimeUUID = timestamp (60-bit) + MAC address + sequence
                          -- Guarantees total ordering within a partition without a coordinator
  sender_id   UUID,
  chat_type   VARCHAR,    -- DIRECT, GROUP
  content     TEXT,
  content_type VARCHAR,   -- TEXT, IMAGE, VIDEO, FILE, REACTION
  media_url   TEXT,       -- for IMAGE/VIDEO/FILE messages
  reply_to_id TIMEUUID,   -- for threaded replies
  deleted_at  TIMESTAMP,  -- soft delete (content replaced with "Message deleted")
  created_at  TIMESTAMP,
  PRIMARY KEY ((chat_id, bucket), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC)  -- newest first = most common read pattern
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS',
                    'compaction_window_size': 1}
  AND compression = {'sstable_compression': 'LZ4Compressor'};

-- TimeWindowCompactionStrategy: optimal for time-series
-- Groups SSTables by time window → old data compacted together
-- Hot recent data stays in recent SSTable (fast reads)

-- ==========================================
-- CHAT_MEMBERS TABLE
-- Read: "get all members of group X" (for fanout)
-- ==========================================
CREATE TABLE chat_members (
  chat_id     UUID,
  user_id     UUID,
  role        VARCHAR,    -- OWNER, ADMIN, MEMBER
  joined_at   TIMESTAMP,
  muted_until TIMESTAMP,  -- null = not muted, future = muted
  last_read_message_id TIMEUUID,  -- for read receipt tracking
  PRIMARY KEY (chat_id, user_id)
);

-- ==========================================
-- USER_CHATS TABLE
-- Read: "get all chats for user X" (inbox view)
-- This is a separate table (denormalized) for efficient user inbox queries
-- Cassandra requires denormalization — each query pattern needs its own table
-- ==========================================
CREATE TABLE user_chats (
  user_id     UUID,
  last_message_at TIMESTAMP,  -- used for ordering inbox
  chat_id     UUID,
  chat_name   TEXT,
  last_message_preview TEXT,  -- for inbox display without loading full message
  unread_count INT,
  PRIMARY KEY (user_id, last_message_at, chat_id)
) WITH CLUSTERING ORDER BY (last_message_at DESC);

-- ==========================================
-- READ RECEIPTS TABLE
-- Track which users have read up to which message
-- ==========================================
CREATE TABLE read_receipts (
  chat_id          UUID,
  user_id          UUID,
  last_read_message_id TIMEUUID,
  updated_at       TIMESTAMP,
  PRIMARY KEY (chat_id, user_id)
);
```

### Redis — Presence and Routing

```
# Per-user gateway routing (where is this user connected?)
user:{userId}:gateway    → "gateway-pod-42"      TTL=24h (refreshed on reconnect)

# Per-user presence status (are they online?)
user:{userId}:status     → "online"              TTL=30s (refreshed on heartbeat)
                         # Key expires if no heartbeat → user appears offline

# Last seen timestamp (for "last active 5 minutes ago" display)
user:{userId}:last_seen  → "1714392060"          (unix timestamp, no TTL)

# Typing indicator (ephemeral, short TTL)
typing:{chatId}:{userId} → "1"                   TTL=5s (auto-expires when user stops typing)

# For group chats: set of online members (optional optimization)
group:{chatId}:online_members → Set{userId1, userId2, ...}  TTL=30s
```

---

## Message Flow: 1-on-1 Chat

```
Step-by-step message delivery (User A → User B):

1. User A types message, presses Send
2. Client sends WebSocket frame to Gateway-A:
   {type:"MESSAGE", chat_id:"chat123", content:"Hello!", client_msg_id:"client-uuid-abc"}
   client_msg_id: client-generated UUID for deduplication (prevents duplicate if WS reconnects)

3. Gateway-A receives message:
   a. Validates: sender is member of chat123, content length < 64KB, not muted
   b. Assigns server-side message_id = TimeUUID.now()
   c. Publishes to Kafka: topic="messages", key=chat_id
      payload: {chat_id, message_id, sender_id, content, ...}

4. Message Service (Kafka consumer):
   a. Persists to Cassandra (messages table)
   b. Updates user_chats for both User A and User B (last_message preview)
   c. Emits ACK back to Kafka: topic="message-acks", key=message_id

5. Gateway-A receives ACK → sends SENT receipt to User A:
   {type:"SENT", client_msg_id:"client-uuid-abc", server_msg_id:"<timeuuid>"}
   User A's UI changes message from "sending..." to single checkmark

6. Fanout Service (Kafka consumer):
   a. Looks up Redis: user:B:gateway → "gateway-pod-17"
   b. If online: HTTP call to Gateway-Pod-17: "deliver message to User B"
   c. Gateway-Pod-17 → WebSocket → User B receives message
   d. User B's gateway sends DELIVERED receipt to Kafka
   e. Gateway-A receives DELIVERED → sends to User A: double checkmark

7. If User B is offline (Redis key expired or no gateway key):
   a. Fanout Service publishes to push notification topic
   b. Push Service → FCM/APNS → User B's device
   c. User B opens app → connects WebSocket → loads unread messages from Cassandra
```

---

## Message Ordering in Distributed Systems

### The Problem

Consider two users sending messages to the same group at the same time:

```
User A on Gateway-1: sends "Hello" at T=1000ms
User B on Gateway-2: sends "World" at T=1001ms

Gateway-1 assigns TIMEUUID with T=1000ms
Gateway-2 assigns TIMEUUID with T=1001ms

But what if the servers' clocks differ by 10ms?
  Gateway-1 clock: 10:00:00.000
  Gateway-2 clock: 09:59:59.995  (5ms behind)

User A sends at Gateway-1 time 10:00:00.000 → message_id timestamp = 1000
User B sends at Gateway-2 time 09:59:59.998 → message_id timestamp = 998

Result: "World" appears before "Hello" even though B sent it later (by real clock)
```

### Solutions

**Option 1: TIMEUUID (Current Recommendation)**

TIMEUUID is a UUID variant (Type 1) that encodes a 60-bit timestamp with 100-nanosecond
resolution plus a 48-bit MAC address and 14-bit clock sequence. The clock sequence prevents
duplicates if multiple UUIDs are generated in the same 100ns interval.

For chat, TIMEUUID provides:
- Within a single server: perfect ordering (monotonically increasing sequence)
- Across servers: approximately correct ordering (bounded by NTP clock skew, typically < 10ms)
- Globally unique: MAC address component prevents collisions across servers
- Self-contained: no central coordinator needed for ID assignment

The 10ms clock skew creates 10ms ordering uncertainty. For chat, this is acceptable: if two
messages arrive within 10ms of each other, the exact ordering is ambiguous even to the users.

**Option 2: Sequence Numbers (per chat)**

```sql
-- Counter table in Redis or PostgreSQL
-- Each chat has a monotonically increasing sequence counter
-- Message ID includes sequence number for ordering

INCR chat:{chatId}:seq → returns next sequence number (atomic in Redis)
```

Problem: creating a single sequence counter per chat creates a bottleneck and a single point
of failure. Every message send requires a synchronous increment, and the counter must be
replicated. At 50k messages/sec distributed across many chats, this is 50k Redis INCR
operations/second — manageable but adds latency.

**Option 3: Hybrid Logical Clocks (HLC)**

HLC combines physical timestamp with a logical counter to handle clock skew:
```
HLC = max(physical_clock, received_hlc) + logical_counter
```
When a server receives a message with an HLC greater than its own, it advances its HLC.
This provides causal ordering (if A happened before B, HLC(A) < HLC(B)) without requiring
a central coordinator. This is the approach used by CockroachDB and Cassandra internally.

**Recommendation**: TIMEUUID for most chat systems. Sequence numbers for financial messaging
systems where strict ordering is critical. HLC for advanced distributed systems.

---

## Group Chat Fanout Strategies

### The Problem

A message to a group with 500 members must be delivered to all 500 members. The fanout
cost multiplied by message volume is the dominant cost in large-scale chat systems.

**Fanout cost analysis:**
```
50k messages/sec × avg_group_size × delivery_cost = total fanout operations/sec

If avg_group_size = 10: 50k × 10 = 500k delivery operations/sec (manageable)
If avg_group_size = 100: 50k × 100 = 5M delivery operations/sec (expensive)
```

### Write Fanout (Push Model)

At message send time, create a separate delivery event for each recipient:

```
Message sent to Group G (500 members):
  → Fanout Service reads chat_members for Group G
  → For each member: Redis lookup(gateway) + deliver or push notification
  → 500 operations per message

Cost: HIGH at send time
Latency: LOW for recipients (immediate delivery)
Best for: small groups (< 50 members), active conversations
```

### Read Fanout (Pull Model)

Store one copy of the message. Recipients fetch it when they open the chat:

```
Message sent to Group G:
  → Stored in Cassandra once
  → No per-member fanout
  → Recipients query Cassandra when they open chat

Cost: LOW at send time
Latency: ZERO additional (recipients read on demand)
Best for: large groups, inactive members, announcement channels
Downside: no real-time delivery — user must poll or be notified to refresh
```

### Hybrid Strategy (Production Recommendation)

```java
@Service
public class FanoutService {

    private static final int SMALL_GROUP_THRESHOLD = 50;

    public void fanoutMessage(ChatMessage message) {
        int memberCount = chatMemberService.getMemberCount(message.getChatId());

        if (memberCount <= SMALL_GROUP_THRESHOLD) {
            // Write fanout: push to each online member immediately
            writefanout(message);
        } else {
            // Read fanout: notify members that a new message exists
            // They'll fetch it from Cassandra when they open the chat
            notifyMembersNewMessage(message);
        }
    }

    private void writefanout(ChatMessage message) {
        // Load all members in this group (from Redis cache or Cassandra)
        List<String> memberIds = chatMemberCache.getMembers(message.getChatId());

        // Process in parallel — don't deliver to 500 members serially
        memberIds.parallelStream()
            .filter(memberId -> !memberId.equals(message.getSenderId())) // don't echo to sender
            .forEach(memberId -> {
                String gatewayHost = redis.opsForValue().get("user:" + memberId + ":gateway");

                if (gatewayHost != null) {
                    // User is online — deliver via WebSocket
                    gatewayClient.deliver(gatewayHost, memberId, message);
                } else {
                    // User is offline — send push notification
                    pushNotificationService.send(memberId, message);
                }
            });
    }

    private void notifyMembersNewMessage(ChatMessage message) {
        // For large groups: instead of delivering the full message, send a lightweight
        // notification: "new message in chat X, message_id Y"
        // Recipients fetch the actual message content from Cassandra
        // This reduces fanout bandwidth significantly (1KB notification vs full message)

        // Send push notification to offline members
        // Send WebSocket notification to online members to trigger a refetch
        List<String> onlineMembers = getOnlineMembersFromRedis(message.getChatId());
        for (String memberId : onlineMembers) {
            gatewayClient.sendNewMessageNotification(memberId, message.getChatId(), message.getMessageId());
        }
    }
}
```

### Fanout via Kafka (for Resilience)

For high-volume write fanout, Kafka provides a durable buffer:

```
Message arrives → Kafka topic: "messages" partition by chat_id
               → Fanout consumer group reads message
               → Per-member delivery published to Kafka topic: "deliveries" partition by user_id
               → Per-user delivery consumers push to WebSocket gateways

This adds ~50ms latency but provides:
  - Durability: messages not lost if fanout service crashes
  - Replayability: can re-deliver missed messages
  - Backpressure: if delivery is slow, Kafka buffers without dropping
```

---

## Presence System Design

### Heartbeat-Based Approach

```
Client sends heartbeat every 10 seconds:
  WebSocket frame: {type: "HEARTBEAT", timestamp: <epoch_ms>}

Server on heartbeat:
  1. SETEX user:{userId}:status "online" 30  // TTL=30s (3× heartbeat interval)
  2. SET user:{userId}:last_seen <epoch_ms>  // no TTL (permanent record)

If no heartbeat for 30s → key expires → user appears offline
```

Why 30s TTL with 10s heartbeat? The 3× factor provides tolerance for:
- One missed heartbeat (network blip): status remains "online"
- Two missed heartbeats: status remains "online"
- Three+ missed heartbeats: status expires → "offline"

### Presence Query API

```http
GET /presence?user_ids=userA,userB,userC

// Server:
// 1. Parse user_ids (max 100 per request)
// 2. Redis MGET [user:A:status, user:B:status, user:C:status]
// 3. For offline users, include last_seen timestamp
// Response:
{
  "users": [
    {"user_id": "A", "status": "online"},
    {"user_id": "B", "status": "offline", "last_seen": "2026-04-29T10:30:00Z"},
    {"user_id": "C", "status": "online"}
  ]
}
```

### Presence at Scale: The Fan-Out Problem

For users with 500 friends, a single user coming online would trigger:
```
500 friends need to be notified that User X is now online
500 Redis presence queries every time someone's status changes
→ 1M users × 500 friends × status_change_rate = enormous notification volume
```

**Solution: Lazy presence (pull on demand)**

Instead of pushing presence changes to all friends, clients poll when they need presence
information (e.g., opening a chat, viewing contact list). This shifts from push to pull:

```
PUSH (expensive): Server notifies all friends on status change
PULL (scalable): Client queries presence when opening a conversation

Optimization: Cache presence results client-side for 30s
→ Opening 10 chats with the same user doesn't trigger 10 Redis lookups
```

For messaging apps, presence is queried when:
1. Opening a chat (query that chat's participants' presence)
2. Viewing online members in a group
3. Composing a message (show typing indicator destination)

---

## Offline Delivery and Message Sync

When a user reconnects after being offline, they must receive all missed messages:

```java
@OnOpen
public void onConnect(WebSocketSession session, String userId) {
    sessionMap.put(userId, session);
    updatePresence(userId);

    // Load unread messages from Cassandra
    deliverOfflineMessages(userId, session);
}

private void deliverOfflineMessages(String userId, WebSocketSession session) {
    // Find all chats the user is a member of
    List<String> userChatIds = chatMemberService.getUserChatIds(userId);

    for (String chatId : userChatIds) {
        // Get user's last read position in this chat
        TIMEUUID lastReadMessageId = readReceiptService.getLastRead(userId, chatId);

        // Fetch all messages since lastRead from Cassandra
        // Use pagination if there are many unread messages
        List<Message> unreadMessages = messageService.getMessagesSince(chatId, lastReadMessageId, 100);

        if (!unreadMessages.isEmpty()) {
            // Send batch delivery to avoid multiple small frames
            BatchDeliveryMessage batch = new BatchDeliveryMessage(chatId, unreadMessages);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(batch)));
        }
    }
}
```

### The "Last Read" Pointer Problem

Each user has a "last read position" per chat. When a user opens a chat, messages from
their last read position to the latest are marked as unread:

```sql
-- In Cassandra: track per-user, per-chat last read message
UPDATE read_receipts
SET last_read_message_id = ?, updated_at = NOW()
WHERE chat_id = ? AND user_id = ?;

-- Unread count (approximation for inbox badge):
-- Option A: Store count in user_chats table (updated on new message + on read)
-- Option B: Compute from Cassandra (count messages since last_read_message_id)
-- Option A is faster at display time; Option B is always accurate
-- Recommendation: Option A with eventual consistency (count may be off by 1-2 during failure)
```

---

## Typing Indicators

Typing indicators are ephemeral — they must expire quickly and never be stored permanently:

```java
@OnMessage
public void onTypingStart(String userId, String chatId) {
    // Publish typing event to all chat members via WebSocket
    broadcastToChat(chatId, new TypingIndicatorMessage(userId, chatId, "STARTED"));

    // Set Redis key with 5s TTL — auto-expires if user stops typing without sending STOPPED
    redis.opsForValue().set("typing:" + chatId + ":" + userId, "1", Duration.ofSeconds(5));
}

@OnMessage
public void onTypingStop(String userId, String chatId) {
    redis.delete("typing:" + chatId + ":" + userId);
    broadcastToChat(chatId, new TypingIndicatorMessage(userId, chatId, "STOPPED"));
}

// Typing indicators are NOT stored in Cassandra — they are ephemeral state only
// If a gateway crashes while a user is typing, the TTL expiry handles cleanup
```

---

## Media Message Flow

Text messages are small enough to pass through WebSocket directly. Media requires a separate
upload path to avoid blocking the WebSocket connection:

```
User wants to send an image:

1. Client: POST /media/upload-url
   → Server generates pre-signed S3 URL (valid for 15 minutes)
   → Returns: { upload_url: "https://s3.../...", media_id: "med_abc" }

2. Client: PUT <upload_url> with image bytes (direct to S3, bypasses your servers)
   → S3 stores the image and triggers a Lambda to:
     a. Generate thumbnail (small preview for inbox)
     b. Validate content type (reject executables, malware scan)
     c. Mark media_id as UPLOADED in your DB

3. Client: WebSocket sends message:
   {type: "MESSAGE", content_type: "IMAGE", media_id: "med_abc", caption: "Look at this!"}
   → Your gateway validates that media_id is UPLOADED (prevents sending unprocessed/invalid media)
   → Message stored in Cassandra with media_url = "https://cdn.example.com/med_abc"
   → Recipients receive message, load image from CDN

4. CDN caches the image — subsequent loads are served from edge (low latency)
```

---

## Edge Cases and Failure Scenarios

### Scenario 1: Gateway Server Crashes Mid-Delivery

A gateway server crashes while holding 10,000 WebSocket connections.

**Impact**: All 10,000 connected users see their connection drop. Their WebSocket clients
immediately try to reconnect (with exponential backoff to prevent thundering herd).

**Recovery**: On reconnect, clients connect to a different gateway server (load balancer
distributes connections). The new gateway server reads their last-read position and delivers
missed messages from Cassandra. Redis heartbeat keys have expired (30s TTL), so these users
appear offline and any messages sent to them during the outage are queued as push notifications
or stored for sync-on-reconnect.

**Prevention**: Health checks detect unhealthy gateway pods; Kubernetes restarts them in < 30s.
Circuit breakers prevent routing to the crashed gateway during restart.

### Scenario 2: Cassandra Node Failure

One Cassandra node in a 3-replica cluster fails.

**Impact**: With replication factor 3 and consistency level QUORUM (2 of 3 nodes must respond),
reads and writes continue normally — two healthy nodes still form a quorum. If 2 of 3 nodes
fail (unlikely), reads and writes fail until nodes recover.

**Recovery**: Cassandra hinted handoff stores writes meant for the failed node on healthy nodes
for up to 3 hours. When the failed node recovers, it receives those hints and replays them.
Read repair catches any inconsistencies during normal reads.

### Scenario 3: Message Duplication on Client Retry

User A sends a message. The WebSocket frame is sent, but the connection drops before receiving
the SENT acknowledgment. User A's client reconnects and retries the message.

**Solution**: Client generates a `client_msg_id` (UUID) for each message. The server checks
this ID before processing: if a message with this `client_msg_id` from this sender already
exists in Cassandra, return the existing `server_msg_id` without creating a duplicate.

```java
// Before inserting, check for duplicate client_msg_id
SELECT message_id FROM messages_by_client_id
WHERE chat_id = ? AND sender_id = ? AND client_msg_id = ?;
// If found → return existing message_id
// If not found → insert new message
```

### Scenario 4: Clock Skew Creating Message Ordering Issues

Two users send messages within 5ms of each other on servers with 10ms clock skew.

**Impact**: Messages may appear in incorrect order in the UI.

**Mitigation**: Clients can apply a local ordering adjustment: if two messages arrive within
a 50ms window, sort them by `server_message_id` (TIMEUUID), which at least provides a
deterministic (if not perfectly clock-correct) ordering. The UI shows a consistent view
to all participants even if the exact order is ambiguous. For truly order-sensitive
applications (financial messaging), use Kafka sequence numbers per-partition (guarantees
total ordering within a partition).

### Scenario 5: Redis Failure (Presence and Routing Data Lost)

Redis goes down — presence data and gateway routing data are lost.

**Impact**: All users appear offline (no routing info). New messages cannot be delivered
via WebSocket (no routing). Push notifications still work (fallback to offline delivery path).

**Recovery**: Redis recovers within seconds (Sentinel or Cluster failover). WebSocket clients
reconnect (connection drops when they lose heartbeat acknowledgment). On reconnect, they
re-register their gateway routing key in the new Redis primary. Full recovery in < 60 seconds.

**Prevention**: Redis Cluster (3 shards × 2 replicas) provides fault tolerance for node failures.
Redis Sentinel (3 nodes) provides automatic failover for master failures. Redis persistence
(AOF) recovers recent writes after a full cluster restart.

---

## Scalability Table

| Concern | Solution | Why |
|---|---|---|
| 1M WebSocket connections | Horizontal gateway scaling, L4 load balancer | L4 preserves TCP connections; L7 terminates them |
| Message routing across gateways | Redis gateway-location lookup + internal HTTP | O(1) lookup, simpler than pub/sub for point-to-point |
| Group fanout | Hybrid write/read fanout by group size | Avoids 500-write-per-message at large scale |
| Message history at scale | Cassandra with time-bucketed partitions | Linear scalability, time-series read optimization |
| Media files | S3 + CDN | Offloads bandwidth from chat servers entirely |
| Offline delivery | Push notifications (FCM/APNS) | Native mobile delivery guarantee |
| Message search | Async index to Elasticsearch | Cassandra cannot do full-text search |
| Presence at scale | Lazy pull model with Redis TTL | Avoid push-to-all-friends on every status change |

---

## Tech Stack

| Component | Technology | Rationale |
|---|---|---|
| WebSocket gateway | Java + Netty (or Go) | Netty handles millions of connections with low thread count |
| Message store | Apache Cassandra 4 | Time-series partitioning, linear scale, tunable consistency |
| Presence + routing | Redis 7 Cluster | Sub-ms TTL operations, atomic SETEX for heartbeat |
| Message broker | Apache Kafka | Durable fanout, consumer group scaling, replay capability |
| Media storage | AWS S3 + CloudFront CDN | Object storage for large files; CDN for global low latency |
| Push notifications | FCM (Android) + APNS (iOS) | Platform-mandated push delivery systems |
| Search | Elasticsearch | Full-text search with relevance ranking |
| Service mesh | Kubernetes + Envoy | mTLS between services, traffic management, observability |

---

## Interview Q&A

### Q1: Why WebSocket instead of long polling? When would you choose long polling?

WebSocket provides true bidirectional communication over a single persistent TCP connection,
with frame overhead as low as 2 bytes vs. 500+ bytes for HTTP headers. For chat with
50k messages/second, this overhead difference translates to significant bandwidth savings.
More importantly, WebSocket allows the server to push messages at any time without the
client requesting them first — long polling requires the client to always have an outstanding
request, which creates reconnect gaps during which messages may be missed. Long polling is
an acceptable fallback for environments where WebSocket is blocked (some corporate firewalls,
older proxies), but for a greenfield chat system, WebSocket is the clear choice. Server-Sent
Events (SSE) is a good choice for one-way notifications (the server pushes updates to the
client, like a live feed) but cannot be used for bidirectional chat without a separate HTTP
POST channel for client-to-server messages, making the architecture more complex than WebSocket.

---

### Q2: Why Cassandra instead of MySQL or PostgreSQL for message storage?

At 4B messages/day accumulating over 5 years, the messages table would have ~7 trillion rows.
PostgreSQL can handle large tables with proper indexing, but the query `SELECT * FROM messages WHERE chat_id = X ORDER BY created_at DESC LIMIT 50` requires an index seek followed by
a sort operation, and vacuum/autovacuum becomes increasingly expensive. Cassandra's data model
eliminates this problem: the partition key `(chat_id, day_bucket)` physically co-locates all
messages for a chat on the same set of nodes, and the clustering key `message_id` (TIMEUUID)
stores them pre-sorted on disk. The query becomes a sequential read from the beginning of the
partition — no sorting, no index traversal. Cassandra also scales horizontally by simply adding
nodes, with data automatically redistributed using consistent hashing. The tradeoff is that
Cassandra does not support multi-partition queries, joins, or arbitrary WHERE clauses — you
must design your data model around your specific query patterns. For chat, the query patterns
are well-known and fixed, making Cassandra's limitations acceptable.

---

### Q3: How does message ordering work in a distributed system, and what are the tradeoffs between TIMEUUID and sequence numbers?

TIMEUUID provides approximately-correct ordering with no central coordinator: each server
generates a UUID incorporating its clock timestamp and MAC address. Messages from different
servers that arrive within the NTP clock skew window (typically < 10ms) may be ordered
slightly incorrectly. For chat, this is acceptable — users cannot perceive 10ms ordering
differences, and the ordering is at least deterministic (same order for all participants).
Sequence numbers per chat guarantee total ordering but require a centralized counter that
becomes a bottleneck: every message send is blocked waiting for a INCR operation from Redis
or a sequence table in the database. At 50k messages/second distributed across potentially
millions of active chats, this is 50k INCR operations/second — Redis can handle this, but
it adds latency and creates a single point of contention. For financial messaging or systems
where strict causal ordering is critical, sequence numbers are worth the complexity. For
consumer chat (WhatsApp, Slack), TIMEUUID is the pragmatic choice: globally unique without
coordination, approximately correct ordering, naturally integrates with Cassandra's
clustering column model.

---

### Q4: How would you design group chat fanout for a group with 1 million members (like a public channel)?

At 1 million members, write fanout is completely impractical — a single message would require
1 million delivery operations, at 50k messages/second this is 50 billion ops/second. The
read fanout model must be used: store one copy of the message in Cassandra, send a lightweight
notification ("new message in channel X, cursor position Y") to all online members, and let
them fetch the actual message content from Cassandra when they open the channel. Online member
detection at this scale requires a different mechanism than per-user Redis keys — instead, a
pub/sub system where gateway servers subscribe to channels they have members for, and message
events are published to that channel. Only gateways with active subscribers for that channel
receive the event, avoiding 1M Redis lookups. For truly massive channels (1M+ members), you'd
segment delivery: multiple Kafka partitions (one per user_id range), with consumers that handle
each partition's users. WebSocket delivery uses local session maps — each gateway only delivers
to its locally connected users. Push notifications for offline users are batched and rate-limited
to avoid overwhelming FCM/APNS.

---

### Q5: How does the presence system work at scale, and what are the failure modes?

The presence system uses a heartbeat with Redis TTL: each client sends a heartbeat every 10
seconds, and the server sets a Redis key with a 30-second TTL. If the TTL expires (no heartbeat
for 30s), the user appears offline. The main failure mode is false negatives: a user with a
slow network who misses two heartbeats appears temporarily offline even though they're connected.
This is mitigated by the 3× TTL factor (30s TTL for 10s heartbeat). Another failure mode is
Redis eviction: if Redis runs low on memory and evicts presence keys, online users appear offline.
This is prevented by setting `maxmemory-policy noeviction` on the presence Redis instance (or
using a dedicated Redis instance for presence with enough memory). At scale (1M online users),
the presence queries must be batched: `MGET user:A:status user:B:status user:C:status` for up to
100 users per call, rather than 100 individual GET calls. The lazy pull model (query presence
when opening a chat, not push on status change) prevents the thundering herd of presence updates
when a popular user with millions of followers changes status.

---

### Q6: How do you handle the scenario where a gateway server crashes with 10,000 active WebSocket connections?

When a gateway server crashes, all 10,000 WebSocket connections drop simultaneously. Clients
detect the connection drop via WebSocket close event or timeout (typically within 5-30 seconds
depending on TCP keepalive settings). Clients implement reconnect with exponential backoff and
jitter to prevent the 10,000 clients from all reconnecting to the same remaining server at
the same instant (thundering herd). The load balancer's health check removes the failed server
from the pool within 10-30 seconds (depending on health check interval). On reconnect, each
client re-authenticates and registers with the new gateway server. The new gateway server
fetches the client's last-read position from Cassandra and delivers missed messages. Redis
heartbeat keys for these users expire within 30 seconds, after which they appear offline —
messages sent to them during this window are queued as push notifications and also stored in
Cassandra for sync on reconnect. The system is designed so no messages are lost during a
gateway crash — Cassandra is the durable store, and the WebSocket path is the fast path.

---

### Q7: How would you implement message search across all conversations for a user?

Cassandra is not suitable for full-text search — it supports only primary key lookups and
range scans on clustering keys. Message search requires an inverted index over message content.
Elasticsearch is the standard solution: each message is asynchronously indexed into Elasticsearch
after being written to Cassandra. A Kafka consumer reads from the "messages" topic and indexes
each message into Elasticsearch with the fields: `chat_id`, `sender_id`, `content` (analyzed
for full-text), `created_at`, and the list of `participant_ids` (for access control filtering).
Search queries filter by `participant_ids` containing the requesting user (ensuring users can
only search their own conversations), then match on full-text content. The index lag (Cassandra
write → Kafka → Elasticsearch) means search results may be 1-5 seconds stale — acceptable for
search. Deleted messages must be removed from Elasticsearch index asynchronously when a deletion
event is published. For very large deployments, the Elasticsearch index is sharded by time
(separate indices per month) to enable efficient deletion of old data.

---

### Q8: What's your strategy for handling the "message not delivered" scenario when the push notification fails?

Push notification delivery is best-effort — FCM and APNS do not guarantee delivery if the
device is offline for an extended period or if the notification quota is exceeded. The
defense-in-depth strategy has three layers: First, push notification delivery is supplemental
to Cassandra storage — the message is always in Cassandra regardless of push delivery success.
When the user next opens the app, the client syncs from their last-read position in Cassandra.
Second, the client and server implement a message sequence number (or cursor): the client sends
its current cursor on reconnect, and the server delivers all messages after that cursor. This
ensures 100% delivery for users who reconnect within the Cassandra retention window (5 years).
Third, for CRITICAL messages (security alerts, 2FA codes), the push notification payload includes
the full message content so it's visible in the notification banner without opening the app —
reducing reliance on the app opening to see the message. The "unread count" badge on the app
icon serves as a persistent reminder that there are unread messages, prompting users to open the
app even if the push notification was missed.

---

### Q9: How would you implement read receipts at scale without creating a database write storm?

Naive read receipt implementation: every time a user reads a message, write a record to the
database. At 4B messages/day with 50% read receipt rate = 2B writes/day = 23k writes/second.
This is manageable but can be optimized. The key insight is that read receipts are not per-message
but per-user-per-chat: you only need to track the LAST message a user has read in each chat,
not a separate record for every message. This reduces 2B individual receipts to "update the
`last_read_message_id` for user X in chat Y" — one record per user per chat, updated on every
read action. In practice: batch these updates in the client (send a single "I've read up to
message M" update when the user closes the chat, not on every scroll event). Server-side,
write updates to Redis first (sub-ms) and flush to Cassandra asynchronously every 5 seconds.
The read receipt timestamp is the TIMEUUID of the last-read message, which provides both the
ordering position and the timestamp. For group chats, showing "read by 47 of 500" requires
aggregating last_read_message_ids for all members — this is expensive at read time, so maintain
a counter separately.

---

### Q10: How does your system handle users in different time zones for "last seen" timestamps?

The server stores `last_seen` as a Unix timestamp (UTC epoch milliseconds) — no timezone
information needed at storage time. The client is responsible for converting this to a
user-friendly representation in the viewer's local timezone. "Last seen 3 hours ago" is
computed as `now() - last_seen_timestamp`, which is timezone-independent. "Last seen today
at 3:30 PM" would use the viewer's timezone for the "today" and "3:30 PM" formatting, not
the target user's timezone. This design separates storage (UTC epoch, unambiguous) from
display (viewer's local timezone preference). The `last_seen` key in Redis has no TTL — it's
a permanent timestamp updated on each heartbeat. However, it's read infrequently (only when
displaying contact info), so memory consumption is minimal. Privacy controls should allow
users to disable last-seen sharing — in this case, the server returns null for `last_seen`
and the client displays "last seen recently" or hides the field entirely.

---

### Q11: How would you approach adding end-to-end encryption to this chat system?

End-to-end encryption (E2EE) means the server stores only ciphertext — it cannot read messages.
This is implemented using the Signal Protocol (used by WhatsApp and Signal): each user has a
long-term identity key pair and a set of pre-keys (one-time ephemeral keys) uploaded to the
server's key server. When User A wants to send an E2EE message to User B: A fetches one of
B's pre-keys from the key server, performs a Diffie-Hellman key exchange to derive a shared
session key, encrypts the message with this key, and sends the ciphertext. B uses their private
key (never leaves the device) to derive the same session key and decrypt. The server stores
only: the sender's user_id, the recipient's user_id, the ciphertext blob, and metadata
(timestamp, message_id). The server never has the plaintext. The implications for system design:
search is impossible (you can't full-text search encrypted content), media files must be
encrypted by the client before upload and decrypted after download, and if a user loses their
device (loses their private key), they lose access to their message history. Key management
(key backup, multi-device support) becomes a major product and engineering challenge. Most
consumer apps (Slack, Teams) choose not to use E2EE to preserve features like search and
admin visibility; consumer messaging apps (WhatsApp, iMessage) do because privacy is a core
product value.

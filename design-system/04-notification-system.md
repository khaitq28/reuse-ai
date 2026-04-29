# Notification System — Deep Design Guide

## Problem Statement

Design a notification system that sends messages to users via multiple channels: push notification,
email, and SMS. The system must handle a wide variety of event types (order updates, security alerts,
promotions), respect user preferences, guarantee delivery, deduplicate messages, and handle
priority-based routing at scale.

---

## Why This Problem Matters

Notification systems are deceptively hard. They look simple on the surface — "just send an email" —
but at scale they expose nearly every distributed systems challenge simultaneously:

- **Reliability**: A missed security alert (e.g., "unusual login detected") is a product failure.
  A duplicated promotional email erodes user trust and violates CAN-SPAM regulations.
- **Scale**: A single marketing blast to 50M users at 09:00 on a Monday creates an instant 10k+
  req/sec spike that must be absorbed without affecting critical transactional notifications.
- **Correctness**: Users who opted out of SMS must never receive SMS. Users in quiet hours must not
  be woken by a promotional ping. These are contractual and legal obligations, not just UX niceties.
- **Latency heterogeneity**: A 2FA code is useless if it arrives 60 seconds late. A weekly digest
  can wait minutes. The system must serve both use cases simultaneously.

**What interviewers are testing**: Whether you understand why async processing is not optional here,
how fanout works at scale, how you reason about delivery guarantees vs. duplication tradeoffs, and
whether you think about operational concerns like observability and failure recovery.

---

## Key Insight Before Diving In

**The notification system is fundamentally a fan-out pipeline with per-channel specialization.**

The core challenge is not "how do I send an email" — any library can do that. The challenge is:

1. **Decoupling**: The Order Service must never block waiting for FCM to acknowledge a push. If
   SendGrid is down, orders must still be processed. Async queues are the only solution.
2. **Channel heterogeneity**: Push, email, and SMS have completely different APIs, rate limits,
   payload formats, and delivery semantics. Each needs its own worker pool.
3. **Exactly-once is impossible; at-least-once is the goal**: In distributed systems, you cannot
   guarantee exactly-once delivery across network boundaries. You guarantee at-least-once and
   use deduplication to suppress duplicates at the receiver side.
4. **Priority must be enforced at the queue level, not the worker level**: If CRITICAL and LOW
   messages share a queue, a flood of LOW messages can starve CRITICAL ones. Separate queues
   with separate consumer pools is the only reliable solution.

---

## Requirements

### Functional
- Send notifications via: Push (iOS/Android), Email, SMS
- Support user preferences (opt-in/out per channel, per notification type)
- Support priority levels: CRITICAL, HIGH, NORMAL, LOW
- Retry failed notifications with exponential backoff
- Scheduled notifications (send at a future time)
- Template-based messages with variable substitution
- Quiet hours support (suppress non-critical notifications during sleep hours)
- Deduplication (no duplicate notifications within a time window)
- Delivery tracking (sent, delivered, failed, bounced)

### Non-Functional
- 1M notifications/day average, peak 10k/sec (promotional blasts)
- At-least-once delivery guarantee
- CRITICAL notifications must arrive < 5 seconds end-to-end
- Deduplication window: 24 hours
- 99.9% delivery rate for CRITICAL; 95% for NORMAL
- Audit log retained for 90 days

---

## Capacity Estimation

```
Daily volume: 1M notifications/day
Peak burst:   10,000/sec (marketing blast campaigns)

Channel breakdown (estimated):
  Push:  60% → 600k/day
  Email: 30% → 300k/day
  SMS:   10% → 100k/day

Storage per notification:
  Push payload:  ~1KB  → 600MB/day
  Email (HTML):  ~50KB → 15GB/day
  SMS:           ~0.5KB→ 50MB/day

Retention 90 days:
  Push:  600MB × 90  = ~54GB
  Email: 15GB  × 90  = ~1.35TB
  SMS:   50MB  × 90  = ~4.5GB
  Total: ~1.4TB (manageable with compression)

Dedup Redis keys:
  1M keys/day × 100 bytes/key = 100MB/day
  24h TTL → steady state ~100MB in Redis (trivial)
```

---

## High-Level Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                        Event Producers                             │
│  Order-Service  Payment-Service  User-Service  Auth-Service        │
└─────────────────────────┬──────────────────────────────────────────┘
                          │ REST / Kafka event
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Notification API Service                          │
│                                                                     │
│  POST /notifications                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  1. Validate request                                         │  │
│  │  2. Check dedup (Redis) — reject if duplicate                │  │
│  │  3. Load user preferences (Redis cache → DB fallback)        │  │
│  │  4. Apply quiet hours logic                                  │  │
│  │  5. Resolve template → render final message                  │  │
│  │  6. Persist to notifications table (status=PENDING)          │  │
│  │  7. Enqueue to appropriate priority queue                    │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────┬───────────────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          │               │               │
          ▼               ▼               ▼
  notifications    notifications    notifications
  .critical        .high            .normal / .low
  (10 consumers)   (5 consumers)    (3 / 1 consumers)
          │               │               │
          └───────────────┼───────────────┘
                          │ Each worker:
                          │  1. Read message from queue
                          │  2. Route to channel-specific sub-queue
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
    push.queue       email.queue      sms.queue
          │               │               │
    ┌─────▼─────┐  ┌──────▼─────┐  ┌────▼──────┐
    │Push Worker│  │Email Worker│  │SMS Worker │
    └─────┬─────┘  └──────┬─────┘  └────┬──────┘
          │               │               │
    ┌─────▼─────┐  ┌──────▼─────┐  ┌────▼──────┐
    │ FCM / APNS│  │SES/SendGrid│  │  Twilio   │
    └───────────┘  └────────────┘  └───────────┘
          │               │               │
          └───────────────┼───────────────┘
                          ▼
                  Delivery Status DB
                  (update notification status)
```

---

## Detailed Design

### Why Async is Mandatory

Consider a synchronous design where Order Service directly calls FCM and SES:

```
Order Service → FCM → SES → Twilio → return response to user
```

Problems:
1. **Latency addition**: FCM P99 = 500ms. SES P99 = 800ms. Twilio P99 = 1200ms. Total = 2.5 seconds
   added to every order placement. Unacceptable.
2. **Coupling**: If FCM is experiencing an outage, orders cannot be placed. A 3rd-party notification
   service should never be in the critical path of a core business operation.
3. **Retry complexity**: If FCM returns a 503, Order Service must implement retry logic — now every
   producer needs to understand notification delivery semantics.
4. **Fan-out**: When sending to 3 channels, you'd need parallel calls and partial failure handling
   in every producer. This logic belongs in one place.

The async pattern solves all of this:

```
Order Service → publish event to queue (< 5ms) → return to user
                           ↓ (asynchronously)
                  Notification Worker → FCM (with retry, backoff)
```

The queue acts as a buffer and decoupler. The Order Service's job ends when it puts the message
in the queue. Retry, backoff, priority, dead-letter handling — all live in the notification system.

---

### Notification API

```http
POST /notifications
Content-Type: application/json
Idempotency-Key: client-generated-uuid

{
  "user_id": "usr_abc123",
  "type": "ORDER_SHIPPED",
  "priority": "HIGH",
  "channels": ["PUSH", "EMAIL"],
  "template_id": "order-shipped-v2",
  "variables": {
    "order_id": "ORD-456",
    "tracking_number": "1Z999AA10123456784",
    "estimated_delivery": "2026-04-30"
  },
  "reference_id": "ORD-456",    // used for deduplication scoping
  "scheduled_at": null          // null = send immediately
}
```

Response (synchronous acknowledgment — not delivery confirmation):
```json
{
  "notification_id": "ntf_xyz789",
  "status": "ACCEPTED",
  "queued_at": "2026-04-29T10:00:00Z"
}
```

The `ACCEPTED` status means the message was accepted into the queue. Delivery status is tracked
asynchronously via the notifications table and webhooks.

---

### Template Engine

Templates are stored in the database and support Mustache-style variable substitution:

```
Template ID: order-shipped-v2
Channel: EMAIL
Subject:   "Your order {{order_id}} is on its way!"
Body (HTML):
  <h1>Great news!</h1>
  <p>Your order <strong>{{order_id}}</strong> has shipped.</p>
  <p>Tracking: <a href="{{tracking_url}}">{{tracking_number}}</a></p>
  <p>Expected delivery: {{estimated_delivery}}</p>
```

Template rendering happens in the Notification API before queuing, not in the worker. This is
a deliberate choice: workers should receive fully-rendered messages, not template IDs. Why?

1. **Worker simplicity**: Workers do not need access to the template database or rendering logic.
2. **Snapshot consistency**: If a template is updated between enqueuing and processing, workers
   would use the new template for old events. Pre-rendering captures the message at the time of
   the event.
3. **Debuggability**: The queue message contains exactly what was sent, making debugging easier.

```java
@Service
public class TemplateRenderer {

    // Renders template variables into the final message body
    // Uses a sandboxed Mustache engine — no code execution, only string substitution
    // This prevents SSTI (Server-Side Template Injection) attacks
    public RenderedMessage render(String templateId, Map<String, String> variables) {
        NotificationTemplate template = templateRepository.findById(templateId)
            .orElseThrow(() -> new TemplateNotFoundException(templateId));

        // Mustache.compile is thread-safe — safe to use in concurrent workers
        String subject = Mustache.compiler()
            .escapeHTML(true)           // prevent XSS in email subjects
            .compile(template.getSubject())
            .execute(variables);

        String body = Mustache.compiler()
            .escapeHTML(template.getChannel() == Channel.EMAIL) // email needs escaping
            .compile(template.getBody())
            .execute(variables);

        return new RenderedMessage(subject, body, template.getChannel());
    }
}
```

---

## Data Model

```sql
-- Core notifications table
-- Partitioned by created_at for efficient time-range queries and archival
CREATE TABLE notifications (
  id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID          NOT NULL,
  type            VARCHAR(100)  NOT NULL,             -- ORDER_SHIPPED, PAYMENT_FAILED, etc.
  priority        VARCHAR(20)   NOT NULL,             -- CRITICAL, HIGH, NORMAL, LOW
  status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
  -- PENDING → QUEUED → SENT → DELIVERED | FAILED | BOUNCED
  channel         VARCHAR(20)   NOT NULL,             -- PUSH, EMAIL, SMS
  template_id     VARCHAR(100),
  rendered_subject TEXT,                              -- pre-rendered at enqueue time
  rendered_body   TEXT,                              -- pre-rendered at enqueue time
  variables       JSONB,                              -- kept for audit/debugging
  reference_id    VARCHAR(255),                       -- business entity ID (order ID, etc.)
  idempotency_key VARCHAR(255)  UNIQUE,               -- prevents duplicate enqueuing
  scheduled_at    TIMESTAMPTZ,                        -- null = immediate
  sent_at         TIMESTAMPTZ,
  delivered_at    TIMESTAMPTZ,
  retry_count     INT           NOT NULL DEFAULT 0,
  next_retry_at   TIMESTAMPTZ,
  provider_ref    VARCHAR(255),                       -- FCM message_id, SES message_id, etc.
  error_message   TEXT,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (created_at);

-- User notification preferences
CREATE TABLE user_preferences (
  user_id         UUID          NOT NULL,
  channel         VARCHAR(20)   NOT NULL,
  notification_type VARCHAR(100),                    -- NULL = applies to all types
  enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
  quiet_start     TIME,                              -- e.g. 22:00 local time
  quiet_end       TIME,                              -- e.g. 08:00 local time
  timezone        VARCHAR(50)   DEFAULT 'UTC',       -- user's timezone for quiet hours
  updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, channel, notification_type)
);

-- Notification templates (versioned)
CREATE TABLE notification_templates (
  id              VARCHAR(100)  NOT NULL,
  version         INT           NOT NULL DEFAULT 1,
  channel         VARCHAR(20)   NOT NULL,
  subject_template TEXT,                             -- for email
  body_template   TEXT          NOT NULL,
  is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  PRIMARY KEY (id, version)
);

-- Deduplication tracking (could also live purely in Redis)
-- Kept in DB for long-term audit and for cases where Redis data is lost
CREATE TABLE notification_dedup (
  dedup_key       VARCHAR(255)  PRIMARY KEY,          -- hash of user+type+reference+window
  notification_id UUID          NOT NULL,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Device tokens for push notifications
CREATE TABLE user_devices (
  id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID          NOT NULL,
  platform        VARCHAR(10)   NOT NULL,            -- IOS, ANDROID
  device_token    TEXT          NOT NULL UNIQUE,
  is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
  last_used_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Indexes for common query patterns
CREATE INDEX idx_notifications_user_status ON notifications(user_id, status, created_at DESC);
CREATE INDEX idx_notifications_next_retry  ON notifications(next_retry_at) WHERE status = 'PENDING';
CREATE INDEX idx_notifications_scheduled   ON notifications(scheduled_at)  WHERE status = 'QUEUED';
```

---

## Retry and Reliability

### Why Exponential Backoff?

Naive retry (retry immediately on failure) creates thundering herd problems. If FCM is down and
100k push messages fail, retrying all immediately creates a spike that may keep FCM down. Exponential
backoff with jitter spreads the retry load over time:

```
Attempt 1: immediate (the original send)
Attempt 2: 30 seconds  (+/- 10% jitter) = 27-33 seconds
Attempt 3: 5 minutes   (+/- 10% jitter) = 4.5-5.5 minutes
Attempt 4: 30 minutes  (+/- 10% jitter) = 27-33 minutes
Attempt 5: 2 hours     (+/- 10% jitter) = 1.8-2.2 hours
→ After attempt 5: mark FAILED, alert ops, move to DLQ
```

Jitter formula:
```java
// Full jitter — most effective at preventing synchronized retries
// Spreads retries uniformly across the entire backoff window
long delayMs = (long)(Math.pow(2, attemptNumber) * BASE_DELAY_MS);
long jitter   = (long)(Math.random() * delayMs);
long retryAt  = System.currentTimeMillis() + jitter;
```

### Dead Letter Queue (DLQ) Design

```
Normal flow:    Queue → Worker → Provider
On max retry:   Queue → Worker → DLQ (dead.notifications)

DLQ contains:
  - Original message payload
  - All retry attempts with timestamps and error codes
  - Provider error responses

Operations team processes DLQ:
  1. Inspect error (invalid token? provider outage? bug?)
  2. Fix root cause
  3. Replay specific messages or entire DLQ batch
  4. Mark as permanently failed if unreplayable
```

```java
@Component
public class NotificationWorker {

    @RabbitListener(queues = "notifications.high")
    public void processNotification(NotificationMessage message) {
        try {
            sendToProvider(message);
            markDelivered(message.getNotificationId());

        } catch (ProviderTemporaryException e) {
            // Temporary failure (503, rate limit) — schedule retry
            if (message.getRetryCount() < MAX_RETRIES) {
                scheduleRetry(message, calculateBackoffDelay(message.getRetryCount()));
            } else {
                // Exhausted retries — send to DLQ
                sendToDlq(message, e.getMessage());
                markFailed(message.getNotificationId(), e.getMessage());
                alertOps(message); // page on-call for CRITICAL priority
            }

        } catch (ProviderPermanentException e) {
            // Permanent failure (invalid token, invalid email) — no retry
            // Example: FCM returns "registration-token-not-registered"
            deactivateDeviceToken(message.getDeviceToken());
            markFailed(message.getNotificationId(), e.getMessage());
        }
    }

    private long calculateBackoffDelay(int retryCount) {
        long baseDelay = BASE_DELAY_MS * (long) Math.pow(2, retryCount);
        long jitter    = (long) (Math.random() * baseDelay);
        return Math.min(jitter, MAX_DELAY_MS); // cap at 2 hours
    }
}
```

---

## Deduplication

### The Problem in Detail

Without deduplication, several scenarios cause duplicate notifications:

1. **Producer retry**: Order Service sends event to Notification API. Network times out. Order
   Service retries. Both events arrive. User gets 2 "order shipped" emails.
2. **Worker retry**: Worker sends to FCM. FCM returns 200 but the connection drops before worker
   receives it. Worker retries. FCM receives it twice → 2 push notifications.
3. **Queue re-delivery**: RabbitMQ delivers a message but the worker crashes before ACKing. RabbitMQ
   re-delivers. Second worker processes it again.

### Deduplication Strategy

```
Dedup key formula:
  dedup_key = SHA-256(user_id + ":" + notification_type + ":" + reference_id + ":" + time_window)

Time window for dedup:
  CRITICAL:  1 hour  (security alerts can repeat hourly if still relevant)
  HIGH:      24 hours
  NORMAL:    24 hours
  LOW:       7 days  (promotional messages should not repeat in a week)
```

```java
@Service
public class DeduplicationService {

    private final RedisTemplate<String, String> redis;
    private final DeduplicationRepository deduplicationRepo;

    public boolean isDuplicate(NotificationRequest request) {
        String dedupKey = buildDedupKey(request);

        // Fast path: check Redis first (in-memory, sub-millisecond)
        String existingId = redis.opsForValue().get("dedup:" + dedupKey);
        if (existingId != null) {
            log.info("Duplicate notification suppressed. Key={}, existingId={}", dedupKey, existingId);
            return true;
        }

        // Slow path: check DB (handles Redis restart/eviction scenario)
        // This is a belt-and-suspenders check — Redis should catch 99.9% of cases
        boolean existsInDb = deduplicationRepo.existsByKey(dedupKey);
        return existsInDb;
    }

    public void markSent(NotificationRequest request, UUID notificationId) {
        String dedupKey = buildDedupKey(request);
        Duration ttl = getDedupWindowForPriority(request.getPriority());

        // Write to both Redis and DB
        // Redis: fast lookup for current window
        // DB: audit trail and Redis-failure resilience
        redis.opsForValue().set("dedup:" + dedupKey, notificationId.toString(), ttl);
        deduplicationRepo.save(new DeduplicationRecord(dedupKey, notificationId));
    }

    private String buildDedupKey(NotificationRequest request) {
        // Time window bucket: rounds down to the nearest window boundary
        // This means a notification sent at 10:59 and one at 11:01 are NOT duplicates
        // (different 24h windows), which is intentional for daily notifications
        String windowBucket = getWindowBucket(request.getPriority());

        return Hashing.sha256().hashString(
            request.getUserId() + ":" +
            request.getType() + ":" +
            request.getReferenceId() + ":" +
            windowBucket,
            StandardCharsets.UTF_8
        ).toString();
    }
}
```

---

## Priority Queue Design

### Why Separate Queues, Not a Single Queue with Priority Field?

A single queue with a "priority" field does not work with most message brokers. Consumers pick
messages in order of arrival, not by priority field. A flood of LOW messages would still block
CRITICAL messages from being processed quickly.

Separate physical queues + dedicated consumer pools solve this:

```
Queue: notifications.critical   → 10 dedicated consumers (always available, never blocked)
Queue: notifications.high       →  5 dedicated consumers
Queue: notifications.normal     →  3 shared consumers
Queue: notifications.low        →  1 consumer (batch, can be paused during peak load)

Total consumers: 19 (each is a thread/process)
```

RabbitMQ configuration:
```yaml
# Each priority level is a separate exchange + queue
# Critical exchange: durable=true, no-expire
rabbitmq:
  exchanges:
    - name: notifications.critical
      type: direct
      durable: true
    - name: notifications.high
      type: direct
      durable: true
    - name: notifications.normal
      type: direct
      durable: true
    - name: notifications.low
      type: direct
      durable: true

  queues:
    - name: notifications.critical
      durable: true
      arguments:
        x-message-ttl: 300000    # 5 min — if not processed in 5min, something is wrong
        x-dead-letter-exchange: dlx.notifications
    - name: notifications.high
      durable: true
      arguments:
        x-message-ttl: 3600000  # 1 hour
        x-dead-letter-exchange: dlx.notifications
```

---

## Quiet Hours Handling

Quiet hours require timezone-aware logic. This is trickier than it looks:

```java
@Service
public class QuietHoursService {

    public DeliveryDecision shouldDeliver(NotificationRequest request, UserPreferences prefs) {
        // CRITICAL notifications always bypass quiet hours
        // A 2FA code or "your account is being accessed" cannot wait until morning
        if (request.getPriority() == Priority.CRITICAL) {
            return DeliveryDecision.DELIVER_NOW;
        }

        if (prefs.getQuietStart() == null || prefs.getQuietEnd() == null) {
            return DeliveryDecision.DELIVER_NOW; // no quiet hours configured
        }

        // Convert current time to user's local timezone
        ZoneId userZone = ZoneId.of(prefs.getTimezone());
        LocalTime userLocalTime = LocalTime.now(userZone);

        boolean inQuietHours = isInQuietHours(
            userLocalTime,
            prefs.getQuietStart(),
            prefs.getQuietEnd()
        );

        if (!inQuietHours) {
            return DeliveryDecision.DELIVER_NOW;
        }

        // Calculate when quiet hours end → schedule for that time
        // This preserves the notification rather than dropping it
        ZonedDateTime quietEnd = getNextQuietEnd(userZone, prefs.getQuietEnd());
        return DeliveryDecision.scheduleFor(quietEnd);
    }

    private boolean isInQuietHours(LocalTime current, LocalTime start, LocalTime end) {
        // Handle overnight quiet hours (e.g., 22:00 to 08:00)
        if (start.isAfter(end)) {
            // Overnight: quiet if current >= start OR current < end
            return !current.isBefore(start) || current.isBefore(end);
        } else {
            // Same-day: quiet if current is between start and end
            return !current.isBefore(start) && current.isBefore(end);
        }
    }
}
```

**Design decision**: Should notifications be dropped or rescheduled during quiet hours?

- **Drop**: Simple, but user misses a notification entirely. Bad for HIGH priority.
- **Reschedule**: Better UX — user wakes up to the notification. Requires a scheduler component.

Recommendation: Reschedule HIGH/NORMAL to quiet-hours-end. Drop LOW (promotional) if it would
be more than 8 hours old by the time quiet hours end (stale promotional content is noise).

---

## Fanout Strategy for Broadcast Notifications

Promotional blasts to millions of users require a different architecture:

```
Standard flow (1:1):
  API → single notification → queue → worker → provider

Blast/fanout flow (1:N):
  Marketing team → create campaign (template + targeting criteria)
                → Campaign Service queries user segments
                → Fan-out worker reads user IDs in batches of 1000
                → Publishes 1000 notifications per Kafka partition
                → Multiple consumers drain in parallel

Kafka partitioning for blast:
  Partition key = user_id % num_partitions
  → Evenly distributes load across all consumers
  → Single user's notifications are ordered (not critical for blasts but avoids confusion)
```

```
Campaign: "Black Friday Sale"
Target: 5M users (email opted in, last active < 90 days)

Timeline:
  T+0s:   Campaign Service queries user IDs → cursor-based pagination
  T+0s:   Publish to Kafka: partition 0..N, 5M messages in ~50 seconds
  T+50s:  Email workers start draining (50 workers × 100msg/sec = 5k/sec)
  T+17min: All 5M emails enqueued to SendGrid
  T+45min: All emails delivered (SendGrid's own fanout)

Rate limiting:
  SendGrid: 1M emails/hour per account
  → Use token bucket per provider: 1M tokens/3600s ≈ 278 tokens/sec
  → Multiple SendGrid accounts if volume exceeds single account limits
```

---

## Provider Rate Limiting

Each external provider has different rate limits. The system must honor them:

```java
@Component
public class ProviderRateLimiter {

    // Token bucket per provider, stored in Redis for distributed rate limiting
    // All worker instances share the same bucket
    private final Map<String, RateLimiter> buckets = Map.of(
        "fcm",      new RedisTokenBucket("rate:fcm",      600_000, 600_000), // 600k/min
        "apns",     new RedisTokenBucket("rate:apns",     500_000, 500_000),
        "sendgrid", new RedisTokenBucket("rate:sendgrid", 16_667,  16_667),  // 1M/hour
        "twilio",   new RedisTokenBucket("rate:twilio",   100,     100)      // 100/sec
    );

    // Called before each provider API call
    // Returns immediately if token available, blocks or throws if rate exceeded
    public void acquire(String provider) {
        RateLimiter limiter = buckets.get(provider);
        if (!limiter.tryAcquire()) {
            // Don't drop — re-enqueue with a delay
            throw new RateLimitExceededException(provider);
        }
    }
}
```

---

## Provider Failover

```java
// Each channel has a primary and fallback provider
// SMS example: Twilio (primary) → AWS SNS (fallback)
@Service
public class SmsProviderService {

    private final List<SmsProvider> providers = List.of(
        new TwilioProvider(),     // primary
        new AwsSnsProvider()      // fallback
    );

    public void send(SmsMessage message) {
        for (SmsProvider provider : providers) {
            try {
                provider.send(message);
                recordSuccess(provider.getName());
                return; // success — stop trying

            } catch (ProviderException e) {
                log.warn("Provider {} failed: {}", provider.getName(), e.getMessage());
                recordFailure(provider.getName());
                // try next provider
            }
        }
        // All providers failed
        throw new AllProvidersFailedException("SMS delivery failed for " + message.getTo());
    }
}
```

---

## Scheduled Notifications

For scheduled delivery (e.g., "send at 09:00 tomorrow"), two approaches:

**Option A: Database polling**
```sql
-- Scheduler runs every 30 seconds
SELECT * FROM notifications
WHERE status = 'SCHEDULED'
  AND scheduled_at <= NOW()
  AND scheduled_at > NOW() - INTERVAL '1 hour'  -- safety window
ORDER BY scheduled_at ASC
LIMIT 1000;
-- For each result: update status=QUEUED, publish to queue
```

**Option B: Delay queue (RabbitMQ TTL + DLX)**
```
Message → notifications.scheduled (x-message-ttl = delay_ms)
         → On TTL expiry → Dead Letter Exchange → notifications.high
```

Option A is recommended because:
- Easy to inspect scheduled notifications (just query the DB)
- Easy to cancel (update status=CANCELLED)
- No dependency on broker-specific features
- Works across restarts (DB is durable)

---

## Edge Cases and Failure Scenarios

### Scenario 1: FCM Token Expiry
When a user reinstalls the app, their FCM token changes. The old token becomes invalid.
FCM returns `registration-token-not-registered` error.

**Handling**: On this specific error code, mark the device token as inactive in `user_devices`.
Do NOT retry — it will never succeed. Send via other channels if available (email fallback).

### Scenario 2: User Unsubscribes During Processing
A user clicks "unsubscribe" on the email while a batch of emails is still in the queue.

**Handling**: Workers check user preferences at processing time (with Redis cache), not only at
enqueue time. The cache TTL for preferences should be short (5-60 minutes) to reflect recent
opt-outs quickly. CRITICAL notifications ignore opt-out preferences (security alerts, 2FA).

### Scenario 3: Template Rendering Failure
A variable is missing from the payload (e.g., template expects `{{tracking_url}}` but it's null).

**Handling**: Render at enqueue time in the API service. If rendering fails, reject the request
with 400 Bad Request immediately. Do not enqueue a partially-rendered message. This is why
pre-rendering (not worker-side rendering) is preferred.

### Scenario 4: Redis Failure (Preference Cache and Dedup Cache Down)
If Redis goes down, the dedup check falls through to the database (slower but correct). User
preference lookups also fall back to the database. The system degrades gracefully in performance
but maintains correctness. Brief Redis outages should not cause duplicate notifications to users.

### Scenario 5: Provider Partial Failure During Blast
SendGrid returns 429 (rate limit) during a 5M-user blast. Workers should not crash.

**Handling**: Catch 429 specifically, pause the consumer (or implement backpressure), and wait
for the rate limit window to reset. RabbitMQ's prefetch count ensures messages stay in the queue
rather than being fetched and dropped.

### Scenario 6: Time Zone Bug in Quiet Hours
User's timezone is set to "America/New_York" but DST transition happens overnight.

**Handling**: Always use `ZoneId` with IANA timezone names (not GMT offsets like "GMT-5").
Java's `ZonedDateTime` handles DST transitions correctly when using IANA zone IDs.
Never use raw offset-based zones for user-facing time calculations.

---

## Tech Stack

| Component | Technology | Rationale |
|---|---|---|
| API | Java 17 + Spring Boot | Mature ecosystem, strong typing for financial-adjacent code |
| Internal queue | RabbitMQ | Excellent priority queue support, per-message TTL, DLX |
| Blast fanout | Apache Kafka | Partitioned for parallel processing, durable, replay |
| Push (Android) | Firebase FCM | Google's official service, 600k/min throughput |
| Push (iOS) | Apple APNS | Required for iOS — no alternative |
| Email primary | AWS SES | Cost-effective at scale (~$0.10/1k emails), high deliverability |
| Email fallback | SendGrid | Good deliverability tools, easy IP warm-up |
| SMS | Twilio | Global coverage, reliable API |
| SMS fallback | AWS SNS | Cost-effective for high volume |
| Cache | Redis 7 | Sub-ms latency, TTL support, atomic operations for dedup |
| Preferences DB | PostgreSQL | ACID, relational structure for user preferences |
| Notification DB | PostgreSQL | Partitioned tables for efficient archival |
| Scheduler | Spring Quartz + PostgreSQL job store | Clustered, survives restarts |
| Monitoring | Prometheus + Grafana | Delivery rate, latency, provider health |
| Alerting | PagerDuty | On-call alerts for DLQ growth, CRITICAL delivery failures |

---

## Observability

### Key Metrics

```
# Delivery success rate by channel and priority
notification_delivery_success_total{channel, priority}
notification_delivery_failure_total{channel, priority, error_type}

# Queue depth by priority (alert if critical queue depth > 100)
notification_queue_depth{priority}

# End-to-end latency (time from API acceptance to provider delivery)
notification_e2e_latency_seconds{channel, priority, quantile}

# Provider-specific metrics
provider_api_latency_seconds{provider}
provider_rate_limit_hits_total{provider}
provider_error_rate{provider, status_code}

# Deduplication
notification_dedup_suppressed_total{notification_type}
```

### Alerting Rules

```yaml
- alert: CriticalNotificationQueueDepth
  expr: notification_queue_depth{priority="critical"} > 100
  for: 30s
  severity: page   # wake up on-call

- alert: HighDeliveryFailureRate
  expr: rate(notification_delivery_failure_total[5m]) / rate(notification_delivery_success_total[5m]) > 0.05
  for: 2m
  severity: warning

- alert: DLQGrowing
  expr: notification_dlq_depth > 50
  for: 5m
  severity: warning
```

---

## Interview Q&A

### Q1: Why is async processing mandatory for a notification system? Can't you just call the provider APIs synchronously?

Synchronous provider calls would add 500ms–2s to every user request that triggers a notification,
because external APIs (FCM, SES, Twilio) have significant latency with high tail latencies. More
critically, it creates a hard coupling between your application's availability and the provider's
availability — if Twilio is experiencing an outage, users cannot complete orders. Synchronous
calls also make retry logic the responsibility of every producer service, duplicating complex
retry/backoff code across the codebase. The async queue pattern decouples the producer's
acknowledgment (fast) from the actual delivery (slower, with retries). The queue also acts as
a buffer absorbing traffic spikes — a promotional blast that generates 10k notifications/second
is smoothed over minutes rather than hitting providers simultaneously.

---

### Q2: How would you ensure at-least-once delivery while avoiding duplicates?

At-least-once delivery is achieved through durable queues (messages survive broker restart),
consumer ACK patterns (message stays in queue until explicitly acknowledged after successful
delivery), and retry-to-DLQ logic (max retries before moving to dead-letter). Deduplication
is the complementary mechanism that suppresses the duplicates that at-least-once inherently
produces. The dedup key is a hash of (user_id + notification_type + reference_id + time_window),
stored in Redis with a TTL matching the dedup window. When a message is re-delivered due to
a consumer crash or network issue, the second attempt sees the key in Redis and drops silently.
The critical insight is that dedup keys must survive longer than the maximum retry interval
(e.g., if max retry is 2 hours, dedup TTL must be at least 3 hours). For truly idempotent
delivery, you'd also check on the provider side — FCM supports deduplication via the
`collapse_key` parameter, grouping multiple notifications into one delivery.

---

### Q3: How do you design the priority queue system so that CRITICAL notifications are never delayed by LOW priority messages?

Use separate physical queues — one per priority level — with dedicated consumer groups for each.
This is not just configuration but a fundamental design choice: a single queue with a priority
field relies on the broker supporting message priorities (RabbitMQ does, but with limitations)
and does not guarantee that CRITICAL messages jump ahead of already-consumed LOW messages.
Dedicated queues with dedicated consumers mean CRITICAL consumers are never competing with LOW
consumers for processing slots. Scaling is also independent: during a marketing blast that floods
the LOW queue, you can scale LOW consumers independently without touching CRITICAL capacity.
Additionally, monitoring is clearer — a growing CRITICAL queue depth immediately triggers an
alert, whereas in a mixed queue, depth alone does not reveal which priority is backed up.

---

### Q4: How do you handle quiet hours across different time zones for millions of users?

Each user's preferences store their IANA timezone identifier (e.g., "America/New_York"), their
quiet start time, and their quiet end time as local times. At delivery time, the system converts
the current UTC time to the user's local time using `ZonedDateTime` with the IANA zone ID, then
checks whether the local time falls within the quiet window. The overnight case (22:00–08:00)
requires special handling: if start > end, then "in quiet hours" means current >= start OR
current < end. Notifications that fall into quiet hours are rescheduled to the moment quiet hours
end (not dropped), except for CRITICAL priority which bypasses quiet hours entirely (a security
alert cannot wait until morning), and LOW/promotional which may be dropped if the scheduled
delivery time would make the content stale. The preference cache in Redis should have a short
TTL (5–15 minutes) so that a user who changes their quiet hours setting sees the change take
effect quickly without requiring a cache flush operation.

---

### Q5: How would you design deduplication for a "daily digest" notification vs. a "2FA code" notification?

These require fundamentally different deduplication windows. A 2FA code must be deduplicated
over a very short window (30 seconds or less) because a second code request a minute later is
a legitimate, new code request. A daily digest should be deduplicated over 24 hours (one digest
per day). The dedup key design must encode the time window: for 2FA, the window bucket is the
current minute; for daily digest, the window bucket is the current calendar date in the user's
timezone. The dedup TTL must exceed the window size — if the 24h window expires before the TTL,
a second digest could slip through. A subtle edge case: if the deduplication key hash collides
for two different users (birthday problem at scale), they both get blocked. Using SHA-256 with
user_id as part of the key makes collision probability astronomically low (2^-128). For CRITICAL
notifications like security alerts, deduplication should be applied only within a short window
(1 hour) because the alert may still be relevant 2 hours later and should re-trigger.

---

### Q6: Walk me through what happens when your email provider (SES) goes down for 30 minutes.

When SES returns 5xx errors, the Email Worker catches a `ProviderTemporaryException`. The
message is not ACKed in the queue, so it stays available for reprocessing. The worker applies
exponential backoff — first retry at 30 seconds, then 5 minutes, etc. During the 30-minute
outage, messages accumulate in the email queue rather than being lost. The circuit breaker
monitoring SES error rate trips to OPEN state after seeing >50% failure rate, causing the
worker to fail fast without even attempting SES calls (this preserves worker thread capacity).
When SES recovers, the circuit breaker transitions to HALF-OPEN, lets one test request through,
sees success, and closes. The queue drains normally. The key design requirement here is that
the email queue must be durable (messages survive broker restart), consumer prefetch must be
tuned so unprocessable messages don't block the entire queue, and the fallback provider
(Mailgun/SendGrid) should be configured to absorb overflow for HIGH/CRITICAL priority emails.
LOW priority emails can simply wait for SES to recover.

---

### Q7: How would you implement provider fallback — e.g., if FCM push fails, send an SMS instead?

Provider fallback is different from channel fallback. Provider fallback (FCM → APNS — not
applicable since they're platform-specific) is about redundancy at the same channel. Channel
fallback (push fails → SMS) is a business policy decision that varies by notification type.
The implementation uses a fallback chain defined per notification type in configuration:
ORDER_SHIPPED: [PUSH, EMAIL] — if push fails permanently (invalid token), automatically
create an email notification. SECURITY_ALERT: [PUSH, EMAIL, SMS] — escalate until delivered.
The worker publishes a new notification event with the fallback channel after detecting a
permanent failure (not a temporary one — you don't want to send both push and email just
because FCM had a 5-second hiccup). The fallback event goes through the same dedup check
but with the new channel encoded in the key, so a push + email combo doesn't trigger false
deduplication suppression.

---

### Q8: How do you handle a 5-million-user marketing blast without overloading your system or providers?

The blast goes through a Campaign Service that queues messages gradually using a controlled
fanout rate. Instead of publishing 5M messages simultaneously, the Campaign Service reads
user IDs in cursor-paginated batches of 1000 and publishes each batch, respecting a token
bucket rate limiter keyed to the provider's throughput limit (e.g., SES: 1M/hour = 278/sec).
The LOW priority queue handles blast traffic so it never competes with transactional CRITICAL
or HIGH notifications. The blast can also be spread over a time window (e.g., "deliver between
09:00 and 11:00 in each user's local timezone") which both improves UX and naturally distributes
load. Monitoring watches queue depth and delivery rate; if the LOW queue grows beyond a threshold,
the campaign rate limiter automatically backs off. Database writes during blasts are batched
(bulk insert notifications 1000 at a time) rather than individual INSERTs to prevent DB overload.
At the provider level, multiple SendGrid sub-accounts or dedicated IPs are used to achieve
higher throughput than a single account allows.

---

### Q9: What are the tradeoffs between pre-rendering templates at enqueue time vs. rendering at worker time?

**Pre-rendering at enqueue time (recommended)**:
- Pros: Workers are simpler (no template DB access), message in queue is self-contained, rendering
  failure is caught immediately with a 400 response to the producer, the rendered content reflects
  the state of the world at event time (important for time-sensitive messages like "your order
  shipped at 10:30 AM").
- Cons: Queue messages are larger (full rendered HTML vs. template reference), template changes
  do not propagate to already-queued messages (a minor version fix requires draining the queue).

**Rendering at worker time**:
- Pros: Smaller queue messages, template updates apply to all pending messages.
- Cons: Workers need access to template DB, rendering failure in a worker causes retry loops
  (the message cannot be processed regardless of how many times it's retried), renders the
  content at delivery time rather than event time (could show "shipped at X" where X is stale).

The pre-rendering approach wins for production systems because it makes debugging dramatically
easier (the queue message shows exactly what was sent) and prevents a class of bugs where a
worker retry loop is caused by a data issue rather than a transient failure.

---

### Q10: How would you track delivery status and handle bounced emails?

Delivery status tracking uses a combination of provider webhooks and polling. For email,
SES/SendGrid send webhook events to a dedicated webhook endpoint: `delivered`, `bounce`,
`complaint`, `open`, `click`. The webhook handler updates the notification record status.
Hard bounces (invalid email address) permanently deactivate the email address in the user
profile to prevent future sends (sending to hard-bounced addresses damages your sender
reputation). Soft bounces (mailbox full, temporary) trigger a retry. For push, FCM and
APNS provide delivery receipts in their callback responses; invalid tokens trigger device
deactivation. For SMS, Twilio's status callback webhook provides delivery confirmation.
The `provider_ref` column stores the external message ID (e.g., FCM's `message_id`,
SES's `MessageId`) which can be used to query provider status directly if a webhook is
missed. A nightly reconciliation job queries the provider APIs for messages where the
notification record shows `SENT` but not `DELIVERED` after 24 hours — these are
investigated or marked as delivery-failed.

---

### Q11: How would you scale this system to handle 100M notifications per day?

Horizontal scaling at every layer. The Notification API is stateless — add instances behind
a load balancer. Kafka replaces RabbitMQ for the blast queue: Kafka's log-based storage
allows thousands of consumer instances to read independently, and its consumer group mechanism
auto-rebalances partitions as workers scale up/down. Push workers scale to hundreds of
instances against FCM (which itself handles millions of req/sec). Email workers are rate-limited
by provider quotas, so multiple provider accounts are needed — domain verification for each.
The PostgreSQL notification table is partitioned by month with older partitions moved to
cheap S3-backed storage (e.g., AWS Aurora + S3 tiering). Redis scales via Redis Cluster
for the dedup store. At 100M/day, the dedup Redis holds ~100M keys × 50 bytes = 5GB — well
within a Redis Cluster's capacity. The template rendering could become a bottleneck if
templates are complex HTML; caching compiled templates in JVM memory (with cache invalidation
on template version change) prevents recompilation overhead at high throughput.

---

### Q12: What would you monitor and alert on in production to ensure the notification system is healthy?

The most important metric is the end-to-end delivery rate: what percentage of CRITICAL
notifications are delivered within 5 seconds? Track this as a P99 latency metric per
channel and priority, and alert immediately if CRITICAL delivery P99 exceeds 10 seconds.
Queue depth per priority level is a leading indicator of problems — CRITICAL queue depth
above 100 should page on-call. Dead-letter queue growth is another critical signal: messages
in the DLQ indicate a systematic failure (provider outage, schema mismatch, bug). Provider
health metrics (error rate by provider, rate limit hits) identify which external dependency
is failing. Deduplication suppression rate identifies whether there is a producer bug causing
excessive duplicates. Bounce rate and complaint rate for email monitor sender reputation
(high complaint rates get your domain blacklisted). Worker lag (difference between message
enqueue time and processing time) catches slow consumers. A synthetic canary notification
sent every minute through the full stack provides an active health check of the entire pipeline.

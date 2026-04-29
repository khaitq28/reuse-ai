# Distributed Message Queue

## Problem Statement
Design a distributed message queue like Kafka or RabbitMQ — a system that decouples producers and consumers, enabling async communication, buffering, and reliable event delivery at scale.

---

## Why This Problem Matters

Message queues are the circulatory system of distributed architectures. Almost every large-scale system uses one — for decoupling services, for absorbing traffic spikes, for enabling event-driven processing, and for making asynchronous workflows reliable. Interviewers ask this question to probe whether you understand the fundamental properties that make a message queue trustworthy at scale.

What interviewers are really testing:
- Do you understand **why append-only logs are the key architectural insight** behind Kafka's performance? (Most candidates say "Kafka is fast" without knowing why.)
- Can you reason about **delivery guarantees** (at-most-once, at-least-once, exactly-once) and their implementation trade-offs?
- Do you understand **partition key design** and its implications for ordering and parallelism?
- Can you explain **consumer group rebalancing** and why it causes processing pauses?
- Do you know when to use **Kafka vs. RabbitMQ** and what each sacrifices for its strengths?
- Can you design a complete **Dead Letter Queue** strategy including replay and alerting?

The failure modes of message queues are subtle and system-breaking: message loss, message duplication, ordering violations, consumer lag, poison pills that halt processing. A senior engineer must be able to reason about all of these proactively.

---

## Key Insight Before Diving In

**The genius insight behind Kafka is that sequential disk writes are faster than random memory writes.**

This is counter-intuitive. We assume disk is slow and memory is fast. But that's only true for *random* access. A rotating hard disk doing sequential writes achieves 100-200 MB/s. A disk doing random writes achieves 1-2 MB/s. An append-only log by definition only does sequential writes — every message appended at the end. This is why Kafka can sustain millions of messages per second on commodity hardware while maintaining durability.

The second insight: **consumers track their own position (offset)**. Traditional message queues track what's been delivered — complex, stateful, expensive. Kafka brokers don't track what consumers have read; consumers track their own offset and commit it periodically. This makes brokers stateless with respect to consumption, enabling multiple independent consumer groups to read the same data simultaneously without any coordination overhead.

---

## Requirements

### Functional
- Producers publish messages to named topics/queues
- Consumers subscribe to topics and process messages
- At-least-once delivery guarantee (configurable to exactly-once)
- Message ordering within a partition
- Consumer groups: multiple consumers share partition load
- Message replay: re-read from any offset
- Topic partitioning for parallelism
- Message retention with configurable TTL

### Non-Functional
- 1M messages/sec throughput
- < 10ms publish latency (P99)
- Messages retained for 7 days
- Handle consumer lag without data loss (producers never block)
- Horizontally scalable
- No message loss for durability-critical topics

---

## Capacity Estimation

```
Throughput:
  1M msg/sec × 1KB avg = 1GB/sec write throughput
  3 replicas → 3GB/sec total disk write bandwidth
  → Requires fast SSDs or distributed storage

Storage:
  1GB/sec × 86,400 sec/day × 7 days = 604TB for 7-day retention
  With 2:1 compression (LZ4/SNAPPY): ~300TB
  → 10-node cluster, 30TB each (feasible with modern hardware)

Consumer throughput:
  10 consumer groups × same 1M msg/sec = 10GB/sec total read
  Consumers mostly read from page cache (RAM) not disk
  → OS page cache absorbs most reads

Partition sizing:
  1 partition handles ~1MB/sec write throughput
  1M msg/sec × 1KB = 1GB/sec → need 1000 partitions minimum
  With 20% buffer: 1200 partitions across 10 brokers = 120 partitions/broker
```

---

## Core Concepts — The Mental Model

```
Topic:
  Logical channel for a category of events (e.g., "orders", "payments")
  Think of it as a named stream, not a database table

Partition:
  Physical append-only log file on a broker's disk
  A topic is divided into N partitions, each on one broker
  Ordering is guaranteed WITHIN a partition, not across partitions
  Parallelism = number of partitions (up to partition count consumers can run in parallel)

Offset:
  Zero-indexed sequential position of a message in a partition
  Immutable: once a message is at offset 42, it stays at offset 42 forever (until retention expires)
  Consumer reads by saying "give me messages starting at offset 42"

Consumer Group:
  Logical subscriber that collectively processes a topic
  Each partition assigned to exactly ONE consumer in the group at a time
  Multiple groups can independently consume the same topic simultaneously (e.g., billing and analytics groups both read "orders")

Broker:
  Server that stores partitions and serves producers/consumers
  Each partition has one leader broker (handles reads/writes) and N-1 follower brokers (replicas)

Controller:
  One broker elected as cluster controller (manages partition leadership, broker membership)
  In modern Kafka (KRaft mode), ZooKeeper replaced by internal Raft consensus
```

---

## High-Level Architecture

```
                        Producer Side
┌──────────┐     ┌──────────────────────────────────┐
│ Producer │────►│    KafkaProducer (client library) │
│  App 1   │     │  - Serialization (Avro/Protobuf)  │
│  App 2   │     │  - Partitioning (key hash)        │
│  App 3   │     │  - Batching (linger.ms=5)         │
└──────────┘     │  - Compression (LZ4)              │
                 │  - Retry with backoff              │
                 └──────────────┬───────────────────┘
                                │
                       Broker Cluster
              ┌─────────────────┴──────────────────────┐
              │                                        │
       ┌──────┴───────┐  ┌────────────┐  ┌───────────┐│
       │   Broker 1   │  │  Broker 2  │  │  Broker 3 ││
       │  Leader: P0  │  │ Leader: P1 │  │Leader: P2 ││
       │  Leader: P3  │  │ Leader: P4 │  │Leader: P5 ││
       │  Follow: P1  │  │ Follow: P2 │  │Follow: P0 ││
       │  Follow: P5  │  │ Follow: P3 │  │Follow: P4 ││
       └──────────────┘  └────────────┘  └───────────┘│
              │                                        │
              │      Controller: manages metadata,     │
              │      leader elections via KRaft        │
              └────────────────────────────────────────┘
                                │
                        Consumer Side
             ┌──────────────────┴───────────────────┐
             │         Consumer Group A              │
             │  Consumer 1 → P0, P1                 │
             │  Consumer 2 → P2, P3                 │
             │  Consumer 3 → P4, P5                 │
             └───────────────────────────────────────┘
             ┌──────────────────────────────────────┐
             │         Consumer Group B             │
             │  (independent — reads same partitions│
             │   from its own committed offsets)    │
             └──────────────────────────────────────┘
```

---

## Message Storage: The Append-Only Log — Why It's Genius

This is the most important concept to understand deeply. The append-only log is not a data structure choice — it's a fundamental paradigm shift.

```
Traditional message queue approach (RabbitMQ / ActiveMQ):
  - Messages stored in a tree-structured database (BTree)
  - Each message tracked: who received it, was it acked?
  - Delivered messages deleted (consuming = deleting)
  - Multiple consumers → complex locking for "who gets this message"
  - B-tree random writes: 1-5 MB/sec
  - State grows complex as messages are acknowledged out of order

Kafka append-only log approach:
  - Messages written to END of file, always sequential
  - File offset = message position (immutable, forever)
  - Sequential disk writes: 100-200 MB/sec (100x faster!)
  - Consumers read by offset — broker doesn't need to track this
  - "Consuming" does NOT delete the message — it stays on disk
  - Multiple consumer groups each have their own offset tracker
  - Retention by time/size, not by consumption
```

```
Partition 0 log file (physical file on disk):
  ┌──────────────────────────────────────────────────────────┐
  │ offset=0 │ offset=1 │ offset=2 │ offset=3 │ offset=4 │  │
  │ msg_A    │ msg_B    │ msg_C    │ msg_D    │ msg_E    │  │
  └──────────────────────────────────────────────────────────┘
                                               ▲ append here

Consumer Group A: committed offset = 2 (processed msg_A, B, C)
  → next read: offset 3 (msg_D, msg_E)

Consumer Group B: committed offset = 0 (only processed msg_A)
  → next read: offset 1 (msg_B, C, D, E)

Both groups read the SAME partition independently.
The broker just serves sequential bytes — no per-consumer state.
```

**Segment files — the retention mechanism:**

```
Instead of one giant file, Kafka uses rolling segment files:
  partition-0/
    00000000000000000000.log  (offsets 0 - 999,999)
    00000000000000000000.index
    00000000000001000000.log  (offsets 1,000,000 - 1,999,999)
    00000000000001000000.index
    00000000000002000000.log  (current, actively written)

.index file: sparse offset index
  → Maps every Nth offset to byte position in .log file
  → Consumer asking for offset 1,500,000 → binary search index → jump to byte N → read sequentially

Retention:
  log.retention.hours=168 (7 days)
  → Kafka background thread deletes old .log files when age > retention
  → Segment-based deletion: entire segment deleted at once (not message-by-message)
  → This is why "delete old messages" is O(1) — just remove a file
```

**Zero-copy reads — the performance multiplier:**

```
Without zero-copy (traditional approach):
  1. Kernel reads file from disk into kernel buffer (1st copy)
  2. Kernel copies buffer to user space (2nd copy, expensive context switch)
  3. Application code processes buffer
  4. Application asks to send over network
  5. OS copies from user space back to kernel socket buffer (3rd copy)
  6. NIC sends from kernel buffer (4th copy)

With zero-copy (Kafka uses sendfile() syscall):
  1. Kernel reads file from disk into kernel buffer (1st copy)
  2. Kernel transfers directly from kernel buffer to NIC (2nd copy, stays in kernel)
  No user-space involvement, no context switches
  → 2-4x throughput improvement for large sequential reads
  → Consumer fetch is almost entirely zero-copy disk → network
```

---

## Message Format (Wire Protocol)

Understanding the wire format explains why Kafka is efficient at large batches:

```
Message Batch (RecordBatch in Kafka 0.11+):
┌─────────────────────────────────────────────────────────────────┐
│ BaseOffset (8B)     │ BatchLength (4B)    │ PartitionLeaderEpoch│
│ Magic (1B, =2)      │ Attributes (2B)     │ LastOffsetDelta (4B)│
│ FirstTimestamp (8B) │ MaxTimestamp (8B)   │ ProducerId (8B)     │
│ ProducerEpoch (2B)  │ BaseSequence (4B)   │ RecordCount (4B)    │
│ Records[]:                                                       │
│   Length (varint)   │ Attributes (1B)     │ TimestampDelta      │
│   OffsetDelta       │ KeyLength (varint)  │ Key (bytes)         │
│   ValueLength       │ Value (bytes)       │ Headers[]           │
└─────────────────────────────────────────────────────────────────┘

Key points:
- Offsets stored as DELTA from batch base offset (varint compression)
- Timestamps stored as DELTA (varint compression)
- Entire batch CRC'd once (not per-message) → cheaper to verify
- ProducerId + BaseSequence → enables idempotent producer (exactly-once)
- Compression applied to entire batch, not individual messages
  → Batch compression ratio much better than per-message (redundancy across messages)
```

---

## Partitioning Strategy — Design Decisions with Consequences

Partition key selection is one of the most critical and irreversible decisions in Kafka design:

```java
// The default partitioner: consistent hash of key mod partition count
// Kafka uses MurmurHash2 for uniform distribution
int partition = Math.abs(Utils.murmur2(key.getBytes())) % numPartitions;

// If key is null: round-robin across partitions (good for throughput, bad for ordering)
// Kafka 2.4+: sticky partitioning (batches to one partition before round-robin)
```

**Scenario 1: Order events (need per-order ordering)**
```java
// Partition by order_id → all events for order 123 go to same partition → ordered
// Good: order state machine is always consistent
// Risk: hot orders? If one orderId gets 1M events/sec → one partition becomes hot
producer.send(new ProducerRecord<>("orders", orderId.toString(), orderEvent));
```

**Scenario 2: User events (need per-user ordering)**
```java
// Partition by user_id → all events for user 456 go to same partition
// Good: user activity stream is ordered, easy to build user session views
// Risk: "whale" users with massive activity → hot partition
// Mitigation: hash(userId + date) to spread whale user's events across days
producer.send(new ProducerRecord<>("user-events", userId.toString(), event));
```

**Scenario 3: Geographic distribution**
```java
// Partition by region → regional consumers process their own region's data
// Good: data locality for compliance (GDPR data stays in EU partitions)
// Risk: uneven partition load if regions have different traffic volumes
// Risk: adding new regions requires repartitioning (painful — see below)
producer.send(new ProducerRecord<>("events", region, event));
```

**The repartitioning problem:**
```
You can INCREASE partition count but CANNOT decrease it.
Increasing partition count changes the hash → same key goes to different partition.
All existing messages stay in their old partitions.
This breaks the ordering guarantee for existing keys!

Solutions:
1. Start with more partitions than you need (overprovision — Kafka recommends 3x)
2. Use a custom partitioner that abstracts the logical partition from physical
3. Drain and recreate the topic (requires coordinating all producers and consumers)

Rule of thumb: provision 2-3x your current max throughput need.
```

---

## Replication and Durability

```
Replication factor = 3 (standard recommendation)
  Leader: 1 broker handles all reads and writes for a partition
  ISR (In-Sync Replicas): followers within max.replication.lag.time.ms of leader

Replication flow:
  Producer → Leader (write to local log)
  Leader → Follower 1 (async pull-based replication)
  Leader → Follower 2 (async pull-based replication)
  ISR = set of replicas caught up with leader

Leader election:
  If leader crashes → controller picks new leader from ISR
  Non-ISR replicas cannot become leader (would lose messages)
  unclean.leader.election.enable=false (critical! prevents data loss)
```

**acks configuration — the durability vs. latency tradeoff:**

```java
// acks=0: fire and forget
// Producer doesn't wait for ANY acknowledgment
// Fastest, but if broker crashes after receiving TCP packet, message is lost
// Use case: metrics/logs where occasional loss is acceptable
Properties props = new Properties();
props.put("acks", "0");  // DANGEROUS for critical data

// acks=1: leader acknowledges
// Producer waits for leader to write to its local log
// If leader crashes BEFORE replicating to followers → data loss
// Use case: high-throughput scenarios where some loss is tolerable
props.put("acks", "1");

// acks=all (-1): all ISR must acknowledge
// Producer waits until all in-sync replicas have written the message
// Combined with min.insync.replicas=2: at least 2 brokers must confirm
// If leader + 1 follower confirm, you can survive one more broker failure
// Use case: financial data, audit logs, anything where loss is unacceptable
props.put("acks", "all");
props.put("min.insync.replicas", "2");  // set on the TOPIC, not the producer
```

**Why min.insync.replicas matters:**
```
replication.factor=3, min.insync.replicas=2

Normal:  ISR = {Broker1, Broker2, Broker3}
         acks=all → write must reach 2+ brokers → safe

1 broker down: ISR = {Broker1, Broker2}
               acks=all → write must reach 2 brokers → still works

2 brokers down: ISR = {Broker1}
                acks=all → ISR size (1) < min.insync.replicas (2)
                → Producer gets NOT_ENOUGH_REPLICAS error
                → System correctly refuses to accept writes
                → Better to reject than silently lose data

Without min.insync.replicas=2:
  acks=all with ISR={Broker1} → leader "acks=all" is satisfied (all 1 ISRs confirmed)
  → Silent data loss if that one broker dies before replication recovers
```

---

## Consumer Group Rebalancing — The Performance Cliff

Rebalancing is when partitions are reassigned among consumers in a group. It's necessary but expensive — during rebalancing, all consumption stops ("stop the world").

```
Initial state:
  Topic: orders, 6 partitions (P0-P5)
  Consumer Group: [C1, C2, C3]
  Assignment: C1→{P0,P1}, C2→{P2,P3}, C3→{P4,P5}

Trigger: C2 crashes or loses heartbeat
  Group coordinator (a broker) detects C2 missed heartbeat for session.timeout.ms
  → Initiates rebalance

Rebalance protocol (Eager/Stop-The-World, the default):
  1. Coordinator sends revoke request to ALL consumers
  2. All consumers STOP processing, revoke ALL their partitions
  3. All consumers send JoinGroup request
  4. One consumer elected as Group Leader (not the same as partition leader)
  5. Group Leader receives member list + partition list
  6. Group Leader runs partition assignment algorithm (round-robin, sticky, etc.)
  7. Group Leader sends assignment to coordinator
  8. Coordinator sends assigned partitions back to each consumer
  9. Consumers resume processing

During steps 1-9: ZERO messages processed by this consumer group
  With default timeouts: rebalance takes 3-30 seconds
  With 100 partitions and complex assignment: can take minutes
```

**Minimizing rebalance impact:**

```java
// Strategy 1: Use Sticky Assignor to minimize partition movement
// Sticky: when consumer dies, only redistribute its partitions
// Default RoundRobin: ALL partitions get reassigned → all consumers pause
props.put("partition.assignment.strategy",
    "org.apache.kafka.clients.consumer.StickyAssignor");

// Strategy 2: Cooperative Rebalancing (Kafka 2.4+)
// Instead of stop-the-world: only affected partitions are revoked
// Consumers that keep their partitions continue processing during rebalance
props.put("partition.assignment.strategy",
    "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");

// Strategy 3: Tune heartbeat/session timeouts
// Shorter timeouts = faster detection of dead consumer
// But: too short → network hiccup causes false rebalance
props.put("session.timeout.ms", "30000");     // coordinator declares consumer dead after 30s
props.put("heartbeat.interval.ms", "10000");  // consumer sends heartbeat every 10s
props.put("max.poll.interval.ms", "300000");  // consumer must poll within 5min or kicked out

// Strategy 4: Static group membership (Kafka 2.3+)
// Consumer gets a static group.instance.id
// Short network partition doesn't trigger rebalance — consumer can rejoin with same assignment
props.put("group.instance.id", "consumer-pod-1-az-us-east-1a");
```

---

## Delivery Guarantees — The Contract Between Producer and Consumer

### At-Most-Once (Fastest, Lossy)

```java
// Consumer auto-commits offset BEFORE processing
// If consumer crashes after commit but before processing → message lost
// Use case: metrics, click tracking where occasional loss is acceptable
Properties props = new Properties();
props.put("enable.auto.commit", "true");
props.put("auto.commit.interval.ms", "1000");

// Problem: auto-commit commits the offset of ALL polled messages every 1 second
// Even the ones your code hasn't processed yet
// Crash window: up to 1 second of messages can be lost
```

### At-Least-Once (Safe Default)

```java
// Consumer commits offset AFTER successfully processing
// If consumer crashes after processing but before commit → message reprocessed (duplicate)
// Use case: 90% of use cases — acceptable to deduplicate on consumer side
@KafkaListener(topics = "orders", groupId = "order-processor")
public void process(ConsumerRecord<String, OrderEvent> record,
                    Acknowledgment ack) {
    try {
        // Process FIRST, then commit
        // If this crashes: message redelivered → we process it again (OK if idempotent)
        orderService.processOrder(record.value());

        // Commit offset AFTER processing
        // If THIS crashes: message redelivered but already processed → idempotent handler needed
        ack.acknowledge();

    } catch (Exception e) {
        // Do NOT ack → Kafka will redeliver to this or another consumer
        // After max retries: message goes to DLQ
        log.error("Processing failed for offset {}", record.offset(), e);
        throw e;
    }
}
```

### Exactly-Once (Complex, High Overhead)

Exactly-once requires coordination between producer and consumer — a distributed transaction across Kafka internals:

```java
// Part 1: Idempotent Producer
// Kafka assigns each producer a PID (Producer ID) and sequence number per partition
// Broker deduplicates: if it sees PID=5, seq=100 twice, it ignores the duplicate
// This handles producer retries causing duplicates at the broker level
Properties producerProps = new Properties();
producerProps.put("enable.idempotence", "true");
// Automatically sets: acks=all, retries=MAX_INT, max.in.flight.requests.per.connection=5

// Part 2: Transactional Producer (for atomic multi-partition writes)
producerProps.put("transactional.id", "order-processor-tx-1");
// transactional.id must be unique per producer instance and stable across restarts

KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);
producer.initTransactions(); // registers with transaction coordinator broker

// Part 3: Atomic consume-transform-produce loop (the "read-process-write" pattern)
KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    if (records.isEmpty()) continue;

    producer.beginTransaction();
    try {
        for (ConsumerRecord<String, String> record : records) {
            // Transform and produce to output topic
            String result = transform(record.value());
            producer.send(new ProducerRecord<>("output-topic", record.key(), result));
        }

        // Atomically commit the input offsets AS PART OF the transaction
        // This is the key: offset commit and output write are ATOMIC
        Map<TopicPartition, OffsetAndMetadata> offsets = currentOffsets(records);
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());

        producer.commitTransaction();
        // At this point: output messages visible AND input offsets committed — atomically
        // Crash anywhere before commitTransaction → transaction rolls back → reprocess

    } catch (Exception e) {
        producer.abortTransaction();
        // Both the output messages AND the offset commit are rolled back
        // Consumer re-reads the input records on next poll
    }
}
```

**Why exactly-once is expensive:**
- Every transaction requires coordination with the transaction coordinator broker
- Consumers reading transactional output must be configured with `isolation.level=read_committed`
  → They only see committed transaction output (adds ~10-50ms latency to reads)
- Transaction overhead: ~10-20% throughput reduction
- transactional.id must be unique per logical producer stream (complex management)

---

## Dead Letter Queue Design

A DLQ is not just a fallback — it's a critical observability and recovery mechanism:

```
Primary flow:
  Input Topic → Consumer → Process → Output Topic
                    ↓ (on failure)
              Retry (3 attempts with exponential backoff)
                    ↓ (all retries exhausted)
              DLQ Topic

DLQ message envelope — preserve context for debugging:
{
  "original_topic": "orders",
  "original_partition": 3,
  "original_offset": 150234,
  "original_timestamp": "2024-01-15T14:30:00Z",
  "original_key": "order-123",
  "original_value": { ... raw message ... },
  "failure_reason": "OrderAlreadyExistsException: order 123 already processed",
  "stack_trace": "...",
  "failure_timestamp": "2024-01-15T14:30:02Z",
  "attempt_count": 3,
  "consumer_group": "order-processor",
  "consumer_instance": "pod-a3f2-us-east-1a"
}
```

```java
// DLQ producer — always use a separate producer for DLQ
// to avoid entangling DLQ writes with transaction state of main producer
@Service
public class DeadLetterQueueService {

    // Idempotent producer for DLQ (not transactional — simpler)
    private final KafkaProducer<String, DlqMessage> dlqProducer;

    public void sendToDlq(ConsumerRecord<String, String> original,
                          Exception failure,
                          int attemptCount) {
        DlqMessage envelope = DlqMessage.builder()
            .originalTopic(original.topic())
            .originalPartition(original.partition())
            .originalOffset(original.offset())
            .originalKey(original.key())
            .originalValue(original.value())
            .failureReason(failure.getMessage())
            .stackTrace(ExceptionUtils.getStackTrace(failure))
            .failureTimestamp(Instant.now())
            .attemptCount(attemptCount)
            .consumerGroup("order-processor")
            .build();

        // DLQ topic: same name as original + ".dlq" suffix
        // Partition by original topic+partition to preserve some locality
        String dlqKey = original.topic() + ":" + original.partition();
        dlqProducer.send(
            new ProducerRecord<>(original.topic() + ".dlq", dlqKey, envelope),
            (metadata, ex) -> {
                if (ex != null) {
                    // DLQ write failed — this is a critical failure
                    // Log to local file system as last resort
                    log.error("CRITICAL: DLQ write failed for offset {}", original.offset(), ex);
                    // Alert ops: if DLQ is broken, messages are being silently dropped
                    alertService.criticalAlert("DLQ write failure");
                }
            }
        );
    }
}

// DLQ replay service — allows ops to reprocess failed messages after fixing the bug
@RestController
public class DlqReplayController {

    @PostMapping("/dlq/{topic}/replay")
    public ReplayResult replay(
            @PathVariable String topic,
            @RequestParam(defaultValue = "0") long fromOffset,
            @RequestParam(defaultValue = "100") int limit) {

        // Read from DLQ topic and republish to original topic
        // Optionally filter by failure reason to only replay specific error types
        List<DlqMessage> messages = dlqReader.read(topic + ".dlq", fromOffset, limit);

        int replayed = 0, skipped = 0;
        for (DlqMessage msg : messages) {
            if (shouldReplay(msg)) {
                // Republish to the ORIGINAL topic (not the DLQ)
                originalProducer.send(new ProducerRecord<>(
                    msg.getOriginalTopic(),
                    msg.getOriginalKey(),
                    msg.getOriginalValue()
                ));
                replayed++;
            } else {
                skipped++;
            }
        }
        return new ReplayResult(replayed, skipped);
    }
}
```

---

## Back-Pressure and Consumer Lag Management

Consumer lag is the number of messages produced but not yet consumed. It's the primary health metric of a Kafka deployment:

```
Producer offset (latest): 1,000,000
Consumer committed offset: 990,000
Consumer lag = 10,000 messages

At 1000 msg/sec consumption rate:
  Time to catch up: 10,000 / 1,000 = 10 seconds

If lag is growing (consumer slower than producer):
  At producer rate 1,200/sec, consumer rate 1,000/sec:
  Lag grows at 200/sec → eventually runs out of retention → data loss risk
```

**Scaling strategy based on lag:**

```yaml
# KEDA (Kubernetes Event-Driven Autoscaling) configuration
# Automatically scales consumer deployment based on Kafka consumer lag
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: order-consumer-scaler
spec:
  scaleTargetRef:
    name: order-consumer-deployment
  minReplicaCount: 2    # minimum consumers (for redundancy)
  maxReplicaCount: 20   # cannot exceed partition count!
  triggers:
  - type: kafka
    metadata:
      bootstrapServers: kafka:9092
      consumerGroup: order-processor
      topic: orders
      lagThreshold: "1000"    # scale up when lag > 1000 per consumer
      # If 3 consumers and total lag = 9000 → lag per consumer = 3000 > 1000
      # KEDA adds consumers until lag per consumer < 1000
```

**Important constraint: you cannot have more consumers in a group than partitions.**

```
Topic: orders, 6 partitions
Consumer Group: 6 consumers → each handles 1 partition → maximum parallelism
Consumer Group: 7 consumers → 1 consumer sits idle (no partition assigned)
Consumer Group: 3 consumers → each handles 2 partitions → half parallelism

Implication: partition count is the ceiling on parallelism.
If you need 100 consumers: you need 100+ partitions.
Partition count is set at topic creation and is hard to change later.
→ Always overprovision partitions (Kafka recommends 3× expected peak consumers).
```

---

## Producer Performance Tuning

The producer client batches messages before sending to improve throughput:

```java
Properties producerProps = new Properties();

// Batching: wait up to 5ms to accumulate more messages before sending
// linger.ms=0 (default): send immediately (low latency, low throughput)
// linger.ms=5: small wait → 5-10x throughput increase for high-volume producers
// tradeoff: adds up to 5ms latency per message
producerProps.put("linger.ms", "5");

// Batch size: send when batch reaches this size (OR linger.ms elapsed)
// Default: 16KB — too small for high-throughput
// Recommended: 64KB-256KB for high-throughput scenarios
producerProps.put("batch.size", "65536");  // 64KB

// Compression: dramatically reduces network and disk IO
// LZ4: fast compression, 2:1 ratio — best for most cases
// SNAPPY: similar to LZ4, Google's algorithm
// GZIP: slow, 4:1 ratio — only for archival/cold data
// ZSTD: excellent ratio + speed — use for Kafka 2.1+
producerProps.put("compression.type", "lz4");

// Buffer: total memory for unsent batches
// If buffer.memory fills up: send() blocks for max.block.ms before throwing
producerProps.put("buffer.memory", "67108864");  // 64MB
producerProps.put("max.block.ms", "5000");        // fail after 5s if buffer full

// Retries: idempotent producer retries transparently
// max.in.flight.requests: 5 with idempotent=true (maintains ordering)
producerProps.put("enable.idempotence", "true");
producerProps.put("max.in.flight.requests.per.connection", "5");
```

---

## Topic Design Best Practices

```
Naming convention: {domain}.{entity}.{event-type}
  orders.order.created
  orders.order.cancelled
  payments.payment.initiated
  payments.payment.completed
  users.user.registered
  users.user.deactivated

Schema management:
  Never use raw JSON in production topics
  Use Avro with Confluent Schema Registry:
    - Schema versioned: consumers can handle schema evolution
    - Schema enforced: producer cannot send invalid messages
    - Compact serialization (Avro binary < JSON by 3-5x)

  Avro evolution rules:
    - Add optional fields with defaults: forward/backward compatible
    - Remove optional fields: forward compatible (old consumers ignore new fields)
    - Never: change field types, rename fields, remove required fields
    → These break existing consumers

Partition count guidance:
  Start with: max_desired_throughput_MB_per_sec / 10MB_per_sec_per_partition
  Add 50% buffer
  Round up to nearest multiple of replication factor
  Example: 100MB/sec target → 10 partitions → +50% buffer → 15 partitions

  Topics with retention-based compaction (user state, last-known values):
    - Log compaction: only keep the LATEST message per key
    - Old messages with same key are garbage collected
    - Use for: user profiles, device state, configuration
    - cleanup.policy=compact

Retention:
  Transactional events (orders, payments): 7-14 days
  User activity: 30 days
  Audit/compliance: 1 year (use tiered storage: hot data on SSD, cold on S3)
  Compacted topics: infinite (until deleted by key)
```

---

## RabbitMQ vs Kafka — When to Use Each

| Dimension | Kafka | RabbitMQ |
|---|---|---|
| Throughput | 1M+ msg/sec | 50-100k msg/sec |
| Message retention | Days/weeks (configurable) | Until consumed |
| Replay | Yes — read from any offset | No — consumed = gone |
| Consumer model | Pull (consumers poll) | Push (broker pushes) |
| Ordering | Per-partition ordering | Per-queue ordering |
| Routing | Topic + partition (simple) | Complex exchanges/bindings/routing keys |
| Message size | Optimized for 1KB-1MB | Handles any size, better for large |
| Consumer lag | Broker retains data, consumers catch up | If consumers lag: queue grows without bound |
| Use case | Event streaming, log aggregation, event sourcing | Task queues, RPC, complex routing |

**Choose Kafka when:**
- You need to replay historical events (audit log, event sourcing, ML training)
- Multiple independent consumers read the same events (analytics + billing + notifications all read "orders")
- Throughput > 50k msg/sec
- You need the stream processing paradigm (Kafka Streams, Flink)

**Choose RabbitMQ when:**
- You need complex message routing (fanout, topic exchange, header matching)
- Messages should be deleted after consumption (task queues)
- You need per-message priority
- Push-based delivery to consumers is simpler for your use case

---

## API Design (Client Usage)

```java
// Producer with full production configuration
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Durability
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // Throughput
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        return new DefaultKafkaProducerFactory<>(config);
    }
}

// Typed producer service with observability
@Service
public class OrderEventProducer {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    public void publishOrderCreated(Order order) {
        OrderEvent event = OrderEvent.builder()
            .orderId(order.getId().toString())
            .customerId(order.getCustomerId().toString())
            .totalAmount(order.getTotal())
            .items(order.getItems())
            .createdAt(Instant.now())
            .build();

        // Partition key = orderId: all events for this order go to same partition
        // Guarantees ordering of order lifecycle events (created → paid → shipped)
        CompletableFuture<SendResult<String, OrderEvent>> future =
            kafkaTemplate.send("orders.order.created", order.getId().toString(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // Producer retry is automatic (enable.idempotence=true handles retries)
                // This callback fires only on unrecoverable failure (e.g., topic doesn't exist)
                log.error("Failed to publish order event {}", order.getId(), ex);
                // Fall back: write to outbox table for guaranteed delivery
                outboxRepository.save(new OutboxEntry(event));
            } else {
                log.debug("Order event published: partition={}, offset={}",
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            }
        });
    }
}

// Consumer with full error handling
@Service
public class OrderEventConsumer {

    private final OrderService orderService;
    private final DeadLetterQueueService dlqService;

    @KafkaListener(
        topics = "orders.order.created",
        groupId = "order-processor",
        concurrency = "6",  // 6 threads, matches partition count
        containerFactory = "manualAckKafkaListenerContainerFactory"
    )
    public void handleOrderCreated(
            ConsumerRecord<String, OrderEvent> record,
            Acknowledgment ack) {

        int attempt = 0;
        Exception lastException = null;

        while (attempt < 3) {
            try {
                orderService.processNewOrder(record.value());
                ack.acknowledge(); // commit offset only after success
                return;
            } catch (TransientException e) {
                // Transient: network timeout, DB unavailable — retry
                attempt++;
                lastException = e;
                sleep(exponentialBackoff(attempt)); // 1s, 2s, 4s
            } catch (PermanentException e) {
                // Permanent: invalid message format, business rule violation — go to DLQ
                dlqService.sendToDlq(record, e, 1);
                ack.acknowledge(); // commit offset: we handled it (via DLQ)
                return;
            }
        }

        // All retries exhausted
        dlqService.sendToDlq(record, lastException, 3);
        ack.acknowledge(); // don't block the partition — move on
    }
}
```

---

## Failure Scenarios and Edge Cases

### Scenario 1: Broker Leader Failure During Write

```
Producer sends message → Leader (Broker 1) receives it
Broker 1 crashes BEFORE replicating to followers (ISR={B1, B2, B3})

With acks=all: Producer never got ack → producer retries
  New leader elected from remaining ISR (B2 or B3)
  B2/B3 don't have this message (leader crashed before replication)
  Producer retry succeeds on new leader → message delivered exactly once
  (Idempotent producer: sequence numbers prevent duplicate if message DID replicate)

With acks=1: Producer got ack from B1 before crash
  New leader (B2) doesn't have the message
  Message is LOST — this is by design for acks=1
```

### Scenario 2: Consumer Group Split-Brain (Network Partition)

```
Network partition: Consumer Group Coordinator can't reach Consumer C1
Coordinator declares C1 dead → rebalances → assigns C1's partitions to C2

C1 is actually alive, just network issue
C1 still thinks it owns P0, continues processing and trying to commit offsets
C2 was assigned P0, starts processing from last committed offset

Both C1 and C2 process some of the same messages simultaneously!

Resolution:
Kafka's fencing mechanism:
  Each consumer gets a generation ID that increments with each rebalance
  Offset commits include generation ID
  If C1 (old generation) tries to commit → coordinator rejects (wrong generation)
  C1's offset commits fail → C1 eventually detects rebalance → stops
  C2's commits succeed (current generation)

  Result: duplicates possible (both processed same messages) but no data loss
  Consumer code must be idempotent to handle this
```

### Scenario 3: Schema Evolution Breaking Consumers

```
Producer team deploys new version: adds required field "shipping_address" to OrderEvent
Old consumer (still running): deserializes OrderEvent → field missing → NullPointerException
All consumers halt on partitions receiving new-format messages

Prevention with Schema Registry:
  1. New schema submitted to registry: marked BACKWARD_COMPATIBLE
  2. Avro: "shipping_address" field must have default value (null or "")
  3. Old consumers use old schema: new field is ignored (it has a default)
  4. New consumers use new schema: field is populated correctly
  5. Registry enforces compatibility before producer can publish new schema
  → Old and new consumers can coexist during rolling deployment
```

---

## Tech Stack

- **Message Queue**: Apache Kafka (high throughput, replay, event streaming)
- **Alternative**: RabbitMQ (complex routing, task queues, lower volume)
- **Client**: Spring Kafka (Java) — handles most boilerplate
- **Schema Registry**: Confluent Schema Registry (Avro/Protobuf schema management)
- **Monitoring**: Kafka Exporter → Prometheus → Grafana dashboards
- **Consumer Lag**: Burrow (LinkedIn's lag monitor) or Kafka UI
- **Auto-scaling**: KEDA (Kubernetes Event-Driven Autoscaling)
- **Operations**: Conduktor or Kafka UI for topic management/inspection

---

## Interview Q&A

### Q1: Why is Kafka's append-only log fundamentally faster than traditional message queues?

The performance difference comes down to disk I/O access patterns. Traditional message queues like ActiveMQ store messages in B-tree structures that require random disk writes — the message goes wherever the tree's balance dictates. Random writes on spinning disks achieve 1-5 MB/sec because the disk head must physically seek to different locations. Kafka's append-only log always writes at the end of the current segment file, which means sequential disk writes at 100-200 MB/sec — a 50-100x difference. On SSDs the gap is smaller but still significant because sequential I/O avoids SSD internal write amplification. Additionally, Kafka uses the OS page cache aggressively: recently written data stays in RAM (the OS caches it automatically), so consumers reading recent messages get RAM-speed retrieval. Kafka also uses the `sendfile()` system call for zero-copy reads — data goes directly from the kernel page cache to the network card buffer without any user-space copying, halving the CPU overhead of serving consumers.

### Q2: Explain exactly-once semantics in Kafka. Why is it harder than it sounds?

Exactly-once sounds simple but requires solving two distinct problems: producer deduplication and atomic consumer offset commits. The producer problem: if a producer sends a message and the network drops the ack, it retries — without idempotency, the broker stores the message twice. Kafka solves this with per-producer sequence numbers; the broker rejects a message with sequence number N if it already stored sequence N from the same producer (identified by PID). The consumer problem: if a consumer processes a message and produces output but crashes before committing its input offset, it reprocesses on restart — potentially producing duplicate output. Kafka solves this with transactions: the consumer offset commit and the output message write are atomically part of the same transaction — either both commit or both roll back. The implementation overhead is significant: transactions require a transaction coordinator broker, all output topic consumers must use `isolation.level=read_committed` (they only see committed messages), and throughput drops 10-20% due to transaction coordination. Exactly-once is the right choice for financial ledger updates, inventory management, and anywhere idempotent processing is impossible.

### Q3: How do you choose the right partition count for a Kafka topic?

Partition count determines maximum parallelism and is difficult to change after topic creation (increasing partition count breaks key ordering guarantees). The formula is: partitions = target_throughput / per_partition_throughput, where per_partition_throughput is typically 1 MB/sec write and 10 MB/sec read (read is faster due to zero-copy). For 100 MB/sec write throughput: 100 partitions, but add 50% buffer for growth headroom, giving 150 partitions. Round to a number with good factoring properties (evenly divisible by your replica factor and expected consumer counts). Consider the operations cost: 1000 partitions means 3000 partition-replica files, 3000 connections maintained between brokers — too many partitions have diminishing returns and increase controller load. Also consider your consumer count ceiling: you can never usefully run more consumers in a group than partitions. The practical advice: start with 20-50% more partitions than you think you need today, because adding partitions later is painful but having extra capacity is free.

### Q4: What happens during a Kafka consumer rebalance and how do you minimize its impact?

A consumer group rebalance happens when a consumer joins or leaves the group, or when partitions are added to a topic. During a traditional "eager" rebalance, ALL consumers stop processing simultaneously, revoke all their partitions, and wait for new assignments — a stop-the-world event lasting 3-30 seconds. With 100 partitions and complex assignment logic, this can halt processing for minutes. Modern Kafka (2.4+) offers "cooperative sticky" rebalancing: only partitions that need to move are revoked, while other consumers continue processing their unchanged partitions. This dramatically reduces the impact of consumer failures. To further minimize rebalance frequency: use static group membership (`group.instance.id`) so that a consumer temporarily losing connectivity doesn't immediately trigger a rebalance — it gets a grace period to rejoin with the same assignment. Tune `session.timeout.ms` carefully: too low (5s) means network blips cause rebalances; too high (120s) means a dead consumer takes 2 minutes to be detected. Set `max.poll.interval.ms` based on your worst-case processing time — if processing one batch takes 60 seconds, set this to at least 90 seconds or the consumer gets kicked out mid-processing.

### Q5: How would you design a system to guarantee no message loss in Kafka even if a broker catches fire?

No-message-loss requires configuration at multiple levels. At the producer level: `acks=all` with `min.insync.replicas=2` ensures messages are written to at least 2 brokers before the producer considers them sent. With `enable.idempotence=true`, retries don't create duplicates. At the broker level: `unclean.leader.election.enable=false` prevents a lagging replica from becoming leader (which would "forget" messages not yet replicated to it). Set `replication.factor=3` on all critical topics — this tolerates 2 simultaneous broker failures before risking data loss. At the consumer level: use manual offset commits with `enable.auto.commit=false`, commit only after successful processing, and handle exceptions to avoid silently skipping failed messages (route to DLQ instead). Operationally: monitor ISR shrinkage (a topic's ISR count dropping below replication factor means one broker is lagging — increased risk window). Use separate disk volumes for Kafka log directories so disk failures don't take down the OS. With all these in place, the only data loss scenarios are: simultaneous failure of min.insync.replicas brokers, or hardware failures that corrupt data on disk — mitigated by replication across availability zones.

### Q6: How do you handle a "poison pill" message that crashes your consumer every time it's processed?

A poison pill is a message that cannot be processed due to bad data, schema mismatch, or a bug triggered by specific content. Without a strategy, the consumer crashes, restarts, re-reads the same message, crashes again — an infinite loop that halts the entire partition. The solution is retry with exponential backoff combined with a dead letter queue. On the first failure, wait 1 second and retry. On the second failure, wait 5 seconds and retry. On the third failure, give up: serialize the original message with failure metadata (error message, stack trace, original topic/partition/offset) and publish it to a `.dlq` topic. Then commit the original message's offset and continue processing subsequent messages. The DLQ consumer alerts ops and stores the message for manual inspection. After deploying a bug fix, ops can replay specific DLQ messages back to the original topic. The key insight is: it's better to acknowledge and move on (with full audit trail in DLQ) than to stall the entire partition indefinitely. A partition stalled means its consumer group falls behind, potentially causing message retention expiry (messages older than retention.ms get deleted from Kafka, creating data loss).

### Q7: Explain Kafka's log compaction feature and when you'd use it.

Log compaction is an alternative retention policy where instead of deleting old messages by age or size, Kafka keeps only the most recent message for each key. Consider a topic tracking user account status: if user 123 transitions through status updates (ACTIVE, SUSPENDED, ACTIVE, DEACTIVATED), log compaction ensures the topic always retains at least the final state (DEACTIVATED) for user 123. Older entries for the same key are eventually garbage collected. This enables using Kafka as a durable key-value store: consumers can "restore" their state by reading the compacted topic from the beginning — they see exactly the latest value for every key. Common use cases: database change data capture (Debezium), application configuration distribution, user profile snapshots, microservice data replication for cache warming. Log compaction runs as a background process and doesn't guarantee which specific historical entries are kept (only the latest), so it's not suitable for audit trails where the history matters. Combine with `min.compaction.lag.ms` to ensure recent messages aren't immediately compacted (giving all consumers time to process before a message is eligible for compaction).

### Q8: How would you monitor Kafka health in production, and what are your key alerting thresholds?

Kafka monitoring requires watching three planes: broker health, producer health, and consumer health. For brokers: alert on (1) under-replicated partitions > 0 — means a replica is falling behind, increasing data loss risk; (2) active controller count != 1 — no controller or split-brain; (3) disk usage > 80% — approaching retention-based deletion; (4) network throughput approaching NIC saturation; (5) request queue depth > 10 — broker is overloaded. For producers: alert on (1) record error rate > 0.01% — messages being dropped; (2) request latency P99 > 1 second — broker is slow. For consumers: alert on (1) consumer lag growing — lag should fluctuate but never trend upward indefinitely; (2) lag > Xminutes worth of messages (X depends on SLA, typically 5-30 minutes); (3) consumer group rebalance frequency > 1/hour — indicates instability; (4) consumer process crash rate. Use Kafka's built-in metrics (JMX) exported via Prometheus JMX Exporter, and visualize in Grafana. LinkedIn's Burrow is excellent for lag monitoring because it models lag as velocity (is it growing or shrinking?) rather than absolute value, enabling smarter alerting. Set up dead man's switch alerts: if a consumer group doesn't commit any offsets for 5 minutes, something is wrong even if lag appears stable.

### Q9: What is the Outbox pattern and why do you need it alongside Kafka?

The Outbox pattern solves a fundamental distributed transaction problem: atomically writing to your application database AND publishing to Kafka. Without it, consider: your service saves an order to PostgreSQL and then calls `kafkaTemplate.send()`. If Kafka is temporarily unavailable, the order is saved but the event never fires — downstream services don't know about the order. Conversely, if the DB write fails after the Kafka send, you've published an event for a nonexistent order. The Outbox pattern solves this: the service writes both the domain entity AND an outbox record (containing the Kafka event) in a single database transaction. A separate "outbox relay" process polls the outbox table, publishes events to Kafka, and marks them as sent. Since the outbox record and the domain entity are written atomically (same DB transaction), they can't be out of sync. The relay uses at-least-once delivery (idempotent Kafka producer handles duplicates). Alternatively, use CDC (Change Data Capture) with Debezium: it reads the database's transaction log (WAL) and publishes changes to Kafka without any outbox table — the transaction log IS the outbox. The Outbox pattern is essential for any service where domain state and events must be consistent.

### Q10: How would you design a multi-tenant Kafka deployment where tenants must be isolated?

Multi-tenant Kafka has several isolation dimensions: data isolation, throughput isolation, and operational isolation. For data isolation: one topic per tenant is the simplest but creates thousands of topics (operational nightmare). Better: use tenant ID as the partition key within shared topics, or use separate clusters for high-value tenants. Prefix all topic names with tenant ID for visibility. For throughput isolation: Kafka quotas allow limiting producer/consumer bandwidth per client.id or principal. Configure quotas per tenant principal: `kafka-configs.sh --alter --add-config 'producer_byte_rate=1048576'` (1MB/sec). Exceeding the quota causes the client to be throttled. For operational isolation: VIP (high-value) tenants get dedicated partitions or dedicated consumer groups; this prevents one tenant's burst from starving others. For strict isolation requirements (financial services, healthcare): separate Kafka clusters per tenant, managed by a control plane. This is more expensive but provides true blast-radius isolation. The data plane / control plane separation: a metadata service tracks which tenant uses which topics/clusters, and producers use this service to discover their topic before publishing — changing the topic assignment doesn't require code changes.

### Q11: Explain the difference between Kafka Streams and a regular Kafka consumer. When would you choose each?

A regular Kafka consumer is a simple loop: poll for records, process them, commit offsets. It's stateless by default and you manage all state yourself (in-memory maps, external databases). Kafka Streams is a library built on top of the consumer API that adds stateful stream processing primitives: joins, aggregations, windowing, and per-key state stores backed by RocksDB (local embedded database) with Kafka changelog topics for fault tolerance. Use a regular consumer when: processing is stateless (each message handled independently), you need full control over the processing loop, or you're consuming in a language without a Kafka Streams library. Use Kafka Streams when: you need to aggregate events over time windows ("count orders per customer per hour"), join streams ("enrich orders with customer data from a table"), or maintain running state ("sum of all payments per account"). The key advantage of Kafka Streams over Apache Flink/Spark for stream processing: it's a library, not a separate cluster. You deploy it as part of your application JAR — no separate cluster to manage. The trade-off: less powerful for very complex streaming SQL or machine learning inference on streams — for those, Flink is the better choice.

### Q12: How would you handle a scenario where your Kafka cluster runs out of disk space in production?

Disk exhaustion is an immediate production crisis because Kafka brokers cannot accept writes and may corrupt data if the disk completely fills. The response has three phases. Immediate: increase disk space (expand EBS volumes, add disks) if cloud-hosted — this is the fastest fix. If disk expansion takes time: reduce `log.retention.bytes` or `log.retention.hours` on the largest topics to trigger faster log deletion. Temporarily increase `log.segment.bytes` so segments roll over faster and older ones become eligible for deletion. Emergency: if the broker is about to crash, manually delete old log segments from the filesystem (dangerous — do this only if retention-based deletion isn't fast enough). Prevention for the future: set up tiered storage (Kafka 3.6+ has built-in S3 tiered storage) — cold segments automatically move to S3 after 24 hours, keeping local disk usage bounded. Alert at 70% and 85% disk usage with enough lead time to add capacity. Ensure your Kafka data directories are on separate volumes from the OS — a full data disk shouldn't crash the broker itself. Monitor `kafka.log:type=LogFlushRateAndTimeMs` — if flush latency spikes, the disk is saturating before you hit capacity alerts.

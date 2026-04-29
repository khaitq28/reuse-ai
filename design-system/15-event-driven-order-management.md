# Event-Driven Order Management System (EDA + CQRS + Event Sourcing)

## Problem Statement

Design an order management system using Event-Driven Architecture (EDA), CQRS, and Event Sourcing — handling order creation, payment, inventory reservation, shipping, and notifications as decoupled microservices communicating through events. This architecture is central to modern e-commerce, fintech, and logistics platforms.

---

## Why This Problem Matters

This problem tests whether you understand the fundamental shift from **state-centric** (CRUD) to **event-centric** thinking in distributed systems. Interviewers use this to gauge:

1. **Consistency vs. availability tradeoff awareness** — In a distributed system with 5 independent services (Order, Payment, Inventory, Shipping, Notification), you cannot have a single ACID transaction spanning all of them. The question is how you achieve eventual consistency safely.
2. **Why simple REST calls between services fail** — Synchronous REST coupling creates cascading failure: if Payment Service is down, order creation fails. This is bad. EDA solves this by making services temporally decoupled.
3. **Audit trail requirements** — Regulated industries (banking, healthcare, e-commerce) require immutable audit trails of every state change. Event Sourcing gives you this for free — events are the audit log.
4. **Query optimization** — CQRS recognizes that write patterns (one aggregated command) and read patterns (denormalized views across many entities) are fundamentally different, and trying to serve both from the same model is a compromise that serves neither well.

The interviewer is testing: distributed transactions, Saga pattern (choreography vs. orchestration), Outbox Pattern, event store design, idempotent consumers, and projection rebuilding.

---

## Key Insight Before Diving In

**Events are facts about things that already happened. State is derived from events, not stored directly.**

In traditional CRUD: when a payment succeeds, you UPDATE the orders table to set `status = 'PAID'`. You lose the history — who processed the payment, at what exact time, what the previous state was, what triggered it.

In Event Sourcing: when a payment succeeds, you APPEND a `PaymentSucceededEvent` to the event store. The current state of the order is always re-derivable by replaying all events. This gives you time travel (what was the state of this order at 3pm yesterday?), natural audit trails, and the ability to add new projections without data migration.

The secondary insight is the **Outbox Pattern**: writing to a database and publishing to a message broker (Kafka) cannot be done atomically without a distributed transaction. The Outbox Pattern solves this by writing the event to an outbox table in the same database transaction as the business logic, then using a separate relay process to publish the event to Kafka. This guarantees at-least-once delivery without distributed transactions.

---

## Requirements

### Functional
- Create orders with line items and compute total price
- Process payment for an order (charge customer)
- Reserve inventory per order line item
- Schedule shipment after payment and inventory confirmed
- Full order lifecycle: CREATED → PAYMENT_PENDING → PAID → INVENTORY_RESERVED → SHIPPED → DELIVERED
- Customers can query current order status and full event history
- Compensating transactions on failure (cancel order, refund payment, release inventory)
- Cancel order at any stage with appropriate compensation

### Non-Functional
- Services are independently deployable and scalable
- No synchronous coupling between Order, Payment, Inventory, Shipping services
- Eventual consistency acceptable for reads (query model may lag seconds)
- Complete audit trail of every state change with timestamps and reasons
- At-least-once event delivery (consumers must be idempotent)
- Projection rebuilding: ability to rebuild read models from event history
- Event schema evolution without breaking consumers

---

## Architecture: EDA + CQRS + Event Sourcing

```
                    ┌───────────────────────────────────────┐
                    │           Command Side                  │
                    │   POST /orders → CreateOrderCommand     │
                    │   Order Aggregate processes command     │
                    │   Emits OrderCreatedEvent               │
                    │   Appends to Event Store (PostgreSQL)   │
                    │   Writes to Outbox table (same txn)     │
                    └─────────────────┬─────────────────────┘
                                      │
                              Outbox Relay (CDC or polling)
                                      │
                    ┌─────────────────▼─────────────────────┐
                    │           Kafka Event Bus               │
                    │  Topics: order-events, payment-events  │
                    │          inventory-events, ship-events  │
                    └──┬──────────────┬────────────┬────────┘
                       │              │            │
              ┌────────▼──┐  ┌────────▼─┐  ┌──────▼──────────┐
              │  Payment   │  │Inventory │  │ Shipping        │
              │  Service   │  │ Service  │  │ Service         │
              │  (Saga     │  │ (Saga    │  │ (Saga step 3)   │
              │   step 1)  │  │  step 2) │  └──────┬──────────┘
              └────────┬──┘  └────────┬─┘         │
                       │              │            │
                    ┌──▼──────────────▼────────────▼────────┐
                    │           Query Side                    │
                    │    Projection consumers update         │
                    │    denormalized order_view table       │
                    │    (PostgreSQL read replica or ES)     │
                    │    GET /orders/{id} → instant read     │
                    └────────────────────────────────────────┘
```

The write side (Command Side) handles commands that mutate state. The read side (Query Side) handles queries from pre-built projections. They are completely separate and can scale independently.

---

## Event Sourcing vs. CRUD — The Fundamental Difference

### CRUD approach (traditional):
```sql
-- Order created:
INSERT INTO orders (id, status, total) VALUES ('ord-1', 'CREATED', 99.99);

-- Payment succeeds:
UPDATE orders SET status = 'PAID' WHERE id = 'ord-1';
-- Previous state is GONE. No record of when, why, or what triggered it.

-- Want to know: was this order ever in PENDING state? → Can't tell.
-- Want to replay what happened? → Impossible.
-- Want to add a new report showing status transitions? → Impossible without new table.
```

### Event Sourcing approach:
```sql
-- Event store only has INSERT, never UPDATE or DELETE
INSERT INTO event_store (aggregate_id, event_type, payload, version)
VALUES ('ord-1', 'OrderCreatedEvent', '{"items":[...],"total":99.99}', 1);

INSERT INTO event_store (aggregate_id, event_type, payload, version)
VALUES ('ord-1', 'PaymentSucceededEvent', '{"paymentId":"pay-123","amount":99.99}', 2);

-- Current state: replay all events for ord-1 → version 1 (CREATED) + version 2 (PAID) = current state PAID
-- Historical state: replay up to version 1 → CREATED
-- New projection: read all events, build any view you want — without data migration
-- Audit trail: free, built-in, immutable
```

The event store is append-only. You never update or delete events (except for legal compliance with data erasure regulations, which requires special handling — typically encrypting sensitive data and deleting the key).

---

## Order Aggregate — Command Processing and State Reconstitution

```java
/**
 * The Order aggregate is the consistency boundary.
 * It encapsulates all business rules for the Order domain.
 *
 * Key principles:
 * 1. Commands are validated and produce Events (facts)
 * 2. Events are applied to mutate state (apply method)
 * 3. State is NEVER stored directly — only events are persisted
 * 4. The aggregate can be reconstituted from event history at any time
 */
public class Order {
    private UUID id;
    private UUID customerId;
    private List<OrderItem> items;
    private OrderStatus status;
    private BigDecimal totalPrice;
    private String cancellationReason;

    // Events that have been raised in this command cycle but not yet persisted
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();

    // ===== COMMAND HANDLERS =====
    // These validate business rules and raise events.
    // They do NOT mutate state directly — that is the job of event handlers.

    public static Order create(CreateOrderCommand cmd) {
        // Business rule: order must have at least one item
        if (cmd.getItems() == null || cmd.getItems().isEmpty()) {
            throw new InvalidOrderException("Order must have at least one item");
        }
        // Business rule: all items must have positive quantity
        if (cmd.getItems().stream().anyMatch(i -> i.getQuantity() <= 0)) {
            throw new InvalidOrderException("All items must have positive quantity");
        }

        Order order = new Order();
        BigDecimal total = cmd.getItems().stream()
            .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Raise the event — do NOT set state here
        order.raise(new OrderCreatedEvent(
            cmd.getOrderId(),
            cmd.getCustomerId(),
            cmd.getItems(),
            total,
            Instant.now()
        ));
        return order;
    }

    public void cancel(String reason) {
        // Business rule: can only cancel if not yet shipped
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
            throw new InvalidOperationException(
                "Cannot cancel order in status: " + status
            );
        }
        raise(new OrderCancelledEvent(id, reason, Instant.now()));
    }

    public void markPaymentSucceeded(UUID paymentId, BigDecimal amount) {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new InvalidOperationException(
                "Unexpected PaymentSucceeded in status: " + status
            );
        }
        raise(new PaymentSucceededEvent(id, paymentId, amount, Instant.now()));
    }

    // ===== EVENT HANDLERS =====
    // These apply events to mutate state.
    // They must be PURE — no side effects, no validation, just state mutation.
    // Called both when processing new commands AND when reconstituting from history.

    private void on(OrderCreatedEvent event) {
        this.id = event.getOrderId();
        this.customerId = event.getCustomerId();
        this.items = new ArrayList<>(event.getItems());
        this.totalPrice = event.getTotalPrice();
        this.status = OrderStatus.CREATED;
    }

    private void on(PaymentSucceededEvent event) {
        this.status = OrderStatus.PAID;
        // Note: we store paymentId for reference but it's also in the event
    }

    private void on(InventoryReservedEvent event) {
        this.status = OrderStatus.INVENTORY_RESERVED;
    }

    private void on(OrderCancelledEvent event) {
        this.status = OrderStatus.CANCELLED;
        this.cancellationReason = event.getReason();
    }

    private void on(OrderShippedEvent event) {
        this.status = OrderStatus.SHIPPED;
    }

    // ===== INFRASTRUCTURE =====

    /**
     * Apply an event: add to uncommitted list AND mutate state.
     * Used for new events raised during command processing.
     */
    private void raise(DomainEvent event) {
        apply(event);
        uncommittedEvents.add(event);
    }

    /**
     * Apply an event to state only (no uncommitted tracking).
     * Used during reconstitution from event store.
     */
    public void apply(DomainEvent event) {
        switch (event) {
            case OrderCreatedEvent e     -> on(e);
            case PaymentSucceededEvent e -> on(e);
            case InventoryReservedEvent e-> on(e);
            case OrderCancelledEvent e   -> on(e);
            case OrderShippedEvent e     -> on(e);
            default -> throw new UnknownEventException(event.getClass().getName());
        }
    }

    /**
     * Reconstitute an Order from its complete event history.
     * This is how the aggregate is loaded — NOT from a state table.
     *
     * Time complexity: O(n) where n = number of events for this aggregate.
     * For long-lived aggregates (thousands of events), use Snapshots (see below).
     */
    public static Order reconstitute(List<DomainEvent> history) {
        if (history.isEmpty()) throw new AggregateNotFoundException("No events found");
        Order order = new Order();
        history.forEach(order::apply);
        return order;
    }

    public List<DomainEvent> getUncommittedEvents() {
        return Collections.unmodifiableList(uncommittedEvents);
    }

    public void clearUncommittedEvents() {
        uncommittedEvents.clear();
    }
}
```

---

## Event Store — Append-Only, Optimistic Concurrency

```sql
CREATE TABLE event_store (
  id              UUID        NOT NULL DEFAULT gen_random_uuid(),
  aggregate_id    UUID        NOT NULL,
  aggregate_type  VARCHAR(100) NOT NULL,  -- "Order", "Payment", etc.
  event_type      VARCHAR(100) NOT NULL,  -- "OrderCreatedEvent"
  event_version   INT         NOT NULL,   -- monotonically increasing per aggregate
  payload         JSONB       NOT NULL,   -- the event data
  metadata        JSONB,                  -- correlation_id, causation_id, user_id
  occurred_at     TIMESTAMP   NOT NULL DEFAULT NOW(),

  PRIMARY KEY (id),

  -- Optimistic concurrency: prevents two concurrent commands on the same aggregate
  -- from both succeeding at the same version.
  -- If two writers both try to insert version=5, only one succeeds.
  UNIQUE (aggregate_id, event_version)
);

-- The primary access pattern: load all events for an aggregate, ordered by version
CREATE INDEX idx_event_store_aggregate
  ON event_store (aggregate_id, event_version ASC);

-- For projection rebuilding: stream all events of a given type
CREATE INDEX idx_event_store_type
  ON event_store (event_type, occurred_at);
```

### Saving Events with Optimistic Concurrency

```java
@Repository
public class PostgresEventStore implements EventStore {

    /**
     * Append new events to the event store.
     *
     * expectedVersion: the version of the aggregate before this command.
     * If another command was processed concurrently and already wrote version N,
     * our insert of version N will fail the UNIQUE constraint → OptimisticLockException.
     * The caller retries by reloading the aggregate and re-applying the command.
     */
    public void append(UUID aggregateId, int expectedVersion,
                       List<DomainEvent> events) {
        int version = expectedVersion;
        for (DomainEvent event : events) {
            version++;
            try {
                jdbcTemplate.update("""
                    INSERT INTO event_store
                      (aggregate_id, aggregate_type, event_type, event_version,
                       payload, metadata, occurred_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                    """,
                    aggregateId,
                    event.getAggregateType(),
                    event.getClass().getSimpleName(),
                    version,
                    objectMapper.writeValueAsString(event),
                    buildMetadata(event),
                    event.getOccurredAt()
                );
            } catch (DuplicateKeyException e) {
                // Another concurrent command wrote this version first
                throw new OptimisticConcurrencyException(
                    "Conflict on aggregate " + aggregateId + " version " + version
                );
            }
        }
    }

    public List<DomainEvent> load(UUID aggregateId) {
        return jdbcTemplate.query("""
            SELECT event_type, payload, event_version, occurred_at
            FROM event_store
            WHERE aggregate_id = ?
            ORDER BY event_version ASC
            """,
            (rs, rowNum) -> deserializeEvent(
                rs.getString("event_type"),
                rs.getString("payload")
            ),
            aggregateId
        );
    }
}
```

### Snapshot Pattern for Long-Lived Aggregates

If an order has 500 events (e.g., a complex B2B order with many amendments), replaying all 500 events on every command is wasteful. Snapshots solve this:

```sql
CREATE TABLE aggregate_snapshots (
  aggregate_id   UUID PRIMARY KEY,
  version        INT NOT NULL,      -- event version at snapshot time
  payload        JSONB NOT NULL,    -- serialized aggregate state
  created_at     TIMESTAMP NOT NULL
);
```

```java
// Load: try snapshot first, then load only events after the snapshot
public Order loadWithSnapshot(UUID orderId) {
    Optional<Snapshot> snapshot = snapshotStore.findLatest(orderId);

    if (snapshot.isPresent()) {
        Order order = objectMapper.readValue(snapshot.get().getPayload(), Order.class);
        // Load only events AFTER the snapshot version
        List<DomainEvent> recentEvents = eventStore.loadFrom(
            orderId, snapshot.get().getVersion() + 1
        );
        recentEvents.forEach(order::apply);
        return order;
    } else {
        return Order.reconstitute(eventStore.load(orderId));
    }
}

// Save snapshot every 50 events
public void maybeSaveSnapshot(Order order, int currentVersion) {
    if (currentVersion % 50 == 0) {
        snapshotStore.save(new Snapshot(
            order.getId(),
            currentVersion,
            objectMapper.writeValueAsString(order)
        ));
    }
}
```

---

## Domain Events — Immutable Facts

```java
/**
 * Domain events are immutable value objects representing facts.
 * Key design decisions:
 * 1. Past tense names: OrderCreated, not CreateOrder
 * 2. Carry all data needed by consumers (avoid requiring consumers to call back)
 * 3. Include event ID for idempotency
 * 4. Include correlation_id to trace a business transaction across services
 */

public sealed interface DomainEvent permits
    OrderCreatedEvent, PaymentSucceededEvent, PaymentFailedEvent,
    InventoryReservedEvent, InventoryFailedEvent, OrderShippedEvent,
    OrderCancelledEvent, OrderDeliveredEvent {

    UUID getEventId();            // unique ID for this event instance (idempotency key)
    UUID getAggregateId();        // which aggregate this event belongs to
    String getAggregateType();    // "Order", "Payment", etc.
    Instant getOccurredAt();      // when the event occurred (not when published)
    UUID getCorrelationId();      // traces across the entire business flow
    UUID getCausationId();        // which event or command caused this event
}

public record OrderCreatedEvent(
    UUID eventId,
    UUID orderId,
    UUID customerId,
    List<OrderItem> items,
    BigDecimal totalPrice,
    String currency,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId
) implements DomainEvent {
    @Override public UUID getAggregateId() { return orderId; }
    @Override public String getAggregateType() { return "Order"; }
}

public record PaymentSucceededEvent(
    UUID eventId,
    UUID orderId,
    UUID paymentId,
    BigDecimal amount,
    String currency,
    String paymentMethod,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId
) implements DomainEvent {
    @Override public UUID getAggregateId() { return orderId; }
    @Override public String getAggregateType() { return "Order"; }
}

public record PaymentFailedEvent(
    UUID eventId,
    UUID orderId,
    String failureReason,   // "INSUFFICIENT_FUNDS", "CARD_DECLINED", "FRAUD_DETECTED"
    String failureCode,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId
) implements DomainEvent {
    @Override public UUID getAggregateId() { return orderId; }
    @Override public String getAggregateType() { return "Order"; }
}

public record InventoryReservedEvent(
    UUID eventId,
    UUID orderId,
    List<ReservationItem> reservations,  // which warehouse, bin location, etc.
    Instant occurredAt,
    UUID correlationId,
    UUID causationId
) implements DomainEvent {
    @Override public UUID getAggregateId() { return orderId; }
    @Override public String getAggregateType() { return "Order"; }
}
```

---

## Outbox Pattern — Guaranteed At-Least-Once Delivery

### The Problem: Dual Write

The fundamental problem: when an Order is created, you need to:
1. Write the event to the event store (PostgreSQL)
2. Publish the event to Kafka

These two operations cannot be made atomic with a standard transaction. If you write to the event store and then Kafka crashes before publishing — the event is lost. If you publish to Kafka first and then the database crashes — consumers process an event that was never durably recorded.

### Solution: Outbox Table

```sql
-- Outbox table lives in the SAME database as the event store
-- Written in the SAME transaction as business data
-- A relay process reads from outbox and publishes to Kafka
CREATE TABLE outbox (
  id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  topic         VARCHAR(100) NOT NULL,    -- Kafka topic name
  partition_key VARCHAR(255),             -- message key for ordering
  event_type    VARCHAR(100) NOT NULL,
  payload       JSONB       NOT NULL,
  headers       JSONB,                    -- Kafka message headers
  published     BOOLEAN     DEFAULT FALSE,
  published_at  TIMESTAMP,
  created_at    TIMESTAMP   DEFAULT NOW(),
  retry_count   INT         DEFAULT 0
);

-- Index for the relay query: find unpublished messages in order
CREATE INDEX idx_outbox_unpublished ON outbox (created_at ASC)
  WHERE published = FALSE;
```

### Business Logic + Outbox in One Transaction

```java
@Service
public class OrderCommandHandler {

    @Transactional  // single database transaction
    public void handle(CreateOrderCommand cmd) {
        // 1. Load or create aggregate
        Order order = Order.create(cmd);

        // 2. Append events to event store (within the transaction)
        eventStore.append(order.getId(), 0, order.getUncommittedEvents());

        // 3. Write events to outbox (SAME transaction — atomic with step 2)
        // If this transaction commits, BOTH the event store and outbox are updated.
        // If it rolls back, neither is written. No dual-write problem.
        for (DomainEvent event : order.getUncommittedEvents()) {
            outboxRepository.save(new OutboxMessage(
                "order-events",
                order.getId().toString(),  // partition key: same order → same partition → ordered
                event.getClass().getSimpleName(),
                objectMapper.writeValueAsString(event),
                buildHeaders(event)
            ));
        }

        order.clearUncommittedEvents();
    }
}
```

### Outbox Relay — Publishing to Kafka

```java
/**
 * Two approaches for the relay:
 * A) Polling: SELECT unpublished rows every 100ms, publish, mark as published.
 *    Simple but adds ~100ms latency to event delivery.
 *
 * B) Change Data Capture (CDC) via Debezium:
 *    Debezium tails PostgreSQL WAL (Write-Ahead Log) and publishes changes to Kafka.
 *    Near-zero latency, no polling overhead.
 *    More complex: requires Debezium cluster + Kafka Connect.
 *
 * For most systems, polling is sufficient and simpler to operate.
 */
@Scheduled(fixedDelay = 100)  // run every 100ms
@Transactional
public void relayMessages() {
    // Fetch batch of unpublished messages, ordered by creation time
    List<OutboxMessage> pending = outboxRepository.findUnpublished(100);

    for (OutboxMessage msg : pending) {
        try {
            // Publish to Kafka — synchronous send with acknowledgment
            ProducerRecord<String, String> record = new ProducerRecord<>(
                msg.getTopic(),
                msg.getPartitionKey(),
                msg.getPayload()
            );
            // Add headers for correlation, event type, etc.
            msg.getHeaders().forEach((k, v) -> record.headers().add(k, v.getBytes()));

            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);  // wait for ack

            // Mark as published — same transaction
            outboxRepository.markPublished(msg.getId());

        } catch (Exception e) {
            // Increment retry count; alert if retry_count > threshold
            outboxRepository.incrementRetry(msg.getId());
            log.error("Failed to relay message {}: {}", msg.getId(), e.getMessage());
        }
    }
}
```

**Why Kafka's at-least-once delivery combined with idempotent consumers is the correct model:**

The outbox relay may publish the same message twice (if it crashes after Kafka ack but before marking the row as published in the DB). This is why every Kafka consumer must be idempotent — processing the same event twice must produce the same result.

---

## Saga Pattern: Distributed Transaction Without 2PC

### Choreography vs. Orchestration

**Choreography (chosen for this design):** Each service reacts to events and emits new events. No central coordinator. Services are loosely coupled.

```
Order Service:      emit OrderCreatedEvent
Payment Service:    consume OrderCreatedEvent → charge → emit PaymentSucceededEvent or PaymentFailedEvent
Inventory Service:  consume PaymentSucceededEvent → reserve → emit InventoryReservedEvent or InventoryFailedEvent
Shipping Service:   consume InventoryReservedEvent → schedule → emit OrderShippedEvent
Order Service:      consumes all → updates order status
```

Advantages: simple, no single point of failure, services are independent.
Disadvantages: hard to see the full flow in one place; compensating logic is distributed across services; tracing requires distributed tracing tools.

**Orchestration:** A central Saga Orchestrator (state machine) sends commands to services and reacts to their responses.

```
Saga Orchestrator:
  state: STARTED
  → send PaymentCommand to Payment Service
  ← receive PaymentSucceededEvent
  state: PAYMENT_DONE
  → send ReserveInventoryCommand to Inventory Service
  ← receive InventoryReservedEvent
  state: INVENTORY_RESERVED
  → send ShipOrderCommand to Shipping Service
```

Advantages: the flow is visible in one place; easier to reason about compensation.
Disadvantages: the orchestrator becomes a central coupling point; it can become a bottleneck.

**Rule of thumb:** Use choreography for simple, linear flows. Use orchestration when flows have complex branching, parallel steps, or require explicit visibility.

### Choreography Implementation

```java
// Payment Service — consumes OrderCreatedEvent, emits PaymentSucceededEvent or PaymentFailedEvent
@KafkaListener(topics = "order-events", groupId = "payment-service")
public void onOrderEvent(ConsumerRecord<String, String> record) {
    DomainEvent event = deserialize(record.value());

    // Only process events this service cares about
    if (!(event instanceof OrderCreatedEvent orderCreated)) return;

    // Idempotency check FIRST — before any business logic
    if (processedEventRepository.exists(event.getEventId())) {
        log.info("Duplicate event {}, skipping", event.getEventId());
        return;
    }

    try {
        // Execute payment charge
        PaymentResult result = paymentGateway.charge(
            orderCreated.getCustomerId(),
            orderCreated.getTotalPrice(),
            orderCreated.getCurrency()
        );

        if (result.isSuccessful()) {
            // Publish success event (via outbox for reliability)
            publishViaOutbox("payment-events", new PaymentSucceededEvent(
                UUID.randomUUID(),
                orderCreated.getOrderId(),
                result.getPaymentId(),
                result.getAmount(),
                result.getCurrency(),
                result.getPaymentMethod(),
                Instant.now(),
                orderCreated.getCorrelationId(),  // propagate correlation ID
                event.getEventId()                // causation: this event caused the response
            ));
        } else {
            publishViaOutbox("payment-events", new PaymentFailedEvent(
                UUID.randomUUID(),
                orderCreated.getOrderId(),
                result.getFailureReason(),
                result.getFailureCode(),
                Instant.now(),
                orderCreated.getCorrelationId(),
                event.getEventId()
            ));
        }

        // Mark event as processed (idempotency record)
        processedEventRepository.save(event.getEventId());

    } catch (Exception e) {
        // Don't mark as processed — Kafka will redeliver
        // Ensure payment gateway calls are idempotent (use orderCreated.orderId as idempotency key)
        log.error("Failed to process payment for order {}: {}", orderCreated.getOrderId(), e.getMessage());
        throw e;  // triggers Kafka retry with backoff
    }
}
```

### Compensating Transactions

When Inventory Service fails after Payment has succeeded, we must refund:

```java
// Inventory Service consumes PaymentSucceededEvent
@KafkaListener(topics = "payment-events", groupId = "inventory-service")
public void onPaymentEvent(ConsumerRecord<String, String> record) {
    DomainEvent event = deserialize(record.value());
    if (!(event instanceof PaymentSucceededEvent paymentSucceeded)) return;

    if (processedEventRepository.exists(event.getEventId())) return;

    InventoryResult result = inventoryService.reserve(
        paymentSucceeded.getOrderId(),
        orderService.getItems(paymentSucceeded.getOrderId())
    );

    if (result.isSuccessful()) {
        publishViaOutbox("inventory-events", new InventoryReservedEvent(...));
    } else {
        // Cannot reserve inventory — trigger compensation: refund the payment
        publishViaOutbox("inventory-events", new InventoryFailedEvent(
            UUID.randomUUID(),
            paymentSucceeded.getOrderId(),
            result.getFailureReason(),
            Instant.now(),
            paymentSucceeded.getCorrelationId(),
            event.getEventId()
        ));
    }

    processedEventRepository.save(event.getEventId());
}

// Payment Service consumes InventoryFailedEvent → refund
@KafkaListener(topics = "inventory-events", groupId = "payment-service-compensation")
public void onInventoryEvent(ConsumerRecord<String, String> record) {
    DomainEvent event = deserialize(record.value());
    if (!(event instanceof InventoryFailedEvent inventoryFailed)) return;

    if (processedEventRepository.exists(event.getEventId())) return;

    // Compensate: refund the payment
    paymentGateway.refund(inventoryFailed.getOrderId());
    publishViaOutbox("payment-events", new PaymentRefundedEvent(...));

    processedEventRepository.save(event.getEventId());
}
```

---

## Idempotent Event Consumers — Critical for Correctness

At-least-once delivery means consumers may receive the same event multiple times (e.g., consumer crashes after processing but before committing offset). Without idempotency, this causes duplicate charges, duplicate inventory deductions, or duplicate shipments.

```java
/**
 * Idempotency can be implemented at different levels:
 *
 * Level 1: In-memory dedup (only works within single instance, not useful)
 * Level 2: Redis-based dedup (fast, but Redis failure loses the record)
 * Level 3: Database-based dedup (durable, correct, slightly slower)
 *
 * Use Level 3 for financial operations, Level 2 for non-critical operations.
 */
@Service
public class IdempotentEventProcessor {

    /**
     * Execute business logic exactly once per event, even if the event
     * is delivered multiple times by Kafka.
     *
     * Uses a processed_events table with the event_id as the primary key.
     * The insert is atomic: if the event_id already exists, we skip processing.
     */
    @Transactional
    public void processIdempotently(UUID eventId, Runnable businessLogic) {
        // Try to insert the event ID as "being processed"
        // If this insert fails (duplicate key), the event was already processed
        boolean inserted = processedEventRepository.tryInsert(eventId);

        if (!inserted) {
            log.debug("Event {} already processed, skipping", eventId);
            return;
        }

        // Execute business logic within the same transaction
        // If business logic throws → transaction rolls back → event_id record is NOT saved
        // → Kafka redelivers → next attempt will process it
        businessLogic.run();
    }
}
```

```sql
CREATE TABLE processed_events (
  event_id    UUID        PRIMARY KEY,
  processed_at TIMESTAMP  NOT NULL DEFAULT NOW(),
  consumer    VARCHAR(100) NOT NULL   -- which service processed it (for debugging)
);

-- Auto-cleanup old records (events older than 7 days won't be re-delivered)
CREATE INDEX idx_processed_events_time ON processed_events (processed_at);
-- Background job: DELETE FROM processed_events WHERE processed_at < NOW() - INTERVAL '7 days';
```

---

## Read Model: CQRS Projections

The query side maintains denormalized views built by consuming events. These views are optimized for the specific queries clients make — not for writes.

```sql
-- Denormalized order view: everything a client needs in one row
-- Updated by the projection handler consuming all order/payment/shipping events
CREATE TABLE order_view (
  order_id          UUID    PRIMARY KEY,
  customer_id       UUID    NOT NULL,
  customer_name     VARCHAR(255),       -- denormalized from customer service
  customer_email    VARCHAR(255),
  status            VARCHAR(50)  NOT NULL,
  status_display    VARCHAR(100),       -- human-readable: "Preparing your order"
  total_amount      DECIMAL(10,2) NOT NULL,
  currency          CHAR(3)      NOT NULL,
  item_count        INT          NOT NULL,
  items             JSONB        NOT NULL,  -- full item details denormalized
  created_at        TIMESTAMP    NOT NULL,
  paid_at           TIMESTAMP,
  inventory_reserved_at TIMESTAMP,
  shipped_at        TIMESTAMP,
  delivered_at      TIMESTAMP,
  cancelled_at      TIMESTAMP,
  tracking_code     VARCHAR(100),
  estimated_delivery DATE,
  shipping_address  JSONB,
  payment_id        UUID,
  payment_method    VARCHAR(50),
  last_event_version INT NOT NULL      -- for detecting stale updates
);

CREATE INDEX idx_order_view_customer ON order_view (customer_id, created_at DESC);
CREATE INDEX idx_order_view_status ON order_view (status, created_at DESC);
```

### Projection Handler

```java
/**
 * Projection handler builds and updates the read model by consuming events.
 *
 * Key design: projections are IDEMPOTENT and can be REBUILT from scratch.
 * If you need to add a new field to order_view, you can:
 * 1. Truncate order_view
 * 2. Replay all events from event_store
 * 3. The projection rebuilds automatically
 *
 * This is the "time travel" superpower of event sourcing.
 */
@KafkaListener(
    topics = {"order-events", "payment-events", "inventory-events", "shipping-events"},
    groupId = "order-view-projection"
)
public void project(ConsumerRecord<String, String> record) {
    DomainEvent event = deserialize(record.value());

    // All projection updates are idempotent by design:
    // - OrderCreatedEvent: INSERT ... ON CONFLICT DO NOTHING
    // - PaymentSucceededEvent: UPDATE order_view SET paid_at = ... WHERE paid_at IS NULL
    // - etc. — each update is safe to replay multiple times

    switch (event) {
        case OrderCreatedEvent e -> {
            orderViewRepository.insertIfAbsent(OrderView.builder()
                .orderId(e.getOrderId())
                .customerId(e.getCustomerId())
                .status("CREATED")
                .statusDisplay("Order received")
                .totalAmount(e.getTotalPrice())
                .currency(e.getCurrency())
                .items(e.getItems())
                .itemCount(e.getItems().size())
                .createdAt(e.getOccurredAt())
                .lastEventVersion(1)
                .build());
        }

        case PaymentSucceededEvent e -> {
            orderViewRepository.updatePayment(
                e.getOrderId(),
                "PAID",
                "Payment confirmed — preparing your order",
                e.getPaymentId(),
                e.getPaymentMethod(),
                e.getOccurredAt()
            );
        }

        case InventoryReservedEvent e -> {
            orderViewRepository.updateStatus(
                e.getOrderId(),
                "INVENTORY_RESERVED",
                "Items reserved — ready for shipping",
                e.getOccurredAt()
            );
        }

        case OrderShippedEvent e -> {
            orderViewRepository.updateShipped(
                e.getOrderId(),
                "SHIPPED",
                "Your order is on its way!",
                e.getTrackingCode(),
                e.getEstimatedDelivery(),
                e.getOccurredAt()
            );
        }

        case OrderCancelledEvent e -> {
            orderViewRepository.updateCancelled(
                e.getOrderId(),
                "CANCELLED",
                "Order cancelled: " + e.getReason(),
                e.getOccurredAt()
            );
        }
    }
}
```

### Projection Rebuilding from Scratch

```java
/**
 * Rebuild the entire order_view projection from the event store.
 * Used when:
 * - Adding a new field to order_view requires backfilling historical data
 * - A bug in the projection handler corrupted some records
 * - Migrating to a new projection schema
 *
 * Procedure:
 * 1. Build new projection in a separate table (order_view_new)
 * 2. Switch traffic to new table atomically
 * 3. Drop old table
 *
 * Or: truncate and rebuild (acceptable for systems with replay capability)
 */
@Scheduled(cron = "0 3 * * * ?")  // run at 3am as a maintenance operation
public void rebuildProjection() {
    log.info("Starting projection rebuild...");

    // Stream all events from event_store in order
    // Use keyset pagination to avoid loading all events into memory
    UUID lastId = null;
    int batchSize = 1000;
    int totalProcessed = 0;

    do {
        List<EventRecord> batch = eventStore.streamAllAfter(lastId, batchSize);
        for (EventRecord record : batch) {
            DomainEvent event = deserialize(record.getEventType(), record.getPayload());
            projectEvent(event);  // same logic as Kafka consumer
        }
        if (!batch.isEmpty()) {
            lastId = batch.get(batch.size() - 1).getId();
        }
        totalProcessed += batch.size();
        log.info("Rebuilt {} events so far", totalProcessed);
    } while (true /* until batch is empty */);

    log.info("Projection rebuild complete. Total events: {}", totalProcessed);
}
```

---

## Event Schema Evolution

As the system evolves, event schemas change. Consumers that were built for v1 of an event must not break when v2 is published.

```java
// Strategy 1: Upcasting — convert old event format to new on the way out
// The event store stores raw JSON; an upcaster chain transforms it before deserialization

public class OrderCreatedEventV1Upcaster implements Upcaster {
    @Override
    public boolean canUpcast(String eventType, int version) {
        return "OrderCreatedEvent".equals(eventType) && version == 1;
    }

    @Override
    public JsonNode upcast(JsonNode payload) {
        // v1 had "customer_id"; v2 renamed to "customerId" and added "currency"
        ObjectNode upgraded = payload.deepCopy();
        upgraded.set("customerId", payload.get("customer_id"));
        upgraded.remove("customer_id");
        if (!upgraded.has("currency")) {
            upgraded.put("currency", "USD");  // default for old events
        }
        return upgraded;
    }
}

// Strategy 2: Backward-compatible additions only
// Never remove fields — mark them @Deprecated
// Never rename fields — add a new field with the new name
// Never change field types — add a new field with the new type
public record OrderCreatedEvent(
    UUID eventId,
    UUID orderId,
    @Deprecated UUID customer_id,   // v1 field — kept for backward compat
    UUID customerId,                 // v2 field — new name
    List<OrderItem> items,
    BigDecimal totalPrice,
    String currency                  // v2 addition (was missing in v1)
) {}
```

---

## Data Model Summary

```sql
-- Write side (event sourcing)
event_store          -- append-only events
aggregate_snapshots  -- performance optimization for long aggregates
outbox               -- guaranteed event delivery buffer

-- Read side (CQRS projections)
order_view           -- denormalized order status for queries
order_timeline_view  -- event-by-event timeline for UI display

-- Infrastructure
processed_events     -- idempotency tracking for consumers
```

---

## API Design

```
# Command Side (writes — return 202 Accepted for async processing)
POST /orders                       → CreateOrderCommand
POST /orders/{orderId}/cancel      → CancelOrderCommand

# Query Side (reads — return 200 OK immediately from read model)
GET  /orders/{orderId}             → read model (denormalized order_view)
GET  /orders/{orderId}/events      → full event history for audit/debug
GET  /orders/{orderId}/timeline    → visual state machine with timestamps
GET  /orders?customerId=X&status=SHIPPED&page=0&size=20

# Admin / Operations
POST /admin/projections/rebuild    → trigger full projection rebuild
GET  /admin/saga/{correlationId}   → trace entire saga across services
```

---

## Tech Stack

| Component | Technology | Why |
|---|---|---|
| Backend | Java 17, Spring Boot | Sealed interfaces for events, records for immutable event data |
| Event Bus | Kafka | Durable, ordered per partition, consumer group replay |
| Event Store | PostgreSQL | ACID, UNIQUE constraint for optimistic concurrency |
| Read DB | PostgreSQL read replica or ElasticSearch | Denormalized projections for fast reads |
| Schema Registry | Confluent Schema Registry + Avro | Schema validation and evolution management |
| CDC (optional) | Debezium | WAL-based outbox relay for near-zero latency |
| Tracing | OpenTelemetry + Jaeger | Trace saga steps across services via correlation_id |
| Monitoring | Prometheus + Grafana | Event lag, consumer group offset, saga failure rate |

---

## Interview Q&A

**Q1: Why use Event Sourcing instead of just storing the current state? Isn't it more complex?**

Event Sourcing is more complex than CRUD but pays dividends in specific scenarios. The key advantages are: complete audit trail (every state change is recorded with who, when, and why), temporal queries (reconstruct the state of any aggregate at any point in time), and the ability to add new projections retroactively by replaying events. For an order management system in e-commerce or fintech, auditors and regulators require proof of every state change — Event Sourcing provides this for free. The complexity cost (replaying events to reconstitute state, managing projections, handling schema evolution) is justified when auditability and temporal querying are first-class requirements. For simple CRUD systems like a blog or a recipe manager, Event Sourcing adds unnecessary complexity.

**Q2: What happens if a service is down when an event is published? Does the event get lost?**

The Outbox Pattern ensures events are never lost. Events are written to the outbox table in the same database transaction as the business logic. If Kafka is down or the service crashes, the event stays in the outbox as `published = FALSE`. The outbox relay periodically queries for unpublished events and retries publishing. Once Kafka is healthy, the relay publishes all pending events and marks them as published. The key guarantee is: if the business transaction committed, the event WILL eventually be delivered to Kafka — even if it takes minutes due to a Kafka outage. This is at-least-once delivery. Kafka's consumer groups remember their offset, so when a consumer restarts after downtime, it continues from where it left off.

**Q3: How do you handle a Saga that is stuck because an event never arrives (e.g., Payment Service is dead for 2 hours)?**

Each Saga step should have a timeout. The Saga Orchestrator (or a dedicated saga monitor in choreography) tracks the expected next event and raises a timeout event if it doesn't arrive within a configured window. For example: if `PaymentSucceededEvent` or `PaymentFailedEvent` does not arrive within 30 minutes of `OrderCreatedEvent`, a `PaymentTimedOutEvent` is raised, which triggers compensation (cancel the order). The monitoring service queries the event store for orders stuck in `PAYMENT_PENDING` state for more than 30 minutes. This is typically implemented as a scheduled job that checks for stale saga states and emits timeout events.

**Q4: How do you ensure the projection (read model) is consistent enough for user-facing reads, given it lags behind the event store?**

The typical lag is milliseconds to seconds — from event publication to Kafka to projection consumer to database write. For most user-facing reads, this is imperceptible. However, for reads immediately after a write (e.g., user creates an order and immediately sees their order list), the new order may not appear yet. Mitigation strategies: (1) Return the created order directly in the POST /orders response — don't wait for the projection. (2) Include a version field in the response and have the GET endpoint check if the projection has caught up to that version. (3) Read your own writes: for the creating user, return data from the command side (event store) immediately after creation, falling back to the projection for subsequent requests. (4) Accept the lag and inform users that their order history updates within seconds.

**Q5: How does optimistic concurrency work in the event store, and what happens on a conflict?**

The `UNIQUE (aggregate_id, event_version)` constraint in the event store prevents two concurrent commands on the same aggregate from both succeeding. Each command handler loads the current events (getting the current version N), processes the command to produce new events (to be saved at version N+1), and attempts to insert at version N+1. If another command was processed concurrently and already wrote version N+1, the insert fails with a duplicate key error. The application catches this `OptimisticConcurrencyException`, reloads the aggregate from the event store (now at version N+1), and re-applies the command. This retry is correct because the command handler is a pure function of the aggregate state — replaying it on the updated state produces the right result. If the command is no longer valid after seeing the new state (e.g., cancel an already-cancelled order), it throws a business exception instead of retrying.

**Q6: How would you add a new reporting feature that needs data from three different services (Order, Payment, Shipping) combined?**

This is exactly what projections are for. Create a new projection that consumes events from the `order-events`, `payment-events`, and `shipping-events` Kafka topics and builds a new denormalized view combining data from all three. Because all events are in Kafka (or the event store) indefinitely (or for a configured retention period), you can build this new projection by replaying historical events — no data migration needed. The projection handler processes events in order and accumulates the necessary data. This is the "retroactive feature" superpower of event-driven systems: you can add new views of historical data without modifying the services that produced the events.

**Q7: What are the failure scenarios in a choreography Saga, and how do you detect them?**

Failure scenarios in choreography:
1. A service consumes an event but crashes before completing its step — the event is redelivered (Kafka offset not committed), and the service processes it again (requires idempotency).
2. A compensating event (e.g., `InventoryFailedEvent`) is lost — the payment is not refunded, creating an inconsistent state.
3. A service is down for an extended period — events pile up in Kafka; when the service recovers, it processes them in order (Kafka durability guarantees this).
4. Events arrive out of order — possible if using multiple Kafka partitions; mitigated by using the order ID as the partition key so all events for one order go to the same partition.

Detection: implement a Saga Monitor that tracks expected event sequences. If a saga enters a terminal state (confirmed or cancelled), it's healthy. If a saga is stuck between states for more than N minutes, it's flagged for manual investigation or automatic timeout compensation. Distributed tracing (OpenTelemetry) with correlation IDs is essential for debugging stuck sagas.

**Q8: How do you handle schema evolution when an old event version is in the event store but new consumers expect a new schema?**

Use the Upcaster pattern: before deserializing an event from the event store, run it through an upcaster chain that transforms old JSON payloads to the current schema. This is transparent to the consumer — it always sees the latest schema regardless of what version is stored. Upcast rules: never remove fields (mark deprecated), add new fields with sensible defaults (so old events get the default when upcasted), never change field types. Register all schema changes in the Confluent Schema Registry to enforce backward/forward compatibility at publish time. The registry can be configured to reject schemas that are not backward compatible, catching breaking changes before they reach production.

**Q9: How would you implement the "Order Timeline" view that shows every state transition with timestamps?**

```sql
CREATE TABLE order_timeline_view (
  order_id      UUID NOT NULL,
  event_version INT  NOT NULL,
  event_type    VARCHAR(100) NOT NULL,
  display_text  VARCHAR(255),
  occurred_at   TIMESTAMP NOT NULL,
  actor         VARCHAR(100),   -- who triggered this (user, system, payment-service)
  metadata      JSONB,          -- any additional context
  PRIMARY KEY (order_id, event_version)
);
```

The projection handler appends one row per event, in version order. This table is never updated — only inserted. The `GET /orders/{id}/timeline` endpoint reads all rows for the order ID ordered by `event_version`, which is O(1) per event. The timeline view is trivially rebuilt by replaying all events for all orders. This design elegantly handles the requirement for showing "what happened and when" without any complex state machine reconstruction at read time.

**Q10: What is the difference between correlation_id and causation_id, and why do you need both?**

`correlation_id` traces a single business flow across all services and all events. If you create an order, every subsequent event related to that order — the payment, inventory reservation, shipment — carries the same `correlation_id`. This allows you to query "give me all events for business flow X" and see the complete picture in Jaeger or Kibana.

`causation_id` points to the specific event that directly caused the current event. `OrderCreatedEvent` causes `PaymentSucceededEvent` (because Payment Service consumed the OrderCreatedEvent). `PaymentSucceededEvent` causes `InventoryReservedEvent`. This creates a causal chain — a directed acyclic graph of event causation. You can use causation_id to answer "why did this event happen?" by tracing backwards through the chain. Together, correlation_id gives you the full saga scope, and causation_id gives you the exact causal relationship between events.

**Q11: How would you handle "exactly once" semantics when processing financial events like payment charges?**

True exactly-once semantics in distributed systems is extremely difficult to achieve. The practical approach is at-least-once delivery combined with idempotent processing. For payment charges: when Payment Service processes `OrderCreatedEvent`, it calls the payment gateway with an idempotency key = `orderCreated.orderId`. If the service crashes and redelivers the event, the payment gateway recognizes the idempotency key and returns the result of the first charge without charging again. The processed_events table in the database prevents the service from calling the payment gateway twice for the same event (the first check catches the duplicate before making the gateway call). This combination — gateway idempotency + consumer-side deduplication — achieves exactly-once charging behavior even with at-least-once delivery infrastructure.

**Q12: How do you test a system with event sourcing and CQRS? What are the unique testing challenges?**

Testing event-sourced systems has a natural structure: Given (event history) → When (command) → Then (expected events). Unit tests for aggregates are clean: provide a list of past events to reconstitute the aggregate, apply a command, assert on the emitted events. No mocks, no databases needed.

Integration testing is more complex because of the temporal decoupling — commands and queries don't share a request-response cycle. Test techniques: (1) Publish events directly to the event store and verify the projection is built correctly. (2) Use embedded Kafka for end-to-end Saga tests. (3) Use TestContainers for PostgreSQL. (4) Use the "Saga Monitor" to wait for a saga to reach a terminal state before asserting. The key insight for testing is: if your aggregates, projections, and event handlers are pure functions of events, they are trivially unit-testable without any infrastructure. Infrastructure concerns (Kafka, DB) are tested in integration tests.

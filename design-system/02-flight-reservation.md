# Flight Reservation System

## Why This Problem Matters

Flight reservation is one of the most demanding real-world systems because it combines multiple hard problems simultaneously: **inventory management under contention** (two users racing to book the last seat), **distributed transactions across services** (booking + payment + notification must all succeed or all fail), and **complex business rules** (fare classes, refund policies, overbooking strategies). Airlines run on systems like Amadeus and Sabre that handle billions of dollars in inventory daily.

For system design interviews, this problem tests your ability to reason about **consistency vs availability tradeoffs** (when is eventual consistency acceptable? when is it dangerous?), **Saga pattern** vs 2-phase commit, and **optimistic locking** as a coordination mechanism.

---

## Key Insight Before Diving In

The hardest part is NOT the search or booking UI. It's the **seat inventory consistency problem**: how do you prevent two users from booking the same seat simultaneously, at global scale, without making the system slow or unavailable?

The answer is: **don't use distributed locks**. Use **database-level optimistic locking** and **compensation (Saga)** instead. Here's why that matters.

---

## Requirements

### Functional
- Search flights by origin, destination, date, passenger count
- View available seats by class (Economy, Business, First)
- Reserve a seat (hold for 10 minutes while user pays)
- Complete booking after payment succeeds
- Cancel booking with configurable refund rules
- Manage overbooking (airlines intentionally oversell 5-10%)
- E-ticket generation and email delivery

### Non-Functional
- **No double booking**: the absolute #1 constraint — two users must never book the same seat
- Search: eventual consistency OK (showing stale availability is acceptable)
- Booking: **strong consistency required** (cannot be eventually consistent)
- 10M searches/day (read-heavy), 500k bookings/day (write-moderate)
- Booking operation must complete in < 3 seconds (payment included)
- System must handle peak loads: holidays, sale events (10x normal)

---

## Capacity Estimation

```
Search:
  10M/day ÷ 86,400 = ~116 searches/sec avg
  Peak (holiday sale): 1,160 searches/sec
  Each search: ElasticSearch query (< 50ms)

Booking:
  500k/day ÷ 86,400 = ~6 bookings/sec avg
  Peak: 60 bookings/sec
  Each booking: DB transaction (< 100ms) + payment API (< 2s)

Inventory:
  100k flights/day × 200 seats avg = 20M active seat records
  Each seat record: ~500B → 10GB total (fits in DB + cache easily)

Reservation holds:
  At any moment: peak 60 bookings/sec × 10min hold = 36,000 active holds
  These seats appear "reserved" in inventory during hold window
```

---

## The Core Challenge: Preventing Double Booking

Imagine User A and User B both see seat 14A as available. They both click "Book" simultaneously. Without protection, both could succeed and create a conflict.

### Why Pessimistic Locking Doesn't Scale

```sql
-- Pessimistic: lock the row immediately
SELECT * FROM seats WHERE seat_id = '14A' FOR UPDATE;
-- This blocks ALL other readers/writers until commit
-- At 60 concurrent bookings × 10min payment time = database chaos
```

Pessimistic locks held during payment (which takes 1-3 seconds) would serialize all bookings through the database. At 60 concurrent users paying, they'd queue behind each other → terrible UX and throughput.

### Why Optimistic Locking is the Right Answer

Optimistic locking assumes conflicts are rare. It doesn't lock the row. Instead, it checks at write time whether the data changed since we read it.

```sql
-- Read (no lock):
SELECT seat_id, status, version FROM seats WHERE seat_id = '14A';
-- Returns: { status: AVAILABLE, version: 7 }

-- Write (conditional update):
UPDATE seats
SET status = 'RESERVED', booking_id = 'BK-001', version = 8
WHERE seat_id = '14A' AND status = 'AVAILABLE' AND version = 7;
-- Returns 1 row updated → success
-- Returns 0 rows updated → someone else got there first → retry or fail
```

If two users try simultaneously, only one UPDATE wins (version mismatch for the other). The loser gets 0 rows updated → show "seat unavailable, please choose another."

This is fast (no blocking), scalable, and correct.

---

## High-Level Architecture

```
                         ┌──────────────────┐
                         │   Client (Web/App)│
                         └────────┬─────────┘
                                  │ HTTPS
                         ┌────────▼─────────┐
                         │   API Gateway    │
                         │ Auth/RateLimit   │
                         └──┬───────────┬───┘
                            │           │
              ┌─────────────▼──┐  ┌─────▼──────────┐
              │  Search        │  │  Booking        │
              │  Service       │  │  Service        │
              └──────┬─────────┘  └────────┬────────┘
                     │                     │
           ┌─────────▼──┐       ┌──────────▼────────┐
           │ElasticSearch│      │  Inventory         │
           │(denormalized│      │  Service           │
           │ flight data)│      └──────────┬─────────┘
           └─────────────┘                 │
                              ┌────────────▼─────────┐
                              │   PostgreSQL          │
                              │ (seats, bookings,     │
                              │  optimistic lock)     │
                              └──────────────────────┘

Async (after booking confirmed):
  Booking Service → Kafka → Notification Service → Email/SMS
                          → Analytics Service
                          → Loyalty Service (miles)
```

---

## Booking Flow — Step by Step

```
Step 1: Search
  User searches CDG → JFK, June 1
  ElasticSearch returns flights (near real-time, may be slightly stale)
  ← OK: eventual consistency acceptable for search

Step 2: Select Seat
  User selects seat 14A (Economy, $450)
  GET /flights/{id}/seats → query PostgreSQL for exact availability
  ← Must be fresh: read from primary DB replica, not cache

Step 3: Hold (Temporary Reservation)
  POST /bookings { flightId, seatId, passengerId }
  → BEGIN TRANSACTION
  → UPDATE seats SET status='RESERVED', version=version+1
     WHERE seat_id='14A' AND status='AVAILABLE' AND version=7
  → If 0 rows: return 409 (seat taken)
  → INSERT booking { status='PENDING', expires_at=NOW()+10min }
  → COMMIT
  ← Return: { booking_id, expires_in: 600s }

Step 4: Payment
  User enters card details (handled client-side by Stripe.js, never touches our servers)
  POST /bookings/{id}/pay { payment_method_token }
  → Call Stripe API: charge $450
  → On success: UPDATE booking SET status='CONFIRMED'
  → On failure: UPDATE booking SET status='CANCELLED'
               UPDATE seats SET status='AVAILABLE' (compensate)

Step 5: Confirmation
  Async: send email with e-ticket PDF
  Async: update ElasticSearch availability index
  Async: credit loyalty miles
```

---

## Handling Expired Holds

When a user starts a booking but abandons before paying (browser closes, card declined, timeout):

```java
// Background job runs every minute
@Scheduled(fixedRate = 60_000)
public void releaseExpiredHolds() {
    List<Booking> expired = bookingRepo.findByStatusAndExpiresAtBefore(
        BookingStatus.PENDING, LocalDateTime.now()
    );
    for (Booking booking : expired) {
        // Compensate: release the seat
        seatRepo.updateStatus(booking.getSeatId(), SeatStatus.AVAILABLE);
        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepo.save(booking);
    }
}
```

**Why 10 minutes?** Long enough for user to fill payment form. Short enough to not hold inventory from other buyers.

---

## Saga Pattern for Cross-Service Coordination

When booking spans multiple services (inventory + payment + notification), we need to handle partial failures. We do NOT use 2-Phase Commit (too slow, requires all services to lock simultaneously). Instead, we use **Choreography-based Saga** with compensating transactions.

```
Normal flow:
BookingService:   reserve_seat()           → emit SeatReservedEvent
PaymentService:   charge_card()            → emit PaymentSucceededEvent
NotifService:     send_confirmation_email() → emit NotificationSentEvent
AnalyticsService: record_booking()

Failure compensation (payment fails):
PaymentService emits: PaymentFailedEvent
BookingService listens:
  → release_seat() (compensate step 1)
  → booking status = CANCELLED
  → emit BookingCancelledEvent
NotifService listens:
  → send_payment_failed_email()
```

Each service is responsible for its own rollback. No global coordinator needed.

---

## Data Model

```sql
-- Flights (semi-static, loaded from airline systems)
CREATE TABLE flights (
  id              UUID PRIMARY KEY,
  flight_number   VARCHAR(10) NOT NULL,
  airline         VARCHAR(50),
  origin          CHAR(3) NOT NULL,       -- IATA code: CDG, JFK
  destination     CHAR(3) NOT NULL,
  departure_at    TIMESTAMPTZ NOT NULL,
  arrival_at      TIMESTAMPTZ NOT NULL,
  aircraft_type   VARCHAR(50),
  status          VARCHAR(20) DEFAULT 'SCHEDULED'  -- SCHEDULED, DEPARTED, ARRIVED, CANCELLED
);

-- Seats (one row per seat per flight)
CREATE TABLE seats (
  id          UUID PRIMARY KEY,
  flight_id   UUID NOT NULL REFERENCES flights(id),
  seat_number VARCHAR(5) NOT NULL,     -- "14A", "2B"
  cabin_class VARCHAR(20) NOT NULL,   -- ECONOMY, BUSINESS, FIRST
  base_price  DECIMAL(10,2) NOT NULL,
  status      VARCHAR(20) DEFAULT 'AVAILABLE',  -- AVAILABLE, RESERVED, BOOKED, BLOCKED
  booking_id  UUID,
  version     INT DEFAULT 0,          -- CRITICAL: optimistic lock version
  UNIQUE(flight_id, seat_number)
);

-- Bookings
CREATE TABLE bookings (
  id              UUID PRIMARY KEY,
  passenger_id    UUID NOT NULL,
  seat_id         UUID NOT NULL REFERENCES seats(id),
  flight_id       UUID NOT NULL REFERENCES flights(id),
  status          VARCHAR(20) NOT NULL,  -- PENDING, CONFIRMED, CANCELLED, COMPLETED
  amount          DECIMAL(10,2) NOT NULL,
  currency        CHAR(3) DEFAULT 'EUR',
  payment_id      VARCHAR(255),          -- Stripe payment intent ID
  booked_at       TIMESTAMPTZ DEFAULT NOW(),
  expires_at      TIMESTAMPTZ,           -- null if CONFIRMED
  cancelled_at    TIMESTAMPTZ,
  cancel_reason   TEXT
);

-- Passengers
CREATE TABLE passengers (
  id           UUID PRIMARY KEY,
  user_id      UUID,
  first_name   VARCHAR(100),
  last_name    VARCHAR(100),
  passport_no  VARCHAR(20) ENCRYPTED,  -- encrypted at rest
  nationality  CHAR(2),
  date_of_birth DATE
);
```

---

## Overbooking Strategy

Airlines intentionally sell more seats than exist (typically 5-10% overbooking). The system supports this:

```sql
-- Add overbooking capacity to flights
ALTER TABLE flights ADD COLUMN
  overbooking_capacity INT DEFAULT 0;  -- extra seats allowed

-- Allow bookings up to (total_seats + overbooking_capacity)
-- If overbooked passenger shows up, airline provides compensation (voucher/upgrade)
```

The inventory service checks:
```java
int availableSeats = totalSeats + overbookingCapacity - bookedSeats;
```

---

## API Design

```http
GET  /flights?from=CDG&to=JFK&date=2026-06-01&passengers=2&class=ECONOMY
GET  /flights/{flightId}
GET  /flights/{flightId}/seats?class=ECONOMY

POST /bookings
{
  "flight_id": "uuid",
  "seat_id": "uuid",
  "passenger": { "first_name": "Khai", "last_name": "Tran", "passport": "..." },
  "contact_email": "khai@example.com"
}
→ 201: { "booking_id": "...", "expires_in": 600, "status": "PENDING" }
→ 409: { "error": "seat_unavailable", "message": "Seat 14A is no longer available" }

POST /bookings/{bookingId}/pay
{ "payment_method": "pm_stripe_token" }
→ 200: { "status": "CONFIRMED", "ticket_url": "..." }
→ 402: { "error": "payment_failed", "message": "Card declined" }

DELETE /bookings/{bookingId}
→ 200: { "refund_amount": 450.00, "refund_eta_days": 5 }

GET /bookings/{bookingId}
GET /passengers/{id}/bookings
```

---

## Tech Stack

- **Backend**: Java 17, Spring Boot, Spring Data JPA
- **Search**: ElasticSearch (denormalized flight data, near real-time sync)
- **Database**: PostgreSQL (ACID, optimistic locking, FOR UPDATE)
- **Cache**: Redis (seat availability cache, TTL 30s — short because freshness matters)
- **Payment**: Stripe (PCI-DSS compliance via tokenization)
- **Queue**: Kafka (post-booking events: email, analytics, loyalty)
- **Notification**: AWS SES (e-ticket email) + Twilio (SMS)
- **PDF**: iText / JasperReports (e-ticket generation)

---

## Interview Q&A

**Q1: How do you prevent two users from booking the same seat simultaneously?**

A: Optimistic locking at the database level. Each seat row has a `version` column. When we reserve a seat, we do `UPDATE seats SET status='RESERVED', version=version+1 WHERE seat_id=? AND status='AVAILABLE' AND version=?`. If another user reserved the seat between our read and write, the version changed and our UPDATE affects 0 rows. We detect this and return 409 to the user. No database-level row locks are held during user think time or payment processing, so the system scales horizontally. We only hold a lock for the microseconds needed to execute the UPDATE statement.

---

**Q2: Why not use 2-Phase Commit (2PC) for the booking + payment transaction?**

A: 2PC requires all participating services (booking, payment, inventory) to hold locks on their resources while a coordinator decides commit or abort. This creates:
1. **Long-held locks** during payment API calls (1-3 seconds) → database contention
2. **Single point of failure**: if the coordinator crashes mid-transaction, all participants are stuck
3. **Tight coupling**: all services must implement the 2PC protocol

Saga is better: each service does its own local transaction and emits events. On failure, compensating transactions undo previous steps. No coordinator, no long-held locks, services can be deployed independently. The tradeoff: eventual consistency (brief window where booking exists but payment hasn't happened) — which is acceptable and clearly communicated to users via PENDING status.

---

**Q3: What happens if the payment succeeds but our system crashes before updating the booking to CONFIRMED?**

A: This is a critical failure scenario. Solutions:
1. **Idempotency key**: before charging, create a Stripe Payment Intent with our booking_id as idempotency key. If we crash and retry, Stripe returns the same payment (not double-charge).
2. **Reconciliation job**: runs every hour, finds bookings in PENDING state where Stripe shows payment succeeded → auto-confirm them.
3. **Outbox pattern**: write to DB and outbox table in same transaction. Outbox relay publishes to Kafka, which triggers the confirm flow. If crash happens after DB write, outbox relay retries publishing.

---

**Q4: How would you handle a flash sale where 10 million users try to book simultaneously?**

A: Flash sales are a specific variant of the thundering herd problem:
1. **Queue-based fairness**: instead of direct booking, users join a virtual queue. System processes queue FIFO. Front-end shows position estimate. Eliminates database overload.
2. **Inventory pre-allocation**: split inventory into shards (e.g., 10 servers each responsible for 10% of seats). Reduces contention per shard.
3. **Circuit breaker**: if DB response time > 2s, return "seat unavailable, try again" — better than crashing under load.
4. **Pessimistic rate limiting**: limit booking attempts per user per minute during sale.
5. **CDN for search**: search responses during sale come from CDN (slightly stale) — don't overwhelm search infra when everyone is browsing simultaneously.

---

**Q5: How would you design the search to show real-time availability without hammering the database?**

A: Layered approach:
- **ElasticSearch** for flight discovery (city, date, airline). Updated asynchronously when bookings are confirmed (Kafka → ES sync). May show slightly stale seat counts (acceptable: user still confirms actual availability at booking time).
- **Redis cache** for seat availability per flight (TTL: 30 seconds). Short TTL ensures freshness for high-demand flights.
- **DB query** only when user actually selects a specific flight to view exact seats. This is 1/100th the volume of search queries.

Never query PostgreSQL for search. Never show guaranteed availability in search results — always say "seats may be limited."

---

**Q6: How do you handle international date/timezone for departure times?**

A: Store all times as `TIMESTAMPTZ` (timestamp with time zone) in UTC in the database. This is non-negotiable. Display logic converts to local time using the airport's timezone (IATA provides timezone data per airport code). Never store local times in the DB — you will have bugs around DST transitions, which is especially bad for flights that cross DST boundaries.

```java
// Store
flight.setDepartureAt(ZonedDateTime.of(localTime, ZoneId.of("Europe/Paris")).toInstant());

// Display
String display = flight.getDepartureAt()
    .atZone(ZoneId.of(airportTimezone))
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
```

---

**Q7: What's the difference between a hold/reservation and a confirmed booking? Why have two states?**

A: A **hold** (PENDING) separates the act of claiming a seat from the act of paying. This gives users a grace period (typically 10-15 minutes) to complete payment without the seat being snatched mid-payment. The seat appears unavailable to others during this time. If payment fails or times out, the seat is released. A **confirmed booking** means payment succeeded and the transaction is complete. This two-phase approach balances user experience (enough time to pay) with fairness (seat released if user abandons).

---

**Q8: How would you implement the e-ticket generation and PDF delivery?**

A: This is a classic async, fire-and-forget workflow:
```
1. Booking confirmed → Kafka event: booking.confirmed
2. Ticket Service consumes event:
   a. Fetch booking details, passenger info, flight info
   b. Generate PDF with iText: barcode, seat map, flight details
   c. Upload PDF to S3: tickets/{booking_id}.pdf
   d. Generate pre-signed S3 URL (valid 30 days)
3. Email Service sends confirmation email with PDF link
4. Update booking record: ticket_url = presigned_s3_url
```

Never generate PDFs synchronously in the booking API call — it adds 200-500ms latency for something the user can receive a few seconds later.

---

**Q9: How would you handle the case where a flight is cancelled after bookings are made?**

A: Flight cancellation is a bulk operation triggering compensating actions for all affected bookings:
```
1. Airline system updates flight status = CANCELLED
2. Kafka event: flight.cancelled { flight_id, affected_bookings: [...] }
3. Booking Service:
   → For each affected booking: status = CANCELLED
   → Release all reserved/booked seats
4. Payment Service:
   → Full refund for all bookings (call Stripe refund API)
5. Notification Service:
   → Send cancellation email with rebooking options
6. Loyalty Service:
   → Restore any miles used for these bookings
```

All steps are idempotent — safe to retry if any step fails.

---

**Q10: How do you ensure the booking system is GDPR compliant?**

A: Key GDPR requirements for booking systems:
1. **Data minimization**: only collect what's needed (passport number for international, not for domestic)
2. **Encryption at rest**: passport numbers, personal data encrypted in DB (AES-256)
3. **Right to erasure**: `DELETE passenger WHERE id=?` — but booking records are needed for financial/legal purposes → anonymize passenger data, keep booking record
4. **Data retention**: define retention policy per data type. Booking records: 7 years (legal/financial). Personal data: delete after 2 years post-flight.
5. **Audit trail**: log all access to personal data (who read it, when, why)
6. **Consent**: clear opt-in for marketing communications
7. **Cross-border**: passenger data may not leave EU to non-adequate countries without SCC (Standard Contractual Clauses)

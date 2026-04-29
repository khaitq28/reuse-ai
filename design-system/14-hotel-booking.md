# Hotel Booking System — Deep Dive

## Problem Statement

Design a hotel booking system like Booking.com or Airbnb — where users search for hotels by location and dates, view real-time availability, reserve rooms atomically, pay, and manage cancellations with policy-based refunds.

---

## Why This Problem Matters

Hotel booking sits at the intersection of three genuinely hard problems in distributed systems: **inventory management under high contention**, **geospatial search at scale**, and **dynamic pricing**. Interviewers use this problem to test whether you understand:

1. **Why double-booking is catastrophic** — unlike e-commerce where overselling is recoverable (refund + restock), a hotel cannot physically add rooms at 11pm. The failure mode is a guest arriving with a confirmed reservation and no room.
2. **The atomicity challenge of multi-night bookings** — booking 5 nights requires locking 5 separate rows in an availability table simultaneously. This exposes concurrency bugs that many candidates miss.
3. **Read/write asymmetry** — searches massively outnumber bookings (10M:500K ratio). The architecture must decouple search infrastructure from booking infrastructure; they have fundamentally different consistency requirements.
4. **The false availability problem** — showing a room as available when it is actually reserved (but payment not yet confirmed) is nearly as bad as double-booking. The hold/payment/confirm flow must account for abandoned bookings.

The interviewer is testing: distributed transactions, optimistic vs pessimistic locking, CQRS thinking (separate read store for search), eventual consistency tolerance, and payment saga patterns.

---

## Key Insight Before Diving In

**Availability is per-room per-date, and booking N nights means touching N rows atomically.**

Most candidates think of availability as a single flag on a room. In reality, a room has an independent availability state for every date it could be booked. A 5-night booking from June 1–5 must check and lock rows for June 1, June 2, June 3, June 4, and June 5 — all within a single database transaction. If any single date is already booked, the entire transaction must roll back.

This means:
- You cannot use application-level locking (two concurrent requests can both read "available" before either writes).
- You must use database-level `SELECT ... FOR UPDATE` (pessimistic locking) or an optimistic version check.
- The number of rows locked scales linearly with trip length — long stays under high contention are the hardest case.

A secondary insight: **search and booking must be powered by different data stores**. Search needs full-text, geo-distance, and faceted filtering — that is ElasticSearch. Booking needs ACID guarantees on row-level locks — that is PostgreSQL. The two stores are eventually consistent with each other (search index lags slightly behind actual availability), and that is acceptable because the booking flow does a real-time DB check before confirming.

---

## Requirements

### Functional
- Search hotels by city, check-in/out dates, and guest count
- View hotel details, room types, photos, and reviews
- Check real-time room availability
- Book a room: hold → payment → confirm (three-phase flow)
- Cancel booking with policy-based refund calculation
- Hotel management portal: add/update rooms, set pricing and availability blocks
- Review and rating system (post-stay)
- Seasonal and event-based pricing rules

### Non-Functional
- **No double booking** — strong consistency on inventory is non-negotiable
- **Search latency** — P99 under 200ms; eventual consistency acceptable (search index may lag up to 30 seconds)
- **Scale** — 10M searches/day, 500k bookings/day
- **Booking operation** — end-to-end under 2 seconds (including payment)
- **Availability hold** — 10-minute timer; room released if payment not completed
- **Seasonal pricing** — price varies per date, not just per room

---

## Capacity Estimation

```
Search:
  10M searches/day → 116 req/sec average, peak 1,000 req/sec
  Each search query hits ElasticSearch → cache results for 5 minutes
  Cached searches: ~80% cache hit rate at peak

Bookings:
  500k bookings/day → 6 bookings/sec average, peak 50/sec
  Each booking: 1 DB transaction touching N availability rows

Hotels and rooms:
  500k hotels × avg 100 rooms = 50M rooms
  50M rooms × 365 nights = 18.25B room-night combinations/year
  Storage per row: ~100 bytes → 1.8TB for one year of availability data

Availability table size management:
  Partition by year: availability_2026, availability_2027
  Archive past dates (they never change, move to cold storage)

Search index:
  500k hotels × 2KB per document = 1GB ElasticSearch index (trivial)
  Hotel documents are small; the index is fast
```

The key constraint: the availability table is write-heavy (every booking modifies N rows) and must support concurrent readers who also want locks. Partition the table on `(room_id, date)` to ensure each booking touches minimal page ranges.

---

## High-Level Architecture

```
Client (Web / Mobile)
         │
     API Gateway (rate limiting, auth, routing)
         │
  ┌──────┴──────────────────────────────────────────┐
  │                                                   │
Search Service                              Booking Service
  │                                                   │
  ├── ElasticSearch (geo, text, facets)     ┌─────────┤
  ├── Redis (search result cache, 5min)     │         │
  └── async consumer of availability events │   Inventory DB
                                            │   (PostgreSQL)
                                            │         │
                                     Payment Service  │
                                     (Stripe / VNPay) │
                                            │         │
                                     Pricing Service  │
                                     (seasonal rates) │
                                                      │
                                         Kafka (events) ←─ booking events
                                              │
                             ┌────────────────┤
                      Notification       ES Updater
                      Service            (sync index)
                      (email/SMS)
```

**Why this split?**
- Search Service reads from ElasticSearch (read-optimized, eventual consistency acceptable)
- Booking Service reads/writes PostgreSQL (ACID, pessimistic locking)
- These two never talk to each other synchronously. Events via Kafka keep ES in sync asynchronously.
- Payment is a separate bounded context with its own retry/idempotency logic

---

## Room Availability: The Core Design Challenge

### Design Option 1: Row-Per-Date (Chosen Approach)

```sql
-- Each row represents one room on one date
CREATE TABLE room_availability (
  room_id      UUID        NOT NULL,
  date         DATE        NOT NULL,
  status       VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
  -- AVAILABLE: no booking
  -- RESERVED:  hold placed, awaiting payment (10-min TTL)
  -- BOOKED:    payment confirmed, room sold
  -- BLOCKED:   hotel manually blocked (maintenance, VIP hold)
  price        DECIMAL(10,2) NOT NULL,   -- effective price for this date (seasonal)
  booking_id   UUID,                     -- which booking holds/owns this date
  reserved_at  TIMESTAMP,               -- when the RESERVED status was set
  PRIMARY KEY (room_id, date)
);

-- Partial index for quick availability lookup — only index AVAILABLE rows
-- This makes the "find available rooms for date range" query very fast
CREATE INDEX idx_available_rooms ON room_availability (date, status)
  WHERE status = 'AVAILABLE';
```

**Why row-per-date over alternatives?**

Row-per-date is the canonical approach because:
1. It naturally handles partial-week bookings without complex interval arithmetic
2. `SELECT ... FOR UPDATE` can lock exactly the rows corresponding to requested nights
3. Seasonal pricing is trivially stored per row
4. Blocking specific dates (maintenance, events) is simple

**Design Option 2: Bitmap-Per-Room (alternative)**

```
room_availability_bitmap:
  room_id: UUID
  year:    INT
  bitmap:  BYTEA (365 bits, one per day — 1=available, 0=booked)
```

Advantages: extremely compact (46 bytes per room per year vs 100 bytes × 365 rows = 36.5KB)
Disadvantages:
- Atomic update of multiple bits requires CAS or full row lock on the entire year
- Storing per-date price is impossible — need a separate table anyway
- Bitwise operations in SQL are awkward and not portable
- Cannot store which booking ID owns which date

Conclusion: row-per-date wins for correctness and flexibility. The storage overhead (1.8TB/year) is manageable with date partitioning.

**Design Option 3: Booking Intervals (start_date, end_date)**

```sql
CREATE TABLE bookings (
  room_id    UUID,
  start_date DATE,
  end_date   DATE,     -- exclusive
  status     VARCHAR(20)
);
-- Check availability: no overlapping interval
SELECT COUNT(*) FROM bookings
WHERE room_id = $1
  AND start_date < $checkout
  AND end_date > $checkin
  AND status != 'CANCELLED';
```

Advantages: compact — one row per booking instead of N rows per booking
Disadvantages:
- Overlap queries are harder to index efficiently (range vs equality)
- Cannot represent partial-night pricing without a join
- Concurrent bookings for non-overlapping ranges but touching the same resource require careful predicate locking
- Harder to implement `FOR UPDATE` on a range overlap query

---

### Multi-Day Booking Atomicity — The Critical Implementation

```sql
-- WRONG approach (race condition):
-- Step 1: Check availability (SELECT)
SELECT COUNT(*) FROM room_availability
WHERE room_id = $1 AND date IN ($dates) AND status = 'AVAILABLE';
-- >> returns 3 (all available)

-- Step 2: Update (UPDATE) — ANOTHER REQUEST COULD RUN BETWEEN STEP 1 AND 2
UPDATE room_availability SET status = 'RESERVED' WHERE ...;
-- Race condition! Both requests can see "available" and both update.

-- CORRECT approach — lock within a single transaction:
BEGIN;

-- SELECT ... FOR UPDATE acquires row-level exclusive locks.
-- No other transaction can read-for-update or modify these rows until commit.
-- The COUNT check + FOR UPDATE must be in the SAME transaction.
SELECT COUNT(*) as available_count
FROM room_availability
WHERE room_id = $1
  AND date = ANY($2::date[])   -- array of dates for the stay
  AND status = 'AVAILABLE'
FOR UPDATE;                    -- lock these rows immediately

-- Application checks: if available_count != number_of_nights → ROLLBACK
-- If we expected 5 rows and got 4, one date is already taken.

-- Atomically mark all dates as RESERVED
UPDATE room_availability
SET
  status = 'RESERVED',
  booking_id = $3,              -- the new booking ID
  reserved_at = NOW()
WHERE room_id = $1
  AND date = ANY($2::date[])
  AND status = 'AVAILABLE';     -- re-check status in WHERE clause (defense in depth)

-- Verify exactly the right number of rows were updated
-- GET DIAGNOSTICS updated_count = ROW_COUNT;
-- If updated_count != expected → something went wrong → ROLLBACK

-- Create the booking record in same transaction
INSERT INTO bookings (id, room_id, check_in, check_out, status, user_id, total_price)
VALUES ($3, $1, $checkin, $checkout, 'PENDING', $userid, $total);

COMMIT;
-- If any date was already RESERVED or BOOKED, the UPDATE touches fewer rows,
-- we detect the mismatch, and ROLLBACK → return 409 Conflict to caller.
```

**Why the count check matters:** The `FOR UPDATE` locks rows that exist AND match the WHERE clause. If a date row has `status = 'BOOKED'`, it does NOT get locked (because status != AVAILABLE), but neither does it get updated. Checking the count of locked rows versus expected nights gives you the atomicity guarantee: if you locked 4 instead of 5, rollback.

**Deadlock risk:** If two bookings overlap on dates, they will both try to lock some of the same rows. Postgres resolves deadlocks by aborting one transaction. The application must catch the deadlock error and retry. Always lock rows in the same order (order by date ASC) to minimize deadlock probability.

```java
// Java implementation with deadlock retry
@Transactional
public BookingResult reserveRoom(ReservationRequest req) {
    List<LocalDate> dates = req.getCheckIn()
        .datesUntil(req.getCheckOut())
        .collect(Collectors.toList());
    // Sort dates ascending to avoid deadlock with concurrent bookings
    dates.sort(Comparator.naturalOrder());

    int maxRetries = 3;
    for (int attempt = 0; attempt < maxRetries; attempt++) {
        try {
            return executeReservation(req, dates);
        } catch (DeadlockLoserDataAccessException e) {
            // Postgres killed our transaction to resolve deadlock
            // Wait briefly and retry
            if (attempt == maxRetries - 1) throw new BookingConflictException("Room unavailable");
            Thread.sleep(50 * (attempt + 1)); // exponential backoff
        } catch (BookingConflictException e) {
            throw e; // Not a deadlock, a genuine conflict — propagate immediately
        }
    }
}

private BookingResult executeReservation(ReservationRequest req, List<LocalDate> dates) {
    // Lock all availability rows for the date range
    int lockedCount = availabilityRepository.lockAvailableDates(
        req.getRoomId(), dates
    );

    if (lockedCount != dates.size()) {
        // Some dates are not available — immediate conflict
        throw new BookingConflictException(
            "Only " + lockedCount + " of " + dates.size() + " dates available"
        );
    }

    // Calculate total price (sum of per-date prices, respecting seasonal rules)
    BigDecimal totalPrice = pricingService.calculateTotal(req.getRoomId(), dates);

    // Mark RESERVED and create booking in same transaction
    availabilityRepository.markReserved(req.getRoomId(), dates, req.getBookingId());

    Booking booking = bookingRepository.save(Booking.builder()
        .id(req.getBookingId())
        .roomId(req.getRoomId())
        .userId(req.getUserId())
        .status(BookingStatus.PENDING)
        .checkIn(req.getCheckIn())
        .checkOut(req.getCheckOut())
        .totalPrice(totalPrice)
        .build());

    return BookingResult.success(booking);
}
```

---

### Handling the 10-Minute Hold Expiry

When a room is RESERVED (hold placed) but payment is not completed, you must release it after 10 minutes. Two approaches:

**Approach A: Background sweeper job**
```sql
-- Job runs every 60 seconds
UPDATE room_availability
SET status = 'AVAILABLE', booking_id = NULL, reserved_at = NULL
WHERE status = 'RESERVED'
  AND reserved_at < NOW() - INTERVAL '10 minutes';

-- Also cancel the associated booking
UPDATE bookings SET status = 'EXPIRED'
WHERE status = 'PENDING'
  AND created_at < NOW() - INTERVAL '10 minutes';
```

Advantage: simple. Disadvantage: rooms held for up to 10 minutes + 60-second job interval = up to 11 minutes hold effectively.

**Approach B: Delayed Kafka message**
When creating the hold, publish to a delayed Kafka topic (or use Redis TTL with keyspace notifications):
```
SETEX hold:{bookingId} 600 "pending"   // expires in 600 seconds
// Redis keyspace notification on expiry → triggers cleanup
```

Advantage: precise 10-minute release. Disadvantage: more complex infrastructure.

**Recommended:** Use Approach A with a 30-second job interval for simplicity. The business impact of a 30-second grace period is negligible, and it's far easier to operate.

---

## Data Model

### hotels
```sql
CREATE TABLE hotels (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            VARCHAR(255) NOT NULL,
  description     TEXT,
  city            VARCHAR(100) NOT NULL,
  country         CHAR(2) NOT NULL,           -- ISO country code
  address         TEXT,
  lat             DECIMAL(10, 7) NOT NULL,    -- for geo search backup
  lon             DECIMAL(10, 7) NOT NULL,
  star_rating     DECIMAL(2, 1),              -- 1.0 to 5.0
  amenities       TEXT[] DEFAULT '{}',        -- ["WIFI","POOL","GYM","SPA","PARKING"]
  check_in_time   TIME DEFAULT '14:00',
  check_out_time  TIME DEFAULT '12:00',
  cancellation_policy_id UUID,               -- FK to cancellation_policies
  active          BOOLEAN DEFAULT TRUE,
  created_at      TIMESTAMP DEFAULT NOW(),
  updated_at      TIMESTAMP DEFAULT NOW()
);

-- PostGIS extension for native geo queries on PostgreSQL
-- (ElasticSearch handles geo for search; PostGIS is for admin queries)
CREATE INDEX idx_hotels_city ON hotels (city);
CREATE INDEX idx_hotels_location ON hotels USING GIST (
  ST_SetSRID(ST_MakePoint(lon, lat), 4326)
);
```

### rooms
```sql
CREATE TABLE rooms (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  hotel_id    UUID NOT NULL REFERENCES hotels(id),
  name        VARCHAR(100),           -- "Deluxe King Room with City View"
  type        VARCHAR(50) NOT NULL,   -- SINGLE, DOUBLE, TWIN, SUITE, FAMILY
  capacity    INT NOT NULL,           -- max number of guests
  base_price  DECIMAL(10, 2) NOT NULL, -- default price (overridden by pricing_rules)
  amenities   TEXT[],                  -- room-specific: ["BATHTUB","SEA_VIEW","BALCONY"]
  floor       INT,
  size_sqm    DECIMAL(6, 2),
  bed_type    VARCHAR(50),             -- KING, QUEEN, TWIN, BUNK
  active      BOOLEAN DEFAULT TRUE,
  created_at  TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_rooms_hotel ON rooms (hotel_id);
```

### room_availability (the critical table)
```sql
-- Partitioned by date range for performance (one partition per month or year)
CREATE TABLE room_availability (
  room_id      UUID        NOT NULL,
  date         DATE        NOT NULL,
  status       VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
  price        DECIMAL(10, 2) NOT NULL,
  booking_id   UUID,
  reserved_at  TIMESTAMP,
  PRIMARY KEY (room_id, date)
) PARTITION BY RANGE (date);

-- Create monthly partitions
CREATE TABLE room_availability_2026_01 PARTITION OF room_availability
  FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

-- Index to quickly find available rooms for a given date range
CREATE INDEX idx_ra_available ON room_availability (date, room_id)
  WHERE status = 'AVAILABLE';
```

### bookings
```sql
CREATE TABLE bookings (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL,
  hotel_id          UUID NOT NULL,
  room_id           UUID NOT NULL,
  status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  -- PENDING:   hold placed, awaiting payment
  -- CONFIRMED: payment successful
  -- CANCELLED: cancelled by user or expired
  -- COMPLETED: stay completed (past check-out date)
  -- EXPIRED:   hold timed out without payment
  check_in          DATE NOT NULL,
  check_out         DATE NOT NULL,
  guests            INT NOT NULL,
  total_price       DECIMAL(10, 2) NOT NULL,
  currency          CHAR(3) DEFAULT 'USD',
  payment_id        UUID,                      -- Stripe PaymentIntent ID
  special_requests  TEXT,
  booked_at         TIMESTAMP DEFAULT NOW(),
  confirmed_at      TIMESTAMP,
  cancelled_at      TIMESTAMP,
  cancellation_reason VARCHAR(255),
  refund_amount     DECIMAL(10, 2),
  idempotency_key   UUID UNIQUE              -- prevent duplicate booking submissions
);

CREATE INDEX idx_bookings_user ON bookings (user_id, booked_at DESC);
CREATE INDEX idx_bookings_room ON bookings (room_id, check_in);
CREATE INDEX idx_bookings_status ON bookings (status, booked_at);
```

### pricing_rules (seasonal pricing)
```sql
CREATE TABLE pricing_rules (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  room_id     UUID NOT NULL REFERENCES rooms(id),
  start_date  DATE NOT NULL,
  end_date    DATE NOT NULL,          -- inclusive
  price       DECIMAL(10, 2) NOT NULL,
  reason      VARCHAR(100),           -- "CHRISTMAS_2026", "SUMMER_PEAK", "WEEKEND"
  priority    INT DEFAULT 0,          -- higher priority wins when rules overlap
  created_at  TIMESTAMP DEFAULT NOW(),
  CONSTRAINT no_negative_price CHECK (price > 0)
);

CREATE INDEX idx_pricing_room_date ON pricing_rules (room_id, start_date, end_date);
```

### cancellation_policies
```sql
CREATE TABLE cancellation_policies (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name              VARCHAR(100),           -- "Flexible", "Moderate", "Strict"
  rules             JSONB NOT NULL,
  -- [
  --   { "days_before": 7, "refund_pct": 100 },
  --   { "days_before": 3, "refund_pct": 50 },
  --   { "days_before": 0, "refund_pct": 0 }
  -- ]
  created_at        TIMESTAMP DEFAULT NOW()
);
```

---

## Pricing Service: Seasonal and Dynamic Pricing

The pricing service determines the effective price for each date in a booking.

```java
@Service
public class PricingService {

    /**
     * Calculate total price for a multi-night stay.
     *
     * Strategy: for each night, find the most specific applicable pricing rule.
     * If no rule matches, fall back to the room's base_price.
     * Rules can overlap (e.g., "Summer Peak" and "Weekend Surcharge") —
     * use the highest-priority rule, or sum multiple rules depending on policy.
     */
    public BigDecimal calculateTotal(UUID roomId, List<LocalDate> dates) {
        // Load all pricing rules that cover any date in the range
        // (single query with date range overlap, much cheaper than N queries)
        List<PricingRule> rules = pricingRepository.findApplicableRules(
            roomId,
            dates.get(0),
            dates.get(dates.size() - 1)
        );

        // Index rules by date for fast lookup
        Map<LocalDate, PricingRule> ruleByDate = buildRuleIndex(rules, dates);

        // Fetch room base price once
        BigDecimal basePrice = roomRepository.getBasePrice(roomId);

        return dates.stream()
            .map(date -> {
                PricingRule rule = ruleByDate.get(date);
                // Rule found: use seasonal price; else fall back to base
                return rule != null ? rule.getPrice() : basePrice;
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Build a map of date → best applicable rule.
     * When multiple rules apply to the same date, take the highest-priority one.
     * This handles cases like "weekend surcharge" overlapping with "holiday peak."
     */
    private Map<LocalDate, PricingRule> buildRuleIndex(
            List<PricingRule> rules, List<LocalDate> dates) {
        Map<LocalDate, PricingRule> index = new HashMap<>();
        for (LocalDate date : dates) {
            rules.stream()
                .filter(r -> !date.isBefore(r.getStartDate()) && !date.isAfter(r.getEndDate()))
                .max(Comparator.comparingInt(PricingRule::getPriority))
                .ifPresent(r -> index.put(date, r));
        }
        return index;
    }
}
```

**Populating the availability table with seasonal prices:**

When a hotel manager creates a pricing rule, the system must backfill prices into the `room_availability` table for the affected date range. This is done asynchronously via a background job to avoid blocking the API:

```sql
-- When a pricing rule is created or updated, update existing AVAILABLE rows
-- (RESERVED and BOOKED rows keep the price locked at booking time)
UPDATE room_availability
SET price = $new_price
WHERE room_id = $room_id
  AND date BETWEEN $start_date AND $end_date
  AND status = 'AVAILABLE';   -- never change price on already-booked dates
```

---

## Search Design: ElasticSearch

### Why ElasticSearch for Search?

PostgreSQL cannot efficiently combine full-text search, geo-distance filtering, and faceted aggregation (count by amenity, count by star rating, count by price range) in a single fast query. ElasticSearch was built for exactly this combination.

The search index contains a denormalized snapshot of each hotel's key attributes plus pre-computed "min available price" for quick filtering. This data is eventually consistent with the PostgreSQL source of truth.

### ElasticSearch Index Mapping

```json
PUT /hotels
{
  "mappings": {
    "properties": {
      "hotel_id":        { "type": "keyword" },
      "name":            { "type": "text", "analyzer": "english" },
      "description":     { "type": "text", "analyzer": "english" },
      "city":            { "type": "keyword" },
      "country":         { "type": "keyword" },
      "location": {
        "type": "geo_point"
      },
      "star_rating":     { "type": "float" },
      "avg_review_score":{ "type": "float" },
      "review_count":    { "type": "integer" },
      "amenities":       { "type": "keyword" },
      "min_price":       { "type": "float" },
      "has_availability":{ "type": "boolean" },
      "room_types":      { "type": "keyword" }
    }
  }
}
```

### Search Query: Geo Search Near Me + Filters

```json
GET /hotels/_search
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "Paris",
            "fields": ["city^3", "name^2", "description"],
            "type": "best_fields"
          }
        }
      ],
      "filter": [
        { "term": { "has_availability": true } },
        { "range": { "star_rating": { "gte": 4 } } },
        { "range": { "min_price": { "gte": 80, "lte": 300 } } },
        { "terms": { "amenities": ["WIFI", "POOL"] } },
        {
          "geo_distance": {
            "distance": "5km",
            "location": { "lat": 48.8566, "lon": 2.3522 }
          }
        }
      ]
    }
  },
  "sort": [
    { "_score": "desc" },
    { "avg_review_score": "desc" },
    {
      "_geo_distance": {
        "location": { "lat": 48.8566, "lon": 2.3522 },
        "order": "asc",
        "unit": "km"
      }
    }
  ],
  "aggs": {
    "by_star_rating": {
      "terms": { "field": "star_rating" }
    },
    "price_ranges": {
      "range": {
        "field": "min_price",
        "ranges": [
          { "to": 100 },
          { "from": 100, "to": 200 },
          { "from": 200 }
        ]
      }
    },
    "amenity_counts": {
      "terms": { "field": "amenities", "size": 20 }
    }
  },
  "from": 0,
  "size": 20
}
```

**Why use `filter` instead of `must` for structured criteria?**

`filter` clauses bypass relevance scoring and are cacheable by ElasticSearch. `must` clauses affect the `_score`. Using `filter` for structured data (price, rating, amenities) and `must` for free-text search gives the best performance: structured filters are cached; text match drives ranking.

### Keeping the Search Index in Sync

ElasticSearch is updated asynchronously via Kafka. Booking Service publishes events; an ES Updater service consumes them and updates the index.

```
Events that trigger index update:
  BookingConfirmedEvent  → recalculate has_availability, min_price for hotel
  BookingCancelledEvent  → recalculate (room is available again)
  RoomPricingUpdated     → update min_price
  HotelAmenitiesUpdated  → update amenities array
  ReviewAddedEvent       → update avg_review_score, review_count

ES Updater logic:
  On BookingConfirmedEvent:
    1. Query PostgreSQL: what is the minimum available price for any room in this hotel
       for the next 90 days?
    2. Does this hotel have at least one available room in the next 90 days?
    3. Update ES document with new min_price and has_availability
```

This lag (seconds to tens of seconds) is acceptable: users who click through from search see a real-time availability check before booking. The ES index is used only for the listing page — not for the booking confirmation step.

---

## Booking Flow: End-to-End

```
1. User searches: GET /hotels/search?city=Paris&checkin=2026-06-01&checkout=2026-06-05&guests=2
   → ES query → cached result (Redis 5 min TTL) → return list of hotels

2. User selects a hotel and views rooms:
   GET /hotels/{hotelId}/rooms?checkin=2026-06-01&checkout=2026-06-05
   → PostgreSQL query: find rooms with ALL 4 nights AVAILABLE
   → Return rooms with per-night pricing (from pricing_rules)
   → This is a REAL-TIME check, not ES

3. User selects a room and clicks "Reserve":
   POST /bookings
   { roomId, checkIn, checkOut, guests, idempotencyKey }
   → Booking Service:
     a. Check idempotency_key (prevent double submission on network retry)
     b. BEGIN transaction
     c. SELECT ... FOR UPDATE on availability rows
     d. Verify all dates available (count check)
     e. UPDATE status = 'RESERVED'
     f. INSERT booking (status = PENDING)
     g. COMMIT
   → Return { bookingId, holdExpiresAt, totalPrice, checkoutUrl }

4. Client redirects to payment (Stripe Checkout or in-app payment form)
   → 10-minute countdown timer displayed to user

5. User completes payment:
   → Stripe calls webhook: POST /webhooks/stripe { event: payment_intent.succeeded }
   → Booking Service:
     a. BEGIN transaction
     b. UPDATE bookings SET status = 'CONFIRMED', payment_id = ...
     c. UPDATE room_availability SET status = 'BOOKED' (no longer reversible)
     d. COMMIT
   → Publish BookingConfirmedEvent to Kafka
   → Send confirmation email (async, via Kafka consumer)

6. On payment failure:
   → Stripe webhook: payment_intent.payment_failed
   → UPDATE room_availability SET status = 'AVAILABLE' (release hold)
   → UPDATE bookings SET status = 'CANCELLED'
   → User sees error, can retry with different card

7. On hold expiry (payment not completed in 10 min):
   → Background sweeper releases RESERVED rows back to AVAILABLE
   → Booking status = EXPIRED
```

---

## Cancellation Policy Engine

```java
@Service
public class CancellationService {

    /**
     * Calculate refund amount based on hotel's cancellation policy.
     *
     * Policy rules are stored as a JSONB array sorted by days_before descending.
     * We find the first rule where days_until_checkin >= rule.days_before.
     *
     * Example policy (Moderate):
     *   [
     *     { "days_before": 7, "refund_pct": 100 },  // cancel 7+ days before: full refund
     *     { "days_before": 3, "refund_pct": 50 },   // cancel 3-6 days before: 50% refund
     *     { "days_before": 0, "refund_pct": 0 }     // cancel 0-2 days before: no refund
     *   ]
     */
    public CancellationResult cancel(UUID bookingId, String reason) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (!booking.getStatus().equals(BookingStatus.CONFIRMED)) {
            throw new InvalidOperationException("Only CONFIRMED bookings can be cancelled");
        }

        long daysUntilCheckin = ChronoUnit.DAYS.between(LocalDate.now(), booking.getCheckIn());

        CancellationPolicy policy = policyRepository.findByHotelId(booking.getHotelId());
        int refundPct = calculateRefundPercentage(policy, daysUntilCheckin);
        BigDecimal refundAmount = booking.getTotalPrice()
            .multiply(BigDecimal.valueOf(refundPct))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // Execute cancellation atomically
        executeAtomicCancellation(booking, refundAmount, reason);

        // Trigger refund via payment service (async via Kafka for reliability)
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            kafkaProducer.publish("payment-commands",
                new RefundCommand(booking.getPaymentId(), refundAmount, booking.getCurrency()));
        }

        return CancellationResult.of(booking.getId(), refundAmount, refundPct);
    }

    @Transactional
    private void executeAtomicCancellation(Booking booking, BigDecimal refundAmount, String reason) {
        // Mark booking as cancelled
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(reason);
        booking.setRefundAmount(refundAmount);
        bookingRepository.save(booking);

        // Release availability — rooms are available again
        // Only release if check-in date is in the future
        if (booking.getCheckIn().isAfter(LocalDate.now())) {
            availabilityRepository.releaseBooking(
                booking.getRoomId(),
                booking.getCheckIn(),
                booking.getCheckOut()
            );
        }
    }

    private int calculateRefundPercentage(CancellationPolicy policy, long daysUntilCheckin) {
        // Rules are sorted by days_before descending — find first applicable rule
        return policy.getRules().stream()
            .filter(rule -> daysUntilCheckin >= rule.getDaysBefore())
            .findFirst()
            .map(CancellationRule::getRefundPct)
            .orElse(0); // Default: no refund
    }
}
```

---

## Overbooking: When and Why

Some hotels intentionally overbook (like airlines) because historically 5-10% of guests no-show. The system can support configurable overbooking tolerance:

```sql
ALTER TABLE rooms ADD COLUMN overbooking_pct INT DEFAULT 0;
-- 10 = allow 10% more bookings than physical rooms

-- Modified availability check:
-- For a room with capacity=10 and overbooking_pct=10:
-- Allow up to 11 bookings per night before rejecting
SELECT COUNT(*) as booked_count
FROM room_availability
WHERE room_id = $1 AND date = $2 AND status IN ('RESERVED', 'BOOKED');
-- If booked_count >= capacity * (1 + overbooking_pct/100) → reject
```

For most implementations, overbooking is off by default and only enabled for hotel chains that have explicit policies and relocation procedures.

---

## API Design

```
# Search
GET /hotels/search?city=Paris&checkin=2026-06-01&checkout=2026-06-05&guests=2
     &minStars=4&maxPrice=300&amenities=WIFI,POOL&lat=48.8566&lon=2.3522&radius=5km
     &sortBy=RATING&page=0&size=20

# Hotel details
GET /hotels/{hotelId}
GET /hotels/{hotelId}/rooms?checkin=2026-06-01&checkout=2026-06-05&guests=2
GET /hotels/{hotelId}/reviews?page=0&size=20&sortBy=NEWEST

# Booking flow
POST /bookings
{
  "roomId": "uuid",
  "checkIn": "2026-06-01",
  "checkOut": "2026-06-05",
  "guests": 2,
  "specialRequests": "High floor please",
  "idempotencyKey": "client-generated-uuid"
}

GET  /bookings/{bookingId}
DELETE /bookings/{bookingId}               → cancel (applies policy)
GET  /bookings/{bookingId}/cancellation-preview  → show refund amount before cancelling

# User bookings
GET  /users/me/bookings?status=CONFIRMED&page=0

# Hotel management (authenticated hotel staff)
POST /hotels/{hotelId}/rooms
PUT  /hotels/{hotelId}/rooms/{roomId}/pricing    → set seasonal pricing
POST /hotels/{hotelId}/rooms/{roomId}/block      → manually block dates

# Webhooks (from payment provider)
POST /webhooks/stripe

# Reviews (post-stay only)
POST /bookings/{bookingId}/review
{ "rating": 9.0, "comment": "Excellent stay", "categories": {...} }
```

---

## Edge Cases and Failure Scenarios

**1. Payment webhook arrives after hold expiry**
The sweeper released the room (status = AVAILABLE) before the webhook arrived. When the webhook tries to confirm the booking, find the booking in EXPIRED status and issue an immediate refund via Stripe. Log an alert — this indicates the payment took longer than the 10-minute hold.

**2. Partial availability (some dates available, some not)**
The `SELECT COUNT ... FOR UPDATE` will lock only the AVAILABLE rows. If the count is less than expected (e.g., 3 of 5 nights available), rollback and return a specific error message: "Room is not available for the full requested period. Available dates: June 1, 3, 4." Allow the user to choose a different room or adjust dates.

**3. Database partition unavailable during booking**
If the PostgreSQL partition for a specific month is unavailable (disk failure, maintenance), bookings for that month fail. Design: detect the error, return 503 with "Service temporarily unavailable," and trigger an alert. Do not fall back to the search layer (ElasticSearch) for availability — it is not authoritative.

**4. Concurrent identical bookings (same user, double-click)**
The `idempotency_key` (UUID generated by the client before submission) ensures the second request returns the same result as the first without re-executing the transaction. The database has a UNIQUE constraint on `idempotency_key`.

**5. Price changes between search and booking**
A pricing rule is updated between when the user sees the price on the search page and when they confirm. The booking confirmation uses the price stored in the `room_availability` table (which was updated by the pricing rule update), not the cached search result. The user may see a different total at checkout. Best practice: show the final calculated price in the booking confirmation step (after `SELECT ... FOR UPDATE` retrieves the current prices) before requiring payment.

**6. Hotel closes or room is removed**
Mark `rooms.active = FALSE` and `hotels.active = FALSE`. The ElasticSearch index is updated asynchronously. Any PENDING bookings for deactivated rooms should be automatically cancelled and refunded. This requires a batch job that runs whenever a room or hotel is deactivated.

---

## Tech Stack

| Component | Technology | Why |
|---|---|---|
| Backend | Java 17, Spring Boot | Mature ecosystem, strong transactional support |
| Inventory DB | PostgreSQL 15 | ACID, row-level locking, partitioning |
| Search | ElasticSearch 8 | Geo search, full-text, faceted aggregation |
| Cache | Redis 7 | Search result cache (TTL=5min), hold timers |
| Payment | Stripe / VNPay | PCI DSS compliant, webhook support |
| Events | Kafka | Async index sync, notification pipeline |
| Media | AWS S3 + CloudFront | Hotel photos, presigned upload |
| Email | AWS SES | Booking confirmation, cancellation notice |
| Monitoring | Prometheus + Grafana | Booking rate, availability query latency |

---

## Interview Q&A

**Q1: Why do you use pessimistic locking (`SELECT ... FOR UPDATE`) instead of optimistic locking for the availability table?**

Optimistic locking works well when conflicts are rare — you read a version number, update, and check that the version hasn't changed. For hotel booking, especially for popular rooms on peak dates, conflicts are frequent and expected. Under optimistic locking, multiple concurrent booking attempts would all succeed the read phase, then all but one would fail the version check and require retries. This creates a "thundering herd" problem: 50 concurrent requests for the last room on New Year's Eve all read version=5, all try to update, 49 fail and retry, retry again, and so on. The user experience degrades severely.

Pessimistic locking with `FOR UPDATE` queues the transactions at the database level. The second request waits for the first to commit before it even reads the row. The wait time is the duration of the booking transaction — typically 50-100ms including payment authorization. This is acceptable and results in a clean "room unavailable" response for the second user rather than a retry storm. The tradeoff is that `FOR UPDATE` holds locks for the transaction duration, but since our transactions are short (a few hundred milliseconds), this is fine. Optimistic locking would be preferred if bookings were rare and reads were vastly more common than writes — which is not the case for peak inventory.

**Q2: How does ElasticSearch stay consistent with PostgreSQL? What happens if the Kafka event is lost?**

ElasticSearch is updated via a Kafka consumer (the ES Updater service) that processes booking events. Kafka guarantees at-least-once delivery, so the ES Updater must be idempotent: processing the same event twice should produce the same result in ES.

If a Kafka event is lost (which is rare with replication factor 3 and acks=all), the ES index can drift from the database. To handle this, we run a periodic reconciliation job (every 15 minutes) that queries PostgreSQL for any hotel with a booking change in the last 20 minutes and pushes a fresh ES document. This provides a bounded inconsistency window.

For searches, the eventual consistency is acceptable: if a room appears available in ES but is actually booked (stale data), the user will discover this during the booking flow when the real-time DB check runs. They receive an error and see other available options. This user experience impact is minimized by keeping the sync lag under 30 seconds.

**Q3: How would you handle seasonal pricing where a 7-night booking spans two different pricing periods (e.g., from regular to peak season)?**

The pricing is computed per-date, not per-booking. When calculating the total, the pricing service iterates over each date in the stay and applies the highest-priority matching pricing rule. For a check-in June 28 to check-out July 5:
- June 28-30: regular price at $100/night
- July 1-4: peak season price at $180/night (new pricing rule takes effect)

Total = 2 × $100 + 4 × $180 = $920. This is transparent to the user because the booking confirmation page shows a nightly breakdown before payment. The `room_availability` table stores the effective price per night, pre-computed from pricing rules, so the SELECT during booking captures the correct price for each date without any additional pricing queries during the critical locked transaction.

**Q4: How would you implement the geo search feature "hotels near me within 5km" at scale?**

ElasticSearch's `geo_distance` filter uses a spatial index internally. When you index a document with a `geo_point` field, ElasticSearch creates a Geohash-based spatial index. The `geo_distance` filter translates to a bounding box pre-filter (using geohash precision) followed by exact distance calculation. This is O(log N + K) where K is the number of results, not O(N) over all hotels.

At 500k hotels, the ES index is tiny (about 1GB). The geo filter with a 5km radius at a major city center will return thousands of candidates, which are then further filtered by price/amenities/availability. The key performance concern is not the geo filter itself but the combination with full-text scoring — using `filter` context for geo and price (cacheable, no scoring) and `must` for text (scored) ensures the geo filter runs in fast filter mode.

For mobile use cases, you can pre-compute geohashes and use a prefix match on the geohash field, which avoids the distance calculation entirely for rough proximity filtering.

**Q5: What happens if the payment service (Stripe) is down when the user tries to pay?**

The room remains in RESERVED status with a 10-minute hold. The user should see a payment error and be able to retry (same booking ID, same hold). The client must not create a new booking — it retries payment against the existing PENDING booking.

If Stripe is down for more than 10 minutes, the hold expires and the room is released. This is a genuine failure case. Mitigation strategies: extend the hold timer to 20 minutes with explicit user notification ("Your reservation is held for 20 minutes"), implement client-side retry with exponential backoff, and alert operations when Stripe error rate exceeds 1% so they can manually extend holds for affected bookings.

The key insight is that payment processing and inventory locking are on different timescales. Inventory locks are short (minutes); payment processing may take longer. Design your hold duration generously but not infinitely — an infinite hold would mean rooms could be locked by bots with no intention of paying.

**Q6: How would you scale the availability query for a hotel with 500 rooms where each room has 365 dates? That's 182,500 rows per hotel per year.**

The query "find all rooms in hotel X with all dates available from June 1–5" must scan the intersection of room_ids for a given hotel and date rows for 4 nights. The query:

```sql
SELECT ra.room_id
FROM room_availability ra
JOIN rooms r ON r.id = ra.room_id
WHERE r.hotel_id = $hotel_id
  AND ra.date IN ('2026-06-01', '2026-06-02', '2026-06-03', '2026-06-04')
  AND ra.status = 'AVAILABLE'
GROUP BY ra.room_id
HAVING COUNT(*) = 4;  -- must be available for all 4 nights
```

With the composite primary key `(room_id, date)` and a partial index on `status = 'AVAILABLE'`, this query is fast: it touches only the rows for the 4 dates, filtered to AVAILABLE status, grouped by room. For 500 rooms × 4 dates = 2000 rows touched maximum. Even without the partial index, the primary key scan is efficient.

For very large hotels (thousands of rooms), you might add a bitmap-style summary table (`hotel_availability_summary`) that tracks "minimum available rooms per hotel per date" for quick filtering, updated asynchronously after each booking. This allows you to quickly skip hotels with zero availability before drilling into room-level queries.

**Q7: How do you prevent a user from booking the same room twice (double-booking by the same user)?**

Three layers of defense:

First, the `idempotency_key` field on bookings ensures that if the client submits the same request twice (network retry), the second request returns the first booking rather than creating a new one.

Second, the `SELECT ... FOR UPDATE` transaction will fail atomically if the dates are already RESERVED or BOOKED — whether by the same user or different users.

Third, at the application layer, you can add a soft check: before starting the transaction, check if the user has an active booking for any date in the requested range in the same room. This is a fast pre-check that avoids hitting the locking path unnecessarily.

The root cause of double-booking is usually a race condition at the application level (user clicking "Book" twice) or at the network level (client retrying a timeout that actually succeeded). The idempotency key solves both cases.

**Q8: How would you design the review system so that only guests who completed their stay can leave reviews?**

Reviews must be linked to a `booking_id` where `status = 'COMPLETED'` and `check_out < NOW()`. The system changes booking status to COMPLETED via a nightly batch job that updates all confirmed bookings where `check_out < NOW()`.

When a review is submitted:
```sql
-- Verify the booking belongs to this user and is completed
SELECT id FROM bookings
WHERE id = $booking_id
  AND user_id = $user_id
  AND status = 'COMPLETED'
  AND check_out < NOW()
  AND id NOT IN (SELECT booking_id FROM reviews WHERE booking_id IS NOT NULL);
```

The last condition prevents multiple reviews per booking. Reviews are stored with `booking_id` as a foreign key, making it impossible to fake a review without a real booking record. Hotel staff cannot delete reviews — they can only submit official responses.

**Q9: How would you design the hotel management portal so that hotels can update room pricing without affecting existing confirmed bookings?**

Confirmed bookings have the price locked in the `room_availability.price` column at the time of booking (the row is in BOOKED status). Pricing rule updates only affect AVAILABLE rows. The SQL update specifically excludes non-AVAILABLE rows:

```sql
UPDATE room_availability
SET price = $new_price
WHERE room_id = $room_id
  AND date BETWEEN $start_date AND $end_date
  AND status = 'AVAILABLE';   -- does not touch RESERVED or BOOKED rows
```

This means a hotel manager can update pricing for June 15–30 and it will only affect future bookings — guests who already booked at the old price are not affected. This is the correct behavior (you cannot retroactively change a guest's confirmed booking price).

For the confirmed booking receipt, the total price is stored in `bookings.total_price` at the time of booking confirmation, making it immutable regardless of future pricing changes.

**Q10: How do you handle the case where a hotel has 100 rooms and all are being booked simultaneously by 100 users?**

All 100 transactions will attempt `SELECT ... FOR UPDATE` on the availability rows for their requested dates. PostgreSQL handles concurrent locking at the row level. If all 100 users are booking different room IDs, there is no contention — they lock different rows and all succeed in parallel.

If all 100 users are trying to book the last available room (e.g., the only suite), only one transaction can hold the `FOR UPDATE` lock at a time. The others queue behind it. The first transaction commits (either RESERVED or not), then the next sees the updated status and proceeds accordingly. This is serialized at the database level — no distributed lock needed.

The practical concern is lock wait timeout. Set `lock_timeout = '5s'` on the booking session. If a transaction waits more than 5 seconds for a lock (which would indicate extreme contention or a stuck transaction), it times out and returns 409 to the user. This prevents cascading timeouts where 100 users are all waiting for one stuck transaction.

**Q11: How would you handle partial refunds for bookings cancelled mid-stay (early check-out)?**

Early check-out cancellation is different from pre-arrival cancellation. The policy for early check-out typically specifies that already-consumed nights are charged at full price, and future nights are refunded according to the hotel's cancellation policy.

Implementation:
```
nights_consumed = today - check_in_date
nights_remaining = check_out_date - today

charge_for_consumed = sum(price for each consumed night)
refund_for_remaining = based on cancellation_policy applied to remaining nights
total_refund = total_paid - charge_for_consumed - (remaining × pct_to_charge)

-- Release availability for remaining nights
UPDATE room_availability
SET status = 'AVAILABLE', booking_id = NULL
WHERE room_id = $room_id
  AND date >= today
  AND date < $original_checkout;
```

The critical constraint is that already-consumed nights cannot be made available again (they are in the past). Only future nights are released. This requires the cancellation service to be date-aware and handle partial release correctly.

**Q12: How do you design for multi-currency pricing and what are the common pitfalls?**

Hotels set prices in their local currency (USD for US hotels, EUR for European hotels, VND for Vietnamese hotels). Users can view prices in their preferred currency, but the booking is settled in the hotel's currency to avoid exchange rate risk.

Common pitfalls:
1. **Using floating-point for monetary values** — always use `DECIMAL(10,2)` in SQL and `BigDecimal` in Java. Float arithmetic is non-deterministic: `0.1 + 0.2 != 0.3` in floating point.
2. **Converting currency at search time** — exchange rates change. Store original currency in the booking, display converted amounts as "approximately X USD" with a disclaimer.
3. **Not storing the exchange rate at booking time** — when processing a refund days later, the exchange rate may have changed. Store the rate used at booking time in the booking record.
4. **Rounding errors on multi-night totals** — compute the total in the source currency, then convert once. Do not convert each night's price and sum — rounding errors accumulate.

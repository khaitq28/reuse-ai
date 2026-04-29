# Ride-Sharing System — Deep Design Guide

## Problem Statement

Design a ride-sharing platform where riders can request rides, drivers nearby accept and navigate, real-time location is tracked, and dynamic pricing adjusts to supply/demand — similar to Uber or Lyft internals.

---

## Why This Problem Matters

Ride-sharing is one of the richest system design problems because it combines multiple challenging domains simultaneously:

- **Geospatial indexing at scale**: "Find drivers within 5km of a point" must execute in milliseconds against millions of moving data points. This is a fundamentally different query from relational or key-value lookups.
- **Real-time state management**: Driver locations update every 5 seconds. At 1.5M active drivers, that's 300k location writes per second — while simultaneously serving thousands of "find nearby drivers" queries per second.
- **Matching under time pressure**: A rider expects a match in under 5 seconds. The matching algorithm must evaluate, score, and contact multiple drivers within that window, handling rejection and re-offering gracefully.
- **Dynamic pricing**: Surge pricing must reflect real-time supply/demand at geographic granularity — not globally, but per zone. This requires aggregating live data streams into zone-level metrics continuously.

**What interviewers are testing**: Geospatial indexing strategies (Redis GEO, H3 hexagonal grids, S2 geometry), WebSocket vs polling for real-time location, the matching algorithm's design, how surge pricing zones are computed, and how the system handles the failure case where a driver goes offline mid-trip.

---

## Key Insight Before Diving In

**The core technical challenge is indexing a constantly-changing set of points in 2D space and answering "nearest neighbors" queries with sub-second latency.**

Every 5 seconds, 1.5M drivers update their position. Every second, thousands of "find drivers near me" queries arrive. A naive relational approach (`SELECT * FROM drivers WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?`) requires a full table scan or a 2D index — PostgreSQL with PostGIS can handle this at moderate scale, but Redis GEO handles it at extreme scale with sub-millisecond latency.

The second insight: **location data is ephemeral and lossy by nature**. A driver's GPS position 10 seconds ago is not interesting — only the current position matters. This means we don't need durable storage for location data; we need a fast, in-memory structure that's constantly overwritten. Redis is perfect; a relational DB is wrong.

---

## Requirements

### Functional
- Rider requests a ride with pickup and dropoff location
- System finds available drivers within radius, matches, and confirms in < 5 seconds
- Real-time driver location visible to rider during waiting and trip
- Dynamic surge pricing based on local demand/supply ratio
- Trip history, receipts, ratings (post-trip)
- Driver can go online/offline, accept/reject requests
- Estimated time of arrival (ETA) to pickup and destination

### Non-Functional
- 10M daily rides, peak 500 concurrent ride requests/sec
- Driver location update: every 5 seconds, 1.5M active drivers
- Matching: match result delivered in < 5 seconds
- Location tracking latency: < 1 second from driver move to rider screen update
- 99.99% uptime — a trip already in progress must never be interrupted

---

## Capacity Estimation

```
Active drivers:
  5M total registered, 30% active peak = 1.5M active drivers
  Location updates: 1.5M × 1 update/5s = 300,000 writes/sec
  Location payload: 50 bytes (driver_id + lat + lon + timestamp)
  Data rate: 300k × 50B = 15MB/sec (manageable)

Ride requests:
  10M rides/day ÷ 86,400s = 116 rides/sec average
  Peak: 500 rides/sec (Friday evening, concerts)

Matching queries:
  500 requests/sec → 500 × "find 10 nearest drivers" geo queries/sec
  Each Redis GEORADIUS: < 1ms → 500 queries/sec easy for Redis cluster

Location streaming to riders:
  At any time: 500k riders in active trips × 1 update/sec = 500k WebSocket messages/sec
  Each message: ~100 bytes → 50MB/sec fan-out

Storage:
  Trip records: 10M/day × 2KB = 20GB/day
  Location history (for dispute resolution): 10M trips × 60 mins × 1 update/5s = 120B points/day
  → Cassandra time-series, compressed: ~50GB/day
```

---

## High-Level Architecture

```
Rider App          Driver App
    │                  │
    │ REST/WebSocket    │ WebSocket (bidirectional: location up, commands down)
    ▼                  ▼
┌─────────────────────────────────────────────────────────┐
│                 API Gateway (auth, routing)              │
└──────┬──────────────────────────┬───────────────────────┘
       │                          │
       ▼                          ▼
┌─────────────┐          ┌────────────────────┐
│Trip Service │          │ Location Service   │
│(ride lifecycle)        │ (real-time state)  │
└──────┬──────┘          └────────────────────┘
       │                          │
       ▼                          ▼
┌─────────────┐          ┌────────────────────┐
│ Matching    │          │  Redis GEO Cluster │
│ Service     │←─────────│  (driver positions)│
└──────┬──────┘          └────────────────────┘
       │
       ├──────────────────────────────────────┐
       ▼                                      ▼
┌─────────────┐                     ┌─────────────────────┐
│ Pricing     │                     │ Notification Service │
│ Service     │                     │ (push to driver app) │
│ (surge)     │                     └─────────────────────┘
└──────┬──────┘
       │
┌──────▼──────────────────────────────────────────────────┐
│                    Kafka Event Bus                        │
│  ride.requested, ride.matched, trip.started, trip.ended  │
└──────┬──────────────────┬──────────────────┬────────────┘
       ▼                  ▼                  ▼
  PostgreSQL          Cassandra         Payment Service
  (trips, users,     (location         (charge on trip end)
   drivers, ratings)  history)
```

---

## Geospatial Indexing: Redis GEO

### Why Redis GEO?

PostgreSQL with PostGIS can do geospatial queries, but at 300k location writes/sec + 500 geo-radius queries/sec, it becomes the bottleneck. Redis GEO uses **geohash** encoding internally: each (lat, lon) pair is encoded into a 52-bit integer using a space-filling curve. This integer is stored in a Redis Sorted Set as the score. "Nearby" points have similar geohash values (nearby integers in the sorted set), so a radius query becomes a range scan — O(log N + K) where K is the number of results.

```
Driver updates location:
  GEOADD drivers:active {longitude} {latitude} {driver_id}
  → O(log N) operation
  → TTL reset: EXPIRE drivers:active:{driver_id} 60
  → If driver stops sending heartbeats: key expires → driver removed from active pool

Find available drivers within 5km of rider at (48.8566, 2.3522):
  GEORADIUS drivers:active 2.3522 48.8566 5 km
             ASC              ← sorted by distance (nearest first)
             COUNT 20         ← get top 20 candidates
             WITHCOORD        ← include coordinates in result
             WITHDIST         ← include distances in result

Result:
  [
    { "driver_id": "d-001", "distance": "0.8 km", "lat": 48.853, "lon": 2.348 },
    { "driver_id": "d-042", "distance": "1.2 km", "lat": 48.862, "lon": 2.361 },
    ...
  ]

Execution time: < 1ms for 1.5M active drivers
```

### Driver State Management

```
Redis keys per driver:
  drivers:active           → Sorted Set (geohash → driver_id) for ALL active drivers
  driver:{id}:status       → "AVAILABLE" | "ON_TRIP" | "OFFLINE", TTL=60s
  driver:{id}:gateway      → "gateway-host-7" (which WebSocket server this driver is on)
  driver:{id}:vehicle      → JSON { type, model, plate, capacity }

When driver goes AVAILABLE:
  GEOADD drivers:active {lon} {lat} {driver_id}
  SET driver:{id}:status "AVAILABLE" EX 60

When driver accepts a trip:
  ZREM drivers:active {driver_id}   ← remove from available pool immediately
  SET driver:{id}:status "ON_TRIP" EX 7200  ← 2-hour max trip

When driver completes trip:
  GEOADD drivers:active {lon} {lat} {driver_id}  ← back to available
  SET driver:{id}:status "AVAILABLE" EX 60
```

---

## Matching Algorithm

### Step-by-Step Flow

```
1. Rider requests ride: { pickup: (48.8566, 2.3522), dropoff: (48.8744, 2.3522), type: STANDARD }

2. Pricing Service: compute estimated price
   base_fare = 2.00 EUR
   distance_km = 3.2 km × 1.20 EUR/km = 3.84 EUR
   surge = 1.5x (current zone surge)
   total_estimate = (2.00 + 3.84) × 1.5 = 8.76 EUR
   → Show to rider, wait for confirmation

3. Matching Service: find candidates
   GEORADIUS drivers:active {lon} {lat} 5 km ASC COUNT 20

4. Filter candidates:
   - Status must be AVAILABLE (not on another trip)
   - Vehicle type matches requested type
   - Driver rating ≥ minimum threshold (e.g., 4.0)
   - Driver not already offered this trip and rejected

5. Score candidates:
   score(driver) =
     0.5 × distance_score(driver)     // 0-1, 1=closest
   + 0.3 × rating_score(driver)       // (rating - 4.0) / 1.0
   + 0.2 × acceptance_rate(driver)    // 0-1, rewards reliable drivers

6. Offer to highest-score driver:
   Push to driver via WebSocket:
   { type: "RIDE_OFFER", rider_location, destination, estimated_fare, timeout: 30s }

7. Wait up to 30 seconds for driver response:
   If ACCEPTED: → trip CONFIRMED, notify rider
   If REJECTED or TIMEOUT: → move to next candidate
   If all 20 candidates exhausted: → notify rider "No drivers available, retry?"
```

### Scoring Logic

```java
private double scoreDriver(DriverCandidate driver, RideRequest request) {
    // Distance score: closest = 1.0, 5km away = 0.0 (linear)
    double maxRadius = 5.0; // km
    double distanceScore = Math.max(0, 1.0 - (driver.getDistanceKm() / maxRadius));

    // Rating score: 5-star = 1.0, 4-star = 0.0 (scale relative to minimum)
    double ratingScore = Math.max(0, (driver.getRating() - 4.0) / 1.0);

    // Acceptance rate: how often this driver accepts offers (rewards reliability)
    double acceptanceScore = driver.getAcceptanceRate(); // 0.0 - 1.0

    // Weighted sum
    return 0.5 * distanceScore
         + 0.3 * ratingScore
         + 0.2 * acceptanceScore;
}
```

---

## Real-Time Location Tracking

### Why WebSocket?

Driver sends location every 5 seconds. Rider receives driver's location every 1-2 seconds during trip. This is bidirectional, real-time, persistent communication — WebSocket is the only option that works efficiently.

```
Driver App → WebSocket → Gateway (driver's gateway server)
Gateway publishes to Kafka: location.updates
  { driver_id, lat, lon, heading, speed, timestamp }

Location Service consumes:
  1. Update Redis GEO: GEOADD drivers:active {lon} {lat} {driver_id}
  2. If driver has active trip: notify rider

Notifying rider:
  Look up rider's active trip: Redis GET rider:{rider_id}:active_trip
  Look up driver's trip: Redis GET trip:{trip_id}:rider_gateway
  → "gateway-host-42"
  Publish to that gateway via Redis Pub/Sub or Kafka:
    { type: "DRIVER_LOCATION", driver_id, lat, lon, heading, eta_seconds }
  Gateway-42 delivers to rider's WebSocket connection
```

### WebSocket Message Protocol

```json
// Driver → Server (location heartbeat)
{
  "type": "LOCATION_UPDATE",
  "lat": 48.8566,
  "lon": 2.3522,
  "heading": 45,
  "speed_kmh": 32,
  "timestamp": 1714392060000
}

// Server → Rider (driver position during trip)
{
  "type": "DRIVER_LOCATION",
  "driver_id": "drv-123",
  "lat": 48.8566,
  "lon": 2.3522,
  "heading": 45,
  "eta_seconds": 180,
  "polyline": "encrypted_route_polyline"
}

// Server → Driver (ride offer)
{
  "type": "RIDE_OFFER",
  "offer_id": "off-789",
  "pickup": { "lat": 48.8566, "lon": 2.3522, "address": "Place de la République" },
  "dropoff": { "lat": 48.8744, "lon": 2.3522, "address": "Gare du Nord" },
  "distance_km": 3.2,
  "estimated_fare": "8.76 EUR",
  "estimated_pickup_eta_sec": 240,
  "expires_at": 1714392090000
}
```

---

## Surge Pricing — Zone-Based Dynamic Pricing

### H3 Hexagonal Grid

Uber pioneered using Uber H3 — a hierarchical hexagonal grid — for zone-based pricing. Hexagons tile space more uniformly than squares (all neighbors are equidistant), and H3 supports multiple resolutions (city block to entire city).

```
Resolution 7: ~5km² hexagons (neighborhood level — good for surge)
Resolution 9: ~0.1km² hexagons (block level — good for pickup optimization)

For surge pricing, use resolution 7:
  Paris is divided into ~400 H3 hexagons at resolution 7

Every 30 seconds, a stream processor computes per-hexagon metrics:
  active_requests[hex]   = ride requests in last 5 minutes in this hex
  available_drivers[hex] = GEORADIUS count within hex boundary

  demand_supply_ratio = active_requests / max(available_drivers, 1)

  surge_multiplier =
    ratio < 1.5  → 1.0x (normal)
    ratio < 2.0  → 1.5x
    ratio < 3.0  → 2.0x
    ratio ≥ 3.0  → 3.0x (cap — avoid gouging)

Store in Redis:
  SET surge:{h3_index} 1.5 EX 60  ← expires in 60s, must be refreshed

On pricing request:
  h3_index = h3.latLonToCell(pickup_lat, pickup_lon, resolution=7)
  multiplier = redis.get("surge:" + h3_index) ?? 1.0
  price = base_price × multiplier
```

---

## Data Model

```sql
-- Trips (PostgreSQL — relational, payment linkage)
CREATE TABLE trips (
  id               UUID PRIMARY KEY,
  rider_id         UUID NOT NULL,
  driver_id        UUID,
  status           VARCHAR(20) NOT NULL,
    -- REQUESTED, MATCHED, PICKUP_IN_PROGRESS, IN_PROGRESS, COMPLETED, CANCELLED
  pickup_lat       DECIMAL(10,7),
  pickup_lon       DECIMAL(10,7),
  pickup_address   TEXT,
  dropoff_lat      DECIMAL(10,7),
  dropoff_lon      DECIMAL(10,7),
  dropoff_address  TEXT,
  vehicle_type     VARCHAR(20),
  estimated_fare   DECIMAL(10,2),
  final_fare       DECIMAL(10,2),
  surge_multiplier DECIMAL(4,2) DEFAULT 1.0,
  distance_km      DECIMAL(8,2),
  duration_sec     INT,
  requested_at     TIMESTAMPTZ DEFAULT NOW(),
  matched_at       TIMESTAMPTZ,
  started_at       TIMESTAMPTZ,
  completed_at     TIMESTAMPTZ,
  payment_id       UUID
);

-- Location history (Cassandra — time-series per trip)
CREATE TABLE trip_locations (
  trip_id      UUID,
  recorded_at  TIMESTAMP,
  lat          DECIMAL(10,7),
  lon          DECIMAL(10,7),
  heading      SMALLINT,
  speed_kmh    SMALLINT,
  PRIMARY KEY (trip_id, recorded_at)
) WITH CLUSTERING ORDER BY (recorded_at ASC);
-- Partitioned by trip_id: all locations for a trip in one Cassandra partition

-- Drivers
CREATE TABLE drivers (
  id              UUID PRIMARY KEY,
  user_id         UUID NOT NULL,
  vehicle_type    VARCHAR(20),
  vehicle         JSONB,  -- { model, plate, color, capacity }
  license_number  VARCHAR(50),
  rating          DECIMAL(3,2),
  total_trips     INT DEFAULT 0,
  acceptance_rate DECIMAL(4,3),
  is_verified     BOOLEAN DEFAULT FALSE
);
```

---

## ETA Calculation

```
ETA = f(driver_current_position, pickup_position, current_traffic)

Options:
A) Google Maps Distance Matrix API: accurate, real-time traffic, costs money
B) Mapbox: similar, slightly cheaper
C) OSRM (Open Source Routing Machine): self-hosted, fast, uses historical traffic data
D) Simple Haversine + average speed: inaccurate but zero cost

In production:
  Uber uses their own internal routing engine trained on historical trip data
  For a startup/interview context: OSRM self-hosted + traffic overlay

Caching ETAs:
  Origin-destination ETAs change slowly
  Cache: "driver at A to pickup at B" ETA for 30 seconds
  Key: "eta:{h3(driver)}:{h3(pickup)}" → estimated_seconds
  → Reduces routing API calls by 90% (many drivers near the same pickup point)
```

---

## Handling Driver Going Offline Mid-Trip

```
Driver app loses connectivity during active trip:
  1. Driver's WebSocket disconnects from gateway
  2. Gateway detects disconnect → publishes event: driver.disconnected { trip_id }
  3. Trip Service receives event:
     - If status = IN_PROGRESS: mark trip as "connection_lost"
     - Do NOT cancel trip immediately (might be a tunnel)
  4. Grace period: 60 seconds
     - If driver reconnects within 60s: resume normally
     - If not: escalate to support team
  5. Rider app shows: "Driver connection lost, please wait..."

Driver location TTL in Redis:
  EXPIRE driver:{id}:status 60
  If driver goes truly offline (app killed): key expires → removed from active pool
  → New ride offers stop going to this driver
  → Existing trip: handled by grace period above
```

---

## API Design

```
POST /rides/request
{ "pickup": { "lat": 48.8566, "lon": 2.3522 },
  "dropoff": { "lat": 48.8744, "lon": 2.3522 },
  "vehicle_type": "STANDARD" }
→ 202: { "ride_id": "...", "estimated_fare": "8.76 EUR", "estimated_wait_sec": 180 }

GET  /rides/{rideId}               → current trip state
GET  /rides/{rideId}/track         → SSE stream: driver location updates

PUT  /rides/{rideId}/cancel        → rider cancels
POST /rides/{rideId}/rate          → { rating: 5, comment: "Great ride" }

POST /driver/location              → { lat, lon, heading, speed }   (heartbeat)
POST /driver/status                → { status: "AVAILABLE" | "OFFLINE" }
PUT  /driver/rides/{offerId}/accept
PUT  /driver/rides/{offerId}/reject

GET  /pricing/estimate?from_lat=...&from_lon=...&to_lat=...&to_lon=...
```

---

## Tech Stack

- **Backend**: Java 17, Spring Boot, Spring WebFlux (reactive WebSocket)
- **Geospatial**: Redis GEO (active driver positions, O(log N) radius queries)
- **Location History**: Cassandra (time-series, append-heavy, trip-partitioned)
- **Trip Data**: PostgreSQL (relational, payment linkage, ACID)
- **Real-time**: WebSocket via Netty (high concurrency, non-blocking)
- **Event Bus**: Kafka (location.updates, ride.events)
- **Surge Zones**: H3 library (Java) + Redis (surge multiplier per hex)
- **Routing/ETA**: OSRM (self-hosted) or Google Maps API
- **Notifications**: FCM/APNS (when app is backgrounded)
- **Payments**: Stripe (charge at trip end)

---

## Interview Q&A

**Q1: How does Redis GEO work internally? Why is it fast for geospatial queries?**

A: Redis GEO uses **geohash** encoding internally. A geohash divides the Earth's surface recursively into a grid, encoding each cell as a bit string. Redis uses a 52-bit precision geohash encoded as a 64-bit integer. This integer is stored as the **score** in a Redis Sorted Set, with the driver ID as the member. Because nearby geographic points have similar geohash values (spatially close → numerically close), a "find points within radius" query becomes a range scan on the sorted set: find all scores between `geohash(center) - delta` and `geohash(center) + delta`. Redis handles edge cases (points crossing the anti-meridian, polar distortions) internally. The result is O(log N + K) time complexity for radius queries, where N is total active drivers and K is result count — < 1ms for 1.5M drivers.

---

**Q2: How do you prevent a driver from being offered multiple rides simultaneously?**

A: When a driver is offered a ride, they are immediately removed from the available pool: `ZREM drivers:active {driver_id}`. This happens before contacting the driver. If the driver rejects (or times out), they are re-added: `GEOADD drivers:active {lon} {lat} {driver_id}`. This means while a driver is being offered ride A, they cannot receive offer B — their key is not in the available sorted set. If the matching service crashes between removing the driver from the pool and notifying them: the driver believes they're available, but Redis says they're not. A background reconciliation job (runs every 30 seconds) checks: is `driver:{id}:status = AVAILABLE` but `driver_id NOT IN drivers:active`? If so, re-add to pool. This handles the race condition edge case.

---

**Q3: Surge pricing shows 3x in an area. The rider accepts, books the ride, but by the time the driver arrives, surge drops to 1x. Which price does the rider pay?**

A: This is a contractual question that also has a system design dimension. Uber's approach: **lock the price at the time the rider confirms the booking**. The surge multiplier is captured in the trip record at request time and stored in the `trips.surge_multiplier` column. Even if surge changes during the trip, the stored multiplier is used for final fare calculation. The system must: (1) capture surge at booking time, (2) store it durably in the trip record, (3) not re-query it at payment time. This is also why the pricing estimate is shown to the rider before confirmation — they consent to the estimated price including the current surge.

---

**Q4: How would you scale the system to handle a major concert ending (50,000 people requesting rides simultaneously)?**

A: This is a planned load spike. Strategies: (1) **Predictive scaling**: ops team knows the concert ends at 23:00 — scale up Matching Service, API Gateway, and Redis cluster 30 minutes before. (2) **Queue-based request management**: instead of direct matching, concert-area requests enter a priority queue. The system processes them FIFO at its maximum sustainable rate, notifying riders of their position. (3) **Geofenced surge**: the concert venue's H3 cells get aggressive surge pricing — attracts more drivers to the area proactively. (4) **Circuit breaker on acceptance rate**: if matching fails 5 times for a rider, hold for 30 seconds before retrying — prevents thundering herd on matching service. (5) **Graceful degradation**: if all drivers are taken, show "estimated wait: 45 minutes" rather than "no drivers" — manages expectations.

---

**Q5: How does the routing between driver gateway servers work when rider and driver are on different WebSocket servers?**

A: When a driver connects to a WebSocket gateway, that gateway registers: `SET driver:{id}:gateway "gateway-host-07"`. When a rider needs to receive a message routed through the driver's update (e.g., driver accepted, driver is approaching), the Location Service looks up `GET driver:{trip_id}:rider_gateway → "gateway-host-42"`. To send a message to a connection on a different gateway, there are two approaches: (1) **Redis Pub/Sub**: publish to channel `gateway-42:messages`; gateway-42 subscribes to its own channel and delivers to the appropriate WebSocket session. Latency: < 5ms. (2) **Kafka topic per gateway**: each gateway has its own Kafka partition; routed messages are published to the correct partition. Kafka-based routing handles gateway restarts better (messages persisted until gateway comes back). For 100 gateway nodes, option 1 (Redis Pub/Sub) is simpler; option 2 (Kafka) is more reliable for production.

---

**Q6: What happens to the matching algorithm if the driver database is stale and a driver listed as AVAILABLE is actually on a trip?**

A: This is a consistency challenge. The available driver pool in Redis (`drivers:active`) is the authoritative real-time state. The PostgreSQL driver table may lag by seconds. The solution: always query Redis (not DB) to determine driver availability during matching. When a driver is offered a ride, the offer flow does a final status check: `GET driver:{id}:status`. If it returns `ON_TRIP` (race condition where driver just accepted another offer), skip this candidate and move to the next. This "check-offer-check" pattern (read-check-then-offer-then-check-again) handles the race condition at the cost of one extra Redis read per offer. The probability of this race is low but non-zero at scale, so it must be handled.

---

**Q7: How do you compute ETA accurately when traffic conditions change mid-trip?**

A: Initial ETA (at trip start) is computed using current traffic data from the routing engine. As the trip progresses, ETA is updated dynamically: (1) every 30 seconds, recompute ETA using the driver's current GPS position and current traffic. (2) Push updated ETA to rider via WebSocket. (3) ETA changes are normal — communicate them proactively ("ETA updated: 8 minutes" vs. "ETA updated: 12 minutes due to traffic"). Machine learning improves ETA: historical trip data from this route at this time of day, day of week, and weather conditions provides a better prior than pure routing algorithms. Uber's internal ML model reportedly achieves < 1-minute ETA error for most trips.

---

**Q8: How would you design the driver earnings and payout system?**

A: Earnings calculation happens at trip end: `fare = base_fare + per_km_rate × distance + per_min_rate × duration - platform_commission`. This is computed by the Pricing Service using the locked-in surge multiplier from the trip record. The result is written to: (1) the trip record (final_fare), (2) a driver_earnings ledger table (append-only, double-entry). Payouts happen on a schedule (daily or weekly): the Payout Service aggregates all completed earnings since last payout → transfers via Stripe Connect or bank ACH. Key design: earnings are always computed from the immutable trip record, never re-computed. This ensures driver payments are audit-able and can be disputed with exact trip data.

---

**Q9: How do you handle fraud? A driver claims to have completed a trip but never picked up the rider.**

A: Multiple signals are used for fraud detection: (1) **GPS verification**: trip_locations in Cassandra shows the actual route taken. If the driver's GPS never came within 200 meters of the pickup point, the "trip started" event is suspicious. (2) **Rider confirmation**: require rider to tap "Driver arrived" or scan a QR code — this proves physical co-location. (3) **Duration anomalies**: a 10-km trip that took 2 minutes is physically impossible. Flag for review. (4) **Rating correlation**: a driver with many 1-star ratings and "never showed up" complaints is flagged. (5) **ML model**: trains on historical fraud cases to score each trip in real-time. Trips above a threshold are held for review before payout. Fraud detection is asynchronous — it doesn't block the rider experience, it flags trips for human review post-completion.

---

**Q10: How would you design the system to comply with GDPR — specifically the right to erasure for riders?**

A: A rider invoking the right to erasure requires careful handling of linked data: (1) **Personal data**: delete user profile (name, email, phone, payment methods). (2) **Trip records**: financial/legal retention requirements mean trip records (amount, date, rough location) must be kept for 7 years in France — but can be pseudonymized by replacing `rider_id` with a non-identifiable surrogate key. (3) **Location history**: GPS traces are personal data. Delete from Cassandra after financial retention period expires, or at erasure request if not needed for active dispute. (4) **Ratings**: driver ratings that mention a rider by name must be anonymized. The system must support "soft delete + anonymization" rather than hard delete for most records, because the trip happened and has financial and legal implications.

# System Design Problems

Famous system design problems with full solutions: architecture, data model, API, scalability, and tech stack.
Each file covers: Requirements → Capacity → Architecture → Data Model → API → Scale decisions → Tech Stack.

---

## Index

| # | Problem | Key Patterns |
|---|---|---|
| [01](01-rate-limiter.md) | **Rate Limiter** | Sliding window, Redis, Token bucket |
| [02](02-flight-reservation.md) | **Flight Reservation** | Optimistic lock, Saga, Inventory |
| [03](03-url-shortener.md) | **URL Shortener** | Base62, Snowflake ID, Redis cache |
| [04](04-notification-system.md) | **Notification System** | Multi-channel, Priority queue, Retry |
| [05](05-payment-system.md) | **Payment System** | Double-entry ledger, Idempotency, Saga |
| [06](06-chat-system.md) | **Chat System** | WebSocket, Cassandra, Fanout |
| [07](07-api-gateway.md) | **API Gateway** | JWT auth, Circuit breaker, Rate limit |
| [08](08-distributed-cache.md) | **Distributed Cache** | Consistent hashing, LRU, Cache patterns |
| [09](09-news-feed.md) | **News Feed** | Push/pull fanout, Redis sorted set |
| [10](10-distributed-message-queue.md) | **Message Queue** | Kafka, Partitioning, Exactly-once |
| [11](11-ride-sharing.md) | **Ride-Sharing** | Geo search, WebSocket, Surge pricing |
| [12](12-search-autocomplete.md) | **Search Autocomplete** | Trie, Redis sorted set, Fuzzy match |
| [13](13-distributed-job-scheduler.md) | **Job Scheduler** | Time wheel, Leader election, Cron |
| [14](14-hotel-booking.md) | **Hotel Booking** | Inventory lock, ElasticSearch, Saga |
| [15](15-event-driven-order-management.md) | **Order Management (EDA+CQRS)** | Event sourcing, Outbox pattern, Saga |
| [16](16-stock-exchange.md) | **Stock Exchange** | Order book, Matching engine, LMAX |
| [17](17-video-streaming.md) | **Video Streaming** | HLS, CDN, Adaptive bitrate, FFmpeg |
| [18](18-web-crawler.md) | **Web Crawler** | Bloom filter, Politeness, SimHash |
| [19](19-auth-service.md) | **Auth Service** | JWT RS256, OAuth2, RBAC, MFA |
| [20](20-cicd-devsecops-pipeline.md) | **CI/CD DevSecOps Pipeline** | GitLab CI, Helm, Vault, Quality gates |

---

## Common Tech Stack (Java ecosystem)

| Layer | Technology |
|---|---|
| API | Java 17, Spring Boot, Spring Security |
| Database | PostgreSQL (ACID), Cassandra (time-series) |
| Cache | Redis (data cache, sessions, geo, pub/sub) |
| Messaging | Kafka (high-volume), RabbitMQ (routing) |
| Search | ElasticSearch |
| Container | Docker, Kubernetes, Helm |
| Cloud | AWS (EKS, S3, SES, ECR) |
| Security | Vault, SonarQube, Trivy, OWASP DC |
| Observability | Prometheus, Grafana, ELK, Jaeger |

---

## Recurring Design Patterns

- **Saga** — distributed transactions without 2PC (02, 05, 14, 15)
- **Outbox Pattern** — reliable event publishing (15)
- **CQRS** — separate read/write models (15, 16)
- **Event Sourcing** — state from events (15)
- **Optimistic Locking** — concurrent writes without blocking (02, 14)
- **Consistent Hashing** — partitioning (08, 10)
- **Bloom Filter** — probabilistic dedup at scale (18)
- **Idempotency Key** — exactly-once semantics (02, 05, 10)
- **Circuit Breaker** — resilience (07)
- **Fanout** — push vs pull tradeoff (09, 06)

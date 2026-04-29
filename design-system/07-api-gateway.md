# API Gateway — Deep Design Guide

## Problem Statement

Design an API Gateway — the single entry point for all client requests to a microservices backend. It handles routing, authentication, rate limiting, load balancing, circuit breaking, and observability — so that downstream services don't need to implement these cross-cutting concerns individually.

---

## Why This Problem Matters

Without an API Gateway, every microservice must implement authentication, rate limiting, SSL termination, logging, and CORS independently. In a 20-service architecture that's 20 separate implementations — each different, each with bugs, each needing maintenance. The API Gateway pattern centralizes these concerns.

But the gateway is also the **single point of failure** for your entire platform. A misconfigured gateway can take down every service simultaneously. This tension — centralize concerns vs. avoid single points of failure — is what interviewers want to explore.

**What interviewers are testing**:
- Do you understand *why* the gateway pattern exists and what it buys you vs. what it risks?
- Can you explain JWT validation correctly — specifically why RS256 (asymmetric) beats HS256 in a microservices context?
- Do you understand the circuit breaker state machine deeply enough to explain failure and recovery?
- Can you reason about zero-downtime config reload?
- Do you understand distributed tracing and why correlation IDs are non-optional at scale?

---

## Key Insight Before Diving In

**The API Gateway is not a proxy — it is a policy enforcement point.**

A proxy forwards requests. A gateway enforces: who can call what, how often, with what retry behavior, and what to do when the backend is unhealthy. The difference is important architecturally: a gateway is stateful (it maintains circuit state, rate limit counters, config) while a proxy is stateless.

The second key insight: **JWT validation at the gateway must be stateless**. If the gateway calls the auth service to validate every token, the auth service becomes a bottleneck and a dependency. Using RS256 (public/private key pairs), each gateway instance can validate tokens using only the public key — no network call. This is why asymmetric JWT signing exists.

---

## Requirements

### Functional
- Route requests to the correct backend service based on path, method, and headers
- Authenticate requests (JWT Bearer token, API Key, OAuth2 token introspection)
- Rate limit per client identity (user, API key, IP)
- SSL/TLS termination (clients use HTTPS; internal services can use HTTP)
- Request/response transformation (add/remove headers, rewrite paths)
- Circuit breaker for failing backends
- API versioning (route `/api/v1/` and `/api/v2/` to different service versions)
- WebSocket proxying (for real-time features)
- CORS handling

### Non-Functional
- Added latency overhead: < 5ms P99 (gateway must not be the bottleneck)
- 99.999% availability (5 nines — a gateway outage is a total platform outage)
- Handle 100,000 req/sec peak
- Zero-downtime config reload (route changes without restart)
- Horizontal scaling (stateless gateway nodes, shared state in Redis)

---

## Capacity Estimation

```
Traffic: 100,000 req/sec peak
Latency budget: 5ms gateway overhead
  JWT verification (RSA public key): ~0.5ms (cryptographic, in-memory)
  Redis rate limit check: ~0.3ms (local Redis or pipelined)
  Route lookup: ~0.01ms (in-memory hash map)
  Header manipulation: ~0.01ms
  Total: ~1ms actual overhead (well under 5ms budget)

Memory per gateway node:
  Route table: 10,000 routes × 1KB = 10MB
  Circuit breaker state: 100 services × 1KB = 100KB
  JWT public keys: 10 keys × 4KB = 40KB
  Total config state: ~11MB (negligible)

Network:
  100k req/sec × 2KB avg request = 200MB/sec ingress
  100k req/sec × 5KB avg response = 500MB/sec egress
  → 10 gateway nodes × 70MB/sec each = manageable
```

---

## High-Level Architecture

```
Internet (clients)
        │ HTTPS
        ▼
┌───────────────────────────────────────────────────────────────┐
│               Load Balancer (L4/L7 — AWS ALB / Nginx)         │
│  Distributes TCP connections across gateway nodes             │
└────────────────────────────┬──────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
┌────────────┐       ┌────────────┐       ┌────────────┐
│  Gateway   │       │  Gateway   │       │  Gateway   │
│  Node 1    │       │  Node 2    │       │  Node 3    │
│            │       │            │       │            │
│ Filter     │       │ Filter     │       │ Filter     │
│ Pipeline:  │       │ Pipeline:  │       │ Pipeline:  │
│ 1.SSL term │       │ 1.SSL term │       │ 1.SSL term │
│ 2.Auth     │       │ 2.Auth     │       │ 2.Auth     │
│ 3.RateLimit│       │ 3.RateLimit│       │ 3.RateLimit│
│ 4.Route    │       │ 4.Route    │       │ 4.Route    │
│ 5.LB       │       │ 5.LB       │       │ 5.LB       │
│ 6.CircBreak│       │ 6.CircBreak│       │ 6.CircBreak│
│ 7.Transform│       │ 7.Transform│       │ 7.Transform│
│ 8.Trace    │       │ 8.Trace    │       │ 8.Trace    │
└──────┬─────┘       └──────┬─────┘       └──────┬─────┘
       │                    │                    │
       └────────────────────┼────────────────────┘
                            │ HTTP (internal network)
         ┌──────────────────┼──────────────────────┐
         ▼                  ▼                      ▼
  User-Service       Order-Service         Payment-Service
         │                  │                      │
         └──────────────────┴──────────────────────┘
                            │
                    Shared State (Redis Cluster)
                    - Rate limit counters
                    - Circuit breaker state (optional)
                    - Token blocklist
```

---

## Filter Pipeline — Processing Order Matters

The order of filters is critical. Rejecting unauthenticated requests early avoids unnecessary processing:

```
Incoming Request
      │
      ▼ [1] SSL Termination
        Decrypt HTTPS → plain HTTP internally
        Extract client cert if mTLS
      │
      ▼ [2] Request ID Injection
        Generate UUID if missing: X-Request-Id: a3f8-...
        This becomes the correlation ID for distributed tracing
      │
      ▼ [3] Authentication Filter
        Extract Bearer token / API Key
        Validate JWT signature (RS256 — no network call)
        Check token expiry, issuer, audience
        → 401 if invalid (stop here, don't touch rate limiter or backend)
      │
      ▼ [4] Rate Limiting Filter
        Redis sliding window check (user + endpoint + window)
        → 429 with Retry-After header if exceeded
      │
      ▼ [5] Route Matching
        Match request path + method → target service + path rewrite
        → 404 if no route matches
      │
      ▼ [6] Circuit Breaker Check
        Is target service healthy? (circuit state = OPEN?)
        → 503 Service Unavailable if circuit OPEN (fail fast)
      │
      ▼ [7] Load Balancer
        Select target instance from service pool
        (Round robin / least connections / consistent hash)
      │
      ▼ [8] Header Transformation
        Add: X-User-Id, X-User-Roles (from JWT claims)
        Add: X-Forwarded-For, X-Real-IP
        Remove: Authorization (don't forward raw token to services)
        Rewrite: Host header to internal service hostname
      │
      ▼ [9] Forward to Backend
      │
      ▼ [10] Response Transformation
        Add CORS headers
        Add security headers (X-Frame-Options, HSTS, CSP)
        Strip internal headers (X-Internal-Service-Name)
      │
      ▼ [11] Logging + Metrics
        Log: {request_id, method, path, status, latency_ms, user_id}
        Metrics: increment counters, record histogram
      │
      ▼ Response to Client
```

---

## JWT Authentication — RS256 vs HS256

### HS256 (Symmetric — Wrong for Microservices)

```
Auth Service signs:   JWT = HMAC-SHA256(header.payload, SECRET_KEY)
Gateway verifies:     HMAC-SHA256(header.payload, SECRET_KEY) == signature?

Problem: Every gateway node needs the SECRET_KEY.
         Every microservice that verifies tokens needs the SECRET_KEY.
         SECRET_KEY is now spread across 50+ services.
         If any service is compromised, the attacker can forge any JWT.
         Rotating the key requires coordinated update of all services simultaneously.
```

### RS256 (Asymmetric — Correct for Microservices)

```
Auth Service:
  Has PRIVATE key (signs tokens) — stored in Vault, never shared
  JWT = RSA-SHA256(header.payload, PRIVATE_KEY)

Gateway + all services:
  Have PUBLIC key only (verify tokens) — safe to distribute
  Valid? = RSA-SHA256-verify(header.payload, PUBLIC_KEY, signature)

Benefits:
  Compromise of gateway → attacker can only verify tokens, not forge them
  Key rotation: deploy new PUBLIC key to all services (safe, no secret exposure)
  Services can self-validate without calling Auth Service (stateless)
```

```java
@Component
public class JwtAuthFilter implements WebFilter {

    // Loaded at startup from JWKS endpoint (JSON Web Key Set)
    // Public key only — safe to cache in memory
    private final RSAPublicKey publicKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing authorization header");
        }

        String token = authHeader.substring(7);

        try {
            // Pure cryptographic verification — no DB call, no network call
            // Verifies: signature (RS256), expiry (exp), issuer (iss), audience (aud)
            Claims claims = Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .requireIssuer("https://auth.myapp.com")
                .requireAudience("api.myapp.com")
                .build()
                .parseClaimsJws(token)
                .getBody();

            // Check token blocklist (for logout/revocation)
            // This IS a Redis call — only way to support revocation
            String jti = claims.getId();
            if (tokenBlocklist.isRevoked(jti)) {
                return unauthorized(exchange, "Token has been revoked");
            }

            // Inject user context as headers for downstream services
            // Services read X-User-Id instead of parsing JWT themselves
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id", claims.getSubject())
                .header("X-User-Roles", String.join(",", (List<String>) claims.get("roles")))
                .header("X-User-Email", claims.get("email", String.class))
                .build();

            return chain.filter(exchange.mutate().request(mutated).build());

        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, "Token expired");
        } catch (JwtException e) {
            return unauthorized(exchange, "Invalid token");
        }
    }
}
```

---

## Circuit Breaker — State Machine Deep Dive

The circuit breaker pattern prevents cascading failures. When a backend service starts failing, the gateway detects it and stops forwarding requests — failing fast rather than waiting for timeouts.

```
                    ┌─────────────────────────────────────┐
                    │                                     │
                    ▼         failure_rate > threshold    │
         ┌──────────────────┐ ─────────────────────────► ┌──────────────────┐
         │                  │                             │                  │
         │     CLOSED       │                             │      OPEN        │
         │  (normal ops)    │ ◄─────────────────────────  │  (fail fast)    │
         │                  │   probe succeeds            │                  │
         └──────────────────┘                             └─────────┬────────┘
                                                                    │
                                                         wait_duration_open (e.g. 30s)
                                                                    │
                                                                    ▼
                                                         ┌──────────────────┐
                                                         │                  │
                                                         │   HALF-OPEN      │
                                                         │ (trial request)  │
                                                         │                  │
                                                         └──────────────────┘
                                                           │            │
                                                  success  │            │ failure
                                                           ▼            ▼
                                                        CLOSED        OPEN
```

**CLOSED state**: All requests flow through. Failure rate is tracked in a sliding window.
- If failure_rate > threshold (e.g., 50%) → **OPEN**

**OPEN state**: All requests immediately fail with 503 (no backend contact). Fast failure means:
- Users see an error immediately (not after a 30-second timeout)
- Backend gets zero traffic → opportunity to recover
- After `wait_duration_open` (e.g., 30 seconds) → **HALF-OPEN**

**HALF-OPEN state**: Let exactly one probe request through.
- If it succeeds → **CLOSED** (service recovered)
- If it fails → **OPEN** again (not ready yet, wait another 30s)

```java
@Bean
public CircuitBreakerRegistry circuitBreakerRegistry() {
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .failureRateThreshold(50)                        // Open if 50%+ requests fail
        .slowCallRateThreshold(80)                       // Open if 80%+ calls take > 2s
        .slowCallDurationThreshold(Duration.ofSeconds(2))
        .waitDurationInOpenState(Duration.ofSeconds(30)) // Stay open for 30s
        .permittedNumberOfCallsInHalfOpenState(3)        // Allow 3 probes in HALF-OPEN
        .slidingWindowType(SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(20)                           // Evaluate last 20 calls
        .minimumNumberOfCalls(10)                        // Need at least 10 calls before opening
        .recordExceptions(IOException.class, TimeoutException.class)
        .build();

    return CircuitBreakerRegistry.of(config);
}
```

**Why `minimumNumberOfCalls`?** Without it, the circuit opens after the first failed request (1/1 = 100%). With `minimumNumberOfCalls=10`, it waits for a meaningful sample before making decisions.

---

## Load Balancing Algorithms

```java
public interface LoadBalancer {
    ServiceInstance choose(List<ServiceInstance> instances, ServerWebExchange exchange);
}

// Round Robin — default, stateless, equal distribution
public class RoundRobinLoadBalancer implements LoadBalancer {
    private final AtomicInteger counter = new AtomicInteger(0);

    public ServiceInstance choose(List<ServiceInstance> instances, ServerWebExchange exchange) {
        int idx = Math.abs(counter.getAndIncrement() % instances.size());
        return instances.get(idx);
    }
}

// Weighted Round Robin — for heterogeneous instances (some stronger)
// Instance weights: [A:3, B:2, C:1] → A gets 3/6=50%, B gets 2/6=33%, C gets 1/6=17%

// Least Connections — best for long-lived connections (WebSocket, large uploads)
// Route to instance with fewest active connections
// Requires connection tracking (in-memory per gateway node)

// Consistent Hash — for sticky sessions (user always routed to same instance)
// hash(X-User-Id) % instances.size() → same user → same instance
// Problem: when instances added/removed, hash changes for many users → use consistent hashing ring
```

---

## Route Configuration and Zero-Downtime Reload

Routes are stored in a configuration store (DB + Redis cache). Changes take effect without restart.

```yaml
# routes.yaml (stored in DB, version-controlled)
routes:
  - id: user-service-v2
    predicates:
      - Path=/api/v2/users/**
      - Method=GET,POST,PUT,DELETE
    uri: lb://user-service          # lb:// = load balanced via service discovery
    filters:
      - StripPrefix=2               # /api/v2/users/123 → /users/123 to backend
      - name: Auth
        args: { required: true }
      - name: RateLimit
        args: { key: user_id, limit: 100, window: 60s }
      - name: CircuitBreaker
        args: { name: user-service, fallbackUri: forward:/fallback/user }
    metadata:
      tier: core
      owner: platform-team

  - id: public-products
    predicates:
      - Path=/api/v1/products/**
      - Method=GET
    uri: lb://product-service
    filters:
      - name: Auth
        args: { required: false }   # public endpoint, no auth needed
      - name: Cache
        args: { ttl: 300s }         # CDN-style response caching
      - name: RateLimit
        args: { key: ip, limit: 1000, window: 60s }
```

**Zero-downtime reload mechanism**:
```
Config change committed to Git → CI/CD deploys to DB
→ Kafka event: route.config.changed
→ All gateway nodes subscribe to this event
→ Each node: reload routes from DB into memory (atomic swap)
→ New routes take effect immediately, no restart, no connection drop
→ In-flight requests continue on old routes until complete
```

---

## Distributed Tracing — Correlation IDs

When a request fails, you need to trace it across 5+ services. Without correlation IDs, logs from different services cannot be linked.

```
Client sends: GET /api/v2/orders/123

Gateway generates: X-Request-Id: 7f3a9b2c-e4d5-4f1a-8c6d-1234567890ab
Gateway forwards this header to Order Service.
Order Service forwards it to Payment Service.
Order Service forwards it to Inventory Service.

All logs across all services contain the same X-Request-Id.

In ELK/Grafana Loki, query:
  request_id:"7f3a9b2c-e4d5-4f1a-8c6d-1234567890ab"
→ See the full journey: gateway (2ms) → order-service (45ms) → payment-service (1.2s) → inventory-service (12ms)
→ Identify payment-service as the bottleneck immediately
```

```java
// MDC (Mapped Diagnostic Context) propagation in each service
@Component
public class RequestIdFilter implements WebFilter {
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders()
            .getFirst("X-Request-Id");
        if (requestId == null) requestId = UUID.randomUUID().toString();

        // Put in MDC so every log line in this thread includes it automatically
        MDC.put("requestId", requestId);

        // Also put in reactor context for async/reactive chains
        return chain.filter(exchange)
            .contextWrite(ctx -> ctx.put("requestId", requestId))
            .doFinally(signal -> MDC.remove("requestId"));
    }
}
```

---

## Data Model

```sql
-- Route definitions (source of truth)
CREATE TABLE gateway_routes (
  id              UUID PRIMARY KEY,
  route_id        VARCHAR(100) UNIQUE NOT NULL,
  path_pattern    VARCHAR(500) NOT NULL,
  methods         TEXT[],
  target_service  VARCHAR(100) NOT NULL,
  path_rewrite    VARCHAR(255),
  auth_required   BOOLEAN DEFAULT TRUE,
  rate_limit      JSONB,     -- { key: 'user_id', limit: 100, window: '60s' }
  circuit_breaker JSONB,     -- { threshold: 50, window: 20, open_duration: '30s' }
  cache_config    JSONB,     -- { ttl: '300s', vary: ['Accept-Language'] }
  version         INT DEFAULT 1,
  enabled         BOOLEAN DEFAULT TRUE,
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Access log (sampled — 100% would be 100k rows/sec)
CREATE TABLE gateway_access_log (
  id            UUID PRIMARY KEY,
  request_id    VARCHAR(64),
  timestamp     TIMESTAMPTZ,
  client_ip     INET,
  user_id       UUID,
  method        VARCHAR(10),
  path          TEXT,
  target        VARCHAR(100),
  status_code   INT,
  latency_ms    INT,
  request_size  INT,
  response_size INT
) PARTITION BY RANGE (timestamp);
-- Partition by day, retain 30 days, archive older
```

---

## API Design (Gateway Management API)

```
# Route management
GET    /admin/routes                     → list all routes
POST   /admin/routes                     → create route
PUT    /admin/routes/{routeId}          → update route
DELETE /admin/routes/{routeId}          → disable route
POST   /admin/routes/reload             → force config reload

# Circuit breaker management
GET    /admin/circuit-breakers          → all circuit states
POST   /admin/circuit-breakers/{service}/reset → force to CLOSED

# Health and metrics
GET    /actuator/health                 → gateway health
GET    /actuator/metrics               → Prometheus metrics
GET    /actuator/gateway/routes        → active route table (Spring Cloud GW)
```

---

## Tech Stack

- **Gateway**: Spring Cloud Gateway (Java 17, reactive/non-blocking via Project Reactor)
- **Auth**: JJWT (RS256 validation), public key from JWKS endpoint
- **Rate Limiting**: Redis (Lua scripts for atomic sliding window)
- **Circuit Breaker**: Resilience4j
- **Service Discovery**: Kubernetes DNS (`lb://service-name` resolves via K8s Service)
- **Config Store**: PostgreSQL + Redis cache + Kafka notifications
- **Observability**: Micrometer → Prometheus → Grafana; MDC logging → ELK; OpenTelemetry → Jaeger
- **Load Balancer**: Spring Cloud LoadBalancer (round robin / weighted)

---

## Interview Q&A

**Q1: Why is the API Gateway considered a single point of failure, and how do you mitigate it?**

A: Every request passes through the gateway, so if it goes down, the entire platform is unavailable. Mitigation is multi-layered: (1) **Horizontal scaling** — run 5+ gateway instances behind a load balancer; no single node failure affects traffic. (2) **Statelessness** — gateway nodes hold no session state; any node can serve any request. Rate limit counters and circuit breaker state live in Redis, not the gateway. (3) **Health checks** — the load balancer removes unhealthy gateway instances from rotation within seconds. (4) **Active-active multi-region** — deploy gateway clusters in multiple regions; DNS failover routes to healthy region. (5) **Simple code** — the gateway does minimal logic. Less code = fewer bugs = fewer crashes.

---

**Q2: A downstream service (Payment Service) is experiencing intermittent 30-second timeouts. How does the gateway protect other services from being affected?**

A: This is exactly what circuit breakers solve. The gateway has a circuit breaker per downstream service. When Payment Service starts timing out at 30 seconds, all gateway threads handling those requests are blocked for 30 seconds each. With 100 concurrent requests at 30s each, 100 threads are consumed — gateway becomes unresponsive for other clients too. Circuit breaker detects the high failure/slow-call rate and **opens**: subsequent requests to Payment Service immediately get a 503 response (fail fast) without waiting 30 seconds. This frees gateway threads to serve Order Service, User Service, etc. — the blast radius is contained. Meanwhile, the payment team gets paged, and the circuit moves to HALF-OPEN after 30 seconds to test if recovery happened.

---

**Q3: How do you handle JWT token revocation when JWTs are validated statically (no DB call)?**

A: Static JWT validation (RS256 signature check + expiry) cannot detect revocation — that information isn't in the token. Solutions: (1) **Short TTL** (15 minutes): accept that a revoked token can be used for up to 15 minutes. Sufficient for most logout scenarios. (2) **Token blocklist in Redis**: store revoked JWT IDs (`jti` claim) in Redis with TTL matching the token expiry. Gateway checks `SISMEMBER jwt:blocklist {jti}` on every request — one Redis call. Adds ~0.3ms overhead but provides true revocation. (3) **Rotate public keys**: emergency — if a signing key is compromised, rotate to a new key. All tokens signed with old key become invalid. Takes effect when gateway picks up new JWKS. Choose (1) for normal logout, (2) for security-sensitive revocation (compromised account), (3) for key compromise.

---

**Q4: How does the gateway handle versioning — routing `/api/v1/` and `/api/v2/` to different service versions?**

A: Route predicates include the version prefix, and the gateway routes to different backend service deployments. Example: `v1` routes to `user-service` deployment tagged `v1.x`, while `v2` routes to `user-service` deployment tagged `v2.x`. Both run simultaneously in Kubernetes (different Deployments, same namespace). The gateway strips the version prefix before forwarding: `/api/v2/users/123` → `/users/123` on the v2 backend. This allows gradual migration: run both versions, monitor v2 error rates, shift traffic percentage, sunset v1 only when adoption is complete. The gateway is the only place where version routing logic lives — services don't need to know their own version number.

---

**Q5: How would you implement response caching at the gateway level?**

A: The gateway can cache GET responses to avoid hitting backend services for identical requests. Implementation: (1) Cache-key is derived from: method + path + significant headers (Accept-Language, user-tier if relevant). (2) Responses are stored in Redis with configurable TTL. (3) Cache is only applied to safe methods (GET, HEAD) and cacheable status codes (200, 203, 204). (4) Backend sets `Cache-Control: max-age=300` or `no-cache`; gateway respects these headers. Invalidation: explicit purge API (`DELETE /admin/cache?path=/api/v1/products`) or tag-based invalidation. Use case: public product catalog (cache 5 min), public prices (cache 30s), personalized feeds (no cache). Caching at the gateway reduces backend load by 60-80% for read-heavy public endpoints.

---

**Q6: What is the difference between gateway-level load balancing and load balancing in Kubernetes?**

A: Kubernetes has a built-in Service resource that load balances across pod replicas at the L4 (TCP) level using iptables/kube-proxy. The gateway using `lb://service-name` delegates to this K8s Service. However, gateway-level load balancing (L7) has advantages: (1) **Healthcheck-aware routing**: gateway knows which pods are actually healthy (via actuator health endpoints), not just that they're running. (2) **Weighted routing**: gateway can send 10% of traffic to a canary pod. (3) **Sticky sessions**: gateway can use consistent hashing on user ID to route to the same pod. (4) **Retry on specific pod failure**: if pod A returns 500, gateway can retry on pod B. K8s Service can't do this; it uses random round-robin. For most microservices, K8s Service is sufficient. Use gateway-level LB for canary deployments and advanced retry logic.

---

**Q7: How would you implement a fallback response when a backend service is down?**

A: Three fallback strategies: (1) **Static fallback**: return a pre-configured static response (e.g., empty list, default values). Good for non-critical endpoints. (2) **Cache fallback**: return the last successfully cached response from Redis even if it's stale. "Show stale data rather than an error." Good for product catalogs, recommendations. (3) **Alternate service fallback**: route to a degraded version of the service (read-only replica, simplified response). Circuit breaker `fallbackUri: forward:/fallback/products` hits a local endpoint that returns a stripped-down response. Implementation in Spring Cloud Gateway: `CircuitBreakerGatewayFilterFactory` supports `fallbackUri`. The fallback endpoint is implemented as a standard Spring controller.

---

**Q8: How do you handle backward compatibility when deploying a new version of a route configuration?**

A: Route changes must be backward-compatible during the deployment window when old and new gateway versions run simultaneously. Strategies: (1) **Additive only**: only add new routes, never remove existing ones in the same deploy. Remove in a follow-up deploy after confirming no traffic hits the old route. (2) **Feature flags**: new route is deployed but `enabled: false`. Enabled via the admin API after verification. (3) **Canary routes**: route 1% of traffic to new route, monitor error rates, gradually increase. (4) **Versioned route IDs**: old route `user-service-v1`, new route `user-service-v2`. Both exist simultaneously. Deprecate v1 by adding a deprecation header (`Deprecation: true, Sunset: 2026-12-31`) before removing it. All changes are code-reviewed through the same GitOps process as application code.

---

**Q9: How do you prevent internal service headers (e.g., X-User-Id injected by the gateway) from being spoofed by external clients?**

A: External clients could forge `X-User-Id: admin-uuid` to impersonate other users. Solution: the gateway **always strips and re-injects** these headers, regardless of what the client sends. In the filter pipeline, step 1 removes any `X-User-*` headers from incoming requests, then step 8 injects them from the verified JWT claims. Services must trust only these gateway-injected headers, and the gateway must never pass client-provided versions of these headers. In Kubernetes, enforce this by configuring network policies: services only accept traffic from the gateway's pod, not directly from the internet. This way, even if an attacker reaches a service directly (port-forward exploit), their forged headers are accepted — which is why network policies (defense in depth) are necessary.

---

**Q10: How would you design the gateway for a multi-tenant SaaS platform where each tenant has different rate limits and allowed endpoints?**

A: Tenant context is embedded in the JWT (claim: `tenant_id`, `plan`). The gateway's rate limiter uses a composite key: `{tenant_id}:{endpoint}`. Tenant-specific limits are stored in Redis (pre-loaded from a tenant config DB, TTL-cached). Route predicate can include tenant constraints: some endpoints are only accessible to `ENTERPRISE` plan tenants. RBAC check: `plan=FREE` → reject requests to `/api/v1/advanced-analytics`. Implementation: a custom `TenantAwareRateLimitFilter` that loads tenant config from Redis, falls back to DB on miss, and evaluates per-tenant limits. Limits are configurable per tenant without redeployment (stored in DB, cached in Redis, invalidated via API). This is exactly how Stripe, Twilio, and AWS structure their API Gateway layers.

---

**Q11: How do you monitor the gateway in production and alert on problems?**

A: Key metrics to track:
- `gateway_requests_total{route, method, status_code}` — request volume and error rates
- `gateway_request_duration_seconds{route, quantile}` — P50/P95/P99 latency per route
- `gateway_circuit_breaker_state{service}` — 0=CLOSED, 1=OPEN, 2=HALF-OPEN
- `gateway_rate_limit_rejected_total{route}` — how many requests are being throttled
- `gateway_upstream_connections_active{service}` — connection pool saturation

Alert thresholds:
- Error rate (5xx) > 1% on any route for 5 minutes → page on-call
- P99 latency > 500ms on any route → warning
- Any circuit breaker OPEN for > 5 minutes → page on-call
- Rate limit rejection rate > 10% → investigate (possible attack or misconfigured client)
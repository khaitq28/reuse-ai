# Authentication & Authorization Service — Deep Design Guide

## Problem Statement

Design a secure, scalable authentication and authorization service — supporting registration, login, JWT tokens, OAuth2/SSO, role-based access control (RBAC), MFA, and session management — used across all microservices in a platform.

---

## Why This Problem Matters

Authentication and authorization sit at the foundation of every application's security posture. Getting this wrong doesn't just mean a bug — it means account takeover, data breaches, regulatory violations (GDPR, PCI-DSS), and potential legal liability. Interviewers use this problem to test:

- **JWT architecture in microservices**: Why RS256 (asymmetric) is critical, and why every microservice having the signing secret (HS256) is a severe security vulnerability.
- **Stateless vs. stateful tokens**: JWTs are stateless by design, but revocation requires statefulness. How do you reconcile this tension?
- **OAuth2 flow correctness**: PKCE prevents authorization code interception. Most candidates cannot explain why it exists.
- **Password security depth**: bcrypt cost factor tradeoffs, HIBP integration, timing attack resistance — each of these is a separate concern with a specific solution.
- **Refresh token rotation**: Why rotating refresh tokens prevents silent token theft — a subtle but important security property.

**What interviewers are testing**: Whether you can design a security-critical system correctly from first principles, not just recite buzzwords.

---

## Key Insight Before Diving In

**JWT validation in a microservices system must be stateless — no network call per request — but revocation inherently requires state. These two requirements are in direct tension, and the resolution is key.**

The resolution: use **short-lived access tokens** (15-minute TTL) with **stateless RS256 validation** for normal operations, combined with a **Redis blocklist** for explicit revocation (logout, account compromise). The 15-minute window means a stolen token is usable for at most 15 minutes without refresh. The Redis check is only needed for the small percentage of tokens that are explicitly revoked.

The second insight: **in a microservices architecture, the auth service should never be in the critical path of normal requests**. If User Service can only validate requests by calling Auth Service, then Auth Service downtime takes down User Service too. RS256 public key distribution allows every service to validate tokens independently — Auth Service failure only affects login/registration, not normal API operations.

---

## Requirements

### Functional
- User registration with email/password
- Social login (Google, GitHub via OAuth2)
- Login: return access token (JWT) + refresh token
- Token refresh without re-login
- Logout (explicit token revocation)
- MFA (TOTP — Time-based One-Time Password)
- RBAC: roles (USER, ADMIN, MODERATOR) + permissions (order:read, payment:write)
- Account security: lockout after failed attempts, suspicious activity alerts
- Password policies: minimum length, HIBP integration, history check

### Non-Functional
- JWT validation: < 1ms (cryptographic only, no I/O in the normal path)
- Login throughput: 1M/day → 12/sec average, 500/sec peak
- Stateless horizontal scaling (no sticky sessions)
- Token revocation propagates to all services within < 1 second
- Zero tolerance for password storage vulnerabilities (bcrypt, not MD5/SHA1)

---

## Capacity Estimation

```
Users: 100M registered, 10M daily active (DAU)

Auth operations:
  Login:   10M DAU × 1/day = 116 logins/sec avg, 500/sec peak
  Refresh: 10M DAU × 4/day = 463 refreshes/sec avg (tokens expire every 15min)
  Validate: 50M API requests/day → 580 req/sec (stateless, < 1ms each, no I/O)

Redis:
  Active sessions (refresh tokens): 10M × 200 bytes = 2GB
  Token blocklist:  100k revocations/day × 200 bytes × 7 days = 140MB
  MFA temp tokens:  1M/day × 200 bytes × TTL 5min = ~140MB peak
  Total Redis: ~3GB → fits on single node (with replicas for HA)

JWT payload size:
  sub, iat, exp, iss, aud, jti, roles[], permissions[] = ~500 bytes
  → After base64 encode: ~700 bytes per token → negligible
```

---

## JWT Architecture: RS256 vs HS256

### The Security Problem with HS256 in Microservices

```
HS256 (symmetric HMAC-SHA256):
  Auth Service signs: JWT = HMAC(header.payload, SECRET)
  Anyone with SECRET can verify AND forge tokens

Problem in microservices:
  Every service that validates tokens needs SECRET
  → SECRET distributed to 20+ services
  → If Order Service is compromised: attacker has SECRET
  → Attacker can forge: { sub: "admin-uuid", roles: ["ADMIN"] }
  → Every service accepts forged tokens as valid
  → Single service compromise = full platform compromise

  Rotating SECRET requires:
  → Simultaneously updating all 20 services
  → Brief window where old and new secrets both valid
  → Operational complexity nightmare
```

```
RS256 (asymmetric RSA-SHA256):
  Auth Service has:    PRIVATE KEY (signs tokens — never leaves auth service)
                       ← stored in Vault, accessed via K8s service account
  All other services:  PUBLIC KEY only (verifies tokens — safe to distribute)

  Compromise of Order Service:
  → Attacker gets PUBLIC KEY only
  → PUBLIC KEY can only verify, not sign
  → Attacker cannot forge tokens
  → Damage is limited to Order Service's data

  Key rotation:
  → Auth Service generates new PRIVATE KEY
  → Publishes new PUBLIC KEY to JWKS endpoint
  → All services fetch new key on next refresh cycle
  → Old tokens still valid until their exp (using old public key during transition)
  → No coordinated deployment needed
```

### JWKS (JSON Web Key Set) — Key Distribution

Auth service publishes its public keys at a well-known URL:

```
GET https://auth.myapp.com/.well-known/jwks.json

Response:
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "kid": "key-2026-01",    ← Key ID (for rotation)
      "n": "0vx7agoebGcQ...", ← RSA modulus (public key)
      "e": "AQAB"             ← RSA exponent
    }
  ]
}
```

Services cache this at startup (TTL=1 hour). JWT header contains `"kid": "key-2026-01"` — if it matches the cached key, validate with that key. If not found: refresh JWKS cache (key rotation in progress).

---

## JWT Token Design

```json
// Access Token (signed with RS256, 15-minute TTL)
{
  "header": { "alg": "RS256", "typ": "JWT", "kid": "key-2026-01" },
  "payload": {
    "sub": "user-uuid-123",           // user identifier
    "email": "khai@example.com",
    "roles": ["USER", "PREMIUM"],     // coarse-grained roles
    "permissions": ["order:read", "order:write"], // fine-grained permissions
    "tenant_id": "org-456",           // for multi-tenant systems
    "iss": "https://auth.myapp.com",  // issuer
    "aud": "api.myapp.com",           // audience (prevents token misuse)
    "iat": 1714392000,                // issued at
    "exp": 1714392900,                // expires at (iat + 900s = 15 min)
    "jti": "tok-uuid-789"             // JWT ID (for revocation)
  }
}

// Refresh Token (opaque random string, 30-day TTL)
// Stored in Redis: refresh:{token} → { user_id, device_id, session_id, expires_at }
// NOT a JWT: intentionally opaque (no embedded data that can be inspected)
// Rotated on each use: old token invalidated, new token issued
```

### Why Separate Access and Refresh Tokens?

```
Access Token:
  Short TTL (15 min) → if stolen, expires quickly
  Stateless validation → no Redis call for normal requests
  Embedded claims → services read user info without DB call

Refresh Token:
  Long TTL (30 days) → convenience (user doesn't re-login daily)
  Stored in Redis → can be revoked explicitly (logout)
  Opaque → no sensitive data exposed if decoded

Refresh Token Rotation (security property):
  Each refresh issues a NEW refresh token and invalidates the old one.
  If an attacker steals a refresh token and uses it:
  → Legitimate user's next refresh fails (token already rotated by attacker)
  → Legitimate user gets an "invalid refresh token" error
  → System detects: "this token was already rotated — possible theft"
  → Invalidate ALL sessions for this user → force re-login
  → Alert security team
  Without rotation: attacker can silently use the stolen token indefinitely
```

---

## Login Flow — Complete Implementation

```java
@Service
public class AuthService {

    public LoginResponse login(LoginRequest req) {
        // Step 1: Find user (constant-time to prevent user enumeration)
        User user = userRepo.findByEmail(req.getEmail())
            .orElseThrow(() -> new AuthException("invalid_credentials"));
            // NOTE: don't say "email not found" — reveals that email doesn't exist

        // Step 2: Check account status
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new AuthException("account_disabled");
        }

        // Step 3: Check lockout (brute force protection)
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            long retryAfter = Duration.between(Instant.now(), user.getLockedUntil()).getSeconds();
            throw new RateLimitException("account_locked", retryAfter);
        }

        // Step 4: Verify password (bcrypt — constant time comparison, prevents timing attacks)
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            // Increment failed attempt counter
            int attempts = user.getFailedAttempts() + 1;
            if (attempts >= 5) {
                // Exponential lockout: 15min, 30min, 1h, 2h, 4h
                Duration lockDuration = Duration.ofMinutes(15L * (1L << (attempts - 5)));
                user.setLockedUntil(Instant.now().plus(lockDuration));
                userRepo.save(user);
                throw new RateLimitException("account_locked", lockDuration.getSeconds());
            }
            user.setFailedAttempts(attempts);
            userRepo.save(user);
            throw new AuthException("invalid_credentials"); // Same message for wrong password
        }

        // Step 5: Reset failed attempts on success
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());

        // Step 6: Check MFA requirement
        if (user.isMfaEnabled()) {
            // Issue short-lived MFA challenge token (not full access token)
            String mfaChallengeToken = mfaService.createChallenge(user.getId());
            userRepo.save(user);
            return LoginResponse.mfaRequired(mfaChallengeToken);
        }

        // Step 7: Load roles and permissions
        Set<String> roles = roleRepo.findRoleNamesByUserId(user.getId());
        Set<String> permissions = permissionRepo.findPermissionsByRoles(roles);

        // Step 8: Generate tokens
        String jti = UUID.randomUUID().toString();
        String accessToken = jwtService.generateAccessToken(user, roles, permissions, jti);
        String refreshToken = refreshTokenService.create(user.getId(), req.getDeviceId());

        userRepo.save(user);
        return LoginResponse.success(accessToken, refreshToken, 900);
    }
}
```

---

## Password Security

### bcrypt — Why This Algorithm

```
bcrypt characteristics:
  Adaptive cost factor: bcrypt("password", cost=12) takes ~250ms
    → cost=10: ~65ms (too fast for modern GPUs, acceptable)
    → cost=12: ~250ms (reasonable for login, expensive for attacker)
    → cost=14: ~1000ms (very slow, might be frustrating for users)

Why bcrypt vs SHA-256:
  SHA-256("password") = constant time on GPU (billions/sec)
  bcrypt("password", cost=12) = 250ms — computationally expensive BY DESIGN
  → An attacker with 100 GPUs can try: SHA-256: 100B/sec, bcrypt: 400/sec
  → bcrypt makes brute force 250,000,000× more expensive

Why cost=12?
  Must take ~100ms minimum to be effective against GPU attacks
  Must not take > 500ms to be usable as a login API
  cost=12 on modern hardware ≈ 250ms → good balance
  Adjust cost every 2 years as hardware gets faster
```

```java
@Bean
public PasswordEncoder passwordEncoder() {
    // BCryptPasswordEncoder: automatically includes salt in the hash
    // Never store passwords with the same salt across users
    return new BCryptPasswordEncoder(12); // cost factor 12
}

// Registration:
String hash = passwordEncoder.encode(rawPassword);
user.setPasswordHash(hash); // example: $2a$12$9bWRYJhFCTKYM5RjXZ8KE.qzHBj6Cy8a3AEGqQS...
// Hash is self-contained: includes algorithm ($2a), cost ($12$), salt, and hash

// Login:
boolean matches = passwordEncoder.matches(rawPassword, storedHash);
// BCrypt.checkpw() is timing-safe: takes constant time regardless of where mismatch occurs
```

### HIBP Integration (Have I Been Pwned)

```java
public void checkPasswordNotPwned(String password) {
    // k-Anonymity API: don't send full password hash to HIBP
    String sha1Hash = sha1(password).toUpperCase();
    String prefix = sha1Hash.substring(0, 5);   // send only first 5 chars
    String suffix = sha1Hash.substring(5);       // keep remainder private

    // HIBP returns all hashes starting with prefix
    String response = httpClient.get("https://api.pwnedpasswords.com/range/" + prefix);

    boolean isPwned = Arrays.stream(response.split("\n"))
        .map(line -> line.split(":")[0])
        .anyMatch(h -> h.equalsIgnoreCase(suffix));

    if (isPwned) throw new PasswordException("password_compromised",
        "This password appears in known data breaches. Please choose a different one.");
}
```

---

## MFA: TOTP (Time-Based One-Time Password)

### How TOTP Works

```
Secret: 32-byte random value, base32-encoded for QR code
  → User scans QR with Google Authenticator / Authy

TOTP algorithm (RFC 6238):
  counter = floor(current_unix_time / 30)  ← time step: 30 seconds
  totp = HMAC-SHA1(secret, counter)         ← HMAC with secret and counter
  code = truncate(totp) % 10^6             ← 6-digit code

  The code changes every 30 seconds.
  Both server and app compute independently — must match.

Verification with clock skew tolerance:
  Accept codes for: counter-1, counter, counter+1  (±30 seconds)
  Prevents failure due to minor clock differences between app and server

Anti-replay: each TOTP code can only be used once within its 30-second window
  Store used codes in Redis: SET mfa:used:{secret_hash}:{counter} 1 EX 90
  If already in Redis → reject (replay attack)
```

```java
@Service
public class MfaService {

    public MfaSetupResponse setup(UUID userId) {
        // Generate secret
        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        String secret = Base32.encode(secretBytes);

        // Encrypt before storing (secret is sensitive)
        String encryptedSecret = aesEncrypt(secret);
        userRepo.updateMfaSecret(userId, encryptedSecret);

        // Generate QR code URI for Google Authenticator
        String qrUri = String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30",
            "MyApp", userEmail, secret, "MyApp");

        return new MfaSetupResponse(qrUri, secret); // show backup codes too
    }

    public boolean verify(UUID userId, String userCode) {
        String encryptedSecret = userRepo.getMfaSecret(userId);
        String secret = aesDecrypt(encryptedSecret);

        long counter = System.currentTimeMillis() / 1000 / 30;

        // Check counter-1, counter, counter+1 (clock skew tolerance)
        for (long c = counter - 1; c <= counter + 1; c++) {
            String expectedCode = generateTotp(secret, c);
            if (expectedCode.equals(userCode)) {
                // Anti-replay: ensure this specific code hasn't been used
                String replayKey = "mfa:used:" + sha256(secret) + ":" + c;
                boolean alreadyUsed = redis.exists(replayKey);
                if (!alreadyUsed) {
                    redis.setex(replayKey, 90, "1"); // TTL: 3 time steps
                    return true;
                }
            }
        }
        return false;
    }
}
```

---

## OAuth2 Authorization Code + PKCE Flow

### Why PKCE Exists

In a mobile/SPA context, the OAuth2 Authorization Code flow has a vulnerability: the authorization code (returned via redirect) can be intercepted by a malicious app on the same device that has registered the same custom URL scheme. PKCE (Proof Key for Code Exchange) adds a cryptographic challenge to prove that the entity redeeming the code is the same one that initiated the flow.

```
1. Client generates:
   code_verifier = random(32 bytes) → base64url
   code_challenge = BASE64URL(SHA256(code_verifier))

2. Authorization request (client → browser → auth server):
   GET /oauth/authorize
     ?client_id=my-app
     &redirect_uri=https://myapp.com/callback
     &response_type=code
     &code_challenge={code_challenge}
     &code_challenge_method=S256
     &state={random_nonce}       ← CSRF protection

3. Auth server stores: { code → code_challenge }

4. User logs in at auth server → redirect back:
   https://myapp.com/callback?code=AUTH_CODE_XYZ&state={nonce}

5. Token exchange (client → auth server):
   POST /oauth/token
     { grant_type: authorization_code,
       code: AUTH_CODE_XYZ,
       code_verifier: {original_random}  ← Only the real initiator has this
       redirect_uri: https://myapp.com/callback }

6. Auth server verifies: SHA256(code_verifier) == stored code_challenge?
   Yes → issue access token + refresh token

Without PKCE: Malicious app intercepts AUTH_CODE_XYZ → exchanges for tokens
With PKCE: Malicious app doesn't have code_verifier → exchange fails
```

---

## RBAC Data Model

```sql
-- Core RBAC tables
CREATE TABLE roles (
  id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(50) UNIQUE NOT NULL,  -- USER, ADMIN, MODERATOR, PREMIUM
  description TEXT
);

CREATE TABLE permissions (
  id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(100) UNIQUE NOT NULL, -- order:read, order:write, user:delete
  description TEXT,
  resource VARCHAR(50),              -- order, user, payment
  action   VARCHAR(20)               -- read, write, delete, admin
);

CREATE TABLE role_permissions (
  role_id       UUID REFERENCES roles(id) ON DELETE CASCADE,
  permission_id UUID REFERENCES permissions(id) ON DELETE CASCADE,
  PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
  user_id    UUID REFERENCES users(id) ON DELETE CASCADE,
  role_id    UUID REFERENCES roles(id),
  granted_by UUID REFERENCES users(id),
  granted_at TIMESTAMPTZ DEFAULT NOW(),
  expires_at TIMESTAMPTZ,  -- role can have expiry (e.g., trial PREMIUM expires after 30 days)
  PRIMARY KEY (user_id, role_id)
);

-- Efficient permission lookup query
CREATE VIEW user_permissions AS
SELECT ur.user_id, p.name as permission
FROM user_roles ur
JOIN role_permissions rp ON ur.role_id = rp.role_id
JOIN permissions p ON rp.permission_id = p.id
WHERE (ur.expires_at IS NULL OR ur.expires_at > NOW());

-- Cache in Redis: user:{userId}:permissions → SET of permission strings, TTL=5min
```

---

## API Design

```
POST /auth/register          { email, password, name }
POST /auth/login             { email, password }             → { access_token, refresh_token }
POST /auth/refresh           { refresh_token }               → { access_token, refresh_token }
POST /auth/logout            { refresh_token }               → revoke session
POST /auth/logout-all        { }                             → revoke all user sessions

GET  /auth/me                Bearer: {access_token}         → user profile + roles
PUT  /auth/me/password       { current_password, new_password }
POST /auth/forgot-password   { email }                      → send reset link
POST /auth/reset-password    { token, new_password }

POST /auth/mfa/setup         → { qr_code_uri, backup_codes }
POST /auth/mfa/verify        { code }                       → complete login after MFA
DELETE /auth/mfa             { code }                       → disable MFA

GET  /oauth2/login/google    → redirect to Google OAuth2
GET  /oauth2/callback/google → exchange code, issue tokens

GET  /.well-known/jwks.json  → public keys for token validation (no auth required)
```

---

## Tech Stack

- **Backend**: Java 17, Spring Boot, Spring Security 6
- **JWT**: JJWT (io.jsonwebtoken) or Nimbus JOSE + JWT
- **Password Hashing**: BCrypt (Spring Security PasswordEncoder, cost=12)
- **MFA/TOTP**: aerogear-otp-java or custom HMAC-SHA1 implementation
- **OAuth2**: Spring Security OAuth2 Resource Server + Client
- **Sessions (Redis)**: refresh tokens, MFA challenges, blocklist
- **Database**: PostgreSQL (users, roles, permissions — relational, ACID)
- **Secrets**: HashiCorp Vault (JWT private key, AES keys for MFA secrets)
- **Email**: AWS SES (verification, password reset, suspicious login alerts)
- **Rate Limiting**: Redis (per-IP, per-email login attempts)

---

## Interview Q&A

**Q1: Why is RS256 strongly preferred over HS256 for JWT signing in a microservices architecture?**

A: HS256 uses a shared secret — anyone who can verify a JWT can also forge one. In a microservices system, every service that validates JWTs needs the secret. If any single service is compromised (a common occurrence in complex systems), the attacker can forge JWTs with arbitrary claims (`"roles": ["ADMIN"]`), impersonating any user. RS256 uses asymmetric cryptography: only the Auth Service has the private key for signing. All other services have only the public key for verification. The public key can only verify — it cannot sign new tokens. A compromised Order Service gives an attacker the public key (useless for forgery) but not the private key. Key rotation also becomes straightforward with RS256: publish a new public key to the JWKS endpoint, no coordinated deployment of all services required.

---

**Q2: A user logs out. How do you invalidate their JWT access token before it expires in 15 minutes?**

A: Stateless JWTs cannot be revoked by design — that's the price of statelessness. Solutions in order of tradeoff: (1) **Accept the 15-minute window**: for normal logout, this is acceptable. Most users don't care if their token technically works for a few more minutes. (2) **Token blocklist in Redis**: on logout, `SADD jwt:blocklist {jti}` with TTL matching the token's expiry. Every request checks `SISMEMBER jwt:blocklist {jti}` — one Redis read per request. This gives < 1-second revocation propagation. Overhead: ~0.3ms per request. (3) **Shorten token TTL**: 5 minutes instead of 15. Smaller attack window, but more frequent refresh calls. (4) **Token version**: store `token_version` per user in Redis. JWT includes version claim. On logout, increment version. Validation fails if JWT version != current version. One Redis read per request, but simpler than a blocklist.

---

**Q3: What is refresh token rotation and why does it detect token theft?**

A: Without rotation: the refresh token is issued once and used many times. If stolen, an attacker uses it indefinitely and silently. With rotation: every time the refresh token is used to get a new access token, the old refresh token is immediately invalidated and a new one is issued. If an attacker steals the refresh token and uses it before the legitimate user's next refresh: the attacker gets a new token, the old one is invalidated. When the legitimate user next refreshes (using their copy of the now-invalidated token), the server detects a "refresh token reuse" — the token was already rotated by someone else. This is a definitive signal of theft. The server's response: invalidate ALL sessions for this user, force re-login on all devices, and notify the user. This converts a silent, ongoing attack into a detectable, bounded incident.

---

**Q4: How do you implement "remember me" functionality securely?**

A: "Remember me" means extending the session beyond the browser session. Implementation: (1) Issue a long-lived refresh token (30-90 days TTL instead of 24 hours). (2) Store in `HttpOnly, Secure, SameSite=Strict` cookie — not localStorage (prevents XSS theft). (3) The cookie is sent automatically with requests to the auth domain. (4) The access token (short-lived, 15 min) is stored in memory (not cookies, not localStorage). (5) On page load: check if access token is valid; if expired, use the cookie's refresh token to get a new access token. Security considerations: if the device is stolen, the attacker has access for up to 90 days (the refresh token TTL). Mitigate with device fingerprinting: if the "remember me" token is used from a different device, require re-authentication. Absolute session limit: force re-login every 90 days regardless, even with "remember me."

---

**Q5: How do you prevent SQL injection and other injection attacks in the auth service specifically?**

A: Auth service handles the most sensitive data (credentials, tokens) and is a prime target. SQL injection prevention: (1) **Parameterized queries always**: `findByEmail(String email)` via Spring Data JPA generates `WHERE email = ?` (parameterized) — never string concatenation. (2) **Input validation**: email format validation (regex), password length limits, reject null bytes. (3) **ORM layer**: JPA/Hibernate translates object operations to safe SQL — developers rarely write raw SQL. For raw SQL: `entityManager.createNativeQuery("SELECT * FROM users WHERE email = :email").setParameter("email", email)`. Other injection vectors in auth specifically: (4) **LDAP injection** (if using LDAP auth): use parameterized LDAP queries; escape special chars `* ( ) \\ NUL`. (5) **XML injection** (SAML assertions): use a well-vetted SAML library; never parse assertions with naive XML parser. (6) **Log injection**: user-supplied input (email addresses) logged in login attempts — escape newlines/null bytes to prevent log forging.

---

**Q6: How does the TOTP anti-replay protection work? Why is it necessary?**

A: A TOTP code is valid for a 30-second window (±1 step for clock skew = up to 90 seconds). Without anti-replay, an attacker who observes a user entering their 2FA code (shoulder surfing, malware) can reuse that code within the 30-second window. Anti-replay: when a valid code is accepted, immediately store it in Redis: `SET mfa:used:{user_id}:{time_step} 1 EX 90`. Before accepting any code, check this key. If the same code for the same time step is submitted again, reject it — it's a replay. The TTL of 90 seconds (3 time steps) ensures the key expires after the code is no longer valid anyway. The key is per-user to prevent one user's code from blocking another. This is a low-overhead mitigation: one Redis SET and one GET per MFA verification.

---

**Q7: How do you handle the case where the Auth Service itself is down? Can users still access the platform?**

A: This is the key architectural property enabled by RS256. Services validate JWTs using the public key cached in memory. If Auth Service is down: (1) **Existing sessions**: users with valid access tokens (< 15 min old) can continue to use the API — their tokens are validated locally by each service using the cached public key. No Auth Service call required. (2) **Token refresh**: users with expired access tokens try to refresh → Auth Service is down → refresh fails → users must re-login when Auth Service recovers. (3) **New logins**: blocked while Auth Service is down. (4) **Mitigation**: Auth Service is horizontally scaled (3+ instances), load-balanced, with health checks. K8s automatically restarts failed pods. Target: < 30 seconds downtime. The critical insight: most users (active sessions) see no impact. Only users whose access token expired (every 15 minutes) experience issues. This is a significant improvement over stateful sessions where Auth Service downtime = total platform lockout.

---

**Q8: How would you design the authentication system for a B2B multi-tenant SaaS platform?**

A: Multi-tenancy adds tenant isolation and SSO requirements: (1) **Tenant context in JWT**: add `tenant_id` claim. Services use this to scope all data access: `SELECT * FROM orders WHERE tenant_id = ?`. (2) **Tenant-specific OAuth2**: each enterprise tenant configures their own SAML/OAuth2 IdP (e.g., Okta, Azure AD). When a user's email domain matches a configured enterprise IdP, redirect to that IdP for authentication instead of showing the username/password form — this is SP-initiated SSO. (3) **Role mapping**: enterprise IdP returns group membership (`CN=Admins`); our system maps this to our roles (`ADMIN`). (4) **Tenant isolation**: refresh tokens and sessions are scoped to `(user_id, tenant_id)`. A user with access to two tenants has two separate session contexts. (5) **Tenant-specific password policies**: enterprise tenants may enforce stricter password requirements (min 16 chars, rotation every 90 days) via per-tenant config stored in DB and enforced at registration/password change.
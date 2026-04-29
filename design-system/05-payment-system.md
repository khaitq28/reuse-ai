# Payment System — Deep Design Guide

## Problem Statement

Design a payment processing system that handles money transfers, card charges, refunds, and
maintains accurate financial records — similar to Stripe or PayPal internals. The system must
be correct above all else: it is better to be unavailable than to process a double charge or
lose money.

---

## Why This Problem Matters

Payment systems sit at the intersection of almost every hard distributed systems problem:

- **Correctness over availability**: Most systems can tolerate eventual consistency. Payment
  systems cannot. A brief "split brain" in a chat system means a few messages are delayed.
  A split brain in a payment system means money is debited twice or a charge is both
  "succeeded" and "failed" simultaneously. The stakes are legally and financially consequential.
- **External system integration**: Unlike pure internal systems, payments require coordination
  with external payment gateways (Stripe, Adyen) that have their own failure modes, timeouts,
  and eventual consistency. You cannot rollback a charge on Stripe with a database transaction.
  This is the core challenge the Saga pattern solves.
- **Regulatory requirements**: PCI-DSS, GDPR, and financial audit regulations impose strict
  constraints on data storage, access control, and retention that must be designed in from
  the start — not bolted on later.
- **Idempotency is non-negotiable**: A network timeout on a payment request can leave the
  state ambiguous. Did the charge happen or not? Without idempotency keys, the "safe" retry
  causes a double charge.

**What interviewers are testing**: Whether you understand why double-entry bookkeeping exists and
how it prevents financial inconsistencies, whether you can design for the two-phase nature of
payments (authorization + capture), whether you grasp the Saga pattern's purpose and its
compensating transaction mechanics, and whether you think about reconciliation as a first-class
concern rather than an afterthought.

---

## Key Insight Before Diving In

**The fundamental insight: a payment is not a single operation — it is a distributed transaction
across three systems (your database, the payment gateway, and the bank network), and distributed
transactions cannot be made atomic.**

This is why the Saga pattern replaces a single ACID transaction: each step is committed locally,
and if a later step fails, you run compensating transactions to undo the earlier steps. Unlike
a database rollback, compensating transactions are new forward operations (a refund, not an
undo). This means the system must be designed to handle partial states: a charge was made at
the gateway but the local database failed to record it. Idempotency keys handle this:
re-sending the same payment request checks the idempotency store first and returns the
previously-computed result without re-charging.

The double-entry ledger is the second key insight: every amount that leaves one account must
enter another. The mathematical invariant (sum of all entries = 0) is a continuous integrity
check that catches bugs that unit tests cannot.

---

## Requirements

### Functional
- Process payments (card via tokenization, bank transfer)
- Refund full or partial amounts
- Idempotent operations (no double charges on client retry)
- Transaction history per user and merchant
- Multi-currency support with exchange rate handling
- Webhook notifications to merchants on payment state changes
- Payment authorization and separate capture (hotel/car rental patterns)
- Payout to merchant bank accounts (separate from payment ingestion)

### Non-Functional
- Exactly-once payment processing (idempotency ensures no double charge)
- ACID transactions for ledger operations — money must never be lost or duplicated
- High availability 99.99% (4 minutes downtime per month)
- Full audit trail for every state change (immutable, tamper-evident)
- PCI-DSS compliant — raw card data never touches application servers
- P99 latency for payment API: < 3 seconds (mostly gateway-bound)
- RPO (Recovery Point Objective): 0 — zero data loss on failure

---

## Capacity Estimation

```
Volume: 1M transactions/day → ~12 TPS average, 100 TPS peak (holiday sale)
Each transaction record:   ~2KB
Ledger entries:            2 per transaction (debit + credit) = 2M entries/day
Webhooks:                  ~3M/day (multiple events per payment)
5-year retention:          1M/day × 365 × 5 × 2KB ≈ 3.5TB (well within PostgreSQL at scale)
Gateway API calls:          ~2 per payment (authorize + capture) = 2M calls/day

Read-heavy pattern:
  Merchants check dashboard: high read QPS on transaction history
  Read replicas for reporting, primary for writes
```

---

## High-Level Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                    Client / Merchant SDK                           │
│  (Stripe.js / hosted fields — card data goes directly to gateway) │
└─────────────────────────┬──────────────────────────────────────────┘
                          │ HTTPS (payment token, not raw card data)
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Payment API (Edge Layer)                        │
│  - TLS termination, request validation                              │
│  - Idempotency key extraction                                       │
│  - Authentication (merchant API key / OAuth2)                       │
│  - Rate limiting (per merchant)                                     │
└─────────────────────────┬───────────────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────────────┐
│                      Payment Service                                 │
│                                                                     │
│  1. Idempotency check (Redis + DB)                                  │
│  2. Input validation (amount > 0, valid currency, valid token)      │
│  3. Risk/Fraud check (async ML scoring)                             │
│  4. Payment state machine enforcement                               │
│  5. Saga orchestration                                              │
└────┬─────────────────────────┬───────────────────────────────────────┘
     │                         │
     ▼                         ▼
┌────────────┐         ┌────────────────────┐
│  Ledger    │         │  Payment Gateway   │
│  Service   │         │  (Stripe / Adyen)  │
│            │         │                    │
│  PostgreSQL│         │  authorize()       │
│  (ACID,    │         │  capture()         │
│  double-   │         │  refund()          │
│  entry)    │         │  retrieve()        │
└────┬───────┘         └────────┬───────────┘
     │                         │
     ▼                         ▼
┌──────────────────────────────────────────┐
│              Kafka Event Bus             │
│  Topics: payment.events, ledger.events  │
└──────────┬───────────────────┬───────────┘
           │                   │
    ┌──────▼──────┐    ┌──────▼──────┐
    │  Webhook    │    │  Analytics  │
    │  Service    │    │  Service    │
    └─────────────┘    └─────────────┘
```

---

## Core Concept: Double-Entry Ledger

### Why Double-Entry?

Single-entry accounting (just recording transactions) cannot detect errors. Double-entry
accounting, invented by Italian merchants in the 13th century, provides a mathematical
invariant: **every debit must have a corresponding credit of equal amount**. The sum of all
entries in the ledger is always exactly zero.

Consider a payment of $100 from User A to Merchant B:

```
Entry 1: DEBIT  | User A account     | -$100.00  (money leaves User A)
Entry 2: CREDIT | Merchant B account | +$100.00  (money enters Merchant B)

SUM = -100.00 + 100.00 = 0.00  ✓ Invariant holds
```

If a bug causes the CREDIT to be recorded as $99.00:
```
Entry 1: DEBIT  | User A account     | -$100.00
Entry 2: CREDIT | Merchant B account |  +$99.00

SUM = -100.00 + 99.00 = -1.00  ✗ Invariant violated — alert immediately
```

This simple check catches entire classes of bugs that are otherwise invisible. Systems without
double-entry can drift silently for months before someone notices accounts don't balance.

```sql
-- This query runs continuously (materialized view + alert)
-- If this returns anything other than 0, there is a financial bug
SELECT SUM(amount) AS ledger_sum
FROM ledger_entries
WHERE created_at >= NOW() - INTERVAL '24 hours';
-- Expected: 0.00 (or within rounding epsilon for FX transactions)
```

### Float is Forbidden in Financial Systems

```java
// WRONG — floating point cannot represent decimal fractions exactly
double amount = 0.1 + 0.2; // = 0.30000000000000004 in IEEE 754

// CORRECT — use integer arithmetic (cents/smallest currency unit)
// OR use BigDecimal with explicit scale
long amountCents = 10 + 20; // = 30 (exact)

BigDecimal amount = new BigDecimal("0.10")
    .add(new BigDecimal("0.20")); // = 0.30 (exact)

// In the database: DECIMAL(19, 4) — 19 digits total, 4 decimal places
// Supports amounts up to $999,999,999,999,999.9999 with 4 decimal precision
// 4 decimal places required for currencies like Japanese Yen fractions in FX
```

---

## Data Model

```sql
-- ==========================================
-- PAYMENTS TABLE
-- Core payment record — one per payment attempt
-- ==========================================
CREATE TABLE payments (
  id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  idempotency_key   VARCHAR(255)  UNIQUE NOT NULL,  -- client-provided, prevents double charge
  merchant_id       UUID          NOT NULL,
  payer_id          UUID,                           -- null for anonymous payments
  payee_id          UUID          NOT NULL,         -- merchant account
  amount            DECIMAL(19,4) NOT NULL CHECK (amount > 0),
  currency          CHAR(3)       NOT NULL,         -- ISO 4217: USD, EUR, GBP
  status            VARCHAR(30)   NOT NULL DEFAULT 'INITIATED',
  -- Valid statuses: INITIATED → RISK_REVIEW → AUTHORIZED → CAPTURED → REFUNDED
  --                              → DECLINED (risk) or FAILED (gateway)
  payment_method    JSONB         NOT NULL,         -- {type: CARD, last4: 4242, brand: VISA}
  -- Never store raw PAN — only tokenized reference and display info
  gateway_name      VARCHAR(50),                   -- stripe, adyen, braintree
  gateway_ref       VARCHAR(255),                  -- gateway's charge ID (ch_xxx for Stripe)
  gateway_response  JSONB,                         -- full gateway response for audit
  risk_score        DECIMAL(5,4),                  -- 0.0000 to 1.0000 from fraud model
  risk_decision     VARCHAR(20),                   -- APPROVE, REVIEW, DECLINE
  metadata          JSONB,                         -- merchant-provided key-value pairs
  failure_code      VARCHAR(100),                  -- e.g., insufficient_funds, card_declined
  failure_message   TEXT,
  authorized_at     TIMESTAMPTZ,
  captured_at       TIMESTAMPTZ,
  refunded_at       TIMESTAMPTZ,
  created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ==========================================
-- LEDGER ENTRIES TABLE
-- Immutable double-entry ledger
-- Append-only: NO UPDATE, NO DELETE ever
-- ==========================================
CREATE TABLE ledger_entries (
  id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id    UUID          NOT NULL REFERENCES payments(id),
  account_id    UUID          NOT NULL,             -- references accounts table
  amount        DECIMAL(19,4) NOT NULL,             -- positive = credit, negative = debit
  currency      CHAR(3)       NOT NULL,
  entry_type    VARCHAR(20)   NOT NULL,             -- DEBIT, CREDIT, FEE, FX_ADJUSTMENT
  description   TEXT,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
  -- NO updated_at — entries are immutable
);

-- Prevent any UPDATE or DELETE on ledger_entries (enforced at DB level)
-- In PostgreSQL, this is done via a rule or trigger:
CREATE RULE no_update_ledger AS ON UPDATE TO ledger_entries DO INSTEAD NOTHING;
CREATE RULE no_delete_ledger AS ON DELETE TO ledger_entries DO INSTEAD NOTHING;

-- ==========================================
-- IDEMPOTENCY KEYS TABLE
-- Prevents double charges on client retry
-- ==========================================
CREATE TABLE idempotency_keys (
  key           VARCHAR(255)  PRIMARY KEY,
  merchant_id   UUID          NOT NULL,
  payment_id    UUID,                              -- null while payment is processing
  request_hash  VARCHAR(64)   NOT NULL,            -- hash of request body (detect body changes)
  response      JSONB,                             -- cached response (null while processing)
  status        VARCHAR(20)   NOT NULL DEFAULT 'PROCESSING',
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  expires_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

-- ==========================================
-- PAYMENT STATE AUDIT LOG
-- Immutable record of every state transition
-- Required for regulatory compliance (PCI-DSS audit trail)
-- ==========================================
CREATE TABLE payment_audit_log (
  id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id    UUID          NOT NULL REFERENCES payments(id),
  from_status   VARCHAR(30),
  to_status     VARCHAR(30)   NOT NULL,
  actor         VARCHAR(255)  NOT NULL,            -- system, user_id, or admin_id
  reason        TEXT,
  metadata      JSONB,                             -- gateway response, etc.
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ==========================================
-- ACCOUNTS TABLE
-- Financial accounts (user wallets, merchant accounts, platform escrow)
-- ==========================================
CREATE TABLE accounts (
  id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id        UUID          NOT NULL,           -- user_id or merchant_id
  account_type    VARCHAR(30)   NOT NULL,           -- USER_WALLET, MERCHANT, PLATFORM_FEE, ESCROW
  currency        CHAR(3)       NOT NULL,
  -- Balance is derived from ledger_entries — never stored directly
  -- This prevents balance drift from direct UPDATE operations
  is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Balance view — always computed from ledger (never stored)
CREATE VIEW account_balances AS
SELECT
  account_id,
  currency,
  SUM(amount) AS balance        -- sum of credits - sum of debits
FROM ledger_entries
GROUP BY account_id, currency;

-- Indexes
CREATE INDEX idx_payments_merchant_status ON payments(merchant_id, status, created_at DESC);
CREATE INDEX idx_payments_payer          ON payments(payer_id, created_at DESC);
CREATE INDEX idx_payments_gateway_ref    ON payments(gateway_name, gateway_ref);
CREATE INDEX idx_ledger_payment_id       ON ledger_entries(payment_id);
CREATE INDEX idx_ledger_account_id       ON ledger_entries(account_id, created_at DESC);
CREATE INDEX idx_idempotency_expires     ON idempotency_keys(expires_at);
```

---

## Idempotency Design

### The Problem Without Idempotency

```
Client → POST /payments (charge $100)
Gateway processes charge → SUCCESS
Network timeout before response reaches client
Client retries POST /payments (same request)
Gateway charges AGAIN → $200 total charged ← DISASTER
```

### Idempotency Key Protocol

```
Client generates a UUID before sending the request.
Client includes it as a header: Idempotency-Key: client-uuid-abc123
Client ALWAYS uses this same key when retrying the same payment attempt.
```

```java
@Service
public class IdempotencyService {

    @Transactional
    public IdempotencyResult checkOrCreate(String idempotencyKey, String merchantId,
                                           String requestBodyHash) {
        // 1. Try to find existing record
        Optional<IdempotencyRecord> existing = repo.findByKeyAndMerchant(idempotencyKey, merchantId);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            // Detect request body mismatch — same key, different body = client bug
            // This is a safety check: the idempotency key is tied to ONE specific request
            if (!record.getRequestHash().equals(requestBodyHash)) {
                throw new IdempotencyConflictException(
                    "Idempotency key " + idempotencyKey + " was used with different request body"
                );
            }

            // If still processing (race condition — two parallel retries)
            // Return 409 Conflict: "payment is being processed, try again in a moment"
            if (record.getStatus() == PROCESSING) {
                return IdempotencyResult.conflict();
            }

            // Return cached response — no payment processing
            return IdempotencyResult.cached(record.getResponse());
        }

        // 2. New request — create idempotency lock
        // Use INSERT with ON CONFLICT to handle concurrent retries atomically
        // If two threads race here, only one wins — the other gets the existing record
        try {
            IdempotencyRecord newRecord = new IdempotencyRecord(
                idempotencyKey, merchantId, requestBodyHash, PROCESSING
            );
            repo.insert(newRecord); // will throw on duplicate key if race occurred
            return IdempotencyResult.proceed(newRecord.getId());

        } catch (DuplicateKeyException e) {
            // Race condition — another thread created it first
            // Retry the SELECT to get the existing record
            return checkOrCreate(idempotencyKey, merchantId, requestBodyHash);
        }
    }

    public void complete(String idempotencyKey, Object response) {
        // Called after payment is fully processed
        // Stores the response so future retries return it immediately
        repo.updateResponse(idempotencyKey, response, COMPLETED);
    }
}
```

### Idempotency Key Design Decisions

1. **Client-generated, not server-generated**: The key must be chosen before the request is
   sent so retries use the same key. Server-generated keys require a separate API call to
   reserve a key first, adding latency.
2. **Tied to a specific request body**: Using the same key with a different amount should fail
   loudly. Hash the request body and compare — a mismatch indicates a client programming error.
3. **TTL of 24 hours**: After 24 hours, the idempotency key expires. The client must generate
   a new key for a new payment attempt. This prevents the idempotency store from growing unboundedly.
4. **Scope by merchant**: Key `abc123` for Merchant A and `abc123` for Merchant B are different
   payments. Always scope keys to the merchant/user who generated them.

---

## Payment State Machine

### States and Transitions

```
                    ┌─────────────┐
                    │  INITIATED  │  ← payment created, validation passed
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │ RISK_REVIEW │  ← fraud score > threshold, manual review
                    └──┬──────┬───┘
               approve │      │ decline
                    ┌──▼──┐  ┌▼──────┐
                    │AUTH-│  │DECLINED│  ← terminal state
                    │ORIZ-│  └────────┘
                    │ING  │
                    └──┬──┘
                       │ gateway authorize()
               ┌───────┴───────┐
               │               │
        ┌──────▼─────┐  ┌──────▼─────┐
        │ AUTHORIZED │  │   FAILED   │  ← gateway decline (insufficient funds, etc.)
        └──────┬─────┘  └────────────┘
               │
               │ capture() (can be delayed for auth/capture flow)
        ┌──────▼─────┐
        │  CAPTURED  │  ← money transferred
        └──────┬─────┘
               │
        ┌──────▼────────────┐
        │  REFUND_PENDING   │  ← refund initiated
        └──────┬────────────┘
               │
        ┌──────▼──────┐
        │  REFUNDED   │  ← terminal state (full or partial)
        └─────────────┘
```

### DB-Enforced State Transitions

Allowing invalid state transitions at the application layer causes subtle bugs. The database
enforces valid transitions:

```sql
-- Only allow valid transitions
-- The WHERE clause on status is critical — 0 rows updated = invalid transition
UPDATE payments
SET status = 'CAPTURED',
    captured_at = NOW(),
    updated_at = NOW()
WHERE id = $1
  AND status = 'AUTHORIZED';   -- can only capture an authorized payment

-- Check rows affected:
-- 1 = success (transition was valid and happened)
-- 0 = failure (payment was in wrong state — concurrent modification or bug)
```

```java
@Transactional
public void capturePayment(UUID paymentId) {
    int rowsUpdated = paymentRepo.transitionStatus(paymentId, AUTHORIZED, CAPTURED);

    if (rowsUpdated == 0) {
        // Two possibilities:
        // 1. Payment is not in AUTHORIZED state (already captured, cancelled, etc.)
        // 2. Payment doesn't exist
        // Query to distinguish:
        Payment payment = paymentRepo.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        throw new InvalidStateTransitionException(
            "Cannot capture payment in state: " + payment.getStatus()
        );
    }
    // Record audit log entry for this transition
    auditLog.record(paymentId, AUTHORIZED, CAPTURED, "system", null);
}
```

---

## Saga Pattern for Distributed Payment

### Why Sagas (Not Distributed Transactions)?

A traditional 2-Phase Commit (2PC) distributed transaction requires all participating systems
to "hold" their changes until a coordinator tells them to commit. This requires the payment
gateway to participate in the protocol — which external systems will never do. Even for
internal services, 2PC has availability problems: if the coordinator crashes during the
"prepared" phase, all participants are blocked until it recovers.

The Saga pattern uses a sequence of local transactions with compensating transactions for
rollback. Each step commits immediately; if a later step fails, the system runs compensation
transactions (new operations, not database rollbacks) to undo the earlier steps.

### Payment Saga Steps

```
PAYMENT SAGA (Choreography-based):

Step 1: Validate & Risk Score
  Action: Call fraud scoring service
  Store: payment.risk_score, payment.status = RISK_REVIEW (if high risk) or AUTHORIZING
  Compensation: None (read-only)

Step 2: Reserve Funds (Authorization)
  Action: gateway.authorize(amount, token)
  Store: payment.gateway_ref, payment.status = AUTHORIZED
  Compensation: gateway.void(gateway_ref)  ← cancel the authorization

Step 3: Record Ledger (Debit)
  Action: INSERT INTO ledger_entries (debit user account)
  Store: ledger_entry with type=DEBIT
  Compensation: INSERT INTO ledger_entries (credit user account) ← compensating credit

Step 4: Capture Funds
  Action: gateway.capture(gateway_ref)
  Store: payment.status = CAPTURED, payment.captured_at
  Compensation: gateway.refund(gateway_ref) ← initiate refund

Step 5: Credit Merchant Ledger
  Action: INSERT INTO ledger_entries (credit merchant account)
  Store: ledger_entry with type=CREDIT
  Compensation: INSERT INTO ledger_entries (debit merchant account)

Step 6: Publish Event
  Action: Kafka.publish("payment.captured", event)
  Compensation: Kafka.publish("payment.reversed", event)
```

```java
@Service
public class PaymentSaga {

    // Each step's result is persisted before moving to the next step
    // This allows recovery after crashes: resume from last persisted step
    public PaymentResult execute(PaymentRequest request) {

        // STEP 1: Risk assessment
        RiskResult risk = riskService.score(request);
        if (risk.isDeclined()) {
            updateStatus(request.getPaymentId(), DECLINED);
            return PaymentResult.declined(risk.getReason());
        }

        // STEP 2: Gateway authorization
        GatewayAuthResult authResult;
        try {
            authResult = gateway.authorize(request.getToken(), request.getAmount());
            updatePayment(request.getPaymentId(), AUTHORIZED, authResult.getGatewayRef());

        } catch (GatewayException e) {
            updateStatus(request.getPaymentId(), FAILED, e.getCode());
            return PaymentResult.failed(e.getCode());
        }

        // STEP 3: Debit ledger
        try {
            ledgerService.debit(request.getPayerId(), request.getAmount(), request.getPaymentId());

        } catch (Exception e) {
            // Ledger write failed — must void the gateway authorization
            // This is the compensating transaction for Step 2
            log.error("Ledger debit failed after gateway auth. Running compensation.", e);
            compensateGatewayAuthorization(authResult.getGatewayRef(), request.getPaymentId());
            return PaymentResult.failed("LEDGER_ERROR");
        }

        // STEP 4: Capture
        try {
            gateway.capture(authResult.getGatewayRef());
            updateStatus(request.getPaymentId(), CAPTURED);

        } catch (GatewayException e) {
            // Capture failed — compensate ledger debit AND void authorization
            ledgerService.reverseDebit(request.getPayerId(), request.getAmount(), request.getPaymentId());
            compensateGatewayAuthorization(authResult.getGatewayRef(), request.getPaymentId());
            return PaymentResult.failed(e.getCode());
        }

        // STEP 5: Credit merchant
        ledgerService.credit(request.getPayeeId(), request.getAmount(), request.getPaymentId());

        // STEP 6: Publish event for webhooks, analytics
        eventBus.publish(new PaymentCapturedEvent(request.getPaymentId()));

        return PaymentResult.success(request.getPaymentId());
    }

    private void compensateGatewayAuthorization(String gatewayRef, UUID paymentId) {
        try {
            gateway.voidAuthorization(gatewayRef);
            updateStatus(paymentId, FAILED, "COMPENSATED");
        } catch (GatewayException e) {
            // Compensation failed! This is now a "stuck" payment requiring manual intervention
            // Alert ops immediately — this is a financial inconsistency
            alertOps.critical("Gateway void failed for paymentId=" + paymentId +
                ", gatewayRef=" + gatewayRef + ". Manual intervention required.");
        }
    }
}
```

---

## PCI-DSS Compliance

### Why Raw Card Data Must Never Touch Your Servers

PCI-DSS (Payment Card Industry Data Security Standard) is a contractual and regulatory
requirement enforced by card brands (Visa, Mastercard). Storing, processing, or transmitting
raw Primary Account Numbers (PANs — the 16-digit card number) subjects your entire
infrastructure to Level 1 PCI audit: quarterly network scans, annual penetration testing,
and strict access controls.

The solution: never let raw card data enter your network. Use hosted payment fields:

```
WRONG (PCI scope includes your servers):
  User types card → your frontend → your backend → payment gateway

CORRECT (PCI scope limited to gateway's servers):
  User types card → gateway's iframe/JS SDK → gateway tokenizes → returns token to your server
  Your server sends token to gateway → gateway charges the card
  Token: tok_visa_4242 (useless to attackers — tied to a single merchant + expiry)
```

```javascript
// Frontend: Stripe.js — card fields are rendered in Stripe's iframe
// Your server NEVER receives the card number
const stripe = Stripe('pk_live_xxx');
const elements = stripe.elements();
const cardElement = elements.create('card'); // Stripe renders the input field
cardElement.mount('#card-element');

// On form submit:
const { paymentMethod, error } = await stripe.createPaymentMethod({
  type: 'card',
  card: cardElement,
});
// paymentMethod.id = "pm_xxx" — send this to YOUR backend, not the card number
```

### What PCI-DSS Requires (Simplified)

| Requirement | Implementation |
|---|---|
| Never store CVV after authorization | Tokenization — CVV is used once at authorization, never stored |
| Encrypt card data in transit | TLS 1.3 only — disable TLS 1.0/1.1 and weak ciphers |
| Encrypt card data at rest | AES-256 for any stored payment data; KMS for key management |
| Restrict access to cardholder data | RBAC with least privilege; only gateway engineers see raw data |
| Log all access to payment data | Immutable audit log; forward to SIEM (Splunk, etc.) |
| Penetration testing annually | Annual PCI audit by Qualified Security Assessor (QSA) |

---

## Reconciliation

### Why Reconciliation Is Critical

Your database and the payment gateway can diverge due to:
1. Gateway processed a charge but your server crashed before recording it
2. A webhook was missed (gateway notified you of a refund but the webhook failed)
3. Currency conversion discrepancies
4. Gateway-side refunds initiated by the gateway (chargeback processing)

Reconciliation detects these discrepancies before they become customer complaints or financial
losses.

```java
@Service
public class ReconciliationJob {

    // Runs nightly at 02:00 UTC
    @Scheduled(cron = "0 0 2 * * *")
    public void reconcilePayments() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        // Fetch all payments from our DB that were processed yesterday
        List<Payment> localPayments = paymentRepo.findByDateRange(
            yesterday.atStartOfDay(), yesterday.plusDays(1).atStartOfDay()
        );

        // Fetch the same date's transactions from gateway
        // Gateway provides a settlement file or bulk query API
        List<GatewayTransaction> gatewayTransactions = gateway.listTransactions(yesterday);

        // Build lookup map for O(1) matching
        Map<String, GatewayTransaction> gatewayMap = gatewayTransactions.stream()
            .collect(toMap(GatewayTransaction::getGatewayRef, identity()));

        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();

        for (Payment local : localPayments) {
            GatewayTransaction gatewayTxn = gatewayMap.get(local.getGatewayRef());

            if (gatewayTxn == null) {
                // In our DB but not in gateway — ghost transaction
                discrepancies.add(new ReconciliationDiscrepancy(
                    local.getId(), "MISSING_IN_GATEWAY",
                    "Payment in our DB but not found in gateway settlement"
                ));

            } else if (!local.getAmount().equals(gatewayTxn.getAmount())) {
                // Amount mismatch — currency conversion error or data corruption
                discrepancies.add(new ReconciliationDiscrepancy(
                    local.getId(), "AMOUNT_MISMATCH",
                    "Local: " + local.getAmount() + " Gateway: " + gatewayTxn.getAmount()
                ));

            } else if (!local.getStatus().equals(mapGatewayStatus(gatewayTxn.getStatus()))) {
                // Status mismatch — missed webhook (e.g., gateway shows REFUNDED but we show CAPTURED)
                discrepancies.add(new ReconciliationDiscrepancy(
                    local.getId(), "STATUS_MISMATCH",
                    "Local: " + local.getStatus() + " Gateway: " + gatewayTxn.getStatus()
                ));
            }
        }

        // Check for gateway transactions not in our DB (orphan charges)
        for (GatewayTransaction gatewayTxn : gatewayTransactions) {
            boolean existsLocally = localPayments.stream()
                .anyMatch(p -> p.getGatewayRef().equals(gatewayTxn.getGatewayRef()));
            if (!existsLocally) {
                discrepancies.add(new ReconciliationDiscrepancy(
                    null, "ORPHAN_GATEWAY_CHARGE",
                    "Gateway ref: " + gatewayTxn.getGatewayRef() + " not found locally"
                ));
            }
        }

        if (!discrepancies.isEmpty()) {
            // Log, alert ops, create investigation tickets
            reconciliationRepo.saveAll(discrepancies);
            alertOps.warn("Reconciliation found " + discrepancies.size() + " discrepancies for " + yesterday);
        }

        log.info("Reconciliation complete for {}. Checked {} payments, found {} discrepancies",
            yesterday, localPayments.size(), discrepancies.size());
    }
}
```

---

## Fraud Detection Basics

Fraud detection sits between validation and gateway authorization:

```java
@Service
public class FraudScoringService {

    // Returns a score 0.0 (safe) to 1.0 (definitely fraud)
    public RiskAssessment score(PaymentRequest request) {
        List<RiskSignal> signals = new ArrayList<>();

        // Signal 1: Velocity check — too many payments in short time window
        int paymentsLast1Hour = paymentRepo.countByPayerSince(request.getPayerId(), 1, HOURS);
        if (paymentsLast1Hour > 10) {
            signals.add(RiskSignal.HIGH("velocity_check", "10+ payments in 1 hour"));
        }

        // Signal 2: Unusual amount — far outside historical average
        BigDecimal avgAmount = paymentRepo.getAverageAmountByPayer(request.getPayerId());
        if (request.getAmount().compareTo(avgAmount.multiply(new BigDecimal("10"))) > 0) {
            signals.add(RiskSignal.MEDIUM("unusual_amount", "10x above historical average"));
        }

        // Signal 3: New card on high-value transaction
        boolean isNewCard = !paymentRepo.hasSuccessfulPaymentWithToken(
            request.getPayerId(), request.getPaymentMethodToken()
        );
        if (isNewCard && request.getAmount().compareTo(new BigDecimal("500")) > 0) {
            signals.add(RiskSignal.MEDIUM("new_card_high_value", "First use of card, amount > $500"));
        }

        // Signal 4: IP/device mismatch with user's historical location
        // Signal 5: Card BIN country mismatch with shipping address
        // Signal 6: ML model score (trained on historical fraud patterns)

        double compositeScore = calculateCompositeScore(signals);

        if (compositeScore > 0.8) {
            return RiskAssessment.declined("FRAUD_SCORE_TOO_HIGH");
        } else if (compositeScore > 0.5) {
            return RiskAssessment.review(compositeScore); // human review queue
        } else {
            return RiskAssessment.approved(compositeScore);
        }
    }
}
```

---

## Webhook Service

Merchants need real-time notification of payment state changes to fulfill orders:

```java
// Webhook delivery with at-least-once guarantee and retry
@Service
public class WebhookService {

    // Called when payment state changes
    public void deliverWebhook(UUID paymentId, String eventType) {
        Payment payment = paymentRepo.findById(paymentId).orElseThrow();
        Merchant merchant = merchantRepo.findById(payment.getMerchantId()).orElseThrow();

        WebhookPayload payload = new WebhookPayload(
            eventType,
            payment.getId(),
            payment.getStatus(),
            payment.getAmount(),
            Instant.now()
        );

        // Sign the payload — merchant verifies this signature to authenticate the webhook
        // Prevents attackers from sending fake webhook events to the merchant's endpoint
        String signature = webhookSigner.sign(payload, merchant.getWebhookSecret());

        try {
            HttpResponse response = httpClient.post(merchant.getWebhookUrl())
                .header("X-Webhook-Signature", signature)
                .header("X-Event-Type", eventType)
                .body(objectMapper.writeValueAsString(payload))
                .timeout(Duration.ofSeconds(5))
                .execute();

            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                webhookLog.recordSuccess(paymentId, eventType, merchant.getId());
            } else {
                // Non-2xx response — schedule retry
                scheduleRetry(paymentId, eventType, merchant.getId(), 1);
            }

        } catch (TimeoutException | IOException e) {
            // Network failure — schedule retry
            scheduleRetry(paymentId, eventType, merchant.getId(), 1);
        }
    }

    private void scheduleRetry(UUID paymentId, String eventType, UUID merchantId, int attempt) {
        if (attempt > 5) {
            webhookLog.recordPermanentFailure(paymentId, eventType, merchantId);
            return; // give up after 5 attempts — merchant can query API instead
        }
        // Exponential backoff: 1min, 5min, 30min, 2h, 8h
        long delayMinutes = (long) Math.pow(5, attempt - 1);
        taskScheduler.schedule(
            () -> deliverWebhookAttempt(paymentId, eventType, merchantId, attempt + 1),
            Instant.now().plusSeconds(delayMinutes * 60)
        );
    }
}
```

---

## Security and Compliance

| Concern | Solution | Rationale |
|---|---|---|
| Raw card data | Never stored — gateway tokenization only | PCI-DSS scope reduction |
| Card display | Store only last4 + brand for display | Last 4 digits are not sensitive PAN |
| API authentication | HMAC-signed API keys per merchant | Revocable without password reset |
| User authentication | JWT with short expiry (15 min) | Limits token theft window |
| Encryption in transit | TLS 1.3, HSTS | Prevents MITM attacks |
| Encryption at rest | AES-256 via AWS KMS | HSM-backed key management |
| Data access | RBAC — payer sees own txns, merchant sees their txns | Least privilege |
| Audit trail | Immutable `payment_audit_log`, append-only `ledger_entries` | Regulatory requirement |
| Secrets | Never in code or environment vars — use AWS Secrets Manager | Prevents credential leaks |
| Rate limiting | Per-merchant rate limits at API gateway | Prevents brute force on payment endpoints |

---

## Tech Stack

| Component | Technology | Rationale |
|---|---|---|
| Backend | Java 17 + Spring Boot | Strong typing for financial logic; mature libraries |
| Database | PostgreSQL 15 | ACID, advisory locks, partitioning, excellent DECIMAL support |
| Gateway | Stripe (primary), Adyen (fallback) | Stripe for developer experience; Adyen for enterprise |
| Event bus | Apache Kafka | Durable, replayable events for webhooks and analytics |
| Cache | Redis 7 | Idempotency key fast-path lookup; rate limiting counters |
| Fraud scoring | Python ML service + Redis feature store | Separate deployment; low-latency feature lookup |
| Secrets | AWS Secrets Manager | Automatic rotation, audit trail of access |
| Monitoring | Prometheus + Grafana | TPS, failure rate, P99 latency, ledger balance integrity |
| Alerting | PagerDuty | Immediate page for ledger imbalance, orphan gateway charges |

---

## Interview Q&A

### Q1: Why do financial systems use double-entry bookkeeping and why is balance never stored as a column?

Double-entry bookkeeping provides a mathematical invariant (sum of all entries = 0) that
continuously validates the correctness of every transaction. A system that stores balance as
a column is vulnerable to drift: a bug that runs an UPDATE on the balance column without
a corresponding ledger entry will corrupt the balance silently. With double-entry, the balance
is always derived as `SELECT SUM(amount) FROM ledger_entries WHERE account_id = X` — it cannot
drift because it is computed from the immutable source of truth. The ledger itself acts as
both the transaction record and the balance computation mechanism. Additionally, stored balances
require careful locking (SELECT FOR UPDATE) to prevent race conditions between concurrent
transactions, whereas a sum over immutable ledger entries is always consistent within a
database transaction. The tradeoff is that computing balance from ledger entries requires
an aggregation query, which is why materialized views or balance snapshots (computed nightly
and used as a starting point for incremental sums) are used at scale.

---

### Q2: How does the idempotency key pattern prevent double charges, and what happens if the client sends the same key with a different request body?

The idempotency key is a client-generated UUID sent as a request header. The server stores
this key alongside the payment outcome (including the full response) in an idempotency table.
On retry (due to network timeout or client crash), the server looks up the key, finds the
existing record, and returns the cached response without re-processing the payment. This
means the gateway is only called once, preventing double charges. If the client sends the
same idempotency key with a different request body (e.g., different amount), the server
detects the hash mismatch between the stored request hash and the new request hash, and
returns a 422 Unprocessable Entity error explaining that the idempotency key is already
associated with a different request. This strict enforcement is correct behavior — an
idempotency key represents a specific intent (pay $100), and silently using it for a
different amount ($50) would be a source of financial bugs. Idempotency keys should
expire after 24 hours; after that, a new key must be generated for a new payment attempt.

---

### Q3: Explain the Saga pattern and why it is used instead of a distributed transaction (2PC).

A distributed transaction using 2-Phase Commit (2PC) requires all participants to be in a
"prepared" state before the coordinator issues a final commit. This works for internal
databases but fails for two reasons in payment systems: external payment gateways (Stripe,
Adyen) will never participate in your internal 2PC protocol, and 2PC has a blocking problem
where a coordinator crash leaves participants locked in the prepared state indefinitely.
The Saga pattern solves this by breaking the payment into a sequence of local transactions,
each committed immediately. If step N fails, compensating transactions are run for steps
N-1 through 1 — these are new forward operations (a gateway void, a ledger credit) rather
than database rollbacks. The tradeoff is that the system must handle partial states: a
payment may be in the AUTHORIZED state in the gateway but still INITIATING in your database.
This is why the payment status field and audit log are so critical — they record exactly
which Saga steps completed. If the process crashes mid-Saga, the recovery process reads
the current status and resumes from where it left off, idempotently retrying failed steps.

---

### Q4: How do you handle the case where the payment gateway charges the card but your server crashes before recording the charge in the database?

This is the "lost update" problem and it's one of the hardest scenarios in payment systems.
The idempotency key is the primary defense: when the process recovers, the same idempotency
key is used for the retry, and the server first checks if a gateway_ref already exists for
this key. If the gateway was charged (gateway_ref stored before crash), the server skips
the charge and proceeds to update the local database. If the crash happened before gateway_ref
was stored, the server calls the gateway with the idempotency key — Stripe and Adyen support
idempotency keys on their own APIs (a retry with the same key returns the same charge result
without re-charging). The nightly reconciliation job provides the safety net: it compares
all gateway transactions with local records and flags any gateway charges that don't have a
corresponding local payment record. These orphan charges are investigated and either
reconciled (update the local record) or refunded (if the user was charged but never received
the service). This is why the gateway_ref is stored as the FIRST thing after the gateway
call succeeds, before any other database operations.

---

### Q5: What are the tradeoffs between the authorization/capture flow vs. immediate charge?

In an immediate charge, the card is authorized and captured in a single call — the merchant
receives the funds immediately. In the authorization/capture flow, the card is first
authorized (funds reserved on the customer's card) and captured later (funds transferred)
in two separate steps. Authorization/capture is used when: the final amount is not known
at authorization time (hotels where minibar charges are added later), the merchant wants
to verify the customer before charging (verify identity, confirm stock), or regulatory
requirements mandate a delay between authorization and capture. The tradeoff is complexity:
the system must track authorized-but-not-yet-captured payments, expire authorizations
that are never captured (most card networks allow authorization holds for 7-30 days), and
handle the case where capture fails after authorization (authorization must be voided to
release the hold on the customer's card). For most e-commerce, immediate capture is simpler
and preferred; for travel, hospitality, and B2B, authorization/capture is essential.

---

### Q6: How would you design the refund flow, and what makes partial refunds particularly challenging?

A full refund creates compensating ledger entries that exactly reverse the original payment:
the merchant account is debited and the payer account is credited. The gateway also processes
the refund (Stripe's `create_refund` API). A partial refund is more complex: the merchant
is debited for only the partial amount, the payer is credited for the partial amount, and
the payment status transitions to a new state (PARTIALLY_REFUNDED). The challenge is that
a payment can have multiple partial refunds, and the total refunded amount must never exceed
the original payment amount — this constraint must be enforced with a database lock (SELECT
FOR UPDATE on the payment record while computing the refund total). The payment status becomes
a state machine edge case: a payment can be PARTIALLY_REFUNDED, then FULLY_REFUNDED through
multiple refund calls, each requiring the sum of all refunds to be recomputed. Chargebacks
(bank-initiated reversals) are a separate flow: the gateway notifies you via webhook that a
chargeback has been filed, and you have a window (typically 7 days) to provide evidence
contesting the chargeback before the funds are automatically reversed.

---

### Q7: How do you implement multi-currency support and what are the common pitfalls?

Each payment has a currency field (ISO 4217: USD, EUR, GBP, etc.). Ledger entries must also
carry the currency — an account may hold multiple currencies, tracked as separate balance
views. Currency conversion (FX) adds complexity: when converting USD to EUR, the conversion
rate must be locked at a specific point in time (the authorization time), stored with the
transaction, and never recalculated retroactively. Common pitfalls: using floating-point
arithmetic (produces rounding errors — always use DECIMAL or integer arithmetic), not
accounting for settlement currencies (the payment is in EUR but the gateway settles in USD),
and not handling currencies with no decimal places (JPY amounts are integers — a $1.00 USD
payment is 100 cents, but a ¥100 JPY payment is 100 yen). The `amount` column must store
the amount in the currency's natural unit (cents for USD, yen for JPY), with the currency
code determining how to interpret the integer. The FX rate at the time of the transaction
must be recorded immutably in the ledger entry — it cannot be recomputed later because rates
change continuously.

---

### Q8: How would you design the payment system to handle PCI-DSS compliance without it being a bottleneck?

The key is scope reduction: minimize the number of systems that touch cardholder data. By
using hosted payment fields (Stripe.js, Adyen Web), card data never enters your application
servers — it goes directly from the browser to the payment gateway's servers. Your servers
only receive a payment method token. This removes your application from the most stringent
PCI-DSS scope (SAQ D) and places it in the simpler SAQ A scope. For the parts of your
infrastructure that do handle payment tokens (not raw card numbers, but tokens), standard
controls apply: TLS 1.3 in transit, AES-256 at rest, strict RBAC, audit logging of all
access, and network segmentation (payment services in a separate VPC with no internet egress
except to approved gateway endpoints). Penetration testing and quarterly ASV scans are
contractual obligations. From a bottleneck perspective, the hosted fields approach is
actually a performance win: the browser's network latency to the gateway is similar to
your server's latency, and offloading tokenization removes a synchronous processing step
from your backend's critical path.

---

### Q9: How do you ensure the ledger sum invariant is maintained in production?

The invariant (sum of all ledger entries = 0) is enforced at multiple layers. First, the
application layer always creates paired debit and credit entries in a single database
transaction — if either INSERT fails, the transaction rolls back and neither entry exists.
Second, a database-level CHECK constraint or trigger validates that for each payment_id,
the sum of entries equals zero before committing. Third, a continuous monitoring job runs
every 5 minutes: `SELECT SUM(amount) FROM ledger_entries WHERE created_at > NOW() - INTERVAL '10 min'`
— any non-zero result triggers an immediate PagerDuty alert. Fourth, the nightly
reconciliation compares the computed balance from ledger entries against the expected
balance from payment records. The immutability of ledger entries (enforced via DB rules
preventing UPDATE/DELETE) ensures that historical entries cannot be silently modified to
"fix" a discrepancy — corrections must be made through new compensating entries, which
themselves maintain the invariant.

---

### Q10: Walk me through how you'd handle a chargeback.

A chargeback occurs when a cardholder disputes a charge with their bank. The bank reverses
the funds from the merchant's gateway account and initiates a dispute resolution process.
Your system receives a `chargeback.created` webhook from the gateway. The webhook handler
creates a new chargeback record, updates the payment status to DISPUTED, debits the
merchant's ledger by the chargeback amount plus the chargeback fee (typically $15-$25),
and creates a task in the dispute management queue. The merchant is notified and has a
window (typically 5-7 calendar days) to provide evidence (proof of delivery, signed
authorization, communication logs) through a dispute management interface. The evidence
is uploaded to the gateway's dispute API. If the dispute is won, a `chargeback.reversed`
webhook arrives, and the funds are credited back to the merchant's ledger. If lost, the
chargeback stands and the merchant absorbs the loss. The key design requirement is that
the chargeback flow must be fully automated for the notification and accounting steps,
while the evidence submission is handled through a human-assisted workflow. High chargeback
rates (above 1%) trigger merchant account suspension by card networks — the system must
monitor this metric and alert merchants before they approach the threshold.

---

### Q11: How would you design the system for 99.99% availability given that it depends on external payment gateways that may have lower availability?

The system achieves 99.99% availability through a combination of multi-gateway redundancy,
graceful degradation, and circuit breakers. Multiple gateway integrations (Stripe as primary,
Adyen as secondary) allow automatic failover when one gateway's availability drops. The
circuit breaker monitors gateway error rate: if Stripe's error rate exceeds 50% over a
30-second window, the circuit breaker opens and all new payments are routed to Adyen without
manual intervention. During a full gateway outage, the system can queue payment requests
(up to a configurable limit and time window) and process them when the gateway recovers —
this requires careful design of the idempotency and state machine to handle delayed processing.
The database layer uses PostgreSQL with synchronous replication to a hot standby, automated
failover via Patroni, and connection pooling via PgBouncer to handle connection spikes during
failover. The API layer is stateless with multiple instances behind a load balancer. The
one component that cannot be made redundant is the external gateway: if all gateways are
down simultaneously (extremely rare), the system must fail cleanly (return 503, queue
requests, send notifications) rather than returning incorrect responses.

---

### Q12: What operational metrics would you monitor for a payment system in production?

The most critical metric is the payment success rate — the percentage of payment attempts
that result in a CAPTURED status. This should be segmented by payment method (card, bank
transfer), card brand (Visa, Mastercard), and geography (decline rates vary by country).
A sudden drop in success rate indicates a gateway issue, a fraud rule misconfiguration, or
a bug. P99 latency for the payment API (target < 3 seconds) and the gateway API (target
< 2 seconds from your perspective) catch performance regressions. The ledger balance
invariant check (sum = 0) must run continuously and any non-zero result is a P0 incident.
Reconciliation discrepancy count (ideally zero daily) tracks financial integrity against
the gateway. DLQ depth for webhook delivery indicates merchants are not receiving payment
notifications. Chargeback rate by merchant (threshold: 1% of monthly volume) identifies
merchants with fraud problems. Idempotency key collision rate (should be near zero)
indicates whether clients are incorrectly reusing keys. Gateway error rate by error code
(card_declined vs. insufficient_funds vs. gateway_error) helps distinguish user-caused
failures from system failures. Finally, fraud block rate — the percentage of payments
declined by the fraud scoring system — should be monitored for both false positives
(blocking legitimate payments) and false negatives (fraudulent payments that slipped through).

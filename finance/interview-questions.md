# Finance Interview Questions — Java Developer (Banking/Finance Missions)

> Mix of conceptual, calculation, and Java-specific questions you'll actually face.

---

## Calculations in a Java Finance Interview — Reality Check

**Do they ask calculation questions?** Yes, sometimes. But here is the truth about their difficulty level.

### SIMPLE calculations — asked to a Java dev (high school level)

These test that you understand the concept, nothing more. No complex formula required.

```
✅ Bond coupon
   "Bond face value 1000€, 5% coupon semi-annual → how much per payment?"
   → 1000 × 5% / 2 = 25€   [5 seconds]

✅ Basis points conversion
   "Convert 0.75% to basis points"
   → 75 bps   [instant]

✅ Dividend yield
   "Stock at 80€, annual dividend 4€ → yield?"
   → 4 / 80 × 100 = 5%   [mental math]

✅ P/E ratio
   "Net income 10M€, 5M shares, stock price 40€ → EPS and P/E?"
   → EPS = 2€,  P/E = 20x   [30 seconds]

✅ Future Value (basic)
   "5000€ at 6% for 2 years, compounded annually → FV?"
   → 5000 × (1.06)² = 5618€   [acceptable to use paper]

✅ Present Value (basic)
   "1000€ in 3 years, rate 8% → PV?"
   → 1000 / (1.08)³ ≈ 793.83€   [calculator is fine]
```

### Calculations you will NOT see in a Java dev interview

These belong to quant / risk developer interviews.

```
❌ Option pricing with Black-Scholes
❌ Yield to Maturity calculation (requires iterative solving)
❌ Monte Carlo VaR
❌ IRS cash flow netting with multiple resets
❌ Macaulay / Modified Duration from scratch
❌ Forward FX rate from interest rate parity
❌ MBS / structured product valuation
```

### What interviewers actually care about on finance calculations

The goal is not the arithmetic — it's whether you understand **what the number means**.

> **Typical interview exchange:**
> Interviewer: "If interest rates go up, what happens to a bond price?"
> Good answer: "It goes down. When market yields rise, the fixed coupons of existing bonds become less attractive — the price adjusts down to compensate and make the yield competitive."
> → They will NOT ask you to calculate the exact new price.

If the role is explicitly **quant developer, risk engine developer, or pricing developer**, then the complex calculations do apply — ask in advance to prepare accordingly.

---

## Menu

- [Part 1 — Core Finance Concepts](#part-1--core-finance-concepts)
  - [Q1. APR vs APY](#q1-what-is-the-difference-between-apr-and-apy)
  - [Q2. Basis Points](#q2-what-is-a-basis-point-convert-075-to-basis-points)
  - [Q3. Bond discount vs premium](#q3-what-does-it-mean-when-a-bond-trades-at-a-discount-vs-at-a-premium)
  - [Q4. Coupon payment calculation](#q4-a-bond-has-face-value-1000-coupon-rate-5-pays-semi-annually-what-is-the-coupon-payment)
  - [Q5. Present Value calculation](#q5-what-is-the-present-value-of-1000-received-in-3-years-discount-rate-8)
  - [Q6. Future Value compounded quarterly](#q6-if-you-invest-5000-at-6-compounded-quarterly-for-2-years-what-is-the-future-value)
  - [Q7. Forward vs Futures](#q7-what-is-the-difference-between-a-forward-and-a-futures-contract)
  - [Q8. Call option — buyer vs seller risk](#q8-what-is-a-call-option-is-the-buyer-or-seller-taking-more-risk)
  - [Q9. Option intrinsic value](#q9-calculate-the-intrinsic-value-of-a-call-option)
  - [Q10. VaR and limitations](#q10-what-is-var-and-what-are-its-limitations)
  - [Q11. Dividend yield](#q11-a-stock-pays-an-annual-dividend-of-3-the-stock-price-is-60-what-is-the-dividend-yield)
  - [Q12. EPS and P/E](#q12-company-earns-10m-net-income-has-5m-shares-outstanding-stock-price-is-40-calculate-eps-and-pe)
  - [Q13. Duration](#q13-what-is-duration-in-the-context-of-bonds)
  - [Q14. Mark-to-Market vs Mark-to-Model](#q14-what-is-the-difference-between-mark-to-market-and-mark-to-model)
  - [Q15. Settlement and T+2](#q15-what-is-settlement-and-what-is-t2)
- [Part 2 — Java-Specific Finance Questions](#part-2--java-specific-finance-questions)
  - [Q16. Monetary amounts in Java — why not double?](#q16-how-would-you-represent-monetary-amounts-in-java-why-not-use-double-or-float)
  - [Q17. Compound interest at scale](#q17-you-need-to-calculate-compound-interest-for-1-million-trades-every-night-what-are-your-concerns)
  - [Q18. Multi-currency handling](#q18-how-do-you-handle-currency-and-multi-currency-in-a-java-application)
  - [Q19. Trade booking system design](#q19-how-would-you-design-a-trade-booking-system)
  - [Q20. FIX Protocol](#q20-what-is-the-fix-protocol-and-have-you-worked-with-it)
  - [Q21. High-throughput pricing service](#q21-a-pricing-service-needs-to-handle-10000-market-data-updates-per-second-how-do-you-design-it)
  - [Q22. Idempotency in settlement](#q22-how-do-you-ensure-idempotency-in-a-settlement-system)
- [Part 3 — Behavioral / Situational Questions](#part-3--behavioral--situational-finance-questions)
  - [Q23. Bug in interest calculation](#q23-you-find-a-bug-in-the-interest-calculation-that-has-been-running-for-6-months-what-do-you-do)
  - [Q24. Front / Middle / Back Office](#q24-what-is-the-difference-between-front-office-middle-office-and-back-office)
  - [Q25. Reconciliation](#q25-what-is-reconciliation-in-banking-and-why-is-it-important)
- [Quick Calculation Cheat Sheet](#quick-calculation-cheat-sheet)
- [Topics to Study Deeper](#topics-to-study-deeper-if-time-permits)

---

## Part 1 — Core Finance Concepts

**Q1. What is the difference between APR and APY?**

APR is the nominal annual rate without compounding. APY accounts for compounding.
```
APY = (1 + APR/n)^n - 1

Example: APR = 12%, compounded monthly (n=12)
APY = (1 + 0.12/12)^12 - 1 = (1.01)^12 - 1 ≈ 12.68%
```
APY is always ≥ APR. Banks advertise APY for savings, APR for loans.

---

**Q2. What is a basis point? Convert 0.75% to basis points.**

1 basis point (bps) = 0.01%.
```
0.75% = 75 basis points
```
Used everywhere: "The Fed raised rates by 25bps" = raised by 0.25%.

---

**Q3. What does it mean when a bond trades at a discount vs at a premium?**

- **Discount**: Bond price < face value → market yield > coupon rate
- **Premium**: Bond price > face value → market yield < coupon rate
- **Par**: Bond price = face value → market yield = coupon rate

---

**Q4. A bond has face value €1000, coupon rate 5%, pays semi-annually. What is the coupon payment?**

```
Annual coupon = €1000 × 5% = €50
Semi-annual coupon = €50 / 2 = €25

→ You receive €25 every 6 months.
```

---

**Q5. What is the present value of €1000 received in 3 years, discount rate 8%?**

```
PV = FV / (1 + r)^n
PV = 1000 / (1 + 0.08)^3
PV = 1000 / 1.259712
PV ≈ €793.83
```

---

**Q6. If you invest €5000 at 6% compounded quarterly for 2 years, what is the future value?**

```
FV = P × (1 + r/n)^(n×t)
FV = 5000 × (1 + 0.06/4)^(4×2)
FV = 5000 × (1.015)^8
FV = 5000 × 1.12649
FV ≈ €5632.46
```

---

**Q7. What is the difference between a forward and a futures contract?**

| | Forward | Futures |
|---|---|---|
| Where traded | OTC (bilateral) | Exchange |
| Standardized | No (custom) | Yes |
| Counterparty risk | Higher | Lower (CCP) |
| Settlement | At maturity | Daily mark-to-market |
| Flexibility | High | Low |

---

**Q8. What is a call option? Is the buyer or seller taking more risk?**

A call option gives the buyer the RIGHT (not obligation) to buy an asset at a strike price before expiry.

- **Buyer's max loss**: Premium paid. Unlimited upside.
- **Seller's max gain**: Premium received. Unlimited potential loss.

The **seller (writer)** takes more risk.

---

**Q9. Calculate the intrinsic value of a call option:**
Stock price = €120, strike = €100, premium = €25.

```
Intrinsic value = max(Stock Price - Strike, 0)
= max(120 - 100, 0)
= €20

Time value = Premium - Intrinsic value
= 25 - 20 = €5
```

The option is **in the money (ITM)** by €20.

---

**Q10. What is VaR and what are its limitations?**

VaR (Value at Risk): Maximum loss expected with a given confidence level over a period.
```
"1-day 99% VaR = €2M" → 99% chance daily loss ≤ €2M
```

**Limitations:**
- Says nothing about losses beyond the threshold (tail risk)
- Assumes normal distribution — real markets have fat tails
- Historical VaR assumes the past predicts the future
- Not additive across portfolios (unless normally distributed)
- Doesn't capture liquidity risk

---

**Q11. A stock pays an annual dividend of €3. The stock price is €60. What is the dividend yield?**

```
Dividend Yield = Annual Dividend / Stock Price × 100
= 3 / 60 × 100
= 5%
```

---

**Q12. Company earns €10M net income, has 5M shares outstanding, stock price is €40. Calculate EPS and P/E.**

```
EPS = Net Income / Shares Outstanding
= 10,000,000 / 5,000,000 = €2

P/E = Stock Price / EPS
= 40 / 2 = 20

→ Investors pay 20× earnings. High P/E = growth expectations or overvaluation.
```

---

**Q13. What is duration in the context of bonds?**

Duration measures the sensitivity of a bond's price to interest rate changes.
Expressed in years — longer duration = more interest rate risk.

```
Approximate price change:
ΔPrice ≈ -Duration × ΔRate × Price

Example: Duration = 5, Rate increases by 1% (100 bps), Price = €1000
ΔPrice ≈ -5 × 0.01 × 1000 = -€50
→ Bond price drops ~€50
```

**Modified Duration** is used for this approximation.

---

**Q14. What is the difference between mark-to-market and mark-to-model?**

- **Mark-to-Market (MTM)**: Value using observable market prices. Objective.
- **Mark-to-Model**: No market price available → use a model (e.g., for illiquid OTC derivatives). Subjective, model risk.

---

**Q15. What is settlement and what is T+2?**

Settlement = actual exchange of cash and securities after a trade.
- **T+2**: Settlement happens 2 business days after trade date.
  - Trade on Monday → Settlement on Wednesday
- **T+0 (Same day)** or **T+1** exist for some instruments (e.g., US Treasuries moving to T+1).

---

## Part 2 — Java-Specific Finance Questions

**Q16. How would you represent monetary amounts in Java? Why not use double or float?**

**Never use `double` or `float` for money.** Floating-point cannot represent decimal fractions exactly.

```java
// BAD
double price = 0.1 + 0.2; // = 0.30000000000000004

// GOOD options:
// 1. BigDecimal (most common in finance)
BigDecimal price = new BigDecimal("0.10").add(new BigDecimal("0.20"));

// 2. Store as long (cents/pence/basis points)
long priceInCents = 10L + 20L; // = 30 cents

// In practice: BigDecimal with explicit scale and rounding mode
BigDecimal result = amount.divide(rate, 10, RoundingMode.HALF_EVEN);
// HALF_EVEN (Banker's rounding) is standard in finance
```

**Banker's Rounding (HALF_EVEN)**: Rounds to nearest even when exactly halfway.
```
2.5 → 2 (rounds to even)
3.5 → 4 (rounds to even)
Reduces cumulative rounding bias over many calculations.
```

---

**Q17. You need to calculate compound interest for 1 million trades every night. What are your concerns?**

- **Precision**: Use `BigDecimal` with controlled scale
- **Performance**: `BigDecimal` is slower than primitives — consider batching, parallelism
- **Rounding policy**: Must be consistent and configurable (HALF_EVEN standard)
- **Overflow**: Long for integer amounts, BigDecimal for decimals
- **Idempotency**: Batch job must be re-runnable without double-booking
- **Auditability**: Store calculation inputs and results, not just final number

---

**Q18. How do you handle currency and multi-currency in a Java application?**

```java
// Use JSR-354 (javax.money) — the Java Money API
MonetaryAmount amount = Money.of(100, "EUR");
MonetaryAmount converted = amount.with(MonetaryConversions.getConversion("USD"));

// Or use dedicated value objects
record Money(BigDecimal amount, Currency currency) {
    Money add(Money other) {
        if (!this.currency.equals(other.currency))
            throw new CurrencyMismatchException("Cannot add " + currency + " and " + other.currency);
        return new Money(this.amount.add(other.amount), this.currency);
    }
}
```

Key points:
- Never mix currencies without explicit conversion
- Store exchange rates with timestamp and source
- FX rates have bid/ask spread — which rate do you use?

---

**Q19. How would you design a trade booking system?**

Expected points to cover:
- **Trade entity** with: tradeId, instrument, quantity, price, currency, counterparty, tradeDate, settlementDate, status
- **State machine** for trade lifecycle: NEW → VALIDATED → CONFIRMED → SETTLED
- **Idempotency**: Duplicate trade detection (same tradeId)
- **Audit trail**: Never delete/update trades, use event sourcing or versioning
- **Transactionality**: Database transactions for booking
- **Messaging**: Publish events (Kafka/MQ) for downstream systems (risk, settlement)

```java
public enum TradeStatus {
    DRAFT, VALIDATED, CONFIRMED, PENDING_SETTLEMENT, SETTLED, CANCELLED, FAILED
}
```

---

**Q20. What is the FIX protocol and have you worked with it?**

FIX (Financial Information eXchange): Standard messaging protocol for securities transactions.
- Industry standard for real-time trade communication
- Tag=Value format: `8=FIX.4.4|35=D|49=SENDER|56=TARGET|...`
- **35=D** = New Order Single (most common message)
- Libraries: QuickFIX/J (Java)

Even if you haven't used it, knowing it exists and its purpose is expected.

---

**Q21. A pricing service needs to handle 10,000 market data updates per second. How do you design it?**

- **Non-blocking / reactive**: Spring WebFlux, Project Reactor, or Disruptor pattern
- **In-memory cache**: Redis or local ConcurrentHashMap for latest prices
- **Event-driven**: Kafka consumer subscribing to price feed topics
- **Snapshot + delta**: Don't recompute everything, apply incremental updates
- **Circuit breaker**: Handle upstream feed outages (Resilience4j)
- **Monitoring**: Latency percentiles (p99), throughput metrics (Micrometer + Prometheus)

---

**Q22. How do you ensure idempotency in a settlement system?**

Settlement must not double-execute (sending €1M twice is catastrophic).

```java
// 1. Unique idempotency key per operation
// 2. Check-then-act with database unique constraint
INSERT INTO settlements (settlement_id, trade_id, amount, status)
VALUES (?, ?, ?, 'PENDING')
ON CONFLICT (settlement_id) DO NOTHING;

// 3. Optimistic locking with version field
@Version
private Long version;

// 4. Outbox pattern: write to DB + outbox table in same transaction
// Worker picks up outbox events and sends, marks processed
```

---

## Part 3 — Behavioral / Situational Finance Questions

**Q23. You find a bug in the interest calculation that has been running for 6 months. What do you do?**

Expected structure:
1. **Assess impact**: Which accounts? What period? Magnitude?
2. **Do not silently fix**: Escalate to business/compliance immediately
3. **Do not modify historical data without approval**
4. **Produce a report**: List of affected records, delta amounts
5. **Correction process**: Likely requires a formal adjustment entry (accounting)
6. **Fix the code** + add regression tests
7. **Post-mortem**: How did it pass code review? Add detection mechanisms

---

**Q24. What is the difference between front office, middle office, and back office?**

| | Front Office | Middle Office | Back Office |
|---|---|---|---|
| Role | Revenue generation | Risk & control | Operations |
| Who | Traders, sales | Risk managers, quants | Settlement, accounting |
| Systems | Trading platforms, OMS | Risk engines, PnL systems | Settlement, reconciliation |
| Java dev impact | Low latency, pricing | Risk calculations, reporting | Batch processing, messaging |

---

**Q25. What is reconciliation in banking and why is it important?**

Reconciliation = comparing two sets of records to ensure they match.
- **Nostro reconciliation**: Our records vs bank statement
- **Position reconciliation**: Internal books vs custodian/exchange
- **Trade reconciliation**: Confirm both sides of a trade agree on terms

If reconciliation breaks:
- Money could be lost or double-booked
- Regulatory reporting would be wrong
- Audit failures

In Java: typically batch jobs (Spring Batch) that run end-of-day.

---

## Quick Calculation Cheat Sheet

```
Simple Interest:        I = P × r × t
Future Value (simple):  FV = P(1 + rt)
Future Value (compound):FV = P(1 + r/n)^(nt)
Present Value:          PV = FV / (1 + r)^n
APY from APR:           APY = (1 + APR/n)^n - 1
Coupon Payment:         C = FaceValue × CouponRate / PaymentsPerYear
Dividend Yield:         DY = Annual Dividend / Stock Price
EPS:                    EPS = Net Income / Shares Outstanding
P/E Ratio:              P/E = Stock Price / EPS
Duration price impact:  ΔP ≈ -D × Δr × P
Intrinsic value (call): max(Spot - Strike, 0)
Intrinsic value (put):  max(Strike - Spot, 0)
```

---

## Topics to Study Deeper (If Time Permits)

- **Greeks** (Delta, Gamma, Theta, Vega) — option sensitivity measures
- **Black-Scholes** — option pricing model (know the concept, not necessarily the full formula)
- **Yield curve** — relationship between bond yield and maturity
- **LIBOR/EURIBOR/SOFR** — benchmark interest rates (LIBOR phase-out → SOFR transition)
- **Repo / Reverse Repo** — short-term borrowing using securities as collateral
- **Basel III** capital requirements
- **SWIFT MT vs MX (ISO 20022)** — financial messaging standards
- **Clearing and CCP** — how central counterparties reduce systemic risk

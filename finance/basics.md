# Finance Basics for Java Developers — Banking Missions

> **Philosophy of this document**: you are a good Java developer but you have never worked in finance.
> This file explains concepts the way a colleague who has worked in banking for 5 years would explain them
> to you over a coffee. We start from scratch, go step by step, and always connect back to code you will write.

---

## Menu

- [1. Why understand finance as a Java dev?](#1-why-understand-finance-as-a-java-dev)
- [2. Money and time — the foundation of everything](#2-money-and-time--the-foundation-of-everything)
- [3. Interest rates — how money earns money](#3-interest-rates--how-money-earns-money)
- [4. Financial instruments — what gets bought and sold](#4-financial-instruments--what-gets-bought-and-sold)
  - [4.1 Bonds — lending money](#41-bonds--lending-money)
  - [4.2 Stocks / Equities — owning a piece](#42-stocks--equities--owning-a-piece)
  - [4.3 Derivatives — contracts on other things](#43-derivatives--contracts-on-other-things)
  - [4.4 FX — exchanging currencies](#44-fx--exchanging-currencies)
- [5. The trade — the heart of every banking system](#5-the-trade--the-heart-of-every-banking-system)
- [6. Trade lifecycle — step by step](#6-trade-lifecycle--step-by-step)
- [7. Position and PnL — what the trader watches](#7-position-and-pnl--what-the-trader-watches)
- [8. Risk — why everyone talks about it](#8-risk--why-everyone-talks-about-it)
- [9. Who does what — front, middle, back office](#9-who-does-what--front-middle-back-office)
- [10. Market participants](#10-market-participants)
- [11. Glossary — words you will hear every day](#11-glossary--words-you-will-hear-every-day)
- [12. What you will actually build](#12-what-you-will-actually-build)

---

## 1. Why understand finance as a Java dev?

When you join a banking team, you will very quickly sit in meetings with traders, risk managers, and business analysts. They will use terms you have probably never heard.

**If you don't understand the domain:**
- You code "in the dark" — you don't understand why the business rules exist
- You make silent errors (a wrong rounding in a financial calculation can cost thousands of euros)
- Traders and BAs trust you less, and specifications take longer to understand

**If you understand the domain:**
- You ask the right questions
- You understand priorities (a bug in the EOD PnL batch is critical; a display glitch is not)
- You become a much more effective developer in that context

**Good news:** you don't need to become an expert. You need to understand the vocabulary and the big ideas. This document gets you there step by step.

---

## 2. Money and time — the foundation of everything

### The core question

Imagine I offer you two choices:
- **Option A**: receive €1,000 today
- **Option B**: receive €1,000 in one year

Which do you choose? **Option A**, obviously. Why?

Not just because you prefer cash now. It's because the €1,000 you have today can be invested and become *more than* €1,000 in a year. For example, invested at 5%, it becomes €1,050 next year.

**This is the fundamental principle of all finance:** money today is worth more than the same amount in the future, because it can produce returns.

This is called the **Time Value of Money (TVM)**.

### Why does it matter for a dev?

Every financial calculation rests on this:
- The **price of a bond** is the sum of its discounted future payments
- The **price of an option** includes time value
- **Swap valuations** discount all future cash flows

If you don't understand this concept, you don't understand *why* pricing formulas work the way they do.

### The two essential formulas

**Future Value (FV)** — how much will my money be worth in the future?
```
FV = PV × (1 + r)^n

PV = starting amount (Present Value)
r  = interest rate per period
n  = number of periods
```

Example: I invest €1,000 at 5% for 3 years.
```
FV = 1000 × (1 + 0.05)^3
FV = 1000 × 1.1576
FV = €1,157.63
```

**Present Value (PV)** — how much is a future amount worth today?

This is the reverse: if someone promises me €1,157.63 in 3 years, and I can earn 5% on my money, what is that promise worth today?
```
PV = FV / (1 + r)^n
PV = 1157.63 / (1.05)^3
PV = €1,000
```

We say we **discount** the future cash flow. The rate used is called the **discount rate**.

> **Rule to remember:** the further in the future, the less it is worth today. And the higher the rate, the lower the present value.

---

## 3. Interest rates — how money earns money

### The rate is the price of money

When you borrow money from a bank, you pay interest. When you deposit money, you receive interest. The interest rate is the price of using money over time.

### Simple vs compound interest

**Simple interest:** interest calculated only on the original capital. No "snowball" effect.

```
Interest = Principal × Rate × Time

Example: €1,000 at 5% for 2 years (simple interest)
Interest = 1000 × 0.05 × 2 = €100
Total = €1,100
```

**Compound interest:** interest earns interest on itself. Snowball effect.

```
FV = Principal × (1 + rate)^years

Example: €1,000 at 5% for 2 years (compounded annually)
Year 1: 1000 × 1.05 = €1,050   (€50 in interest)
Year 2: 1050 × 1.05 = €1,102.50   (€52.50 interest on €1,050, not €1,000)
```

The difference here is small (€2.50) but over 30 years and large amounts, the effect becomes enormous. This is why all of finance uses compound interest.

### Basis points (bps) — the unit of rate changes in finance

In finance, interest rate changes are never expressed in "%" directly — everyone uses **basis points** (bps, pronounced "bips").

```
1 basis point = 0.01% = 0.0001

100 bps = 1%
50 bps  = 0.5%
25 bps  = 0.25%
1 bps   = 0.01%
```

**Why?** To remove ambiguity. If a rate goes from 2% to 2.2%, did it rise by 10% (relative: 0.2/2) or 0.2% (absolute)?

If you say "the rate rose 20 bps", it is unambiguous: it went from 2% to 2.20%.

You will hear this every day: *"the ECB hiked rates by 25 bps"*, *"the spread widened 50 bps"*.

### Benchmark interest rates

Many financial products have a variable rate based on a reference rate:

- **EURIBOR** (Euro Interbank Offered Rate): the rate at which European banks lend to each other. Comes in several maturities: 1M, 3M, 6M, 12M. The primary European benchmark.

  Concrete example: a loan at "variable rate EURIBOR 3M + 1%" → the rate resets every 3 months.

- **SOFR**: the US replacement for LIBOR since 2023 (LIBOR was abandoned after a manipulation scandal).

- **Risk-free rate**: government bond yield (OAT for France, Bund for Germany, Treasury for the US). Used to discount "risk-free" cash flows.

---

## 4. Financial instruments — what gets bought and sold

A financial instrument is simply something that can be bought, sold, or held to generate a return or protect against a risk.

There are several major families. A Java developer in banking works on systems that manage these instruments — understanding how they work is essential to coding correctly.

---

### 4.1 Bonds — lending money

#### The simple analogy

Imagine you lend €1,000 to a friend and they promise to:
1. Pay you €50 in interest every year for 5 years
2. Give you back your €1,000 at the end of 5 years

That is exactly what a bond is — except the borrower is a government or large company, and you (the investor) can sell this "I-owe-you" to someone else on the market.

#### Key terms

| Term | What it means | Concrete example |
|---|---|---|
| **Issuer** | The one who borrows | The French government, BNP Paribas |
| **Face value / Nominal** | The amount borrowed, repaid at the end | €1,000 |
| **Coupon rate** | Annual interest rate on face value | 5% per year |
| **Coupon payment** | The actual interest payment | €50/year (or €25 every 6 months) |
| **Maturity** | The date the principal is repaid | In 5 years, on 15/06/2030 |
| **YTM** | Total return if you hold to maturity | 5.2% |

#### How to calculate a coupon (common interview question)

```
Annual coupon = Face Value × Coupon Rate
Semi-annual coupon = Face Value × Coupon Rate / 2

Example:
Bond face value €1,000, coupon rate 4%, semi-annual payments
Annual coupon     = 1000 × 4% = €40
Semi-annual coupon = 40 / 2 = €20  (paid every 6 months)
```

#### What happens to the price when rates change

This is a fundamental and counter-intuitive rule.

Imagine: you own a bond paying 3% per year. Then market rates rise to 5%. Someone can now buy a brand new bond paying 5% — why would they buy your old 3% bond?

They would only buy it at a **reduced price** — enough of a discount so that the effective return equals 5%.

**Result: when rates rise, bond prices fall. And vice versa.**

```
Market rates ↑  →  Bond prices ↓
Market rates ↓  →  Bond prices ↑
```

This is an inverse, fundamental relationship. Rate traders live with this every day.

#### Common bond types

- **Government bonds (Sovereigns)**: issued by governments. OAT (France), Bund (Germany), Treasury (USA). Safest.
- **Corporate bonds**: issued by companies. Riskier = higher coupon.
- **Zero-coupon bond**: no coupons — sold at a deep discount, redeemed at 100% at maturity.
- **Floating rate note (FRN)**: coupon resets periodically based on EURIBOR + a fixed margin.

#### Why does this matter for you as a dev?

- Front office systems **book** bond trades
- Middle office **calculates PnL**: what is the position worth today vs yesterday?
- **Pricing engines** compute the current value of a bond at every rate change
- **Coupon batches** generate payments on the right dates
- **EMIR/MiFID II reporting** declares transactions to regulators

---

### 4.2 Stocks / Equities — owning a piece

#### The simple analogy

A share of stock is a fractional ownership of a company. If BNP Paribas is worth €50 billion and issues 1 billion shares, each share represents one-billionth of the bank.

By buying a share, you become (a very small) owner of the company. You benefit if the company makes money — through:
1. Share price appreciation (capital gain)
2. **Dividends**: a portion of profits paid out to shareholders

#### Key terms

**Dividend**: periodic payment of a share of profits to shareholders.
```
Dividend Yield = Annual Dividend / Share Price × 100

Example: stock at €60, annual dividend €3
Dividend Yield = 3 / 60 × 100 = 5%

→ The stock returns 5% per year in dividends (excluding capital gains)
```

**EPS (Earnings Per Share)**:
```
EPS = Net Income / Number of Shares Outstanding

Example: net income €100M, 50M shares
EPS = 100M / 50M = €2 per share
```

**P/E ratio (Price-to-Earnings)**: how much investors pay for €1 of earnings.
```
P/E = Share Price / EPS

Example: stock at €30, EPS = €2
P/E = 30 / 2 = 15x

→ Investors are willing to pay 15 times annual earnings
→ High P/E (30-50) = strong growth expected, or overvaluation
→ Low P/E (5-10) = cheap stock, or company in trouble
```

**Market Cap (market capitalisation)**:
```
Market Cap = Share Price × Number of Shares

Example: stock at €50, 2M shares
Market Cap = 50 × 2M = €100M
```

#### Why does it matter for a dev?

- **OMS** (Order Management System) manages equity buy/sell orders
- **Corporate actions** (dividends, stock splits, mergers) must be automatically processed in systems — often a complex batch
- **Position valuation** is done in real time (mark-to-market)

---

### 4.3 Derivatives — contracts on other things

A derivative is a contract whose value **depends on** (derives from) another underlying asset (stock, rate, currency, commodity...). It's a **synthetic** instrument — you don't buy the asset itself, you buy a contract that behaves as if you did.

#### Why do derivatives exist?

Mainly two reasons:
1. **Hedging**: an airline worried about rising oil prices can buy oil derivatives to lock in their cost in advance
2. **Speculation**: bet on a market's direction with less capital than buying the asset directly (leverage)

#### Options — the right without the obligation

An option is a contract giving you the **right** (not the obligation) to buy or sell something at a fixed price, before a given date.

**Call option** = right to **buy**
- You buy a call when you think the price will **rise**
- If the price rises above the strike → you exercise and profit
- If the price doesn't rise → you don't exercise, you only lose the premium you paid

**Put option** = right to **sell**
- You buy a put when you think the price will **fall**
- If the price falls below the strike → you exercise and profit

**Simple example:**
```
BNP stock trades at €60.
You buy a call option:
  - Strike (exercise price): €65
  - Premium (option cost): €3
  - Expiry: 3 months from now

Scenario 1: stock rises to €75 in 3 months
→ You exercise: buy at €65 (the strike), worth €75
→ Profit = (75 - 65) - €3 premium = €7 per share ✅

Scenario 2: stock stays at €60
→ You don't exercise (no one buys at €65 what is worth €60)
→ Loss = €3 premium (all you had at risk) ❌

→ Your maximum loss is ALWAYS limited to the premium paid (€3)
→ Your potential gain is unlimited (if stock goes to €200...)
```

**Key vocabulary:**
- **In The Money (ITM)**: profitable to exercise now (call: spot > strike)
- **At The Money (ATM)**: spot ≈ strike
- **Out Of The Money (OTM)**: not worth exercising now (call: spot < strike)

#### Futures and forwards — a commitment to buy/sell in the future

A **forward** or **futures** contract commits both parties to buy/sell an asset at a future date at a price agreed today.

**Key difference:**

| | Forward | Futures |
|---|---|---|
| Where traded | OTC (bilateral, between two banks) | Exchange (standardised) |
| Default risk | Yes — counterparty risk | No — CCP guarantees both sides |
| Settlement | At maturity only | Daily mark-to-market (margin) |

**Concrete example:** Airbus receives payments in dollars but pays its employees in euros. To hedge against a dollar depreciation, Airbus sells USD/EUR forwards — they fix the exchange rate today for future deliveries.

#### Swaps — exchanging cash flows over time

A swap is an agreement where two parties exchange cash flows over time according to pre-defined rules. The **notional** (reference amount) is not exchanged — only the interest payments.

**Example — Interest Rate Swap (IRS):**

The situation: a company borrowed €10M at a variable rate (EURIBOR + 1%). They fear EURIBOR will rise. They want a fixed rate.

Solution: they enter an IRS with a bank.

```
Company PAYS: fixed rate 3% on €10M = €300,000/year
Bank PAYS: EURIBOR × €10M (variable)

If EURIBOR = 2%: bank pays €200,000, company pays €300,000
→ Net: company pays €100,000 to bank

If EURIBOR = 4%: bank pays €400,000, company pays €300,000
→ Net: bank pays €100,000 to company
```

Result: the company has synthetically converted its variable loan into a fixed loan. They know exactly what they will pay each year.

---

### 4.4 FX — exchanging currencies

The Foreign Exchange (FX) market is the largest financial market in the world. Banks, companies, and funds buy and sell currencies here.

**How to read a currency pair:**

```
EUR/USD = 1.08

→ 1 euro = 1.08 US dollars
→ Left currency (EUR) = base currency
→ Right currency (USD) = quote currency

If EUR/USD goes from 1.08 to 1.10 → euro strengthened (buys more dollars)
If EUR/USD goes from 1.08 to 1.06 → euro weakened
```

**Bid / Ask — the buying and selling price:**

```
EUR/USD: Bid = 1.0798 / Ask = 1.0802

→ The bank BUYS euro at 1.0798 (Bid price)
→ The bank SELLS euro at 1.0802 (Ask price)
→ Spread = 1.0802 - 1.0798 = 0.0004 = 4 pips (transaction cost)
```

The spread is the bank's margin. It is always unfavourable to the client.

**Spot vs Forward FX:**
- **Spot**: exchange currencies "now" (settlement in practice at T+2)
- **Forward FX**: agree on the rate today, exchange in X months. Used to hedge currency risk.

---

## 5. The trade — the heart of every banking system

### What is a trade?

A **trade** (also called a "transaction" or "deal") is simply an agreement between two parties to buy or sell a financial instrument under defined conditions.

Every trade has the following information (what you will store and process in Java):

```
TradeId        : unique identifier (UUID) — never changes
Instrument     : what is bought/sold (bond ISIN, stock ticker...)
Direction      : BUY or SELL
Quantity       : how much
Price          : unit price (with currency!)
Counterparty   : with whom (identified by LEI — Legal Entity Identifier)
TradeDate      : when the agreement is made
SettlementDate : when the actual exchange happens (typically TradeDate + 2 days)
Status         : where is the trade in its lifecycle
```

**Example trade:**
```
TradeId        : TRD-2024-00142
Instrument     : FR0000131104  (BNP Paribas ISIN)
Direction      : BUY
Quantity       : 1,000 shares
Price          : €58.40
Counterparty   : 969500TJ5KRTCJQWXH05 (Goldman Sachs LEI)
TradeDate      : 2024-10-15
SettlementDate : 2024-10-17  (T+2)
Status         : CONFIRMED
```

### Types of trades

- **Equity trade**: buying/selling shares
- **Bond trade**: buying/selling bonds
- **FX trade**: exchanging currencies
- **Repo**: selling securities with an agreement to buy them back (short-term financing)
- **Derivative trade**: option, swap, futures

---

## 6. Trade lifecycle — step by step

This is **the most important thing** for a Java dev in banking. The vast majority of systems you will build or maintain correspond to one stage of this lifecycle.

### Overview

```
[Trader decides]
      ↓
1. ORDER CAPTURE
      ↓
2. PRE-TRADE VALIDATION
      ↓
3. EXECUTION
      ↓
4. BOOKING
      ↓
5. CONFIRMATION
      ↓
6. CLEARING
      ↓
7. SETTLEMENT
      ↓
8. ACCOUNTING + PnL
      ↓
9. REPORTING
```

### Step 1: Order Capture

The trader decides to buy 10,000 LVMH shares. They enter the order into an **OMS** (Order Management System).

At this point it is just an order — nothing has been bought yet. The order contains: instrument, quantity, direction (buy/sell), order type (market, limit...).

**What the Java dev codes:** the capture API, format validation, persisting the order to the database.

### Step 2: Pre-trade Validation

Before the order goes to the market, automated checks run:
- **Position limit**: does the trader have the right to buy this much? (risk)
- **Credit limit**: does the bank have enough margin with this counterparty?
- **KYC/AML**: is the counterparty known and legitimate?
- **Compliance**: is the instrument allowed in this portfolio?

If a check fails → the order is blocked and the trader is alerted.

**What the Java dev codes:** validation services, often chained together (Chain of Responsibility pattern), with configurable rules.

### Step 3: Execution

The order is sent to the market (via the **FIX** protocol for exchange-listed equities). The exchange's matching engine finds a matching seller.

For OTC instruments (bonds, swaps, FX): direct negotiation with a counterparty, not via an exchange.

**What the Java dev codes:** the FIX connector, execution message handler, order status updates.

### Step 4: Booking

Once executed, the trade is "booked" (recorded) in the central system (**Murex**, **Calypso**, or an in-house system).

At this point:
- A unique **TradeId** is generated
- All trade data is stored in the database
- An event is published (Kafka, MQ) to notify downstream systems

**Absolute rule:** you **never delete** a trade. If a mistake is made, you cancel it with a trail, or create a corrective trade. Reason: regulatory auditability.

**What the Java dev codes:** the booking service, TradeId generation, event publishing, the Outbox Pattern to guarantee consistency between the database write and the event.

### Step 5: Confirmation

Both counterparties must confirm they agree on the trade terms. In practice this is often automatic via platforms like **MarkitWire** (for derivatives) or **FIX**.

If terms don't match → the trade is "on break" (pending resolution) — a serious operational problem.

**What the Java dev codes:** term matching between both versions of the trade, break management, alerts.

### Step 6: Clearing

For instruments that go through a **CCP** (Central Counterparty) — mandatory for many standardised OTC derivatives (EMIR).

The CCP interposes itself between the two parties:
```
Before clearing: BNP ↔ Goldman Sachs  (bilateral counterparty risk)
After clearing:  BNP ↔ LCH ↔ Goldman Sachs
```

The CCP guarantees both sides — if Goldman Sachs defaults, the CCP covers BNP. In exchange, it demands **margin** (a deposit).

**Netting**: the CCP aggregates all positions between the same counterparties and makes a single net payment. Drastically reduces the number of transactions to settle.

**What the Java dev codes:** sending trades to the CCP interface, margin call management, reconciliation of netted positions.

### Step 7: Settlement

This is where the **real** exchange happens: securities pass from the old owner to the new one, and cash goes the other way.

- For equities: **T+2** (2 business days after the trade)
- For government bonds: T+2 or T+1 depending on the market
- For FX spot: T+2

Settlement uses **SWIFT** messages (the global interbank messaging network) and central depositories like **Euroclear**.

```
SWIFT MT103: payment instruction
SWIFT MT540/541: securities delivery instruction
```

If settlement fails (securities not delivered) → regulatory penalties (**CSDR**) and a **buy-in** process (the failing party must buy the securities in the market to deliver them).

**What the Java dev codes:** SWIFT message generation, settlement status tracking, reconciliation batch.

### Step 8: Accounting and PnL

Once settled, the trade is booked into the bank's general ledger. PnL (Profit and Loss) is calculated.

A batch runs every evening (EOD — End of Day) to compute:
- The MTM (Mark-to-Market) value of all positions
- Daily PnL = today's value - yesterday's value

**What the Java dev codes:** Spring Batch PnL calculation job, valuation engine, accounting interface.

### Step 9: Regulatory Reporting

Regulators require certain information to be transmitted:
- **MiFID II**: every trade must be reported to the national regulator (AMF in France) within the day
- **EMIR**: all OTC derivatives must be reported to a Trade Repository
- **SFTR**: repos and securities lending transactions

**What the Java dev codes:** reporting pipelines, data transformation to regulatory format (XML, CSV), deadline monitoring.

---

## 7. Position and PnL — what the trader watches

### What is a position?

A **position** is the net total of all trades on the same instrument.

```
Example: trades on BNP Paribas stock today
09:30 : buy 1,000 shares
11:00 : buy 500 shares
14:00 : sell 300 shares

Net position = +1,000 + 500 - 300 = +1,200 shares (long position)
```

**Long** = you own the asset (you profit if price rises)
**Short** = you owe the asset (you sold it without owning it — you profit if price falls)

### Mark-to-Market (MTM) — valuing at today's market price

The value of a position changes constantly as market prices move.

```
You own 1,000 shares bought at €58 each.
Tomorrow, the market quotes them at €60.

MTM value   = 1,000 × €60 = €60,000
Purchase cost = 1,000 × €58 = €58,000

Unrealised PnL = +€2,000 (unrealised gain)
```

**Mark-to-Market** = revalue all positions at the market's closing price, every evening.

### PnL (Profit and Loss)

The **daily PnL** is the change in MTM value from one day to the next.

```
Book value yesterday (EOD): €1,500,000
Book value today (EOD):     €1,523,000
Daily PnL = +€23,000
```

PnL accumulates: YTD PnL (Year-to-Date) = sum of daily PnLs since January 1st.

**Why is this critical for a dev?**
- The PnL calculation batch is one of the most critical in the bank
- If it crashes or produces wrong results → traders don't know how much they've made or lost
- It must complete before markets open the next morning (strict cut-off time)

---

## 8. Risk — why everyone talks about it

### What is risk in finance?

Risk is the probability of losing money. In banking, risks are continuously identified, measured, and limited.

### The main types of risk

**Market risk**: prices move in the wrong direction.
- Example: you own bonds, interest rates rise, bond prices fall → you lose money.

**Credit risk**: a counterparty doesn't pay back.
- Example: you lent €10M to a company. It goes bankrupt. You may recover 30% if you're lucky.

**Liquidity risk**: you can't sell what you want, when you want.
- Example: you want to sell €100M of bonds from a little-known company. No one wants them. You have to drop the price 10% to find a buyer.

**Operational risk**: a human or system error.
- Famous example: Jérôme Kerviel (SocGen, 2008) — hidden positions that cost the bank ~€5 billion.
- Developer example: a batch running twice, incorrect rounding, a wrong SWIFT message.

### VaR — Value at Risk

**VaR** is the most well-known market risk metric. It answers: "How much can I lose at most, with a given probability, over a given time horizon?"

```
"99% 1-day VaR = €5M" means:
→ 99% of the time, my daily loss will be ≤ €5M
→ 1% of the time (roughly 2-3 days per year), I will lose more than €5M
```

**It is a probable upper bound on loss — not a guarantee.**

**Why mention it if you won't calculate it?** Because you will build systems that compute or display VaR. When a risk manager says "my DV01 is -€50K and my VaR is €2M", you need to understand what they mean.

**DV01**: how much the position value changes if rates move by 1 bps.
```
DV01 = -€50,000 means: if rates rise 1 bps, I lose €50,000
                       if rates fall 1 bps, I gain €50,000
```

---

## 9. Who does what — front, middle, back office

This comes up in **every** interview. It also determines which team you will work in.

### Front Office — the revenue engine

The front office is where money is made (or lost).

**Who:** traders, sales, structurers
**What they do:** buy, sell, offer products to clients, manage positions
**Their priorities:** speed (milliseconds), real-time prices, frictionless order execution

**What a Java dev does here:**
- Real-time pricing systems
- OMS / EMS (order management and execution)
- FIX connectors to exchanges
- Option valuation engines

**Key platform:** Murex (MXNG), Calypso, Finastra Fusion

### Middle Office — the control layer

The middle office verifies that what the front office does is correct, compliant, and properly managed.

**Who:** risk managers, quants, PnL teams, compliance
**What they do:** compute risk, compute PnL, confirm trades, manage margins
**Their priorities:** accuracy of calculations, detecting anomalies, respecting risk limits

**What a Java dev does here:**
- Risk calculation engines (VaR, sensitivities)
- PnL calculation and PnL Explain
- Confirmation systems (matching with counterparties)
- Collateral management (margin calls)

### Back Office — operations

The back office ensures that everything the front decided actually happens — that securities are delivered, that cash is received.

**Who:** settlement teams, reconciliation, accounting, regulatory reporting
**What they do:** trade settlement, reconciliation, accounting, regulatory reporting
**Their priorities:** zero errors, meeting deadlines, complete traceability

**What a Java dev does here:**
- SWIFT message generation
- Reconciliation batches (Spring Batch, often overnight)
- Regulatory reporting pipelines (EMIR, MiFID II)
- Accounting systems (IFRS 9)

### Visual summary

```
FRONT OFFICE          MIDDLE OFFICE            BACK OFFICE
─────────────         ──────────────────        ─────────────────────
Trade executed   →   Risk checked         →   Settlement
                     PnL calculated            Reconciliation
                     Confirmation done         Regulatory reporting
                     Margin managed            Accounting

Real time             End of day               Night (batches)
Latency critical      Accuracy critical         Reliability critical
```

---

## 10. Market participants

### Buy Side vs Sell Side

**Sell Side** (the banks that sell):
BNP Paribas, Société Générale, Natixis, Goldman Sachs, JP Morgan...

- They create and sell financial products
- They "make markets" — always ready to buy or sell
- They manage a full front-to-back chain

**Buy Side** (the investors that buy):
Amundi, AXA Investment Managers, BlackRock, Vanguard...

- They invest their clients' money (savers, pension funds, insurance)
- They route orders through the sell side
- Systems more oriented toward portfolio management and performance

**For you as a Java dev:** the sell side has the most complex systems (the entire trade chain). The buy side is more oriented toward portfolio management. Both need Java.

### Market infrastructure

**Euronext Paris**: the French stock exchange. Lists French equities (CAC 40). Runs the order matching engine.

**LCH SA / Eurex Clearing**: the European CCPs that clear derivatives and protect the system.

**Euroclear / Clearstream**: the central depositories — they hold securities in custody and settle securities transactions.

**AMF** (Autorité des Marchés Financiers): the French regulator. They receive MiFID II reports.

---

## 11. Glossary — words you will hear every day

These terms come up in almost every conversation in banking. You must know them.

| Term | What it concretely means |
|---|---|
| **Trade / Deal** | A buy/sell agreement — the central object of every banking system |
| **Booking** | The act of recording a trade in the system. *"To book a trade"* = to enter it. |
| **Position** | Net quantity held of an instrument (long if positive, short if negative) |
| **PnL** | Profit and Loss — gains or losses for the day / month / year |
| **Mark-to-Market (MTM)** | Valuing at today's market price, not the purchase price |
| **Spread** | Difference between two prices. Bid-ask spread = transaction cost. Credit spread = yield difference between corporate and government bond. |
| **Notional** | The reference amount of a swap or derivative — not physically exchanged |
| **Collateral** | Guarantee deposited to secure an exposure. If you default, the counterparty keeps the collateral. |
| **Haircut** | Discount applied to collateral. A €100 bond with a 5% haircut = €95 accepted as collateral. |
| **Netting** | Offsetting mutual obligations. A owes B €5M, B owes A €3M → net = A pays €2M |
| **Long** | Owning an asset. You profit if price rises. |
| **Short** | Selling an asset you don't own (borrowed it). You profit if price falls. |
| **Hedge** | Taking an opposite position to protect against a risk. |
| **Liquidity** | Ease of buying/selling an asset quickly without moving its price. CAC 40 stocks = very liquid. Obscure bond = illiquid. |
| **OTC** | Over The Counter — transaction negotiated directly between two parties, not via an exchange |
| **Clearing** | Post-trade process: confirmation, netting, margin — often via a CCP |
| **Settlement** | The actual exchange of securities and cash |
| **CCP** | Central Counterparty — interposes itself between parties to eliminate counterparty risk |
| **ISIN** | International Securities Identification Number. 12-character code. E.g. FR0000131104 = BNP Paribas share |
| **LEI** | Legal Entity Identifier — 20-character code identifying a legal entity. Required for regulatory reporting. |
| **SWIFT** | Global interbank messaging network. Banks send payment and securities instructions via SWIFT. |
| **FIX** | Electronic messaging protocol for trading orders. Tag=value format. |
| **EOD** | End Of Day. *"The EOD batch"* = the processing that runs every evening |
| **Batch** | Automated processing launched at a fixed time (often overnight). Spring Batch in Java. |
| **Reference data** | Master table — list of instruments, counterparties, currencies... |
| **Reconciliation** | Comparing two data sources to detect discrepancies |
| **Cut-off time** | The deadline by which a process must complete. Missing a cut-off = serious incident. |
| **Nostro** | Our account at a foreign correspondent bank |
| **Repo** | Short-term financing: sell securities with an agreement to buy them back. Banks use repos daily for liquidity management. |

---

## 12. What you will actually build

Depending on where you sit in the chain, here are concrete examples:

### If you are in front office / trading systems

```java
// Real-time pricing service
public BigDecimal priceOption(OptionParameters params) {
    // Black-Scholes calculation or call to an external pricing engine
    // Priority: speed (sub-millisecond)
    // Always use BigDecimal with RoundingMode.HALF_EVEN — never double
}

// FIX connector to send orders to the exchange
@Component
public class FixConnector extends Application {
    public void onMessage(ExecutionReport report, SessionID sessionID) {
        // The trade just got executed on the exchange
        // Publish to Kafka for booking
        kafkaProducer.publish("executions", toEvent(report));
    }
}
```

### If you are in middle office / risk

```java
// PnL calculation batch — runs every evening
@Bean
public Step pnlCalculationStep() {
    return stepBuilderFactory.get("pnlCalculation")
        .<Position, PnlResult>chunk(500)
        .reader(positionReader())         // reads all positions
        .processor(pnlProcessor())        // prices at today's close
        .writer(pnlWriter())              // writes results
        .build();
}
```

### If you are in back office / settlement

```java
// Generate a SWIFT message for settlement
public SwiftMessage generateMT541(Trade trade) {
    // MT541 = receive against payment instruction
    return SwiftMessage.builder()
        .messageType("541")
        .settlementDate(trade.getSettlementDate())
        .isin(trade.getIsin())
        .quantity(trade.getQuantity())
        .amount(trade.getAmount())
        .build();
}

// Nostro reconciliation batch (runs overnight)
@Scheduled(cron = "0 0 2 * * MON-FRI")
public void reconcileNostroAccounts() {
    List<Statement> bankStatements = swiftParser.parseStatements();
    List<InternalTransaction> internal = transactionRepository.findByDate(today);
    reconciliationService.compare(bankStatements, internal); // produces "breaks"
}
```

### The tech stack you will encounter

```
Java 17/21       → industry standard
Spring Boot      → applications, REST APIs
Spring Batch     → EOD batches (PnL, settlement, reconciliation, reporting)
Spring Integration → data flows between systems
Kafka / IBM MQ   → messaging between front, middle, back
Oracle / Sybase  → databases (Oracle very dominant in French banking)
Redis            → cache for market data (prices, intraday positions)
FIX Protocol     → trading protocol (QuickFIX/J library)
SWIFT            → payment and securities messages
Murex / Calypso  → front-to-back platforms (you integrate with them)
```

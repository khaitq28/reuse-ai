# SaaS Project Ideas

> Curated for Java/Spring Boot + Angular stack with OAuth2, JWT, DDD, TDD, AWS.
> Goal: build reusable patterns that can be applied to future projects.

---

## Idea 1 — Multi-Tenant SaaS Boilerplate ⭐ Most Reusable

**Concept**: A complete template every future project can fork from. Build it once, reuse forever.

**Who Uses It & Why**:

| User | Scenario |
|---|---|
| **Solo developer / indie hacker** | Wants to launch a SaaS but doesn't want to spend 3 months building auth and billing from scratch. Clones this boilerplate, customizes branding, and ships in days. |
| **Small dev agency** | Builds client SaaS products repeatedly. Uses this as their internal starter kit — every new client project forks from it. Auth, roles, and billing are already done. |
| **Startup CTO** | Needs to demo an MVP fast. Uses the FREE plan out of the box, adds Stripe later when users arrive. RBAC lets them control feature access per plan without code changes. |
| **Enterprise IT buyer** | Evaluates the product. Sees SSO (Keycloak + enterprise IdP) is already supported. Signs the ENTERPRISE deal because they can connect their existing Active Directory. |

**Real scenario**: A freelance developer wins a contract to build an HR SaaS. Instead of starting from zero, they fork this boilerplate. In week 1, auth, roles, and Stripe billing are live. They spend the remaining weeks building the actual HR features the client paid for.

**Core Features**:
- Multi-tenancy (schema-per-tenant or row-level isolation)
- OAuth2/OIDC with Keycloak (social login, enterprise SSO)
- JWT with refresh token rotation + theft detection
- RBAC (Role-Based Access Control) with fine-grained permissions
- Subscription plans (FREE / PRO / ENTERPRISE) with Stripe billing
- Audit log (who did what, when)
- User management (invite, onboard, deactivate)

**Why Reuse**: Every SaaS built after this copies the auth, tenant isolation, billing, and user management skeleton. Zero setup cost on future projects.

**Tech Focus**:
- DDD aggregates: `Tenant`, `User`, `Subscription`
- TDD on all auth flows (login, token refresh, lockout)
- Spring Security + Keycloak integration
- Stripe API for subscription billing
- Angular standalone components with route guards per role

**Key Patterns**: Multi-tenancy, OAuth2/PKCE, JWT rotation, RBAC, Outbox for billing events

---

## Idea 2 — Expense & Budget Tracker SaaS ⭐ Finance Domain

**Concept**: Small business expense management with approval workflows, budget limits, and accounting exports. Directly applicable to banking/insurance domain experience.

**Who Uses It & Why**:

| User | Scenario |
|---|---|
| **Employee (submitter)** | Goes on a business trip, pays for hotel and meals with personal card. Takes a photo of each receipt in the app, submits the expense. Tracks status in real time: pending → approved → reimbursed. No more lost paper receipts or chasing the manager by email. |
| **Department manager (approver)** | Gets a notification: "3 expenses pending your approval." Opens the app, sees receipts, amounts, categories. Approves in one click or rejects with a comment. No Excel, no email attachments. |
| **Finance / accounting team** | At month end, exports all approved expenses for the month as CSV/XLSX. Imports directly into their accounting software (Sage, QuickBooks). Done in minutes instead of days of manual reconciliation. |
| **CFO / finance director** | Opens the dashboard: "Marketing department is at 87% of budget with 2 weeks left in the quarter." Gets an alert automatically when any department hits 80%. Can spot overspending before it happens. |
| **HR manager** | Sets up the company: creates departments, assigns managers, sets reimbursement rules (meals capped at €35/day). All automatic from then on. |

**Real scenario**: A 50-person consulting firm has employees submitting expenses via email with scanned receipts. Finance spends 3 days per month reconciling everything manually. They subscribe to this SaaS. Now employees submit via mobile, managers approve in the app, finance exports one file at month end. 3 days of work becomes 30 minutes.

**Core Features**:
- Multi-user company account with departments
- Expense submission → manager approval → accounting export (CSV/XLSX)
- Budget limits per department with alerts
- Monthly/quarterly reports (PDF generation)
- Receipt upload (S3 presigned URL)
- IBAN/SEPA references for reimbursement
- Email + push notifications at each workflow step

**Why Reuse**:
- Approval workflow → Saga choreography pattern
- Document storage → S3 presigned upload pattern
- Notification pipeline → Kafka async notifications
- PDF reports → reusable report generation service
- All patterns from design-system MDs implemented for real

**Tech Focus**:
- DDD: `Expense` aggregate with state machine (DRAFT → SUBMITTED → APPROVED → REIMBURSED)
- Event Sourcing for immutable audit trail
- CQRS: write model for mutations, read model for reports
- TDD: approval rules, budget threshold logic, currency conversion
- Angular: multi-step form, approval inbox, dashboard charts (Chart.js/ApexCharts)

**Key Patterns**: Saga, Event Sourcing, CQRS, S3 upload, Notification system

---

## Idea 3 — Team Task / Project Management SaaS (Jira-lite)

**Concept**: Kanban board + sprint planning for small teams. Real-time collaboration, webhooks, and REST API for integrations.

**Who Uses It & Why**:

| User | Scenario |
|---|---|
| **Developer** | Picks up a task from the backlog, drags it to "In Progress." His teammate in another city sees it move instantly on her screen — no refresh needed. Adds a comment with a code snippet, tags a colleague. All in real time. |
| **Tech lead / scrum master** | Plans the 2-week sprint: drags tasks from backlog to sprint, assigns story points, balances load per developer. During the sprint, monitors progress on the board without having to ask "what are you working on?" |
| **Product manager** | Creates new feature tasks, adds acceptance criteria in the description, attaches a Figma link. Gets notified when the task moves to "Done." No need to attend daily standups just to know the status. |
| **Small startup team (5 people)** | Doesn't want to pay Jira's per-seat pricing (€8.15/seat/month) for a 5-person team. Uses this at €19/month flat. Gets 90% of Jira's features without the complexity. |
| **Freelancer managing client projects** | Creates one workspace per client. Each client has their own board, their own backlog. Client can log in and see progress without calling every day. |

**Real scenario**: A 4-person dev team is coordinating via WhatsApp and a shared Google Doc. Tasks get lost, nobody knows who's doing what. They switch to this tool. One board per project, each person drags their tasks across columns. The team lead sees the full picture at a glance. WhatsApp goes back to memes.

**Core Features**:
- Workspaces (one per company/team)
- Boards with customizable columns (Kanban)
- Tasks: assignments, labels, due dates, attachments, comments
- Real-time updates via WebSocket (live board drag-drop)
- Activity feed per task and per project
- Sprint planning (backlog → sprint → done)
- Slack/email notifications on task events
- REST API + webhook support for external integrations

**Why Reuse**:
- Real-time WebSocket routing pattern (design-system #11)
- Notification pipeline via Kafka (design-system #04)
- Multi-tenant workspace model (reusable from Idea 1)
- Drag-and-drop Angular board component

**Tech Focus**:
- DDD: `Task`, `Sprint`, `Board`, `Workspace` aggregates
- TDD: task lifecycle transitions, sprint capacity rules
- Redis Sorted Sets for board column ordering
- WebSocket (STOMP over SockJS) for live updates
- Angular: CDK drag-drop, reactive forms, lazy-loaded modules

**Key Patterns**: WebSocket fanout, Kafka notification, Redis ordering, multi-tenant

---

## Idea 4 — Document Signing & Vault SaaS

**Concept**: PDF document management with digital signature workflow and immutable audit trail. Directly applicable to insurance/banking contracts.

**Who Uses It & Why**:

| User | Scenario |
|---|---|
| **Insurance broker** | Needs a client to sign a new policy contract. Uploads the PDF, enters the client's email. Client gets a link, opens the document in browser, draws their signature, clicks "Sign." Broker is notified instantly. No printing, no scanning, no post. |
| **Client / signer** | Receives an email: "Please sign your loan agreement." Clicks the link from their phone. Reads the document, signs with their finger on screen. Done in 3 minutes from anywhere. |
| **Legal / compliance team** | 6 months later, regulators ask: "Prove that client X signed contract Y and wasn't coerced." Opens audit trail: signed at 14:32, from IP 92.x.x.x, on iPhone, took 4 minutes to read before signing. Legally defensible. |
| **Real estate agent** | Coordinates signatures from buyer, seller, and notary — in that specific order. Sets up the signing sequence: buyer signs first, then seller sees the signed doc and signs, then notary countersigns. Fully automated, no back-and-forth coordination. |
| **HR department** | Onboards 20 new employees. Uploads employment contracts in bulk. Each new hire gets an email, signs their individual contract. HR dashboard shows: 18/20 signed, 2 pending (auto-reminder sent). |

**Real scenario**: A small insurance agency sends contracts by post, waits for signed copies to return, then scans and files them. Process takes 5–10 days per contract. With this SaaS, the entire cycle is 24 hours. The agency processes 3x more contracts per month with the same headcount.

**Core Features**:
- Upload contracts (PDF) to secure S3 vault
- Send for e-signature to one or multiple signers (ordered or parallel)
- Signature audit trail: who signed, when, from which IP, on which device
- Document versioning (original vs signed)
- Expiry reminders (document unsigned after N days)
- Secure download of signed PDF via time-limited presigned URL
- Integration webhook when document is fully signed

**Why Reuse**:
- S3 presigned upload/download pattern
- Audit trail via Event Sourcing (signature events are immutable facts)
- Notification system (reminders, signed confirmation)
- JWT-secured time-limited download links
- Directly mirrors real-world use at MGP/SG/BNP for contract management

**Tech Focus**:
- DDD: `Document`, `SignatureRequest`, `Signer` aggregates
- Event Sourcing: every signature action is an event (DocumentUploaded, SignatureRequested, DocumentSigned, DocumentExpired)
- TDD: signature order enforcement, expiry logic, access control per signer
- iText / Apache PDFBox for PDF manipulation and signature embedding
- Angular: document viewer (PDF.js), signature pad (canvas-based)

**Key Patterns**: Event Sourcing, presigned URL, notification, audit trail, DDD

---

## Idea 5 — API Monetization Gateway SaaS (for Developers)

**Concept**: A platform where developers publish APIs and consumers access them with usage-based billing. Implements all core design-system patterns in production.

**Who Uses It & Why**:

| User | Scenario |
|---|---|
| **API producer (developer / company)** | Built a weather data API, a currency conversion API, or an AI text analysis API. Wants to sell access to it without building auth, billing, and rate limiting themselves. Lists the API on this platform, sets pricing tiers (FREE / PRO), and earns money per request. |
| **API consumer (developer)** | Needs a currency conversion API for their app. Finds it on the platform, generates an API key in 30 seconds, gets 500 free calls/month on the FREE plan. When their app grows, upgrades to PRO — one click, billing automatic. |
| **Startup CTO** | Their internal microservices need to expose APIs to B2B partners. Uses this platform as an API gateway: one place to manage all partner API keys, monitor usage, enforce rate limits. No bespoke gateway to build and maintain. |
| **Freelancer monetizing a side project** | Built a useful NLP API (e.g., French text summarization). Instead of charging a flat fee, publishes it here with pay-as-you-go pricing. Gets passive income as developers call the API. |
| **Enterprise partner manager** | Gives each B2B partner their own API key with custom rate limits and usage caps. Monitors which partners are close to their quota. Automatically charges overage at month end via Stripe. |

**Real scenario**: A developer builds a real estate valuation API using public data. Instead of building a developer portal, billing system, and key management from scratch (3 months of work), they list it on this platform in a day. 3 months later, 40 developers are using it, 8 on paid plans — passive income with zero infrastructure to manage.

**Core Features**:
- API key management (generate, rotate, revoke)
- Rate limiting per plan (FREE: 100 req/day, PRO: 10k req/day, ENTERPRISE: unlimited)
- Usage metering per API key (counted via Kafka events)
- Billing (Stripe) based on usage tiers at end of month
- Developer portal: OpenAPI docs, sandbox environment, usage analytics
- Analytics dashboard: requests/day, error rate, latency percentiles

**Why Reuse**:
- Rate limiter → design-system #01 (Redis Lua atomic)
- API Gateway pattern → design-system #07
- Payment/billing → design-system #05
- Notification system → design-system #04
- This project IS the design-system patterns implemented end-to-end

**Tech Focus**:
- Redis Lua scripts for atomic rate limit counters
- Kafka for usage event streaming → Flink/aggregate for billing computation
- JWT-based API keys with custom claims (plan, quota)
- DDD: `APIProduct`, `Subscription`, `UsageRecord` aggregates
- Angular: developer portal with API key dashboard, usage charts, billing history

**Key Patterns**: Rate limiting, API Gateway, Metering, Billing, Kafka streaming

---

---

## Idea 6 — Serverless Invoice Processing SaaS (AWS-Native)

**Concept**: Companies upload invoices (PDF/image), AWS extracts data automatically, routes for approval, stores in DynamoDB, and exports to accounting. Fully serverless — zero server management.

**Who Uses It & Why**:

| User | Scenario |
|---|---|
| **Accountant / bookkeeper** | Receives 200 supplier invoices per month by email. Today: opens each PDF, manually types vendor name, amount, date into the accounting system. With this SaaS: forwards invoices to a dedicated email or uploads in bulk. AWS Textract reads everything automatically. They only intervene on low-confidence extractions. Time per invoice drops from 5 minutes to 20 seconds. |
| **Finance manager (approver)** | Gets a Slack/email notification: "Invoice from AWS €4,320 — approve?" Clicks approve or reject directly from the notification. No need to log into any system. |
| **SMB owner** | Doesn't have an accountant on staff. Uploads invoices as they arrive throughout the month. At month end, exports a clean CSV and gives it to their external accountant. The accountant is happy, bills fewer hours. |
| **Accounts payable team** | Sets up approval rules: invoices above €5,000 need CFO approval. Below €5,000, direct manager is enough. Rules run automatically — no manual routing decisions. |
| **External auditor** | Needs to verify that invoice #4521 was approved before payment. Opens the audit trail: uploaded at 09:12, auto-extracted at 09:13, approved by manager at 10:45, exported to accounting at 11:00. Full chain with timestamps. |

**Real scenario**: A 30-person construction company gets invoices from 50+ suppliers. The admin manually processes them between other tasks — invoices pile up, payments get delayed, suppliers complain. This SaaS processes each invoice in under 2 minutes automatically. Late payments drop to zero. Supplier relationships improve.

**Core Features**:
- Upload invoice via presigned S3 URL
- Automatic data extraction: vendor, amount, date, line items (via AWS Textract)
- Confidence scoring: low confidence → flagged for human review
- Approval workflow: SQS queue → Lambda → approver email (SES) → approval link
- Store structured invoice data in DynamoDB
- Export to CSV/XLSX and push to S3 (downloadable via CloudFront)
- Dashboard: CloudWatch metrics, total spend by vendor/month

**AWS Services Used**:
- S3 (upload + export storage)
- Textract (OCR + structured data extraction)
- Lambda (all business logic — no EC2/ECS)
- SQS (approval workflow queue + DLQ)
- DynamoDB (invoice records, approval state)
- SES (approval emails)
- CloudFront (secure file download CDN)
- EventBridge (scheduled monthly export jobs)
- CloudWatch + X-Ray (observability)
- API Gateway (REST endpoints for frontend)
- Cognito (auth — replaces Keycloak entirely)

**Why Fully AWS**:
- No Kubernetes, no Docker Compose, no Keycloak server to maintain
- Pay-per-invocation — almost free at side-project scale
- Textract is unique to AWS — nothing to self-host equivalent

**Tech Focus**:
- Java Lambda handlers (GraalVM native for cold start reduction)
- DDD inside Lambda handlers: `Invoice`, `ApprovalRequest` aggregates
- TDD: Textract parsing rules, confidence thresholds, workflow state transitions
- Infrastructure as Code: AWS CDK (Java) — full stack in one codebase
- Angular frontend → deployed to S3 + CloudFront (no server)

**Key Patterns**: Event-driven serverless, S3 trigger → Lambda, SQS workflow, CDK IaC

---

## Idea 7 — Real-Time IoT Dashboard SaaS (AWS IoT + Kinesis)

**Concept**: A multi-tenant platform where companies connect sensors/devices and visualize real-time metrics. Applicable to insurance telematics, smart building, fleet tracking.

**Who Uses It & Why**:

| User | Scenario |
|---|---|
| **Insurance company (telematics)** | Installs GPS + accelerometer devices in insured vehicles. Monitors driving behavior in real time: harsh braking, speeding, night driving. Calculates a risk score per driver. Offers lower premiums to safe drivers ("pay-how-you-drive" insurance). |
| **Facility manager (smart building)** | Monitors 200 sensors across a building: temperature per room, CO2 levels, water flow, electricity consumption per floor. Gets an alert at 2am: "Server room temperature exceeded 28°C." Calls maintenance before servers overheat. |
| **Fleet operations manager** | Tracks 50 delivery trucks in real time on a map. Sees which are on schedule, which are delayed. Gets an alert when a truck hasn't moved in 30 minutes — driver may need assistance. |
| **Factory floor supervisor** | Monitors production machine vibration and temperature. When a machine shows abnormal vibration patterns, gets an alert before it breaks down. Schedules preventive maintenance. Downtime drops by 40%. |
| **Data analyst** | At end of month, runs Athena queries on the S3 data lake: "Average temperature per room per hour, last 3 months." Feeds results into a BI tool. No ETL pipeline to build — data is already in S3 in Parquet format. |

**Real scenario**: An insurance company wants to launch a telematics product (like Amaguiz or Allianz "Drive better"). Building the IoT ingestion, real-time dashboard, and analytics pipeline in-house would cost 18 months and a team of 5. This SaaS gives them the infrastructure in weeks. They focus on the insurance product logic, not the plumbing.

**Core Features**:
- Device registration and certificate provisioning (AWS IoT Core)
- Real-time telemetry ingestion (MQTT → Kinesis Data Streams)
- Stream processing: aggregations, anomaly detection (Kinesis Data Analytics / Lambda)
- Time-series storage (Amazon Timestream)
- Real-time dashboard: live charts updating via WebSocket (API Gateway WebSocket)
- Alerting: threshold breach → SNS → email/SMS/push
- Historical analytics with Athena queries on S3 data lake

**AWS Services Used**:
- IoT Core (device management, MQTT broker, rules engine)
- Kinesis Data Streams (real-time telemetry ingestion)
- Kinesis Data Firehose (S3 data lake archival)
- Lambda (stream processing, anomaly detection)
- Timestream (time-series DB — purpose-built for IoT metrics)
- API Gateway WebSocket (push live data to Angular frontend)
- SNS (multi-channel alerting: email, SMS, push)
- Athena + S3 (historical analytics — query raw data lake)
- Cognito (multi-tenant auth with device-level claims)
- CDK (full IaC)

**Why Fully AWS**:
- IoT Core + Timestream have no realistic self-hosted equivalent
- Kinesis → Firehose → Athena is the AWS canonical real-time + analytics pipeline
- WebSocket API Gateway removes the need for a running WebSocket server

**Tech Focus**:
- Simulated MQTT device publisher (Java CLI) for local testing
- Lambda in Java for stream processing with windowed aggregations
- DDD: `Device`, `TelemetryStream`, `AlertRule` aggregates (inside Lambda)
- TDD: aggregation windows, alert threshold logic, anomaly detection rules
- Angular: real-time chart with WebSocket subscription (ApexCharts + RxJS)

**Key Patterns**: IoT ingestion, Kinesis streaming, time-series, WebSocket push, data lake

---

## Idea 8 — AI-Powered CV & Job Matching SaaS (AWS Bedrock)

**Concept**: Candidates upload CVs, companies post jobs. AWS Bedrock (Claude) extracts skills, scores match quality, and generates personalized feedback. Fully AWS, no GPU servers.

**Who Uses It & Why**:

| User | Scenario |
|---|---|
| **Job seeker / candidate** | Uploads their CV PDF. Within 60 seconds: sees their skills automatically extracted ("Java, Spring Boot, AWS, 13 years"), gets a match score against open positions ("87% match for Senior Backend Engineer at FinTech Corp"), and reads a gap analysis: "You're missing: Terraform, Go. Consider adding your AWS CDK experience more prominently." Knows exactly where to apply and what to improve. |
| **Recruiter** | Posts a job with required skills and seniority level. Instead of reading 300 CVs, opens a ranked list: top 10 candidates with AI match scores, extracted skills, years of experience. Spends time only on the top matches. Hiring time drops from 6 weeks to 2. |
| **HR manager** | Sets up the company account, defines job templates with standard skill requirements. Every new job posting inherits the template. Consistency across all recruiters in the company. |
| **Career coach** | Uses the platform with their clients: uploads client CV, runs it against target job postings, gets a concrete gap analysis. Builds a personalized upskilling plan: "To get from 65% to 90% match at BNP Paribas, focus on: AWS certification, Kafka basics, French regulatory compliance knowledge." |
| **Candidate (passive job seeker)** | Not actively looking but curious. Uploads CV, sees match scores for various companies. Realizes they're an 82% match at a company they never considered. Applies. Gets the job. |

**Real scenario**: A French ESN (SSII) receives 500 CVs per month for consulting assignments. HR team of 3 spends 60% of their time reading CVs. With this platform, they pre-screen via AI — only read the top 20 CVs flagged per position. The team focuses on interviewing and client relations instead of CV sorting.

**Core Features**:
- CV upload (PDF) → S3 → Lambda → Textract (extract text) → Bedrock (structure skills/experience)
- Job posting with required skills, seniority, domain
- AI match scoring: Bedrock compares CV embedding vs job requirements
- Personalized gap analysis: "You match 80% — missing: Kubernetes, AWS CDK"
- Automated cover letter draft (Bedrock generation, candidate edits in UI)
- Recruiter dashboard: ranked candidate list with AI scores
- Multi-tenant: each company sees only its jobs and candidates

**AWS Services Used**:
- S3 (CV storage)
- Textract (PDF text extraction)
- Bedrock (Claude model — skill extraction, match scoring, gap analysis, generation)
- Lambda (orchestration, no persistent server)
- DynamoDB (candidates, jobs, match scores)
- OpenSearch Serverless (vector search for semantic job matching)
- SQS (async CV processing queue)
- Cognito (candidate + recruiter auth, separate user pools)
- CloudFront + S3 (Angular frontend hosting)
- Step Functions (CV processing pipeline: upload → extract → embed → score → notify)

**Why Fully AWS**:
- Bedrock = managed Claude/Titan — no self-hosted LLM infra
- OpenSearch Serverless vector search — no Pinecone, no Weaviate
- Step Functions orchestrates the ML pipeline without a workflow server

**Tech Focus**:
- Step Functions state machine with Lambda tasks (CDK-defined)
- Bedrock API calls from Java Lambda (AWS SDK v2)
- DDD: `Candidate`, `JobPosting`, `MatchScore` aggregates
- TDD: match scoring rules, skill normalization, Step Functions state transitions
- Angular: CV upload flow, match score visualization, cover letter editor

**Key Patterns**: Bedrock AI, Step Functions pipeline, vector search, serverless ML orchestration

---

## Idea 9 — Compliance & Audit Trail SaaS (AWS-Native, Banking/Insurance Fit)

**Concept**: A tamper-proof audit log platform for regulated industries. Every action is recorded as an immutable event, queryable with full chain-of-custody proof. Built entirely on AWS managed services.

**Who Uses It & Why**:

| User | Scenario |
|---|---|
| **Compliance officer (bank / insurance)** | ACPR auditors arrive and ask: "Show us every access to client Dupont's portfolio between January and March, and prove the log hasn't been altered." Opens the dashboard, filters by client ID and date range, exports PDF report in 2 minutes. The QLDB cryptographic proof is included — the auditor cannot challenge it. Previously this took 2 days of manual log digging. |
| **DPO (Data Protection Officer)** | CNIL sends a GDPR data subject access request: "What data do you hold on citizen Martin, and who accessed it?" The DPO runs a query: all events touching `userId=martin`, sorted by date. Full access history with operator names. Response sent within the 30-day legal deadline with zero stress. |
| **IT Security manager** | Fraud is detected on account #44521. They need to reconstruct exactly what happened in the 10 minutes before. Queries the audit log: "Show all events on account #44521, last 24 hours." Sees that an internal operator accessed it at 14:03, modified the IBAN at 14:05, initiated a transfer at 14:07. Evidence package ready for legal. |
| **SaaS developer (integrating your platform)** | Adds 10 lines of code to their app: calls your API each time a sensitive action occurs. From that moment, every action is logged immutably. They don't need to build, host, or maintain any audit infrastructure themselves. |
| **External auditor (SOX, ISO 27001)** | Needs to certify that the company's IT systems have change controls in place. Logs into the audit platform, runs a report for the audit period: every configuration change, every privilege escalation, every data export. Report is cryptographically signed. Certification done. |

**Real scenario**: A French mutuelle (health insurance) undergoes an ACPR inspection. The inspector asks for a complete access log for 10 specific client files over the past year. Today, their IT team manually extracts logs from 4 different systems, formats them in Excel, and delivers them 3 days later — with gaps and inconsistencies. With this SaaS, it's one query, one PDF, delivered in 5 minutes. The inspector is satisfied. The mutuelle avoids a formal notice.

**Core Features**:
- Ingest audit events from any system via API Gateway + Kinesis
- Immutable storage: Amazon QLDB (quantum ledger — cryptographically verifiable, append-only)
- Query audit history by entity, user, date range
- Compliance reports: GDPR data access log, SOX change log (PDF via Lambda)
- Anomaly detection: unusual access patterns → GuardDuty + SNS alert
- Data retention policy: auto-archive to S3 Glacier after N years
- Multi-tenant: each client's events are isolated and independently verifiable

**AWS Services Used**:
- API Gateway (event ingestion endpoint)
- Kinesis Data Streams (buffer + fan-out for high-volume events)
- QLDB (Amazon Quantum Ledger DB — immutable, cryptographically verifiable audit log)
- Lambda (ingestion, query, report generation)
- S3 + Glacier (long-term archive with lifecycle policies)
- Athena (SQL queries on archived S3 data)
- GuardDuty (anomaly detection on access patterns)
- SNS (compliance alerts)
- Cognito (multi-tenant auth)
- CDK (IaC)

**Why Fully AWS**:
- QLDB is unique to AWS — provides cryptographic proof of record integrity, no self-hosted equivalent
- GuardDuty threat detection is managed — replaces a full SIEM setup
- Glacier lifecycle policies handle compliance retention automatically

**Tech Focus**:
- QLDB Java driver for PartiQL queries
- Lambda-based Kinesis consumer with exactly-once semantics
- DDD: `AuditEvent`, `ComplianceReport`, `RetentionPolicy` aggregates
- TDD: event ordering guarantees, GDPR purge logic, report generation rules
- Angular: audit log explorer with filters, chain-of-custody viewer

**Key Patterns**: Immutable ledger, event ingestion pipeline, compliance reporting, anomaly detection

---

## Comparison Table

| | Reusability | Learning Breadth | Domain Fit | Complexity | AWS Depth |
|---|---|---|---|---|---|
| #1 Multi-Tenant Boilerplate | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | Medium | ⭐⭐ |
| #2 Expense Tracker | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Medium | ⭐⭐ |
| #3 Task Management | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | High | ⭐⭐ |
| #4 Document Signing | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Medium | ⭐⭐ |
| #5 API Gateway SaaS | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | Very High | ⭐⭐ |
| #6 Invoice Processing (serverless) | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Medium | ⭐⭐⭐⭐⭐ |
| #7 IoT Dashboard | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | High | ⭐⭐⭐⭐⭐ |
| #8 AI CV Matching (Bedrock) | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | High | ⭐⭐⭐⭐⭐ |
| #9 Compliance Audit Ledger | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Medium | ⭐⭐⭐⭐⭐ |

---

## Recommended Starting Point

**Option A — Maximum reusability**: Start with **#1 (Boilerplate)** then add **#2** or **#4** as the first real feature set on top.

**Option B — Domain alignment**: Start directly with **#2 (Expense Tracker)** — closest to banking/insurance work, natural DDD boundaries, production-realistic.

**Option C — Skill stretch**: Start with **#3 (Task Management)** — adds WebSocket + real-time Angular to your stack.

**Option D — Go fully AWS serverless**: Start with **#6 (Invoice Processing)** — no EC2, no K8s, no Keycloak. Just Lambda + CDK + managed services. Best for learning AWS deeply while shipping something real.

**Option E — Banking/insurance + AWS compliance**: Start with **#9 (Audit Ledger)** — QLDB is a killer differentiator on a CV, directly maps to your banking/insurance domain, and the whole stack is AWS-native.

**Option F — AI/ML AWS track**: Start with **#8 (CV Matching)** — Bedrock + Step Functions + OpenSearch is the modern AWS AI stack. High signal on a CV for 2025+.

---

## Common Tech Stack

### Ideas #1–#5 (Self-hosted + some AWS)

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.x, Spring Security |
| Auth | Keycloak (OAuth2/OIDC), JWT (RS256) |
| Database | PostgreSQL (primary), Redis (cache/sessions) |
| Messaging | Kafka (async events, notifications) |
| Storage | AWS S3 (documents, uploads) |
| Deploy | Docker, Kubernetes (Helm), GitLab CI |
| Frontend | Angular 17+ (standalone components) |
| Testing | JUnit 5, Mockito, Testcontainers (TDD) |
| Patterns | DDD, TDD, CQRS, Event Sourcing, Saga |

### Ideas #6–#9 (Fully AWS-native)

| Layer | Technology |
|---|---|
| Compute | AWS Lambda (Java, GraalVM native) |
| Auth | Amazon Cognito (user pools, identity pools) |
| Database | DynamoDB, QLDB, Timestream (purpose-built) |
| Messaging | SQS, SNS, Kinesis Data Streams, EventBridge |
| AI/ML | Bedrock (Claude), Textract, OpenSearch Serverless |
| Storage | S3, S3 Glacier (lifecycle archival) |
| Orchestration | Step Functions (state machines), CDK (IaC) |
| Observability | CloudWatch, X-Ray, GuardDuty |
| Frontend | Angular → S3 + CloudFront (no server) |
| API | API Gateway REST + WebSocket |
| Testing | JUnit 5, Mockito, AWS SDK mock clients, CDK assertions |
| Patterns | Event-driven serverless, IaC-first, managed services over self-hosted |
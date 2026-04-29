# SaaS Project Ideas

> Curated for Java/Spring Boot + Angular stack with OAuth2, JWT, DDD, TDD, AWS.
> Goal: build reusable patterns that can be applied to future projects.

---

## Idea 1 — Multi-Tenant SaaS Boilerplate ⭐ Most Reusable

**Concept**: A complete template every future project can fork from. Build it once, reuse forever.

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

## Comparison Table

| | Reusability | Learning Breadth | Domain Fit | Complexity |
|---|---|---|---|---|
| #1 Multi-Tenant Boilerplate | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | Medium |
| #2 Expense Tracker | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Medium |
| #3 Task Management | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | High |
| #4 Document Signing | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Medium |
| #5 API Gateway SaaS | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | Very High |

---

## Recommended Starting Point

**Option A — Maximum reusability**: Start with **#1 (Boilerplate)** then add **#2** or **#4** as the first real feature set on top.

**Option B — Domain alignment**: Start directly with **#2 (Expense Tracker)** — closest to banking/insurance work, natural DDD boundaries, production-realistic.

**Option C — Skill stretch**: Start with **#3 (Task Management)** — adds WebSocket + real-time Angular to your stack.

---

## Common Tech Stack (All Projects)

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
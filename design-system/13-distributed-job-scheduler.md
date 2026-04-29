# Distributed Job Scheduler — Deep Design Guide

## Problem Statement

Design a distributed job scheduler that executes tasks at specified times or on recurring schedules (cron), guaranteeing exactly-once execution even when multiple scheduler instances are running — similar to Quartz Scheduler, AWS EventBridge, Airflow, or Spring Batch.

---

## Why This Problem Matters

Every production system has scheduled work: generate daily reports at 09:00, purge expired records every hour, send weekly digest emails on Sundays. The naive solution — a single machine running a cron job — breaks as soon as you scale to multiple servers or need fault tolerance. This problem is a favorite in interviews because it combines:

- **Distributed coordination**: how do you ensure exactly one server fires each job, not zero (missed) or two (duplicate)?
- **Leader election**: the classic distributed systems problem. Do you use Redis, ZooKeeper, or a database?
- **At-least-once vs. exactly-once**: job execution is not like messaging. A job that runs twice can cause real damage (send email twice, charge payment twice). Exactly-once execution requires both distributed locking AND idempotency.
- **Time reliability**: `scheduled for 09:00` means the job must fire at 09:00 ± a few seconds, not "sometime later today."

**What interviewers are testing**: Whether you understand the distributed locking approaches (Redis SETNX, PostgreSQL FOR UPDATE SKIP LOCKED, ZooKeeper ephemeral nodes), whether you recognize that at-least-once delivery + idempotent workers = effectively exactly-once, and whether you can design a time wheel algorithm.

---

## Key Insight Before Diving In

**The fundamental problem is: you have N scheduler nodes, all capable of firing the same job. How do you guarantee exactly one node fires it?**

There are two complementary approaches:

1. **Leader-based**: elect one scheduler as leader (via Redis SETNX or ZooKeeper); only the leader fires jobs. Simple, but leader becomes single point of failure (though failover is automatic).

2. **Distributed lock at job level**: all schedulers compete for each job individually using `SELECT ... FOR UPDATE SKIP LOCKED`. The first scheduler to acquire the job row's lock wins; others skip. No single leader needed; every scheduler can fire different jobs concurrently.

The second approach (PostgreSQL SKIP LOCKED) is more resilient and scales better. It's the approach used by Sidekiq, Delayed Job, and many modern job queues.

---

## Requirements

### Functional
- Schedule one-time jobs at a specific timestamp
- Recurring jobs via cron expressions (e.g., `0 9 * * MON-FRI` = 9am weekdays)
- Job types: HTTP webhook, Kafka message publish, internal function execution
- Pause / resume / cancel individual jobs
- Manual trigger (run immediately regardless of schedule)
- Retry on failure with configurable backoff strategy
- Job dependency: Job B runs only after Job A succeeds (DAG)
- Execution history, logs, and status per job

### Non-Functional
- Scheduling accuracy: ± 5 seconds (acceptable for business tasks; not for HFT)
- Support 1M scheduled jobs in the system
- No missed executions on node failure (fault tolerant)
- No duplicate executions (exactly-once semantics)
- Horizontally scalable workers (decouple scheduling from execution)

---

## Capacity Estimation

```
Scheduled jobs:
  1M active jobs in DB
  Average: 1000 jobs fire per second (1M jobs / 1000s average interval)
  Peak: 10,000 jobs/second (poorly distributed cron schedules at top-of-hour)

Why peak matters:
  If 1M jobs all have schedule "0 * * * *" (every hour, at :00):
  All 1M fire at the same second → thundering herd
  Solution: jitter, or avoid top-of-hour for bulk schedules

Storage:
  1M job definitions × 2KB = 2GB (fits in PostgreSQL easily)
  Execution history: 1000 executions/sec × 86400 = 86M rows/day × 500B = 43GB/day
  → Partition by day, retain 90 days, archive older → ~3.9TB retained

Scheduling overhead:
  Tick every second: SELECT * FROM jobs WHERE next_run_at <= NOW() ... SKIP LOCKED LIMIT 1000
  With index on (next_run_at, status): this query takes < 10ms on 1M rows
  1000 jobs/query × 1 query/second = 1000 jobs/sec throughput
```

---

## Architecture

```
                        ┌──────────────────────────────┐
                        │   Scheduler Admin API         │
                        │   POST /jobs (create)         │
                        │   PUT  /jobs/{id}/pause       │
                        │   POST /jobs/{id}/trigger     │
                        └──────────────┬───────────────┘
                                       │ writes to
                                       ▼
                        ┌──────────────────────────────┐
                        │         PostgreSQL            │
                        │   jobs table (definitions)   │
                        │   job_executions (history)   │
                        │   outbox (event relay)       │
                        └──────────────┬───────────────┘
                                       │ polled by
         ┌─────────────────────────────┼─────────────────────────────┐
         ▼                             ▼                             ▼
┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
│  Scheduler      │          │  Scheduler      │          │  Scheduler      │
│  Node 1         │          │  Node 2         │          │  Node 3         │
│                 │          │                 │          │                 │
│  Polls DB every │          │  Polls DB every │          │  Polls DB every │
│  1 second       │          │  1 second       │          │  1 second       │
│  FOR UPDATE     │          │  FOR UPDATE     │          │  FOR UPDATE     │
│  SKIP LOCKED    │          │  SKIP LOCKED    │          │  SKIP LOCKED    │
└────────┬────────┘          └────────┬────────┘          └────────┬────────┘
         │                            │                            │
         └────────────────────────────┴────────────────────────────┘
                                       │ enqueue to
                                       ▼
                        ┌──────────────────────────────┐
                        │      Kafka / RabbitMQ        │
                        │   (job execution queue)      │
                        └──────────────┬───────────────┘
                                       │ consumed by
         ┌─────────────────────────────┼─────────────────────────────┐
         ▼                             ▼                             ▼
┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
│  Worker Node 1  │          │  Worker Node 2  │          │  Worker Node N  │
│  (executes job) │          │  (executes job) │          │  (executes job) │
└─────────────────┘          └─────────────────┘          └─────────────────┘
```

**Key design decision**: Schedulers and Workers are separate. Schedulers determine **what** to run and **when**. Workers determine **how** to run it. This allows independent scaling: heavy job workloads → scale workers; many scheduled jobs → scale schedulers.

---

## PostgreSQL SKIP LOCKED — The Core Mechanism

This is how multiple scheduler nodes avoid firing the same job twice. It's a PostgreSQL feature specifically designed for job queue patterns.

```sql
-- The scheduler tick (runs every 1 second on each scheduler node)
BEGIN;

-- Find due jobs, lock them, skip any already locked by another scheduler node
SELECT id, job_type, payload, next_run_at, cron_expression, max_retries
FROM jobs
WHERE status = 'ACTIVE'
  AND next_run_at <= NOW()
ORDER BY next_run_at ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;  -- ← This is the magic

-- SKIP LOCKED means: if a row is already locked by another transaction, skip it
-- Without SKIP LOCKED: this query would WAIT for the other transaction (deadlock risk)
-- With SKIP LOCKED: this query gets the next available unlocked row immediately

-- For each found job:
-- 1. Create an execution record
INSERT INTO job_executions (job_id, status, worker_id, started_at, attempt)
VALUES (?, 'RUNNING', ?, NOW(), ?);

-- 2. Update next_run_at (for recurring jobs) or set status=INACTIVE (for one-time jobs)
UPDATE jobs
SET next_run_at = calculate_next_run(cron_expression, NOW()),
    last_run_at = NOW(),
    status = CASE WHEN cron_expression IS NULL THEN 'COMPLETED' ELSE 'ACTIVE' END
WHERE id = ?;

-- 3. Enqueue to Kafka/RabbitMQ for worker execution
-- (via outbox pattern to guarantee delivery)
INSERT INTO outbox (topic, payload) VALUES ('jobs.execute', ?);

COMMIT;
-- Lock released → other scheduler nodes can now see this job as "already updated"
```

**Why this works**: The `FOR UPDATE SKIP LOCKED` ensures only ONE scheduler node locks each job row. The other nodes skip that row and take the next available one. No job is locked by two schedulers simultaneously. The UPDATE inside the same transaction advances `next_run_at`, so even if the same scheduler re-queries 1 second later, the job won't appear as due again.

---

## Time Wheel Algorithm (Alternative to DB Polling)

For low-latency scheduling (< 1 second accuracy), a time wheel is more efficient than polling a DB.

```
Concept: a circular buffer where each slot = 1 unit of time (e.g., 1 second)
         Wheel size determines maximum schedulable duration per rotation

Example: 60-second wheel (60 slots, slot[i] = jobs to run at second i of current minute)

Tick every second:
  current_slot = (current_slot + 1) % 60
  jobs = wheel[current_slot]
  for each job in jobs:
    submit(job)       → worker queue
    if job.recurring:
      reschedule(job) → place in wheel at (current_slot + interval) % 60

Hierarchical Time Wheel (for long-range scheduling):
  Wheel 1: 60 slots (seconds)
  Wheel 2: 60 slots (minutes)
  Wheel 3: 24 slots (hours)
  Wheel 4: 365 slots (days)

  Job scheduled for 2026-06-01 09:30:00:
    Place in Day wheel slot[June 1]
    On cascade: move to Hour wheel slot[9]
    On cascade: move to Minute wheel slot[30]
    On cascade: move to Second wheel slot[0]
    On tick: execute

Memory: 60 + 60 + 24 + 365 = 509 slots
        Each slot = linked list of job pointers
        Total: ~509 × avg_jobs_per_slot × 16 bytes/pointer → trivial
```

**Time Wheel vs DB Polling**:
- Time Wheel: nanosecond accuracy, pure in-memory, requires full state reload on restart
- DB Polling: second accuracy, survives restarts naturally, simpler to implement
- **For most business scheduling (daily reports, hourly tasks): DB polling is sufficient and simpler**
- **For high-frequency scheduling (< 1 second): Time Wheel is necessary**

---

## Cron Expression Parsing

```
Cron: "0 9 * * MON-FRI"
       │ │ │ │    └── Day of week: MON-FRI
       │ │ │ └─────── Month: * (any)
       │ │ └───────── Day of month: * (any)
       │ └─────────── Hour: 9
       └───────────── Minute: 0

Extended cron: "*/5 * * * *" = every 5 minutes
               "0 9,18 * * *" = at 9am and 6pm daily
               "0 0 1 * *" = midnight on first day of month

Java library: cron-utils or CronExpression from Spring Framework

Next execution calculation:
  CronExpression expr = CronExpression.parse("0 9 * * MON-FRI");
  LocalDateTime nextRun = expr.next(LocalDateTime.now());
  // nextRun = 2026-04-30T09:00:00 (next weekday 9am)
```

---

## Retry Strategy

```
Job fails at attempt 1:
  → Create new execution record (attempt=2, status=PENDING_RETRY)
  → Calculate retry_at = NOW() + backoff(attempt)
  → UPDATE jobs SET next_run_at = retry_at

Exponential backoff with jitter:
  delay(attempt) = min(base × 2^(attempt-1) + random(0, 1000ms), max_delay)
  attempt 1: 60s + jitter
  attempt 2: 120s + jitter
  attempt 3: 240s + jitter
  attempt 4: 480s + jitter
  attempt 5: 960s + jitter → FAILED permanently, alert ops

Why jitter?
  Without jitter: all retrying jobs hit the external API at exactly the same time
  With jitter: retries are spread over a window, reducing thundering herd on recovery

Max attempts: configurable per job type
  CRITICAL_PAYMENT: 10 retries over 24 hours
  MARKETING_EMAIL: 3 retries over 1 hour
  REPORT_GENERATION: 5 retries over 8 hours
```

---

## Job Dependency (DAG)

Some jobs must run after others complete. Example: Job B depends on Job A.

```sql
CREATE TABLE job_dependencies (
  dependent_job_id  UUID REFERENCES jobs(id),
  depends_on_job_id UUID REFERENCES jobs(id),
  PRIMARY KEY (dependent_job_id, depends_on_job_id)
);

-- Job B depends on Job A
INSERT INTO job_dependencies VALUES ('job-B', 'job-A');

-- Job B's initial status: 'WAITING'
-- When Job A completes:
UPDATE jobs SET status = 'ACTIVE', next_run_at = NOW()
WHERE id IN (
  SELECT dependent_job_id FROM job_dependencies
  WHERE depends_on_job_id = 'job-A'
    AND dependent_job_id NOT IN (
      SELECT dependent_job_id FROM job_dependencies jd
      JOIN job_executions je ON jd.depends_on_job_id = je.job_id
      WHERE je.status != 'SUCCESS'  -- only activate if ALL dependencies succeeded
    )
);
```

---

## Data Model

```sql
CREATE TABLE jobs (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name             VARCHAR(255) NOT NULL,
  description      TEXT,
  type             VARCHAR(50) NOT NULL,   -- CRON, ONE_TIME, INTERVAL
  cron_expression  VARCHAR(100),           -- "0 9 * * MON-FRI", null for ONE_TIME
  interval_sec     INT,                    -- for INTERVAL type
  next_run_at      TIMESTAMPTZ NOT NULL,   -- when to fire next
  last_run_at      TIMESTAMPTZ,
  status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE, PAUSED, COMPLETED, FAILED, WAITING (for dependency)
  max_retries      INT DEFAULT 3,
  retry_delay_sec  INT DEFAULT 60,
  timeout_sec      INT DEFAULT 300,        -- kill execution if exceeds this
  payload          JSONB,                  -- job-specific config (URL, topic, etc.)
  created_by       VARCHAR(100),
  created_at       TIMESTAMPTZ DEFAULT NOW(),
  updated_at       TIMESTAMPTZ DEFAULT NOW()
);

-- Critical index: the scheduler polls this constantly
CREATE INDEX idx_jobs_due ON jobs (next_run_at, status)
  WHERE status IN ('ACTIVE', 'WAITING');

CREATE TABLE job_executions (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id       UUID NOT NULL REFERENCES jobs(id),
  status       VARCHAR(20) NOT NULL,  -- RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED
  worker_id    VARCHAR(100),          -- which worker node ran it
  attempt      INT DEFAULT 1,
  started_at   TIMESTAMPTZ DEFAULT NOW(),
  finished_at  TIMESTAMPTZ,
  duration_ms  INT,
  output       TEXT,                  -- truncated stdout/result
  error        TEXT,                  -- error message + stack trace
  retry_of     UUID REFERENCES job_executions(id)  -- chain retries
) PARTITION BY RANGE (started_at);   -- daily partitions
```

---

## Monitoring

```
Key metrics:
  jobs_due_on_time_rate      → % of jobs fired within 5s of scheduled time
  jobs_executed_total{status=success|failed|timeout}
  job_execution_duration_p99{job_name}
  scheduler_lag_seconds      → how far behind is the scheduler?
  worker_queue_depth         → how many jobs are waiting to be executed?
  retry_rate                 → % of jobs requiring retry (high = systemic failure)

Alerts:
  scheduler_lag > 30s         → scheduler node down or DB slow, page on-call
  retry_rate > 10%            → systemic job failure, investigate
  worker_queue_depth > 10,000 → workers can't keep up, scale workers
  job_execution_duration_p99 > timeout → jobs timing out, investigate payload
  any critical job FAILED     → immediate alert (not just metrics threshold)
```

---

## API Design

```
POST /jobs
{
  "name": "daily-report",
  "type": "CRON",
  "cron": "0 9 * * MON-FRI",
  "payload": {
    "type": "HTTP",
    "url": "https://reports-service/api/generate",
    "method": "POST",
    "timeout_sec": 120
  },
  "max_retries": 3
}
→ 201: { "job_id": "...", "next_run_at": "2026-04-30T09:00:00Z" }

GET  /jobs                    ?status=ACTIVE&page=0&size=50
GET  /jobs/{jobId}
GET  /jobs/{jobId}/executions ?limit=20&status=FAILED
PUT  /jobs/{jobId}/pause
PUT  /jobs/{jobId}/resume
DELETE /jobs/{jobId}
POST /jobs/{jobId}/trigger    → run immediately (ignores schedule)
```

---

## Tech Stack

- **API**: Java 17, Spring Boot
- **Database**: PostgreSQL (SKIP LOCKED, ACID, partitioned execution history)
- **Cron Parsing**: Spring's CronExpression or cron-utils library
- **Worker Queue**: Kafka (high volume) or RabbitMQ (simpler routing)
- **Outbox Relay**: Debezium (CDC-based) or polling relay
- **Leader Election (alternative)**: Redis SETNX if using leader-based model
- **Distributed Lock**: PostgreSQL row lock (SKIP LOCKED) — no additional infrastructure
- **Monitoring**: Prometheus + Grafana + PagerDuty alerting

---

## Interview Q&A

**Q1: How does PostgreSQL SKIP LOCKED prevent duplicate job execution?**

A: `SELECT ... FOR UPDATE SKIP LOCKED` atomically locks the selected rows within a transaction. Any other transaction attempting to lock the same row is not blocked — it simply doesn't see that row (SKIP LOCKED skips already-locked rows). This means Scheduler Node 1 and Scheduler Node 2 can execute the same query simultaneously, and each will get a different set of job rows — no overlap. Within each transaction, the scheduler updates `next_run_at` and creates an execution record. When the transaction commits, the lock is released. The next tick of any scheduler sees the job's `next_run_at` in the future (already updated) — it won't be selected as due. This provides distributed mutual exclusion without any external coordination service (no Redis, no ZooKeeper required).

---

**Q2: What's the difference between leader election (Redis SETNX) and per-job locking (PostgreSQL SKIP LOCKED)? When would you use each?**

A: **Leader election** (one scheduler runs, others are standbys): simpler logic — only one node runs the tick loop. Failover: Sentinel watches the leader; when it fails, another node becomes leader in 10-30 seconds. During failover window: no jobs fire (gap). Good for: simple systems, when you want predictable single-point-of-control. **Per-job locking** (all schedulers run, competing per job): more complex but more robust. No single point of failure — if one scheduler dies, others pick up immediately. Good for: high-availability requirements, large job volumes where one scheduler can't process fast enough. In production, per-job locking with PostgreSQL SKIP LOCKED is preferred because it provides immediate failover (no leader election delay) and natural load distribution across scheduler nodes.

---

**Q3: A job runs at 09:00 every weekday. The server crashes at 08:59 and restarts at 09:05. Does the job run?**

A: Yes, with the polling approach. When the server restarts at 09:05, it polls the DB and finds jobs where `next_run_at <= NOW()` and `next_run_at >= NOW() - grace_period`. The `next_run_at` for this job was set to 09:00. At 09:05, the query `next_run_at <= NOW()` returns this job as overdue. The scheduler fires it at 09:05 instead of 09:00 — a 5-minute delay, but no missed execution. The `grace_period` determines how far back to look (e.g., 1 hour). Jobs missed by more than the grace period are either: (a) fired immediately with a "late" flag, or (b) skipped (their window has passed). This depends on business requirements — a "daily report at 9am" should be generated late rather than skipped; a "send coupon before 9:30am" might be skipped if it's now 10am.

---

**Q4: How would you implement a "max concurrency" constraint — only 3 instances of a particular job should run simultaneously?**

A: Add a semaphore using database counters: (1) `jobs.max_concurrent` column (e.g., 3). (2) `jobs.running_count` column (atomic increment/decrement). (3) Before acquiring a job in SKIP LOCKED: add condition `running_count < max_concurrent`. (4) On job start: `UPDATE jobs SET running_count = running_count + 1`. (5) On job finish: `UPDATE jobs SET running_count = running_count - 1`. Race condition protection: use `FOR UPDATE` when updating running_count to prevent two workers both seeing count=2 (below limit=3) and both incrementing to 3, then 4. Better: use a dedicated `job_semaphores` table with a row-level lock per job for the check-and-increment.

---

**Q5: A cron job takes 90 minutes to run, but it's scheduled every 60 minutes. What happens?**

A: Without protection: a second instance starts while the first is still running → two instances running simultaneously → likely data corruption. Solutions: (1) **Overlap prevention**: before firing, check if `current_execution.status = 'RUNNING'`. If yes, skip this tick (mark in job history as "skipped: previous still running"). (2) **Max concurrency = 1**: use the semaphore approach (Q4) with `max_concurrent=1`. The scheduler simply won't fire a second instance. (3) **Timeout-based kill**: if a job runs longer than `timeout_sec`, mark it as TIMEOUT and kill the worker. The next scheduled run can proceed. (4) **Investigate root cause**: a job consistently running over its schedule is a performance regression — alert on `p99 duration > schedule_interval`.

---

**Q6: How do you handle timezone-aware scheduling? A job must run at 9am in France, accounting for DST.**

A: Store cron expressions with timezone metadata: `{ "cron": "0 9 * * MON-FRI", "timezone": "Europe/Paris" }`. Next run calculation must account for the timezone:

```java
ZoneId tz = ZoneId.of(job.getTimezone()); // "Europe/Paris"
ZonedDateTime now = ZonedDateTime.now(tz);
CronExpression expr = CronExpression.parse(job.getCron());
ZonedDateTime nextRun = expr.next(now);
Instant nextRunUTC = nextRun.toInstant();
// Store nextRunUTC in DB; all DB operations are in UTC
```

DST handling: at the spring-forward transition (2am → 3am), a job scheduled for 2:30am simply doesn't exist that day. `CronExpression.next()` correctly jumps to the next valid time. At fall-back (3am → 2am), a job at 2:30am runs twice. Application must be idempotent to handle this.

---

**Q7: How would you design the system to scale to 100M scheduled jobs?**

A: At 100M jobs, the single `jobs` table becomes a challenge for the polling query: (1) **Partition by next_run_at**: monthly partitions. The scheduler only queries the current month's partition — dramatically reduces index size and query time. (2) **Shard by job category**: put high-frequency jobs (every minute) in a "fast" table, daily jobs in a "slow" table. Poll them at different rates. (3) **Bloom filter**: maintain a Bloom filter of job IDs due in the next 60 seconds — quick pre-check before hitting DB. (4) **Pre-materialize next-run schedule**: a background job computes "all jobs due in the next 5 minutes" and loads them into Redis. Scheduler reads from Redis, not DB. DB is only the persistence layer. This caches the scheduler's work in a fast store and removes DB from the hot path.
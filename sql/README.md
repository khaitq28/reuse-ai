# SQL — From Basic JOINs to Advanced Queries

A progressive study guide using a realistic 6-table HR schema (inspired by Oracle Live SQL).
All SQL is written for **PostgreSQL 16**.

---

## Schema

Six related tables: `COUNTRY` → `LOCATION` → `DEPARTMENT` → `EMPLOYEE` → `JOB_HISTORY`, and `JOB`

See full schema, ERD diagram, DDL, and sample data → [01_schema.md](01_schema.md)

---

## Exercises — 15 total, basic to advanced

| File | Exercises | Topics |
|------|-----------|--------|
| [02_exercises_basic.md](02_exercises_basic.md)           | 1 – 4   | SELECT, WHERE, ORDER BY, COUNT + GROUP BY |
| [03_exercises_joins.md](03_exercises_joins.md)           | 5 – 8   | INNER JOIN chain, self-join, LEFT JOIN, job_history |
| [04_exercises_aggregates.md](04_exercises_aggregates.md) | 9 – 12  | MIN / MAX / AVG, HAVING, multi-level GROUP BY |
| [05_exercises_advanced.md](05_exercises_advanced.md)     | 13 – 15 | Correlated subquery, CTE + RANK(), LAG() |

---

## Run locally with Docker (PostgreSQL 16)

```bash
# Start — ready in ~10 seconds
docker compose up -d

# Check it's healthy
docker compose ps

# Watch logs
docker logs -f postgres-hr
```

**Connect with any SQL client** (DBeaver, DataGrip, pgAdmin, IntelliJ):

| Setting | Value |
|---------|-------|
| Host | `localhost` |
| Port | `5432` |
| Database | `hrdb` |
| User | `hr` |
| Password | `hr123` |

**Connect via psql inside the container:**
```bash
docker exec -it postgres-hr psql -U hr -d hrdb
```

**Useful psql commands once connected:**
```sql
\dt                  -- list tables
\d employee          -- describe employee table
SELECT * FROM employee;
```

**Stop / reset:**
```bash
docker compose down          # stop, keep data
docker compose down -v       # stop + wipe all data (re-runs init scripts on next start)
```

> The `init/` scripts run **once** on first start (alphabetical order: `01_tables.sql` then `02_data.sql`).
> To re-apply after changes: `docker compose down -v && docker compose up -d`

---

## Run in a SQL playground (no Docker)

Paste the DDL + data from `01_schema.md` directly into:
- [db-fiddle.com](https://www.db-fiddle.com) — choose **PostgreSQL 16**, no changes needed
- [sqlfiddle.com](http://sqlfiddle.com) — choose **PostgreSQL**

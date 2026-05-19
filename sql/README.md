# SQL — From Basic JOINs to Advanced Queries

A progressive study guide using a realistic 6-table HR schema (inspired by Oracle Live SQL).

---

## Schema

Six related tables: `COUNTRY` → `LOCATION` → `DEPARTMENT` → `EMPLOYEE` → `JOB_HISTORY`, and `JOB`

See full schema, ERD diagram, DDL, and sample data → [01_schema.md](01_schema.md)

---

## Exercises — 15 total, basic to advanced

| File | Exercises | Topics |
|------|-----------|--------|
| [02_exercises_basic.md](02_exercises_basic.md) | 1 – 4 | SELECT, WHERE, ORDER BY, COUNT + GROUP BY |
| [03_exercises_joins.md](03_exercises_joins.md) | 5 – 8 | INNER JOIN chain, self-join, LEFT JOIN, job_history |
| [04_exercises_aggregates.md](04_exercises_aggregates.md) | 9 – 12 | MIN / MAX / AVG, HAVING, multi-level GROUP BY |
| [05_exercises_advanced.md](05_exercises_advanced.md) | 13 – 15 | Correlated subquery, CTE + RANK(), LAG() |

---

## How to run

Paste the DDL from `01_schema.md` into any SQL playground:
- [db-fiddle.com](https://www.db-fiddle.com) — choose PostgreSQL
- [sqlfiddle.com](http://sqlfiddle.com)
- Local: PostgreSQL, MySQL, SQLite

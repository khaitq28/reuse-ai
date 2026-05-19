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
| User | `hr123` |
| Password | `hr123` |

**Connect via psql inside the container:**
```bash
docker exec -it postgres-hr psql -U hr123 -d hrdb
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


## Example 

```sql
select round(avg(e.salary),0), d.department_name from employee e
    inner join department d on e.department_id = d.department_id
group by e.department_id, d.department_name;



select count(e.employee_id), m.first_name || ' ' || m.last_name as manager
from employee e join employee m on e.manager_id = m.employee_id
group by e.manager_id, m.first_name, m.last_name;

select * from employee where manager_id is null;

select count(*), manager_id from employee group by manager_id having manager_id is not null;

select * from employee;
select * from job;

--job_title	min_sal	max_sal	avg_sal

select j.job_title, min(e.salary), max(e.salary), avg(e.salary) from employee e
inner join job j on e.job_id = j.job_id
group by j.job_title;


-- departments whose average employee salary is above the overall company average.
-- Show department name, city, and the average salary.

select round(avg(e.salary),1) as sal, d.department_name as dep, l.city
from employee e inner join public.department d on e.department_id = d.department_id
join location l on l.location_id = d.location_id
group by d.department_name, l.city
having avg(e.salary) > (select avg(salary) from employee)


select count(employee_id), d.department_name from employee e
inner join department d on e.department_id = d.department_id
group by d.department_name having count(employee_id) > 2

--Aggregate all the way up: sum every employee's salary, grouped by country.
-- country_name	total_salary	nb_employees
select  c.country_name, avg(salary), count(*) from employee e
inner join department d on e.department_id = d.department_id
inner join location l on d.location_id = l.location_id
inner join public.country c on l.country_id = c.country_id
group by c.country_id

--Find employees whose salary is strictly above the average salary of their department.
-- Show employee name, department, their salary, and the department average.

```
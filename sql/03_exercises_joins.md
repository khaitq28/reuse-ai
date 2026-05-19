# 03 — Exercises: JOINs  *(PostgreSQL)*

> **DB:** PostgreSQL 16 — run via Docker (see [README](README.md)) or [db-fiddle.com](https://www.db-fiddle.com)
> Tables: see [01_schema.md](01_schema.md)
> Covers: `INNER JOIN`, `LEFT JOIN`, multi-table chains, self-join.

---

## Exercise 5 — Employee with department, city, and country

Write a query that returns every employee with their department name, city, and country.

| first_name | last_name | department_name  | city          | country_name  |
|------------|-----------|------------------|---------------|---------------|
| Alice      | Martin    | Engineering      | San Francisco | United States |
| …          | …         | …                | …             | …             |

**Hint:** 4 tables — `employee` → `department` → `location` → `country`

<details>
<summary>Solution</summary>

```sql
SELECT
    e.first_name,
    e.last_name,
    d.department_name,
    l.city,
    c.country_name
FROM employee   e
JOIN department d ON e.department_id = d.department_id
JOIN location   l ON d.location_id   = l.location_id
JOIN country    c ON l.country_id    = c.country_id
ORDER BY e.last_name;
```

</details>

---

## Exercise 6 — Each employee with their manager's name (self-join)

Return employee full name, job title, and their manager's full name.
Employees with no manager should show `'(no manager)'`.

| employee        | job_title           | manager       |
|-----------------|---------------------|---------------|
| Frank Williams  | Software Developer  | Alice Martin  |
| Alice Martin    | Manager             | (no manager)  |
| …               | …                   | …             |

**Hint:** join `employee` to itself — `e` for the employee, `m` for the manager.
Use `LEFT JOIN` so top-level managers are not excluded.

<details>
<summary>Solution</summary>

```sql
SELECT
    e.first_name || ' ' || e.last_name                           AS employee,
    j.job_title,
    COALESCE(m.first_name || ' ' || m.last_name, '(no manager)') AS manager
FROM employee   e
JOIN job        j  ON e.job_id     = j.job_id
LEFT JOIN employee m  ON e.manager_id = m.employee_id
ORDER BY manager, employee;
```

</details>

---

## Exercise 7 — Departments that have NO employees

Use a `LEFT JOIN` to find departments with zero employees assigned.

| department_name | city   |
|-----------------|--------|
| …               | …      |

*(With the current sample data this returns 0 rows — try deleting an employee
temporarily or adding a new department to test it.)*

**Hint:** `LEFT JOIN employee … WHERE employee_id IS NULL`

<details>
<summary>Solution</summary>

```sql
SELECT d.department_name, l.city
FROM department d
LEFT JOIN location l  ON d.location_id   = l.location_id
LEFT JOIN employee e  ON d.department_id = e.department_id
WHERE e.employee_id IS NULL;
```

</details>

---

## Exercise 8 — Career path: current job + past jobs (job_history)

Show each employee's current job title and any previous jobs from `job_history`.
Employees who never changed roles should still appear (past_job = `'(none)'`).

| employee        | current_job         | past_job       | from_date  | to_date    |
|-----------------|---------------------|----------------|------------|------------|
| Frank Williams  | Software Developer  | Analyst        | 2017-06-01 | 2019-10-31 |
| Alice Martin    | Manager             | (none)         | NULL       | NULL       |
| …               | …                   | …              | …          | …          |

**Hint:** `LEFT JOIN job_history`, then another `LEFT JOIN job` for the old job title.

<details>
<summary>Solution</summary>

```sql
SELECT
    e.first_name || ' ' || e.last_name      AS employee,
    j_cur.job_title                          AS current_job,
    COALESCE(j_old.job_title, '(none)')      AS past_job,
    jh.start_date                            AS from_date,
    jh.end_date                              AS to_date
FROM employee     e
JOIN job          j_cur ON e.job_id        = j_cur.job_id
LEFT JOIN job_history jh   ON e.employee_id  = jh.employee_id
LEFT JOIN job         j_old ON jh.job_id     = j_old.job_id
ORDER BY employee, jh.start_date;
```

</details>

---

## What's next?

→ [04_exercises_aggregates.md](04_exercises_aggregates.md) — GROUP BY, AVG, COUNT, HAVING

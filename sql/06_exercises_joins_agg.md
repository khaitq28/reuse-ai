# 06 — Exercises: JOINs + Aggregates (mixed)  *(PostgreSQL)*

> **DB:** PostgreSQL 16 — run via Docker (see [README](README.md))
> Tables: see [01_schema.md](01_schema.md)
> Level: same as files 03 & 04 — multi-table JOINs combined with GROUP BY / HAVING.

---

## Exercise 16 — How many employees does each manager have?

Return each manager's full name and the number of people they manage.
Only show managers who have at least 1 report. Order by count descending.

| manager          | nb_reports |
|------------------|------------|
| Alice Martin     | 5          |
| Bob Johnson      | 2          |
| …                | …          |

**Hint:** self-join `employee` — `e` for reports, `m` for the manager.

<details>
<summary>Solution</summary>

```sql
SELECT
    m.first_name || ' ' || m.last_name AS manager,
    COUNT(e.employee_id)               AS nb_reports
FROM employee e
JOIN employee m ON e.manager_id = m.employee_id
GROUP BY m.employee_id, m.first_name, m.last_name
ORDER BY nb_reports DESC;
```

</details>

---

## Exercise 17 — Employees whose salary exceeds their job's max_salary

Some employees are paid above the official range defined in the `job` table.
Return their name, job title, salary, and the job's max_salary.

| employee      | job_title | salary | max_salary |
|---------------|-----------|--------|------------|
| …             | …         | …      | …          |

*(May return 0 rows with current data — try `UPDATE employee SET salary = 130000 WHERE employee_id = 100` to test.)*

**Hint:** `JOIN job` and filter `WHERE e.salary > j.max_salary`.

<details>
<summary>Solution</summary>

```sql
SELECT
    e.first_name || ' ' || e.last_name AS employee,
    j.job_title,
    e.salary,
    j.max_salary
FROM employee e
JOIN job j ON e.job_id = j.job_id
WHERE e.salary > j.max_salary
ORDER BY e.salary DESC;
```

</details>

---

## Exercise 18 — Number of employees per country

Count employees by country (chain through department → location → country).
Order by count descending.

| country_name  | nb_employees |
|---------------|-------------|
| United States | 9           |
| …             | …           |

<details>
<summary>Solution</summary>

```sql
SELECT
    c.country_name,
    COUNT(e.employee_id) AS nb_employees
FROM employee   e
JOIN department d ON e.department_id = d.department_id
JOIN location   l ON d.location_id   = l.location_id
JOIN country    c ON l.country_id    = c.country_id
GROUP BY c.country_id, c.country_name
ORDER BY nb_employees DESC;
```

</details>

---

## Exercise 19 — Departments where the lowest salary is below 60 000

Return department name, city, and the minimum salary in that department.
Only show departments where the minimum is **below 60 000**.

| department_name  | city     | min_salary |
|------------------|----------|------------|
| Sales            | Paris    | 47000      |
| …                | …        | …          |

**Hint:** `HAVING MIN(e.salary) < 60000`

<details>
<summary>Solution</summary>

```sql
SELECT
    d.department_name,
    l.city,
    MIN(e.salary) AS min_salary
FROM employee   e
JOIN department d ON e.department_id = d.department_id
JOIN location   l ON d.location_id   = l.location_id
GROUP BY d.department_id, d.department_name, l.city
HAVING MIN(e.salary) < 60000
ORDER BY min_salary;
```

</details>

---

## Exercise 20 — For each job title: how many employees currently hold it, and how many held it in the past?

Combine `employee` (current) and `job_history` (past assignments) to count both.

| job_title           | current_count | history_count |
|---------------------|---------------|---------------|
| Software Developer  | 5             | 2             |
| HR Specialist       | 2             | 2             |
| …                   | …             | …             |

**Hint:** Two separate `GROUP BY` subqueries (or CTEs), joined on `job_id`.

<details>
<summary>Solution</summary>

```sql
SELECT
    j.job_title,
    COUNT(DISTINCT e.employee_id)  AS current_count,
    COUNT(DISTINCT jh.employee_id) AS history_count
FROM job j
LEFT JOIN employee    e  ON e.job_id  = j.job_id
LEFT JOIN job_history jh ON jh.job_id = j.job_id
GROUP BY j.job_id, j.job_title
ORDER BY current_count DESC;
```

</details>

---

## Exercise 21 — Average salary per region (Americas, Europe, Asia)

Group all the way up to `country.region`.

| region   | nb_employees | avg_salary |
|----------|-------------|------------|
| Americas | 9           | …          |
| Europe   | 4           | …          |
| Asia     | 2           | …          |

<details>
<summary>Solution</summary>

```sql
SELECT
    c.region,
    COUNT(e.employee_id)    AS nb_employees,
    ROUND(AVG(e.salary), 0) AS avg_salary
FROM employee   e
JOIN department d ON e.department_id = d.department_id
JOIN location   l ON d.location_id   = l.location_id
JOIN country    c ON l.country_id    = c.country_id
GROUP BY c.region
ORDER BY avg_salary DESC;
```

</details>

---

## Exercise 22 — Employees hired in the same year as another employee (same year, different person)

Find all pairs of employees hired in the same year.
Return year, and both employee names. Do not show a person paired with themselves.

| year | employee_1    | employee_2   |
|------|---------------|--------------|
| 2021 | Iris Scott    | Jack Adams   |
| 2021 | Iris Scott    | Karine Moreau|
| …    | …             | …            |

**Hint:** self-join on `EXTRACT(YEAR FROM hire_date)`, and add `e1.employee_id < e2.employee_id` to avoid duplicates.

<details>
<summary>Solution</summary>

```sql
SELECT
    EXTRACT(YEAR FROM e1.hire_date)            AS year,
    e1.first_name || ' ' || e1.last_name       AS employee_1,
    e2.first_name || ' ' || e2.last_name       AS employee_2
FROM employee e1
JOIN employee e2
  ON EXTRACT(YEAR FROM e1.hire_date) = EXTRACT(YEAR FROM e2.hire_date)
 AND e1.employee_id < e2.employee_id
ORDER BY year, employee_1;
```

</details>

---

## Exercise 23 — Departments with no job_history records

Find departments that have **never** appeared in `job_history`.

| department_name | city          |
|-----------------|---------------|
| …               | …             |

**Hint:** `LEFT JOIN job_history … WHERE job_history.department_id IS NULL`

<details>
<summary>Solution</summary>

```sql
SELECT
    d.department_name,
    l.city
FROM department d
JOIN location   l  ON d.location_id   = l.location_id
LEFT JOIN job_history jh ON d.department_id = jh.department_id
WHERE jh.department_id IS NULL
ORDER BY d.department_name;
```

</details>

---

## What's next?

→ [05_exercises_advanced.md](05_exercises_advanced.md) — Correlated subqueries, CTEs, RANK(), LAG()

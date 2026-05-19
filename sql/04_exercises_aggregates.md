# 04 — Exercises: Aggregates & GROUP BY  *(PostgreSQL)*

> **DB:** PostgreSQL 16 — run via Docker (see [README](README.md)) or [db-fiddle.com](https://www.db-fiddle.com)
> Tables: see [01_schema.md](01_schema.md)
> Covers: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `GROUP BY`, `HAVING`.

---

## Exercise 9 — Min, max, and average salary per job title

Return job_title, min_salary, max_salary from the `job` table is **not** the answer —
query **actual** employee salaries from the `employee` table.

| job_title           | min_sal | max_sal | avg_sal |
|---------------------|---------|---------|---------|
| Manager             | 90000   | 110000  | 97000   |
| Software Developer  | 58000   | 72000   | 65750   |
| …                   | …       | …       | …       |

**Concepts:** `MIN`, `MAX`, `ROUND(AVG(…))`, `JOIN`, `GROUP BY`

<details>
<summary>Solution</summary>

```sql
SELECT
    j.job_title,
    MIN(e.salary)          AS min_sal,
    MAX(e.salary)          AS max_sal,
    ROUND(AVG(e.salary),0) AS avg_sal
FROM employee e
JOIN job      j ON e.job_id = j.job_id
GROUP BY j.job_id, j.job_title
ORDER BY avg_sal DESC;
```

</details>

---

## Exercise 10 — Departments above the company average salary

Return departments whose average employee salary is **above** the overall company average.
Show department name, city, and the average salary.

| department_name  | city          | avg_salary |
|------------------|---------------|------------|
| Engineering      | San Francisco | …          |
| …                | …             | …          |

**Concepts:** `HAVING`, scalar subquery `(SELECT AVG(salary) FROM employee)`

<details>
<summary>Solution</summary>

```sql
SELECT
    d.department_name,
    l.city,
    ROUND(AVG(e.salary), 0) AS avg_salary
FROM employee   e
JOIN department d ON e.department_id = d.department_id
JOIN location   l ON d.location_id   = l.location_id
GROUP BY d.department_id, d.department_name, l.city
HAVING AVG(e.salary) > (SELECT AVG(salary) FROM employee)
ORDER BY avg_salary DESC;
```

</details>

---

## Exercise 11 — Departments with more than 2 employees

Return only departments that have **strictly more than 2** employees.
Show department name and the employee count.

| department_name | nb_employees |
|-----------------|-------------|
| Engineering     | 6           |
| …               | …           |

**Concepts:** `COUNT(*)`, `HAVING COUNT(*) > 2`

<details>
<summary>Solution</summary>

```sql
SELECT
    d.department_name,
    COUNT(*) AS nb_employees
FROM employee   e
JOIN department d ON e.department_id = d.department_id
GROUP BY d.department_id, d.department_name
HAVING COUNT(*) > 2
ORDER BY nb_employees DESC;
```

</details>

---

## Exercise 12 — Total salary cost per country

Aggregate all the way up: sum every employee's salary, grouped by country.

| country_name  | total_salary | nb_employees |
|---------------|-------------|--------------|
| United States | …           | …            |
| …             | …           | …            |

**Hint:** chain joins employee → department → location → country, then GROUP BY country.

<details>
<summary>Solution</summary>

```sql
SELECT
    c.country_name,
    SUM(e.salary)  AS total_salary,
    COUNT(*)       AS nb_employees
FROM employee   e
JOIN department d ON e.department_id = d.department_id
JOIN location   l ON d.location_id   = l.location_id
JOIN country    c ON l.country_id    = c.country_id
GROUP BY c.country_id, c.country_name
ORDER BY total_salary DESC;
```

</details>

---

## What's next?

→ [05_exercises_advanced.md](05_exercises_advanced.md) — Subqueries, CTEs, Window functions

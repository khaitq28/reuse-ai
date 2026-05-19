# 02 — Exercises: Basic SELECT & Filtering

> Tables: see [01_schema.md](01_schema.md)
> No JOINs needed here — single-table queries only.

---

## Exercise 1 — List all employees sorted by salary

Write a query that returns every employee's full name, job_id, and salary,
**ordered from highest to lowest salary**.

| first_name | last_name | job_id | salary |
|------------|-----------|--------|--------|
| Alice      | Martin    | MGR    | 110000 |
| …          | …         | …      | …      |

<details>
<summary>Solution</summary>

```sql
SELECT first_name, last_name, job_id, salary
FROM employee
ORDER BY salary DESC;
```

</details>

---

## Exercise 2 — Employees with salary between 50 000 and 80 000

Return first_name, last_name, salary for employees whose salary is in that range,
ordered by salary.

<details>
<summary>Solution</summary>

```sql
SELECT first_name, last_name, salary
FROM employee
WHERE salary BETWEEN 50000 AND 80000
ORDER BY salary;
```

</details>

---

## Exercise 3 — Employees hired in 2021 or later

Return full name and hire_date, ordered by hire_date ascending.

<details>
<summary>Solution</summary>

```sql
SELECT first_name, last_name, hire_date
FROM employee
WHERE hire_date >= '2021-01-01'
ORDER BY hire_date;
```

</details>

---

## Exercise 4 — How many employees are there per job_id?

Return job_id and the count, ordered by count descending.

| job_id | nb_employees |
|--------|-------------|
| DEV    | 5           |
| MGR    | 5           |
| …      | …           |

**Concepts:** `COUNT`, `GROUP BY`, `ORDER BY`

<details>
<summary>Solution</summary>

```sql
SELECT job_id, COUNT(*) AS nb_employees
FROM employee
GROUP BY job_id
ORDER BY nb_employees DESC;
```

</details>

---

## What's next?

→ [03_exercises_joins.md](03_exercises_joins.md) — INNER JOIN, LEFT JOIN, self-join

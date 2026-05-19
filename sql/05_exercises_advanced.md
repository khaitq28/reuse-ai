# 05 — Exercises: Subqueries, CTEs & Window Functions

> Tables: see [01_schema.md](01_schema.md)
> Covers: correlated subqueries, `WITH` (CTE), `RANK()`, `ROW_NUMBER()`, `LAG()`.

---

## Exercise 13 — Employees earning more than their own department's average

Find employees whose salary is **strictly above** the average salary of their department.
Show employee name, department, their salary, and the department average.

| employee       | department_name | salary | dept_avg |
|----------------|-----------------|--------|----------|
| Alice Martin   | Engineering     | 110000 | 78800    |
| …              | …               | …      | …        |

**Concept:** correlated subquery — the inner `SELECT AVG` references the outer row's `department_id`.

<details>
<summary>Solution</summary>

```sql
SELECT
    e.first_name || ' ' || e.last_name      AS employee,
    d.department_name,
    e.salary,
    ROUND((
        SELECT AVG(e2.salary)
        FROM employee e2
        WHERE e2.department_id = e.department_id
    ), 0) AS dept_avg
FROM employee   e
JOIN department d ON e.department_id = d.department_id
WHERE e.salary > (
    SELECT AVG(e2.salary)
    FROM employee e2
    WHERE e2.department_id = e.department_id
)
ORDER BY d.department_name, e.salary DESC;
```

</details>

---

## Exercise 14 — Rank employees by salary within each department (CTE + RANK)

Use a **CTE** to compute the rank, then return only the **top 2** earners per department.

| department_name | employee       | salary | rnk |
|-----------------|----------------|--------|-----|
| Engineering     | Alice Martin   | 110000 | 1   |
| Engineering     | Frank Williams | 72000  | 2   |
| Finance         | David Lee      | 90000  | 1   |
| …               | …              | …      | …   |

**Concepts:**
- `WITH cte AS (…)` — Common Table Expression
- `RANK() OVER (PARTITION BY department_id ORDER BY salary DESC)`
- Filter on the CTE result: `WHERE rnk <= 2`

<details>
<summary>Solution</summary>

```sql
WITH ranked AS (
    SELECT
        e.employee_id,
        e.first_name || ' ' || e.last_name  AS employee,
        e.salary,
        d.department_name,
        RANK() OVER (
            PARTITION BY e.department_id
            ORDER BY e.salary DESC
        ) AS rnk
    FROM employee   e
    JOIN department d ON e.department_id = d.department_id
)
SELECT department_name, employee, salary, rnk
FROM ranked
WHERE rnk <= 2
ORDER BY department_name, rnk;
```

</details>

---

## Exercise 15 — Salary evolution: compare each employee to the previous hire (LAG)

Using window function `LAG`, show each employee's salary alongside the salary of the
**previously hired** employee (ordered by hire_date), and compute the difference.

| hire_date  | employee       | salary | prev_salary | diff   |
|------------|----------------|--------|-------------|--------|
| 2015-03-01 | Alice Martin   | 110000 | NULL        | NULL   |
| 2016-07-15 | Bob Johnson    | 95000  | 110000      | -15000 |
| 2017-01-20 | Claire Dupont  | 98000  | 95000       | +3000  |
| …          | …              | …      | …           | …      |

**Concepts:**
- `LAG(salary) OVER (ORDER BY hire_date)` — value from the previous row
- `salary - LAG(salary) OVER (…)` for the difference

<details>
<summary>Solution</summary>

```sql
SELECT
    e.hire_date,
    e.first_name || ' ' || e.last_name                         AS employee,
    e.salary,
    LAG(e.salary) OVER (ORDER BY e.hire_date)                  AS prev_salary,
    e.salary - LAG(e.salary) OVER (ORDER BY e.hire_date)       AS diff
FROM employee e
ORDER BY e.hire_date;
```

</details>

---

## Recap — all 15 exercises

| # | File | Topic |
|---|------|-------|
| 1–4  | [02_exercises_basic.md](02_exercises_basic.md)      | SELECT, WHERE, ORDER BY, COUNT + GROUP BY |
| 5–8  | [03_exercises_joins.md](03_exercises_joins.md)      | INNER JOIN chain, self-join, LEFT JOIN, job_history |
| 9–12 | [04_exercises_aggregates.md](04_exercises_aggregates.md) | MIN/MAX/AVG, HAVING, multi-level GROUP BY |
| 13   | [05_exercises_advanced.md](05_exercises_advanced.md) | Correlated subquery |
| 14   | [05_exercises_advanced.md](05_exercises_advanced.md) | CTE + RANK() window function |
| 15   | [05_exercises_advanced.md](05_exercises_advanced.md) | LAG() window function |

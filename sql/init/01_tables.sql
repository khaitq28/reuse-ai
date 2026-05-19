-- =============================================================
-- HR Schema — DDL (PostgreSQL)
-- Runs once as user HR on first container start.
-- =============================================================

-- ── Drop in reverse dependency order ─────────────────────────
DROP TABLE IF EXISTS job_history;
DROP TABLE IF EXISTS employee;
DROP TABLE IF EXISTS department;
DROP TABLE IF EXISTS job;
DROP TABLE IF EXISTS location;
DROP TABLE IF EXISTS country;

-- ── 1. COUNTRY ───────────────────────────────────────────────
CREATE TABLE country (
    country_id   INT          PRIMARY KEY,
    country_name VARCHAR(60)  NOT NULL,
    region       VARCHAR(30)
);

-- ── 2. LOCATION ──────────────────────────────────────────────
CREATE TABLE location (
    location_id    INT          PRIMARY KEY,
    city           VARCHAR(50)  NOT NULL,
    street_address VARCHAR(100),
    country_id     INT          REFERENCES country(country_id)
);

-- ── 3. JOB ───────────────────────────────────────────────────
CREATE TABLE job (
    job_id     VARCHAR(10) PRIMARY KEY,
    job_title  VARCHAR(50) NOT NULL,
    min_salary INT,
    max_salary INT
);

-- ── 4. DEPARTMENT  (manager_id FK added after EMPLOYEE) ──────
CREATE TABLE department (
    department_id   INT         PRIMARY KEY,
    department_name VARCHAR(50) NOT NULL,
    location_id     INT         REFERENCES location(location_id),
    manager_id      INT         -- FK added via ALTER after employee exists
);

-- ── 5. EMPLOYEE ──────────────────────────────────────────────
CREATE TABLE employee (
    employee_id   INT         PRIMARY KEY,
    first_name    VARCHAR(30),
    last_name     VARCHAR(40) NOT NULL,
    hire_date     DATE        NOT NULL,
    job_id        VARCHAR(10) REFERENCES job(job_id),
    salary        INT,
    department_id INT         REFERENCES department(department_id),
    manager_id    INT         REFERENCES employee(employee_id)    -- self-join
);

-- ── 6. JOB_HISTORY  (composite PK) ───────────────────────────
CREATE TABLE job_history (
    employee_id   INT         REFERENCES employee(employee_id),
    start_date    DATE,
    end_date      DATE,
    job_id        VARCHAR(10) REFERENCES job(job_id),
    department_id INT         REFERENCES department(department_id),
    PRIMARY KEY (employee_id, start_date),
    CONSTRAINT chk_jh_dates CHECK (end_date >= start_date)
);

-- ── Add circular FK: department.manager_id → employee ────────
ALTER TABLE department
    ADD CONSTRAINT fk_dept_manager
    FOREIGN KEY (manager_id) REFERENCES employee(employee_id);

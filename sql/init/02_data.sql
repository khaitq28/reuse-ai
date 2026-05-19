-- =============================================================
-- HR Schema — Sample Data (PostgreSQL)
-- =============================================================

-- ── COUNTRY ───────────────────────────────────────────────────
INSERT INTO country VALUES (1, 'United States',  'Americas');
INSERT INTO country VALUES (2, 'France',          'Europe');
INSERT INTO country VALUES (3, 'United Kingdom',  'Europe');
INSERT INTO country VALUES (4, 'Japan',           'Asia');

-- ── LOCATION ──────────────────────────────────────────────────
INSERT INTO location VALUES (1100, 'San Francisco', '100 Market St',    1);
INSERT INTO location VALUES (1200, 'New York',      '200 Broadway',     1);
INSERT INTO location VALUES (1300, 'Paris',         '10 Rue de Rivoli', 2);
INSERT INTO location VALUES (1400, 'London',        '1 Canary Wharf',   3);
INSERT INTO location VALUES (1500, 'Tokyo',         '5 Shinjuku Blvd',  4);

-- ── JOB ───────────────────────────────────────────────────────
INSERT INTO job VALUES ('MGR',   'Manager',            60000, 120000);
INSERT INTO job VALUES ('DEV',   'Software Developer', 45000,  95000);
INSERT INTO job VALUES ('HR',    'HR Specialist',      35000,  65000);
INSERT INTO job VALUES ('ANLST', 'Analyst',            40000,  80000);
INSERT INTO job VALUES ('SA',    'Sales Account Rep',  30000,  70000);

-- ── DEPARTMENT  (manager_id NULL for now, set after employees) ─
INSERT INTO department (department_id, department_name, location_id) VALUES (10, 'Engineering',    1100);
INSERT INTO department (department_id, department_name, location_id) VALUES (20, 'Human Resources',1200);
INSERT INTO department (department_id, department_name, location_id) VALUES (30, 'Sales',          1300);
INSERT INTO department (department_id, department_name, location_id) VALUES (40, 'Finance',        1400);
INSERT INTO department (department_id, department_name, location_id) VALUES (50, 'R&D',            1500);

-- ── EMPLOYEE  (managers first — manager_id = NULL) ────────────
INSERT INTO employee VALUES (100,'Alice',  'Martin',  '2015-03-01','MGR',  110000,10, NULL);
INSERT INTO employee VALUES (101,'Bob',    'Johnson', '2016-07-15','MGR',   95000,20, NULL);
INSERT INTO employee VALUES (102,'Claire', 'Dupont',  '2017-01-20','MGR',   98000,30, NULL);
INSERT INTO employee VALUES (103,'David',  'Lee',     '2018-05-10','MGR',   90000,40, NULL);
INSERT INTO employee VALUES (104,'Emiko',  'Tanaka',  '2019-09-01','MGR',   92000,50, NULL);
INSERT INTO employee VALUES (105,'Frank',  'Williams','2019-11-03','DEV',   72000,10, 100);
INSERT INTO employee VALUES (106,'Grace',  'Kim',     '2020-02-14','DEV',   68000,10, 100);
INSERT INTO employee VALUES (107,'Hugo',   'Bernard', '2020-06-30','DEV',   65000,10, 100);
INSERT INTO employee VALUES (108,'Iris',   'Scott',   '2021-01-11','HR',    52000,20, 101);
INSERT INTO employee VALUES (109,'Jack',   'Adams',   '2021-04-22','HR',    48000,20, 101);
INSERT INTO employee VALUES (110,'Karine', 'Moreau',  '2021-07-01','SA',    55000,30, 102);
INSERT INTO employee VALUES (111,'Luca',   'Ferrari', '2022-03-15','SA',    47000,30, 102);
INSERT INTO employee VALUES (112,'Mia',    'Patel',   '2022-08-20','ANLST', 61000,40, 103);
INSERT INTO employee VALUES (113,'Nathan', 'Brown',   '2023-01-05','DEV',   58000,50, 104);
INSERT INTO employee VALUES (114,'Olivia', 'Chen',    '2023-05-17','ANLST', 59000,10, 100);

-- ── Set department managers ────────────────────────────────────
UPDATE department SET manager_id = 100 WHERE department_id = 10;
UPDATE department SET manager_id = 101 WHERE department_id = 20;
UPDATE department SET manager_id = 102 WHERE department_id = 30;
UPDATE department SET manager_id = 103 WHERE department_id = 40;
UPDATE department SET manager_id = 104 WHERE department_id = 50;

-- ── JOB_HISTORY ───────────────────────────────────────────────
INSERT INTO job_history VALUES (105, '2017-06-01', '2019-10-31', 'ANLST', 40);
INSERT INTO job_history VALUES (106, '2018-03-01', '2020-01-31', 'HR',    20);
INSERT INTO job_history VALUES (108, '2019-05-01', '2020-12-31', 'SA',    30);
INSERT INTO job_history VALUES (110, '2016-09-01', '2021-06-30', 'HR',    20);
INSERT INTO job_history VALUES (112, '2020-01-15', '2022-07-31', 'DEV',   10);
INSERT INTO job_history VALUES (114, '2021-07-01', '2023-04-30', 'SA',    30);

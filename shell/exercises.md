## Shell Exercises — Log File Analysis

Log file used: `errors.log`
Format: `date;ip;level;message`

---

### Exercise 1 — Count errors per IP

Write a script `ex1.sh` that reads `errors.log` and prints how many `ERROR` lines each IP address has, sorted from most to least.

Expected output:
```
=== Errors per IP ===
      5 192.168.1.10
      3 172.16.0.3
      2 192.168.1.22
```

**Hints:**
- Filter `ERROR` lines with `grep`
- Extract field 2 (IP) with `cut -d';' -f2`
- Count with `sort | uniq -c | sort -nr`

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash

LOG_FILE="errors.log"

echo "=== Errors per IP ==="

grep 'ERROR' "$LOG_FILE" \
  | cut -d';' -f2 \
  | sort \
  | uniq -c \
  | sort -nr
```

</details>

---

### Exercise 2 — Log health check

Write a script `ex2.sh` that counts the total number of `ERROR` lines in `errors.log` and prints a status:

- `CRITICAL` if errors >= 8
- `WARNING` if errors >= 5
- `OK` if errors < 5

Expected output:
```
=== Log Health Check ===
Errors found: 11
Status: CRITICAL
```

**Hints:**
- Count with `grep -c 'ERROR'`
- Use `if [ $VAR -ge $THRESHOLD ]` for numeric comparison

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash

LOG_FILE="errors.log"
WARN_THRESHOLD=5
CRITICAL_THRESHOLD=8

echo "=== Log Health Check ==="

ERROR_COUNT=$(grep -c 'ERROR' "$LOG_FILE")

echo "Errors found: $ERROR_COUNT"

if [ "$ERROR_COUNT" -ge "$CRITICAL_THRESHOLD" ]; then
  echo "Status: CRITICAL"
elif [ "$ERROR_COUNT" -ge "$WARN_THRESHOLD" ]; then
  echo "Status: WARNING"
else
  echo "Status: OK"
fi
```

</details>

---

### Exercise 3 — Daily error report

Write a script `ex3.sh` that reads `errors.log` and prints:
1. Number of errors per date (sorted by date)
2. The single most frequent error message

Expected output:
```
=== Errors per Day ===
      2 2024-01-15
      2 2024-01-16
      3 2024-01-17
      4 2024-01-18

=== Top Error Message ===
timeout connecting to DB
```

**Hints:**
- Extract field 1 (date) and field 4 (message) with `cut -d';' -f1`
- Use `sort | uniq -c | sort -nr | head -1` to get the top one
- Extract just the message text with `awk '{ $1=""; print $0 }' | xargs`

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash

LOG_FILE="errors.log"

echo "=== Errors per Day ==="

grep 'ERROR' "$LOG_FILE" \
  | cut -d';' -f1 \
  | sort \
  | uniq -c

echo ""
echo "=== Top Error Message ==="

grep 'ERROR' "$LOG_FILE" \
  | cut -d';' -f4 \
  | sort \
  | uniq -c \
  | sort -nr \
  | head -1 \
  | awk '{ $1=""; print $0 }' \
  | xargs
```

</details>

---

### Exercise 4 — Filter by level (script argument)

Write a script `ex4.sh` that accepts a log level as argument and prints all matching lines plus a total count.

```bash
./ex4.sh ERROR
./ex4.sh INFO
```

Expected output for `./ex4.sh ERROR`:
```
2024-01-15;192.168.1.10;ERROR;timeout connecting to DB
...
Total ERROR lines: 11
```

**Hints:**
- Access the first argument with `$1`
- Check if empty: `if [ -z "$1" ]`
- Exit with error code: `exit 1`
- Filter by field 3: `awk -F';' -v level="$LEVEL" '$3 == level'`

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash

LOG_FILE="errors.log"
LEVEL=$1

if [ -z "$LEVEL" ]; then
  echo "Usage: ./ex4.sh <LEVEL>"
  exit 1
fi

awk -F';' -v level="$LEVEL" '$3 == level { print }' "$LOG_FILE"

COUNT=$(awk -F';' -v level="$LEVEL" '$3 == level' "$LOG_FILE" | wc -l | xargs)
echo "Total $LEVEL lines: $COUNT"
```

</details>

---

### Exercise 5 — Read log line by line (while loop)

Write a script `ex5.sh` that reads `errors.log` line by line. For each `ERROR` line, print an alert:

```
=== Alerts ===
ALERT [192.168.1.10] : timeout connecting to DB
ALERT [192.168.1.10] : timeout connecting to DB
...
```

**Hints:**
- Loop syntax: `while IFS=';' read -r date ip level msg; do ... done < "$LOG_FILE"`
- `IFS=';'` splits each line on `;` into the 4 variables automatically
- Check level inside the loop: `if [ "$level" = "ERROR" ]`

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash

LOG_FILE="errors.log"

echo "=== Alerts ==="

while IFS=';' read -r date ip level msg; do
  if [ "$level" = "ERROR" ]; then
    echo "ALERT [$ip] : $msg"
  fi
done < "$LOG_FILE"
```

</details>

---

### Exercise 6 — Functions: full summary report

Write a script `ex6.sh` with a reusable function `count_level` that counts lines for any given level. Use it to print a summary.

Expected output:
```
=== Summary Report ===
ERROR : 11
INFO  : 4
Total : 15
```

**Hints:**
- Function syntax: `my_func() { ...; }`
- Call and capture result: `RESULT=$(my_func "ERROR")`
- Arithmetic: `TOTAL=$(( A + B ))`

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash

LOG_FILE="errors.log"

count_level() {
  awk -F';' -v level="$1" '$3 == level' "$LOG_FILE" | wc -l | xargs
}

ERROR_COUNT=$(count_level "ERROR")
INFO_COUNT=$(count_level "INFO")
TOTAL=$(( ERROR_COUNT + INFO_COUNT ))

echo "=== Summary Report ==="
echo "ERROR : $ERROR_COUNT"
echo "INFO  : $INFO_COUNT"
echo "Total : $TOTAL"
```

</details>

---

### Exercise 7 — sed: clean and transform a log file

File: `errors.log` — format: `date;ip;level;message`

Tasks in `ex7.sh`:
1. Replace all `ERROR` with `[ERROR]`
2. Print only lines from `2024-01-18`
3. Remove the IP column visually (strip `;ip;` → `;`)
4. Add prefix `LOGLINE: ` to every line

**Key commands:** `s/old/new/g`, `-n /pattern/p`, `s/^/prefix/`

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash
LOG_FILE="errors.log"

echo "=== 1. Replace ERROR with [ERROR] ==="
sed 's/ERROR/[ERROR]/g' "$LOG_FILE"

echo ""
echo "=== 2. Show only lines from 2024-01-18 ==="
sed -n '/^2024-01-18/p' "$LOG_FILE"

echo ""
echo "=== 3. Remove the IP column ==="
sed 's/;[^;]*//' "$LOG_FILE"

echo ""
echo "=== 4. Add prefix LOGLINE: ==="
sed 's/^/LOGLINE: /' "$LOG_FILE"
```

</details>

---

### Exercise 8 — sed: work with data.csv and users.csv

Files: `data.csv` (`product;quantity;price`), `users.csv` (`name;role;status`)

Tasks in `ex8.sh`:
1. Print `data.csv` without the header line
2. Replace `inactive` with `DISABLED` in `users.csv`
3. Print only lines 2 to 4 of `data.csv` (line range)
4. Delete all lines containing `viewer` from `users.csv`

**Key commands:** `1d`, `s/old/new/`, `-n '2,4p'`, `/word/d`

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash

echo "=== 1. Without header ==="
sed '1d' data.csv

echo ""
echo "=== 2. inactive → DISABLED ==="
sed 's/inactive/DISABLED/' users.csv

echo ""
echo "=== 3. Lines 2 to 4 ==="
sed -n '2,4p' data.csv

echo ""
echo "=== 4. Remove viewer lines ==="
sed '/viewer/d' users.csv
```

</details>

---

### Exercise 9 — awk: extract columns and filter rows

Files: `users.csv` (`name;role;status`), `data.csv` (`product;quantity;price`)

Tasks in `ex9.sh`:
1. Print only the name column from `users.csv` (skip header)
2. Print name and status of `active` users
3. Print products with quantity > 100
4. Print each user as `User: <name> is <status>`

**Key concepts:** `-F';'`, `NR > 1`, `$3 == "active"`, `$2 > 100`, `print "text", $1`

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash

echo "=== 1. Name column ==="
awk -F';' 'NR > 1 { print $1 }' users.csv

echo ""
echo "=== 2. Active users ==="
awk -F';' 'NR > 1 && $3 == "active" { print $1, $3 }' users.csv

echo ""
echo "=== 3. Products quantity > 100 ==="
awk -F';' 'NR > 1 && $2 > 100 { print $1 }' data.csv

echo ""
echo "=== 4. User label ==="
awk -F';' 'NR > 1 { print "User:", $1, "is", $3 }' users.csv
```

</details>

---

### Exercise 10 — awk: count, sum, and compute with BEGIN/END

Files: `data.csv` (`product;quantity;price`), `users.csv` (`name;role;status`)

Tasks in `ex10.sh`:
1. Count total users (excluding header)
2. Count how many users are `active`
3. Total quantity of all products
4. Find the most expensive product (highest price)
5. Print a price list with a header using `BEGIN`

**Key concepts:** `END { print NR }`, `count++`, `sum += $2`, `max` tracking, `BEGIN { print "header" }`, `printf`

<details>
<summary>▶ Solution</summary>

```bash
#!/bin/bash

echo "=== 1. Total users ==="
awk -F';' 'NR > 1 { count++ } END { print count }' users.csv

echo ""
echo "=== 2. Active users ==="
awk -F';' '$3 == "active" { count++ } END { print count }' users.csv

echo ""
echo "=== 3. Total quantity ==="
awk -F';' 'NR > 1 { sum += $2 } END { print "Total quantity:", sum }' data.csv

echo ""
echo "=== 4. Most expensive ==="
awk -F';' 'NR > 1 && $3 > max { max = $3; name = $1 } END { print name, max }' data.csv

echo ""
echo "=== 5. Price list ==="
awk -F';' 'BEGIN { print "=== Product Price List ===" } NR > 1 { printf "%-12s %s\n", $1, $3 }' data.csv
```

</details>

---

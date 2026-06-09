#!/bin/bash

# Exercise 7 — sed: clean and transform a log file
# File: errors.log
# Format: date;ip;level;message

LOG_FILE="errors.log"

echo "=== 1. Replace ERROR with [ERROR] ==="
# TODO: use sed to replace all occurrences of ERROR with [ERROR]
# Expected: 2024-01-15;192.168.1.10;[ERROR];timeout connecting to DB

 sed 's/ERROR/[ERROR]/g' errors.log

echo ""
echo "=== 2. Show only lines from 2024-01-18 ==="
# TODO: use sed -n to print only lines that start with 2024-01-18
# Hint: use address /pattern/p with -n


echo ""
echo "=== 3. Remove the IP column (field 2) visually ==="
# TODO: use sed to delete the second ;....; block — replace ";IP;" with ";"
# Hint: s/;[^;]*;//  removes the first occurrence of ;something;
# Expected: 2024-01-15;ERROR;timeout connecting to DB

cut -d ";" -f1,3,4,5,6 errors.log

echo ""
echo "=== 4. Add prefix LOGLINE: to every line ==="
# TODO: use sed to add "LOGLINE: " at the beginning of each line
# Hint: s/^/PREFIX/

 sed 's/^/LOGLINE:/' errors.log

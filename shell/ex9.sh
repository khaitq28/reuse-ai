#!/bin/bash

# Exercise 9 — awk: extract columns and filter rows
# Files: users.csv, data.csv
# users.csv format: name;role;status
# data.csv format:  product;quantity;price

echo "=== 1. Print only the name column from users.csv (skip header) ==="
# TODO: use awk with -F';' to print field 1, skip line 1 with NR > 1
# Expected:
# john
# alice
# ...


echo ""
echo "=== 2. Print name and status of active users ==="
# TODO: use awk to filter lines where field 3 == "active", print field 1 and 3
# Expected:
# john active
# alice active
# ...


echo ""
echo "=== 3. Print products with quantity > 100 ==="
# TODO: use awk on data.csv, filter where field 2 > 100, print field 1
# Expected:
# apple
# cherry
# grape


echo ""
echo "=== 4. Print each user with a label ==="
# TODO: use awk to print: "User: <name> is <status>"
# Expected:
# User: john is active
# User: alice is active
# ...
# Hint: awk -F';' 'NR > 1 { print "User:", $1, "is", $3 }'

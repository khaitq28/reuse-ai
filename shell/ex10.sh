#!/bin/bash

# Exercise 10 — awk: count, sum, and compute with BEGIN/END
# Files: data.csv, users.csv
# data.csv format:  product;quantity;price  (line 1 = header)
# users.csv format: name;role;status        (line 1 = header)

echo "=== 1. Count total number of users (excluding header) ==="
# TODO: use awk on users.csv — count lines where NR > 1, print at END
# Expected: 7


echo ""
echo "=== 2. Count how many users are 'active' ==="
# TODO: use awk to count lines where field 3 == "active"
# Expected: 5


echo ""
echo "=== 3. Total quantity of all products ==="
# TODO: use awk on data.csv — skip header, sum field 2, print at END
# Expected: Total quantity: 660


echo ""
echo "=== 4. Most expensive product (highest price) ==="
# TODO: use awk to track the max value of field 3 and which product ($1) has it
# Hint: compare $3 > max, then update max and name
# Expected: elderberry 8.5


echo ""
echo "=== 5. Print a summary with BEGIN header ==="
# TODO: use awk with BEGIN to print a title, then print each product and price
# Expected:
# === Product Price List ===
# apple       1.5
# banana      0.8
# ...
# Hint: use printf "%-12s %s\n" for alignment

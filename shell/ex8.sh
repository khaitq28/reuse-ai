#!/bin/bash

# Exercise 8 — sed: work with data.csv
# File: data.csv
# Format: product;quantity;price  (line 1 = header)

DATA_FILE="data.csv"

echo "=== 1. Print file without the header ==="
# TODO: delete line 1 with sed
# Expected: apple;120;1.5  (and the rest, no header)

sed '1d' data.csv

echo ""
echo "=== 2. Replace 'inactive' with 'DISABLED' in users.csv ==="
# TODO: use sed on users.csv to replace inactive → DISABLED
# Expected: bob;developer;DISABLED

sed 's/inactive/DISABLED/' users.csv

echo ""
echo "=== 3. Print only lines where quantity > 100 (lines 3,4,6,7,8 in data.csv) ==="
# TODO: use sed -n to print lines 2 to 4 (apple, banana, cherry — after header)
# Hint: sed -n '2,4p'  prints lines 2 to 4
# Note: this is a range by line number, not by value — awk is better for value filtering


echo ""
echo "=== 4. Delete all lines containing 'viewer' from users.csv ==="
# TODO: use sed to remove lines that contain the word viewer
# Expected output should have no viewer lines

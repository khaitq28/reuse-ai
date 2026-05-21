#!/bin/bash

# Exercise 3 — Daily error report
# Show number of errors per date, then the top error message
# File: errors.log

LOG_FILE="errors.log"

echo "=== Errors per Day ==="

# TODO 1: Filter ERROR lines, extract the date (field 1, separator ;)
#         sort and count — display one line per date with its count

grep 'ERROR' $LOG_FILE |cut -d ';' -f1 | uniq -c

echo ""
echo "=== Top Error Message ==="

# TODO 2: Filter ERROR lines, extract the message (field 4, separator ;)
#         count occurrences and display only the #1 most frequent message


grep 'ERROR' $LOG_FILE |cut -d ';' -f4 | uniq -c | sort | tail -1
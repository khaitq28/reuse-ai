#!/bin/bash

# Exercise 1 — Count errors per IP
# Log format: date;ip;level;message
# File: errors.log
#2024-01-16;192.168.1.22;ERROR;timeout connecting to DB

LOG_FILE="errors.log"

echo "=== Errors per IP ==="

# TODO 1: Filter only ERROR lines from $LOG_FILE
# TODO 2: Extract the IP field (field 2, separator ;)
# TODO 3: Sort and count occurrences (sort | uniq -c)
# TODO 4: Sort by count descending and display

grep 'ERROR' $LOG_FILE \
      | cut -d ';' -f2 \
      | sort \
      | uniq -c \
      | sort -n


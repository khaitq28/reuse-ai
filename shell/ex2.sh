#!/bin/bash

# Exercise 2 — Log health check
# Count total errors and print status: OK / WARNING / CRITICAL
# File: errors.log

LOG_FILE="errors.log"
WARN_THRESHOLD=5
CRITICAL_THRESHOLD=8

echo "=== Log Health Check ==="

# TODO 1: Count total number of ERROR lines in $LOG_FILE
#         store the result in variable ERROR_COUNT

number_errors=$(grep -c 'ERROR' $LOG_FILE)

# TODO 2: Print how many errors were found
echo "Total errors: $number_errors"


# TODO 3: Use if/elif/else to print a status:
#         - ERROR_COUNT >= CRITICAL_THRESHOLD => print "Status: CRITICAL"
#         - ERROR_COUNT >= WARN_THRESHOLD     => print "Status: WARNING"
#         - otherwise                         => print "Status: OK"

if [ $number_errors -ge  $CRITICAL_THRESHOLD ]; then
    echo "Status: CRITICAL"
elif [ $number_errors -ge $WARN_THRESHOLD ]; then
    echo "Status: WARNING"
else
    echo "Status: OK"
fi
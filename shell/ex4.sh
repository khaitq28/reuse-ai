#!/bin/bash

# Exercise 4 — Filter by level (script argument)
# Usage: ./ex4.sh ERROR
#        ./ex4.sh INFO
# File: errors.log

# TODO 1: Check if the user passed an argument
# If $LEVEL is empty, print "Usage: ./ex4.sh <LEVEL>" and exit 1
# TODO 2: Filter lines from $LOG_FILE where field 3 (separator ;) equals $LEVEL
# Print each matching line
# TODO 3: Count and print the total at the end
# "Total <LEVEL> lines: X"

LOG_FILE="errors.log"
LEVEL=$1

if [ "$LEVEL" == '' ]; then
    echo "Usage: ./ex4.sh <LEVEL>"
    exit 1
elif [ $LEVEL == 'ERROR' ]; then
    count=$(grep -c 'ERROR' $LOG_FILE)
    grep 'ERROR' $LOG_FILE
    echo "Total ERROR lines: $count"
elif [ "$LEVEL" == 'INFO' ]; then
    count=$(grep -c 'INFO' $LOG_FILE)
    grep 'INFO' $LOG_FILE
    echo "Total INFO lines: $count"
else
    echo "Usage: ./ex4.sh <LEVEL>"
    exit 1
fi




#!/bin/bash

# Exercise 4 — Filter by level (script argument)
# Usage: ./ex4.sh ERROR
#        ./ex4.sh INFO
# File: errors.log

LOG_FILE="errors.log"
LEVEL=$1

# TODO 1: Check if the user passed an argument
#         If $LEVEL is empty, print "Usage: ./ex4.sh <LEVEL>" and exit 1




# TODO 2: Filter lines from $LOG_FILE where field 3 (separator ;) equals $LEVEL
#         Print each matching line

if [ $LEVEL == '' ]; then
    echo "Usage: ./ex4.sh <LEVEL>"
    exit 1
elif [ $LEVEL == 'ERROR' ]; then
    grep 'ERROR' $LOG_FILE
elif [ $LEVEL == 'INFO' ]; then
    grep 'INFO' $LOG_FILE
else
    echo "Usage: ./ex4.sh <LEVEL>"
    exit 1
fi

# TODO 3: Count and print the total at the end
#         "Total <LEVEL> lines: X"


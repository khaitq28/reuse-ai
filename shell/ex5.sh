#!/bin/bash

# Exercise 5 — Read log line by line with a loop
# For each ERROR line, print an alert: "ALERT [ip] : message"
# File: errors.log

LOG_FILE="errors.log"

echo "=== Alerts ==="

# TODO 1: Use a while loop to read $LOG_FILE line by line
#         Split each line into 4 variables using IFS=';'
#         Syntax: while IFS=';' read -r date ip level msg; do ... done < "$LOG_FILE"

# TODO 2: Inside the loop, check if $level equals "ERROR"
#         If yes, print:  ALERT [<ip>] : <msg>




## The shell basic. 

$? is result of the last command.

```shell

Find error in txt file.  
-q to not display grep resultat

if grep -q error log.txt
then
   echo "Batch failed"
else
   echo "Batch OK"
fi

```

Other way:

```shell

grep -q 'error' log.txt
if [ $? -eq 0 ]
then
   echo "text found"
else
   echo "text not found"
fi
```
##
## Manipulate with files:

#### 

```shell

Delete empty lines: 

  sed '/^$/d' log.txt


Show NON matching lines: 

  grep -v
  
Show lines which does not contain 'Rec':

  grep -v 'Rec' app.log
  
Valid format:  [date] [level] message

To list the lines invalid:

  grep -v '^\[.*\] \[.*\] .*$' log.txt

^   : begin of line
\[  : literal , is [ 
.*  : anything 
$   : end line


If the log: 
abcd;2015-07-29;error;messages

To list the lines invalid:
grep  '^[a-z^;]*;[^;]*;[^;]*;[^;]' app.log

[] char set
[a-z] : any char from a to z lowercase
[^a] : char but not a
[^;] : char not ;
* : zero or many time
\. : literal dot

Example:
abcd;2015-07-29;error;messages

abcd: => match [^;]*

So to match the line :    [^;]*;[^;]*;[^;]*;[^;] 


```

### Find by format: 

```shell

 192.168.1.10 - ERROR - timeout

 grep '^[0-9]*\.[0-9]*\.[0-9]*\.[0-9]* - [A-Z]* - [.*]*' app.log


 john@gmail.com
 
 grep '^[^@]*@[^@]*\.[^@]' app.log
 
 
 INFO User created
 
 grep '^[A-Z^ ]* [^ ]* [^ ]' app.log
 
```


---

## sed — Stream Editor (find, replace, delete lines)

`sed` reads line by line and applies transformations.

```shell
# Syntax:
sed 'COMMAND' file

# Replace first occurrence per line:
sed 's/old/new/' file.txt

# Replace ALL occurrences per line (g = global):
sed 's/error/ERROR/g' app.log

# Replace and save in-place (-i):
sed -i 's/error/ERROR/g' app.log

# Delete lines containing a word:
sed '/timeout/d' app.log

# Delete empty lines:
sed '/^$/d' app.log

# Print only lines matching a pattern (-n + p):
sed -n '/ERROR/p' app.log

# Print line 3 to 7:
sed -n '3,7p' app.log

# Delete line 1 (header):
sed '1d' data.csv
```

---

## awk — Pattern scanning and processing

`awk` splits each line into fields by a separator (default: space).
`$1` = first field, `$2` = second, `$NF` = last field.

```shell
# Syntax:
awk 'PATTERN { ACTION }' file

# Print first column:
awk '{ print $1 }' app.log

# Print 1st and 3rd column:
awk '{ print $1, $3 }' app.log

# Use custom separator (-F):
# File: john;admin;active
awk -F';' '{ print $1, $3 }' users.csv
# Output: john active

# Print lines where 3rd field = "ERROR":
awk '$3 == "ERROR" { print }' app.log

# Print lines where a number field > 100:
awk -F';' '$2 > 100 { print $1 }' data.csv

# Count lines (NR = number of records):
awk 'END { print NR }' app.log

# Sum a column:
awk -F';' '{ sum += $2 } END { print "Total:", sum }' data.csv
```

---

## cut — Extract columns from text

`cut` extracts specific characters or fields from each line.

```shell
# Syntax:
cut -d DELIMITER -f FIELD file

# File: john;admin;active
# Extract field 1:
cut -d';' -f1 users.csv
# Output: john

# Extract fields 1 and 3:
cut -d';' -f1,3 users.csv
# Output: john;active

# Extract first 5 characters of each line:
cut -c1-5 app.log

# Real example — extract only the date from logs:
# Log line: 2024-01-15 ERROR timeout
cut -d' ' -f1 app.log
# Output: 2024-01-15
```

---

## sort — Sort lines

```shell
# Sort alphabetically:
sort file.txt

# Sort in reverse:
sort -r file.txt

# Sort numerically:
sort -n numbers.txt

# Sort by column 2 (separator = ;):
sort -t';' -k2 data.csv

# Sort numerically by column 2, descending:
sort -t';' -k2 -nr data.csv

# Remove duplicates while sorting:
sort -u file.txt
```

---

## uniq — Remove or count duplicate lines

`uniq` works on **consecutive** duplicates — always sort first!

```shell
# Remove duplicate lines:
sort file.txt | uniq

# Count occurrences of each line:
sort file.txt | uniq -c

# Show only duplicated lines:
sort file.txt | uniq -d

# Show only unique lines (appear once):
sort file.txt | uniq -u
```

---

## wc — Count lines, words, characters

```shell
# Count lines:
wc -l file.txt

# Count words:
wc -w file.txt

# Count characters:
wc -c file.txt

# Count matching lines (combine with grep):
grep -c 'ERROR' app.log
# or:
grep 'ERROR' app.log | wc -l
```

---

## Combining commands — pipelines

The real power comes from chaining tools with `|` (pipe).

```shell
# --- Example 1 ---
# How many ERROR lines are in the log?
grep 'ERROR' app.log | wc -l

# --- Example 2 ---
# List unique users who had errors (field 1, separator ;):
grep 'ERROR' app.log | cut -d';' -f1 | sort | uniq

# --- Example 3 ---
# Top 3 most frequent error messages:
grep 'ERROR' app.log | awk -F';' '{ print $4 }' | sort | uniq -c | sort -nr | head -3

# --- Example 4 ---
# From a CSV (name;score;grade), list names with score > 80, sorted:
awk -F';' '$2 > 80 { print $1 }' results.csv | sort

# --- Example 5 ---
# Remove header (line 1), extract column 2, sum all values:
sed '1d' data.csv | cut -d';' -f2 | awk '{ sum += $1 } END { print "Total:", sum }'

# --- Example 6 ---
# Find IPs that appear more than once in logs, list them:
grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' app.log | sort | uniq -c | sort -nr | awk '$1 > 1 { print $2 }'
```

---

## With ls:

```shell

List detail: 
* ls -l 

List sort by time (t) or size (s): 

* ls -lt  
* ls -ls

To reverse: add 'r':

* ls -ltr
* ls -lsr

Lastest log:

* ls -ltr *.log


```
  
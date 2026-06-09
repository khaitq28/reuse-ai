

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

`sed` reads the file **line by line** and applies a command to each line.

### How to read a sed command

```
sed 's/old/new/g' file.txt
     │  │   │  │
     │  │   │  └── flags: g = all occurrences, nothing = first only
     │  │   └───── replacement text
     │  └───────── text to find (regex)
     └──────────── s = substitute command
```

The separator `/` can be any character — useful when the text contains `/`:
```shell
sed 's|/old/path|/new/path|g' file.txt   # use | instead of /
```

### Basic examples

```shell
# Replace first occurrence per line:
sed 's/error/ERROR/' app.log
# Input:  "error on server, error again"
# Output: "ERROR on server, error again"   ← only first replaced

# Replace ALL occurrences per line (g = global):
sed 's/error/ERROR/g' app.log
# Output: "ERROR on server, ERROR again"   ← all replaced

# Replace and save in-place (-i = modify the file directly):
sed -i 's/error/ERROR/g' app.log

# Delete lines containing a word (d = delete):
sed '/timeout/d' app.log

# Delete empty lines (^$ = line with nothing):
sed '/^$/d' app.log
```

### Address: apply command only to certain lines

An **address** before the command tells sed WHICH lines to target:

```shell
# Format:
sed 'ADDRESS COMMAND' file

# Delete line 1 only (address = line number):
sed '1d' data.csv

# Delete lines 1 to 3:
sed '1,3d' data.csv

# Apply only to lines matching a pattern (address = /regex/):
sed '/ERROR/s/timeout/TIMEOUT/g' app.log
#   ^^^^^^^^ only lines with ERROR → replace timeout

# Print only lines matching a pattern:
# -n = silent mode (don't print by default), p = print this line
sed -n '/ERROR/p' app.log

# Print line 3 to 7:
sed -n '3,7p' app.log
```

### Common patterns

```shell
# Remove all spaces at end of line:
sed 's/ *$//' file.txt

# Add a prefix to every line:
sed 's/^/PREFIX: /' file.txt

# Add a suffix to every line:
sed 's/$/ END/' file.txt

# Replace only the 2nd occurrence on each line:
sed 's/error/ERROR/2' app.log

# Extract: keep only what matches (using & = matched text):
sed 's/[0-9]*/[&]/' file.txt
# Input:  "port 8080 is open"
# Output: "port [8080] is open"   ← & is replaced by the match
```

---

## awk — Pattern scanning and processing

`awk` splits each line into **fields** (columns) and lets you filter + compute.

### How to read an awk command

```
awk -F';' '$3 == "ERROR" { print $1, $4 }' app.log
     │     │               │
     │     │               └── ACTION: what to do with matching lines
     │     └────────────────── PATTERN: which lines to process
     └──────────────────────── field separator (default = space)
```

Both PATTERN and ACTION are optional:
- No PATTERN → applies to every line
- No ACTION → prints the whole line (`{ print }`)

### Built-in variables (memorize these)

```shell
$0    # the whole line
$1    # field 1 (first column)
$2    # field 2
$NF   # last field (NF = Number of Fields)
NR    # current line number (Number of Records)
NF    # number of fields in current line
```

### Basic examples

```shell
# File content: john;admin;active
#               mary;user;inactive

# Print first column (default separator = space):
awk '{ print $1 }' app.log

# Print 1st and 3rd column with custom separator (-F):
awk -F';' '{ print $1, $3 }' users.csv
# Output:
# john active
# mary inactive

# Print the last field of each line:
awk -F';' '{ print $NF }' users.csv
# Output: active / inactive

# Print line number + line:
awk '{ print NR, $0 }' app.log
# Output: 1 john;admin;active
#         2 mary;user;inactive
```

### PATTERN: filter lines

```shell
# Print lines where 3rd field equals "ERROR":
awk -F';' '$3 == "ERROR" { print }' app.log

# Print lines where field 2 (number) is > 100:
awk -F';' '$2 > 100 { print $1 }' data.csv

# Print lines that contain "timeout" (regex match):
awk '/timeout/ { print }' app.log

# Print lines that do NOT contain "timeout":
awk '!/timeout/ { print }' app.log

# Combine conditions:
awk -F';' '$3 == "ERROR" && $2 > 50 { print $1 }' data.csv
```

### BEGIN and END blocks

```shell
# BEGIN runs once before reading any line
# END runs once after all lines are read

# Count lines:
awk 'END { print "Total lines:", NR }' app.log

# Sum a column:
awk -F';' '{ sum += $2 } END { print "Total:", sum }' data.csv

# Print a header before output:
awk -F';' 'BEGIN { print "Name | Status" } { print $1, "|", $3 }' users.csv
# Output:
# Name | Status
# john | active
# mary | inactive

# Skip header line (line 1), then sum:
awk -F';' 'NR > 1 { sum += $2 } END { print sum }' data.csv
```

### Compute and format output

```shell
# Print field with custom text:
awk -F';' '{ print "User:", $1, "- Level:", $3 }' users.csv
# Output: User: john - Level: admin

# Use printf for aligned output (like C printf):
awk -F';' '{ printf "%-10s %s\n", $1, $3 }' users.csv
# Output:
# john       active
# mary       inactive

# Pass a shell variable into awk (-v):
LEVEL="ERROR"
awk -F';' -v level="$LEVEL" '$3 == level { print }' app.log
```

### Quick mental model

| Task | Use |
|------|-----|
| filter lines by content | `awk '/pattern/'` or `grep` |
| filter lines by column value | `awk '$3 == "X"'` |
| extract columns | `awk '{ print $1, $3 }'` or `cut` |
| count / sum | `awk '{ count++ } END { print count }'` |
| skip header | `awk 'NR > 1'` |
| last column | `awk '{ print $NF }'` |

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
  
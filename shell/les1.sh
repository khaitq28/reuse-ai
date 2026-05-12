
grep -q 'error' log.txt

if [ $? -eq 0 ]
then
   echo "text found"
else
   echo "text not found"
fi
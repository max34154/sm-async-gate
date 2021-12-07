#!/bin/bash
case $1 in
"h2") p="test/config/h2/*"
;;
"postgres") p="test/config/postgres/*" 
;;
*) echo "Incorrect config name. Usage run-test [h2|postgres]"
exit 0 
esac
echo "Prepare " $1 " configuration"
rm -f test/config/run/*
cp $p test/config/run
echo "Prepared. Run tests."
lein test

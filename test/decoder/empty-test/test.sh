#!/bin/bash

set -u

cat input | $JOSHUA/bin/joshua-decoder > output 2> log

diff -u output output.gold > diff

if [ $? -eq 0 ]; then
	exit 0
else
	exit 1
fi



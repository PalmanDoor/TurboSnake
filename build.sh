#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
javac -encoding UTF-8 -cp '.:lib/*' ./*.java
javac -encoding UTF-8 -cp '.:lib/*' -d maps maps/*.java
mkdir -p build/tests
javac -encoding UTF-8 -cp '.:lib/*' -d build/tests tests/*.java
java -ea -cp '.:lib/*:build/tests' RegressionTests

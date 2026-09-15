#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
./build.sh
exec java -cp '.:lib/*' SnakeApp

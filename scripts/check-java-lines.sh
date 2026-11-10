#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source_dir="$script_dir/../src"

find "$source_dir" -type f -name '*.java' -exec awk '
  length($0) > 120 {
    printf "%s:%d: line length %d exceeds 120\n", FILENAME, FNR, length($0)
    failed = 1
  }
  END { exit failed }
' {} +

#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
version="${1:?Usage: sh scripts/set-version.sh <version>}"

# The version of the library in the build.
perl -pi -e "s|^version = \"[^\"]*\"|version = \"$version\"|" build.gradle.kts

# The install snippet in the readme.
perl -pi -e "s|sh\.soml:soml-kotlin:[0-9A-Za-z.+-]*|sh.soml:soml-kotlin:$version|" readme.md

git commit --message "$version" build.gradle.kts readme.md

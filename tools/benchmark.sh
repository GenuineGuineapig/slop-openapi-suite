#!/usr/bin/env bash
# Measures request and response validation throughput (see tools/benchmark/Benchmark.java).
#
#   tools/benchmark.sh [seconds per round] (default: 5)
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
seconds=${1:-5}
"$root/gradlew" -p "$root" :openapi-fn-generator:cliJar -q
version=$(sed -n 's/^ *version = "\(.*\)"/\1/p' "$root/build.gradle.kts" | head -1)
# The generator's self-contained jar has the validator, the example generator and their dependencies.
classpath="$root/openapi-fn-generator/build/libs/openapi-fn-generator-$version-cli.jar"
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" -cp "$classpath" "$root/tools/benchmark/Benchmark.java"
java -cp "$classpath:$classes" Benchmark "$root" "$seconds"

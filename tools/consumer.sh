#!/usr/bin/env bash
# Builds a sample as a project outside this repository would: openapi-fn is published to the local
# Maven repository, the sample is copied elsewhere, and its build takes the Gradle plugin and the
# libraries by version from there, rather than from this repository's projects.
#
#   tools/consumer.sh [sample] (default: petstore)
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
sample=${1:-petstore}
version=$(sed -n 's/^ *version = "\(.*\)"/\1/p' "$root/build.gradle.kts" | head -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

"$root/gradlew" -p "$root" publishToMavenLocal -q

(cd "$root/samples/$sample" && tar --exclude ./build --exclude ./.gradle -cf - .) | (cd "$work" && tar -xf -)
cat > "$work/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "$sample"
EOF
perl -0pi -e "s/id\\(\"dev.openapifn\"\\)/id(\"dev.openapifn\") version \"$version\"/; s/repositories \\{\\n    mavenCentral\\(\\)/repositories {\\n    mavenLocal()\\n    mavenCentral()/" "$work/build.gradle.kts"
grep -q "version \"$version\"" "$work/build.gradle.kts"
grep -q "mavenLocal()" "$work/build.gradle.kts"

cd "$work"
./gradlew check --console=plain
tests=$(cat build/test-results/test/*.xml | grep -c "<testcase " || true)
[ "$tests" -gt 0 ]
echo "$sample builds and passes its $tests tests with openapi-fn $version from the local Maven repository"

#!/usr/bin/env bash
# Builds a sample as a project outside this repository would: openapi-fn is installed to the local
# Maven repository, the sample is copied elsewhere, and its build takes the Maven plugin and the
# libraries by version from there, rather than from this repository's build.
#
#   tools/consumer.sh [sample] (default: petstore)
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
sample=${1:-petstore}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mvnw() { "$root/mvnw" -B -ntp "$@"; }

# The libraries and the plugin (their tests run in the repository's own build).
mvnw -q -f "$root/pom.xml" install -pl openapi-fn-maven-plugin,openapi-fn-spring-webflux,openapi-fn-spring-test -am -DskipTests -Djacoco.skip
version=$(mvnw -q -f "$root/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout)

(cd "$root/samples/$sample" && tar --exclude ./target -cf - .) | (cd "$work" && tar -xf -)
grep -q "<openapi-fn.version>$version</openapi-fn.version>" "$work/pom.xml"

# The generator is needed during the build only: the application's classpaths have the validator, not it.
for scope in runtime test; do
    mvnw -q -f "$work/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.11.0:list -DincludeScope="$scope" -DoutputFile="$work/$scope.txt"
    grep -q "dev.openapifn:openapi-fn-validator" "$work/$scope.txt"
    if grep -q "openapi-fn-generator" "$work/$scope.txt"; then
        echo "openapi-fn-generator is on the $scope class path of $sample" >&2
        exit 1
    fi
done
mvnw -f "$work/pom.xml" verify
tests=$(cat "$work"/target/surefire-reports/TEST-*.xml | grep -c "<testcase " || true)
[ "$tests" -gt 0 ]
echo "$sample builds and passes its $tests tests with openapi-fn $version from the local Maven repository, without the generator on its class paths"

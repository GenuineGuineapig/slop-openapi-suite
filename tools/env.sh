# Source this file to get JAVA21_HOME pointing at a JDK 21, which the build needs:
#   source tools/env.sh && JAVA_HOME=$JAVA21_HOME ./mvnw verify
export JAVA21_HOME="${JAVA21_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home}"

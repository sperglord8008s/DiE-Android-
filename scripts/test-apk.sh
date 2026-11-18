#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# APKSIG_JAR can point to the cached artifact for a completely offline test run.
jar="${APKSIG_JAR:-$tmp/apksig.jar}"
source scripts/fetch-verified.sh
fetch_verified_dependency "$jar" c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89 \
  https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.9.2/apksig-8.9.2.jar
keytool -genkeypair -alias fixture -keystore "$tmp/fixture.p12" -storetype PKCS12 \
  -storepass fixture-only -keypass fixture-only -dname 'CN=DiE disposable test fixture' \
  -keyalg RSA -keysize 2048 -validity 30 >/dev/null 2>&1
javac -cp "$jar" -d "$tmp/classes" app/src/main/java/org/diemobile/Inspector.java \
  app/src/main/java/org/diemobile/ApkSigningReport.java tests/ApkSigningTest.java
java -cp "$tmp/classes:$jar" org.diemobile.ApkSigningTest "$tmp"

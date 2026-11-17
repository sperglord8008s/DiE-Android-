#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# Android supplies org.json; this pinned host-only dependency tests the same model code.
jar="${JSON_JAR:-$tmp/json.jar}"
if [[ ! -f "$jar" ]]; then
  curl -fLsS --retry 2 https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$jar"
fi
echo '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  '"$jar" | sha256sum -c -
javac -cp "$jar" -d "$tmp/classes" app/src/main/java/org/diemobile/{Inspector,ScanOptions,ScanEvidence,ScanReport}.java tests/ScanEvidenceTest.java
java -cp "$tmp/classes:$jar" org.diemobile.ScanEvidenceTest

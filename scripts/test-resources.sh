#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/fetch-resources.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dex-dependencies.sh
load_dex_test_dependencies "$tmp"
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
cache="${RESOURCE_DEPS_DIR:-$PWD/vendor/resource-downloads}"
python3 tests/test-resource-source.py "$cache"
fetch() { fetch_verified_dependency "$cache/$1" "$2" "$3"; }
fetch auto-value-1.11.0.jar aaf8d637bfed3c420436b9facf1b7a88d12c8785374e4202382783005319c2c3 https://repo.maven.apache.org/maven2/com/google/auto/value/auto-value/1.11.0/auto-value-1.11.0.jar
fetch auto-value-annotations-1.11.0.jar 5a055ce4255333b3346e1a8703da5bf8ff049532286fdcd31712d624abe111dd https://repo.maven.apache.org/maven2/com/google/auto/value/auto-value-annotations/1.11.0/auto-value-annotations-1.11.0.jar
fetch checker-compat-qual-2.5.5.jar 11d134b245e9cacc474514d2d66b5b8618f8039a1465cdc55bbc0b34e0008b7a https://repo.maven.apache.org/maven2/org/checkerframework/checker-compat-qual/2.5.5/checker-compat-qual-2.5.5.jar
if command -v rg >/dev/null; then
  rg --files vendor/android-resource-engine/src resource-engine/src/main/java -g '*.java' > "$tmp/sources"
else
  find vendor/android-resource-engine/src resource-engine/src/main/java -name '*.java' > "$tmp/sources"
fi
javac --release 8 -cp "$dex_test_classpath:$cache/auto-value-annotations-1.11.0.jar:$cache/checker-compat-qual-2.5.5.jar" \
  -processorpath "$cache/auto-value-1.11.0.jar" -s "$tmp/generated" -d "$tmp/classes" @"$tmp/sources" \
  tests/fixtures/resources/ResourceFixtureFactory.java tests/ResourceEngineTest.java
java -cp "$tmp/classes:$dex_test_classpath" org.diemobile.ResourceEngineTest
javac -cp "$tmp/classes:$dex_test_classpath:$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceJson,EvidenceRows,InputSnapshot,ArchiveStore,FileRange,RangeAnalysis,PreparedExport,InvestigationBackend,BinaryRunner,AndroidResourceFacts,AndroidResourceProbe,AndroidResourceDocument,AndroidResourceBackend,SdkManifestNames}.java \
  tests/ResourceInvestigationTest.java
python3 tests/generate-resource-reference.py "$tmp/reference"
references=()
if [[ -f "$tmp/reference/reference.apk" ]]; then references=("$tmp/reference/reference.apk" "$tmp/reference/sparse.apk"); fi
java -cp "$tmp/classes:$dex_test_classpath:$json_test_jar" org.diemobile.ResourceInvestigationTest "$tmp" "${references[@]}"

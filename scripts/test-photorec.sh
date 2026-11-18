#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build-photorec.sh host
exports=$(nm -D --defined-only app/build/host-photorec/libdiephotorec.so | awk '{print $3}' | LC_ALL=C sort)
expected_exports=$'Java_org_diemobile_PhotoRecEngine_scanFd\nJava_org_diemobile_PhotoRecEngine_scanPath'
if [[ "$exports" != "$expected_exports" ]]; then
  echo 'PhotoRec exports unexpected native symbols; upstream/codec symbol isolation differs.' >&2
  exit 1
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceRows,InputSnapshot,InvestigationBackend,FileRange,RangeAnalysis,PreparedExport,PhotoRecEngine,BinaryRunner,CarvingBackend,PhotoRecBackend}.java \
  tests/fixtures/metadata/MetadataFixtureFactory.java tests/fixtures/carving/CarvingFixture.java tests/PhotoRecInvestigationTest.java
java --add-opens=java.base/java.io=ALL-UNNAMED -Djava.library.path="$PWD/app/build/host-photorec" \
  -cp "$tmp/classes:$json_test_jar" org.diemobile.PhotoRecInvestigationTest "$tmp"

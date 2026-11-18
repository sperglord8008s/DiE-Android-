#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# APKSIG_JAR can point to the cached artifact for a completely offline test run.
jar="${APKSIG_JAR:-$tmp/apksig.jar}"
source scripts/fetch-verified.sh
mapfile -t signing_lock < <(python3 - <<'PY'
import json
from pathlib import Path
lock=json.loads(Path('signing-engine/source-lock.json').read_text())
for key in ['binarySha256','binaryUrl','sourcesSha256','sourcesUrl']: print(lock[key])
PY
)
fetch_verified_dependency "$jar" "${signing_lock[0]}" "${signing_lock[1]}"
sources="${APKSIG_SOURCES_JAR:-${jar%.jar}-sources.jar}"
fetch_verified_dependency "$sources" "${signing_lock[2]}" "${signing_lock[3]}"
python3 scripts/check-apksig-source.py "$sources"

source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$jar:$json_test_jar" -d "$tmp/classes" app/src/main/java/org/diemobile/{Inspector,EvidenceJson,EvidenceRows,InputSnapshot,InvestigationBackend,ApkSigningInput,ApkSigningReport,ApkSigningEvidence,ApkSigningBackend}.java tests/fixtures/signing/ApkSigningFixture.java tests/ApkSigningTest.java
java -cp "$tmp/classes:$jar:$json_test_jar" org.diemobile.ApkSigningFixture "$tmp/fixture.p12"
java -cp "$tmp/classes:$jar:$json_test_jar" org.diemobile.ApkSigningTest "$tmp"

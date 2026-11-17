#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p vendor generated-assets/knowledge
lc=4189fc0de0a0d67f0e729a5b9699a52e9ff3ffcf
uad=9fcad87bf78c52c4270504bc8880139d95a27a29
checkout() {
  local name="$1" url="$2" rev="$3"
  if [[ ! -d "vendor/$name/.git" ]]; then git init -q "vendor/$name"; git -C "vendor/$name" remote add origin "$url"; fi
  git -C "vendor/$name" fetch --depth 1 origin "$rev"
  git -C "vendor/$name" checkout --detach "$rev"
}
checkout LibChecker-Rules https://github.com/LibChecker/LibChecker-Rules.git "$lc"
checkout UAD-NG https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation.git "$uad"
python3 scripts/compile-knowledge.py vendor/LibChecker-Rules generated-assets/knowledge/libchecker.json
cp vendor/UAD-NG/resources/assets/uad_lists.json generated-assets/knowledge/uad.json
cp vendor/LibChecker-Rules/LICENSE generated-assets/knowledge/LibChecker-LICENSE.txt
cp vendor/UAD-NG/LICENSE generated-assets/knowledge/UAD-LICENSE.txt
printf 'LibChecker-Rules %s (Apache-2.0)\nUAD-NG %s (GPL-3.0)\n' "$lc" "$uad" > generated-assets/knowledge/revisions.txt

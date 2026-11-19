#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rev=f055daa56fd3feef1db4d6dbc187cb292de24ea9
mkdir -p vendor generated-assets
if [[ ! -d vendor/CDiE/.git ]]; then git clone https://github.com/horsicq/CDiE.git vendor/CDiE; fi
git -C vendor/CDiE checkout --detach "$rev"
git -C vendor/CDiE submodule update --init --recursive
rm -rf generated-assets/db generated-assets/notices
mkdir -p generated-assets/notices
cp -R vendor/CDiE/dep/Detect-It-Easy/db generated-assets/db
printf 'cdie=%s\nsignatures=%s\n' "$rev" "$(git -C vendor/CDiE/dep/Detect-It-Easy rev-parse HEAD)" > generated-assets/engine-revisions.properties
find generated-assets/db -type d \( -name .git -o -name .vscode -o -name _ai_provenance \) -prune -exec rm -rf {} +
cp vendor/CDiE/LICENSE generated-assets/notices/CDiE-LICENSE.txt
while IFS= read -r -d '' f; do rel="${f#vendor/CDiE/}"; mkdir -p "generated-assets/notices/$(dirname "$rel")"; cp "$f" "generated-assets/notices/$rel"; done < <(find vendor/CDiE/dep -type f \( -iname 'license*' -o -iname 'copying*' -o -iname '*notice*' \) -print0)
git -C vendor/CDiE submodule status --recursive > generated-assets/notices/upstream-revisions.txt
printf '%s\n' "$rev" >> generated-assets/notices/upstream-revisions.txt

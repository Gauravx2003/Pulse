#!/usr/bin/env bash
# CI guard: every backend/modules/* and frontend/src/modules/* directory, and every
# backend/calc/{core,work,people} folder, must have its own entry in .github/CODEOWNERS
# naming exactly one track owner.
# Usage: scripts/check-codeowners.sh [repo-root]
set -euo pipefail

root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
owners="$root/.github/CODEOWNERS"
status=0
checked=0

[[ -f "$owners" ]] || { echo "ERROR: $owners is missing"; exit 1; }

require() { # <repo-relative dir without slashes at the ends>
  local dir="$1" line count
  line="$(grep -E "^/${dir//\//\\/}/[[:space:]]" "$owners" || true)"
  if [[ -z "$line" ]]; then
    echo "ERROR: /$dir/ has no owner in .github/CODEOWNERS"
    status=1
    return
  fi
  count="$(awk '{print NF - 1}' <<<"$line")"
  if [[ "$count" != "1" ]]; then
    echo "ERROR: /$dir/ must have exactly one track owner: $line"
    status=1
  fi
  checked=$((checked + 1))
}

for d in "$root"/backend/modules/*/ "$root"/frontend/src/modules/*/; do
  [[ -d "$d" ]] || continue
  d="${d%/}"
  require "${d#"$root"/}"
done
for c in core work people; do
  require "backend/calc/$c"
done

(( status == 0 )) && echo "CODEOWNERS OK ($checked directories mapped to a track)."
exit "$status"

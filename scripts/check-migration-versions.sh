#!/usr/bin/env bash
# CI guard for migration and seed numbering (design.md decision 10).
# Fails when:
#   - two migration files (V<n>__*.sql) or two seed files (S<n>__*.sql) share a version number,
#     anywhere across backend/src/main/resources/db and backend/modules/*;
#   - a file name does not follow V<n>__<description>.sql / S<n>__<description>.sql;
#   - a version is outside 100-399, or a platform script is outside Track 1's 100-199.
# Usage: scripts/check-migration-versions.sh [repo-root]
set -euo pipefail

root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
backend="$root/backend"
status=0
checked=0
declare -A seen

check_dir() { # <dir> <prefix V|S> <min> <max>
  local dir="$1" prefix="$2" min="$3" max="$4" file name version key
  [[ -d "$dir" ]] || return 0
  while IFS= read -r -d '' file; do
    name="$(basename "$file")"
    rel="${file#"$root"/}"
    if [[ ! "$name" =~ ^${prefix}([0-9]+)__[A-Za-z0-9_]+\.sql$ ]]; then
      echo "ERROR: bad file name $rel (expected ${prefix}<number>__<description>.sql)"
      status=1
      continue
    fi
    version=$((10#${BASH_REMATCH[1]}))
    if (( version < min || version > max )); then
      echo "ERROR: $rel uses ${prefix}${version}, outside ${prefix}${min}-${prefix}${max} for this directory"
      status=1
    fi
    key="${prefix}${version}"
    if [[ -n "${seen[$key]:-}" ]]; then
      echo "ERROR: duplicate version $key: ${seen[$key]} and $rel"
      status=1
    else
      seen[$key]="$rel"
      checked=$((checked + 1))
    fi
  done < <(find "$dir" -maxdepth 1 -type f -name '*.sql' -print0 | sort -z)
}

check_dir "$backend/src/main/resources/db/migrations" V 100 199
check_dir "$backend/src/main/resources/db/seed"       S 100 199
for module in "$backend"/modules/*/; do
  check_dir "${module}migrations" V 100 399
  check_dir "${module}seed"       S 100 399
done

if (( status == 0 )); then
  echo "Migration and seed versions OK (${checked} files checked)."
fi
exit "$status"

#!/usr/bin/env bash
# Verifies this rebuild-forward branch against a proper pinned-base 3-way merge
# of the XPRIZE base and Juan's fork. Every path printed is either a deliberate
# decision or a mistake — review the list, it should not grow silently.
#
# 7d49349 is upstream/main immediately BEFORE PR #12 landed. It is the only
# correct merge base: letting git choose walks back to 49cb995 and manufactures
# 18 phantom conflicts, three of which would silently drop XPRIZE changes.
set -uo pipefail

BASE=${BASE:-7d49349}
OURS=${OURS:-upstream/main}
THEIRS=${THEIRS:-juanba/main}

REF=$(git merge-tree --write-tree --merge-base="$BASE" "$OURS" "$THEIRS" 2>/dev/null | head -1) || true
if [ -z "${REF:-}" ]; then
  echo "could not compute reference tree" >&2
  exit 1
fi
echo "reference merge tree: $REF"
echo "still differing from the reference (MAVROS paths excluded — removal is intentional):"
git diff --name-only "$REF" HEAD \
  | grep -v 'lyrebird_mavros\|mavlink_proxy' \
  | sed 's/^/  /' \
  || echo "  (none)"

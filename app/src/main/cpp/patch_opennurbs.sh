#!/bin/sh
# Idempotent apply of the openNURBS Android font patch.
#
# FetchContent's PATCH_COMMAND re-runs on every configure. CI caches
# app/.cxx (including _deps/opennurbs-src), so the checkout may already
# have the patch applied. Reverse-check first and only apply when needed.
#
# Usage: sh patch_opennurbs.sh <path-to.patch>
PATCH="$1"
if git apply --reverse --check "$PATCH" 2>/dev/null; then
    echo "opennurbs patch already applied - skipping"
else
    git apply "$PATCH"
fi

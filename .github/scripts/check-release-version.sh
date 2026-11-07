#!/usr/bin/env bash
# Release safety net: stops a release whose base version was already released or is not
# newer than the last release, before any test or build work is done.
#
# Usage:  check-release-version.sh <path to app/build.gradle.kts>
# Stdin:  tag list in `git ls-remote --tags origin` format (<sha> TAB <ref> per line)
# Env:    GITHUB_SHA     commit of this release (required)
#         GITHUB_OUTPUT  if set, "version=<base version>" is appended to it
#
# Released versions are the tags v<x>.<y>.<z> (current scheme) and v<x>.<y>.<z>.<n>
# (legacy scheme with build number). Both count for the version x.y.z. All other tags,
# such as the rolling pre-release tag "dev", are ignored.
# A re-run of the same release (tag v<base> already points to GITHUB_SHA) is allowed.
set -euo pipefail

fail() {
  echo "::error::$1"
  exit 1
}

[ "$#" -eq 1 ] || fail "Usage: check-release-version.sh <build.gradle.kts>"
gradle_file=$1
sha=${GITHUB_SHA:-}
[[ "$sha" =~ ^[0-9a-fA-F]{7,64}$ ]] || fail "GITHUB_SHA must be a hex commit id"
sha=${sha,,}

# Base version: exactly one line `val baseVersion = "x.y.z"`, strict format.
[ -f "$gradle_file" ] || fail "File $gradle_file not found"
mapfile -t base_lines < <(grep -E '^val baseVersion[[:space:]]*=' "$gradle_file" | tr -d '\r' || true)
[ "${#base_lines[@]}" -eq 1 ] || fail "Expected exactly one 'val baseVersion = \"x.y.z\"' line in $gradle_file, found ${#base_lines[@]}"
num='(0|[1-9][0-9]{0,8})'
if [[ ! "${base_lines[0]}" =~ ^val\ baseVersion\ =\ \"$num\.$num\.$num\"$ ]]; then
  fail "baseVersion in $gradle_file must look like: val baseVersion = \"x.y.z\" (found: ${base_lines[0]})"
fi
base="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"

# Sort key of a version, so that comparing the strings compares the numbers.
key() {
  printf '%010d%010d%010d' "$((10#$1))" "$((10#$2))" "$((10#$3))"
}
base_key=$(key "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}")

# Release tags with their commit. Annotated tags list the commit as "<tag>^{}".
declare -A tag_sha=()
tag_re="^refs/tags/(v$num\.$num\.$num(\.[0-9]+)?)(\^\{\})?$"
while IFS=$'\t' read -r tsha ref; do
  [[ "$ref" =~ $tag_re ]] || continue
  name=${BASH_REMATCH[1]}
  if [ -n "${BASH_REMATCH[6]}" ] || [ -z "${tag_sha[$name]:-}" ]; then
    tag_sha[$name]=${tsha,,}
  fi
done

exact_tag="v$base"
highest_key=""
highest_tag=""
released_tag=""
for name in $(printf '%s\n' "${!tag_sha[@]}" | sort -V); do
  [[ "$name" =~ ^v$num\.$num\.$num ]] || continue
  k=$(key "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}")
  if [ -z "$highest_key" ] || [[ "$k" > "$highest_key" ]]; then
    highest_key=$k
    highest_tag=$name
  fi
  if [ "$k" = "$base_key" ] && { [ -z "$released_tag" ] || [ "$name" = "$exact_tag" ]; }; then
    released_tag=$name
  fi
done

if [ -n "${tag_sha[$exact_tag]:-}" ] && [ "${tag_sha[$exact_tag]}" = "$sha" ]; then
  echo "Version $base: tag $exact_tag already points to this commit, re-run of the same release."
elif [ -n "$released_tag" ]; then
  fail "Version $base is already released (tag $released_tag). Bump baseVersion in app/build.gradle.kts before releasing."
elif [ -n "$highest_key" ] && [[ "$base_key" < "$highest_key" ]]; then
  fail "Version $base is lower than the highest released version (tag $highest_tag). Bump baseVersion in app/build.gradle.kts above it before releasing."
else
  echo "Version $base is new (highest released: ${highest_tag:-none})."
fi

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "version=$base" >> "$GITHUB_OUTPUT"
fi

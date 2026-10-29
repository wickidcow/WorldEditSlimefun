#!/usr/bin/env bash
# Release only a separately authorized, previously tagged, source-pinned build.
# Do not mutate an existing GitHub Release or its assets.
set -euo pipefail

fail() {
    echo "::error::$*" >&2
    exit 1
}

: "${VERSION:?VERSION must be set}"
: "${JAR:?JAR must be set}"
: "${GITHUB_SHA:?GITHUB_SHA must be set}"
: "${EXPECTED_SOURCE_SHA:?EXPECTED_SOURCE_SHA must be set}"

[[ "${GITHUB_REPOSITORY:-}" == "wickidcow/WorldEditSlimefun" ]] || fail "Unexpected repository identity"
[[ "${GITHUB_EVENT_NAME:-}" == "workflow_dispatch" ]] || fail "Publication requires explicit workflow dispatch"
[[ "${RELEASE_CONFIRMED:-}" == "true" ]] || fail "Publication must be explicitly confirmed"
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "GITHUB_SHA must be a full lowercase commit SHA"
[[ "$EXPECTED_SOURCE_SHA" == "$GITHUB_SHA" ]] || fail "Source SHA differs from the authorized commit"
[[ "${GITHUB_REF:-}" == "refs/tags/v${VERSION}" && "${GITHUB_REF_NAME:-}" == "v${VERSION}" ]] ||
    fail "Release tag must exactly match project version v${VERSION}"
[[ -s "$JAR" ]] || fail "Release JAR is missing or empty"

TAG="v${VERSION}"
if gh release view "$TAG" >/dev/null 2>&1; then
    fail "Release $TAG already exists: its bytes, assets, and metadata must remain unchanged"
fi

# gh release create without --clobber cannot overwrite an existing release even
# if a concurrent run creates one between the existence check and this call.
# --verify-tag also prevents this workflow from inventing an unreviewed tag.
gh release create "$TAG" "$JAR" --verify-tag \
    --title "SFWorldEdit ${VERSION}" \
    --notes "Verified Java 21 WorldEditSlimefun ${VERSION} for Minecraft 1.21.11 and newer. Requires a compatible WorldEdit/FAWE and Slimefun Legacy installation."

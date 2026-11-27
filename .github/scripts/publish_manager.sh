#!/usr/bin/env bash
set -euo pipefail

# Publish the native manager assets produced by one successful CI run.
commit=${GITHUB_SHA:?}
repository=${GITHUB_REPOSITORY:?}
first=manager-v0.1.0
tag=$first-$commit

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

assets=()
for binary in dist/manager-linux-x64 dist/manager-linux-aarch64 dist/manager-windows-x64.exe; do
    checksum=$binary.sha256
    [[ -s "$binary" && -f "$checksum" ]] || fail "Missing or empty asset for $binary"
    digest=$(shasum -a 256 "$binary")
    [[ $(<"$checksum") =~ ^[[:space:]]*${digest%% *}[[:space:]]*$ ]] || fail "SHA-256 mismatch for $binary"
    assets+=("$binary" "$checksum")
done

compat=proxy_agent/resources/com/fivetran/proxy_agent/compat
[[ -d "$compat" ]] || compat=.
for name in proxy-agent-manager.sh proxy-agent-manager.ps1; do
    wrapper=$compat/$name
    [[ -s "$wrapper" ]] || fail "Missing or empty manager wrapper: $wrapper"
    assets+=("$wrapper")
done

releases=$(gh api "repos/$repository/releases" --paginate --jq '.[] | [.tag_name, .target_commitish, .draft] | @tsv')
first_target='' first_draft='' target='' draft=''
while IFS=$'\t' read -r release release_target release_draft; do
    case "$release" in
        "$first") first_target=$release_target; first_draft=$release_draft ;;
        "$tag") target=$release_target; draft=$release_draft ;;
    esac
done <<< "$releases"
if [[ -z "$first_draft" || "$first_target" == "$commit" ]]; then
    tag=$first target=$first_target draft=$first_draft
fi

if [[ -n "$draft" ]]; then
    [[ "$target" == "$commit" ]] || fail "$tag targets a different commit"
    if [[ "$draft" == false ]]; then
        printf '%s is already published.\n' "$tag"
        exit 0
    fi
fi

# Pass named GraphQL variables through gh.
# shellcheck disable=SC2016
latest=$(gh api graphql \
    -f 'query=query($owner:String!, $name:String!) { repository(owner:$owner, name:$name) { latestRelease { tagName } } }' \
    -f "owner=${repository%/*}" -f "name=${repository#*/}" --jq '.data.repository.latestRelease.tagName // empty')
if [[ -z "$latest" ]] || git merge-base --is-ancestor "refs/tags/$latest" "$commit"; then
    make_latest=true
else
    status=$?
    (( status == 1 )) || exit "$status"
    make_latest=false
fi

if [[ -z "$draft" ]]; then
    gh release create "$tag" --draft --target "$commit" --title "$tag" >/dev/null
fi
gh release upload "$tag" --clobber "${assets[@]}" >/dev/null
gh release edit "$tag" --draft=false "--latest=$make_latest"

#!/usr/bin/env bash
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

if [ -n "${PROXY_AGENT_MANAGER_BIN:-}" ]; then
    binary=$(type -P -- "$PROXY_AGENT_MANAGER_BIN") || die "Native manager executable not found."
    binary=$(cd -- "$(dirname -- "$binary")" && pwd -P)/$(basename -- "$binary")
    export PROXY_AGENT_INSTALLER_BINARY="$binary"
    exec "$binary" install "$@"
fi

url="${PROXY_AGENT_MANAGER_BINARY_URL:-}"
expected_hash="${PROXY_AGENT_MANAGER_BINARY_SHA256:-}"
if [ -n "$url" ] || [ -n "$expected_hash" ]; then
    if [ -z "$url" ] || ! [[ "$expected_hash" =~ ^[0-9a-fA-F]{64}$ ]]; then
        die "Native manager download requires a URL and its SHA-256 digest."
    fi
else
    case "$(uname -m)" in
        x86_64) platform=linux-x64 ;;
        aarch64|arm64) platform=linux-aarch64 ;;
        *) die "No native manager is available for this host architecture." ;;
    esac
    url="https://github.com/fivetran/proxy_agent/releases/download/manager-v0.1.0/manager-$platform"
    expected_hash=$(curl -fSsL --max-time 30 --url "$url.sha256") ||
        die "Failed to download the native manager SHA-256 digest."
fi

scratch=$(mktemp -d)
binary="$scratch/manager"
trap 'rm -f -- "$binary"; rmdir -- "$scratch"' EXIT
curl -fSsL --max-time 120 --url "$url" -o "$binary" || die "Failed to download the native manager."
if command -v sha256sum >/dev/null; then
    digest=$(sha256sum < "$binary")
else
    digest=$(shasum -a 256 < "$binary")
fi
expected_hash=$(printf '%s' "$expected_hash" | tr '[:upper:]' '[:lower:]')
[ "${digest%% *}" = "$expected_hash" ] || die "Native manager SHA-256 does not match."
chmod u+x "$binary"
PROXY_AGENT_INSTALLER_BINARY="$binary" "$binary" install "$@"

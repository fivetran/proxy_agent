#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"

install_native_manager() (
    local url="${PROXY_AGENT_MANAGER_BINARY_URL:-}"
    local expected="${PROXY_AGENT_MANAGER_BINARY_SHA256:-}"
    local platform candidate digest
    if [ -n "$url" ] || [ -n "$expected" ]; then
        [ -n "$url" ] && [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || exit 1
    else
        [ "$(uname -s)" = Linux ] || exit 1
        case "$(uname -m)" in
            x86_64) platform=linux-x64 ;;
            aarch64|arm64) platform=linux-aarch64 ;;
            *) exit 1 ;;
        esac
        url="https://github.com/fivetran/proxy_agent/releases/latest/download/manager-$platform"
        expected=$(curl -fSsL --max-time 30 --url "$url.sha256" 2>/dev/null) || exit 1
    fi
    [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || exit 1
    candidate=$(mktemp "$BASE_DIR/.native-manager.XXXXXX") || exit 1
    trap 'rm -f -- "$candidate"' EXIT
    curl -fSsL --max-time 120 --url "$url" -o "$candidate" 2>/dev/null || exit 1
    if command -v sha256sum >/dev/null; then
        digest=$(sha256sum < "$candidate") || exit 1
    else
        digest=$(shasum -a 256 < "$candidate") || exit 1
    fi
    expected=$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')
    [ "${digest%% *}" = "$expected" ] || exit 1
    chmod u+x "$candidate" || exit 1
    if [ "${1:-}" = update-manager ] && ! "$candidate" update-manager --help >/dev/null 2>&1; then
        exit 1
    fi
    mv -f -- "$candidate" "$BASE_DIR/manager" || exit 1
    echo "Native manager installed."
)

run_native_manager() {
    local manager="$BASE_DIR/manager"
    if [ ! -f "$manager" ] && ! install_native_manager; then
        echo "ERROR: Could not install a verified native manager." >&2
        exit 1
    fi
    # An early native installer may predate the update-manager command.
    if [ "${1:-}" = update-manager ] && ! "$manager" update-manager --help >/dev/null 2>&1; then
        install_native_manager update-manager || {
            echo "ERROR: Could not install a native manager that supports update-manager." >&2
            exit 1
        }
    fi
    exec "$manager" "$@"
}

run_native_manager "$@"

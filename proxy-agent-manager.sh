#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"

IMAGE="${PROXY_AGENT_IMAGE:-us-docker.pkg.dev/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent}"
IMAGE_TAG_SUFFIX="-ubuntu-26.04"
CONFIG_FILE="$BASE_DIR/config/config.json"
VERSION_FILE="$BASE_DIR/version"
SETTINGS_FILE="$BASE_DIR/settings.sh"
LOGFILE="$BASE_DIR/logs/proxy-agent-manager.log"
CONTAINER_LOG_DIR="/app/logs"
CONTAINER_CONFIG_FILE="/config/config.json"

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

get_agent_id() {
    grep -o '"agent_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$CONFIG_FILE" 2>/dev/null \
        | sed 's/.*"agent_id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/' \
        || true
}

log() {
    echo "$1"
    mkdir -p "$(dirname "$LOGFILE")"
    echo "$(date -u +'%Y-%m-%d %H:%M:%S') UTC - $1" >> "$LOGFILE"
}

check_memory_allocation() {
    if ! [[ "$MEMORY_ALLOCATION_MB" =~ ^[0-9]+$ ]] || [ "$MEMORY_ALLOCATION_MB" -lt 1 ]; then
        echo "ERROR: Invalid MEMORY_ALLOCATION_MB in $SETTINGS_FILE: $MEMORY_ALLOCATION_MB (must be a positive integer, in MB)"
        exit 1
    fi
}

get_release_version() {
    local tag="$1"
    if [[ "$tag" =~ ^([0-9]+\.[0-9]+\.[0-9]+)(-ubuntu-26\.04)?$ ]]; then
        echo "${BASH_REMATCH[1]}"
        return 0
    fi

    echo "ERROR: Invalid proxy agent version '$tag' in $VERSION_FILE. Expected <major>.<minor>.<patch>." >&2
    return 1
}

image_tag() {
    echo "${IMAGE}:$1${IMAGE_TAG_SUFFIX}"
}

list_doctor_logs() {
    find "$BASE_DIR/logs" -maxdepth 1 -name 'doctor-*.log' | LC_ALL=C sort
}

run_doctor() {
    mkdir -p "$BASE_DIR/logs"
    local output_file temporary_output_file earlier_doctor_logs timestamp
    timestamp=$(date -u +'%Y-%m-%dT%H-%M-%S')
    temporary_output_file=$(mktemp "$BASE_DIR/logs/proxy-agent-manager-doctor-${timestamp}.XXXXXXXXXX")
    output_file="${temporary_output_file}.log"
    # macOS mktemp requires Xs at the end, so add the discoverable .log suffix
    # with a hard link. ln creates it atomically and won't overwrite a report.
    if ! ln "$temporary_output_file" "$output_file"; then
        rm -f "$temporary_output_file"
        return 1
    fi
    rm -f "$temporary_output_file"
    earlier_doctor_logs=$(list_doctor_logs)

    # No terminal (-t), which would merge the doctor's progress line into the
    # saved output. The working folder is where newer doctors save their log if
    # they can't read the config.
    # set +e so a failing doctor doesn't exit the script, and PIPESTATUS keeps
    # the doctor's exit code even if tee fails, e.g. on a full disk.
    set +e
    docker run --rm \
        --workdir "$CONTAINER_LOG_DIR" \
        "${AGENT_RUN_ARGS[@]}" \
        "$(image_tag "$CURRENT_VERSION")" \
        "$CONTAINER_CONFIG_FILE" --doctor "$@" 2>&1 \
        | tee "$output_file"
    local status="${PIPESTATUS[0]}"
    set -e
    if [ -n "$(LC_ALL=C comm -13 <(echo "$earlier_doctor_logs") <(list_doctor_logs))" ]; then
        rm -f "$output_file"
        # The doctor printed paths inside the container.
        echo "$CONTAINER_LOG_DIR in the container is $BASE_DIR/logs on this host."
    else
        echo "The doctor's output was saved to $output_file"
    fi
    exit "$status"
}

load_agent() {
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "ERROR: Config file not found: $CONFIG_FILE"
        exit 1
    fi

    AGENT_ID=$(get_agent_id)
    if [ -z "$AGENT_ID" ] || [ "$AGENT_ID" = "null" ]; then
        echo "ERROR: 'agent_id' missing in $CONFIG_FILE"
        exit 1
    fi


    if [ ! -f "$VERSION_FILE" ]; then
        echo "ERROR: $VERSION_FILE not found."
        exit 1
    fi
    # Also strips the UTF-8 byte order mark an editor on Windows may add.
    PINNED_VERSION=$(LC_ALL=C tr -d '[:space:]\357\273\277' < "$VERSION_FILE")
    if [ -z "$PINNED_VERSION" ]; then
        echo "ERROR: $VERSION_FILE is empty."
        exit 1
    fi

    if [ -f "$SETTINGS_FILE" ]; then
        # shellcheck disable=SC1090
        source "$SETTINGS_FILE"
    fi
    MEMORY_ALLOCATION_MB="${MEMORY_ALLOCATION_MB:-5120}"

    # Shared by the agent and doctor containers, so the doctor checks the agent's
    # config, memory limit and log folder.
    AGENT_RUN_ARGS=(
        --memory="${MEMORY_ALLOCATION_MB}m"
        --env IS_DOCKER=true
        --env LOG_FOLDER_PATH="$CONTAINER_LOG_DIR"
        -v "$BASE_DIR/config/config.json:$CONTAINER_CONFIG_FILE:ro"
        -v "$BASE_DIR/logs:$CONTAINER_LOG_DIR"
    )
}

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

case "${1:-}" in
    doctor)
        load_agent
        CURRENT_VERSION=$(get_release_version "$PINNED_VERSION") || exit 1
        check_memory_allocation
        shift
        run_doctor "$@"
        ;;
    *)
        run_native_manager "$@"
        ;;
esac

#!/usr/bin/env bash
#
#  Management script for Fivetran Proxy Agent (Docker)
#
#  Usage: ./proxy-agent-manager.sh {start|stop|restart|upgrade|status|logs|doctor [options]}
#
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

# PROXY_AGENT_IMAGE and PROXY_AGENT_REGISTRY_TAGS_URL are overridable so the
# test suite can run against a stub image and registry.
IMAGE="${PROXY_AGENT_IMAGE:-us-docker.pkg.dev/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent}"
# Appended to the plain agent version to form the Linux image tag.
IMAGE_TAG_SUFFIX="-ubuntu-26.04"
CONFIG_FILE="$BASE_DIR/config/config.json"
VERSION_FILE="$BASE_DIR/version"
SETTINGS_FILE="$BASE_DIR/settings.sh"

get_agent_id() {
    grep -o '"agent_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$CONFIG_FILE" 2>/dev/null \
        | sed 's/.*"agent_id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/' \
        || true
}

if [ ! -f "$CONFIG_FILE" ]; then
    echo "ERROR: Config file not found: $CONFIG_FILE"
    exit 1
fi

AGENT_ID=$(get_agent_id)
if [ -z "$AGENT_ID" ] || [ "$AGENT_ID" = "null" ]; then
    echo "ERROR: 'agent_id' missing in $CONFIG_FILE"
    exit 1
fi

CONTAINER_NAME="proxy-agent-${AGENT_ID}"

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

LOGFILE="$BASE_DIR/logs/proxy-agent-manager.log"
CONTAINER_LOG_DIR="/app/logs"
CONTAINER_CONFIG_FILE="/config/config.json"

# ── Functions ────────────────────────────────────────────────────────────────

log() {
    echo "$1"
    mkdir -p "$(dirname "$LOGFILE")"
    echo "$(date -u +'%Y-%m-%d %H:%M:%S') UTC - $1" >> "$LOGFILE"
}

if [ -f "$SETTINGS_FILE" ]; then
    # shellcheck disable=SC1090
    source "$SETTINGS_FILE"
fi
MEMORY_ALLOCATION_MB="${MEMORY_ALLOCATION_MB:-5120}"

# Only the commands that start a container check the setting, so stop, status
# and logs still work with an invalid one.
check_memory_allocation() {
    if ! [[ "$MEMORY_ALLOCATION_MB" =~ ^[0-9]+$ ]] || [ "$MEMORY_ALLOCATION_MB" -lt 1 ]; then
        echo "ERROR: Invalid MEMORY_ALLOCATION_MB in $SETTINGS_FILE: $MEMORY_ALLOCATION_MB (must be a positive integer, in MB)"
        exit 1
    fi
}

# Shared by the agent and doctor containers, so the doctor checks the agent's
# config, memory limit and log folder.
AGENT_RUN_ARGS=(
    --memory="${MEMORY_ALLOCATION_MB}m"
    --env IS_DOCKER=true
    --env LOG_FOLDER_PATH="$CONTAINER_LOG_DIR"
    -v "$BASE_DIR/config/config.json:$CONTAINER_CONFIG_FILE:ro"
    -v "$BASE_DIR/logs:$CONTAINER_LOG_DIR"
)

get_latest_version() {
    local registry_host="${IMAGE%%/*}"
    local repository_path="${IMAGE#*/}"
    local registry_url="${PROXY_AGENT_REGISTRY_TAGS_URL:-https://${registry_host}/v2/${repository_path}/tags/list}"
    local tags_json
    tags_json=$(curl -s -f --max-time 30 "$registry_url") || {
        echo "ERROR: Unable to query image registry for latest version" >&2
        return 1
    }
    echo "$tags_json" \
        | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+-ubuntu-26\.04"' \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' \
        | sort -V \
        | tail -1 \
        || true
}

# Prints the plain agent version for a version file entry. Accepts a plain
# version (1.2.9) or a legacy image tag (1.2.9-ubuntu-26.04).
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

pin_version() {
    echo "$1" > "$VERSION_FILE"
}

upgrade_agent() {
    echo "Checking for latest version..."
    local latest_version
    latest_version=$(get_latest_version) || exit 1
    if [ -z "$latest_version" ]; then
        echo "ERROR: Unable to determine latest version" >&2
        exit 1
    fi
    if [ "$latest_version" = "$CURRENT_VERSION" ]; then
        pin_version "$CURRENT_VERSION"
        echo "Already running the latest version ($CURRENT_VERSION)."
        exit 0
    fi
    log "Upgrading from $CURRENT_VERSION to $latest_version..."
    pin_version "$latest_version"
    if ! start_agent "$latest_version"; then
        log "Upgrade failed, rolling back to $CURRENT_VERSION..."
        pin_version "$CURRENT_VERSION"
        if ! start_agent "$CURRENT_VERSION"; then
            log "Rollback to $CURRENT_VERSION failed."
        fi
        exit 1
    fi
}

stop_agent() {
    if docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
        docker stop "${CONTAINER_NAME}" >/dev/null
        docker rm "${CONTAINER_NAME}" >/dev/null
        log "Stopped ${CONTAINER_NAME}."
    fi
}

start_agent() {
    local version="$1"
    log "Starting ${CONTAINER_NAME} (version: ${version})..."

    stop_agent

    mkdir -p "$BASE_DIR/logs"

    docker run -d \
        --name "${CONTAINER_NAME}" \
        --restart unless-stopped \
        --label fivetran=proxy-agent \
        --label proxy_agent_id="$AGENT_ID" \
        "${AGENT_RUN_ARGS[@]}" \
        --env HEARTBEAT_PATH=/tmp/proxy-agent-heartbeat.txt \
        --env HEARTBEAT_EXPIRY_SECONDS=30 \
        --health-cmd '[ ! -f $HEARTBEAT_PATH ] || { . $HEARTBEAT_PATH && [ $(date +%s) -lt $HEARTBEAT_EXPIRE_AT ]; }' \
        --health-interval 10s \
        --health-timeout 3s \
        --health-retries 3 \
        --health-start-period 30s \
        "$(image_tag "$version")" \
        -i "$CONTAINER_CONFIG_FILE"

    local timeout=60
    log "Waiting for ${CONTAINER_NAME} to become healthy (timeout: ${timeout}s)..."

    local elapsed=0
    while [ "$(docker inspect -f '{{.State.Health.Status}}' "${CONTAINER_NAME}")" = "starting" ]; do
        if [ "$elapsed" -ge "$timeout" ]; then
            log "Error: ${CONTAINER_NAME} did not become healthy within ${timeout}s"
            docker logs "${CONTAINER_NAME}"
            stop_agent
            return 1
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done

    FINAL_STATUS=$(docker inspect -f '{{.State.Health.Status}}' "${CONTAINER_NAME}")

    if [ "$FINAL_STATUS" = "healthy" ]; then
        docker ps -a --filter "name=${CONTAINER_NAME}" --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
        log "Success: ${CONTAINER_NAME} is healthy."
    else
        log "Error: ${CONTAINER_NAME} entered status: $FINAL_STATUS"
        docker logs "${CONTAINER_NAME}"
        return 1
    fi
}

# Runs the doctor in a throwaway container beside the agent's, passing "$@" to
# --doctor (e.g. --region us-east-1), and exits with its exit code. Its output
# is saved to the log folder, since older doctors (e.g. 1.2.10) only print
# their report. Newer ones also save their log and an archive to the mounted
# log folder, so those outlive the container too.
run_doctor() {
    mkdir -p "$BASE_DIR/logs"
    local output_file
    output_file="$BASE_DIR/logs/doctor-output-$(date -u +'%Y-%m-%dT%H-%M-%S').log"

    local status=0
    # No terminal (-t), which would merge the doctor's progress line into the
    # saved output. The working folder is where newer doctors save their log if
    # they can't read the config.
    docker run --rm \
        --workdir "$CONTAINER_LOG_DIR" \
        "${AGENT_RUN_ARGS[@]}" \
        "$(image_tag "$CURRENT_VERSION")" \
        "$CONTAINER_CONFIG_FILE" --doctor "$@" 2>&1 \
        | tee "$output_file" \
        || status=$?
    echo "The doctor's output was saved to $output_file"
    # Newer doctors print paths inside the container.
    echo "$CONTAINER_LOG_DIR in the container is $BASE_DIR/logs on this host."
    exit "$status"
}

# ── Commands ─────────────────────────────────────────────────────────────────

case "${1:-}" in
    start)
        CURRENT_VERSION=$(get_release_version "$PINNED_VERSION") || exit 1
        check_memory_allocation
        pin_version "$CURRENT_VERSION"
        start_agent "$CURRENT_VERSION"
        ;;

    stop)
        log "Stopping ${CONTAINER_NAME}..."
        stop_agent
        ;;

    status)
        docker ps -a --filter "name=${CONTAINER_NAME}" --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
        ;;

    restart)
        CURRENT_VERSION=$(get_release_version "$PINNED_VERSION") || exit 1
        check_memory_allocation
        log "Restarting ${CONTAINER_NAME}..."
        stop_agent
        pin_version "$CURRENT_VERSION"
        start_agent "$CURRENT_VERSION"
        ;;

    upgrade)
        CURRENT_VERSION=$(get_release_version "$PINNED_VERSION") || exit 1
        check_memory_allocation
        upgrade_agent
        ;;

    logs)
        docker logs -f "${CONTAINER_NAME}"
        ;;

    doctor)
        CURRENT_VERSION=$(get_release_version "$PINNED_VERSION") || exit 1
        check_memory_allocation
        shift
        run_doctor "$@"
        ;;

    *)
        echo "Usage: $0 {start|stop|restart|upgrade|status|logs|doctor [options]}"
        exit 1
        ;;
esac

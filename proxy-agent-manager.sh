#!/usr/bin/env bash
#
#  Management script for Fivetran Proxy Agent (Docker)
#
#  Usage: ./proxy-agent-manager.sh {start|stop|restart|upgrade|status|logs}
#
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

IMAGE="us-docker.pkg.dev/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent"
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
PINNED_VERSION=$(tr -d '[:space:]' < "$VERSION_FILE")
if [ -z "$PINNED_VERSION" ]; then
    echo "ERROR: $VERSION_FILE is empty."
    exit 1
fi

LOGFILE="$BASE_DIR/logs/proxy-agent-manager.log"
CONTAINER_LOG_DIR="/app/logs"

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

get_latest_version() {
    local registry_host="${IMAGE%%/*}"
    local repository_path="${IMAGE#*/}"
    local registry_url="https://${registry_host}/v2/${repository_path}/tags/list"
    local tags_json
    tags_json=$(curl -s -f "$registry_url") || {
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
        start_agent "$CURRENT_VERSION"
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
        --memory="${MEMORY_ALLOCATION_MB}m" \
        --label fivetran=proxy-agent \
        --label proxy_agent_id="$AGENT_ID" \
        --env IS_DOCKER=true \
        --env LOG_FOLDER_PATH="$CONTAINER_LOG_DIR" \
        --env HEARTBEAT_PATH=/tmp/proxy-agent-heartbeat.txt \
        --env HEARTBEAT_EXPIRY_SECONDS=30 \
        --health-cmd '[ ! -f $HEARTBEAT_PATH ] || { . $HEARTBEAT_PATH && [ $(date +%s) -lt $HEARTBEAT_EXPIRE_AT ]; }' \
        --health-interval 10s \
        --health-timeout 3s \
        --health-retries 3 \
        --health-start-period 30s \
        -v "$BASE_DIR/config/config.json:/config/config.json:ro" \
        -v "$BASE_DIR/logs:$CONTAINER_LOG_DIR" \
        "$(image_tag "$version")" \
        -i /config/config.json

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

# ── Commands ─────────────────────────────────────────────────────────────────

CURRENT_VERSION=$(get_release_version "$PINNED_VERSION") || exit 1

case "${1:-}" in
    start)
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
        log "Restarting ${CONTAINER_NAME}..."
        stop_agent
        pin_version "$CURRENT_VERSION"
        start_agent "$CURRENT_VERSION"
        ;;

    upgrade)
        upgrade_agent
        ;;

    logs)
        docker logs -f "${CONTAINER_NAME}"
        ;;

    *)
        echo "Usage: $0 {start|stop|restart|upgrade|status|logs}"
        exit 1
        ;;
esac

#!/usr/bin/env bash
#
#  Integration test for install.sh and proxy-agent-manager.sh on a real Docker
#  daemon. Uses a stub agent image built here (tests/stub) and a local stand-in
#  for the registry and the script download, so it needs no Fivetran
#  credentials and never contacts the real registry; the versions below are
#  fixtures. The only network access is pulling the stub's alpine base image.
#
#  Usage: tests/integration/linux.sh
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PORT="${PORT:-8181}"
STUB_IMAGE="proxy-agent-stub"
SUFFIX="-ubuntu-26.04"
OLD_VERSION="1.2.3"
NEW_VERSION="1.2.4"
AGENT_ID="test-agent"   # matches fixtures/config.json
CONTAINER="proxy-agent-${AGENT_ID}"
INSTALL_DIR="$(mktemp -d)/fivetran-proxy-agent"
SERVER_PID=""

cleanup() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    rm -rf "$(dirname "$INSTALL_DIR")"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

current_image() {
    docker inspect -f '{{.Config.Image}}' "$CONTAINER"
}

assert_image() {
    local actual
    actual=$(current_image)
    [ "$actual" = "$1" ] || fail "expected image $1, got $actual"
}

assert_healthy() {
    local status
    status=$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER")
    [ "$status" = "healthy" ] || fail "expected healthy container, got $status"
}

assert_version_file() {
    local content
    content=$(cat "$INSTALL_DIR/version")
    [[ "$content" == "$1"* ]] || fail "expected version file to start with $1, got '$content'"
}

echo "== Building stub image"
docker build -q -f "$REPO_ROOT/tests/stub/Dockerfile.linux" -t "$STUB_IMAGE:$OLD_VERSION$SUFFIX" "$REPO_ROOT/tests/stub"
# The bare tags let the rollback step work whether the management script pins
# the plain version or the full image tag.
for tag in "$OLD_VERSION" "$NEW_VERSION" "$NEW_VERSION$SUFFIX"; do
    docker tag "$STUB_IMAGE:$OLD_VERSION$SUFFIX" "$STUB_IMAGE:$tag"
done

echo "== Starting stub registry on port $PORT"
python3 "$REPO_ROOT/tests/integration/registry_server.py" --port "$PORT" --root "$REPO_ROOT" \
    --tags "$OLD_VERSION,$OLD_VERSION$SUFFIX,$NEW_VERSION,$NEW_VERSION$SUFFIX,$NEW_VERSION-windows-ltsc2022" &
SERVER_PID=$!
for _ in $(seq 1 40); do
    curl -sf "http://127.0.0.1:$PORT/proxy-agent-manager.sh" >/dev/null && break
    sleep 0.25
done
curl -sf "http://127.0.0.1:$PORT/proxy-agent-manager.sh" >/dev/null || fail "stub registry did not start"

export RUNTIME=docker
export MEMORY_ALLOCATION_MB=256
export PROXY_AGENT_IMAGE="$STUB_IMAGE"
export PROXY_AGENT_REGISTRY_TAGS_URL="http://127.0.0.1:$PORT/v2/stub/tags/list"
export PROXY_AGENT_SCRIPT_URL="http://127.0.0.1:$PORT/proxy-agent-manager.sh"

echo "== Installing"
"$REPO_ROOT/install.sh" "$REPO_ROOT/tests/integration/fixtures/config.json" --install-dir "$INSTALL_DIR"
assert_image "$STUB_IMAGE:$NEW_VERSION$SUFFIX"
assert_healthy
assert_version_file "$NEW_VERSION"
grep -qx "MEMORY_ALLOCATION_MB=256" "$INSTALL_DIR/settings.sh" || fail "settings.sh does not record the memory allocation"
[ "$(docker inspect -f '{{.HostConfig.Memory}}' "$CONTAINER")" = "$((256 * 1024 * 1024))" ] || fail "container memory limit not applied"
config_mode=$(stat -c %a "$INSTALL_DIR/config/config.json" 2>/dev/null || stat -f %Lp "$INSTALL_DIR/config/config.json")
[ "$config_mode" = "600" ] || fail "config.json is not mode 600 (got $config_mode)"
cmp -s "$REPO_ROOT/tests/integration/fixtures/config.json" "$INSTALL_DIR/config/config.json" || fail "config.json was not copied verbatim"

MANAGER="$INSTALL_DIR/proxy-agent-manager.sh"

echo "== status"
"$MANAGER" status | grep -q "$CONTAINER" || fail "status does not list the container"

echo "== Rolling back to $OLD_VERSION via the version file"
echo "$OLD_VERSION" > "$INSTALL_DIR/version"
"$MANAGER" start
case "$(current_image)" in
    "$STUB_IMAGE:$OLD_VERSION"|"$STUB_IMAGE:$OLD_VERSION$SUFFIX") ;;
    *) fail "expected rollback to $OLD_VERSION, got $(current_image)" ;;
esac
assert_healthy

echo "== Upgrading"
"$MANAGER" upgrade
assert_image "$STUB_IMAGE:$NEW_VERSION$SUFFIX"
assert_healthy
assert_version_file "$NEW_VERSION"

echo "== Upgrading again is a no-op"
"$MANAGER" upgrade | grep -q "Already running the latest version" || fail "second upgrade did not report already latest"
assert_image "$STUB_IMAGE:$NEW_VERSION$SUFFIX"

echo "== restart"
"$MANAGER" restart
assert_healthy

echo "== stop"
"$MANAGER" stop
if docker inspect "$CONTAINER" >/dev/null 2>&1; then
    fail "container still exists after stop"
fi

echo "PASS"

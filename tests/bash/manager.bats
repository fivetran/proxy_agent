#!/usr/bin/env bats
#
#  Unit tests for proxy-agent-manager.sh. The script is run as a black box with
#  the stub `docker` and `curl` commands from ./stubs on PATH, so no Docker
#  daemon or network access is needed. The versions below are fixtures and do
#  not depend on what is published in the real registry.
#

REPO_ROOT="$(cd "$(dirname "$BATS_TEST_FILENAME")/../.." && pwd)"
# The repository the manager hardcodes; only used to build expected image
# strings, the docker stub never pulls anything.
IMAGE_REPO="us-docker.pkg.dev/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent"

setup() {
    AGENT_DIR="$(mktemp -d)"
    mkdir -p "$AGENT_DIR/config"
    cp "$REPO_ROOT/proxy-agent-manager.sh" "$AGENT_DIR/"
    echo '{"agent_id": "abc123"}' > "$AGENT_DIR/config/config.json"
    echo "1.2.9-ubuntu-26.04" > "$AGENT_DIR/version"

    # State shared with the stubs in ./stubs; see the comments there.
    export DOCKER_LOG="$AGENT_DIR/docker.log"
    export DOCKER_LAST_IMAGE="$AGENT_DIR/docker.image"
    export DOCKER_UNHEALTHY_TAG=""
    export STUB_TAGS='["1.2.9-ubuntu-26.04","1.2.10-ubuntu-26.04","1.2.11","1.2.10-windows-ltsc2022"]'
    export PATH="$REPO_ROOT/tests/bash/stubs:$PATH"
}

teardown() {
    rm -rf "$AGENT_DIR"
}

manager() {
    "$AGENT_DIR/proxy-agent-manager.sh" "$@"
}

last_image() {
    cat "$DOCKER_LAST_IMAGE"
}

@test "start runs the pinned Ubuntu image with the default memory limit" {
    run manager start
    [ "$status" -eq 0 ]
    [ "$(last_image)" = "$IMAGE_REPO:1.2.9-ubuntu-26.04" ]
    grep -q -- "--memory=5120m" "$DOCKER_LOG"
}

@test "start honors MEMORY_ALLOCATION_MB from settings.sh" {
    echo "MEMORY_ALLOCATION_MB=2048" > "$AGENT_DIR/settings.sh"
    run manager start
    [ "$status" -eq 0 ]
    grep -q -- "--memory=2048m" "$DOCKER_LOG"
}

@test "start mounts the config read-only and labels the container with the agent id" {
    run manager start
    [ "$status" -eq 0 ]
    grep -q -- "$AGENT_DIR/config/config.json:/config/config.json:ro" "$DOCKER_LOG"
    grep -q -- "--name proxy-agent-abc123" "$DOCKER_LOG"
    grep -q -- "proxy_agent_id=abc123" "$DOCKER_LOG"
}

@test "start fails when the config file is missing" {
    rm "$AGENT_DIR/config/config.json"
    run manager start
    [ "$status" -eq 1 ]
    [[ "$output" == *"Config file not found"* ]]
}

@test "start fails when the version file is empty" {
    : > "$AGENT_DIR/version"
    run manager start
    [ "$status" -eq 1 ]
    [[ "$output" == *"is empty"* ]]
}

@test "upgrade picks the newest Ubuntu tag and ignores bare and Windows tags" {
    run manager upgrade
    [ "$status" -eq 0 ]
    [ "$(last_image)" = "$IMAGE_REPO:1.2.10-ubuntu-26.04" ]
    [[ "$(cat "$AGENT_DIR/version")" == 1.2.10* ]]
}

@test "upgrade is a no-op when already on the latest version" {
    export STUB_TAGS='["1.2.9-ubuntu-26.04","1.2.11"]'
    run manager upgrade
    [ "$status" -eq 0 ]
    [[ "$output" == *"Already running the latest version"* ]]
    ! grep -q "^run" "$DOCKER_LOG"
}

@test "upgrade rolls back when the new container is unhealthy" {
    export DOCKER_UNHEALTHY_TAG="1.2.10"
    run manager upgrade
    [ "$status" -eq 0 ]
    [[ "$output" == *"rolling back"* ]]
    [ "$(last_image)" = "$IMAGE_REPO:1.2.9-ubuntu-26.04" ]
    [[ "$(cat "$AGENT_DIR/version")" == 1.2.9* ]]
}

@test "upgrade rejects an invalid version file entry" {
    echo "2.0" > "$AGENT_DIR/version"
    run manager upgrade
    [ "$status" -eq 1 ]
    [[ "$output" == *"Invalid"* ]]
}

@test "stop removes the running container" {
    manager start
    run manager stop
    [ "$status" -eq 0 ]
    [ ! -f "$DOCKER_LAST_IMAGE" ]
    grep -q "^stop proxy-agent-abc123" "$DOCKER_LOG"
    grep -q "^rm proxy-agent-abc123" "$DOCKER_LOG"
}

@test "unknown command prints usage" {
    run manager bogus
    [ "$status" -eq 1 ]
    [[ "$output" == *"Usage:"* ]]
}

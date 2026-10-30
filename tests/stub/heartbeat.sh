#!/bin/sh
#
#  Stub proxy agent: writes the heartbeat file the management script's health
#  check reads, then idles. It expects to be invoked like the real agent, with
#  `-i <config path>` pointing at a readable file, and exits otherwise so the
#  container never becomes healthy and the test fails.
#
set -eu

if [ "${1:-}" != "-i" ] || [ -z "${2:-}" ]; then
    echo "stub proxy agent: expected '-i <config path>', got: $*" >&2
    exit 1
fi
if [ ! -r "$2" ]; then
    echo "stub proxy agent: config file not readable: $2" >&2
    exit 1
fi

path="${HEARTBEAT_PATH:?HEARTBEAT_PATH must be set}"
expiry="${HEARTBEAT_EXPIRY_SECONDS:-30}"

echo "stub proxy agent started with config $2"
while true; do
    echo "HEARTBEAT_EXPIRE_AT=$(( $(date +%s) + expiry ))" > "$path"
    sleep 5
done

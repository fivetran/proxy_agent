#!/bin/sh
#
#  Stub proxy agent: writes the heartbeat file the management script's health
#  check reads, then idles. Any arguments (the real agent's -i <config>) are
#  ignored.
#
set -eu

path="${HEARTBEAT_PATH:?HEARTBEAT_PATH must be set}"
expiry="${HEARTBEAT_EXPIRY_SECONDS:-30}"

echo "stub proxy agent started with args: $*"
while true; do
    echo "HEARTBEAT_EXPIRE_AT=$(( $(date +%s) + expiry ))" > "$path"
    sleep 5
done

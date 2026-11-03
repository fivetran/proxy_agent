#!/bin/sh
#
#  Stub proxy agent: writes the heartbeat file the management script's health
#  check reads, then idles. It expects to be invoked like the real agent, with
#  `-i <config path>` pointing at a readable file, and exits otherwise so the
#  container never becomes healthy and the test fails. Invoked like the
#  doctor, with `<config path> --doctor [options]`, it instead acts like one
#  that saves an archive: it writes doctor-stub.zip to $LOG_FOLDER_PATH, prints
#  a report naming its options, and exits 1, as a doctor whose checks failed.
#
set -eu

if [ "${2:-}" = "--doctor" ]; then
    echo "stub doctor log" >&2
    echo "stub doctor archive" > "${LOG_FOLDER_PATH:?LOG_FOLDER_PATH must be set}/doctor-stub.zip"
    shift 2
    echo "stub doctor report for options: $*"
    exit 1
fi

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

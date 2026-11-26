#!/usr/bin/env bash
set -e
directory="$(cd -- "$(dirname -- "$0")" && pwd)"
exec "$directory/manager" "$@"

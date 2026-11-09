#!/bin/sh
set -e

if [ -n "$JUDGE_REMOTE_BACKEND" ]; then
    socat TCP-LISTEN:8080,fork,reuseaddr TCP:$JUDGE_REMOTE_BACKEND &
    export JUDGE_CONTROL_URL="http://127.0.0.1:8080/api/v1"
fi

exec "$@"

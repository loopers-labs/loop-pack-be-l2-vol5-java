#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
export MYSQL_PORT=${MYSQL_PORT:-13307}
export MYSQL_USER=${MYSQL_USER:-application}
export MYSQL_PWD=${MYSQL_PWD:-application}
exec python3 "$SCRIPT_DIR/load.py" "$@"

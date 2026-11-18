#!/usr/bin/env bash
# W8 부하 테스트 인프라 공통 설정. 다른 스크립트가 source 한다.
# 모든 AWS 호출은 loopers-loadtest 프로필로만 한다.
set -euo pipefail

export AWS_PROFILE=loopers-loadtest
export AWS_REGION=ap-northeast-2
export AWS_PAGER=""

PREFIX=loopers-loadtest
AZ=ap-northeast-2a
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
STATE="$HERE/.state"
KEY="$STATE/$PREFIX.pem"
HOSTS="$STATE/hosts.env"
SSH_OPTS=(-i "$KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=5)

mkdir -p "$STATE"

ssh_to() { # host command...
  local host=$1; shift
  ssh "${SSH_OPTS[@]}" "ec2-user@$host" "$@"
}

wait_ssh() { # host
  for _ in $(seq 1 60); do
    ssh_to "$1" true 2>/dev/null && return 0
    sleep 5
  done
  echo "ssh 접속 실패: $1" >&2
  return 1
}

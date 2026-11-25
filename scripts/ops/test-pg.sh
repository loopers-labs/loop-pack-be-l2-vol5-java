#!/usr/bin/env bash
# 리허설용 test-pg 래퍼(10주차 4단계). 서버는 test 소스의 TestPgServer이며 운영 jar에는 들어가지 않는다.
# 사용:
#   scripts/ops/test-pg.sh start                      # 포그라운드로 띄운다(Ctrl+C로 종료). 결제 127.0.0.1:8082, 제어 127.0.0.1:8083
#   scripts/ops/test-pg.sh lose  LP-ORD-0000000101    # 이 주문의 create를 SUCCESS로 저장한 뒤 응답을 늦춰 앱이 결과를 모르게 한다
#   scripts/ops/test-pg.sh clear LP-ORD-0000000101    # 위 지정을 해제한다
#   scripts/ops/test-pg.sh behavior ACCEPT_SUCCESS    # 전역 create 동작(TestPgServer.CreateBehavior 이름)
#   scripts/ops/test-pg.sh status                     # create·조회 호출 수, 현재 동작, 유실 지정 주문 목록
# 환경 변수: TEST_PG_PORT(8082), TEST_PG_CONTROL_PORT(8083), TEST_PG_DELAY_MILLIS(5000), TEST_PG_USER_ID(135135)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTROL_URL="http://127.0.0.1:${TEST_PG_CONTROL_PORT:-8083}/control"

usage() {
    echo "사용법: $0 start | lose ORDER_ID | clear ORDER_ID | behavior NAME | status" >&2
    exit 2
}

control() {
    local status=0
    curl --silent --show-error --fail-with-body --max-time 5 "$@" || status=$?
    echo
    return "$status"
}

if [[ $# -lt 1 ]]; then
    usage
fi

case "$1" in
    start)
        exec "$ROOT_DIR/gradlew" -p "$ROOT_DIR" :apps:commerce-api:runTestPg
        ;;
    lose | clear)
        [[ $# -eq 2 ]] || usage
        if [[ "$1" == "lose" ]]; then
            METHOD="PUT"
        else
            METHOD="DELETE"
        fi
        control -X "$METHOD" "$CONTROL_URL/lost-responses/$2"
        ;;
    behavior)
        [[ $# -eq 2 ]] || usage
        control -X PUT -H 'Content-Type: application/json' --data "{\"behavior\":\"$2\"}" "$CONTROL_URL/create-behavior"
        ;;
    status)
        [[ $# -eq 1 ]] || usage
        control "$CONTROL_URL/status"
        ;;
    *)
        usage
        ;;
esac

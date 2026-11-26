#!/usr/bin/env bash
# 관리자 API 호출 래퍼(10주차 4단계, local·dev 전용 HTTP Basic).
# 사용:
#   scripts/ops/admin-curl.sh GET  '/api-admin/v1/payments/unresolved?status=UNKNOWN&minAgeSeconds=300'
#   scripts/ops/admin-curl.sh POST /api-admin/v1/payments/reconcile-batch '{"orderIds":[101,102]}'
#   scripts/ops/admin-curl.sh GET  '/api-admin/v1/payments/unresolved?refundOwner=PERSON'
#   scripts/ops/admin-curl.sh GET  '/api-admin/v1/payments/unresolved?refundMoneyStatus=REFUND_REQUESTED'
#   scripts/ops/admin-curl.sh POST /api-admin/v1/payments/refund-cases/101/person-resolution '{"result":"REFUNDED"}'
#   scripts/ops/admin-curl.sh POST /api-admin/v1/payments/refund-cases/101/process-now   (사람 담당 건 지금 처리, 본문 없음)
# 계정은 환경 변수 COMMERCE_ADMIN_USERNAME·COMMERCE_ADMIN_PASSWORD에서만 읽고 출력하지 않는다.
# 주소는 COMMERCE_API_URL(기본 http://localhost:8080)이다.
# POST·PUT·PATCH·DELETE는 먼저 관리자 GET으로 XSRF-TOKEN 쿠키를 받고, 그 값을 X-XSRF-TOKEN 헤더와 쿠키로 붙여 보낸다.
set -euo pipefail

BASE_URL="${COMMERCE_API_URL:-http://localhost:8080}"
TOKEN_PATH="/api-admin/v1/payments/unresolved?size=1"

usage() {
    echo "사용법: $0 METHOD PATH [JSON_BODY]" >&2
    exit 2
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
    usage
fi
METHOD="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
REQUEST_PATH="$2"
BODY="${3:-}"
if [[ "$REQUEST_PATH" != /api-admin/* ]]; then
    echo "관리자 경로(/api-admin/...)만 보낼 수 있습니다: $REQUEST_PATH" >&2
    exit 2
fi
if [[ -z "${COMMERCE_ADMIN_USERNAME:-}" || -z "${COMMERCE_ADMIN_PASSWORD:-}" ]]; then
    echo "COMMERCE_ADMIN_USERNAME·COMMERCE_ADMIN_PASSWORD 환경 변수가 필요합니다." >&2
    exit 2
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
COOKIE_JAR="$WORK_DIR/cookies.txt"

# 계정을 명령줄 인자(-u)로 넘기면 프로세스 목록에 보이므로 curl 설정을 표준 입력으로 넘긴다.
curl_config_value() {
    local value="$1"
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    printf '%s' "$value"
}

admin_curl() {
    printf 'user = "%s:%s"\n' \
        "$(curl_config_value "$COMMERCE_ADMIN_USERNAME")" "$(curl_config_value "$COMMERCE_ADMIN_PASSWORD")" \
        | curl --config - --silent --show-error --cookie "$COOKIE_JAR" --cookie-jar "$COOKIE_JAR" "$@"
}

if [[ "$METHOD" == "GET" ]]; then
    admin_curl --fail-with-body "$BASE_URL$REQUEST_PATH"
    echo
    exit 0
fi

# 토큰 쿠키만 받으면 되므로 응답 본문은 버린다. 인증 실패(401·403)는 여기서 멈춘다.
admin_curl --fail --output /dev/null "$BASE_URL$TOKEN_PATH"
CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -n 1)"
if [[ -z "$CSRF_TOKEN" ]]; then
    echo "관리자 조회 응답에 XSRF-TOKEN 쿠키가 없습니다." >&2
    exit 1
fi

BODY_ARGS=()
if [[ -n "$BODY" ]]; then
    BODY_ARGS=(--header "Content-Type: application/json" --data "$BODY")
fi
admin_curl --fail-with-body --request "$METHOD" --header "X-XSRF-TOKEN: $CSRF_TOKEN" \
    "${BODY_ARGS[@]+"${BODY_ARGS[@]}"}" "$BASE_URL$REQUEST_PATH"
echo

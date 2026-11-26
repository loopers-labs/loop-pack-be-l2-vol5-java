#!/usr/bin/env bash
# 10주차 사람 흐름 리허설(game day) 재현 명령 모음. 자동 테스트가 아니다. 기록은 docs/week10/rehearsal.md.
# 사용: scripts/ops/rehearsal.sh <단계> [인자]
#   up                      인프라(MySQL·Redis·Kafka)와 모니터링(리허설 오버레이) 기동. compose 프로젝트 이름을 따로 써서 평소 볼륨을 건드리지 않는다
#   app                     commerce-api(local)를 띄운다(포그라운드). 아래 "앱 리허설 설정" 참고
#   test-pg                 test-pg 8082·8083(포그라운드)
#   seed                    브랜드·상품(관리자 API) 준비와 포인트 충전. 출력: 상품 ID
#                           사용자(id 1)는 앱 local 시드(sql/local/seed-users.sql)가 기동 때 만든다
#   order PRODUCT_ID [lose] 주문 1건 생성 → (lose면 test-pg 응답 유실 지정) → confirm. 출력: 주문 ID와 confirm 응답
#   metrics                 Prometheus에서 결제 지표 4종 조회
#   alerts                  Prometheus 알림 상태와 Alertmanager 알림
#   receiver                가짜 수신기가 받은 본문(원본·가이드)
#   panel                   Grafana API로 대시보드 "미확인 결제" 패널 쿼리 실행
#   loki ORDER_ID           Loki에서 주문 번호로 경계 로그 찾기(최근 1시간)
#   unresolved              관리자 미확인 목록(admin-curl.sh)
#   reconcile ID[,ID...]    관리자 일괄 조회 확정(admin-curl.sh)
#   down                    리허설 compose를 볼륨까지 내린다
# 환경 변수: COMMERCE_ADMIN_USERNAME·COMMERCE_ADMIN_PASSWORD(관리자 계정, 출력하지 않음)
# 카드는 test-pg 테스트 카드(TestPgFixture)를 그대로 읽어 쓴다. 저장소에 새 카드 값을 두지 않는다.
#
# 앱 리허설 설정(명령줄 override, 운영 설정 파일은 그대로):
#   --payment.lookup.scheduler.enabled=false        자동 재조회·만료 스케줄러를 끈다. test-pg 조회는 정상이라 켜 두면
#                                                   몇 초 안에 자동 회복돼 "자동 재조회가 못 따라잡는" 사고가 재현되지 않는다
#   --commerce.payment.unresolved-metric.refresh-interval=5s   gauge 갱신 15s → 5s
# JSON 로그는 기본 경로(저장소 logs/commerce-api.json)에 쌓인다. bootRun은 저장소 루트에서 실행된다(build.gradle.kts).
set -euo pipefail
unset DOCKER_CONFIG

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_URL="${COMMERCE_API_URL:-http://localhost:8080}"
PROM_URL="http://127.0.0.1:9090"
AM_URL="http://127.0.0.1:9093"
LOKI_URL="http://127.0.0.1:3100"
GRAFANA_URL="http://127.0.0.1:3000"
USER_ID=1 # local 시드 사용자(sql/local/seed-users.sql)
INFRA=(docker compose -p w10r-infra -f "$ROOT/docker/infra-compose.yml")
MON=(docker compose -p w10r-mon -f "$ROOT/docker/monitoring-compose.yml" -f "$ROOT/docker/monitoring-compose.rehearsal.yml")
ADMIN="$ROOT/scripts/ops/admin-curl.sh"
TEST_PG="$ROOT/scripts/ops/test-pg.sh"

card_no() {
    sed -n 's/.*CARD_NO = "\(.*\)";/\1/p' \
        "$ROOT/apps/commerce-api/src/test/java/com/loopers/support/pg/TestPgFixture.java"
}

prom_query() {
    curl -sG "$PROM_URL/api/v1/query" --data-urlencode "query=$1"
    echo
}

case "${1:-}" in
    up)
        "${INFRA[@]}" up -d mysql redis-master redis-readonly kafka
        "${MON[@]}" up -d
        ;;
    app)
        exec "$ROOT/gradlew" -p "$ROOT" :apps:commerce-api:bootRun --args="--payment.lookup.scheduler.enabled=false \
--commerce.payment.unresolved-metric.refresh-interval=5s"
        ;;
    test-pg)
        exec "$TEST_PG" start
        ;;
    seed)
        brand_id="$("$ADMIN" POST /api-admin/v1/brands '{"name":"리허설 브랜드"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')"
        "$ADMIN" POST /api-admin/v1/products "{\"brandId\":$brand_id,\"name\":\"리허설 상품\",\"price\":1000,\"stock\":1000}" \
            | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])'
        curl -sf -X POST "$APP_URL/api/v1/points/charge" -H "X-USER-ID: $USER_ID" -H 'Content-Type: application/json' \
            --data '{"amount":100000}' >/dev/null
        ;;
    order)
        product_id="$2"
        order_id="$(curl -sf -X POST "$APP_URL/api/v1/orders" -H "X-USER-ID: $USER_ID" -H 'Content-Type: application/json' \
            --data "{\"items\":[{\"productId\":$product_id,\"quantity\":1}]}" \
            | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')"
        if [[ "${3:-}" == "lose" ]]; then
            "$TEST_PG" lose "$(printf 'LP-ORD-%010d' "$order_id")" >/dev/null
        fi
        echo "orderId=$order_id"
        curl -s -X POST "$APP_URL/api/v1/orders/$order_id/confirm" -H "X-USER-ID: $USER_ID" -H 'Content-Type: application/json' \
            --data "{\"paymentAttemptKey\":\"$(uuidgen | tr '[:upper:]' '[:lower:]')\",\"cardType\":\"SAMSUNG\",\"cardNo\":\"$(card_no)\"}"
        echo
        ;;
    metrics)
        prom_query 'sum by (provider) (commerce_payment_started_total)'
        prom_query 'sum by (provider) (commerce_payment_unknown_entered_total)'
        prom_query 'sum by (result) (commerce_payment_outcome_total)'
        prom_query 'max by (status) (commerce_payment_unresolved)'
        ;;
    alerts)
        curl -s "$PROM_URL/api/v1/alerts"
        echo
        curl -s "$AM_URL/api/v2/alerts"
        echo
        ;;
    receiver)
        "${MON[@]}" logs --no-log-prefix fake-receiver | grep '"path"'
        ;;
    panel)
        curl -s -u admin:admin -X POST "$GRAFANA_URL/api/ds/query" -H 'Content-Type: application/json' --data \
            '{"from":"now-30m","to":"now","queries":[{"refId":"A","datasource":{"type":"prometheus","uid":"prometheus"},"expr":"max by (status) (commerce_payment_unresolved)","instant":true}]}'
        echo
        ;;
    loki)
        curl -sG "$LOKI_URL/loki/api/v1/query_range" \
            --data-urlencode "query={app=\"commerce-api\"} | orderId=\"$2\"" --data-urlencode 'since=1h' --data-urlencode 'limit=50'
        echo
        ;;
    unresolved)
        "$ADMIN" GET '/api-admin/v1/payments/unresolved?status=UNKNOWN'
        ;;
    reconcile)
        "$ADMIN" POST /api-admin/v1/payments/reconcile-batch "{\"orderIds\":[$2]}"
        ;;
    down)
        "${MON[@]}" down -v
        "${INFRA[@]}" down -v
        ;;
    *)
        sed -n '2,21p' "$0" >&2
        exit 2
        ;;
esac

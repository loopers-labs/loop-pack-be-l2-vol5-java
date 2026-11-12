#!/usr/bin/env bash
# 선착순 발급 스파이크 한 회.
#   1) 사용자 준비: MySQL 에 직접 (관리자 1 + 일반 USERS 명, 이미 있으면 그대로)
#   2) 프로모션 생성: 관리자 API, 쿠폰 COUPONS 장, 바로 ACTIVE
#   3) k6: open model, STAGES 의 단계마다 정한 초당 건수를 그 기간 동안, 요청마다 다른 사용자
#   4) 검증: DB 의 발급 수·남은 수량, 받은 사람 목록으로 서버별 공정성(fairness.py),
#      Prometheus 의 서버·DB 최댓값과 1초 간격 시간 흐름
# 결과는 .state/results/<run-id>/ 에 남는다.
# 실행: infra/loadtest/k6/run-issue-spike.sh
#   (COUPONS=3000 USERS=100000 STAGES=3s:1000,6s:10000 VUS=20000 이 기본. STAGES 는 "기간:초당 건수" 를 쉼표로 잇고, 기간은 초 단위만)
# 사용자는 DB 가 비어 있을 때 한 번만 넣으므로 첫 실행의 USERS 가 이후에도 그대로다.
source "$(dirname "$0")/../common.sh"
source "$HOSTS"

COUPONS=${COUPONS:-3000}
USERS=${USERS:-100000}
STAGES=${STAGES:-3s:1000,6s:10000}
VUS=${VUS:-20000}
NEED=0
for stage in ${STAGES//,/ }; do
  NEED=$((NEED + ${stage%%s:*} * ${stage##*:}))
done
APPS="$APP_1_PRIVATE:8080,$APP_2_PRIVATE:8080,$APP_3_PRIVATE:8080"
RUN_ID="issue-$(date +%Y%m%d-%H%M%S)"
OUT="$STATE/results/$RUN_ID"
mkdir -p "$OUT"

mysql_q() { ssh_to "$MYSQL_PUBLIC" "sudo docker exec -i mysql mysql -uapplication -papplication loopers -N -B 2>/dev/null"; }

echo "== 1) 사용자 준비 (DB 직접)"
read -r ADMIN_ID USER_START USER_COUNT POINTS < <({ echo "SET @users = $USERS;"; cat "$HERE/k6/seed-users.sql"; } | mysql_q | tail -1)
echo "admin=$ADMIN_ID user_start=$USER_START users=$USER_COUNT points=$POINTS (이번 요청 수 $NEED)"
if (( USER_COUNT < NEED )); then
  echo "사용자가 요청 수보다 적다. USERS 를 늘린다." >&2
  exit 1
fi

echo "== 2) 프로모션 생성 (쿠폰 $COUPONS 장)"
STARTS_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ENDS_AT=$(date -u -v+1d +%Y-%m-%dT%H:%M:%SZ)
BODY="{\"name\":\"$RUN_ID\",\"type\":\"COUPON_ISSUE\",\"startsAt\":\"$STARTS_AT\",\"endsAt\":\"$ENDS_AT\",\"couponIssue\":{\"totalQuantity\":$COUPONS,\"perUserLimit\":1,\"couponSpec\":{\"discountValue\":3000}}}"
PROMOTION_ID=$(ssh_to "$K6_PUBLIC" "curl -s -XPOST $APP_1_PRIVATE:8080/api-admin/v1/promotions -H 'X-USER-ID: $ADMIN_ID' -H 'Content-Type: application/json' -d '$BODY'" |
  python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["promotionId"])')
echo "promotion=$PROMOTION_ID"

echo "== 3) k6 (단계 $STAGES, VU $VUS, open model)"
START=$(( $(date +%s) - 3 ))
scp "${SSH_OPTS[@]}" "$HERE/k6/issue-spike.js" "ec2-user@$K6_PUBLIC:/home/ec2-user/issue-spike.js"
ssh_to "$K6_PUBLIC" "source /etc/profile.d/k6-prometheus.sh && K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PERIOD=1s K6_WEB_DASHBOARD_EXPORT=k6-report.html k6 run --no-color -o experimental-prometheus-rw --tag testid=$RUN_ID \
  -e APPS=$APPS -e PROMOTION_ID=$PROMOTION_ID -e USER_START=$USER_START -e STAGES=$STAGES -e VUS=$VUS \
  issue-spike.js" | tee "$OUT/k6.txt"
scp "${SSH_OPTS[@]}" "ec2-user@$K6_PUBLIC:/home/ec2-user/k6-report.html" "$OUT/k6-report.html"

END=$(( $(date +%s) + 3 ))

echo "== 4) 검증"
sleep 5 # 마지막 수집이 들어오도록
{
  echo "promotion=$PROMOTION_ID coupons=$COUPONS stages=$STAGES vus=$VUS requests=$NEED"
  echo "SELECT CONCAT('remaining=', remaining) FROM coupon_issue_stock WHERE promotion_id = $PROMOTION_ID;
        SELECT CONCAT('issued=', COUNT(*), ' distinct_users=', COUNT(DISTINCT user_id),
                      ' first=', MIN(created_at), ' last=', MAX(created_at),
                      ' span_ms=', ROUND(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), MAX(created_at)) / 1000))
        FROM issued_coupon WHERE promotion_id = $PROMOTION_ID;" | mysql_q
} | tee "$OUT/db.txt"
echo "SELECT id, user_id FROM issued_coupon WHERE promotion_id = $PROMOTION_ID ORDER BY id;" | mysql_q > "$OUT/winners.tsv"
python3 "$HERE/k6/fairness.py" "$OUT/winners.tsv" "$USER_START" "$NEED" 3 | tee "$OUT/fairness.txt"
ssh_to "$MONITOR_PUBLIC" "WINDOW=2m bash -s" < "$HERE/k6/prom-snapshot.sh" | tee "$OUT/metrics.txt"
ssh_to "$MONITOR_PUBLIC" "python3 - $START $END" < "$HERE/k6/prom-timeline.py" | tee "$OUT/timeline.tsv"

echo "결과: $OUT"

#!/usr/bin/env bash
# 단계 3 관찰: 조회가 500인 동안 회로가 열려 PG 호출이 멈추는지 보고, 첫 회차가 수신함을 한 번 훑으면
# 테스트 PG를 정상(단계 0 규칙)으로 되돌린 뒤 다시 회수되는지 본다. 15초마다 기록한다.
#   ./watch-stage3.sh results/stage3-timeline.csv
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:?출력 파일}"
PG="http://127.0.0.1:18080"
echo "time,pg_lookups,inbox_received,inbox_retry,inbox_gave_up,attempt_pending,confirmed,pg_state" > "$OUT"
state="failing"
for _ in $(seq 1 160); do
  lookups="$(curl -s -X POST -H 'Content-Type: application/json' -d '{"method":"GET","urlPathPattern":"/api/v1/payments/.*"}' "$PG/__admin/requests/count" | tr -dc '0-9')"
  row="$(docker exec docker-mysql-1 mysql -N -uapplication -papplication loopers -e "
    select (select count(*) from payment_callback_inbox where status = 'RECEIVED'),
           (select count(*) from payment_callback_inbox where status = 'RETRY'),
           (select count(*) from payment_callback_inbox where status = 'GAVE_UP'),
           (select count(*) from payment_attempt where status = 'PENDING'),
           (select count(*) from orders where status = 'CONFIRMED')" 2>/dev/null | tr '\t' ',')"
  echo "$(date +%H:%M:%S),$lookups,$row,$state" >> "$OUT"
  IFS=',' read -r received retry gave_up pending confirmed <<< "$row"
  # 첫 회차가 수신함을 다 훑어(받음 0, 재시도 있음) 실패를 기록했으면 PG를 정상으로 되돌린다.
  if [ "$state" = "failing" ] && [ "${received:-1}" = "0" ] && [ "${retry:-0}" != "0" ]; then
    # 규칙을 바꾸면 요청 기록이 지워지므로, 그 전에 create 수를 남긴다(INV-005 확인용). 이후 pg_lookups는 0부터 다시 센다.
    "$DIR/pg-stub/pg-stub.sh" count | tr -dc '0-9' > "${OUT%.csv}-create-count.txt"
    "$DIR/pg-stub/pg-stub.sh" stage 0 > /dev/null
    state="recovered"
  fi
  if [ "$state" = "recovered" ] && [ "${pending:-1}" = "0" ]; then
    break
  fi
  sleep 15
done

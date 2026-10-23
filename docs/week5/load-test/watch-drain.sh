#!/usr/bin/env bash
# 부하가 끝난 뒤 미종결 결제가 비워지는 과정을 30초마다 기록한다. 처리할 게 없어지거나 최대 시간이 지나면 멈춘다.
#   ./watch-drain.sh results/stage1-drain.csv 40    → 최대 40번(20분)
set -u
OUT="${1:?출력 파일}"; MAX="${2:-80}"
echo "time,inbox_due,attempt_pending,attempt_unknown,attempt_requested,confirmed" > "$OUT"
for _ in $(seq 1 "$MAX"); do
  row="$(docker exec docker-mysql-1 mysql -N -uapplication -papplication loopers -e "
    select (select count(*) from payment_callback_inbox where status in ('RECEIVED','RETRY')),
           (select count(*) from payment_attempt where status = 'PENDING'),
           (select count(*) from payment_attempt where status = 'UNKNOWN'),
           (select count(*) from payment_attempt where status = 'REQUESTED'),
           (select count(*) from orders where status = 'CONFIRMED')" 2>/dev/null | tr '\t' ',')"
  echo "$(date +%H:%M:%S),$row" >> "$OUT"
  case "$row" in 0,0,0,0,*) break ;; esac
  sleep 30
done

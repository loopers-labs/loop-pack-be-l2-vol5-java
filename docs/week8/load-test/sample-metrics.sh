#!/usr/bin/env bash
# 측정 중 1초마다 앱의 DB 커넥션 풀과 Tomcat 스레드 상태를 남긴다(관리 포트 8081의 /actuator/prometheus). 5주차 것과 같은 방식이다.
#   ./sample-metrics.sh results/c3-metrics.csv &   … 측정이 끝나면 kill
# acquire_sum_s·acquire_count는 누적값이다 — 두 줄의 차이로 그 1초 동안의 평균 커넥션 획득 시간을 구한다(sum 차이 ÷ count 차이).
set -u
OUT="${1:?출력 파일}"
echo "time,hikari_active,hikari_pending,hikari_max,acquire_sum_s,acquire_count,acquire_max_s,tomcat_busy" > "$OUT"
while true; do
  m="$(curl -s http://127.0.0.1:8081/actuator/prometheus || true)"
  value() { echo "$m" | grep -E "^$1[{ ]" | grep -m1 -E "${2:-.}" | awk '{print $NF}'; }
  P=mysql-main-pool
  echo "$(date +%H:%M:%S),$(value hikaricp_connections_active $P),$(value hikaricp_connections_pending $P),$(value hikaricp_connections_max $P),$(value hikaricp_connections_acquire_seconds_sum $P),$(value hikaricp_connections_acquire_seconds_count $P),$(value hikaricp_connections_acquire_seconds_max $P),$(value tomcat_threads_busy_threads)" >> "$OUT"
  sleep 1
done

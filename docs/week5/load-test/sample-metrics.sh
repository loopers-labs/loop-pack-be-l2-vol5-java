#!/usr/bin/env bash
# 측정 중 1초마다 앱의 DB 커넥션 풀과 Tomcat 스레드 상태를 남긴다(관리 포트 8081의 /actuator/prometheus).
#   ./sample-metrics.sh results/stage0-vu50-metrics.csv &   … 측정이 끝나면 kill
# Tomcat 스레드 지표는 앱을 --server.tomcat.mbeanregistry.enabled=true 로 띄워야 나온다.
set -u
OUT="${1:?출력 파일}"
echo "time,hikari_active,hikari_pending,hikari_max,tomcat_busy,tomcat_max" > "$OUT"
while true; do
  m="$(curl -s http://127.0.0.1:8081/actuator/prometheus || true)"
  value() { echo "$m" | grep -E "^$1[{ ]" | grep -m1 -E "${2:-.}" | awk '{print $NF}'; }
  echo "$(date +%H:%M:%S),$(value hikaricp_connections_active mysql-main-pool),$(value hikaricp_connections_pending mysql-main-pool),$(value hikaricp_connections_max mysql-main-pool),$(value tomcat_threads_busy_threads),$(value tomcat_threads_config_max_threads)" >> "$OUT"
  sleep 1
done

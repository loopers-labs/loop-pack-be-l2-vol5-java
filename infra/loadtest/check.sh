#!/usr/bin/env bash
# k6 서버에서 앱 3대에 닿는지, 앱에서 MySQL·Redis 에 닿는지 확인한다. 데이터는 바꾸지 않는다.
source "$(dirname "$0")/common.sh"
source "$HOSTS"

echo "== k6 → app (사설 IP)"
ssh_to "$K6_PUBLIC" "k6 version"
for app in "$APP_1_PRIVATE" "$APP_2_PRIVATE" "$APP_3_PRIVATE"; do
  health=$(ssh_to "$K6_PUBLIC" "curl -s $app:8081/actuator/health")
  # 없는 사용자로 조회해 앱 → MySQL 왕복을 확인한다 (404 USER_NOT_FOUND 면 DB 를 읽은 것이다)
  api=$(ssh_to "$K6_PUBLIC" "curl -s $app:8080/api/v1/coupons -H 'X-USER-ID: 999999999'")
  echo "$app health=$health api=$api"
done

echo "== app-1 → MySQL·Redis"
ssh_to "$APP_1_PUBLIC" "timeout 2 bash -c '</dev/tcp/$MYSQL_PRIVATE/3306' && echo mysql ok"
ssh_to "$APP_1_PUBLIC" "timeout 2 bash -c '</dev/tcp/$REDIS_PRIVATE/6379' && echo redis ok"
ssh_to "$REDIS_PUBLIC" "sudo docker exec redis redis-cli ping"

echo "== Prometheus 수집 대상 (job: up 개수/전체)"
ssh_to "$MONITOR_PUBLIC" "curl -s localhost:9090/api/v1/targets | jq -r '.data.activeTargets | group_by(.labels.job)[] | \"\\(.[0].labels.job): \\(map(select(.health==\"up\")) | length)/\\(length)\"'"

echo "== 지표가 들어오는지 (값이 있는 시계열 수)"
for q in tomcat_threads_busy_threads hikaricp_connections_active jvm_memory_used_bytes mysql_global_status_threads_connected node_memory_MemAvailable_bytes; do
  n=$(ssh_to "$MONITOR_PUBLIC" "curl -s 'localhost:9090/api/v1/query?query=$q' | jq '.data.result | length'")
  echo "$q: $n"
done

echo "== Grafana (이 PC 에서)"
curl -s -o /dev/null -w "http://$MONITOR_PUBLIC:3000/api/health -> %{http_code}\n" "http://$MONITOR_PUBLIC:3000/api/health"
ssh_to "$MONITOR_PUBLIC" "ls /opt/monitor/grafana/dashboards"

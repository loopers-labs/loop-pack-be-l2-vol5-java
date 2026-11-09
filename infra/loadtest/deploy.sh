#!/usr/bin/env bash
# jar 를 빌드해 앱 3대에 올리고 기동한다. 다시 실행하면 새 jar 로 재기동한다.
# 이어서 Prometheus 수집 대상을 IP 로 채우고, k6 가 지표를 Prometheus 로 보내도록 환경 변수를 둔다.
# ddl-auto: update 가 동시에 돌지 않도록 app-1 이 뜬 뒤 app-2·3 을 올린다.
source "$(dirname "$0")/common.sh"
source "$HOSTS"

(cd "$ROOT" && ./gradlew :apps:commerce-api:bootJar -q)
JAR=$(ls -t "$ROOT"/apps/commerce-api/build/libs/commerce-api-*.jar | head -1)
echo "jar: $JAR"

for host in "$MYSQL_PUBLIC" "$REDIS_PUBLIC" "$APP_1_PUBLIC" "$APP_2_PUBLIC" "$APP_3_PUBLIC" "$K6_PUBLIC" "$MONITOR_PUBLIC"; do
  wait_ssh "$host"
  ssh_to "$host" "sudo cloud-init status --wait > /dev/null" || { echo "user-data 실패: $host (sudo cat /var/log/cloud-init-output.log)" >&2; exit 1; }
done

echo "MySQL 준비 대기"
for _ in $(seq 1 60); do
  ssh_to "$MYSQL_PUBLIC" "sudo docker exec mysql mysqladmin ping -uapplication -papplication --silent" 2>/dev/null && break
  sleep 3
done

APP_ENV="JAVA_OPTS=-Xms2560m -Xmx2560m
APP_ARGS=--spring.profiles.active=prd --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://$MYSQL_PRIVATE:3306/loopers --outbox.relay.enabled=false
REDIS_MASTER_HOST=$REDIS_PRIVATE
REDIS_MASTER_PORT=6379
REDIS_REPLICA_1_HOST=$REDIS_PRIVATE
REDIS_REPLICA_1_PORT=6379
BOOTSTRAP_SERVERS=127.0.0.1:9"

deploy_app() { # host
  scp "${SSH_OPTS[@]}" "$JAR" "ec2-user@$1:/opt/commerce/commerce-api.jar"
  ssh_to "$1" "cat > /opt/commerce/app.env" <<< "$APP_ENV"
  ssh_to "$1" "sudo systemctl enable commerce-api >/dev/null 2>&1; sudo systemctl restart commerce-api"
  for _ in $(seq 1 60); do
    ssh_to "$1" "curl -sf localhost:8081/actuator/health > /dev/null" && { echo "up: $1"; return 0; }
    sleep 3
  done
  echo "기동 실패: $1 (sudo journalctl -u commerce-api -n 200)" >&2
  return 1
}

deploy_app "$APP_1_PUBLIC"
deploy_app "$APP_2_PUBLIC" &
deploy_app "$APP_3_PUBLIC" &
wait

echo "Prometheus 수집 대상 설정"
node_target() { # name private-ip
  printf '      - targets: ["%s:9100"]\n        labels: {host: "%s"}\n' "$2" "$1"
}
PROM_CONFIG="global:
  scrape_interval: 5s
scrape_configs:
  - job_name: app
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [\"$APP_1_PRIVATE:8081\"]
        labels: {host: \"app-1\"}
      - targets: [\"$APP_2_PRIVATE:8081\"]
        labels: {host: \"app-2\"}
      - targets: [\"$APP_3_PRIVATE:8081\"]
        labels: {host: \"app-3\"}
  - job_name: node
    static_configs:
$(node_target app-1 "$APP_1_PRIVATE")
$(node_target app-2 "$APP_2_PRIVATE")
$(node_target app-3 "$APP_3_PRIVATE")
$(node_target mysql "$MYSQL_PRIVATE")
$(node_target redis "$REDIS_PRIVATE")
$(node_target k6 "$K6_PRIVATE")
$(node_target monitor "$MONITOR_PRIVATE")
  - job_name: mysql
    static_configs:
      - targets: [\"$MYSQL_PRIVATE:9104\"]
        labels: {host: \"mysql\"}"
ssh_to "$MONITOR_PUBLIC" "sudo tee /opt/monitor/prometheus/prometheus.yml > /dev/null" <<< "$PROM_CONFIG"
ssh_to "$MONITOR_PUBLIC" "curl -sf -X POST localhost:9090/-/reload"

# k6 run -o experimental-prometheus-rw 가 이 주소로 보낸다
ssh_to "$K6_PUBLIC" "sudo tee /etc/profile.d/k6-prometheus.sh > /dev/null" <<EOF
export K6_PROMETHEUS_RW_SERVER_URL=http://$MONITOR_PRIVATE:9090/api/v1/write
export K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max"
EOF

echo "Grafana: http://$MONITOR_PUBLIC:3000  (admin / $(cat "$STATE/grafana-password"))"
echo "다음: check.sh"

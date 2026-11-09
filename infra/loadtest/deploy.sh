#!/usr/bin/env bash
# jar 를 빌드해 앱 3대에 올리고 기동한다. 다시 실행하면 새 jar 로 재기동한다.
# ddl-auto: update 가 동시에 돌지 않도록 app-1 이 뜬 뒤 app-2·3 을 올린다.
source "$(dirname "$0")/common.sh"
source "$HOSTS"

(cd "$ROOT" && ./gradlew :apps:commerce-api:bootJar -q)
JAR=$(ls -t "$ROOT"/apps/commerce-api/build/libs/commerce-api-*.jar | head -1)
echo "jar: $JAR"

for host in "$MYSQL_PUBLIC" "$REDIS_PUBLIC" "$APP_1_PUBLIC" "$APP_2_PUBLIC" "$APP_3_PUBLIC" "$K6_PUBLIC"; do
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

echo "다음: check.sh"

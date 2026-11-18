#!/bin/bash
# MySQL 8.0. 계정·DB 이름은 jpa.yml prd 프로필(application/application, loopers)과 같다. max_connections 는 500 (앱 3대 × Hikari 100 = 300 + 여유).
# mysqld_exporter (:9104) 가 exporter 계정(연결 최대 3)으로 MySQL 내부 지표를 내보낸다.
set -euxo pipefail
dnf install -y docker
systemctl enable --now docker
docker run -d --name mysql --restart unless-stopped -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_USER=application -e MYSQL_PASSWORD=application -e MYSQL_DATABASE=loopers \
  -v /var/lib/mysql-data:/var/lib/mysql \
  mysql:8.0 --innodb-buffer-pool-size=5G --max-connections=500

# 초기화가 끝나 root 로 접속될 때까지 계정 생성을 다시 시도한다
for _ in $(seq 1 100); do
  docker exec mysql mysql -uroot -proot -e \
    "CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY 'exporter' WITH MAX_USER_CONNECTIONS 3;
     GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';" && break
  sleep 3
done

docker run -d --name mysqld-exporter --restart unless-stopped --network host \
  -e MYSQLD_EXPORTER_PASSWORD=exporter \
  prom/mysqld-exporter:v0.15.1 \
  --mysqld.username=exporter --mysqld.address=127.0.0.1:3306 \
  --collect.info_schema.innodb_metrics \
  --collect.perf_schema.eventswaits \
  --collect.perf_schema.tableiowaits \
  --collect.perf_schema.tablelocks

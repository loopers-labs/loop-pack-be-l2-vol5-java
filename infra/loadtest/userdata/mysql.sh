#!/bin/bash
# MySQL 8.0. 계정·DB 이름은 jpa.yml prd 프로필(application/application, loopers)과 같다. max_connections 는 기본값(151).
set -euxo pipefail
dnf install -y docker
systemctl enable --now docker
docker run -d --name mysql --restart unless-stopped -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_USER=application -e MYSQL_PASSWORD=application -e MYSQL_DATABASE=loopers \
  -v /var/lib/mysql-data:/var/lib/mysql \
  mysql:8.0 --innodb-buffer-pool-size=5G

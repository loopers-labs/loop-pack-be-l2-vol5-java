#!/bin/bash
# 모든 서버 공통: node_exporter (:9100). up.sh 가 역할별 user-data 앞에 붙인다.
# GitHub 릴리스 다운로드가 504 로 자주 실패해 Docker Hub 이미지로 띄운다.
set -euxo pipefail
dnf install -y docker
systemctl enable --now docker
docker run -d --name node-exporter --restart unless-stopped --network host --pid host \
  -v /:/host:ro,rslave \
  prom/node-exporter:v1.8.2 --path.rootfs=/host

#!/bin/bash
# Redis 7.0. 지금은 쓰지 않고 이후 실험용으로 둔다. 접근은 보안 그룹이 막는다.
set -euxo pipefail
dnf install -y docker
systemctl enable --now docker
docker run -d --name redis --restart unless-stopped -p 6379:6379 \
  redis:7.0 redis-server --protected-mode no --appendonly no --save ""

#!/bin/bash
# k6 설치만 한다. 테스트 스크립트는 따로 올린다.
set -euxo pipefail
dnf install -y https://dl.k6.io/rpm/repo.rpm
dnf install -y k6
cat >> /etc/security/limits.conf <<'EOF'
ec2-user soft nofile 65536
ec2-user hard nofile 65536
EOF

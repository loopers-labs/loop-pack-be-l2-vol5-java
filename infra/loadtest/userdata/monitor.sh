#!/bin/bash
# Prometheus (:9090, k6 remote write 수신) + Grafana (:3000). 수집 대상은 deploy.sh 가 IP 로 채운 뒤 reload 한다.
# GRAFANA_PASSWORD 는 up.sh 가 user-data 앞에 넣는다.
set -euxo pipefail
dnf install -y docker jq
systemctl enable --now docker

M=/opt/monitor
mkdir -p $M/prometheus $M/grafana/provisioning/datasources $M/grafana/provisioning/dashboards $M/grafana/dashboards

cat > $M/prometheus/prometheus.yml <<'EOF'
global:
  scrape_interval: 5s
scrape_configs: []
EOF
docker run -d --name prometheus --restart unless-stopped --network host \
  -v $M/prometheus:/etc/prometheus \
  prom/prometheus:v2.54.1 \
  --config.file=/etc/prometheus/prometheus.yml --web.enable-remote-write-receiver --web.enable-lifecycle

cat > $M/grafana/provisioning/datasources/prometheus.yml <<'EOF'
apiVersion: 1
datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://localhost:9090
    isDefault: true
EOF
cat > $M/grafana/provisioning/dashboards/loadtest.yml <<'EOF'
apiVersion: 1
providers:
  - name: loadtest
    folder: loadtest
    type: file
    options:
      path: /etc/grafana/dashboards
EOF
# grafana.com 공개 대시보드. 데이터소스 입력값(${DS_...})을 위 Prometheus uid 로 바꿔 둔다.
#   1860 Node Exporter Full / 14057 MySQL / 4701 JVM (Micrometer) / 19004 Spring Boot 3.x (Tomcat·HikariCP·HTTP) / 19665 k6 Prometheus
for id in 1860 14057 4701 19004 19665; do
  curl -fsSL --retry 10 --retry-all-errors --retry-delay 3 "https://grafana.com/api/dashboards/$id/revisions/latest/download" |
    sed -E 's/\$\{DS_[A-Za-z0-9_]+\}/prometheus/g' > $M/grafana/dashboards/$id.json
done
chmod -R a+r $M/grafana

docker run -d --name grafana --restart unless-stopped --network host \
  -e GF_SECURITY_ADMIN_PASSWORD="$GRAFANA_PASSWORD" \
  -v $M/grafana/provisioning:/etc/grafana/provisioning \
  -v $M/grafana/dashboards:/etc/grafana/dashboards \
  grafana/grafana:11.2.0

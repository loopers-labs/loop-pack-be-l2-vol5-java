#!/bin/bash
# commerce-api 실행 환경. jar 와 app.env 는 deploy.sh 가 올린다.
set -euxo pipefail
dnf install -y java-21-amazon-corretto-headless
mkdir -p /opt/commerce
chown ec2-user:ec2-user /opt/commerce
cat > /etc/systemd/system/commerce-api.service <<'EOF'
[Unit]
Description=commerce-api
After=network-online.target
Wants=network-online.target

[Service]
User=ec2-user
WorkingDirectory=/opt/commerce
EnvironmentFile=/opt/commerce/app.env
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/commerce/commerce-api.jar $APP_ARGS
Restart=on-failure
RestartSec=5
LimitNOFILE=65536
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload

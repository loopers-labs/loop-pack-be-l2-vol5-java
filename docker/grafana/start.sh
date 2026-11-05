#!/bin/sh
set -eu

# 원본 설정을 수정하지 않고 컨테이너의 임시 복사본으로 시작합니다.
provisioning_directory="$(mktemp -d /tmp/grafana-provisioning.XXXXXX)"
cp -R /etc/grafana/provisioning/. "$provisioning_directory/"

# 웹훅이 없으면 Outbox Slack 연락처와 해당 연락처를 사용하는 규칙을 함께 제외합니다.
if [ -z "${SLACK_WEBHOOK_URI:-}" ]; then
  rm -f "$provisioning_directory/alerting/outbox-slack.yml" \
    "$provisioning_directory/alerting/outbox-blocked-rule.yml"
fi

export GF_PATHS_PROVISIONING="$provisioning_directory"
exec /run.sh

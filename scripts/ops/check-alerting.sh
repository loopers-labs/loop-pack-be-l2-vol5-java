#!/usr/bin/env bash
# 알림 규칙·라우팅·대시보드 검사(10주차 4단계 4a). gradle check에는 넣지 않는다(Docker 필요).
# 1) promtool check rules / check config  2) promtool test rules(0건이면 실패)
# 3) amtool check-config / 라우팅 3건      4) 대시보드 JSON·datasource·dashboard_url·runbook 앵커 일치
# 어느 단계든 실패하거나 도구가 실행되지 않으면 0이 아닌 값으로 끝난다.
set -euo pipefail

# Testcontainers용 DOCKER_CONFIG(gradle/testcontainers-docker-config)가 셸에 남아 있어도 기본 docker 설정을 쓴다.
unset DOCKER_CONFIG

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROMETHEUS_IMAGE="prom/prometheus:v3.5.0"
ALERTMANAGER_IMAGE="prom/alertmanager:v0.28.1"
RULES_DIR="$ROOT/docker/prometheus/rules"
AM_DIR="$ROOT/docker/alertmanager"
PROM_CONFIG="$ROOT/docker/grafana/prometheus.yml"
DASHBOARD="$ROOT/docker/grafana/provisioning/dashboards/payment-incident.json"
DATASOURCE="$ROOT/docker/grafana/provisioning/datasources/datasource.yml"
RUNBOOK="$ROOT/docs/runbook/week10.md"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

step() {
    printf '\n== %s\n' "$1"
}

promtool() {
    docker run --rm --network none \
        -v "$RULES_DIR:/etc/prometheus/rules:ro" \
        -v "$PROM_CONFIG:/etc/prometheus/prometheus.yml:ro" \
        -v "$WORK_DIR:/work" \
        --entrypoint promtool "$PROMETHEUS_IMAGE" "$@"
}

amtool() {
    docker run --rm --network none \
        -v "$AM_DIR:/etc/alertmanager:ro" \
        --entrypoint amtool "$ALERTMANAGER_IMAGE" "$@"
}

step "promtool check rules"
promtool check rules /etc/prometheus/rules/payment.yml

step "promtool check config"
promtool check config /etc/prometheus/prometheus.yml

step "promtool test rules"
promtool test rules --junit=/work/junit.xml /etc/prometheus/rules/payment_test.yml
python3 - "$WORK_DIR/junit.xml" "$RULES_DIR/payment_test.yml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

junit_path, test_file = sys.argv[1], sys.argv[2]
cases = ET.parse(junit_path).getroot().iter("testcase")
cases = list(cases)
failed = [c.get("name") for c in cases if c.find("failure") is not None or c.find("error") is not None]
skipped = [c.get("name") for c in cases if c.find("skipped") is not None]
declared = len(re.findall(r"^  - name: ", open(test_file, encoding="utf-8").read(), re.M))
for c in cases:
    print("  PASS" if c.get("name") not in failed else "  FAIL", c.get("name"))
print(f"  executed={len(cases)} declared={declared} failed={len(failed)} skipped={len(skipped)}")
if len(cases) == 0 or len(cases) != declared or failed or skipped:
    sys.exit("rule tests: 0건, 선언과 실행 수 불일치, 실패 또는 skip")
PY

step "amtool check-config"
amtool check-config /etc/alertmanager/alertmanager.yml

step "amtool config routes test"
route_case() {
    local expected="$1"
    shift
    printf '  labels=[%s] expected=%s -> ' "$*" "$expected"
    amtool config routes test --config.file=/etc/alertmanager/alertmanager.yml \
        --verify.receivers="$expected" "$@"
}
route_case guide-webhook alertname=PaymentUnresolvedPiling severity=critical
route_case slack alertname=PaymentNeedsReview severity=warning
route_case slack alertname=SomethingWithoutSeverity

step "dashboard / rule links"
python3 - "$DASHBOARD" "$DATASOURCE" "$RULES_DIR/payment.yml" "$RUNBOOK" <<'PY'
import json
import re
import sys

dashboard_path, datasource_path, rules_path, runbook_path = sys.argv[1:5]
errors = []

dashboard = json.load(open(dashboard_path, encoding="utf-8"))
uid = dashboard.get("uid")
if not uid:
    errors.append("dashboard uid 없음")

datasource_uids = set(re.findall(r"^\s*uid:\s*(\S+)\s*$", open(datasource_path, encoding="utf-8").read(), re.M))
panels = dashboard.get("panels", [])
if not panels:
    errors.append("dashboard panel 없음")
for panel in panels:
    refs = [panel.get("datasource")] + [t.get("datasource") for t in panel.get("targets", [])]
    for ref in refs:
        if not ref or ref.get("uid") not in datasource_uids:
            errors.append(f"panel '{panel.get('title')}' datasource {ref} 가 provisioning uid {sorted(datasource_uids)}에 없음")
    if not panel.get("targets"):
        errors.append(f"panel '{panel.get('title')}' query 없음")
    if "docs/runbook/week10.md#" not in panel.get("description", ""):
        errors.append(f"panel '{panel.get('title')}' 설명에 runbook 링크 없음")

rules = open(rules_path, encoding="utf-8").read()
alerts = re.findall(r"^\s*- alert:\s*(\S+)", rules, re.M)
dashboard_urls = re.findall(r"^\s*dashboard_url:\s*(\S+)", rules, re.M)
runbook_urls = re.findall(r"^\s*runbook_url:\s*(\S+)", rules, re.M)
for key, found in (("severity", re.findall(r"^\s*severity:\s*(critical|warning)\s*$", rules, re.M)),
                   ("summary", re.findall(r"^\s*summary:\s*\S", rules, re.M)),
                   ("dashboard_url", dashboard_urls), ("runbook_url", runbook_urls)):
    if len(found) != len(alerts):
        errors.append(f"{key} {len(found)}개, 규칙 {len(alerts)}개")
for url in dashboard_urls:
    if not url.rstrip("/").endswith(f"/d/{uid}"):
        errors.append(f"dashboard_url {url} 이 대시보드 uid {uid}를 가리키지 않음")
rule_lines = "\n".join(line.split("#", 1)[0] for line in rules.splitlines() if not line.lstrip().startswith("#"))
if re.search(r"order_?id|user_?id", rule_lines, re.I):
    errors.append("규칙 파일에 orderId·userId가 있음")

anchors = {re.sub(r"[^a-z0-9_-]", "", h.strip().lower().replace(" ", "-"))
           for h in re.findall(r"^#+\s+(.+)$", open(runbook_path, encoding="utf-8").read(), re.M)}
for url in runbook_urls:
    path, _, anchor = url.partition("#")
    if path != "docs/runbook/week10.md" or anchor not in anchors:
        errors.append(f"runbook_url {url} 의 앵커가 runbook에 없음")

print(f"  dashboard uid={uid} panels={len(panels)} datasource_uids={sorted(datasource_uids)}")
print(f"  rules={len(alerts)} dashboard_url={len(dashboard_urls)} runbook_url={len(runbook_urls)} runbook_anchors={len(anchors)}")
if errors:
    for e in errors:
        print("  ERROR", e)
    sys.exit(1)
print("  OK")
PY

printf '\nALL CHECKS PASSED\n'

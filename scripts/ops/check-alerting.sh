#!/usr/bin/env bash
# 알림 규칙·라우팅·대시보드·로그 수집·가이드 서버 검사(10주차 4단계 4a·4b). gradle check에는 넣지 않는다(Docker 필요).
# 1) promtool check rules / check config  2) promtool test rules(0건이면 실패, R1~R14·L1~L5·S1~S6·S5b)
# 3) amtool check-config / 라우팅(A1: critical → 가이드와 Slack 자리 둘 다)
# 4) 대시보드 JSON·datasource·dashboard_url·runbook 앵커 일치
# 5) Alloy validate + label은 app·level만  6) Loki verify-config  7) 가이드 서버 unittest(0건·skip이면 실패)
# 어느 단계든 실패하거나 도구가 실행되지 않으면 0이 아닌 값으로 끝난다.
set -euo pipefail

# Testcontainers용 DOCKER_CONFIG(gradle/testcontainers-docker-config)가 셸에 남아 있어도 기본 docker 설정을 쓴다.
unset DOCKER_CONFIG

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROMETHEUS_IMAGE="prom/prometheus:v3.5.0"
ALERTMANAGER_IMAGE="prom/alertmanager:v0.28.1"
ALLOY_IMAGE="grafana/alloy:v1.10.1"
LOKI_IMAGE="grafana/loki:3.5.3"
PYTHON_IMAGE="python:3.12.11-slim"
RULES_DIR="$ROOT/docker/prometheus/rules"
AM_DIR="$ROOT/docker/alertmanager"
PROM_CONFIG="$ROOT/docker/grafana/prometheus.yml"
DASHBOARD="$ROOT/docker/grafana/provisioning/dashboards/payment-incident.json"
DATASOURCE="$ROOT/docker/grafana/provisioning/datasources/datasource.yml"
RUNBOOK="$ROOT/docs/runbook/week10.md"
# 운영 규칙·대시보드의 runbook 링크는 눌러서 열리는 저장소 웹 주소다(과제 최종 목적지인 원본 저장소, PR 머지 전에는 열리지 않을 수 있음).
RUNBOOK_WEB_URL="https://github.com/loopers-labs/loop-pack-be-l2-vol5-java/blob/L2J4Dot/docs/runbook/week10.md"
ALLOY_DIR="$ROOT/docker/alloy"
LOKI_DIR="$ROOT/docker/loki"
GUIDE_DIR="$ROOT/docker/guide"

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
# A1: critical은 가이드와 Slack 자리 둘 다로 간다(continue). 가이드가 죽어도 원본이 간다.
route_case guide-webhook,slack alertname=PaymentUnresolvedPiling severity=critical
route_case guide-webhook,slack alertname=PaymentAbandonedPiling severity=critical
route_case guide-webhook,slack alertname=PaymentRefundPiling severity=critical
route_case slack alertname=PaymentRefundNeedsPerson severity=warning
route_case slack alertname=PaymentRefundRequestStuck severity=warning
route_case slack alertname=PaymentRefundUnknown severity=warning
route_case slack alertname=PaymentRefundApplyFailed severity=warning
route_case slack alertname=PaymentNeedsReview severity=warning
route_case slack alertname=GuideServerDown severity=warning
route_case slack alertname=CommerceLogIngestionSilent severity=warning
route_case slack alertname=PaymentApiLatencyP99High severity=warning
route_case slack alertname=SomethingWithoutSeverity

step "dashboard / rule links"
python3 - "$DASHBOARD" "$DATASOURCE" "$RULES_DIR/payment.yml" "$RUNBOOK" "$RUNBOOK_WEB_URL" <<'PY'
import json
import re
import sys

dashboard_path, datasource_path, rules_path, runbook_path, runbook_web_url = sys.argv[1:6]
errors = []
# GitHub 앵커 규칙: 소문자, 글자·숫자(한글 포함)·공백·-·_ 외 문자는 지우고 공백은 -.
anchors = {re.sub(r"[^\w\- ]", "", h.strip().lower()).replace(" ", "-")
           for h in re.findall(r"^#+\s+(.+)$", open(runbook_path, encoding="utf-8").read(), re.M)}

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
    description = panel.get("description", "")
    panel_anchors = re.findall(re.escape(runbook_web_url) + r"#([\w-]+)", description)
    if not panel_anchors:
        errors.append(f"panel '{panel.get('title')}' 설명에 runbook 링크({runbook_web_url}#앵커) 없음")
    for anchor in panel_anchors:
        if anchor not in anchors:
            errors.append(f"panel '{panel.get('title')}' runbook 앵커 #{anchor} 가 runbook에 없음")
    if re.search(r"(?<![/\w])docs/runbook/week10\.md#", description):
        errors.append(f"panel '{panel.get('title')}' 설명에 눌러서 열리지 않는 상대 경로 runbook 링크가 있음")
for link in dashboard.get("links", []):
    if "runbook" in link.get("title", "") and link.get("url") != runbook_web_url:
        errors.append(f"dashboard link {link.get('url')} 이 runbook 웹 주소가 아님")

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

for url in runbook_urls:
    path, _, anchor = url.partition("#")
    if path != runbook_web_url or anchor not in anchors:
        errors.append(f"runbook_url {url} 이 runbook 웹 주소가 아니거나 앵커가 runbook에 없음")

print(f"  dashboard uid={uid} panels={len(panels)} datasource_uids={sorted(datasource_uids)}")
print(f"  rules={len(alerts)} dashboard_url={len(dashboard_urls)} runbook_url={len(runbook_urls)} runbook_anchors={len(anchors)}")
if errors:
    for e in errors:
        print("  ERROR", e)
    sys.exit(1)
print("  OK")
PY

step "alloy validate"
docker run --rm --network none -v "$ALLOY_DIR:/etc/alloy:ro" "$ALLOY_IMAGE" validate /etc/alloy/config.alloy
python3 - "$ALLOY_DIR/config.alloy" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()


def block_keys(name):
    found = re.findall(name + r"\s*\{\s*values\s*=\s*\{(.*?)\}", text, re.S)
    return [set(re.findall(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=", body, re.M)) for body in found]


labels = block_keys(r"stage\.labels")
metadata = block_keys(r"stage\.structured_metadata")
keys = set().union(*labels) if labels else set()
print(f"  labels={sorted(keys)} structured_metadata={sorted(set().union(*metadata)) if metadata else []}")
errors = []
if keys != {"app", "level"}:
    errors.append(f"label은 app·level만이어야 함: {sorted(keys)}")
want = {"orderId", "providerOrderId", "attemptKey", "transactionKey"}
if not metadata or not want <= set().union(*metadata):
    errors.append("orderId·providerOrderId·attemptKey·transactionKey가 structured metadata에 없음")
if errors:
    sys.exit("  ERROR " + "; ".join(errors))
print("  OK")
PY

step "loki verify-config"
docker run --rm --network none -v "$LOKI_DIR:/etc/loki:ro" "$LOKI_IMAGE" \
    -config.file=/etc/loki/loki.yml -verify-config
echo "  OK"

step "guide unittest"
# -i: 아래 검사 스크립트를 stdin으로 넘긴다. 빠지면 빈 스크립트가 0으로 끝나 거짓 통과가 된다.
docker run -i --rm --network none -e PYTHONDONTWRITEBYTECODE=1 -v "$GUIDE_DIR:/app:ro" -w /app "$PYTHON_IMAGE" \
    python - <<'PY'
import sys
import unittest

result = unittest.TextTestRunner(verbosity=2, stream=sys.stdout).run(unittest.defaultTestLoader.loadTestsFromName("test_guide"))
print(f"  executed={result.testsRun} failed={len(result.failures)} errors={len(result.errors)} skipped={len(result.skipped)}")
if result.testsRun == 0 or result.failures or result.errors or result.skipped:
    sys.exit("guide tests: 0건, 실패, 오류 또는 skip")
PY

printf '\nALL CHECKS PASSED\n'

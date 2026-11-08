#!/usr/bin/env python3
"""Grafana에 설정된 실제 쿼리를 격리된 promtool로 검증한다."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile

# (provisioning 규칙 파일, promtool 테스트 파일, 테스트 안의 쿼리 자리 표시)
ALERTS = [
    ("outbox-blocked-rule.yml", "outbox-blocked.test.yml", "__OUTBOX_BLOCKED_EXPR__"),
    ("outbox-delay-rule.yml", "outbox-delay.test.yml", "__OUTBOX_DELAY_EXPR__"),
]

THRESHOLD = re.compile(r"evaluator:\s*\n\s*params:\s*\n\s*-\s*([0-9.]+)\s*\n\s*type:\s*gt\b")


def alert_condition(rule):
    text = rule.read_text()
    expressions = re.findall(r"^\s+expr: (.+)$", text, re.MULTILINE)
    thresholds = THRESHOLD.findall(text)
    if len(expressions) != 1 or len(thresholds) != 1:
        raise ValueError(f"{rule.name}에서 한 줄 PromQL 쿼리와 gt 임계값을 하나씩 찾지 못했습니다.")
    # Grafana의 threshold 조건까지 적용해 알림 발생 여부를 1/0으로 비교한다.
    return f"({expressions[0]}) > bool {thresholds[0]}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prometheus-image", default="prom/prometheus:v2.54.1")
    args = parser.parse_args()

    directory = Path(__file__).resolve().parent
    alerting = directory.parent / "provisioning/alerting"

    with tempfile.TemporaryDirectory(prefix="outbox-alert-tests-") as temporary:
        test_files = []
        for rule_file, test_file, placeholder in ALERTS:
            template = (directory / test_file).read_text()
            if placeholder not in template:
                raise ValueError(f"{test_file}에 실제 쿼리를 넣을 위치가 없습니다.")
            condition = alert_condition(alerting / rule_file)
            (Path(temporary) / test_file).write_text(template.replace(placeholder, json.dumps(condition)))
            test_files.append(f"/tests/{test_file}")
        subprocess.run([
            "docker", "run", "--rm", "--network", "none",
            "--mount", f"type=bind,source={temporary},target=/tests,readonly",
            "--entrypoint", "/bin/promtool", args.prometheus_image,
            "test", "rules", *test_files,
        ], check=True, timeout=60)


if __name__ == "__main__":
    main()

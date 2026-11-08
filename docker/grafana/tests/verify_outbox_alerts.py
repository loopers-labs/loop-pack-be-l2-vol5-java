#!/usr/bin/env python3
"""Grafana에 설정된 실제 쿼리를 격리된 promtool로 검증한다."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prometheus-image", default="prom/prometheus:v2.54.1")
    args = parser.parse_args()

    directory = Path(__file__).resolve().parent
    rule = directory.parent / "provisioning/alerting/outbox-blocked-rule.yml"
    expressions = re.findall(r"^\s+expr: (.+)$", rule.read_text(), re.MULTILINE)
    if len(expressions) != 1:
        raise ValueError("Outbox 알림 규칙의 한 줄 PromQL 쿼리를 하나 찾지 못했습니다.")

    # Grafana의 기존 threshold > 0 조건까지 적용해 알림 발생 여부를 비교한다.
    expression = f"({expressions[0]}) > bool 0"
    template = (directory / "outbox-blocked.test.yml").read_text()
    if "__OUTBOX_BLOCKED_EXPR__" not in template:
        raise ValueError("Outbox 알림 테스트에 실제 쿼리를 넣을 위치가 없습니다.")

    with tempfile.TemporaryDirectory(prefix="outbox-alert-tests-") as temporary:
        tests = Path(temporary) / "outbox-blocked.test.yml"
        tests.write_text(template.replace("__OUTBOX_BLOCKED_EXPR__", json.dumps(expression)))
        subprocess.run([
            "docker", "run", "--rm", "--network", "none",
            "--mount", f"type=bind,source={temporary},target=/tests,readonly",
            "--entrypoint", "/bin/promtool", args.prometheus_image,
            "test", "rules", "/tests/outbox-blocked.test.yml",
        ], check=True, timeout=60)


if __name__ == "__main__":
    main()

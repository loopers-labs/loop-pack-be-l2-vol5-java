#!/usr/bin/env python3
"""JUnit 출력의 실측 기록을 JSON과 비교 표로 모은다. 테스트 성공을 먼저 확인한다."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
REPORTS = ROOT / "apps/commerce-api/build/test-results/test"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "results",
                    help="원본을 보존하려면 실행별 새 출력 디렉터리를 지정한다.")
parser.add_argument("--include-arrivals", action="store_true", help="초당 10건 도착률 비교도 수집한다.")
args = parser.parse_args()
OUTPUT = args.output
SOURCES = [
    "apps/commerce-api/src/main/resources/application.yml",
    "apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/TestPgPaymentGateway.java",
    "apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/CircuitBreakingPaymentGateway.java",
    "apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/PgLookupCircuitBreakerConfiguration.java",
    "apps/commerce-api/src/main/resources/pg-test/circuit-breaker.yml",
    "apps/test-pg/src/main/java/com/loopers/testpg/interfaces/PaymentV1Controller.java",
    "apps/test-pg/src/main/java/com/loopers/testpg/domain/LookupScenario.java",
    "apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgLookupBaselineExperimentTest.java",
    "apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgLookupCircuitBreakerExperimentTest.java",
    "apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgLookupCircuitBreakerArrivalExperimentTest.java",
    "apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/TestPgPaymentGatewayCancellationTest.java",
    "apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderPaymentCircuitBreakerE2ETest.java",
    "docs/performance/payment-lookup/collect-results.py",
]


def records(class_name, prefix, expected):
    root = ET.parse(REPORTS / f"TEST-{class_name}.xml").getroot()
    if any(int(root.get(key, "0")) for key in ("failures", "errors", "skipped")):
        raise SystemExit(f"성공하지 않은 실험: {class_name}")
    found = []
    for output in root.iter("system-out"):
        for line in (output.text or "").splitlines():
            if line.startswith(prefix + " "):
                found.append(json.loads(line.split(" ", 1)[1]))
    if len(found) != expected:
        raise SystemExit(f"기록 수 불일치: {class_name}: {len(found)} != {expected}")
    return found


baseline = records("com.loopers.infrastructure.payment.PgLookupBaselineExperimentTest", "PG_LOOKUP_BASELINE", 4)
circuit = records("com.loopers.infrastructure.payment.PgLookupCircuitBreakerExperimentTest", "PG_LOOKUP_CIRCUIT", 2)
business = records("com.loopers.interfaces.api.order.OrderPaymentCircuitBreakerE2ETest", "PG_LOOKUP_BUSINESS", 1)
arrivals = records("com.loopers.infrastructure.payment.PgLookupCircuitBreakerArrivalExperimentTest",
                   "PG_LOOKUP_ARRIVAL", 4) if args.include_arrivals else []
metadata = {
    "collectedAt": datetime.now(timezone.utc).isoformat(),
    "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
    "workingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)),
    "sourceSha256": {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in SOURCES},
    "scope": "Local separate PG container; sequential calls and optional fixed arrivals at the adapter boundary. "
             "Not a full-order load/SLO benchmark. Sequential circuit timings exclude OPEN wait.",
}
OUTPUT.mkdir(parents=True, exist_ok=True)
for name, runs in (("baseline", baseline), ("circuit", circuit), ("business", business)):
    (OUTPUT / f"{name}.json").write_text(json.dumps({**metadata, "runs": runs}, ensure_ascii=False, indent=2) + "\n")
if arrivals:
    (OUTPUT / "arrivals.json").write_text(json.dumps({**metadata, "runs": arrivals}, ensure_ascii=False, indent=2) + "\n")

lines = [
    "# 실측 비교", "", "아래 값은 저장한 JUnit 관측 기록에서 계산했다. 각 조건은 순차 요청 8회이며 동시 부하 측정값은 아니다.", "",
    "| 조회 | 조건 | 요청 수 | 실제 PG 조회 | 요청 대기 합(ms) | 요청 평균(ms) |",
    "| --- | --- | ---: | ---: | ---: | ---: |",
]
for mode in ("TRANSACTION", "ORDER"):
    for run in sorted((r for r in baseline if r["lookupMode"] == mode), key=lambda r: r["pgLookupDelayMillis"]):
        times = run["elapsedMillis"]
        phase = "지연 0ms / 회로 없음" if run["pgLookupDelayMillis"] == 0 else "지연 1500ms / 회로 없음"
        lines.append(f"| {mode} | {phase} | {len(times)} | {run['actualPgLookupRequests']} | {sum(times):.3f} | {sum(times)/len(times):.3f} |")
    run = next(r for r in circuit if r["lookupMode"] == mode)
    initial = [call for call in run["calls"] if call["phase"] in ("FAULT", "BLOCKED")]
    times = [call["elapsedMillis"] for call in initial]
    lines.append(f"| {mode} | 지연 1500ms / 회로 적용 | {len(times)} | 5 (테스트 검증) | {sum(times):.3f} | {sum(times)/len(times):.3f} |")
lines += ["", "회로 적용 시 최초 5회는 timeout이고 다음 3회는 로컬에서 차단됐다. 실패 탐색 2회와 회복 탐색 2회까지 합치면 실제 PG 조회는 9회다.",
          "", "업무 경로에서는 OPEN 동안 PAYMENT_PENDING·PENDING과 상품별 가용 재고 8·포인트 100000을 유지했다. 회복 뒤 두 주문을 기존 거래로 확정했고, 종결 재요청은 PG 조회를 추가하지 않았다.",
          "", "원시 기록: [baseline.json](baseline.json), [circuit.json](circuit.json), [business.json](business.json)."]
if arrivals:
    lines += ["", "## 초당 조회 10건 비교", "",
              "4초간 조회 40회를 예약하고 작업 스레드는 10개로 고정했다. 개별 시작 지연도 원본에 기록했다.",
              "전체 주문의 5분 부하 측정과는 경계·기간이 다르며 운영 SLO 판정에 쓰지 않는다.", "",
              "| 조회 | 회로 | 시도 | 실제 PG 조회 | 로컬 차단 | 요청 대기 합(ms) | 최대 시작 지연(ms) |",
              "| --- | --- | ---: | ---: | ---: | ---: | ---: |"]
    for mode in ("TRANSACTION", "ORDER"):
        for run in sorted((r for r in arrivals if r["lookupMode"] == mode), key=lambda r: r["breakerEnabled"]):
            calls = run["calls"]
            lines.append(f"| {mode} | {'ON' if run['breakerEnabled'] else 'OFF'} | {len(calls)} | "
                         f"{run['actualPgLookupRequests']} | {run['blockedLocally']} | "
                         f"{sum(c['elapsedMillis'] for c in calls):.3f} | {max(c['startLatenessMillis'] for c in calls):.3f} |")
    lines += ["", "회로가 열리기 전에 이미 시작한 요청은 계속 수행될 수 있다. 최소 표본 5회를 실제 PG 호출 상한 5회로 해석하지 않는다.",
              "", "원시 기록: [arrivals.json](arrivals.json)."]
(OUTPUT / "summary.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))

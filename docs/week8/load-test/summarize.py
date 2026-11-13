"""단계별 결과(k6 요약 + 1초 지표)를 표 하나로 모은다. 표준 라이브러리만 쓴다.

    python summarize.py                → results/summary.csv 를 쓰고 표를 출력한다
    python summarize.py vu-eq-c        → 접두사가 다른 측정(results/vu-eq-c-c3.json …)을 모은다
"""
import csv
import json
import os
import sys

DIR = os.path.dirname(os.path.abspath(__file__))
R = os.path.join(DIR, "results")
CS = [1, 3, 6, 12, 24, 40]


def metric(m, name, key, default=0.0):
    return m.get(name, {}).get(key, default)


def main():
    prefix = sys.argv[1] + "-" if len(sys.argv) > 1 else ""
    rows = []
    for c in CS:
        name = f"{prefix}c{c}"
        path = os.path.join(R, f"{name}.json")
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            m = json.load(f)["metrics"]
        with open(os.path.join(R, f"{name}-metrics.csv"), encoding="utf-8") as f:
            samples = [s for s in csv.DictReader(f) if s["hikari_active"] and s["acquire_count"]]
        first, last = samples[0], samples[-1]
        acq_n = float(last["acquire_count"]) - float(first["acquire_count"])
        acq_avg_ms = 1000 * (float(last["acquire_sum_s"]) - float(first["acquire_sum_s"])) / acq_n if acq_n else 0.0
        rows.append({
            "c": c,
            "issue_201_per_s": round(metric(m, "issue_201", "rate"), 2),
            "n_201": int(metric(m, "issue_201", "count")),
            "n_200": int(metric(m, "issue_200", "count")),
            "n_409": int(metric(m, "issue_409", "count")),
            "n_429": int(metric(m, "issue_429", "count")),
            "n_503": int(metric(m, "issue_503", "count")),
            "n_500": int(metric(m, "issue_500", "count")),
            "n_other": int(metric(m, "issue_other", "count")),
            "issue_all_p50_ms": round(metric(m, "issue_duration", "p(50)"), 1),
            "issue_all_p95_ms": round(metric(m, "issue_duration", "p(95)"), 1),
            "issue_201_p50_ms": round(metric(m, "issue_201_duration", "p(50)"), 1),
            "issue_201_p95_ms": round(metric(m, "issue_201_duration", "p(95)"), 1),
            "hikari_active_max": int(max(float(s["hikari_active"]) for s in samples)),
            "hikari_pending_max": int(max(float(s["hikari_pending"]) for s in samples)),
            "acquire_avg_ms": round(acq_avg_ms, 1),
            "tomcat_busy_max": int(max(float(s["tomcat_busy"] or 0) for s in samples)),
            "list_p50_ms": round(metric(m, "list_duration", "p(50)"), 1),
            "list_p95_ms": round(metric(m, "list_duration", "p(95)"), 1),
            "list_p99_ms": round(metric(m, "list_duration", "p(99)"), 1),
            "list_not_200": int(metric(m, "list_not_200", "count")),
        })
    if not rows:
        print("결과 없음")
        return
    out = os.path.join(R, f"{prefix}summary.csv")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(",".join(rows[0].keys()))
    for r in rows:
        print(",".join(str(v) for v in r.values()))


if __name__ == "__main__":
    main()

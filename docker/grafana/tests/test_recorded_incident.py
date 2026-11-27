"""Check the embedded Grafana samples against the original W10 evidence, offline."""

import csv
import io
import json
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[3]
DASHBOARD = ROOT / "docker/grafana/dashboards/w10-payment-incident.json"
ARCHIVE = ROOT / "docs/week10/evidence/checkout-incident-stage6.zip"
KST = timezone(timedelta(hours=9))


def instant(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


class RecordedIncidentTest(unittest.TestCase):
    def test_chart_and_both_tables_match_one_archived_run(self):
        dashboard = json.loads(DASHBOARD.read_text())
        chart, summary, order = (
            list(csv.DictReader(io.StringIO(panel["targets"][0]["csvContent"])))
            for panel in dashboard["panels"]
        )
        self.assertEqual([len(chart), len(summary), len(order)], [3, 3, 2])
        self.assertEqual([int(row["미확인 결제"]) for row in chart], [0, 3, 0])
        with ZipFile(ARCHIVE) as archive:
            for i, (phase, label) in enumerate([
                ("baseline", "정상"), ("fault", "응답 유실"), ("recovery", "복구 후")
            ]):
                prefix = "incident/" + phase + "/"
                timestamp = archive.read(prefix + "recorded-at.txt").decode().strip()
                state = json.loads(archive.read(prefix + "state.json"))
                metric_prefix = 'commerce_payment_unknown{application="commerce-api"} '
                metrics = archive.read(prefix + "prometheus.txt").decode().splitlines()
                samples = [s[len(metric_prefix):] for s in metrics if s.startswith(metric_prefix)]
                self.assertEqual([float(s) for s in samples], [int(chart[i]["미확인 결제"])])
                self.assertEqual(chart[i]["time"], timestamp)
                self.assertLess(instant(dashboard["time"]["from"]), instant(timestamp))
                self.assertLess(instant(timestamp), instant(dashboard["time"]["to"]))
                self.assertEqual(summary[i], {
                    "관측 단계": label,
                    "시각(KST)": instant(timestamp).astimezone(KST).strftime("%H:%M:%S.%f"),
                    "health": json.loads(archive.read(prefix + "health.json"))["status"],
                    "미확인": str(len(state["unknown"])),
                    "PG 승인": str(sum(t["status"] == "SUCCESS" for t in state["pg"].values())),
                    "내부 확정": str(sum(o["orderStatus"] == "CONFIRMED" for o in state["orders"].values())),
                    "가용 재고": str(state["availableStock"]),
                })
                if phase != "baseline":
                    pg, local = state["pg"]["12"], state["orders"]["12"]
                    self.assertEqual(order[i - 1], {
                        "관측 단계": label, "PG 거래": pg["transactionKey"], "PG 상태": pg["status"],
                        "내부 주문": local["orderStatus"], "내부 결제": local["paymentStatus"],
                        "재고 예약": local["reservationStatus"],
                    })


if __name__ == "__main__":
    unittest.main()

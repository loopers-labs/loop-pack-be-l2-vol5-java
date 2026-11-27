"""Compare water-order SLI in windows aligned to each run's normal-phase start (all times UTC)."""
import argparse
import csv
import json
from bisect import bisect_left
from datetime import datetime, timedelta, timezone
from pathlib import Path


NORMAL, COMPARISON, RECOVERY = PHASES = ("normal", "comparison", "recovery")
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
SLO = timedelta(seconds=30)
MAX_TELEMETRY_GAP_SECONDS = 8


def parse_time(value):
    stamp = datetime.fromisoformat(value)
    return stamp.astimezone(timezone.utc) if stamp.tzinfo else stamp.replace(tzinfo=timezone.utc)


def csv_rows(path, delimiter=","):
    with path.open(encoding="utf-8-sig", newline="") as source:
        return list(csv.DictReader(source, delimiter=delimiter))


class Run:
    def __init__(self, folder):
        self.folder = folder
        summary = json.loads((folder / "result.json").read_text(encoding="utf-8"))
        self.finished = parse_time(summary["finished_at"])
        self.phases = {p["phase"]: p for p in summary["phases"]}
        if len(summary["phases"]) != 3 or set(self.phases) != set(PHASES):
            raise ValueError(f"{folder}: expected three distinct phases")
        self.anchor = parse_time(self.phases[NORMAL]["starts_at"])
        water = csv_rows(folder / "water-orders.tsv", delimiter="\t")
        if (len({r["order_id"] for r in water}) != len(water) or len({r["attempt_key"] for r in water}) != len(water)
                or len(water) != int(summary["water_accepted_total"])):
            raise ValueError(f"{folder}: incomplete or duplicate water order ledger")
        self.orders = []
        for r in water:
            accepted = parse_time(r["accepted_at"])
            confirmed = None if r["confirmed_at"] in ("NULL", "") else parse_time(r["confirmed_at"])
            within = confirmed is not None and timedelta() <= confirmed - accepted <= SLO
            if int(r["within_30s"]) != int(within):
                raise ValueError(f"{folder}: inconsistent 30-second outcome for order {r['order_id']}")
            self.orders.append((accepted, int(r["order_id"]), within))
        telemetry = csv_rows(folder / "telemetry.csv")
        self.times = [EPOCH + timedelta(milliseconds=int(p["timestampMs"])) for p in telemetry]
        self.readers = [int(p["activeReaders"]) for p in telemetry]
        if any(later <= earlier for earlier, later in zip(self.times, self.times[1:])):
            raise ValueError(f"{folder}: generator telemetry timestamps must be increasing")

    def cohort(self, start, end):
        first = bisect_left(self.times, start)
        stop = bisect_left(self.times, end)
        bracket_start = first if first < len(self.times) and self.times[first] == start else first - 1
        bracketed = bracket_start >= 0 and stop < len(self.times)
        inside = self.times[first:stop]
        indices = slice(max(0, bracket_start), min(stop + 1, len(self.times)))
        times, readers = self.times[indices], self.readers[indices]
        longest_gap = max(((b - a).total_seconds() for a, b in zip(times, times[1:])), default=None)
        coverage = {"samples_in_window": len(inside), "bracketed": bracketed,
                    "min_readers": min(readers, default=None), "max_readers": max(readers, default=None),
                    "max_gap_seconds": longest_gap, "observed_until": self.finished.isoformat()}
        reasons = []
        if not bracketed or not inside:
            reasons.append("window extends outside generator telemetry")
        if not readers or any(n != 1000 for n in readers):
            reasons.append("1,000 active readers could not be verified throughout the window")
        if longest_gap is None or longest_gap > MAX_TELEMETRY_GAP_SECONDS:
            reasons.append("gap in generator telemetry")
        if self.finished < end + SLO:
            reasons.append("30-second follow-up incomplete")
        cohort = [(order, within) for accepted, order, within in self.orders if start <= accepted < end]
        if not cohort:
            reasons.append("no accepted water orders")
        successes = sum(within for _, within in cohort)
        return {
            "starts_at": start.isoformat(), "ends_at": end.isoformat(),
            "status": "unmeasured" if reasons else "measured", "reasons": reasons, "coverage": coverage,
            "accepted": len(cohort), "within_30s": successes,
            "pct": None if reasons else round(100 * successes / len(cohort), 4),
            "order_ids": [order for order, _ in cohort],
        }


def compare(control_folder, incident_folder):
    control, incident = Run(control_folder), Run(incident_folder)
    phases = []
    for name in PHASES:
        actual = incident.phases[name]
        start, end = parse_time(actual["starts_at"]), parse_time(actual["ends_at"])
        if end - start != timedelta(minutes=1) or start < incident.anchor:
            raise ValueError(f"Invalid incident {name} measurement period")
        offset = start - incident.anchor
        matched = control.cohort(control.anchor + offset, control.anchor + (end - incident.anchor))
        observed = incident.cohort(start, end)
        if (int(actual["accepted"]), int(actual["within_30s"])) != (observed["accepted"], observed["within_30s"]):
            raise ValueError(f"Incident {name} ledger disagrees with the original dashboard summary")
        delta = None
        if matched["status"] == observed["status"] == "measured":
            delta = round(100 * (observed["within_30s"] / observed["accepted"] -
                                 matched["within_30s"] / matched["accepted"]), 4)
        phases.append({"phase": name, "offset_seconds": offset.total_seconds(), "duration_seconds": 60,
                       "control": matched, "incident": observed,
                       "incident_minus_control_percentage_points": delta})
    return {"control": control_folder.name, "incident": incident_folder.name,
            "normal_start_control": control.anchor.isoformat(), "normal_start_incident": incident.anchor.isoformat(),
            "cohort_rule": "server accepted_at in [start,end), 0 <= confirmed_at - accepted_at <= 30 seconds",
            "max_telemetry_gap_seconds": MAX_TELEMETRY_GAP_SECONDS, "phases": phases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("control", type=Path)
    parser.add_argument("incident", type=Path)
    args = parser.parse_args()
    result = compare(args.control, args.incident)
    path = args.incident / "matched-control.json"
    path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    for phase in result["phases"]:
        a, b = phase["control"], phase["incident"]
        print(f"{phase['phase']} +{phase['offset_seconds']}s: control {a['within_30s']}/{a['accepted']} "
              f"({a['status']}), incident {b['within_30s']}/{b['accepted']} ({b['status']}), "
              f"difference={phase['incident_minus_control_percentage_points']} pp")
    print(path)


if __name__ == "__main__":
    main()

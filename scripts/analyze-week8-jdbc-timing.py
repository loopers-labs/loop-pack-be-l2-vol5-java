#!/usr/bin/env python3
"""Summarize test-only JDBC traces; preserves per-request overlap and HTTP timing boundaries."""
import argparse
from collections import defaultdict
import gzip
import json
import math
from pathlib import Path


def distribution(values):
    values = sorted(values)
    if not values:
        return {"count": 0}
    return {
        "count": len(values),
        "meanMs": round(sum(values) / len(values) / 1_000_000, 3),
        **{f"p{p}Ms": round(values[math.ceil(len(values) * p / 100) - 1] / 1_000_000, 3)
           for p in (50, 95, 99)},
        "maxMs": round(values[-1] / 1_000_000, 3),
    }


def summarize(rows):
    fields = ("totalNanos", "acquireNanos", "heldNanos", "sqlNanos", "couponUpdateNanos",
              "couponInsertNanos", "selectNanos", "completionNanos", "outsideJdbcNanos")
    result = {name.removesuffix("Nanos"): distribution([row[name] for row in rows]) for name in fields}
    if all("clientHttpNanos" in row for row in rows):
        result["clientHttp"] = distribution([row["clientHttpNanos"] for row in rows])
        result["clientMinusServlet"] = distribution([row["clientHttpNanos"] - row["totalNanos"] for row in rows])
    result["connections"] = sum(row["connections"] for row in rows)
    result["statements"] = sum(row["statements"] for row in rows)
    result["acquireFailures"] = sum(row["acquireFailures"] for row in rows)
    total = sum(row["totalNanos"] for row in rows)
    result["aggregateShares"] = {
        name.removesuffix("Nanos"): round(sum(row[name] for row in rows) / total, 4) if total else None
        for name in ("acquireNanos", "heldNanos", "outsideJdbcNanos")
    }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    runs = []
    for run in report["runs"]:
        raw = args.report.parent / run["requestTimingsFile"]
        with gzip.open(raw, "rt") as stream:
            rows = json.load(stream)
        groups = defaultdict(list)
        buckets = defaultdict(list)
        for row in rows:
            outside = row["totalNanos"] - row["acquireNanos"] - row["heldNanos"]
            assert outside >= 0, ("Overlapping JDBC periods", row)
            assert row["heldNanos"] >= row["sqlNanos"] + row["completionNanos"], row
            row["outsideJdbcNanos"] = outside
            groups[f"{row['kind']}:{row['status']}"].append(row)
            second = (row["endEpochMs"] - run["originEpochMs"]) // 1000
            buckets[(second, row["kind"], row["status"])].append(row)
        assert len(groups["issue:201"]) == run["correctness"]["accepted"]
        assert all(row["couponUpdateNanos"] > 0 and row["couponInsertNanos"] > 0 for row in groups["issue:201"])
        runs.append({
            "groups": {name: summarize(values) for name, values in sorted(groups.items())},
            "byCompletionSecond": [{"second": key[0], "kind": key[1], "status": key[2], **summarize(values)}
                                   for key, values in sorted(buckets.items())],
        })
    output = {
        "source": args.report.name,
        "mode": report["mode"], "stages": report["stages"], "poolSize": report["poolSize"],
        "boundaries": {
            "total": "Servlet filter entry to completion; excludes time before filter dispatch and network response.",
            "acquire": "Sum of elapsed DataSource.getConnection calls per request; includes failed calls.",
            "held": "Sum of connection acquisition completion to close completion, per request.",
            "sql": "JDBC execute calls, including driver/network/DB execution and lock waits; subset of held.",
            "completion": "JDBC commit/rollback calls; subset of held.",
            "outsideJdbc": "total - acquire - held. Includes Redis, application/JPA processing and other work.",
            "clientMinusServlet": "Paired client duration minus servlet filter duration. Includes time outside filter and network; not a direct Tomcat queue timer. Can be slightly negative due to response completion overlap.",
            "shares": "Ratios of summed durations, not sums of independent percentiles; do not add SQL to held.",
        },
        "runs": runs,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    for run in runs:
        for name, group in run["groups"].items():
            print(name, "n=", group["total"]["count"],
                  "mean total/acquire/held/update (ms)=",
                  [group[field]["meanMs"] for field in ("total", "acquire", "held", "couponUpdate")])


if __name__ == "__main__":
    main()

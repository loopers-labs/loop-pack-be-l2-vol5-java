#!/usr/bin/env python3
"""Compare read-only offset/keyset SQL on the existing benchmark seed."""

import argparse
from datetime import datetime
import importlib.util
import json
from pathlib import Path
import statistics
import sys
from zoneinfo import ZoneInfo

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def summary(values):
    return {"median": statistics.median(values), "mean": statistics.mean(values),
            "min": min(values), "max": max(values)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists")
    probe = module("query_probe", "measure-week4-query.py")
    seed = module("seed", "seed-week4.py")
    mysql = probe.mysql
    mysql("SELECT 'pagination read-only experiment';")
    expected = sorted((i for i in range(1, 10001) if not seed.deleted(i)),
                      key=lambda i: (seed.price(i), -(i % 30), -i))
    prefix = ("SELECT p.* FROM product p WHERE p.deleted_at IS NULL AND EXISTS "
              "(SELECT b.id FROM brand b WHERE b.id=p.brand_id AND b.deleted_at IS NULL)")
    order = " ORDER BY p.price ASC,p.created_at DESC,p.id DESC LIMIT 20"
    indexes = mysql("SHOW INDEX FROM product;")
    result = {"recorded_at": datetime.now(ZoneInfo("Asia/Seoul")).isoformat(),
              "database": mysql("SELECT VERSION(),DATABASE(),@@transaction_isolation;"),
              "indexes": indexes, "warmup_per_strategy": 10, "samples_per_strategy": 30,
              "notes": ["Full product rows, identical visibility and ordering; no HTTP/count/like enrichment",
                        "Cursor anchor prepared outside timing, models an already received previous-page cursor",
                        "SHOW PROFILES SQL duration on one persistent MySQL session; excludes Docker/client startup",
                        "Strategies alternate, reversing first strategy on each round; existing indexes unchanged",
                        "Read-only snapshot per depth; no ANALYZE TABLE or schema/API changes"], "cases": []}
    for offset in (0, 1000, 8000):
        anchor = None
        predicate = ""
        if offset:
            last = expected[offset - 1]
            price = seed.price(last)
            stamp = seed.timestamp(last)
            anchor = {"id": last, "price": price, "created_at": stamp.strip("'")}
            predicate = (f" AND (p.price>{price} OR (p.price={price} AND p.created_at<{stamp}) "
                         f"OR (p.price={price} AND p.created_at={stamp} AND p.id<{last}))")
        queries = {"offset": prefix + order + f" OFFSET {offset}",
                   "cursor": prefix + predicate + order}
        sequence = []
        for round_no in range(40):
            sequence.extend(("offset", "cursor") if round_no % 2 == 0 else ("cursor", "offset"))
        batch = "SET SESSION profiling_history_size=100; SET profiling=1; START TRANSACTION READ ONLY;\n"
        batch += "\n".join(queries[strategy] + ";" for strategy in sequence)
        batch += "\nSELECT '#PROFILES'; SHOW PROFILES; COMMIT;"
        raw = mysql(batch)
        rows_text, profiles_text = raw.split("#PROFILES\n", 1)
        rows = rows_text.splitlines()
        if len(rows) != 80 * 20:
            raise RuntimeError(f"Unexpected result row count at offset {offset}: {len(rows)}")
        wanted = expected[offset:offset + 20]
        for index in range(80):
            page_rows = rows[index * 20:(index + 1) * 20]
            ids = [int(line.split('\t')[0]) for line in page_rows]
            if ids != wanted:
                raise RuntimeError(f"Wrong IDs/order: offset={offset}, execution={index}")
            if page_rows != rows[:20]:
                raise RuntimeError(f"Product fields differ: offset={offset}, execution={index}")
        profiles = []
        for line in profiles_text.splitlines():
            fields = line.split('\t', 2)
            if len(fields) == 3 and fields[2].startswith("SELECT p.* FROM product p WHERE"):
                profiles.append(float(fields[1]) * 1000)
        if len(profiles) != 80:
            raise RuntimeError(f"Incomplete profiling: {len(profiles)}")
        times = {key: [] for key in queries}
        for index in range(20, 80):
            times[sequence[index]].append(profiles[index])
        case = {"offset": offset, "anchor": anchor, "expected_ids": wanted,
                "all_80_results_match": True, "all_product_fields_match": True, "strategies": {}}
        for strategy, query in queries.items():
            case["strategies"][strategy] = {"sql": query, "sql_ms": summary(times[strategy]),
                "all_sql_ms": times[strategy],
                "explain_json": json.loads(mysql("EXPLAIN FORMAT=JSON " + query + ";")),
                "explain_analyze": mysql("EXPLAIN ANALYZE " + query + ";")}
        result["cases"].append(case)
        print(offset, {key: summary(value) for key, value in times.items()}, flush=True)
    if mysql("SHOW INDEX FROM product;") != indexes:
        raise RuntimeError("Indexes changed during comparison")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("Saved:", args.output)


if __name__ == "__main__":
    main()

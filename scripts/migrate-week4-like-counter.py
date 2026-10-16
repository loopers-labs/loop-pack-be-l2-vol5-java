#!/usr/bin/env python3
"""Offline counter backfill for the three explicitly selected local benchmark databases."""
import argparse
from datetime import datetime
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
DATABASES = ("loopers_benchmark", "loopers_benchmark_100k", "loopers_benchmark_100k_hotspot")
spec = importlib.util.spec_from_file_location("probe", ROOT / "scripts/measure-week4-query.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def mysql(db, sql):
    assert db in DATABASES
    return probe.mysql(f"USE {db};\n" + sql)


def fingerprint(db):
    return mysql(db, "SELECT COUNT(*),SUM(id),SUM(price),SUM(quantity),SUM(deleted_at IS NULL) FROM product;"
                 "SELECT COUNT(*),SUM(id),SUM(user_id),SUM(product_id) FROM product_like;"
                 "SELECT table_name,index_name,seq_in_index,column_name,collation FROM information_schema.statistics "
                 "WHERE table_schema=DATABASE() AND table_name IN ('product','product_like') "
                 "ORDER BY table_name,index_name,seq_in_index;")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--databases", nargs="+", choices=DATABASES, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Preserve existing evidence; choose a new output")
    sql = (ROOT / "scripts/sql/week4-20-like-counter-backfill.sql").read_text()
    result = {"recorded_at": datetime.now().astimezone().isoformat(), "databases": {},
              "notes": ["Offline deployment: do not restart the old aggregate-only writer after backfill.",
                        "LOCK TABLES protects the backfill; old/new writer coexistence is unsupported.",
                        "Source rows and indexes retained; product_like_count is intentionally persistent.",
                        "Counts/sums detect changes but are not cryptographic identity checks."]}
    # Preflight every selected target before changing any of them.
    for db in args.databases:
        connections = mysql(db, "SELECT COUNT(*) FROM information_schema.processlist "
                            "WHERE DB=DATABASE() AND USER='application' AND ID<>CONNECTION_ID();")
        if connections != "0":
            raise RuntimeError(f"Stop application connections to {db} before migration")
    for db in args.databases:
        before = fingerprint(db)
        verification = mysql(db, sql).splitlines()
        assert verification == ["0", "0"], (db, verification)
        after = fingerprint(db)
        assert before == after, "Source rows or indexes changed"
        record = {"source_before": before, "source_after": after, "source_unchanged": True,
                  "missing_or_mismatched": 0, "orphan_counters": 0,
                  "counter_rows_and_total": mysql(db, "SELECT COUNT(*),SUM(like_count) FROM product_like_count;"),
                  "schema": mysql(db, "SHOW CREATE TABLE product_like_count;")}
        result["databases"][db] = record
        # Persist progress so a later target failure does not erase a completed target's evidence.
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print(db, record["counter_rows_and_total"], "verified", flush=True)


if __name__ == "__main__":
    main()

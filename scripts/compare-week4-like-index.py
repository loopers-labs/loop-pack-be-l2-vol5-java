#!/usr/bin/env python3
"""Compare like read/write SQL using disposable copies of the benchmark table."""

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
TABLES = ("w4_like_index_control", "w4_like_index_candidate")
INDEX = "idx_product_like_product"
IDS = "7407,4407,1407,9407,6407,3407,407,8407,5407,2407,7707,4707,1707,9707,6707,3707,707,8707,5707,2707"


def load_probe():
    spec = importlib.util.spec_from_file_location("probe", ROOT / "scripts/measure-week4-query.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def stats(values):
    return {"median_ms": statistics.median(values), "mean_ms": statistics.mean(values),
            "min_ms": min(values), "max_ms": max(values)}


def profile(mysql, statements):
    output = mysql("SET SESSION profiling_history_size=100; SET profiling=1;\n"
                   + ";\n".join(statements) + ";\nSELECT '#PROFILES'; SHOW PROFILES;")
    rows, profiles = output.split("#PROFILES\n", 1)
    records = []
    for line in profiles.splitlines():
        _, seconds, sql = line.split("\t", 2)
        if sql != "SELECT '#PROFILES'":
            records.append(float(seconds) * 1000)
    if len(records) != len(statements):
        raise RuntimeError(f"Incomplete profiles: {len(records)} != {len(statements)}")
    return rows.strip(), records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("choose a new output path")
    mysql = load_probe().mysql
    existing = mysql("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() "
                     "AND table_name IN ('w4_like_index_control','w4_like_index_candidate');")
    if existing:
        raise RuntimeError("Experiment tables already exist; refusing to reuse or delete them")
    if mysql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
             f"AND table_name='product_like' AND index_name='{INDEX}';") != "0":
        raise RuntimeError("Run this baseline comparison before adding the candidate to product_like")
    fingerprint_sql = "SELECT COUNT(*), SUM(id), SUM(user_id), SUM(product_id) FROM product_like;"
    before = mysql(fingerprint_sql)
    product_id = int(mysql("SELECT p.id FROM product p WHERE p.deleted_at IS NULL "
                           "AND NOT EXISTS (SELECT 1 FROM product_like l WHERE l.product_id=p.id AND l.user_id=1) "
                           "ORDER BY p.id LIMIT 1;"))
    result = {"recorded_at": datetime.now(ZoneInfo("Asia/Seoul")).isoformat(),
              "database": mysql("SELECT VERSION(), DATABASE(), @@transaction_isolation;"),
              "durability": mysql("SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog,@@log_bin;"),
              "source_fingerprint": before, "write_pair": {"user_id": 1, "product_id": product_id},
              "notes": ["Identical CREATE TABLE LIKE + INSERT SELECT copies; only candidate gets product_id index",
                        "One-row INSERT and relationship DELETE, each committed in its own transaction",
                        "SQL transaction durations include START TRANSACTION, DML and COMMIT, exclude client startup",
                        "Single client, warm tables; alternate table order; 10 warmup pairs then 50 measured pairs per table",
                        "Writes reuse one absent relationship; not contention, sustained throughput, or random-write benchmark",
                        "Storage is allocated table index bytes after equal ANALYZE TABLE, not exact per-index live bytes"],
              "tables": {}}
    created = []
    try:
        for table in TABLES:
            mysql(f"CREATE TABLE {table} LIKE product_like;")
            created.append(table)
            mysql(f"INSERT INTO {table} SELECT * FROM product_like;")
        mysql(f"CREATE INDEX {INDEX} ON {TABLES[1]} (product_id);")
        for table in TABLES:
            mysql(f"ANALYZE TABLE {table};")
            result["tables"][table] = {"indexes": mysql(f"SHOW INDEX FROM {table};"),
                                       "insert_ms": [], "delete_ms": [], "rounds": []}
        for round_no in range(6):
            for table in (TABLES if round_no % 2 == 0 else TABLES[::-1]):
                statements = []
                for _ in range(10):
                    statements.extend(["START TRANSACTION",
                        f"INSERT INTO {table} (user_id,product_id,created_at,updated_at) "
                        f"VALUES (1,{product_id},UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", "COMMIT",
                        "START TRANSACTION", f"DELETE FROM {table} WHERE user_id=1 AND product_id={product_id}",
                        "COMMIT"])
                _, times = profile(mysql, statements)
                inserts = [sum(times[i:i + 3]) for i in range(0, 60, 6)]
                deletes = [sum(times[i + 3:i + 6]) for i in range(0, 60, 6)]
                if round_no:
                    data = result["tables"][table]
                    data["insert_ms"].extend(inserts)
                    data["delete_ms"].extend(deletes)
                    data["rounds"].append({"insert": stats(inserts), "delete": stats(deletes)})
                check = mysql(f"SELECT COUNT(*),SUM(id),SUM(user_id),SUM(product_id) FROM {table};")
                if check != before:
                    raise RuntimeError(f"Write round did not restore original rows: {table}")
        expected = mysql(f"SELECT product_id,COUNT(id) FROM product_like WHERE product_id IN ({IDS}) GROUP BY product_id;")
        for table in TABLES:
            query = f"SELECT product_id,COUNT(id) FROM {table} WHERE product_id IN ({IDS}) GROUP BY product_id"
            rows, times = profile(mysql, [query] * 40)
            expected_rows = sorted(expected.splitlines())
            actual = rows.splitlines()
            if len(actual) != 40 * len(expected_rows):
                raise RuntimeError("Wrong aggregate row count")
            for start in range(0, len(actual), len(expected_rows)):
                if sorted(actual[start:start + len(expected_rows)]) != expected_rows:
                    raise RuntimeError("Aggregate results differ")
            data = result["tables"][table]
            data.update({"insert": stats(data["insert_ms"]), "delete": stats(data["delete_ms"]),
                         "read": stats(times[10:]), "read_ms": times[10:],
                         "explain_analyze": mysql("EXPLAIN ANALYZE " + query + ";"),
                         "delete_plan": mysql(f"EXPLAIN FORMAT=JSON DELETE FROM {table} WHERE user_id=1 AND product_id={product_id};"),
                         "aggregate_matches": True})
            mysql(f"ANALYZE TABLE {table};")
            data["allocated_bytes"] = mysql("SELECT data_length,index_length FROM information_schema.tables "
                                             f"WHERE table_schema=DATABASE() AND table_name='{table}';")
            print(table, {k: data[k] for k in ("read", "insert", "delete", "allocated_bytes")}, flush=True)
        if mysql(fingerprint_sql) != before:
            raise RuntimeError("Source data changed")
        result["source_unchanged"] = True
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    finally:
        for table in reversed(created):
            mysql(f"DROP TABLE {table};")


if __name__ == "__main__":
    main()

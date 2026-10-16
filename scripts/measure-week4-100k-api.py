#!/usr/bin/env python3
"""Verify current production API against normal/hotspot 100k seeds, with temporary schema completion."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import socket
import sys
import time
from urllib.error import URLError

sys.dont_write_bytecode = True
import importlib.util

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("scale", ROOT / "scripts/compare-week4-100k.py")
scale = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scale)
probe = scale.probe


def seed_liked(product_id, user_id):
    return user_id is not None and any((product_id * 13 + k) % scale.seed.USERS + 1 == user_id
                                      for k in range(scale.seed.like_count(product_id)))


def expected(hot, user_id=None):
    return {"items": [{"id": i, "brand": {"id": 7, "name": "W4 Brand 007"},
                       "name": f"W4 Product {i:06d}", "price": scale.seed.price(i),
                       "likeCount": scale.seed.like_count(i) + (50000 if hot and i == 97407 else 0),
                       "isLiked": seed_liked(i, user_id)}
                      for i in scale.expected_ids()], "page": 0, "size": 20}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    assert args.jar.is_file() and not args.output.exists()
    for port in (18082, 18083):
        with socket.socket() as check:
            check.bind(('127.0.0.1', port))
    original_mysql = probe.mysql
    probe.URL = "http://localhost:18082/api/v1/products?brandId=7&page=0&size=20&sort=price_asc"
    result = {"notes": ["Current production service with explicit cache bypass; no JOIN replacement.",
                         "Separate JVM per DB, sequential urllib requests; 10 warmup + 30 measured requests.",
                         "HTTP values verify final behavior, not a controlled causal latency comparison between JVMs.",
                         "SQL captured after HTTP timing; logging settings restored.",
                         "Missing non-product tables temporarily copied as empty schemas for ddl-auto=validate."]}
    for db in (scale.DB, scale.HOT):
        # Keep original function to avoid recursion through scale.mysql.
        mysql = lambda sql: original_mysql(f"USE {db};\n" + sql)
        before = scale.snapshot(db)
        created = []
        process = None
        log_path = args.output.parent / (db + "-api.log")
        try:
            missing = mysql("SELECT table_name FROM information_schema.tables s "
                            "WHERE table_schema='loopers_benchmark' AND NOT EXISTS "
                            "(SELECT 1 FROM information_schema.tables d WHERE d.table_schema=DATABASE() "
                            "AND d.table_name=s.table_name) ORDER BY table_name;")
            allowed = {"example", "orders", "order_item", "point_balance", "point_grant", "point_usage"}
            for table in missing.splitlines():
                assert table in allowed, f"Unexpected missing table: {table}"
                mysql(f"CREATE TABLE {table} LIKE loopers_benchmark.{table};")
                created.append(table)
            body = expected(db == scale.HOT)
            with log_path.open("w") as log:
                process = subprocess.Popen([
                    "/opt/homebrew/opt/openjdk@21/bin/java", "-jar", str(args.jar.resolve()),
                    "--spring.profiles.active=benchmark", "--server.port=18082", "--management.server.port=18083",
                    "--spring.jpa.hibernate.ddl-auto=validate",
                    "--commerce.cache.price.enabled=false",
                    f"--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3307/{db}"],
                    cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f"API exited; inspect {log_path}")
                    try:
                        probe.request(body)
                        break
                    except URLError:
                        if time.monotonic() > deadline:
                            raise RuntimeError(f"API readiness timed out; inspect {log_path}")
                        time.sleep(.5)
                for _ in range(10):
                    probe.request(body)
                times = [probe.request(body)[0] for _ in range(30)]
                probe.mysql = mysql
                try:
                    sql = probe.capture_sql(body)
                finally:
                    probe.mysql = original_mysql
                assert sql["business_select_count"] == 3, "Unexpected current API SQL flow"
                result[db] = {"http": scale.stats(times), "response": body, "sql_probe": sql,
                              "response_sha256": hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest(),
                              "temporary_schema_tables": created.copy(), "source_before": before}
                print(db, result[db]["http"]["median_ms"], "ms, SELECTs", sql["business_select_count"], flush=True)
        finally:
            probe.mysql = original_mysql
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            for table in reversed(created):
                assert mysql(f"SELECT COUNT(*) FROM {table}") == "0", "Unexpected data; preserve table"
                mysql(f"DROP TABLE {table};")
        after = scale.snapshot(db)
        assert after == before, "Source data/schema changed"
        result[db]["source_unchanged"] = True
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("Saved", args.output)


if __name__ == "__main__":
    main()

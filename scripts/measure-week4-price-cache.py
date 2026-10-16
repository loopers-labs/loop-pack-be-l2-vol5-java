#!/usr/bin/env python3
"""Compare production HTTP DB-only/miss/hit and capture real SQL on existing 100k seeds."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import time
from urllib.error import URLError
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("api", ROOT / "scripts/measure-week4-100k-api.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)
probe, scale = api.probe, api.scale


def redis(*args):
    command = ["docker", "compose", "-f", str(ROOT / "docker/infra-compose.yml"),
               "--profile", "benchmark", "exec", "-T", "redis-master", "redis-cli", "--raw", *args]
    return subprocess.run(command, text=True, capture_output=True, check=True, timeout=10).stdout.strip()


def source_snapshot(db):
    result = scale.snapshot(db)
    tables = {"brand", "product", "product_like", "product_like_count", "users"}
    result["indexes"] = "\n".join(line for line in result["indexes"].splitlines()
                                  if line.split("\t", 1)[0] in tables)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    assert args.jar.is_file() and not args.output.exists()
    for port in (18082, 18083):
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            check.bind(("127.0.0.1", port))
    original_mysql = probe.mysql
    probe.URL = "http://localhost:18082/api/v1/products?brandId=7&page=0&size=20&sort=price_asc"
    result = {"notes": ["Production code; no test service replacement. Only owned Redis keys cleared.",
                         "Each mode: 10 warmup + 30 sequential HTTP requests, independently validated against seed.",
                         "Miss key deletion occurs outside HTTP timing. SQL capture occurs after timing.",
                         "DB-only and cache-enabled use separate JVMs, so latency ratios are not causal proof.",
                         "Miss/hit share one JVM; no concurrent load or throughput claim.",
                         "Deleted-product visibility/brand/counter remain DB-authoritative; cache stores no likes, brand labels or stock.",
                         "Source index metadata covers business tables only."]}
    for db in (scale.DB, scale.HOT):
        mysql = lambda sql: original_mysql(f"USE {db};\n" + sql)
        before = source_snapshot(db)
        expected = api.expected(db == scale.HOT)
        data = {"response": expected, "response_sha256": hashlib.sha256(
            json.dumps(expected, sort_keys=True).encode()).hexdigest(), "source_before": before}
        for enabled in (False, True):
            prefix = "price-page-measure:" + str(uuid.uuid4())
            key = prefix + ":brand:7:sort:price_asc:page:0:size:20"
            created = []
            process = None
            try:
                missing = mysql("SELECT table_name FROM information_schema.tables s "
                    "WHERE table_schema='loopers_benchmark' "
                    "AND table_name IN ('example','orders','order_item','point_balance','point_grant','point_usage') "
                    "AND NOT EXISTS "
                    "(SELECT 1 FROM information_schema.tables d WHERE d.table_schema=DATABASE() "
                    "AND d.table_name=s.table_name) ORDER BY table_name;")
                for table in missing.splitlines():
                    assert table in {"example", "orders", "order_item", "point_balance", "point_grant", "point_usage"}
                    mysql(f"CREATE TABLE {table} LIKE loopers_benchmark.{table};")
                    created.append(table)
                log_path = args.output.parent / (args.output.stem + "-" + db + "-" + str(enabled) + ".log")
                with log_path.open("w") as log:
                    process = subprocess.Popen([
                        "/opt/homebrew/opt/openjdk@21/bin/java", "-jar", str(args.jar.resolve()),
                        "--spring.profiles.active=benchmark", "--server.port=18082", "--management.server.port=18083",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        f"--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3307/{db}",
                        "--commerce.cache.price.enabled=" + str(enabled).lower(),
                        "--commerce.cache.price.prefix=" + prefix], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                    deadline = time.monotonic() + 90
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError(f"API exited; inspect {log_path}")
                        try:
                            probe.request(expected)
                            break
                        except URLError:
                            if time.monotonic() > deadline:
                                raise RuntimeError(f"API readiness timeout; inspect {log_path}")
                            time.sleep(.5)
                    for mode in (("miss", "hit") if enabled else ("db_only",)):
                        times = []
                        for i in range(40):
                            if mode == "miss":
                                redis("DEL", key)
                            elapsed, _ = probe.request(expected)
                            if i >= 10:
                                times.append(elapsed)
                        if enabled:
                            assert redis("EXISTS", key) == "1", "Cache did not fill"
                            ttl = int(redis("TTL", key))
                            assert 0 < ttl <= 300
                        if mode == "miss":
                            redis("DEL", key)
                        probe.mysql = mysql
                        try:
                            captured = probe.capture_sql(expected)
                        finally:
                            probe.mysql = original_mysql
                        required = 3
                        assert captured["business_select_count"] == required, captured
                        assert all("count(" not in q["sql"].lower() for q in captured["queries"]), captured
                        queries = [q["sql"].lower() for q in captured["queries"]]
                        if mode == "hit":
                            product_queries = [q for q in queries if "from product " in q]
                            assert len(product_queries) == 1, captured
                            assert all("count(" not in q and "order by" not in q and " in " in q
                                       for q in product_queries), "Only cached-ID visibility lookup is expected"
                        data[mode] = {"http": scale.stats(times), "sql_probe": captured,
                                      "warmup": 10, "samples": 30, "concurrency": 1}
                        if enabled:
                            data[mode]["ttl_seconds_observed"] = ttl
                        print(db, mode, round(data[mode]["http"]["median_ms"], 3), "ms; SQL", required, flush=True)
            finally:
                probe.mysql = original_mysql
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
                redis("DEL", key)
                for table in reversed(created):
                    assert mysql(f"SELECT COUNT(*) FROM {table};") == "0"
                    mysql(f"DROP TABLE {table};")
        assert source_snapshot(db) == before, "Source changed"
        data["source_unchanged"] = True
        result[db] = data
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("Saved", args.output)


if __name__ == "__main__":
    main()

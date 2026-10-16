#!/usr/bin/env python3
"""Actual API checks: brand product skew and synchronized warm/cold request bursts."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import socket
import subprocess
import sys
import threading
import time
from urllib.error import URLError
from urllib.request import Request, urlopen
import uuid
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("existing", ROOT / "scripts/measure-week4-price-cache.py")
existing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(existing)
probe, scale = existing.probe, existing.scale
PATH = "/api/v1/products?brandId=7&page=0&size=20&sort=price_asc"
URL = "http://localhost:18082" + PATH
REQUEST_HEADERS = {}


def reference(skew, user_id=None):
    seed = scale.seed
    ids = [i for i in range(1, 100001)
           if (7 if skew and i <= 90000 else seed.brand_id(i)) == 7 and not seed.deleted(i)]
    ordered = sorted(ids, key=lambda i: (seed.price(i), -(i % 30), -i))[:20]
    return {"items": [{"id": i, "brand": {"id": 7, "name": "W4 Brand 007"},
                       "name": f"W4 Product {i:06d}", "price": seed.price(i),
                       "likeCount": seed.like_count(i), "isLiked": existing.api.seed_liked(i, user_id)} for i in ordered],
            "page": 0, "size": 20}


def get(expected):
    start = time.perf_counter()
    with urlopen(Request(URL, headers=REQUEST_HEADERS), timeout=30) as response:
        raw = response.read()
    elapsed = (time.perf_counter() - start) * 1000
    body = json.loads(raw)
    if body["meta"]["result"] != "SUCCESS" or body["data"] != expected:
        raise AssertionError("HTTP response differs from independent seed reference")
    return elapsed


def invalidate(expected):
    item = expected["items"][0]
    req = Request(f"http://localhost:18082/api-admin/v1/products/{item['id']}",
                  data=json.dumps({"name": item["name"], "price": item["price"]}).encode(),
                  headers={"Content-Type": "application/json", "X-USER-ROLE": "ADMIN"}, method="PUT")
    with urlopen(req, timeout=30) as response:
        assert json.load(response)["meta"]["result"] == "SUCCESS"


def burst(pool, clients, expected):
    barrier = threading.Barrier(clients + 1, timeout=30)

    def worker():
        barrier.wait()
        return get(expected)

    futures = [pool.submit(worker) for _ in range(clients)]
    barrier.wait()
    return [future.result(timeout=35) for future in futures]


def capture_burst(sql, db, action):
    old_log, old_output = sql("SELECT @@global.general_log, @@global.log_output;").split("\t")
    try:
        sql("SET GLOBAL general_log=OFF; SET GLOBAL log_output='TABLE'; SET GLOBAL general_log=ON;")
        begin = sql("SELECT DATE_FORMAT(NOW(6), '%Y-%m-%d %H:%i:%s.%f');")
        action()
        end = sql("SELECT DATE_FORMAT(NOW(6), '%Y-%m-%d %H:%i:%s.%f');")
        # Only this app's live DB connections; unrelated traffic is excluded.
        threads = sql("SELECT PROCESSLIST_ID FROM performance_schema.threads "
                      f"WHERE PROCESSLIST_DB='{db}' AND PROCESSLIST_USER='application';").splitlines()
    finally:
        sql(f"SET GLOBAL general_log=OFF; SET GLOBAL log_output='{old_output}'; SET GLOBAL general_log={int(old_log)};")
    assert threads and all(t.isdigit() for t in threads)
    rows = sql("SELECT HEX(argument),thread_id FROM mysql.general_log "
               f"WHERE event_time >= '{begin}' AND event_time <= '{end}' "
               f"AND thread_id IN ({','.join(threads)}) AND command_type IN ('Query','Execute') "
               "ORDER BY event_time;")
    counts, statements = Counter(), Counter()
    for line in rows.splitlines():
        encoded, _ = line.split("\t")
        query = bytes.fromhex(encoded).decode()
        if not re.match(r"\s*select\b", query, re.I):
            continue
        match = re.search(r"\bfrom\s+`?(brand|product|product_like_count|product_like|users)\b", query, re.I)
        if not match:
            continue
        table = match.group(1).lower()
        kind = table
        if table == "product":
            kind = "product_count" if re.search(r"\bcount\s*\(", query, re.I) else (
                "product_page" if re.search(r"\border\s+by\b", query, re.I) else "active_ids")
        counts[kind] += 1
        statements[query] += 1
    return {"counts": dict(counts), "business_select_count": sum(counts.values()),
            "statements": [{"sql": query, "executions": count} for query, count in statements.items()],
            "previous_general_log": old_log, "previous_log_output": old_output,
            "timing_note": "Separate untimed burst; SQL logging was disabled during latency measurement."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=200)
    parser.add_argument("--clients", type=int, default=20)
    parser.add_argument("--bursts", type=int, default=10)
    parser.add_argument("--user-id", type=int, help="Optional existing seed user (1..200)")
    args = parser.parse_args()
    assert args.jar.is_file() and not args.output.exists() and args.samples >= 20
    assert 2 <= args.clients <= 50 and args.bursts >= 2
    assert args.user_id is None or 1 <= args.user_id <= scale.seed.USERS
    if args.user_id is not None:
        REQUEST_HEADERS["X-USER-ID"] = str(args.user_id)
    probe.HEADERS = REQUEST_HEADERS
    for port in (18082, 18083):
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            check.bind(("127.0.0.1", port))
    # Fail if an old JAR would silently measure a different application build.
    with zipfile.ZipFile(args.jar) as jar:
        for compiled in (ROOT / "apps/commerce-api/build/classes/java/main").rglob("*.class"):
            rel = compiled.relative_to(ROOT / "apps/commerce-api/build/classes/java/main")
            assert jar.read("BOOT-INF/classes/" + str(rel)) == compiled.read_bytes(), rel
        for src in (ROOT / "apps/commerce-api/src/main/java").rglob("*.java"):
            compiled = ROOT / "apps/commerce-api/build/classes/java/main" / src.relative_to(
                ROOT / "apps/commerce-api/src/main/java").with_suffix(".class")
            assert compiled.exists() and src.stat().st_mtime <= compiled.stat().st_mtime, src
    original_mysql = probe.mysql
    tag = uuid.uuid4().hex[:12]
    source_before = existing.source_snapshot(scale.DB)
    counter_before = original_mysql(f"SELECT COUNT(*),SUM(like_count),BIT_XOR(CRC32(CONCAT_WS('|',product_id,like_count))) "
                                    f"FROM {scale.DB}.product_like_count;")
    prefix = "w4-cache-skew:" + tag
    key = prefix + ":brand:7:sort:price_asc:page:0:size:20"
    result = {"completed": False, "started_at_utc": datetime.now(timezone.utc).isoformat(),
              "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(), "source": scale.DB,
              "source_before": source_before, "datasets": {}, "runs": [],
              "response_fields": ["items", "page", "size"], "product_total_count_included": False,
              "requester_id": args.user_id, "personal_state_from_database": args.user_id is not None,
              "conditions": {"sequential_warmup": 100, "sequential_samples": args.samples,
                             "concurrent_clients": args.clients, "burst_warmup": 3,
                             "measured_bursts": args.bursts, "rounds": 3,
                             "mysql_pool_max": 40, "ttl_seconds": 300},
              "notes": ["Same production JAR, original indexes, two owned copied databases.",
                        "Skew remaps product IDs <=90000 to brand 7; soft-delete status/price/counter unchanged.",
                        "Sequential hit/miss order alternates by round. Concurrent hot/cold order also alternates.",
                        "Concurrent cold means empty at burst start, not guaranteed miss for every request.",
                        "Same-value admin PUT evicts outside GET timing; no fill suppression added.",
                        "Barrier releases client threads together; server arrival/DB execution are not forced to overlap.",
                        "Every response validated against independent seed computation.",
                        "SQL counts from separate untimed bursts; no production throughput or worst-case bound claim."]}
    databases, granted, processes = [], [], []

    def save():
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def start(provider, scenario, round_number, db, expected):
        log_path = args.output.with_name(f"{args.output.stem}-{round_number}-{scenario}-{provider}.log")
        with log_path.open("w") as log:
            process = subprocess.Popen([
                "/opt/homebrew/opt/openjdk@21/bin/java", "-Xms256m", "-Xmx512m", "-jar", str(args.jar.resolve()),
                "--spring.profiles.active=benchmark", "--server.port=18082", "--management.server.port=18083",
                "--spring.jpa.hibernate.ddl-auto=validate",
                f"--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3307/{db}",
                "--commerce.cache.price.enabled=" + str(provider != "db").lower(),
                "--commerce.cache.price.provider=" + ("local" if provider == "local" else "redis"),
                "--commerce.cache.price.prefix=" + prefix], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 90
        while True:
            if process.poll() is not None:
                raise RuntimeError(f"App exited: {log_path}")
            try:
                get(expected)
                return process
            except URLError:
                if time.monotonic() >= deadline:
                    raise RuntimeError(f"Readiness timeout: {log_path}")
                time.sleep(.5)

    try:
        tables = original_mysql("SHOW TABLES FROM loopers_benchmark;").splitlines()
        source_indexes = original_mysql("SELECT table_name,index_name,seq_in_index,column_name,collation,non_unique "
                                        "FROM information_schema.statistics "
                                        f"WHERE table_schema='{scale.DB}' AND table_name IN ('brand','product','product_like_count') "
                                        "ORDER BY table_name,index_name,seq_in_index;")
        for scenario in ("normal", "brand_skew"):
            db = "w4_skew_" + tag + "_" + scenario
            original_mysql(f"CREATE DATABASE {db};")
            databases.append(db)
            sql = lambda query, database=db: original_mysql(f"USE {database};\n" + query)
            for table in tables:
                assert table.replace('_', '').isalnum()
                sql(f"CREATE TABLE {table} LIKE loopers_benchmark.{table};")
            copied = ("brand", "product", "product_like_count") + (("users", "product_like") if args.user_id else ())
            for table in copied:
                sql(f"DROP TABLE IF EXISTS {table}; CREATE TABLE {table} LIKE {scale.DB}.{table}; "
                    f"INSERT INTO {table} SELECT * FROM {scale.DB}.{table};")
            if scenario == "brand_skew":
                sql("UPDATE product SET brand_id=7 WHERE id <=90000;")
            sql("ANALYZE TABLE brand,product,product_like_count;")
            original_mysql(f"GRANT SELECT, INSERT, UPDATE, DELETE ON {db}.* TO 'application'@'%';")
            granted.append(db)
            expected = reference(scenario == "brand_skew", args.user_id)
            actual = sql("SELECT COUNT(*) FROM product WHERE brand_id=7 AND deleted_at IS NULL;")
            assert int(actual) == (87390 if scenario == "brand_skew" else 990)
            indexes = sql("SELECT table_name,index_name,seq_in_index,column_name,collation,non_unique "
                          "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                          "AND table_name IN ('brand','product','product_like_count') "
                          "ORDER BY table_name,index_name,seq_in_index;")
            assert indexes == source_indexes, "Index changed"
            result["datasets"][scenario] = {"database": db, "products": int(sql("SELECT COUNT(*) FROM product;")),
                "brand_7_products": int(sql("SELECT COUNT(*) FROM product WHERE brand_id=7;")),
                "brand_7_visible_products": int(actual), "expected_response": expected,
                "index_definitions_match_source": True}
            print(scenario, "brand7 active", actual, flush=True)
        for round_number, order in enumerate((("db", "local", "redis"), ("local", "redis", "db"),
                                               ("redis", "db", "local")), 1):
            scenarios = ("normal", "brand_skew") if round_number % 2 else ("brand_skew", "normal")
            for scenario in scenarios:
                db = result["datasets"][scenario]["database"]
                sql = lambda query: original_mysql(f"USE {db};\n" + query)
                expected = result["datasets"][scenario]["expected_response"]
                for provider in order:
                    existing.redis("DEL", key)
                    process = start(provider, scenario, round_number, db, expected)
                    row = {"round": round_number, "dataset": scenario, "provider": provider,
                           "sequential": {}, "concurrent": {}}
                    try:
                        modes = ("db_only",) if provider == "db" else (
                            ("hit", "miss") if round_number % 2 else ("miss", "hit"))
                        for mode in modes:
                            samples = []
                            for i in range(100 + args.samples):
                                if mode == "miss":
                                    invalidate(expected)
                                elapsed = get(expected)
                                if i >= 100:
                                    samples.append(elapsed)
                            if mode == "miss":
                                invalidate(expected)
                            probe.URL, probe.mysql = URL, sql
                            try:
                                trace = probe.capture_sql(expected)
                            finally:
                                probe.mysql = original_mysql
                            assert trace["business_select_count"] == (5 if args.user_id is not None else 3)
                            assert all("count(" not in q["sql"].lower() for q in trace["queries"]), trace
                            row["sequential"][mode] = {"http": scale.stats(samples), "sql_probe": trace}
                            print(round_number, scenario, provider, mode,
                                  round(scale.stats(samples)["median_ms"], 2), flush=True)
                        burst_modes = ("db_only",) if provider == "db" else (
                            ("cold", "warm") if round_number % 2 else ("warm", "cold"))
                        with ThreadPoolExecutor(max_workers=args.clients) as pool:
                            for mode in burst_modes:
                                samples, batches = [], []
                                for batch in range(3 + args.bursts):
                                    if mode == "cold":
                                        invalidate(expected)
                                    elif mode == "warm":
                                        get(expected)
                                    times = burst(pool, args.clients, expected)
                                    if batch >= 3:
                                        samples.extend(times)
                                        batches.append(scale.stats(times))
                                if mode == "cold":
                                    invalidate(expected)
                                elif mode == "warm":
                                    get(expected)
                                trace = capture_burst(sql, db, lambda: burst(pool, args.clients, expected))
                                counts = trace["counts"]
                                assert counts.get("brand", 0) == args.clients, trace
                                assert counts.get("product_like_count", 0) == args.clients, trace
                                assert counts.get("product_count", 0) == 0, trace
                                loads = counts.get("product_page", 0)
                                selects_per_request = 5 if args.user_id is not None else 3
                                assert trace["business_select_count"] == selects_per_request * args.clients, trace
                                if args.user_id is not None:
                                    assert counts.get("users", 0) == counts.get("product_like", 0) == args.clients, trace
                                if mode == "warm":
                                    assert loads == 0 and counts.get("active_ids", 0) == args.clients, trace
                                elif mode == "db_only":
                                    assert loads == args.clients, trace
                                else:
                                    assert 1 <= loads <= args.clients, trace
                                row["concurrent"][mode] = {"http": scale.stats(samples), "batches": batches,
                                    "sql_probe": trace, "responses_checked": len(samples), "errors": 0}
                                print(round_number, scenario, provider, "burst_" + mode,
                                      round(scale.stats(samples)["p95_ms"], 2), "p95; DB loads", loads, flush=True)
                        result["runs"].append(row)
                        save()
                    finally:
                        stop(process)
    finally:
        probe.mysql = original_mysql
        for process in processes:
            stop(process)
        existing.redis("DEL", key)
        for db in granted:
            original_mysql(f"REVOKE SELECT, INSERT, UPDATE, DELETE ON {db}.* FROM 'application'@'%';")
        for db in reversed(databases):
            assert db.startswith("w4_skew_" + tag + "_")
            original_mysql(f"DROP DATABASE {db};")
        assert existing.source_snapshot(scale.DB) == source_before, "Source changed"
        assert original_mysql(f"SELECT COUNT(*),SUM(like_count),BIT_XOR(CRC32(CONCAT_WS('|',product_id,like_count))) "
                              f"FROM {scale.DB}.product_like_count;") == counter_before
        result["source_unchanged"] = True
        result["owned_resources_cleaned"] = True
        save()
    result["completed"] = True
    result["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
    save()
    print("Saved", args.output, flush=True)


if __name__ == "__main__":
    main()

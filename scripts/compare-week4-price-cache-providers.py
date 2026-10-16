#!/usr/bin/env python3
"""Compare DB/local/Redis over real HTTP on an owned copy of the normal 100k seed."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import time
import uuid
from urllib.error import URLError
from urllib.request import Request, urlopen

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("existing", ROOT / "scripts/measure-week4-price-cache.py")
existing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(existing)
api, probe, scale = existing.api, existing.probe, existing.scale


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=200)
    parser.add_argument("--user-id", type=int, help="Optional existing seed user (1..200) for personal list reads")
    args = parser.parse_args()
    assert args.jar.is_file() and not args.output.exists() and args.samples >= 20
    assert args.user_id is None or 1 <= args.user_id <= scale.seed.USERS
    signed_headers = {} if args.user_id is None else {"X-USER-ID": str(args.user_id)}
    probe.HEADERS = signed_headers
    for port in (18082, 18083, 18084, 18085):
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            check.bind(("127.0.0.1", port))
    original_mysql = probe.mysql
    tag = uuid.uuid4().hex[:12]
    db = "w4_cache_compare_" + tag
    prefix = "w4-cache-compare:" + tag
    key = prefix + ":brand:7:sort:price_asc:page:0:size:20"
    sql = lambda query: original_mysql(f"USE {db};\n" + query)
    before = existing.source_snapshot(scale.DB)
    counter_before = original_mysql(f"SELECT COUNT(*),SUM(like_count) FROM {scale.DB}.product_like_count;")
    expected = api.expected(False, args.user_id)
    target = expected["items"][0]
    path = "/api/v1/products?brandId=7&page=0&size=20&sort=price_asc"
    result = {"completed": False, "source": scale.DB, "samples_per_mode": args.samples, "warmup": 100,
              "response_fields": ["items", "page", "size"], "product_total_count_included": False,
              "requester_id": args.user_id, "personal_state_from_database": args.user_id is not None,
              "rounds": [], "source_before": before,
              "notes": ["Actual application JAR, sequential HTTP; normal 100k seed copied to disposable DB.",
                        "Three rotated process orders; cold forced by successful admin PUT outside GET timing.",
                        "Forced miss uses same-value PUT; mixed workload alternates actual price every 10 GETs.",
                        "Warmup 100 per mode; raw samples retained. No throughput or production hit-rate claim.",
                        "Signed-in runs also copy seed users and relationships and validate isLiked independently.",
                        "Anonymous probes run afterward in the same warmed JVM; not an isolated estimate of personal-query cost.",
                        "RSS is whole JVM after workload, not incremental cache heap. JSON bytes are not local retained heap.",
                        "All providers preserve live brand, counter and deleted-ID checks. DB-only skips invalidation.",
                        "Redis runs on the same host; these are not production network or monetary costs."]}
    processes = []
    created = False
    granted = False

    def get(body, port=18082):
        probe.URL = f"http://localhost:{port}" + path
        return probe.request(body)[0]

    def put(price, port=18082):
        request = Request(f"http://localhost:{port}/api-admin/v1/products/{target['id']}",
                          data=json.dumps({"name": target["name"], "price": price}).encode(),
                          headers={"Content-Type": "application/json", "X-USER-ROLE": "ADMIN"}, method="PUT")
        start = time.perf_counter()
        with urlopen(request, timeout=30) as response:
            assert json.load(response)["meta"]["result"] == "SUCCESS"
        return (time.perf_counter() - start) * 1000

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def start(provider, round_number, port=18082):
        log_path = args.output.with_name(f"{args.output.stem}-{round_number}-{provider}-{port}.log")
        with log_path.open("w") as log:
            process = subprocess.Popen([
                "/opt/homebrew/opt/openjdk@21/bin/java", "-Xms256m", "-Xmx512m", "-jar", str(args.jar.resolve()),
                "--spring.profiles.active=benchmark", f"--server.port={port}",
                f"--management.server.port={port + 1}", "--spring.jpa.hibernate.ddl-auto=validate",
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
                get(expected, port)
                return process
            except URLError:
                if time.monotonic() >= deadline:
                    raise RuntimeError(f"Readiness timeout: {log_path}")
                time.sleep(.5)

    def stats(times):
        return {**scale.stats(times), "samples_ms": times}

    try:
        original_mysql(f"CREATE DATABASE {db};")
        created = True
        tables = original_mysql("SHOW TABLES FROM loopers_benchmark;").splitlines()
        for table in tables:
            assert table.replace('_', '').isalnum()
            sql(f"CREATE TABLE {table} LIKE loopers_benchmark.{table};")
        # Use current source schema/indexes for the business tables actually read.
        copied = ("brand", "product", "product_like_count") + (("users", "product_like") if args.user_id else ())
        for table in copied:
            sql(f"DROP TABLE IF EXISTS {table}; CREATE TABLE {table} LIKE {scale.DB}.{table}; "
                f"INSERT INTO {table} SELECT * FROM {scale.DB}.{table};")
        original_mysql(f"GRANT SELECT, INSERT, UPDATE, DELETE ON {db}.* TO 'application'@'%';")
        granted = True
        assert sql("SELECT COUNT(*) FROM product;") == "100000"
        for round_number, order in enumerate((("db", "local", "redis"), ("local", "redis", "db"),
                                               ("redis", "db", "local")), 1):
            for provider in order:
                existing.redis("DEL", key)
                process = start(provider, round_number)
                row = {"round": round_number, "provider": provider, "modes": {}}
                try:
                    for mode in (("db_only",) if provider == "db" else ("hit", "miss")):
                        times, writes = [], []
                        for i in range(100 + args.samples):
                            if mode == "miss":
                                write_time = put(target["price"])
                                if i >= 100:
                                    writes.append(write_time)
                            elapsed = get(expected)
                            if i >= 100:
                                times.append(elapsed)
                        if mode == "miss":
                            put(target["price"])
                        probe.mysql = sql
                        try:
                            captured = probe.capture_sql(expected)
                        finally:
                            probe.mysql = original_mysql
                        required = 5 if args.user_id is not None else 3
                        assert captured["business_select_count"] == required, captured
                        assert all("count(" not in q["sql"].lower() for q in captured["queries"]), captured
                        row["modes"][mode] = {"http": stats(times), "sql_probe": captured}
                        if writes:
                            row["modes"][mode]["invalidation_put_http"] = stats(writes)
                        print(round_number, provider, mode, round(scale.stats(times)["median_ms"], 3), flush=True)
                    # Same real price mutation pattern for each provider; 10 reads per committed change.
                    mixed, writes = [], []
                    body = copy.deepcopy(expected)
                    for i in range(args.samples):
                        if i % 10 == 0:
                            price = target["price"] - (1 if (i // 10) % 2 == 0 else 0)
                            writes.append(put(price))
                            body["items"][0]["price"] = price
                        mixed.append(get(body))
                    put(target["price"])
                    get(expected)
                    row["mixed"] = {"http": stats(mixed), "put_http": stats(writes),
                                    "reads_per_change": 10, "expected_hit_fraction": 0 if provider == "db" else .9,
                                    "hit_fraction_note": "Workload-derived, not a production measurement; SQL checked separately."}
                    row["process_rss_kib"] = int(subprocess.check_output(
                        ["ps", "-o", "rss=", "-p", str(process.pid)], text=True).strip())
                    if provider == "redis":
                        cached = json.loads(existing.redis("GET", key))
                        assert set(cached) == {"items"} and len(cached["items"]) == 20
                        assert [i["id"] for i in cached["items"]] == [i["id"] for i in expected["items"]]
                        row["cached_fields"] = sorted(cached)
                        row["redis_value_bytes"] = int(existing.redis("STRLEN", key))
                        row["redis_key_memory_bytes"] = int(existing.redis("MEMORY", "USAGE", key))
                        row["ttl_seconds"] = int(existing.redis("TTL", key))
                        assert 0 < row["ttl_seconds"] <= 300
                    if args.user_id is not None:
                        probe.HEADERS = {}
                        try:
                            anonymous = api.expected(False)
                            anonymous_times = []
                            for i in range(100 + args.samples):
                                elapsed = get(anonymous)
                                if i >= 100:
                                    anonymous_times.append(elapsed)
                            probe.mysql = sql
                            try:
                                captured = probe.capture_sql(anonymous)
                            finally:
                                probe.mysql = original_mysql
                            assert captured["business_select_count"] == 3, captured
                            assert all("from users " not in q["sql"].lower()
                                       and "from product_like " not in q["sql"].lower()
                                       for q in captured["queries"]), captured
                            row["anonymous"] = {"mode": "db_only" if provider == "db" else "hit",
                                                "http": stats(anonymous_times), "sql_probe": captured}
                        finally:
                            probe.HEADERS = signed_headers
                    if round_number == 3 and provider != "db":
                        other = start(provider, round_number, 18084)
                        try:
                            get(expected)
                            get(expected, 18084)
                            newer = copy.deepcopy(expected)
                            newer["items"][0]["price"] -= 1
                            put(newer["items"][0]["price"])
                            get(newer)
                            get(expected if provider == "local" else newer, 18084)
                            row["two_processes"] = {"writer_fresh": True,
                                "other_process_fresh": provider == "redis",
                                "other_process_stale_price_reproduced": provider == "local"}
                        finally:
                            stop(other)
                            put(target["price"])
                    result["rounds"].append(row)
                    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
                finally:
                    stop(process)
    finally:
        probe.mysql = original_mysql
        for process in processes:
            stop(process)
        existing.redis("DEL", key)
        if granted:
            original_mysql(f"REVOKE SELECT, INSERT, UPDATE, DELETE ON {db}.* FROM 'application'@'%';")
        if created:
            original_mysql(f"DROP DATABASE {db};")
        assert existing.source_snapshot(scale.DB) == before, "Source changed"
        assert original_mysql(f"SELECT COUNT(*),SUM(like_count) FROM {scale.DB}.product_like_count;") == counter_before
        result["source_unchanged"] = True
        result["owned_resources_cleaned"] = True
        result["jar_sha256"] = hashlib.sha256(args.jar.read_bytes()).hexdigest()
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    result["completed"] = True
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("Saved", args.output, flush=True)


if __name__ == "__main__":
    main()

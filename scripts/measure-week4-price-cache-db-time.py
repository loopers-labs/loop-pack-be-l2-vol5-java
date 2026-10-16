#!/usr/bin/env python3
"""Measure MySQL work per brand-7 first-page list request for DB-only, Redis hit and Redis miss.

Uses performance_schema statement digests (server execution time, CPU time, rows examined)
on an owned copy of the normal 100k seed. HTTP latency is recorded only as a sanity value.
--brand-skew remaps product IDs <= 90000 to brand 7 in the copy (87,390 active products).
--stampede runs synchronized request bursts instead of sequential modes and counts how many
requests recomputed the page from the database.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
from http.client import HTTPException
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from urllib.error import HTTPError, URLError

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("existing", ROOT / "scripts/measure-week4-price-cache.py")
existing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(existing)
api, probe, scale = existing.api, existing.probe, existing.scale

PS_TO_MS = 1e-9  # performance_schema timers are picoseconds


def redis(*args):
    """docker exec redis-cli occasionally stalls on the local Docker VM; retry before failing the run."""
    for attempt in range(3):
        try:
            return existing.redis(*args)
        except subprocess.TimeoutExpired:
            if attempt == 2:
                raise
            time.sleep(1)


def reference(sort, skew, user_id):
    seed = scale.seed
    ids = [i for i in range(1, 100001)
           if (7 if skew and i <= 90000 else seed.brand_id(i)) == 7 and not seed.deleted(i)]
    if sort == "popular":
        key = lambda i: (-seed.like_count(i), -(i % 30), -i)
    else:
        key = lambda i: (seed.price(i), -(i % 30), -i)
    return {"items": [{"id": i, "brand": {"id": 7, "name": "W4 Brand 007"},
                       "name": f"W4 Product {i:06d}", "price": seed.price(i),
                       "likeCount": seed.like_count(i), "isLiked": api.seed_liked(i, user_id)}
                      for i in sorted(ids, key=key)[:20]], "page": 0, "size": 20}


def is_page_recompute(digest_text):
    """Product read that selects the page from the whole brand (DB-only/miss), not the hit ID check."""
    text = digest_text.lower()
    return ("from `product`" in text and "`brand_id` = ?" in text and "in (" not in text) \
        or ("from `product`" in text and "order by" in text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=200)
    parser.add_argument("--warmup", type=int, default=100)
    parser.add_argument("--user-id", type=int, default=95)
    parser.add_argument("--sort", choices=("price_asc", "popular"), default="price_asc")
    parser.add_argument("--brand-skew", action="store_true")
    parser.add_argument("--product-cache", action="store_true",
                        help="popular only: Redis app also reads product values from the per-product cache")
    parser.add_argument("--stampede", help="comma-separated burst concurrencies, e.g. 20,80")
    parser.add_argument("--bursts", type=int, default=10)
    parser.add_argument("--sustain", type=int, help="seconds of continuous load per provider (natural TTL expiry)")
    parser.add_argument("--threads", type=int, default=20, help="client threads for --sustain")
    args = parser.parse_args()
    assert args.jar.is_file() and not args.output.exists() and args.samples >= 20
    assert 1 <= args.user_id <= scale.seed.USERS
    if args.product_cache:
        # The product cache was reverted (ADR 0027); refuse to label a run "on" when the JAR cannot honour it.
        with zipfile.ZipFile(args.jar) as jar:
            if "BOOT-INF/classes/com/loopers/infrastructure/product/RedisProductCache.class" not in jar.namelist():
                parser.error("--product-cache needs a JAR built with the reverted product cache (see ADR 0027)")
    probe.HEADERS = {"X-USER-ID": str(args.user_id)}
    for port in (18082, 18083):
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            check.bind(("127.0.0.1", port))
    mysql = probe.mysql
    tag = uuid.uuid4().hex[:12]
    db = "w4_cache_dbtime_" + tag
    prefix = "w4-cache-dbtime:" + tag
    key = prefix + f":brand:7:sort:{args.sort}:page:0:size:20"
    sql = lambda query: mysql(f"USE {db};\n" + query)
    before = existing.source_snapshot(scale.DB)
    expected = reference(args.sort, args.brand_skew, args.user_id)
    concurrencies = [int(c) for c in args.stampede.split(",")] if args.stampede else []
    probe.URL = f"http://localhost:18082/api/v1/products?brandId=7&page=0&size=20&sort={args.sort}"
    old_cpu = mysql("SELECT ENABLED FROM performance_schema.setup_consumers "
                    "WHERE NAME='events_statements_cpu';")
    result = {"completed": False, "source": scale.DB, "sort": args.sort, "requester_id": args.user_id,
              "brand_skew": args.brand_skew, "product_cache": args.product_cache, "brand7_active_products": 87390 if args.brand_skew else 990,
              "stampede_concurrencies": concurrencies, "bursts": args.bursts,
              "sustain_seconds": args.sustain, "sustain_threads": args.threads if args.sustain else None,
              "samples_per_mode": args.samples, "warmup": args.warmup, "rounds": [],
              "notes": ["Actual application JAR, sequential HTTP, one app process per provider.",
                        "DB work = performance_schema statement digests filtered by the disposable schema, "
                        "reset after warmup; includes every statement the app sent (SELECT, SET, COMMIT...).",
                        "Timer = server-side statement execution time; CPU = SUM_CPU_TIME with "
                        "events_statements_cpu temporarily enabled. Not whole-server CPU.",
                        "Miss = owned Redis key deleted before each GET outside HTTP timing (no admin write).",
                        "db_only_gap = DB-only with the same per-request redis-cli DEL gap as miss, "
                        "to separate idle-gap effects from cache-miss work.",
                        "Single local host; not a production capacity claim.",
                        "Stampede: threads released together by a barrier (arrival not forced to the same "
                        "instant); cold = key deleted before each burst, warm = key filled before each burst; "
                        "page_recomputes = executions of the whole-brand product read during the burst."]}
    processes = []
    created = granted = False

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def start(provider, round_number):
        log_path = args.output.with_name(f"{args.output.stem}-{round_number}-{provider}.log")
        with log_path.open("w") as log:
            process = subprocess.Popen([
                "/opt/homebrew/opt/openjdk@21/bin/java", "-Xms256m", "-Xmx512m", "-jar", str(args.jar.resolve()),
                "--spring.profiles.active=benchmark", "--server.port=18082", "--management.server.port=18083",
                "--spring.jpa.hibernate.ddl-auto=validate",
                f"--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3307/{db}",
                "--commerce.cache.price.enabled=" + str(provider == "redis" and args.sort == "price_asc").lower(),
                "--commerce.cache.price.provider=redis", "--commerce.cache.price.prefix=" + prefix,
                "--commerce.cache.popular.enabled=" + str(provider == "redis" and args.sort == "popular").lower(),
                "--commerce.cache.popular.prefix=" + prefix,
                "--commerce.cache.product.enabled="
                + str(provider == "redis" and args.sort == "popular" and args.product_cache).lower(),
                "--commerce.cache.product.prefix=" + prefix],
                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 90
        while True:
            if process.poll() is not None:
                raise RuntimeError(f"App exited: {log_path}")
            try:
                probe.request(expected)
                return process
            except URLError:
                if time.monotonic() >= deadline:
                    raise RuntimeError(f"Readiness timeout: {log_path}")
                time.sleep(.5)

    def digests():
        rows = mysql(
            "SELECT REPLACE(REPLACE(DIGEST_TEXT, '\\t', ' '), '\\n', ' '), COUNT_STAR, SUM_TIMER_WAIT, "
            "SUM_CPU_TIME, SUM_ROWS_EXAMINED, SUM_ROWS_SENT "
            "FROM performance_schema.events_statements_summary_by_digest "
            f"WHERE SCHEMA_NAME='{db}' ORDER BY SUM_TIMER_WAIT DESC;")
        out = []
        for line in filter(None, rows.splitlines()):
            text, count, timer, cpu, examined, sent = line.split("\t")
            out.append({"digest_text": text[:300], "count": int(count),
                        "exec_ms": int(timer) * PS_TO_MS, "cpu_ms": int(cpu) * PS_TO_MS,
                        "rows_examined": int(examined), "rows_sent": int(sent)})
        return out

    def run(mode):
        times = []
        for i in range(args.warmup + args.samples):
            if i == args.warmup:
                mysql("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;")
            if mode in ("miss", "db_only_gap"):
                redis("DEL", key)  # db_only_gap: same idle gap as miss, key unused
            elapsed = probe.request(expected)[0]
            if i >= args.warmup:
                times.append(elapsed)
        rows = digests()
        n = args.samples
        total = lambda field, only_select=False: sum(
            r[field] for r in rows if not only_select or r["digest_text"].upper().startswith("SELECT"))
        return {"http": scale.stats(times), "digests": rows, "per_request": {
            "statements": total("count") / n, "select_statements": total("count", True) / n,
            "exec_ms": total("exec_ms") / n, "select_exec_ms": total("exec_ms", True) / n,
            "cpu_ms": total("cpu_ms") / n, "rows_examined": total("rows_examined") / n}}

    def burst(concurrency):
        gate = threading.Barrier(concurrency)

        def worker():
            gate.wait()
            started = time.perf_counter()
            try:
                return probe.request(expected)[0], None
            except HTTPError as error:  # e.g. 500 from the app; recorded instead of aborting the run
                return (time.perf_counter() - started) * 1000, error.code
            except (TimeoutError, URLError, ConnectionError, HTTPException):  # timeout or app closed the connection
                return (time.perf_counter() - started) * 1000, "no-response"

        with ThreadPoolExecutor(concurrency) as pool:
            return [f.result() for f in [pool.submit(worker) for _ in range(concurrency)]]

    def run_sustained():
        """N threads request continuously; the cache key is never deleted, so it expires on its own TTL."""
        for _ in range(20):  # warm JVM and fill the key outside the window
            probe.request(expected)
        mysql("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;")
        samples, lock = [], threading.Lock()
        start = time.perf_counter()
        deadline = start + args.sustain

        def worker():
            local = []
            while time.perf_counter() < deadline:
                began = time.perf_counter()
                try:
                    probe.request(expected)
                    code = None
                except HTTPError as error:
                    code = error.code
                except (TimeoutError, URLError, ConnectionError, HTTPException):
                    code = "no-response"
                ended = time.perf_counter()
                local.append((round(began - start, 3), round((ended - began) * 1000, 3), code))
            with lock:
                samples.extend(local)

        with ThreadPoolExecutor(args.threads) as pool:
            for future in [pool.submit(worker) for _ in range(args.threads)]:
                future.result()
        elapsed = time.perf_counter() - start
        rows = digests()
        ok = [ms for _, ms, code in samples if code is None]
        total_ms = sum(r["exec_ms"] for r in rows)
        result = {"seconds": elapsed, "threads": args.threads, "requests": len(samples),
                  "failures": len(samples) - len(ok), "throughput_rps": len(samples) / elapsed,
                  "http": {**scale.stats(ok), "p99_ms": probe.percentile(ok, .99)} if ok else {},
                  "db_exec_ms_total": total_ms, "db_exec_ms_per_request": total_ms / max(len(samples), 1),
                  "db_cpu_ms_total": sum(r["cpu_ms"] for r in rows),
                  "page_recomputes": sum(r["count"] for r in rows if is_page_recompute(r["digest_text"])),
                  "digests": rows, "samples": samples}
        http = result["http"]
        print("sustain", round(result["throughput_rps"], 1), "rps", "fail", result["failures"],
              "p50", round(http.get("median_ms", 0), 1), "p95", round(http.get("p95_ms", 0), 1),
              "p99", round(http.get("p99_ms", 0), 1), "db_ms/req", round(result["db_exec_ms_per_request"], 3),
              "recomputes", result["page_recomputes"], flush=True)
        return result

    def run_stampede(provider):
        out = {}
        for concurrency in concurrencies:
            for state in (("db_only",) if provider == "db" else ("cold", "warm")):
                times, recomputes, exec_ms, errors = [], [], [], []
                for i in range(2 + args.bursts):
                    if state == "cold":
                        redis("DEL", key)
                    elif state == "warm":
                        try:
                            probe.request(expected)
                        except (HTTPError, TimeoutError, URLError, ConnectionError, HTTPException):
                            pass  # an unhealthy app shows up as errors in the measured burst
                    measured = i >= 2
                    if measured:
                        mysql("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;")
                    results = burst(concurrency)
                    if measured:
                        rows = digests()
                        times.extend(ms for ms, code in results if code is None)
                        errors.append([code for _, code in results if code is not None])
                        recomputes.append(sum(r["count"] for r in rows if is_page_recompute(r["digest_text"])))
                        exec_ms.append(sum(r["exec_ms"] for r in rows))
                http = {**scale.stats(times), "p99_ms": probe.percentile(times, .99)} if times else {}
                out[f"{state}@{concurrency}"] = {"http": http, "page_recomputes_per_burst": recomputes,
                                                 "db_exec_ms_per_burst": exec_ms, "http_errors_per_burst": errors}
                print(provider, state, concurrency, "recomputes", recomputes, "errors", [len(e) for e in errors],
                      "p50", round(http.get("median_ms", 0), 1), "p99", round(http.get("p99_ms", 0), 1),
                      "db_ms/burst", round(sum(exec_ms) / len(exec_ms), 1), flush=True)
        return out

    try:
        mysql("UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME='events_statements_cpu';")
        mysql(f"CREATE DATABASE {db};")
        created = True
        for table in mysql("SHOW TABLES FROM loopers_benchmark;").splitlines():
            assert table.replace('_', '').isalnum()
            sql(f"CREATE TABLE {table} LIKE loopers_benchmark.{table};")
        for table in ("brand", "product", "product_like_count", "users", "product_like"):
            sql(f"DROP TABLE IF EXISTS {table}; CREATE TABLE {table} LIKE {scale.DB}.{table}; "
                f"INSERT INTO {table} SELECT * FROM {scale.DB}.{table};")
            sql(f"ANALYZE TABLE {table};")
        if args.brand_skew:
            sql("UPDATE product SET brand_id=7 WHERE id <= 90000; ANALYZE TABLE product;")
        mysql(f"GRANT SELECT, INSERT, UPDATE, DELETE ON {db}.* TO 'application'@'%';")
        granted = True
        assert sql("SELECT COUNT(*) FROM product;") == "100000"
        assert sql("SELECT COUNT(*) FROM product WHERE brand_id=7 AND deleted_at IS NULL;") == \
            str(result["brand7_active_products"])
        for round_number, order in enumerate((("db", "redis"), ("redis", "db"), ("db", "redis")), 1):
            for provider in order:
                redis("DEL", key)
                process = start(provider, round_number)
                row = {"round": round_number, "provider": provider, "modes": {}}
                try:
                    if args.sustain:
                        row["sustained"] = run_sustained()
                        result["rounds"].append(row)
                        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
                        continue
                    if concurrencies:
                        row["stampede"] = run_stampede(provider)
                        result["rounds"].append(row)
                        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
                        continue
                    for mode in (("db_only", "db_only_gap") if provider == "db" else ("hit", "miss")):
                        if mode == "hit":
                            probe.request(expected)
                        row["modes"][mode] = run(mode)
                        p = row["modes"][mode]["per_request"]
                        print(round_number, provider, mode, {k: round(v, 4) for k, v in p.items()}, flush=True)
                    result["rounds"].append(row)
                    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
                finally:
                    stop(process)
    finally:
        for process in processes:
            stop(process)
        cleanup = [lambda: redis("EVAL", "for _,k in ipairs(redis.call('KEYS', ARGV[1])) do redis.call('DEL', k) end",
                                 "0", prefix + ":*"),
                   lambda: mysql(f"UPDATE performance_schema.setup_consumers SET ENABLED='{old_cpu}' "
                                 "WHERE NAME='events_statements_cpu';")]
        if granted:
            cleanup.append(lambda: mysql(f"REVOKE SELECT, INSERT, UPDATE, DELETE ON {db}.* FROM 'application'@'%';"))
        if created:
            cleanup.append(lambda: mysql(f"DROP DATABASE {db};"))
        failed = []
        for step in cleanup:  # each step runs even if an earlier one failed
            try:
                step()
            except Exception as error:
                failed.append(repr(error))
        if failed:
            raise RuntimeError(f"Cleanup incomplete: {failed}")
        assert existing.source_snapshot(scale.DB) == before, "Source changed"
        result["source_unchanged"] = True
        result["owned_resources_cleaned"] = True
        result["jar_sha256"] = hashlib.sha256(args.jar.read_bytes()).hexdigest()
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    result["completed"] = True
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("Saved", args.output, flush=True)


if __name__ == "__main__":
    main()

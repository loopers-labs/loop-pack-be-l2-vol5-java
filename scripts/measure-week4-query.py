#!/usr/bin/env python3
"""Measure the existing product API; optionally capture one request's real SQL."""

import argparse
from datetime import datetime
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import platform
import re
import statistics
import subprocess
import time
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
URL = "http://localhost:8082/api/v1/products?brandId=7&page=0&size=20&sort=price_asc"
HEADERS = {}


def mysql(sql):
    command = [
        "docker", "compose", "-f", str(ROOT / "docker/infra-compose.yml"),
        "--profile", "benchmark", "exec", "-T", "mysql-benchmark", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --raw --skip-column-names '
        '-u root -D loopers_benchmark',
    ]
    result = subprocess.run(command, input=sql, text=True, capture_output=True, check=True, timeout=60)
    return result.stdout.strip()


def expected_response():
    spec = importlib.util.spec_from_file_location("seed", ROOT / "scripts/seed-week4.py")
    seed = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(seed)
    ids = [i for i in range(1, 10001) if seed.brand_id(i) == 7 and not seed.deleted(i)]
    ids.sort(key=lambda i: (seed.price(i), -(i % 30), -i))
    return {
        "items": [{"id": i, "brand": {"id": 7, "name": "W4 Brand 007"},
                   "name": f"W4 Product {i:05d}", "price": seed.price(i),
                   "likeCount": seed.like_count(i), "isLiked": False} for i in ids[:20]],
        "page": 0, "size": 20,
    }


def request(expected):
    start = time.perf_counter()
    with urlopen(Request(URL, headers=HEADERS), timeout=30) as response:
        payload = response.read()
    elapsed = (time.perf_counter() - start) * 1000
    body = json.loads(payload)
    if body["meta"]["result"] != "SUCCESS" or body["data"] != expected:
        raise RuntimeError("API result differs from the seed's expected page; no valid comparison")
    return elapsed, len(payload)


def capture_sql(expected):
    # Dedicated benchmark server only. Restore logging even if the probe fails.
    old_log, old_output = mysql("SELECT @@global.general_log, @@global.log_output;").split("\t")
    stamp = None
    end = None
    try:
        mysql("SET GLOBAL general_log=OFF; SET GLOBAL log_output='TABLE'; SET GLOBAL general_log=ON;")
        stamp = mysql("SELECT DATE_FORMAT(NOW(6), '%Y-%m-%d %H:%i:%s.%f');")
        request(expected)
        end = mysql("SELECT DATE_FORMAT(NOW(6), '%Y-%m-%d %H:%i:%s.%f');")
    finally:
        mysql(f"SET GLOBAL general_log=OFF; SET GLOBAL log_output='{old_output}'; SET GLOBAL general_log={int(old_log)};")
    rows = mysql(f"SELECT HEX(argument), thread_id FROM mysql.general_log "
                 f"WHERE event_time >= '{stamp}' AND event_time <= '{end}' "
                 "AND command_type IN ('Query','Execute') ORDER BY event_time;")
    queries = []
    threads = set()
    for row in rows.splitlines():
        encoded, thread = row.split("\t")
        sql = bytes.fromhex(encoded).decode()
        if re.match(r"\s*select\b", sql, re.I) and re.search(r"\bfrom\s+`?(brand|product|product_like|product_like_count|users)\b", sql, re.I):
            threads.add(thread)
            queries.append(sql)
    if not queries or len(threads) != 1:
        raise RuntimeError("Cannot isolate one API request's SQL; stop other benchmark traffic")
    plans = []
    for sql in queries:
        plans.append({"sql": sql, "explain_json": json.loads(mysql("EXPLAIN FORMAT=JSON " + sql + ";")),
                      "explain_analyze": mysql("EXPLAIN ANALYZE " + sql + ";")})
    return {"business_select_count": len(queries), "queries": plans,
            "previous_general_log": old_log, "previous_log_output": old_output}


def percentile(values, p):
    return sorted(values)[math.ceil(p * len(values)) - 1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--samples", type=int, default=30)
    parser.add_argument("--capture-sql", action="store_true")
    args = parser.parse_args()
    if args.warmup < 0 or args.samples < 2:
        parser.error("warmup must be >= 0 and samples must be >= 2")
    if args.output.exists():
        parser.error("output exists; choose a new path to preserve earlier evidence")
    expected = expected_response()
    print(f"Warmup={args.warmup}; samples={args.samples}; concurrency=1; {URL}", flush=True)
    for _ in range(args.warmup):
        request(expected)
    measured = [request(expected) for _ in range(args.samples)]
    times = [x[0] for x in measured]
    result = {
        "recorded_at": datetime.now(ZoneInfo("Asia/Seoul")).isoformat(),
        "url": URL, "host_platform": platform.platform(),
        "warmup": args.warmup, "samples": args.samples, "concurrency": 1,
        "database": mysql("SELECT VERSION(), DATABASE(), @@transaction_isolation;"),
        "indexes": mysql("SHOW INDEX FROM product; SHOW INDEX FROM product_like;"),
        "response": expected,
        "response_sha256": hashlib.sha256(json.dumps(expected, sort_keys=True).encode()).hexdigest(),
        "response_bytes": sorted(set(x[1] for x in measured)),
        "http_ms": {"mean": statistics.mean(times), "median": statistics.median(times),
                    "p95": percentile(times, .95), "min": min(times), "max": max(times)},
        "all_http_ms": times,
        "notes": ["Sequential urllib HTTP calls including connection, full body read; JSON validation excluded from timing",
                  "Warm process/data; no concurrent load; not a throughput or production latency benchmark",
                  "SQL capture and EXPLAIN ANALYZE run separately after HTTP timing; no explicit ANALYZE TABLE",
                  "Business SELECT count excludes transaction/session commands; SQL is captured with actual bound values"],
    }
    if args.capture_sql:
        result["sql_probe"] = capture_sql(expected)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result["http_ms"], indent=2))
    if args.capture_sql:
        print("Business SELECTs:", result["sql_probe"]["business_select_count"])
    print("Saved:", args.output)


if __name__ == "__main__":
    main()

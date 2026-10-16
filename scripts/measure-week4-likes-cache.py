#!/usr/bin/env python3
"""Measure the existing likes_desc HTTP path using an owned copy of the 100k seed."""
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
spec = importlib.util.spec_from_file_location("existing", ROOT / "scripts/measure-week4-price-cache.py")
existing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(existing)
probe, scale = existing.probe, existing.scale


def expected(user_id=95, changes=None):
    changes = changes or {}
    seed = scale.seed
    ids = [i for i in range(1, 100001) if seed.brand_id(i) == 7 and not seed.deleted(i)]
    count = lambda i: seed.like_count(i) + changes.get(i, 0)
    ordered = sorted(ids, key=lambda i: (-count(i), -(i % 30), -i))[:20]
    return {"items": [{"id": i, "brand": {"id": 7, "name": "W4 Brand 007"},
                       "name": f"W4 Product {i:06d}", "price": seed.price(i), "likeCount": count(i),
                       "isLiked": existing.api.seed_liked(i, user_id) or changes.get(i, 0) > 0}
                      for i in ordered], "page": 0, "size": 20}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--samples", type=int, default=200)
    args = parser.parse_args()
    assert args.jar.is_file() and not args.output.exists() and args.samples >= 20
    for port in (18086, 18087):
        with socket.socket() as check:
            check.bind(("127.0.0.1", port))
    original_mysql = probe.mysql
    tag = uuid.uuid4().hex[:12]
    database = "w4_likes_baseline_" + tag
    sql = lambda query: original_mysql(f"USE {database};\n" + query)
    source_before = existing.source_snapshot(scale.DB)
    counter_before = original_mysql(f"SELECT COUNT(*),SUM(like_count),BIT_XOR(CRC32(CONCAT_WS('|',product_id,like_count))) FROM {scale.DB}.product_like_count;")
    body = expected()
    probe.URL = "http://localhost:18086/api/v1/products?brandId=7&page=0&size=20&sort=likes_desc"
    probe.HEADERS = {"X-USER-ID": "95"}
    result = {"completed": False, "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
              "source": scale.DB, "source_before": source_before, "expected_response": body,
              "conditions": {"warmup": 100, "samples": args.samples, "rounds": 3, "requester_id": 95},
              "rounds": [], "notes": ["Pre-cache baseline, actual application JAR and existing indexes.",
                  "Owned copied DB; source data/indexes unchanged. No new seed generation.",
                  "Sequential HTTP; JSON response validation excluded from timing; not a throughput benchmark.",
                  "General SQL log and EXPLAIN run separately after timing."]}
    created = granted = False
    process = None

    def save():
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")

    def stop():
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    try:
        original_mysql(f"CREATE DATABASE {database};")
        created = True
        for table in original_mysql("SHOW TABLES FROM loopers_benchmark;").splitlines():
            assert table.replace('_', '').isalnum()
            sql(f"CREATE TABLE {table} LIKE loopers_benchmark.{table};")
        for table in ("brand", "product", "product_like_count", "users", "product_like"):
            sql(f"DROP TABLE IF EXISTS {table}; CREATE TABLE {table} LIKE {scale.DB}.{table}; INSERT INTO {table} SELECT * FROM {scale.DB}.{table};")
        original_mysql(f"GRANT SELECT,INSERT,UPDATE,DELETE ON {database}.* TO 'application'@'%';")
        granted = True
        result["brand_7_active_products"] = int(sql("SELECT COUNT(*) FROM product WHERE brand_id=7 AND deleted_at IS NULL;"))
        for round_number in range(1, 4):
            log_path = args.output.with_name(f"{args.output.stem}-{round_number}.log")
            with log_path.open('w') as log:
                process = subprocess.Popen(["/opt/homebrew/opt/openjdk@21/bin/java", "-Xms256m", "-Xmx512m", "-jar", str(args.jar.resolve()),
                    "--spring.profiles.active=benchmark", "--server.port=18086", "--management.server.port=18087",
                    "--spring.jpa.hibernate.ddl-auto=validate", f"--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3307/{database}",
                    "--commerce.cache.price.enabled=false", "--commerce.cache.likes.enabled=false"], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f"App exited: {log_path}")
                    try:
                        probe.request(body)
                        break
                    except URLError:
                        if time.monotonic() >= deadline:
                            raise RuntimeError(f"Readiness timeout: {log_path}")
                        time.sleep(.5)
                for _ in range(100):
                    probe.request(body)
                samples = [probe.request(body)[0] for _ in range(args.samples)]
                probe.mysql = sql
                try:
                    captured = probe.capture_sql(body)
                finally:
                    probe.mysql = original_mysql
                assert captured['business_select_count'] == 5, captured
                assert not any('count(' in q['sql'].lower() for q in captured['queries'])
                result['rounds'].append({'round': round_number, 'http': scale.stats(samples), 'sql_probe': captured})
                save()
                print(round_number, 'DB-only likes_desc', round(scale.stats(samples)['median_ms'], 3), 'ms', flush=True)
            finally:
                stop()
    finally:
        probe.mysql = original_mysql
        stop()
        if granted:
            original_mysql(f"REVOKE SELECT,INSERT,UPDATE,DELETE ON {database}.* FROM 'application'@'%';")
        if created:
            original_mysql(f"DROP DATABASE {database};")
        assert existing.source_snapshot(scale.DB) == source_before
        assert original_mysql(f"SELECT COUNT(*),SUM(like_count),BIT_XOR(CRC32(CONCAT_WS('|',product_id,like_count))) FROM {scale.DB}.product_like_count;") == counter_before
        result.update(source_unchanged=True, owned_resources_cleaned=True)
        save()
    result['completed'] = True
    save()
    print('Saved', args.output, flush=True)


if __name__ == '__main__':
    main()

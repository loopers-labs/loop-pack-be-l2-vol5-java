#!/usr/bin/env python3
"""Measure warmed Redis GET and JSON decode separately using the built app's classes."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import uuid
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("existing", ROOT / "scripts/measure-week4-price-cache.py")
existing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(existing)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--warmup", type=int, default=1000)
    parser.add_argument("--samples", type=int, default=1000)
    args = parser.parse_args()
    assert args.jar.is_file() and not args.output.exists()
    assert args.warmup >= 100 and args.samples >= 200
    java_home = Path("/opt/homebrew/opt/openjdk@21")
    db = existing.scale.DB
    before = existing.source_snapshot(db)
    query = (f"SELECT id,name,price,created_at FROM {db}.product "
             "WHERE brand_id=7 AND deleted_at IS NULL "
             "ORDER BY price,created_at DESC,id DESC LIMIT 20;")
    rows = existing.probe.mysql(query).splitlines()
    items = []
    for row in rows:
        product_id, name, price, created = row.split("\t")
        items.append({"id": int(product_id), "name": name, "price": int(price),
                      "createdAt": created.replace(" ", "T") + "Z"})
    assert [i["id"] for i in items] == existing.scale.expected_ids()
    result = {"completed": False, "started_at_utc": datetime.now(timezone.utc).isoformat(),
              "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
              "host_platform": platform.platform(), "source": db,
              "product_total_count_included": False,
              "source_before": before, "source_page_sql": query,
              "source_product_count": int(existing.probe.mysql(f"SELECT COUNT(*) FROM {db}.product;")),
              "warmup_per_jvm": args.warmup, "samples_per_jvm": args.samples,
              "redis_endpoint": "localhost:6379", "redis_database": 0,
              "redis_version": existing.redis("INFO", "server").split("redis_version:")[1].splitlines()[0],
              "rounds": [], "notes": [
                  "Standalone Java component probe, not a running HTTP API or full cache.get instrumentation.",
                  "Uses existing PricePageRedisConfig, StringRedisTemplate, JacksonAutoConfiguration and project JacksonConfig.",
                  "Source page read via SQL before timing; only a unique TTL-bound Redis key is written.",
                  "Three independent JVMs; each uses one warmed shared native Lettuce connection.",
                  "Redis GET includes client, network, Redis processing and String decoding; not pure network latency.",
                  "JSON decode measured immediately after GET; equality validation is outside timed intervals.",
                  "No per-request logging, HTTP or DB queries during the measured loop; all samples retained.",
                  "Same-host Docker Redis; no production latency or causal attribution of previous HTTP differences."]}
    with tempfile.TemporaryDirectory(prefix="w4-redis-read-cost-") as directory:
        work = Path(directory)
        with zipfile.ZipFile(args.jar) as jar:
            for compiled in (ROOT / "apps/commerce-api/build/classes/java/main").rglob("*.class"):
                relative = compiled.relative_to(ROOT / "apps/commerce-api/build/classes/java/main")
                assert jar.read("BOOT-INF/classes/" + str(relative)) == compiled.read_bytes(), relative
            for source in (ROOT / "apps/commerce-api/src/main/java").rglob("*.java"):
                compiled = ROOT / "apps/commerce-api/build/classes/java/main" / source.relative_to(
                    ROOT / "apps/commerce-api/src/main/java").with_suffix(".class")
                assert compiled.exists() and source.stat().st_mtime <= compiled.stat().st_mtime, source
            for entry in jar.namelist():
                if entry.startswith(("BOOT-INF/lib/", "BOOT-INF/classes/")):
                    jar.extract(entry, work)
        classpath = str(work / "BOOT-INF/classes") + ":" + str(work / "BOOT-INF/lib/*")
        source = ROOT / "scripts/probes/PriceCacheReadCost.java"
        subprocess.run([str(java_home / "bin/javac"), "-cp", classpath, "-d", str(work), str(source)],
                       check=True, capture_output=True, text=True, timeout=60)
        input_path = work / "page.json"
        input_path.write_text(json.dumps({"items": items}) + "\n")
        for round_number in range(1, 4):
            prefix = "w4-redis-read-cost:" + uuid.uuid4().hex
            key = prefix + ":brand:7:sort:price_asc:page:0:size:20"
            output_path = work / "round.json"
            try:
                process = subprocess.run([
                    str(java_home / "bin/java"), "-Xms256m", "-Xmx512m", "-cp", str(work) + ":" + classpath,
                    "PriceCacheReadCost", str(input_path), str(output_path), prefix,
                    str(args.warmup), str(args.samples)],
                    check=True, capture_output=True, text=True, timeout=60)
                row = json.loads(output_path.read_text())
                assert row["validated_measured_reads"] == args.samples
                assert row["application_adapter_result_matches"]
                assert 0 < row["ttl_seconds_observed"] <= 300
                assert existing.redis("EXISTS", key) == "0"
                row.update({"round": round_number, "owned_key_removed": True})
                result["rounds"].append(row)
                print(round_number, "GET", round(row["redis_get"]["median_ms"], 4), "ms; decode",
                      round(row["json_decode"]["median_ms"], 4), "ms", flush=True)
            except subprocess.CalledProcessError as error:
                print(error.stderr, file=sys.stderr)
                raise
            finally:
                existing.redis("DEL", key)
                assert existing.redis("EXISTS", key) == "0"
    assert existing.source_snapshot(db) == before, "Source data/indexes changed"
    result.update({"completed": True, "source_unchanged": True, "owned_resources_cleaned": True,
                   "finished_at_utc": datetime.now(timezone.utc).isoformat()})
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("Saved", args.output, flush=True)


if __name__ == "__main__":
    main()

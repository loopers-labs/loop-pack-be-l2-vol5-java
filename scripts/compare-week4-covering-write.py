#!/usr/bin/env python3
"""Compare product write cost with and without name in the price index on owned copies."""

import argparse
from datetime import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import random
import re
import statistics
import sys
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
SOURCE = "loopers_benchmark_100k.product"
INDEX = "idx_product_brand_price"
INDEX_COLUMNS = "brand_id,deleted_at,price ASC,created_at DESC,id DESC"
STAMP = "'2026-10-16 00:00:00.000000'"
OPERATIONS = ("insert", "name_update", "price_update", "soft_delete")


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


probe = load("measure-week4-query")
profile = load("compare-week4-like-index").profile
mysql = probe.mysql


def stats(values):
    return {"samples": len(values), "median_ms": statistics.median(values),
            "p95_ms": probe.percentile(values, .95), "mean_ms": statistics.mean(values),
            "min_ms": min(values), "max_ms": max(values)}


def fingerprint(table, columns, where=""):
    expressions = ",".join(f"COALESCE(CAST(`{c}` AS CHAR),'#NULL#')" for c in columns)
    return mysql(f"SELECT COUNT(*),MIN(id),MAX(id),SUM(id),SUM(price),SUM(quantity),"
                 f"SUM(deleted_at IS NULL),BIT_XOR(CRC32(CONCAT_WS('|',{expressions}))) "
                 f"FROM {table} {where};")


def source_snapshot(columns):
    return {"rows": fingerprint(SOURCE, columns),
            "definition": mysql(f"SHOW CREATE TABLE {SOURCE};"),
            "index_definitions": mysql("SELECT index_name,seq_in_index,column_name,collation,non_unique "
                "FROM information_schema.statistics WHERE table_schema='loopers_benchmark_100k' "
                "AND table_name='product' ORDER BY index_name,seq_in_index;")}


def storage(database, table):
    raw = mysql("SELECT data_length,index_length FROM information_schema.tables "
                f"WHERE table_schema='{database}' AND table_name='{table}';")
    data_bytes, index_bytes = map(int, raw.split("\t"))
    index_pages = mysql("SELECT index_name,stat_value FROM mysql.innodb_index_stats "
                       f"WHERE database_name='{database}' AND table_name='{table}' AND stat_name='size' "
                       "ORDER BY index_name;")
    return {"data_allocated_bytes": data_bytes, "secondary_indexes_allocated_bytes": index_bytes,
            "index_allocated_pages": {name: int(pages) for name, pages in
                (row.split("\t") for row in index_pages.splitlines())}}


def evidence_hashes():
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted((ROOT / "docs/week4/measurements").glob("*.json"))}


def statements(table, operation, ids):
    result = []
    for product_id in ids:
        if operation == "insert":
            dml = (f"INSERT INTO {table} (id,brand_id,name,price,quantity,created_at,updated_at,deleted_at) "
                   f"VALUES ({product_id},7,'W4 Product {product_id:06d}',6000,10,"
                   "'2026-01-01','2026-01-01',NULL)")
        else:
            values = {"name_update": "name=CONCAT('X',SUBSTRING(name,2))",
                      "price_update": "price=price+1", "soft_delete": f"deleted_at={STAMP}"}
            dml = f"UPDATE {table} SET {values[operation]},updated_at={STAMP} WHERE id={product_id}"
        result.extend(["START TRANSACTION", dml, "COMMIT"])
    return result


def validate_and_restore(table, operation, ids, size, columns, expected_fingerprint):
    wanted = ",".join(map(str, ids))
    count = int(mysql(f"SELECT COUNT(*) FROM {table} WHERE id IN ({wanted});"))
    assert count == len(ids), (table, operation, count)
    if operation == "insert":
        matched = mysql(f"SELECT COUNT(*) FROM {table} WHERE id IN ({wanted}) "
            "AND brand_id=7 AND name=CONCAT('W4 Product ',LPAD(id,6,'0')) AND price=6000 "
            "AND quantity=10 AND deleted_at IS NULL AND created_at='2026-01-01' AND updated_at='2026-01-01';")
        assert int(matched) == len(ids)
        assert int(mysql(f"SELECT COUNT(*) FROM {table};")) == size + len(ids)
        mysql(f"START TRANSACTION; DELETE FROM {table} WHERE id IN ({wanted}); COMMIT;")
    else:
        changes = {"updated_at": STAMP}
        changes.update({"name_update": {"name": "CONCAT('X',SUBSTRING(s.name,2))"},
                        "price_update": {"price": "s.price+1"},
                        "soft_delete": {"deleted_at": STAMP}}[operation])
        equality = " AND ".join(f"t.`{c}` <=> {changes.get(c, 's.`' + c + '`')}" for c in columns)
        matched = mysql(f"SELECT COUNT(*) FROM {table} t JOIN {SOURCE} s ON t.id=s.id "
                        f"WHERE t.id IN ({wanted}) AND ({equality});")
        assert int(matched) == len(ids), (table, operation, matched)
        assignments = ",".join(f"t.`{c}`=s.`{c}`" for c in changes)
        mysql(f"START TRANSACTION; UPDATE {table} t JOIN {SOURCE} s ON t.id=s.id "
              f"SET {assignments} WHERE t.id IN ({wanted}); COMMIT;")
    assert fingerprint(table, columns) == expected_fingerprint, (table, operation, "restore failed")


def has_covering_table(plan):
    if isinstance(plan, dict):
        if plan.get("using_index") is True:
            return True
        return any(has_covering_table(v) for v in plan.values())
    return isinstance(plan, list) and any(has_covering_table(v) for v in plan)


def plans(table, covering):
    tail = (f" FROM {table} FORCE INDEX ({INDEX}) WHERE brand_id=7 AND deleted_at IS NULL "
            "ORDER BY price ASC,created_at DESC,id DESC LIMIT 20")
    projected = json.loads(mysql("EXPLAIN FORMAT=JSON SELECT id,brand_id,name,price,created_at" + tail))
    entity = json.loads(mysql("EXPLAIN FORMAT=JSON SELECT id,brand_id,name,price,created_at,"
                              "deleted_at,quantity,updated_at" + tail))
    assert has_covering_table(projected) == covering
    assert not has_covering_table(entity), "Name alone must not cover the current full entity projection"
    return {"five_column_projection": projected, "current_entity_columns": entity}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=200)
    parser.add_argument("--warmup", type=int, default=50)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output exists; previous measurements must not be overwritten")
    if args.samples < 2 or args.warmup < 0 or args.repeats < 1 or args.samples + args.warmup > 1000:
        parser.error("Require samples >= 2, warmup >= 0, repeats >= 1 and samples + warmup <= 1000")
    columns = [line.split("\t")[0] for line in mysql(f"SHOW COLUMNS FROM {SOURCE};").splitlines()]
    assert set(columns) == {"id", "brand_id", "name", "price", "quantity", "created_at", "updated_at", "deleted_at"}
    assert all(re.fullmatch(r"[a-z_]+", c) for c in columns)
    before = source_snapshot(columns)
    assert before["rows"].split("\t")[:3] == ["100000", "1", "100000"], "Expected the existing 100k benchmark source"
    previous_evidence = evidence_hashes()
    total = args.samples + args.warmup
    expected = {size: fingerprint(SOURCE, columns, f"WHERE id<={size}") for size in (10000, 100000)}
    active = {size: [int(v) for v in mysql(f"SELECT id FROM {SOURCE} WHERE id<={size} "
                "AND deleted_at IS NULL AND LEFT(name,1)<>'X' ORDER BY id;").splitlines()]
              for size in (10000, 100000)}
    assert all(len(ids) >= total for ids in active.values())
    database = "loopers_benchmark_covering_write_" + uuid.uuid4().hex[:12]
    assert re.fullmatch(r"loopers_benchmark_covering_write_[0-9a-f]{12}", database)
    result = {"recorded_at": datetime.now().astimezone().isoformat(),
              "environment": mysql("SELECT VERSION(),@@transaction_isolation,@@innodb_buffer_pool_size,"
                  "@@innodb_page_size,@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@log_bin;"),
              "source": SOURCE, "source_before": before, "owned_database": database,
              "settings": {"samples": args.samples, "warmup": args.warmup, "repeats": args.repeats,
                  "batch_size": 25, "concurrency": 1, "random_seed": 417,
                  "sizes": [10000, 100000], "baseline_columns": INDEX_COLUMNS,
                  "candidate_columns": INDEX_COLUMNS + ",name"},
              "notes": [
                  "10k is the first 10k rows of the SAME 100k source; names have the same width and formulas.",
                  "Four owned tables are rebuilt each repeat; the name index REPLACES the original price index.",
                  "All other indexes and row values are identical; indexes are bulk-built after the same ordered load.",
                  "One client, warmed touched rows; case order alternates every batch and size order every repeat.",
                  "DML and START+DML+COMMIT are MySQL server profile sums in ms; not HTTP or client wall latency.",
                  "Every statement is committed; inserts use explicit fresh IDs, updates use sampled distinct active IDs.",
                  "Name changes preserve length; restoration/DDL/validation are outside the timed samples.",
                  "Physical page layout is not restored between operations; operation order rotates per repeat.",
                  "No concurrent workload, production throughput, buffer-pool pressure or API covering gain is measured.",
                  "Current entity columns quantity/updated_at still prevent covering with name alone.",
                  "Allocated bytes/pages are InnoDB allocation statistics, not exact live key bytes."],
              "rounds": []}
    owned = False
    try:
        mysql(f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;")
        owned = True
        for repeat in range(args.repeats):
            round_result = {"repeat": repeat + 1, "sizes": {}}
            for size in ((10000, 100000) if repeat % 2 == 0 else (100000, 10000)):
                tables = {case: f"{database}.p_{size}_{case}" for case in ("baseline", "with_name")}
                metadata = {}
                for case, table in tables.items():
                    mysql(f"CREATE TABLE {table} LIKE {SOURCE}; ALTER TABLE {table} DROP INDEX {INDEX}; "
                          f"INSERT INTO {table} SELECT * FROM {SOURCE} WHERE id<={size} ORDER BY id; "
                          f"CREATE INDEX {INDEX} ON {table} ({INDEX_COLUMNS + (',name' if case == 'with_name' else '')}); "
                          f"ANALYZE TABLE {table};")
                    assert fingerprint(table, columns) == expected[size]
                    metadata[case] = {"definition": mysql(f"SHOW CREATE TABLE {table};"),
                        "before_storage": storage(database, table.split(".")[1]),
                        "plans": plans(table, case == "with_name")}
                selected = random.Random(417 + repeat).sample(active[size], total)
                record = {"row_fingerprint": expected[size], "selected_active_ids": selected,
                          "cases": metadata, "operations": {}}
                operation_order = OPERATIONS[repeat % len(OPERATIONS):] + OPERATIONS[:repeat % len(OPERATIONS)]
                for operation in operation_order:
                    ids = list(range(size + 1, size + total + 1)) if operation == "insert" else selected
                    data = {case: {"transaction_ms": [], "dml_ms": [], "commit_ms": [],
                                  "raw_statement_ms": []} for case in tables}
                    for offset in range(0, total, 25):
                        cases = list(tables) if (repeat + offset // 25) % 2 == 0 else list(tables)[::-1]
                        for case in cases:
                            _, timings = profile(mysql, statements(tables[case], operation, ids[offset:offset + 25]))
                            for number in range(len(timings) // 3):
                                if offset + number < args.warmup:
                                    continue
                                start, dml, commit = timings[number * 3:number * 3 + 3]
                                data[case]["transaction_ms"].append(start + dml + commit)
                                data[case]["dml_ms"].append(dml)
                                data[case]["commit_ms"].append(commit)
                                data[case]["raw_statement_ms"].append([start, dml, commit])
                    for case, table in tables.items():
                        validate_and_restore(table, operation, ids, size, columns, expected[size])
                        data[case]["restored_and_validated"] = True
                        for key in ("transaction", "dml", "commit"):
                            assert len(data[case][key + "_ms"]) == args.samples
                            data[case][key] = stats(data[case][key + "_ms"])
                    old = data["baseline"]["transaction"]["median_ms"]
                    new = data["with_name"]["transaction"]["median_ms"]
                    data["median_transaction_change_percent"] = (new / old - 1) * 100
                    record["operations"][operation] = data
                    print(f"round {repeat + 1} / {size} / {operation}: transaction median "
                          f"{old:.4f} -> {new:.4f} ms ({(new / old - 1) * 100:+.1f}%)", flush=True)
                for case, table in tables.items():
                    mysql(f"ANALYZE TABLE {table};")
                    metadata[case]["after_storage"] = storage(database, table.split(".")[1])
                    mysql(f"DROP TABLE {table};")
                round_result["sizes"][str(size)] = record
            result["rounds"].append(round_result)
    finally:
        if owned:
            mysql(f"DROP DATABASE {database};")
            assert mysql("SELECT COUNT(*) FROM information_schema.schemata "
                         f"WHERE schema_name='{database}';") == "0"
            result["owned_database_removed"] = True
        result["source_after"] = source_snapshot(columns)
        assert result["source_after"] == before, "Original product rows/schema/indexes changed"
        assert evidence_hashes() == previous_evidence, "Existing measurement evidence changed"
    result["source_unchanged"] = True
    result["previous_evidence_unchanged"] = True
    result["previous_evidence_files_checked"] = len(previous_evidence)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print("Saved", args.output, flush=True)


if __name__ == "__main__":
    main()

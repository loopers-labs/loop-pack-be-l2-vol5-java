#!/usr/bin/env python3
"""Opt-in 100k read/write comparison on disposable copies; preserve source rows/indexes."""
import argparse
from datetime import datetime
import importlib.util
import json
from pathlib import Path
import statistics
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
DB = "loopers_benchmark_100k"
HOT = DB + "_hotspot"


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


probe = load("measure-week4-query")
profile = load("compare-week4-like-index").profile
seed = load("seed-week4-100k")


def mysql(sql, db=DB):
    assert db in (DB, HOT)
    return probe.mysql(f"USE {db};\n" + sql)


def stats(values):
    return {"median_ms": statistics.median(values), "mean_ms": statistics.mean(values),
            "p95_ms": probe.percentile(values, .95), "samples_ms": values}


def snapshot(db):
    # Full-row checksum plus count/sums, and index definitions without volatile cardinality.
    result = {}
    for table in ("product", "product_like", "brand", "users"):
        columns = mysql(f"SHOW COLUMNS FROM {table};", db)
        names = [line.split("\t")[0] for line in columns.splitlines()]
        expr = ",".join(f"COALESCE(CAST(`{name}` AS CHAR),'NULL')" for name in names)
        result[table] = mysql(f"SELECT COUNT(*),SUM(id),BIT_XOR(CRC32(CONCAT_WS('|',{expr}))) FROM {table};", db)
    result["indexes"] = mysql("SELECT table_name,index_name,seq_in_index,column_name,collation,non_unique "
                              "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                              "ORDER BY table_name,index_name,seq_in_index;", db)
    return result


def expected_ids():
    ids = [i for i in range(1, 100001) if seed.brand_id(i) == 7 and not seed.deleted(i)]
    return sorted(ids, key=lambda i: (seed.price(i), -(i % 30), -i))[:20]


def expected_page():
    return "\n".join(f"{i}\t7\tW4 Product {i:06d}\t{seed.price(i)}\t"
                     + seed.timestamp(i).strip("'") for i in expected_ids())


def expected_likes(hot=False):
    return "\n".join(f"{i}\t{seed.like_count(i) + (50000 if hot and i == 97407 else 0)}"
                     for i in sorted(expected_ids()) if seed.like_count(i) or (hot and i == 97407))


def handler(query):
    output = mysql("SHOW SESSION STATUS LIKE 'Handler_read%'; SELECT '#ROWS'; " + query
                   + "; SELECT '#AFTER'; SHOW SESSION STATUS LIKE 'Handler_read%';")
    before, rest = output.split("#ROWS\n")
    rows, after = rest.split("#AFTER\n")
    parse = lambda s: {k: int(v) for k, v in (line.split("\t") for line in s.strip().splitlines())}
    a, b = parse(before), parse(after)
    return rows.strip(), {key: b[key] - a[key] for key in a}


def compare(queries, expectations):
    samples = {key: [] for key in queries}
    keys = list(queries)
    for round_no in range(40):
        order = keys if round_no % 2 == 0 else keys[::-1]
        rows, times = profile(mysql, [queries[key] for key in order])
        assert rows == "\n".join(expectations[key] for key in order), "Read result mismatch"
        if round_no >= 10:
            for key, elapsed in zip(order, times):
                samples[key].append(elapsed)
    result = {}
    for key, query in queries.items():
        rows, delta = handler(query)
        assert rows == expectations[key]
        result[key] = {"sql": query, "time": stats(samples[key]), "handler_delta": delta,
                       "explain": mysql("EXPLAIN " + query),
                       "explain_analyze": mysql("EXPLAIN ANALYZE " + query), "result": rows}
    return result


def writes(tables, product):
    samples = {t: {} for t in tables}
    # Fifty different existing products; each round restores its data with committed DML.
    for round_no in range(60):
        i = 1001 + round_no * 100
        for table in (tables if round_no % 2 == 0 else tables[::-1]):
            if product:
                statements = [
                    ("insert", f"INSERT INTO {table} (id,created_at,updated_at,brand_id,name,price,quantity) "
                     f"VALUES (200001,'2026-01-01','2026-01-01',7,'W4 write probe',6000,10)"),
                    ("delete_cleanup", f"DELETE FROM {table} WHERE id=200001"),
                    ("price_update", f"UPDATE {table} SET price=price+100 WHERE id={i}"),
                    ("price_restore", f"UPDATE {table} SET price=price-100 WHERE id={i}"),
                    ("soft_delete", f"UPDATE {table} SET deleted_at='2026-09-01' WHERE id={i}"),
                    ("delete_restore", f"UPDATE {table} SET deleted_at="
                     + ("'2026-09-01'" if seed.deleted(i) else "NULL") + f" WHERE id={i}")]
                # Ensure each soft-delete is a real change, including naturally deleted rows.
                if seed.deleted(i):
                    continue
            else:
                # user 201 is absent in the normal seed; no FK in CREATE TABLE LIKE copies.
                statements = [("insert", f"INSERT INTO {table} "
                               "(id,user_id,product_id,created_at,updated_at) "
                               f"VALUES (900001,201,{i},'2026-01-01','2026-01-01')"),
                              ("delete", f"DELETE FROM {table} WHERE user_id=201 AND product_id={i}")]
            sql = []
            for _, statement in statements:
                sql.extend(["START TRANSACTION", statement, "COMMIT"])
            _, times = profile(mysql, sql)
            if round_no >= 10:
                for index, (name, _) in enumerate(statements):
                    samples[table].setdefault(name, []).append(sum(times[index * 3:index * 3 + 3]))
    return {table: {name: stats(values) for name, values in metrics.items()}
            for table, metrics in samples.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output exists; preserve previous evidence")
    tables = ("w4_100k_product_control", "w4_100k_product_candidate",
              "w4_100k_like_control", "w4_100k_like_candidate")
    assert not mysql("SHOW TABLES LIKE 'w4_100k_%';"), "Experiment tables already exist"
    before = {db: snapshot(db) for db in (DB, HOT)}
    assert before[DB]["product"].split("\t")[0] == "100000"
    assert before[DB]["product_like"].split("\t")[0] == "628186"
    assert before[HOT]["product_like"].split("\t")[0] == "678186"
    result = {"recorded_at": datetime.now().astimezone().isoformat(), "source_before": before,
              "environment": mysql("SELECT VERSION(),DATABASE(),@@transaction_isolation,"
                                   "@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@log_bin;"),
              "notes": ["Read: alternating order, 10 warmup and 30 samples; single client, warm data.",
                        "Write: committed transactions, 10 warmup and up to 50 samples; rotating product IDs.",
                        "Write transaction time sums START TRANSACTION, DML and COMMIT server profiles.",
                        "No concurrent load; excludes Docker startup/network; no production throughput claim.",
                        "Only owned copies modified; baseline removes only the candidate index.",
                        "Like write uses synthetic user 201 in FK-free copies; measures SQL, not API validation."]}
    created = []
    try:
        for table in tables:
            source = "product_like" if "like" in table else "product"
            mysql(f"CREATE TABLE {table} LIKE {source};")
            created.append(table)
            mysql(f"INSERT INTO {table} SELECT * FROM {source};")
            if table.endswith("control"):
                index = "idx_product_like_product" if source == "product_like" else "idx_product_brand_price"
                mysql(f"ALTER TABLE {table} DROP INDEX {index};")
            mysql(f"ANALYZE TABLE {table};")
        page = "SELECT id,brand_id,name,price,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') FROM {} WHERE brand_id=7 AND deleted_at IS NULL ORDER BY price ASC,created_at DESC,id DESC LIMIT 20"
        likes = "SELECT product_id,COUNT(id) FROM {} WHERE product_id IN (" + ",".join(map(str, expected_ids())) + ") GROUP BY product_id ORDER BY product_id"
        result["product_read"] = compare({t: page.format(t) for t in tables[:2]}, {t: expected_page() for t in tables[:2]})
        result["like_read"] = compare({t: likes.format(t) for t in tables[2:]}, {t: expected_likes() for t in tables[2:]})
        result["hotspot_same_page"] = compare({db: likes.format(db + ".product_like") for db in (DB, HOT)},
                                               {DB: expected_likes(), HOT: expected_likes(True)})
        result["product_write"] = writes(tables[:2], True)
        result["like_write"] = writes(tables[2:], False)
        result["copies"] = {}
        for table in tables:
            source = "product_like" if "like" in table else "product"
            cols = [line.split("\t")[0] for line in mysql(f"SHOW COLUMNS FROM {source};").splitlines()]
            equality = " AND ".join(f"c.`{c}` <=> s.`{c}`" for c in cols)
            assert mysql(f"SELECT COUNT(*) FROM {table} c LEFT JOIN {source} s ON c.id=s.id WHERE NOT ({equality});") == "0"
            assert mysql(f"SELECT COUNT(*) FROM {table}") == mysql(f"SELECT COUNT(*) FROM {source}")
            mysql(f"ANALYZE TABLE {table};")
            result["copies"][table] = {"restored_rows": True, "indexes": mysql(f"SHOW INDEX FROM {table}"),
                "allocated_bytes": mysql("SELECT data_length,index_length FROM information_schema.tables "
                                         f"WHERE table_schema=DATABASE() AND table_name='{table}'")}
    finally:
        for table in reversed(created):
            mysql(f"DROP TABLE {table};")
    result["source_after"] = {db: snapshot(db) for db in (DB, HOT)}
    assert result["source_after"] == before, "Source rows or indexes changed"
    result["source_unchanged"] = True
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("Saved", args.output, flush=True)
    for group in ("product_read", "like_read", "hotspot_same_page"):
        print(group, {k: v["time"]["median_ms"] for k, v in result[group].items()}, flush=True)


if __name__ == "__main__":
    main()

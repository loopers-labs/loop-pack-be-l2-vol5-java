#!/usr/bin/env python3
"""Revisit only the initial SQL brand-filter change, without modifying the DB."""
import argparse
from datetime import datetime
import importlib.util
import json
from pathlib import Path
import statistics
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def counters(text):
    return {key: int(value) for key, value in (line.split('\t') for line in text.splitlines())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--stage', choices=('brand', 'sort'), default='brand')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('choose a new output path')
    probe = module('probe', 'measure-week4-query.py')
    seed = module('seed', 'seed-week4.py')
    mysql = probe.mysql
    columns = mysql('SHOW COLUMNS FROM product;').splitlines()
    names = [line.split('\t')[0] for line in columns]
    id_pos, brand_pos = names.index('id'), names.index('brand_id')
    # Exclude the price index only if it exists; reset DBs use their original indexes.
    price_index_exists = mysql("SELECT COUNT(*) FROM information_schema.statistics "
                              "WHERE table_schema=DATABASE() AND table_name='product' "
                              "AND index_name='idx_product_brand_price';") != '0'
    hint = ' IGNORE INDEX (idx_product_brand_price)' if price_index_exists else ''
    queries = {
        'all_active': 'SELECT p.* FROM product p' + hint + ' WHERE p.deleted_at IS NULL',
        'brand_7': 'SELECT p.* FROM product p' + hint + ' WHERE p.deleted_at IS NULL AND p.brand_id=7',
    }
    expected_all = {i for i in range(1, 10001) if not seed.deleted(i)}
    expected_brand = {i for i in expected_all if seed.brand_id(i) == 7}
    expected_order = sorted(expected_brand, key=lambda i: (seed.price(i), -(i % 30), -i))
    if args.stage == 'sort':
        base = queries['brand_7']
        queries = {'brand_7': base,
                   'brand_7_sorted': base + ' ORDER BY p.price ASC,p.created_at DESC,p.id DESC'}
    result = {'recorded_at': datetime.now().astimezone().isoformat(),
        'database': mysql('SELECT VERSION(),DATABASE(),@@transaction_isolation;'),
        'indexes_before': mysql('SHOW INDEX FROM product;'),
        'stage': args.stage,
        'price_index_excluded': price_index_exists,
        'notes': ['Product candidates only; no LIMIT, likes or active-brand filtering; sort stage adds only ORDER BY.',
                  'Price index excluded only when present; current statistics are not historical statistics.',
                  'Handler snapshots surround an ordinary SELECT on the same session; EXPLAIN ANALYZE runs separately.',
                  'Server SELECT durations only, exclude row transfer/client and Java filtering.',
                  '10 warmup + 30 measurements, alternate first strategy; each round uses one read-only transaction.',
                  'Handler requests are not physical page reads or Root/Branch/Leaf visit counts.'], 'cases': {}}
    reference = {}
    for key, query in queries.items():
        raw = mysql("START TRANSACTION READ ONLY; SELECT '#BEFORE'; SHOW SESSION STATUS LIKE 'Handler_read%'; "
                    "SELECT '#ROWS'; " + query + "; SELECT '#AFTER'; SHOW SESSION STATUS LIKE 'Handler_read%'; COMMIT;")
        before, rest = raw.split('#ROWS\n', 1)
        rows, after = rest.split('#AFTER\n', 1)
        before = counters(before.removeprefix('#BEFORE\n').strip())
        after = counters(after.strip())
        reference[key] = rows.strip().splitlines()
        ids = {int(line.split('\t')[id_pos]) for line in reference[key]}
        assert ids == (expected_all if key == 'all_active' else expected_brand)
        assert len(ids) == len(reference[key])
        if key == 'brand_7_sorted':
            assert [int(line.split('\t')[id_pos]) for line in reference[key]] == expected_order
        result['cases'][key] = {'sql': query, 'returned_rows': len(ids),
            'handler_before': before, 'handler_after': after,
            'handler_delta': {name: after[name] - value for name, value in before.items()},
            'explain': mysql('EXPLAIN ' + query),
            'explain_analyze': mysql('EXPLAIN ANALYZE ' + query), 'all_sql_ms': []}
    source = reference['all_active'] if args.stage == 'brand' else reference['brand_7_sorted']
    filtered = sorted(line for line in source if int(line.split('\t')[brand_pos]) == 7)
    assert filtered == sorted(reference['brand_7'])
    for round_no in range(40):
        order = list(queries) if round_no % 2 == 0 else list(queries)[::-1]
        raw = mysql('SET SESSION profiling_history_size=100; SET profiling=1; START TRANSACTION READ ONLY; '
                    + "; SELECT '#NEXT'; ".join(queries[k] for k in order)
                    + "; SELECT '#PROFILES'; SHOW PROFILES; COMMIT;")
        rows, profiles = raw.split('#PROFILES\n', 1)
        parts = rows.strip().split('\n#NEXT\n')
        for key, part in zip(order, parts):
            assert sorted(part.splitlines()) == sorted(reference[key])
            if key == 'brand_7_sorted':
                assert [int(line.split('\t')[id_pos]) for line in part.splitlines()] == expected_order
        times = []
        for line in profiles.splitlines():
            _, seconds, sql = line.split('\t', 2)
            if sql.startswith('SELECT p.*'):
                times.append(float(seconds) * 1000)
        assert len(times) == 2
        if round_no >= 10:
            for key, value in zip(order, times):
                result['cases'][key]['all_sql_ms'].append(value)
    for case in result['cases'].values():
        case['median_ms'] = statistics.median(case['all_sql_ms'])
        case['mean_ms'] = statistics.mean(case['all_sql_ms'])
    result['indexes_after'] = mysql('SHOW INDEX FROM product;')
    assert result['indexes_before'] == result['indexes_after']
    result['brand_filtered_fields_equal'] = True
    result['validated_executions'] = 80
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    for key, case in result['cases'].items():
        print(key, case['returned_rows'], case['median_ms'], case['handler_delta'])
        print(case['explain_analyze'])


if __name__ == '__main__':
    main()

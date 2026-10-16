#!/usr/bin/env python3
"""Compare price-only and full tie-order indexes using the same page query."""
from datetime import datetime
import argparse
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--without-id', action='store_true')
    modes.add_argument('--price-first', action='store_true')
    args = parser.parse_args()
    out = ROOT / ('docs/week4/measurements/price-without-id-index.json' if args.without_id
                  else 'docs/week4/measurements/price-tie-index.json')
    if args.price_first:
        out = ROOT / 'docs/week4/measurements/price-first-index.json'
    if out.exists():
        raise RuntimeError('Refusing to overwrite measurement')
    experiment = load('experiment', 'compare-week4-like-index.py')
    mysql = experiment.load_probe().mysql
    seed = load('seed', 'seed-week4.py')
    candidate = 'idx_product_brand_price_time' if args.without_id else 'idx_product_brand_price_only'
    candidate_key = 'without_id' if args.without_id else 'price_only'
    candidate_columns = 'brand_id,deleted_at,price,created_at DESC' if args.without_id else 'brand_id,deleted_at,price'
    if args.price_first:
        candidate = 'idx_product_price_first'
        candidate_key = 'price_first'
        candidate_columns = 'price ASC,brand_id,deleted_at,created_at DESC,id DESC'
    full = 'idx_product_brand_price'
    assert mysql('SELECT DATABASE();') == 'loopers_benchmark'
    assert mysql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                 f"AND table_name='product' AND index_name='{candidate}';") == '0'
    indexes_before = mysql('SHOW INDEX FROM product;')
    tail = (' WHERE brand_id=7 AND deleted_at IS NULL '
            'ORDER BY price ASC,created_at DESC,id DESC LIMIT 20')
    plain = 'SELECT id,brand_id,name,price,created_at FROM product' + tail
    wanted = sorted((i for i in range(1,10001) if seed.brand_id(i)==7 and not seed.deleted(i)),
                    key=lambda i: (seed.price(i), -(i % 30), -i))[:20]

    def validate(rows):
        assert len(rows)==20
        for line, i in zip(rows, wanted):
            values = line.split('\t')
            assert values[:4] == [str(i),'7',f'W4 Product {i:05d}',str(seed.price(i))]
            assert datetime.fromisoformat(values[4]) == datetime.fromisoformat(seed.timestamp(i).strip("'"))

    created = False
    result = {'database': mysql('SELECT VERSION(),DATABASE(),@@transaction_isolation;'),
        'indexes_before': indexes_before, 'fixed_query': plain, 'cases': {},
        'notes': ['Only experimental price-only index is created and removed in finally.',
                  'FORCE INDEX isolates each candidate; no-hint optimizer choice is recorded separately.',
                  'Same SELECT columns, predicates, ordering and LIMIT; 10 warmup + 30 samples each.',
                  'Execution order alternates; single profiled session; server SQL duration only.',
                  'Per-index size uses sampled InnoDB index page statistics after ANALYZE, not exact live bytes.',
                  'No write benchmark, API benchmark, or physical Leaf inspection.']}
    try:
        mysql(f'CREATE INDEX {candidate} ON product ({candidate_columns});')
        created = True
        mysql('ANALYZE TABLE product;')
        queries = {key: 'SELECT id,brand_id,name,price,created_at FROM product FORCE INDEX (' + index + ')' + tail
                   for key, index in [(candidate_key,candidate),('full_order',full)]}
        for key, q in queries.items():
            raw = mysql("START TRANSACTION READ ONLY; SELECT '#BEFORE'; SHOW SESSION STATUS LIKE 'Handler_read%'; "
                        "SELECT '#ROWS'; " + q + "; SELECT '#AFTER'; SHOW SESSION STATUS LIKE 'Handler_read%'; COMMIT;")
            before, rest = raw.split('#ROWS\n',1)
            rows, after = rest.split('#AFTER\n',1)
            validate(rows.strip().splitlines())
            def counters(value):
                return {k:int(v) for k,v in (line.split('\t') for line in value.strip().splitlines())}
            before=counters(before.removeprefix('#BEFORE\n'));after=counters(after)
            result['cases'][key]={'sql':q,'result_rows':rows.strip().splitlines(),
                'handler_delta':{k:after[k]-v for k,v in before.items()},
                'explain':mysql('EXPLAIN '+q),'explain_analyze':mysql('EXPLAIN ANALYZE '+q),'all_sql_ms':[]}
        sequence=[]
        for i in range(40):
            sequence.extend([candidate_key,'full_order'] if i%2==0 else ['full_order',candidate_key])
        rows, times=experiment.profile(mysql,[queries[key] for key in sequence])
        lines=rows.splitlines();assert len(lines)==1600
        for i,key in enumerate(sequence):
            validate(lines[i*20:(i+1)*20])
            if i//2>=10:result['cases'][key]['all_sql_ms'].append(times[i])
        for key,index in [(candidate_key,candidate),('full_order',full)]:
            case=result['cases'][key]
            case['timing']=experiment.stats(case['all_sql_ms'])
            case['index_size_pages']=int(mysql("SELECT stat_value FROM mysql.innodb_index_stats "
                f"WHERE database_name=DATABASE() AND table_name='product' AND index_name='{index}' AND stat_name='size';"))
            case['index_size_bytes']=case['index_size_pages']*int(mysql('SELECT @@innodb_page_size;'))
        result['optimizer_choice_with_both']=mysql('EXPLAIN '+plain)
        result['plain_result_rows']=mysql(plain).splitlines();validate(result['plain_result_rows'])
        assert result['cases'][candidate_key]['result_rows']==result['cases']['full_order']['result_rows']
        if args.without_id:
            key_query = ('SELECT brand_id,deleted_at,price,created_at,id FROM product FORCE INDEX ('
                         + candidate + ') WHERE brand_id=7 AND deleted_at IS NULL '
                         'ORDER BY price ASC,created_at DESC,id ASC LIMIT 6')
            result['implicit_primary_key_order_example'] = {
                'sql':key_query,'rows':mysql(key_query),'explain':mysql('EXPLAIN '+key_query)}
        result['candidate_columns'] = candidate_columns
        result['validated_profiled_executions']=80
    finally:
        if created:mysql(f'DROP INDEX {candidate} ON product;')
    result['indexes_after']=mysql('SHOW INDEX FROM product;')
    # Cardinality statistics may change, so compare index identity/column/direction instead.
    def identity(text):
        return sorted(tuple(line.split('\t')[i] for i in (2,3,4,5)) for line in text.splitlines())
    assert identity(indexes_before)==identity(result['indexes_after'])
    result['temporary_index_removed']=True
    out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    for key,case in result['cases'].items():
        print(key,case['timing'],case['handler_delta'],case['index_size_bytes']);print(case['explain_analyze'])


if __name__=='__main__':main()

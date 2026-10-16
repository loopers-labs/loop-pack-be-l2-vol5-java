#!/usr/bin/env python3
"""Compare name-included and covering projections on the same existing index."""
from datetime import datetime
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    path = ROOT / 'docs/week4/measurements/covering-name.json'
    if path.exists():
        raise RuntimeError('Refusing to overwrite measurement')
    experiment = load('experiment', 'compare-week4-like-index.py')
    seed = load('seed', 'seed-week4.py')
    mysql = experiment.load_probe().mysql
    assert mysql('SELECT DATABASE();') == 'loopers_benchmark'
    tail = (' FROM product WHERE brand_id=7 AND deleted_at IS NULL '
            'ORDER BY price ASC,created_at DESC,id DESC LIMIT 20')
    queries = {'with_name': 'SELECT id,brand_id,name,price,created_at' + tail,
               'without_name': 'SELECT id,brand_id,price,created_at' + tail}
    wanted = sorted((i for i in range(1,10001) if seed.brand_id(i)==7 and not seed.deleted(i)),
                    key=lambda i: (seed.price(i), -(i % 30), -i))[:20]

    def validate(key, lines):
        assert len(lines) == 20
        common = []
        for line, i in zip(lines, wanted):
            cols = line.split('\t')
            if key == 'with_name':
                assert cols[2] == f'W4 Product {i:05d}'
                cols = cols[:2] + cols[3:]
            assert cols[:3] == [str(i), '7', str(seed.price(i))]
            assert datetime.fromisoformat(cols[3]) == datetime.fromisoformat(seed.timestamp(i).strip("'"))
            common.append(cols)
        return common

    result = {'database': mysql('SELECT VERSION(),DATABASE(),@@transaction_isolation;'),
              'indexes_before': mysql('SHOW INDEX FROM product;'), 'cases': {},
              'notes': ['No DDL, data or ANALYZE TABLE changes; existing full-order index retained.',
                        'Only SELECT projection differs; WHERE/ORDER BY/LIMIT identical; no hints.',
                        'Results differ in name column intentionally; all common fields and ordering verified.',
                        '10 warmup + 30 samples each, alternating order; server SQL durations only.',
                        'Handler measured separately around ordinary SELECT on same connection, no extension COUNT.',
                        'Covering does not guarantee zero clustered reads under every MVCC state; physical pages not measured.']}
    common = []
    for key, query in queries.items():
        raw = mysql("START TRANSACTION READ ONLY; SELECT '#BEFORE'; SHOW SESSION STATUS LIKE 'Handler_read%'; "
                    "SELECT '#ROWS'; " + query + "; SELECT '#AFTER'; SHOW SESSION STATUS LIKE 'Handler_read%'; COMMIT;")
        before, rest = raw.split('#ROWS\n', 1)
        rows, after = rest.split('#AFTER\n', 1)
        common.append(validate(key, rows.strip().splitlines()))
        def counters(s):
            return {k:int(v) for k,v in (line.split('\t') for line in s.strip().splitlines())}
        before=counters(before.removeprefix('#BEFORE\n'));after=counters(after)
        result['cases'][key] = {'sql':query, 'rows':rows.strip().splitlines(),
            'handler_delta':{k:after[k]-v for k,v in before.items()},
            'explain':mysql('EXPLAIN '+query), 'explain_analyze':mysql('EXPLAIN ANALYZE '+query),
            'all_sql_ms':[]}
    assert common[0] == common[1]
    sequence=[]
    for i in range(40):
        sequence.extend(['with_name','without_name'] if i%2==0 else ['without_name','with_name'])
    rows,times=experiment.profile(mysql,[queries[k] for k in sequence])
    lines=rows.splitlines();assert len(lines)==1600
    for i,key in enumerate(sequence):
        validate(key,lines[i*20:(i+1)*20])
        if i//2>=10:result['cases'][key]['all_sql_ms'].append(times[i])
    for case in result['cases'].values():case['timing']=experiment.stats(case['all_sql_ms'])
    result['validated_executions']=82
    result['indexes_after']=mysql('SHOW INDEX FROM product;')
    assert result['indexes_before']==result['indexes_after']
    path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    for key,case in result['cases'].items():
        print(key,case['timing'],case['handler_delta']);print(case['explain']);print(case['explain_analyze'])


if __name__=='__main__':main()

#!/usr/bin/env python3
"""Measure a fixed product page query with plans and session Handler deltas."""
import argparse
from datetime import datetime
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('choose a new output path')
    experiment = load('experiment', 'compare-week4-like-index.py')
    probe = experiment.load_probe()
    seed = load('seed', 'seed-week4.py')
    mysql = probe.mysql
    query = ('SELECT id,brand_id,name,price,created_at FROM product '
             'WHERE brand_id=7 AND deleted_at IS NULL '
             'ORDER BY price ASC,created_at DESC,id DESC LIMIT 20')
    wanted = sorted((i for i in range(1,10001) if seed.brand_id(i)==7 and not seed.deleted(i)),
                    key=lambda i: (seed.price(i), -(i % 30), -i))[:20]
    def validate(rows):
        lines = rows.splitlines()
        assert len(lines)==20
        for line, i in zip(lines, wanted):
            fields=line.split('\t')
            assert fields[:4] == [str(i),'7',f'W4 Product {i:05d}',str(seed.price(i))]
            assert datetime.fromisoformat(fields[4]) == datetime.fromisoformat(seed.timestamp(i).strip("'"))
    indexes_before = mysql('SHOW INDEX FROM product; SHOW INDEX FROM product_like;')
    raw=mysql("START TRANSACTION READ ONLY; SELECT '#BEFORE'; SHOW SESSION STATUS LIKE 'Handler_read%'; "
              "SELECT '#ROWS'; "+query+"; SELECT '#AFTER'; SHOW SESSION STATUS LIKE 'Handler_read%'; COMMIT;")
    before, rest=raw.split('#ROWS\n',1)
    rows, after=rest.split('#AFTER\n',1)
    validate(rows.strip())
    def counters(value):
        return {k:int(v) for k,v in (line.split('\t') for line in value.strip().splitlines())}
    before=counters(before.removeprefix('#BEFORE\n'));after=counters(after)
    measured, times=experiment.profile(mysql,[query]*40)
    lines=measured.splitlines()
    assert len(lines)==800
    for offset in range(0,800,20):validate('\n'.join(lines[offset:offset+20]))
    result={'recorded_at':datetime.now().astimezone().isoformat(),'sql':query,
        'database':mysql('SELECT VERSION(),DATABASE(),@@transaction_isolation;'),
        'indexes_before':indexes_before,'result_rows':rows.strip().splitlines(),
        'handler_before':before,'handler_after':after,
        'handler_delta':{k:after[k]-v for k,v in before.items()},
        'explain':mysql('EXPLAIN '+query),'explain_analyze':mysql('EXPLAIN ANALYZE '+query),
        'brand_index_order_example':mysql('SELECT brand_id,deleted_at,id,price FROM product '
            'FORCE INDEX (idx_product_brand_deleted) WHERE brand_id=7 AND deleted_at IS NULL ORDER BY id LIMIT 10'),
        'timing':experiment.stats(times[10:]),'all_sql_ms':times[10:],
        'validated_executions':41,'notes':['Fixed query; no schema/data/statistics change.',
        'FORCE INDEX is only for the illustrative index-key-order query; measurement query has no hint.',
        'Handler around a separate ordinary SELECT on the same session; ANALYZE runs separately.',
        '10 warmup + 30 server SQL duration samples; excludes API/network/client timings.',
        'Key-order example does not inspect physical pages or determine tree height.']}
    result['indexes_after']=mysql('SHOW INDEX FROM product; SHOW INDEX FROM product_like;')
    assert result['indexes_after']==indexes_before
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(result['explain']);print(result['explain_analyze'])
    print(result['handler_delta']);print(result['timing']);print(result['brand_index_order_example'])


if __name__=='__main__':main()

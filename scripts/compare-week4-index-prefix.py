#!/usr/bin/env python3
"""Compare the overlapping brand prefix on real read shapes; no DDL."""
from datetime import datetime
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]


def load(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


def main():
    path=ROOT/'docs/week4/measurements/index-prefix.json'
    if path.exists():raise RuntimeError('Refusing overwrite')
    e=load('experiment','compare-week4-like-index.py');mysql=e.load_probe().mysql
    seed=load('seed','seed-week4.py')
    expected=sorted(i for i in range(1,10001) if seed.brand_id(i)==7 and not seed.deleted(i))
    indexes={'short':'idx_product_brand_deleted','long':'idx_product_brand_price'}
    shapes={
        'brand_products':('SELECT p.* FROM product p',' WHERE p.brand_id=7 AND p.deleted_at IS NULL'),
        'visible_count':('SELECT COUNT(*) FROM product p',' WHERE p.deleted_at IS NULL AND p.brand_id=7 AND EXISTS (SELECT 1 FROM brand b WHERE b.id=p.brand_id AND b.deleted_at IS NULL)'),
        'deletion_ids':('SELECT p.id FROM product p',' WHERE p.brand_id=7 AND p.deleted_at IS NULL ORDER BY p.id')}
    before=mysql('SHOW INDEX FROM product;')
    names=[line.split('\t')[0] for line in mysql('SHOW COLUMNS FROM product;').splitlines()]
    r={'database':mysql('SELECT VERSION(),DATABASE(),@@transaction_isolation;'),
       'indexes_before':before,'cases':{},'notes':['Read-only SQL experiment; no schema/data/statistics changes.',
       'FORCE INDEX isolates candidates; no-hint plans also recorded.',
       '10 warmup + 30 samples each per shape, alternating execution order.',
       'Server durations only; no API, write or concurrent deletion benchmark.']}
    for shape,(head,tail) in shapes.items():
        plain=head+tail
        queries={key:head+' FORCE INDEX ('+index+')'+tail for key,index in indexes.items()}
        cases={}
        def validate(lines):
            if shape=='visible_count':assert lines==[str(len(expected))]
            else:
                pos=names.index('id') if shape=='brand_products' else 0
                ids=[int(line.split('\t')[pos]) for line in lines]
                assert sorted(ids)==expected
                if shape=='deletion_ids':assert ids==expected
        reference=None
        for key,q in queries.items():
            raw=mysql("START TRANSACTION READ ONLY; SELECT '#BEFORE'; SHOW SESSION STATUS LIKE 'Handler_read%'; SELECT '#ROWS'; "+q+"; SELECT '#AFTER'; SHOW SESSION STATUS LIKE 'Handler_read%'; COMMIT;")
            a,rest=raw.split('#ROWS\n',1);rows,b=rest.split('#AFTER\n',1)
            lines=rows.strip().splitlines();validate(lines)
            if reference is None:reference=sorted(lines)
            assert sorted(lines)==reference
            def c(x):return {k:int(v) for k,v in (l.split('\t') for l in x.strip().splitlines())}
            a=c(a.removeprefix('#BEFORE\n'));b=c(b)
            cases[key]={'sql':q,'returned_rows':len(lines),'handler_delta':{k:b[k]-v for k,v in a.items()},
                'explain':mysql('EXPLAIN '+q),'explain_analyze':mysql('EXPLAIN ANALYZE '+q),'all_ms':[]}
        seq=[]
        for i in range(40):seq.extend(['short','long'] if i%2==0 else ['long','short'])
        rows,times=e.profile(mysql,[queries[k] for k in seq]);lines=rows.splitlines()
        n=1 if shape=='visible_count' else len(expected);assert len(lines)==80*n
        for i,key in enumerate(seq):
            page=lines[i*n:(i+1)*n];validate(page);assert sorted(page)==reference
            if i//2>=10:cases[key]['all_ms'].append(times[i])
        for case in cases.values():case['timing']=e.stats(case['all_ms'])
        r['cases'][shape]={'paths':cases,'automatic_explain':mysql('EXPLAIN '+plain)}
    r['index_size_pages']={key:int(mysql("SELECT stat_value FROM mysql.innodb_index_stats WHERE database_name=DATABASE() AND table_name='product' AND index_name='"+index+"' AND stat_name='size';")) for key,index in indexes.items()}
    r['indexes_after']=mysql('SHOW INDEX FROM product;');assert r['indexes_after']==before
    r['validated_profiled_executions']=240
    path.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
    for shape,cases in r['cases'].items():
        for key,case in cases['paths'].items():print(shape,key,case['timing'],case['handler_delta']);print(case['explain'])


if __name__=='__main__':main()

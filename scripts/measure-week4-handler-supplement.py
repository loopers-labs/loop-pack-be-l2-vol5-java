#!/usr/bin/env python3
"""Fill missing Handler evidence using captured SQL, without DDL or data writes."""
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]


def main():
    out=ROOT/'docs/week4/measurements/handler-supplement.json'
    if out.exists():raise RuntimeError('Refusing overwrite')
    spec=importlib.util.spec_from_file_location('probe',ROOT/'scripts/measure-week4-query.py')
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);mysql=m.mysql
    results=json.loads((ROOT/'docs/week4/measurements/index-results.json').read_text())['results']
    source=results['join-api-final.json']
    ins=results['index-conditions.json']
    queries={
        'in_forced_full':[ins['in_condition']['forced_full_index']['sql']],
        'split_page_and_likes':[source['split_sql_probe']['queries'][i]['sql'] for i in (2,4)],
        'join_page_brand_likes':[source['join_sql_probe']['queries'][2]['sql']],
        'split_api_business_selects':[q['sql'] for q in source['split_sql_probe']['queries']],
        'join_api_business_selects':[q['sql'] for q in source['join_sql_probe']['queries']],
    }
    before=mysql('SHOW INDEX FROM product; SHOW INDEX FROM product_like;')
    fingerprint='SELECT COUNT(*),SUM(id),SUM(price),SUM(deleted_at IS NULL) FROM product; SELECT COUNT(*),SUM(id),SUM(product_id),SUM(user_id) FROM product_like;'
    data_before=mysql(fingerprint)
    r={'database':mysql('SELECT VERSION(),DATABASE(),@@transaction_isolation;'),
       'indexes_before':before,'source_before':data_before,'cases':{},
       'notes':['Only SELECTs; no schema/data/statistics changes. Same connection before/after each block.',
                '3 independent connections/repetitions per block; EXPLAIN ANALYZE outside Handler measurement.',
                'API blocks replay actual captured business SELECTs in a read-only transaction; not live HTTP Handler capture.',
                'No VS Code auto COUNT. Counters include temporary-table and constant-table reads.',
                'No physical I/O/tree-page/clustered-row-relookup measurements.']}
    for key,sqls in queries.items():
        samples=[];reference=None
        for _ in range(3):
            raw=mysql("START TRANSACTION READ ONLY; SELECT '#BEFORE'; SHOW SESSION STATUS LIKE 'Handler_read%'; SELECT '#ROWS'; "+';'.join(sqls)+"; SELECT '#AFTER'; SHOW SESSION STATUS LIKE 'Handler_read%'; COMMIT;")
            a,rest=raw.split('#ROWS\n',1);rows,b=rest.split('#AFTER\n',1)
            def counters(text):return {k:int(v) for k,v in (line.split('\t') for line in text.strip().splitlines())}
            a=counters(a.removeprefix('#BEFORE\n'));b=counters(b)
            rows=rows.strip()
            if reference is None:reference=rows
            assert reference==rows
            if key=='in_forced_full':assert rows.splitlines()==ins['in_condition']['two_brands']['rows']
            if key=='join_page_brand_likes':
                lines=rows.splitlines();items=source['fixed_response']['items'];assert len(lines)==len(items)
                for line,item in zip(lines,items):
                    v=line.split('\t')
                    assert [int(v[0]),v[1],int(v[2]),int(v[4]),v[5],int(v[7])] == [item['id'],item['name'],item['price'],item['brand']['id'],item['brand']['name'],item['likeCount']]
            samples.append({'before':a,'after':b,'delta':{k:b[k]-v for k,v in a.items()}})
        r['cases'][key]={'sqls':sqls,'handler_samples':samples,'all_deltas_equal':all(s['delta']==samples[0]['delta'] for s in samples),
            'row_output':reference,'explain_analyze':[mysql('EXPLAIN ANALYZE '+q) for q in sqls]}
        print(key,samples[0]['delta'],r['cases'][key]['all_deltas_equal'])
    r['indexes_after']=mysql('SHOW INDEX FROM product; SHOW INDEX FROM product_like;');assert r['indexes_after']==before
    r['source_after']=mysql(fingerprint);assert r['source_after']==data_before
    r['validated_blocks']=15
    out.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__':main()

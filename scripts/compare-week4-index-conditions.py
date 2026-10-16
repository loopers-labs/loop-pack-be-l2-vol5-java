#!/usr/bin/env python3
"""IN, soft-delete filtering, and offset/cursor on project data."""
from datetime import datetime
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
LAB='w4_product_deleted_lab'


def load(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


def main():
    out=ROOT/'docs/week4/measurements/index-conditions.json'
    if out.exists():raise RuntimeError('Refusing overwrite')
    e=load('e','compare-week4-like-index.py');mysql=e.load_probe().mysql
    seed=load('seed','seed-week4.py')
    order=' ORDER BY price ASC,created_at DESC,id DESC LIMIT 20'
    fields='SELECT id,brand_id,name,price,created_at FROM '
    def ids(brands):
        return sorted((i for i in range(1,10001) if seed.brand_id(i) in brands and not seed.deleted(i)),
                      key=lambda i:(seed.price(i),-(i%30),-i))
    def validate(lines,wanted):
        assert len(lines)==len(wanted)
        for line,i in zip(lines,wanted):
            v=line.split('\t');assert v[:4]==[str(i),str(seed.brand_id(i)),f'W4 Product {i:05d}',str(seed.price(i))]
            assert datetime.fromisoformat(v[4])==datetime.fromisoformat(seed.timestamp(i).strip("'"))
    def observe(query,wanted):
        raw=mysql("START TRANSACTION READ ONLY; SELECT '#BEFORE'; SHOW SESSION STATUS LIKE 'Handler_read%'; SELECT '#ROWS'; "+query+"; SELECT '#AFTER'; SHOW SESSION STATUS LIKE 'Handler_read%'; COMMIT;")
        a,rest=raw.split('#ROWS\n',1);rows,b=rest.split('#AFTER\n',1)
        validate(rows.strip().splitlines(),wanted)
        def c(s):return {k:int(v) for k,v in (l.split('\t') for l in s.strip().splitlines())}
        a=c(a.removeprefix('#BEFORE\n'));b=c(b)
        return {'sql':query,'expected_ids':wanted,'rows':rows.strip().splitlines(),
                'handler_delta':{k:b[k]-v for k,v in a.items()},'explain':mysql('EXPLAIN '+query),
                'explain_analyze':mysql('EXPLAIN ANALYZE '+query),'all_ms':[]}
    def pair(cases):
        keys=list(cases);sequence=[]
        for i in range(40):sequence.extend(keys if i%2==0 else keys[::-1])
        rows,times=e.profile(mysql,[cases[k]['sql'] for k in sequence]);lines=rows.splitlines()
        n=20;assert len(lines)==len(sequence)*n
        for i,key in enumerate(sequence):
            validate(lines[i*n:(i+1)*n],cases[key]['expected_ids'])
            if i//2>=10:cases[key]['all_ms'].append(times[i])
        for case in cases.values():case['timing']=e.stats(case['all_ms'])
    before=mysql('SHOW INDEX FROM product; SHOW INDEX FROM product_like;')
    fingerprint_sql='SELECT COUNT(*),SUM(id),SUM(price),SUM(deleted_at IS NULL) FROM product;'
    fingerprint=mysql(fingerprint_sql)
    r={'database':mysql('SELECT VERSION(),DATABASE(),@@transaction_isolation;'),
       'indexes_before':before,'source_fingerprint_before':fingerprint,
       'notes':['Project brand 7 has 99 active products; PDF data has 1000, so use offset 60 here.',
                'IN changes the result contract deliberately; each case verified against independent seed.',
                'Deletion-heavy changes only disposable cloned table, actual products preserved.',
                '10 warmup + 30 samples each, alternating order; SQL server time only.',
                'Handler around separate ordinary SELECT on same session; no extension COUNT.',
                'Cursor anchor prepared outside timing, assumes previous page available; not random page access.']}
    r['in_condition']={
        'one_brand':observe(fields+'product WHERE brand_id=7 AND deleted_at IS NULL'+order,ids({7})[:20]),
        'two_brands':observe(fields+'product WHERE brand_id IN (7,8) AND deleted_at IS NULL'+order,ids({7,8})[:20])}
    pair(r['in_condition'])
    existing=mysql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='"+LAB+"';")
    assert existing=='0'
    created=False
    try:
        mysql('CREATE TABLE '+LAB+' LIKE product;');created=True
        mysql('INSERT INTO '+LAB+' SELECT * FROM product;')
        mysql('CREATE INDEX idx_lab_without_deleted ON '+LAB+' (brand_id,price ASC,created_at DESC,id DESC); ANALYZE TABLE '+LAB+';')
        def deletion_case(wanted):
            result={}
            for key,index in [('without_deleted','idx_lab_without_deleted'),('with_deleted','idx_product_brand_price')]:
                q=fields+LAB+' FORCE INDEX ('+index+') WHERE brand_id=7 AND deleted_at IS NULL'+order
                result[key]=observe(q,wanted)
            assert result['without_deleted']['rows']==result['with_deleted']['rows']
            pair(result);return result
        r['deletion_original']=deletion_case(ids({7})[:20])
        removed=ids({7})[:70]
        mysql('UPDATE '+LAB+" SET deleted_at='2026-10-14 00:00:00' WHERE id IN ("+','.join(map(str,removed))+');')
        r['deletion_heavy']={'removed_ids':removed,'remaining_active':len(ids({7}))-len(removed),
            'cases':deletion_case(ids({7})[70:90])}
    finally:
        if created:mysql('DROP TABLE '+LAB+';')
    all_ids=ids({7});offset=60;anchor=all_ids[offset-1]
    stamp=seed.timestamp(anchor);price=seed.price(anchor)
    prefix=fields+'product WHERE brand_id=7 AND deleted_at IS NULL'
    predicate=(f' AND (price>{price} OR (price={price} AND created_at<{stamp}) '
               f'OR (price={price} AND created_at={stamp} AND id<{anchor}))')
    r['pagination']={'offset':observe(prefix+order+' OFFSET '+str(offset),all_ids[offset:offset+20]),
        'cursor':observe(prefix+predicate+order,all_ids[offset:offset+20])}
    assert r['pagination']['offset']['rows']==r['pagination']['cursor']['rows']
    pair(r['pagination']);r['cursor_anchor']={'id':anchor,'price':price,'created_at':stamp.strip("'")}
    r['source_fingerprint_after']=mysql(fingerprint_sql);assert fingerprint==r['source_fingerprint_after']
    r['indexes_after']=mysql('SHOW INDEX FROM product; SHOW INDEX FROM product_like;')
    assert before==r['indexes_after'];r['cloned_table_removed']=True;r['validated_profiled_executions']=320
    out.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
    for name,cases in [('in',r['in_condition']),('delete-original',r['deletion_original']),
                       ('delete-heavy',r['deletion_heavy']['cases']),('page',r['pagination'])]:
        for key,case in cases.items():print(name,key,case['timing'],case['handler_delta']);print(case['explain_analyze'])
    print('anchor',r['cursor_anchor'])


if __name__=='__main__':main()

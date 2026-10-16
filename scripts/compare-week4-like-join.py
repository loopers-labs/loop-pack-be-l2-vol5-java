#!/usr/bin/env python3
"""Read-only comparison of page + like aggregate versus a page-first LEFT JOIN."""
import importlib.util
import json
from pathlib import Path
import statistics
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('probe', ROOT / 'scripts/measure-week4-query.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
out = ROOT / 'docs/week4/measurements/like-join.json'
if out.exists():
    raise RuntimeError('Refusing to overwrite measurement')
expected = probe.expected_response()['items']
ids = ','.join(str(x['id']) for x in expected)
page = ('SELECT p.id,p.name,p.price,p.brand_id,p.created_at FROM product p '
        'WHERE p.brand_id=7 AND p.deleted_at IS NULL AND EXISTS '
        '(SELECT 1 FROM brand b WHERE b.id=p.brand_id AND b.deleted_at IS NULL) '
        'ORDER BY p.price ASC,p.created_at DESC,p.id DESC LIMIT 20')
likes = f'SELECT product_id,COUNT(id) FROM product_like WHERE product_id IN ({ids}) GROUP BY product_id'
joined = ('SELECT pg.id,pg.name,pg.price,pg.brand_id,pg.created_at,COUNT(l.id) FROM (' + page + ') pg '
          'LEFT JOIN product_like l ON l.product_id=pg.id '
          'GROUP BY pg.id,pg.name,pg.price,pg.brand_id,pg.created_at '
          'ORDER BY pg.price ASC,pg.created_at DESC,pg.id DESC')
# Explicit result markers permit validation even when aggregate rows are absent.
sequence = []
for round_no in range(40):
    sequence.extend(['split','join'] if round_no % 2 == 0 else ['join','split'])
samples = {'split': [], 'join': []}
# At most 5 profiled statements per iteration, below the history limit.
for i, strategy in enumerate(sequence):
    queries = [page, likes] if strategy == 'split' else [joined]
    raw = probe.mysql('SET SESSION profiling_history_size=100; SET profiling=1; START TRANSACTION READ ONLY; '
                      + "; SELECT '#NEXT'; ".join(queries)
                      + "; SELECT '#PROFILES'; SHOW PROFILES; COMMIT;")
    rows, profiles = raw.split('#PROFILES\n', 1)
    parts = rows.strip().split('\n#NEXT\n')
    if strategy == 'split':
        product_rows = [r.split('\t') for r in parts[0].splitlines()]
        counts = {int(r.split('\t')[0]): int(r.split('\t')[1]) for r in parts[1].splitlines()}
    else:
        all_rows = [r.split('\t') for r in parts[0].splitlines()]
        product_rows = [r[:5] for r in all_rows]
        counts = {int(r[0]): int(r[5]) for r in all_rows}
    assert len(product_rows) == 20
    for row, item in zip(product_rows, expected):
        assert (int(row[0]),row[1],int(row[2]),int(row[3]),counts.get(int(row[0]),0)) == (
            item['id'],item['name'],item['price'],7,item['likeCount'])
    if i == 0:
        reference = product_rows
    assert product_rows == reference
    durations = []
    for line in profiles.splitlines():
        _, seconds, sql = line.split('\t', 2)
        if sql.startswith('SELECT p.id,') or sql.startswith('SELECT product_id,') or sql.startswith('SELECT pg.id,'):
            durations.append(float(seconds)*1000)
    assert len(durations) == len(queries)
    if i // 2 >= 10:
        samples[strategy].append(sum(durations))
result = {'database': probe.mysql('SELECT VERSION(),DATABASE(),@@transaction_isolation;'),
          'indexes': probe.mysql('SHOW INDEX FROM product; SHOW INDEX FROM product_like;'),
          'warmup_per_strategy':10,'samples_per_strategy':30,'validated_executions':80,
          'notes':['SQL server durations only; split is sum of page and aggregate SELECTs.',
                   'One read-only transaction per iteration; alternating order; client startup/network excluded.',
                   'COUNT, brand validation/enrichment and HTTP mapping excluded equally; no JOIN API implemented.',
                   'All product fields selected and like counts checked; zero-like products retained.'],
          'strategies':{}}
for key, queries in [('split',[page,likes]),('join',[joined])]:
    result['strategies'][key] = {'median_ms':statistics.median(samples[key]),
        'mean_ms':statistics.mean(samples[key]),'all_ms':samples[key],
        'queries':[{'sql':q,'explain_analyze':probe.mysql('EXPLAIN ANALYZE '+q)} for q in queries]}
out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print({k:v['median_ms'] for k,v in result['strategies'].items()})

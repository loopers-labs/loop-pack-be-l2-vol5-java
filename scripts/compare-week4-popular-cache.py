#!/usr/bin/env python3
"""Compare the 60-second popular snapshot on an owned copy of the existing 100k seed."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
from urllib.error import URLError
from urllib.request import Request, urlopen
import uuid
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('previous', ROOT / 'scripts/compare-week4-likes-cache.py')
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
baseline, probe, scale, existing = previous.baseline, previous.probe, previous.scale, previous.existing


def validation(jar_hash):
    suites = [ET.parse(p).getroot() for p in (ROOT / 'apps/commerce-api/build/test-results/test').glob('TEST-*.xml')]
    result = {name: sum(int(s.get(name, 0)) for s in suites) for name in ('tests', 'failures', 'errors', 'skipped')}
    result['suites'] = len(suites)
    assert result['tests'] >= 331 and all(result[name] == 0 for name in ('failures', 'errors', 'skipped'))
    popular = next(s for s in suites if s.get('name').endswith('.PopularPageCacheIntegrationTest'))
    result['popular_cases'] = [case.get('name') for case in popular.findall('testcase')]
    assert len(result['popular_cases']) == 17
    result['archunit_suite'] = next(s.get('name') for s in suites if 'ArchitectureTest' in s.get('name'))
    for scope in ('main', 'test'):
        report = ROOT / f'apps/commerce-api/build/reports/checkstyle/{scope}.xml'
        assert len(ET.parse(report).getroot().findall('.//error')) == 0
    result.update(checkstyle='passed', jar_sha256=jar_hash,
                  command=':apps:commerce-api:check :apps:commerce-api:bootJar', exit_code=0)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--samples', type=int, default=200)
    args = parser.parse_args()
    assert args.jar.is_file() and not args.output.exists() and args.samples >= 20 and args.samples % 20 == 0
    jar_hash = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    checks = validation(jar_hash)
    with zipfile.ZipFile(args.jar) as jar:
        assert 'BOOT-INF/classes/com/loopers/infrastructure/product/RedisPopularPageCache.class' in jar.namelist()
        for path in (ROOT / 'apps/commerce-api/build/classes/java/main').rglob('*.class'):
            relative = path.relative_to(ROOT / 'apps/commerce-api/build/classes/java/main')
            assert jar.read('BOOT-INF/classes/' + str(relative)) == path.read_bytes(), relative
    for port in (18086, 18087):
        with socket.socket() as check:
            check.bind(('127.0.0.1', port))
    original_mysql = probe.mysql
    tag = uuid.uuid4().hex[:12]
    database, prefix = 'w4_popular_compare_' + tag, 'w4-popular-compare:' + tag
    key = prefix + ':brand:7:sort:popular:page:0:size:20'
    sql = lambda query: original_mysql(f'USE {database};\n' + query)
    before = existing.source_snapshot(scale.DB)
    counter_query = f"SELECT COUNT(*),SUM(like_count),BIT_XOR(CRC32(CONCAT_WS('|',product_id,like_count))) FROM {scale.DB}.product_like_count;"
    counter_before = original_mysql(counter_query)
    body = baseline.expected()
    ids = {item['id'] for item in body['items']}
    ordered_ids = [item['id'] for item in body['items']]
    target = next(item['id'] for item in reversed(body['items']) if not existing.api.seed_liked(item['id'], 95)
                  and [row['id'] for row in baseline.expected(95, {item['id']: 1})['items']] != ordered_ids)
    outside = next(i for i in range(1, 100001) if scale.seed.brand_id(i) == 7 and not scale.seed.deleted(i)
                   and i not in ids and scale.seed.like_count(i) == 10 and not existing.api.seed_liked(i, 95))
    current_changed = baseline.expected(95, {target: 1})
    snapshot_changed = deepcopy(body)
    item = next(item for item in snapshot_changed['items'] if item['id'] == target)
    item.update(likeCount=item['likeCount'] + 1, isLiked=True)
    assert current_changed != snapshot_changed
    assert {item['id'] for item in current_changed['items']} == ids
    url = 'http://localhost:18086/api/v1/products?brandId=7&page=0&size=20&sort=popular'
    probe.URL, probe.HEADERS = url, {'X-USER-ID': '95'}
    result = {'completed': False, 'jar_sha256': jar_hash, 'validation': checks,
              'source': scale.DB, 'source_before': before, 'expected_response': body,
              'changed_product_id': target, 'outside_product_id': outside,
              'changed_db_response': current_changed, 'changed_snapshot_response': snapshot_changed,
              'conditions': {'warmup': 100, 'samples_per_mode': args.samples, 'rounds': 3,
                  'requester_id': 95, 'ttl_seconds': 60, 'concurrent_clients': 20,
                  'concurrent_warmup_bursts': 3, 'measured_bursts': 10, 'mysql_pool_max': 40},
              'runs': [], 'notes': [
                  'Existing 100k seed/indexes copied to an owned DB; no source mutation or new seed.',
                  'Current JAR DB-only/miss/hit have identical responses without changes.',
                  'During changes DB-only sorts current counts; Redis reuses snapshot IDs with current counts/hearts.',
                  'Mixed responses have separate independent expectations; order equality is not claimed.',
                  'Control DEL/GET/PTTL, response validation, SQL tracing and write HTTP are outside GET timing.',
                  'Key identity/TTL checks and representative SQL do not constitute per-request hit telemetry.',
                  'Cold concurrent means empty at burst start, not all requests necessarily miss.',
                  'One app on one host; no maximum throughput, DB CPU or monthly cost claim.']}
    process = None
    control = previous.RedisControl()
    created = granted = current_like = outside_like = False

    def save():
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')

    def get(expected):
        return probe.request(expected)[0]

    def change(add, product=target):
        request = Request(f'http://localhost:18086/api/v1/products/{product}/likes',
                          headers={'X-USER-ID': '95'}, method='POST' if add else 'DELETE')
        start = time.perf_counter()
        with urlopen(request, timeout=30) as response:
            raw = response.read()
            assert response.status == (201 if add else 204), (response.status, raw)
        return (time.perf_counter() - start) * 1000

    def capture(expected):
        probe.mysql = sql
        try:
            trace = probe.capture_sql(expected)
        finally:
            probe.mysql = original_mysql
        assert trace['business_select_count'] == 5
        assert not any('count(' in query['sql'].lower() for query in trace['queries'])
        return trace

    def stop():
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def burst(pool, expected):
        barrier = threading.Barrier(21, timeout=30)
        def worker():
            barrier.wait()
            return get(expected)
        futures = [pool.submit(worker) for _ in range(20)]
        barrier.wait()
        return [future.result(timeout=35) for future in futures]

    try:
        original_mysql(f'CREATE DATABASE {database};')
        created = True
        for table in original_mysql('SHOW TABLES FROM loopers_benchmark;').splitlines():
            assert table.replace('_', '').isalnum()
            sql(f'CREATE TABLE {table} LIKE loopers_benchmark.{table};')
        for table in ('brand', 'product', 'product_like_count', 'users', 'product_like'):
            sql(f'DROP TABLE IF EXISTS {table}; CREATE TABLE {table} LIKE {scale.DB}.{table}; INSERT INTO {table} SELECT * FROM {scale.DB}.{table};')
        original_mysql(f"GRANT SELECT,INSERT,UPDATE,DELETE ON {database}.* TO 'application'@'%';")
        granted = True
        result['brand_7_active_products'] = int(sql('SELECT COUNT(*) FROM product WHERE brand_id=7 AND deleted_at IS NULL;'))
        for round_number, order in enumerate((('db', 'redis'), ('redis', 'db'), ('db', 'redis')), 1):
            for provider in order:
                control.command('DEL', key)
                log_path = args.output.with_name(f'{args.output.stem}-{round_number}-{provider}.log')
                with log_path.open('w') as log:
                    process = subprocess.Popen(['/opt/homebrew/opt/openjdk@21/bin/java', '-Xms256m', '-Xmx512m', '-jar', str(args.jar.resolve()),
                        '--spring.profiles.active=benchmark', '--server.port=18086', '--management.server.port=18087',
                        '--spring.jpa.hibernate.ddl-auto=validate', f'--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3307/{database}',
                        '--commerce.cache.price.enabled=false', '--commerce.cache.popular.enabled=' + str(provider == 'redis').lower(),
                        '--commerce.cache.popular.prefix=' + prefix], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                row = {'round': round_number, 'provider': provider, 'modes': {}, 'mixed': {}, 'concurrent': {}}
                try:
                    deadline = time.monotonic() + 90
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError(f'App exited: {log_path}')
                        try:
                            get(body)
                            break
                        except URLError:
                            if time.monotonic() >= deadline:
                                raise RuntimeError(f'Readiness timeout: {log_path}')
                            time.sleep(.5)
                    modes = ('db_only',) if provider == 'db' else (('hit', 'miss') if round_number % 2 else ('miss', 'hit'))
                    for mode in modes:
                        control.command('DEL', key)
                        get(body)
                        samples = []
                        for i in range(100 + args.samples):
                            if mode == 'miss':
                                control.command('DEL', key)
                            elapsed = get(body)
                            if i >= 100:
                                samples.append(elapsed)
                        if mode == 'miss':
                            control.command('DEL', key)
                        row['modes'][mode] = {'http': scale.stats(samples), 'sql_probe': capture(body)}
                        print(round_number, provider, mode, round(scale.stats(samples)['median_ms'], 3), 'ms', flush=True)
                    if provider == 'redis':
                        cached = json.loads(control.command('GET', key))
                        assert cached == {'ids': [item['id'] for item in body['items']]}
                        row['cache'] = {'value': cached, 'value_bytes': control.command('STRLEN', key),
                                        'key_memory_bytes': control.command('MEMORY', 'USAGE', key), 'ttl_seconds': control.command('TTL', key)}
                        assert 0 < row['cache']['ttl_seconds'] <= 60
                    for reads_per_change in (10, 1):
                        control.command('DEL', key)
                        get(body)
                        old_value = control.command('GET', key) if provider == 'redis' else None
                        ttl_before = control.command('PTTL', key) if provider == 'redis' else None
                        samples, changes = [], []
                        for i in range(100 + args.samples):
                            if i % reads_per_change == 0:
                                current_like = not current_like
                                elapsed_write = change(current_like)
                                if i >= 100:
                                    changes.append(elapsed_write)
                            expected = (snapshot_changed if provider == 'redis' else current_changed) if current_like else body
                            elapsed = get(expected)
                            if provider == 'redis':
                                assert control.command('GET', key) == old_value
                            if i >= 100:
                                samples.append(elapsed)
                        if current_like:
                            change(False)
                            current_like = False
                        get(body)
                        record = {'get_http': scale.stats(samples), 'write_http': scale.stats(changes),
                                  'reads_per_change': reads_per_change, 'responses_checked': len(samples),
                                  'sql_probe_after_changes': capture(body)}
                        if provider == 'redis':
                            ttl_after = control.command('PTTL', key)
                            assert 0 < ttl_after <= ttl_before
                            record.update(cache_value_unchanged=True, ttl_before_ms=ttl_before, ttl_after_ms=ttl_after,
                                          key_identity_checks=100 + args.samples)
                        row['mixed'][str(reads_per_change)] = record
                        print(round_number, provider, 'mixed', reads_per_change, round(scale.stats(samples)['median_ms'], 3), 'ms', flush=True)
                    with ThreadPoolExecutor(max_workers=20) as pool:
                        for mode in (('db_only',) if provider == 'db' else ('cold', 'warm')):
                            samples = []
                            for batch in range(13):
                                if mode == 'cold':
                                    control.command('DEL', key)
                                elif mode == 'warm':
                                    get(body)
                                values = burst(pool, body)
                                if batch >= 3:
                                    samples.extend(values)
                            row['concurrent'][mode] = {'http': scale.stats(samples), 'responses_checked': len(samples), 'errors': 0}
                            print(round_number, provider, 'burst_' + mode, round(scale.stats(samples)['p95_ms'], 3), 'p95 ms', flush=True)
                    if provider == 'redis' and round_number == 3:
                        control.command('DEL', key)
                        started = time.monotonic()
                        get(body)
                        old_value = control.command('GET', key)
                        original_ttl = control.command('PTTL', key)
                        assert 59_000 <= original_ttl <= 60_000
                        change(True)
                        current_like = True
                        change(True, outside)
                        outside_like = True
                        get(snapshot_changed)
                        expected = baseline.expected(95, {target: 1, outside: 1})
                        assert outside in {item['id'] for item in expected['items']}
                        try:
                            probe.URL = url.replace('sort=popular', 'sort=likes_desc')
                            get(expected)
                        finally:
                            probe.URL = url
                        assert control.command('GET', key) == old_value
                        ttl_after_likes = control.command('PTTL', key)
                        assert 0 < ttl_after_likes <= original_ttl
                        print('Observing actual 60-second expiry; likes keep IDs, counts/hearts are current.', flush=True)
                        deadline = time.monotonic() + 70
                        while control.command('EXISTS', key):
                            assert time.monotonic() < deadline
                            time.sleep(.25)
                        expired_at_ms = (time.monotonic() - started) * 1000
                        get(expected)
                        assert json.loads(control.command('GET', key)) == {'ids': [item['id'] for item in expected['items']]}
                        result['real_ttl_observation'] = {'initial_ttl_ms': original_ttl, 'ttl_after_likes_ms': ttl_after_likes,
                            'expired_observed_after_ms': expired_at_ms, 'old_ids_retained_after_commits': True,
                            'likes_desc_was_current': True, 'after_expiry_response': expected,
                            'outside_product_entered_after_expiry': True}
                        change(False, outside)
                        outside_like = False
                        change(False)
                        current_like = False
                        control.command('DEL', key)
                    result['runs'].append(row)
                    save()
                finally:
                    if outside_like:
                        change(False, outside)
                        outside_like = False
                    if current_like:
                        change(False)
                        current_like = False
                    stop()
        for product in (target, outside):
            assert sql(f'SELECT COUNT(*) FROM product_like WHERE user_id=95 AND product_id={product};') == '0'
            assert int(sql(f'SELECT like_count FROM product_like_count WHERE product_id={product};')) == scale.seed.like_count(product)
    finally:
        probe.mysql = original_mysql
        stop()
        try:
            try:
                control.command('DEL', key)
            finally:
                control.close()
        finally:
            try:
                if granted:
                    original_mysql(f"REVOKE SELECT,INSERT,UPDATE,DELETE ON {database}.* FROM 'application'@'%';")
            finally:
                if created:
                    original_mysql(f'DROP DATABASE {database};')
        assert existing.source_snapshot(scale.DB) == before
        assert original_mysql(counter_query) == counter_before
        result.update(source_unchanged=True, owned_resources_cleaned=True)
        save()
    result['completed'] = True
    save()
    print('Saved', args.output, flush=True)


if __name__ == '__main__':
    main()

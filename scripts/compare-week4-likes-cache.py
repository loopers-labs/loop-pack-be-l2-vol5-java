#!/usr/bin/env python3
"""Historical ADR 0024 experiment; use its implementation JAR, not the current popular snapshot JAR."""
import argparse
from concurrent.futures import ThreadPoolExecutor
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
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("baseline", ROOT / "scripts/measure-week4-likes-cache.py")
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
existing, probe, scale = baseline.existing, baseline.probe, baseline.scale


class RedisControl:
    """One persistent control connection; DEL/inspection are outside HTTP timing."""
    def __init__(self):
        self.socket = socket.create_connection(("127.0.0.1", 6379), timeout=5)
        self.reader = self.socket.makefile('rb')

    def command(self, *args):
        encoded = [str(arg).encode() for arg in args]
        self.socket.sendall(b'*' + str(len(encoded)).encode() + b'\r\n' + b''.join(
            b'$' + str(len(arg)).encode() + b'\r\n' + arg + b'\r\n' for arg in encoded))
        line = self.reader.readline()
        if line.startswith(b'-'):
            raise RuntimeError(line.decode())
        if line.startswith(b':'):
            return int(line[1:])
        if line.startswith(b'$'):
            size = int(line[1:])
            if size == -1:
                return None
            value = self.reader.read(size)
            assert self.reader.read(2) == b'\r\n'
            return value.decode()
        assert line.startswith(b'+'), line
        return line[1:-2].decode()

    def close(self):
        self.reader.close()
        self.socket.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--samples', type=int, default=200)
    args = parser.parse_args()
    assert args.jar.is_file() and not args.output.exists() and args.samples >= 20
    prior = json.loads(args.baseline.read_text())
    prior = prior.get('baseline_before_cache', prior)
    assert prior['completed'] and prior['source_unchanged'] and prior['owned_resources_cleaned']
    with zipfile.ZipFile(args.jar) as jar:
        if 'BOOT-INF/classes/com/loopers/infrastructure/product/RedisLikesPageCache.class' not in jar.namelist():
            parser.error('ADR 0024 implementation JAR required; current comparison: scripts/compare-week4-popular-cache.py')
        for path in (ROOT / 'apps/commerce-api/build/classes/java/main').rglob('*.class'):
            relative = path.relative_to(ROOT / 'apps/commerce-api/build/classes/java/main')
            assert jar.read('BOOT-INF/classes/' + str(relative)) == path.read_bytes(), relative
    for port in (18086, 18087):
        with socket.socket() as check:
            check.bind(('127.0.0.1', port))
    original_mysql = probe.mysql
    tag = uuid.uuid4().hex[:12]
    database = 'w4_likes_compare_' + tag
    prefix = 'w4-likes-compare:' + tag
    key = prefix + ':brand:7:sort:likes_desc:page:0:size:20'
    sql = lambda query: original_mysql(f'USE {database};\n' + query)
    before = existing.source_snapshot(scale.DB)
    counter_before = original_mysql(f"SELECT COUNT(*),SUM(like_count),BIT_XOR(CRC32(CONCAT_WS('|',product_id,like_count))) FROM {scale.DB}.product_like_count;")
    body = baseline.expected()
    assert body == prior['expected_response']
    ids = {item['id'] for item in body['items']}
    seed = scale.seed
    target = next(i for i in range(1, 100001) if seed.brand_id(i) == 7 and not seed.deleted(i)
                  and i not in ids and seed.like_count(i) == 10 and not existing.api.seed_liked(i, 95))
    promoted = baseline.expected(95, {target: 1})
    assert target in {item['id'] for item in promoted['items']}
    probe.URL = 'http://localhost:18086/api/v1/products?brandId=7&page=0&size=20&sort=likes_desc'
    probe.HEADERS = {'X-USER-ID': '95'}
    result = {'completed': False, 'jar_sha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(),
              'source': scale.DB, 'source_before': before, 'baseline_before_cache': prior,
              'expected_response': body, 'changed_response': promoted, 'changed_product_id': target,
              'conditions': {'warmup': 100, 'samples_per_mode': args.samples, 'rounds': 3,
                  'requester_id': 95, 'concurrent_clients': 20, 'concurrent_warmup_bursts': 3,
                  'measured_bursts': 10, 'mysql_pool_max': 40}, 'runs': [],
              'notes': ['Current production JAR; existing indexes; normal 100k seed copied, not regenerated.',
                  'Page IDs only in Redis; brand/products/counters/personal relationships still read from DB.',
                  'Three rotated JVM orders; hit/miss order alternates. Each mode has independent warmup.',
                  'Forced miss DEL uses a persistent direct control connection outside HTTP timing.',
                  'Mixed workloads alternate real POST/DELETE before every 10th/every GET; not production frequencies.',
                  'Concurrent cold is empty at burst start; not a guarantee every request misses.',
                  'Concurrent arrivals are synchronized in the client, not at the server or database.',
                  'SQL logging is separate from latency samples; no production throughput/cost claim.']}
    process = None
    control = RedisControl()
    created = granted = False
    current_like = False

    def save():
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')

    def get(expected):
        return probe.request(expected)[0]

    def change(add):
        request = Request(f'http://localhost:18086/api/v1/products/{target}/likes',
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
                        '--commerce.cache.price.enabled=false', '--commerce.cache.likes.enabled=' + str(provider == 'redis').lower(),
                        '--commerce.cache.likes.prefix=' + prefix], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
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
                        assert 0 < row['cache']['ttl_seconds'] <= 300
                    for reads_per_change in (10, 1):
                        get(body)
                        samples, changes = [], []
                        for i in range(100 + args.samples):
                            if i % reads_per_change == 0:
                                current_like = not current_like
                                elapsed_write = change(current_like)
                                if i >= 100:
                                    changes.append(elapsed_write)
                            elapsed = get(promoted if current_like else body)
                            if i >= 100:
                                samples.append(elapsed)
                        if current_like:
                            change(False)
                            current_like = False
                        get(body)
                        row['mixed'][str(reads_per_change)] = {'get_http': scale.stats(samples), 'write_http': scale.stats(changes),
                            'reads_per_change': reads_per_change, 'responses_checked': len(samples),
                            'workload_hit_fraction': 0 if provider == 'db' else (reads_per_change - 1) / reads_per_change,
                            'note': 'Expected from the controlled workload, not production hit rate or per-request hit logs.'}
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
                    result['runs'].append(row)
                    save()
                finally:
                    if current_like:
                        change(False)
                        current_like = False
                    stop()
        assert sql(f'SELECT COUNT(*) FROM product_like WHERE user_id=95 AND product_id={target};') == '0'
        assert int(sql(f'SELECT like_count FROM product_like_count WHERE product_id={target};')) == seed.like_count(target)
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
        assert original_mysql(f"SELECT COUNT(*),SUM(like_count),BIT_XOR(CRC32(CONCAT_WS('|',product_id,like_count))) FROM {scale.DB}.product_like_count;") == counter_before
        result.update(source_unchanged=True, owned_resources_cleaned=True)
        save()
    result['completed'] = True
    save()
    print('Saved', args.output, flush=True)


if __name__ == '__main__':
    main()

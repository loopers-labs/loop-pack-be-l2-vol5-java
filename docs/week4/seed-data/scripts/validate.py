"""Validate actual CSV files and create/verify their manifest before loading."""
import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path

TABLES = {
    'brands': ('brand', ['id', 'name', 'created_at', 'updated_at', 'deleted_at']),
    'users': ('users', ['id', 'created_at', 'updated_at', 'deleted_at']),
    'products': ('product', ['id', 'brand_id', 'name', 'price', 'created_at', 'updated_at', 'deleted_at']),
    'stocks': ('stock', ['id', 'product_id', 'quantity', 'created_at', 'updated_at', 'deleted_at']),
    'likes': ('product_like', ['id', 'user_id', 'product_id', 'created_at', 'updated_at', 'deleted_at']),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def file_hash(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def row_hash(values):
    encoded = '|'.join(value.encode('utf-8').hex().upper() for value in values)
    return hashlib.sha256(encoded.encode('ascii')).hexdigest()


def valid_name(name, limit):
    require(0 < len(name.encode('utf-16-be')) // 2 <= limit, 'Invalid UTF-16 name length')
    require(ord(name[0]) > 32 and ord(name[-1]) > 32, 'Untrimmed name')


def validate(directory, write=False):
    directory = Path(directory)
    generation = json.loads((directory / 'generation.json').read_text())
    skewed = generation.get('profile') == 'skewed-1m'
    require(generation.get('profile') in (None, 'skewed-1m'), 'Unknown generation profile')
    stamp = generation['reference_time_utc']
    require(stamp == '2026-01-01 00:00:00.000000', 'Unexpected reference time')
    brands, users, products, stock_products = set(), set(), set(), set()
    names = {'brands': set(), 'products': set()}
    brand_counts = collections.Counter()
    like_counts = collections.Counter()
    like_users = collections.defaultdict(int)
    pairs = set()
    user_counts = collections.Counter()
    files = {}
    for kind, (_, headers) in TABLES.items():
        path = directory / (kind + '.csv')
        digest = hashlib.sha256()
        count = 0
        with path.open(encoding='utf-8', newline='') as stream:
            reader = csv.DictReader(stream, strict=True)
            require(reader.fieldnames == headers, f'{kind}: unexpected headers')
            for count, row in enumerate(reader, 1):
                require(None not in row and all(v is not None for v in row.values()), f'{kind}: malformed CSV')
                require(row['id'] == str(count), f'{kind}: IDs must be sequential and unique')
                require(row['created_at'] == stamp and row['updated_at'] == stamp and row['deleted_at'] == '', f'{kind}: timestamps')
                if kind in names:
                    valid_name(row['name'], 50 if kind == 'brands' else 100)
                    require(row['name'] not in names[kind], f'{kind}: duplicate name')
                    names[kind].add(row['name'])
                if kind == 'brands':
                    brands.add(count)
                elif kind == 'users':
                    users.add(count)
                elif kind == 'products':
                    brand = int(row['brand_id'])
                    require(brand in brands, 'Missing brand')
                    require(1 <= int(row['price']) < 100_000_000, 'Price outside generation bounds')
                    brand_counts[brand] += 1
                    products.add(count)
                elif kind == 'stocks':
                    product = int(row['product_id'])
                    require(product in products and product not in stock_products, 'Missing or duplicate stock product')
                    require(0 <= int(row['quantity']) < 100_000_000, 'Stock outside generation bounds')
                    stock_products.add(product)
                else:
                    product, user = int(row['product_id']), int(row['user_id'])
                    require(product in products and user in users, 'Missing like reference')
                    if skewed:
                        pair = (user, product)
                        require(pair not in pairs, 'Duplicate user/product like')
                        pairs.add(pair)
                    else:
                        bit = 1 << user
                        require(not (like_users[product] & bit), 'Duplicate user/product like')
                        like_users[product] |= bit
                    like_counts[product] += 1
                    user_counts[user] += 1
                    require(like_counts[product] <= (len(users) if skewed else 200), 'Like cap exceeded')
                digest.update((row_hash([row[h] for h in headers]) + '\n').encode('ascii'))
        require(count == generation[kind], f'{kind}: count mismatch')
        files[path.name] = {'rows': count, 'bytes': path.stat().st_size,
                            'sha256': file_hash(path), 'ordered_row_sha256': digest.hexdigest()}
    require(stock_products == products, 'Missing stock')
    histogram = collections.Counter(like_counts[p] for p in products)
    if skewed:
        require(len(brands) == 1000 and len(users) == 240000 and len(products) == 1000000, 'Skewed size mismatch')
        require(sum(like_counts.values()) == 690000, 'Skewed like total')
        require(generation['brand_distribution'] == 'tiered', 'Wrong brand profile')
        for brand in brands:
            expected = 5000 if brand <= 100 else 1000 if brand <= 400 else 334 if brand <= 600 else 333
            require(brand_counts[brand] == expected, 'Wrong brand allocation')
        require(histogram[0] == 790000, 'Wrong zero-product count')
        require(len(users) - len(user_counts) == 146400, 'Wrong zero-user count')
        ranked_products = sorted(like_counts.values(), reverse=True)
        ranked_users = sorted(user_counts.values(), reverse=True)
        require(sum(ranked_products[:10000]) == 269100, 'Wrong product top 1%')
        require(sum(ranked_products[:100000]) == 579600, 'Wrong product top 10%')
        require(sum(ranked_users[:48000]) == 614100, 'Wrong user top 20%')
    else:
        require(len(brands) == 100 and len(users) == len(products) == 10_000, 'Baseline size mismatch')
        require(generation['brand_distribution'] == 'uniform' and set(brand_counts.values()) == {100}, 'Uneven brands')
        require(histogram[0] > 0 and histogram[200] > 0 and any(v > 1 for v in histogram.values()), 'Missing zero/tied/popular products')
    manifest = {
        'generation': generation, 'generation_sha256': file_hash(directory / 'generation.json'),
        'csv': {'encoding': 'UTF-8', 'newline': 'LF', 'quoting': 'RFC4180 double quotes', 'deleted_at_null': 'empty field'},
        'files': files, 'brand_product_counts': dict(sorted(brand_counts.items())),
        'like_count_histogram': dict(sorted(histogram.items())),
        'generator_sha256': file_hash(Path(__file__).with_name('CatalogSeed.java')),
    }
    if skewed:
        manifest['user_like_count_histogram'] = dict(sorted(collections.Counter(user_counts[u] for u in users).items()))
        manifest['skewed_generator_sha256'] = file_hash(Path(__file__).with_name('SkewedCatalogSeed.java'))
    path = directory / 'manifest.json'
    serialized = json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + '\n'
    if write:
        require(not path.exists(), 'Manifest exists; do not overwrite a dataset version')
        path.write_text(serialized, encoding='utf-8')
    else:
        saved = json.loads(path.read_text())
        # Script edits do not invalidate a previously verified dataset's recorded generator version.
        manifest['generator_sha256'] = saved['generator_sha256']
        if skewed:
            manifest['skewed_generator_sha256'] = saved['skewed_generator_sha256']
        require(json.loads(json.dumps(manifest)) == saved, 'Manifest differs from actual CSV data')
    print(f"Validated {directory.name}: {len(products)} products, {sum(like_counts.values())} likes")
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--write-manifest', action='store_true')
    args = parser.parse_args()
    validate(args.directory, args.write_manifest)

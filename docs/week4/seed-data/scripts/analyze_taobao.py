"""Aggregate Taobao fav pairs over the documented nine-day China-time window."""
import argparse
from collections import Counter
from datetime import datetime, timezone, timedelta
import hashlib
import json
import math
from pathlib import Path


def distribution(counts, population):
    hist = Counter(counts.values())
    hist[0] += population - len(counts)
    total = sum(k * v for k, v in hist.items())
    def percentile(p):
        rank = math.ceil(population * p / 100)
        seen = 0
        for value, n in sorted(hist.items()):
            seen += n
            if seen >= rank:
                return value
    shares = {}
    for percent in (1, 10, 20):
        remaining = math.ceil(population * percent / 100)
        top_total = 0
        for value, n in sorted(hist.items(), reverse=True):
            take = min(remaining, n)
            top_total += take * value
            remaining -= take
            if not remaining:
                break
        shares[str(percent)] = {'entities': math.ceil(population * percent / 100),
                                'favorites': top_total, 'sharePercent': top_total / total * 100}
    return {'population': population, 'withFavorites': len(counts),
            'zeroCount': hist[0], 'zeroPercent': hist[0] / population * 100,
            'favorites': total, 'mean': total / population,
            'median': percentile(50), 'p95': percentile(95), 'p99': percentile(99),
            'max': max(hist), 'topShares': shares,
            'countHistogram': dict(sorted(hist.items()))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('csv', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    zone = timezone(timedelta(hours=8))
    begin = int(datetime(2017, 11, 25, tzinfo=zone).timestamp())
    end = int(datetime(2017, 12, 4, tzinfo=zone).timestamp())
    items, users, pairs = set(), set(), set()
    raw_events, events = Counter(), Counter()
    item_favs, user_favs = Counter(), Counter()
    digest = hashlib.sha256()
    rows = excluded = duplicates = 0
    with args.csv.open('rb') as source:
        for line in source:
            digest.update(line)
            u, i, category, behavior, timestamp = line.strip().split(b',')
            rows += 1
            raw_events[behavior.decode()] += 1
            if not begin <= int(timestamp) < end:
                excluded += 1
                continue
            uid, iid = int(u), int(i)
            users.add(uid)
            items.add(iid)
            events[behavior.decode()] += 1
            if behavior == b'fav':
                assert 0 <= uid < 2**32 and 0 <= iid < 2**32
                pair = (uid << 32) | iid
                if pair in pairs:
                    duplicates += 1
                else:
                    pairs.add(pair)
                    item_favs[iid] += 1
                    user_favs[uid] += 1
            if rows % 10000000 == 0:
                print(f'processed {rows:,} rows', flush=True)
    assert sum(item_favs.values()) == sum(user_favs.values()) == len(pairs)
    assert events['fav'] == len(pairs) + duplicates
    result = {
        'source': 'https://tianchi.aliyun.com/dataset/649',
        'download': 'https://huggingface.co/datasets/Diegopueyo/UserBehaviour/resolve/40508ace04dfbd3bca7aaeaa61239e1a755d9d72/UserBehavior.csv',
        'provenance': 'Third-party mirror; not authenticated against an official file checksum.',
        'sha256': digest.hexdigest(), 'bytes': args.csv.stat().st_size,
        'window': '[2017-11-25 00:00:00, 2017-12-04 00:00:00) UTC+08:00',
        'rawRows': rows, 'rawEvents': dict(raw_events), 'excludedOutsideWindow': excluded,
        'events': dict(events), 'duplicateFavoritePairs': duplicates,
        'uniqueFavoritePairs': len(pairs),
        'denominator': 'All distinct items/users with any event inside the window; not the full catalog or registered user base.',
        'percentileMethod': 'nearest rank, including zero-favorite observed entities',
        'items': distribution(item_favs, len(items)),
        'users': distribution(user_favs, len(users)),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    brief = {k: v for k, v in result.items() if k not in ('items', 'users')}
    for key in ('items', 'users'):
        brief[key] = {k: v for k, v in result[key].items() if k != 'countHistogram'}
    print(json.dumps(brief, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

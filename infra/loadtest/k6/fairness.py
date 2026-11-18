#!/usr/bin/env python3
"""선착순 발급의 서버별 공정성.

fair 의 기준: 같은 앱 서버로 보낸 요청끼리만, 먼저 보낸 요청이 먼저 받는다. 서버 사이 순서는 따지지 않는다.
issue-spike.js 는 i 번째 요청(0 부터)을 사용자 USER_START + i 로, 앱 i % SERVERS 로 보낸다.
그래서 받은 사람의 user_id 에서 서버(i % SERVERS)와 그 서버 안의 보낸 번호(i // SERVERS)가 나온다.
받은 순서는 issued_coupon.id (insert 순서). 재고 차감 행 잠금을 얻은 순서와는 다를 수 있다.

입력: winners.tsv (줄마다 "id<TAB>user_id"), USER_START, 보낸 요청 수, 서버 수
실행: python3 fairness.py winners.tsv 2 12000 3
"""
import sys


def count_inversions(values):
    """values 에서 앞이 뒤보다 큰 쌍의 수 (merge sort)."""
    def sort(xs):
        if len(xs) <= 1:
            return xs, 0
        mid = len(xs) // 2
        left, a = sort(xs[:mid])
        right, b = sort(xs[mid:])
        merged, inv, i, j = [], a + b, 0, 0
        while i < len(left) and j < len(right):
            if left[i] <= right[j]:
                merged.append(left[i])
                i += 1
            else:
                merged.append(right[j])
                inv += len(left) - i
                j += 1
        merged += left[i:] + right[j:]
        return merged, inv
    return sort(list(values))[1]


def percentile(sorted_values, p):
    if not sorted_values:
        return 0
    return sorted_values[min(len(sorted_values) - 1, int(len(sorted_values) * p))]


def analyze(sent, seqs_by_id):
    """sent: 이 서버로 보낸 요청 수, seqs_by_id: 받은 요청의 서버 안 번호를 id(발급) 순으로."""
    k = len(seqs_by_id)
    seqs = sorted(seqs_by_id)
    r = {"sent": sent, "won": k}

    # 누가 받았나: 이상적이면 0..k-1 번이 받는다
    r["prefix_hits"] = sum(1 for q in seqs if q < k)
    # 앞번호는 떨어지고 뒷번호는 받은 쌍: 받은 요청마다 자기보다 앞번호인 탈락 요청 수
    r["skipped_pairs"] = sum(q - rank for rank, q in enumerate(seqs))
    r["skipped_pairs_max"] = k * (sent - k)
    r["last_winner_seq"] = seqs[-1] if seqs else -1

    # 받은 순서: 받은 요청끼리 보낸 번호 순서와 발급 순서가 어긋난 쌍
    pairs = k * (k - 1) // 2
    inv = count_inversions(seqs_by_id)
    r["order_inversions"] = inv
    r["order_pairs"] = pairs
    r["kendall_tau"] = 1 - 2 * inv / pairs if pairs else 1.0

    # 밀린 거리: 발급 순위 - 보낸 번호 순위 (받은 요청 안에서). 양수면 자기 차례보다 늦게 받았다
    rank_of = {q: rank for rank, q in enumerate(seqs)}
    lag = sorted(max(0, issued_rank - rank_of[q]) for issued_rank, q in enumerate(seqs_by_id))
    r["lag_p50"] = percentile(lag, 0.50)
    r["lag_p99"] = percentile(lag, 0.99)
    r["lag_max"] = lag[-1] if lag else 0
    return r


def report(name, r, prefix_label=None, ideal_last=None):
    k = r["won"]
    prefix_label = prefix_label or f"0..{k - 1}"
    ideal_last = k - 1 if ideal_last is None else ideal_last
    prefix = r["prefix_hits"] / k if k else 1.0
    skipped = r["skipped_pairs"] / r["skipped_pairs_max"] if r["skipped_pairs_max"] else 0.0
    order = r["order_inversions"] / r["order_pairs"] if r["order_pairs"] else 0.0
    print(f"[{name}] 보냄 {r['sent']}, 받음 {k}")
    print(f"  누가 받았나: 앞번호({prefix_label}) 안에서 받은 비율 {prefix:.4f} ({r['prefix_hits']}/{k}), "
          f"마지막으로 받은 번호 {r['last_winner_seq']} (이상적 {ideal_last})")
    print(f"               앞번호 탈락·뒷번호 받음 쌍 {r['skipped_pairs']} / 최대 {r['skipped_pairs_max']} ({skipped:.4f})")
    print(f"  받은 순서:   어긋난 쌍 {r['order_inversions']} / {r['order_pairs']} ({order:.4f}), Kendall tau {r['kendall_tau']:.4f}")
    print(f"               밀린 거리(칸) p50 {r['lag_p50']}, p99 {r['lag_p99']}, 최대 {r['lag_max']}")


def main():
    path, user_start, total, servers = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
    rows = []
    with open(path) as f:
        for line in f:
            if line.strip():
                coupon_id, user_id = line.split()
                rows.append((int(coupon_id), int(user_id) - user_start))
    rows.sort()
    total = max([total] + [i + 1 for _, i in rows])

    results = []
    for s in range(servers):
        sent = len(range(s, total, servers))
        seqs_by_id = [i // servers for _, i in rows if i % servers == s]
        results.append(analyze(sent, seqs_by_id))

    print(f"서버별 공정성 (보낸 요청 {total}, 받은 요청 {len(rows)}, 서버 {servers})")
    print("기준: 같은 서버로 보낸 요청끼리 보낸 순서. 받은 순서는 issued_coupon.id (insert 순서)")
    for s, r in enumerate(results):
        report(f"app-{s + 1}", r)

    total_r = {key: sum(r[key] for r in results)
               for key in ("sent", "won", "prefix_hits", "skipped_pairs", "skipped_pairs_max", "order_inversions", "order_pairs")}
    total_r["last_winner_seq"] = max(r["last_winner_seq"] for r in results)
    total_r["kendall_tau"] = 1 - 2 * total_r["order_inversions"] / total_r["order_pairs"] if total_r["order_pairs"] else 1.0
    total_r["lag_p50"] = "-"
    total_r["lag_p99"] = max(r["lag_p99"] for r in results)
    total_r["lag_max"] = max(r["lag_max"] for r in results)
    report("서버 합계 (쌍은 같은 서버 안에서만, 밀린 거리는 서버별 최대)", total_r,
           prefix_label="서버별 0..받은 수-1", ideal_last=max(r["won"] for r in results) - 1)


if __name__ == "__main__":
    main()

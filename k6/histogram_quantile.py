"""prometheus 텍스트에서 Timer 히스토그램 버킷을 읽어 p50·p95·p99 를 근사한다(버킷 안은 선형 보간)."""
import re
import sys

path, name = sys.argv[1], sys.argv[2]
buckets, count, total, maximum = [], 0.0, 0.0, 0.0
for line in open(path, encoding="utf-8"):
    if line.startswith(name + "_bucket"):
        le = re.search(r'le="([^"]+)"', line).group(1)
        buckets.append((float("inf") if le == "+Inf" else float(le), float(line.rsplit(" ", 1)[1])))
    elif line.startswith(name + "_count"):
        count = float(line.rsplit(" ", 1)[1])
    elif line.startswith(name + "_sum"):
        total = float(line.rsplit(" ", 1)[1])
    elif line.startswith(name + "_max"):
        maximum = float(line.rsplit(" ", 1)[1])
buckets.sort()


def quantile(q):
    target, prev_le, prev_c = q * count, 0.0, 0.0
    for le, c in buckets:
        if c >= target:
            if le == float("inf"):
                return maximum
            return prev_le + (le - prev_le) * ((target - prev_c) / (c - prev_c) if c > prev_c else 0)
        prev_le, prev_c = le, c
    return maximum


if count == 0:
    print("T_tx: 측정 없음")
else:
    print("T_tx(ms): 건수 %d, 평균 %.1f, p50 %.1f, p95 %.1f, p99 %.1f, 최대 %.1f" % (
        count, total / count * 1000, quantile(0.5) * 1000, quantile(0.95) * 1000, quantile(0.99) * 1000, maximum * 1000))

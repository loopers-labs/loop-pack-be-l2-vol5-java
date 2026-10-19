#!/usr/bin/env bash
# A 단발 반복 측정 (curl). 프로젝트 루트에서 실행한다. B 의 "10회 순차 curl" 방식을 캐시 hit/miss 용으로 일반화한 것이다.
# 사용법: bash docs/week4/perf/scripts/single-calls.sh <hit|miss> <횟수> <단계 폴더>
#   miss: 매 호출 직전에 캐시 키 1개(CACHE_KEY)를 지우고 호출한다 → 매번 miss.
#   hit : 측정 전에 호출 1회(결과 버림)로 캐시를 채우고 그대로 호출한다 → 매번 hit. 횟수가 많아 TTL(30초)을 넘길 것 같으면 횟수를 줄인다.
# 결과: <단계 폴더>/A-<모드>-<횟수>calls.txt
#   호출마다 "번호 HTTP상태 time_total(초)" 한 줄. 끝에 전체 구간의 SQL 캡처(ps-round-capture.sql)와 캐시 카운터 전후 차이를 붙인다.
# time_total 은 curl 프로세스 비용이 섞인 참고값이다. 1회가 60초를 넘으면 중단한다.
set -euo pipefail

MODE="$1"
COUNT="$2"
STAGE="$3"
SCRIPTS=docs/week4/perf/scripts
OUTDIR="docs/week4/perf/$STAGE"
OUT="$OUTDIR/A-$MODE-${COUNT}calls.txt"
URL="${URL:-http://127.0.0.1:8080/api/v1/products?brandId=7&sort=price_asc&page=0&size=20}"
CACHE_KEY="${CACHE_KEY:-products:v1:brand=7:sort=price_asc:page=0:size=20}"
METRICS_URL="${METRICS_URL:-http://127.0.0.1:8081/actuator/prometheus}"
MYSQL=(docker compose -f ./docker/infra-compose.yml exec -T mysql mysql -uroot -proot loopers --table)
REDIS=(docker compose -f ./docker/infra-compose.yml exec -T redis-master redis-cli)

case "$MODE" in hit|miss) ;; *) echo "모드는 hit 또는 miss" >&2; exit 1 ;; esac
mkdir -p "$OUTDIR"
if [ -e "$OUT" ]; then
  echo "STOP: 결과 파일이 이미 있습니다: $OUT (덮어쓰지 않음)" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

capture_cache_counters() {
  curl -s -m 3 "$METRICS_URL" 2>/dev/null | grep '^product_list_cache_total' > "$1" || true
}

{
  echo "# A $MODE ${COUNT}회 순차 호출 ($STAGE)  call http time_total_s"
  echo "started_at: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "url: $URL"
  echo "cache_key: $CACHE_KEY ($MODE 모드: $([ "$MODE" = miss ] && echo '매 호출 전 DEL' || echo '준비 호출 1회 뒤 유지'))"
} > "$OUT"

if [ "$MODE" = hit ]; then
  "${REDIS[@]}" DEL "$CACHE_KEY" > /dev/null
  curl -s -o /dev/null "$URL"   # 준비 호출: 캐시를 채운다 (결과 버림)
fi

"${MYSQL[@]}" < "$SCRIPTS/ps-reset.sql" > /dev/null 2>&1 || true
capture_cache_counters "$TMP/cache-before.txt"

for i in $(seq 1 "$COUNT"); do
  if [ "$MODE" = miss ]; then
    "${REDIS[@]}" DEL "$CACHE_KEY" > /dev/null
  fi
  line=$(curl -s -o /dev/null -m 60 -w "%{http_code} %{time_total}" "$URL")
  echo "$i $line" >> "$OUT"
done

capture_cache_counters "$TMP/cache-after.txt"
{
  echo
  echo "## 캐시 카운터 전후 (product_list_cache_total)"
  echo "before:"; cat "$TMP/cache-before.txt"
  echo "after:"; cat "$TMP/cache-after.txt"
  echo
  echo "## SQL 개수 (ps-round-capture.sql, ${COUNT}회 구간)"
  "${MYSQL[@]}" < "$SCRIPTS/ps-round-capture.sql" 2>&1 | grep -v "Using a password"
  echo
  echo "## time_total 요약 (초, 정렬 후 p50 = 중앙 순위, p90 = 상위 10% 경계 순위)"
  grep -E '^[0-9]+ [0-9]{3} ' "$OUT" | awk '{ print $3 }' | sort -n | awk '{ t[++n] = $1; sum += $1 } END {
    p50 = t[int((n + 1) / 2)]; p90 = t[int(n * 0.9) < 1 ? 1 : int(n * 0.9)]
    printf "n=%d avg=%.4f p50=%.4f p90=%.4f min=%.4f max=%.4f\n", n, sum / n, p50, p90, t[1], t[n]
  }'
} >> "$OUT"

echo "result: $OUT"
cat "$OUT"

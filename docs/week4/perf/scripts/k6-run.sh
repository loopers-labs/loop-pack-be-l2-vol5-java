#!/usr/bin/env bash
# A 응답 시간 측정. 프로젝트 루트에서 실행한다.
# 사용법: bash docs/week4/perf/scripts/k6-run.sh <VUS> <라벨> <단계 폴더>
#   예) bash docs/week4/perf/scripts/k6-run.sh 1 vu1 baseline  → 5단계 (동시 1명)
#       bash docs/week4/perf/scripts/k6-run.sh 5 vu5 baseline  → 6단계 (동시 5명)
#       bash docs/week4/perf/scripts/k6-run.sh 1 vu1 step1     → 개선 후 1단계
#       CACHE_KEY=products:v1:brand=7:sort=price_asc:page=0:size=20 bash docs/week4/perf/scripts/k6-run.sh 1 hit-vu1 step4-cache
#         → 캐시 hit 측정. 회차마다 준비 50 직전에 그 키만 지워 준비의 첫 요청이 다시 채우게 한다 (TTL 이 회차마다 새로 시작).
# 결과 (측정 1번당 2개):
#   <단계 폴더>/k6-A-<라벨>-summary.tsv : 회차별 지표 + SQL 개수 + 캐시 카운터, mean / min / max / range_pct 행
#   <단계 폴더>/k6-A-<라벨>-run.log     : 전체 실행 로그 (k6 한 줄 요약, SQL 캡처 출력, 카운터 출력 포함)
# 단계 폴더가 없으면 만들고, 위 두 파일 중 하나라도 이미 있으면 덮어쓰지 않고 k6 실행 전에 멈춘다.
# 회차마다: (CACHE_KEY 삭제) → 준비 50회(결과 버림) → SQL 카운터 초기화 + 캐시 카운터 읽기 → 측정 500회 → SQL 개수 캡처 + 캐시 카운터 읽기. 3회 반복.
# 회차별 중간 결과는 임시 폴더에만 두고 summary.tsv 를 만든 뒤 지운다.
# 요청당 SQL 수 = 상품 조회 SQL 4종 합계(digest 기준: 목록·좋아요 집계·브랜드·hit 재고 확인) ÷ 요청 수. 커넥션 초기화 등은 other_select 로 따로 센다.
# 캐시 카운터(cache_hit / cache_miss / cache_other)는 측정 500회 구간 전후의 /actuator/prometheus product_list_cache_total 차이다.
#   cache_other = hit_check_failed + find_error + save_error + evict_error. 카운터가 없는 앱(step3 이전)에서는 모두 0 이다.
# summary.tsv 의 mean 행은 회차별 값의 산술 평균이다 (p50·p90·p95·max 도 회차 값의 평균이며 전체 요청을 합친 백분위가 아니다).
set -euo pipefail

VUS="$1"
LABEL="$2"
STAGE="$3"
SCRIPTS=docs/week4/perf/scripts
OUTDIR="docs/week4/perf/$STAGE"
SCRIPT="$SCRIPTS/k6-products.js"
SUMMARY="$OUTDIR/k6-A-$LABEL-summary.tsv"
LOG="$OUTDIR/k6-A-$LABEL-run.log"
MYSQL=(docker compose -f ./docker/infra-compose.yml exec -T mysql mysql -uroot -proot loopers --table)
REDIS=(docker compose -f ./docker/infra-compose.yml exec -T redis-master redis-cli)
METRICS_URL="${METRICS_URL:-http://127.0.0.1:8081/actuator/prometheus}"
CACHE_KEY="${CACHE_KEY:-}"

mkdir -p "$OUTDIR"
for f in "$SUMMARY" "$LOG"; do
  if [ -e "$f" ]; then
    echo "STOP: 결과 파일이 이미 있습니다: $f (덮어쓰지 않음)" >&2
    exit 1
  fi
done

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# product_list_cache_total 줄만 저장한다. 엔드포인트가 없거나 카운터가 아직 없으면 빈 파일이 된다.
capture_cache_counters() {
  curl -s -m 3 "$METRICS_URL" 2>/dev/null | grep '^product_list_cache_total' > "$1" || true
  cat "$1"
}

run_rounds() {
  for r in 1 2 3; do
    if [ -n "$CACHE_KEY" ]; then
      echo "=== round $r delete cache key $CACHE_KEY ==="
      "${REDIS[@]}" DEL "$CACHE_KEY"
    fi

    echo "=== round $r (vus=$VUS) warmup 50 ==="
    k6 run --quiet -e VUS="$VUS" -e ITERS=50 "$SCRIPT"

    echo "=== round $r reset counters ==="
    "${MYSQL[@]}" < "$SCRIPTS/ps-reset.sql" 2>&1 | grep -v "Using a password" || true
    echo "=== round $r cache counters before ==="
    capture_cache_counters "$TMP/cache-before-r$r.txt"

    echo "=== round $r measure 500 ==="
    k6 run --quiet -e VUS="$VUS" -e ITERS=500 "$SCRIPT" | tee "$TMP/k6-r$r.txt"

    echo "=== round $r sql counters (ps-round-capture.sql) ==="
    "${MYSQL[@]}" < "$SCRIPTS/ps-round-capture.sql" 2>&1 | grep -v "Using a password" \
      | tee "$TMP/sql-r$r.txt"
    echo "=== round $r cache counters after ==="
    capture_cache_counters "$TMP/cache-after-r$r.txt"
  done
}

# 두 prometheus 캡처의 차이를 "hit miss other" 세 수로 만든다.
cache_delta() {
  local r="$1"
  awk '
    function val(line,   v) { n = split(line, f, " "); return f[n] }
    function res(line) { match(line, /result="[^"]*"/); return substr(line, RSTART + 8, RLENGTH - 9) }
    FNR == NR { before[res($0)] = val($0); next }
    { after[res($0)] = val($0) }
    END {
      for (k in after) d[k] = after[k] - (k in before ? before[k] : 0)
      other = 0; for (k in d) if (k != "hit" && k != "miss") other += d[k]
      printf "%d %d %d\n", d["hit"] + 0, d["miss"] + 0, other
    }' "$TMP/cache-before-r$r.txt" "$TMP/cache-after-r$r.txt"
}

# k6 한 줄 요약(vus=… avg=… checks_fail=…), ps-round-capture 의 7숫자 요약 행, 캐시 카운터 차이에서 회차 1줄을 만든다.
round_row() {
  local r="$1" k6 sql cache
  k6=$(grep -E '^vus=' "$TMP/k6-r$r.txt" | tail -1)
  sql=$(grep -E '^\| +[0-9]+ \| +[0-9]+ \| +[0-9]+ \| +[0-9]+ \| +[0-9]+ \| +[0-9]+ \| +-?[0-9]+ \|' "$TMP/sql-r$r.txt" | head -1)
  cache=$(cache_delta "$r")
  awk -v r="$r" -v k6="$k6" -v sql="$sql" -v cache="$cache" 'BEGIN {
    n = split(k6, a, " "); for (i = 1; i <= n; i++) { split(a[i], kv, "="); v[kv[1]] = kv[2] }
    split(sql, s, "|"); for (i = 2; i <= 8; i++) gsub(/ /, "", s[i])
    split(cache, c, " ")
    printf "%s\t%s\t%s\t%s\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%.3f\t%d\t%d\t%d\n",
      r, v["vus"], v["iters"], v["reqs"], v["avg"], v["p50"], v["p90"], v["p95"], v["max"], v["rps"],
      v["http_failed"], v["checks_fail"], s[3], s[4], s[5], s[6], s[7], s[8], s[7] / v["reqs"], c[1], c[2], c[3]
  }'
}

build_summary() {
  {
    printf "round\tvus\titers\treqs\tavg_ms\tp50_ms\tp90_ms\tp95_ms\tmax_ms\treq_s\thttp_fail\tcheck_fail\tsql_list\tsql_like_agg\tsql_brand\tsql_stock_check\tsql_product_total\tother_select\tsql_per_req\tcache_hit\tcache_miss\tcache_other\n"
    for r in 1 2 3; do round_row "$r"; done
  } > "$TMP/rows.tsv"

  # 5~22열에 대해 mean / min / max / range_pct 행을 덧붙인다. 건수 열(11~18, 20~22)은 평균이 정수면 정수로 찍는다.
  awk -F'\t' 'BEGIN { OFS = "\t" }
    NR == 1 { print; next }
    { print; n++; for (c = 5; c <= 22; c++) { sum[c] += $c; if (n == 1 || $c < mn[c]) mn[c] = $c; if (n == 1 || $c > mx[c]) mx[c] = $c } ; vus = $2; iters = $3; reqs = $4 }
    END {
      for (c = 5; c <= 22; c++) mean[c] = sum[c] / n
      printf "mean\t%s\t%s\t%s", vus, iters, reqs
      for (c = 5; c <= 22; c++) printf "\t%s", fmt(c, mean[c]); printf "\n"
      printf "min\t%s\t%s\t%s", vus, iters, reqs
      for (c = 5; c <= 22; c++) printf "\t%s", fmt(c, mn[c]); printf "\n"
      printf "max\t%s\t%s\t%s", vus, iters, reqs
      for (c = 5; c <= 22; c++) printf "\t%s", fmt(c, mx[c]); printf "\n"
      printf "range_pct\t-\t-\t-"
      for (c = 5; c <= 22; c++) printf "\t%.1f", (mean[c] > 0 ? (mx[c] - mn[c]) / mean[c] * 100 : 0); printf "\n"
    }
    function fmt(c, x) {
      if (c == 19) return sprintf("%.3f", x)
      if ((c >= 11 && c <= 18) || c >= 20) return (x == int(x)) ? sprintf("%d", x) : sprintf("%.2f", x)
      return sprintf("%.2f", x)
    }' "$TMP/rows.tsv" > "$SUMMARY"
}

run_rounds 2>&1 | tee "$LOG"
build_summary
echo "summary: $SUMMARY"
cat "$SUMMARY"

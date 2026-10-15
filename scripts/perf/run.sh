#!/usr/bin/env bash
# 상품 조회 쿼리 성능 측정
#
# 사용법 (저장소 루트에서, docker/infra-compose.yml의 mysql이 떠 있는 상태)
#   scripts/perf/run.sh seed              # 측정용 DB(loopers_perf)에 대량 데이터 생성
#   scripts/perf/run.sh bench baseline    # 측정 결과를 docs/week4/query-performance-raw.md 끝에 섹션으로 추가
#   scripts/perf/run.sh bench after-index # 개선 후 같은 방식으로 측정해 비교
#   scripts/perf/run.sh api-bench baseline            # 앱을 loopers_perf로 띄워 API 요청 단위 측정
#   scripts/perf/run.sh compare baseline after-index  # 두 측정의 결과 스냅샷(SQL·API) 비교
#   scripts/perf/run.sh ddl-check         # 빈 DB에 앱을 ddl-auto=create로 띄워 seed.sql 스키마와 대조
#
# 환경 변수
#   PERF_DB      측정용 DB 이름 (기본 loopers_perf, 애플리케이션 local DB와 분리)
#   RUNS         쿼리별 측정 횟수 (기본 10, 별도로 워밍업 1회)
#   TIMEOUT_MS   쿼리 1회 제한 시간 (기본 30000). 넘으면 TIMEOUT으로 기록하고 반복 측정을 건너뛴다
#   SLOW_MS      워밍업이 이 시간(ms)을 넘으면 느린 쿼리로 보고 SLOW_RUNS회만 측정 (기본 10000 / 3)
#   QUERY_TIMEOUTS  쿼리별 제한 시간 (기본 "Q6_likes_all=60000 Q7_likes_brand=60000": TIMEOUT 하한값과 EXPLAIN만 남긴다)
#   QUERY_RUNS      쿼리별 측정 횟수. 예) "Q6_likes_all=1"
#   MYSQL_CMD    mysql 클라이언트 명령 (기본: compose의 mysql 컨테이너에서 root로 실행)
#   api-bench    APP_JAR(없으면 bootJar 빌드) APP_PORT(8080) APP_DB_USER/APP_DB_PASSWORD(root/root)
#                API_WARMUP(20) API_REQUESTS(200) API_SLOW_MS(5000, 첫 요청이 넘으면 워밍업 1회 + 측정 3회)
#                API_REQUEST_TIMEOUTS 요청별 curl 제한(초). 예) "R3=60" — 넘으면 TIMEOUT으로 기록하고 그 요청은
#                더 보내지 않으며(실패로 보지 않음), 남은 앱 쿼리는 KILL QUERY로 끊는다
#                개선 전후 비교는 같은 계정·옵션(root, show-sql=false 등 app_args)으로 측정한다
#                API_CACHE_MODE off(기본: 도구가 Redis를 건드리지 않음) | hit(워밍업이 채운 캐시를 읽음) | miss(요청마다 직전에
#                버전 키 INCR, 측정 구간 밖) — hit·miss는 Redis 상태·요청당 Redis 명령 수를 함께 기록한다
#                API_ONLY 측정할 요청만 고른다. 예) "R1 R2 R3" (기본: 전부)
#   cache-fault  scripts/perf/run.sh cache-fault <label> stop|pause — R1을 워밍업 없이 FAULT_REQUESTS(20)회 보내며
#                Redis 장애 중 응답·지연을 재고, 복구 후 첫 요청이 캐시를 다시 채우는지 확인한다. 끝나면(실패해도) 복구한다.
#                FAULT_REF_LABEL(cache-off) 스냅샷과 응답을 비교한다.
#   Redis        REDIS_CLI(기본: docker exec redis-master redis-cli), REDIS_STOP_CMD·REDIS_START_CMD·REDIS_PAUSE_CMD·
#                REDIS_UNPAUSE_CMD(기본: compose의 redis-master·redis-readonly 컨테이너에 docker stop/start/pause/unpause)
#   seed 규모    BRANDS(200) PRODUCTS(100000) LIKES(1000000) USERS(50000)
#   seed 부하    CHUNK(100000, 청크마다 커밋) CHUNK_SLEEP(0, 청크 사이 쉬는 초)
#   예) PRODUCTS=10000000 LIKES=10000000 CHUNK_SLEEP=0.2 scripts/perf/run.sh seed
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PERF_DIR="$ROOT_DIR/scripts/perf"
PERF_DB="${PERF_DB:-loopers_perf}"
RUNS="${RUNS:-10}"
TIMEOUT_MS="${TIMEOUT_MS:-30000}"
SLOW_MS="${SLOW_MS:-10000}"
SLOW_RUNS="${SLOW_RUNS:-3}"
QUERY_TIMEOUTS="${QUERY_TIMEOUTS-Q6_likes_all=60000 Q7_likes_brand=60000}"
QUERY_RUNS="${QUERY_RUNS:-}"
RESULTS_DIR="$ROOT_DIR/docs/week4/perf-results"
APP_JAR="${APP_JAR:-}"
APP_PORT="${APP_PORT:-8080}"
APP_DB_USER="${APP_DB_USER:-root}"
APP_DB_PASSWORD="${APP_DB_PASSWORD:-root}"
APP_START_TIMEOUT="${APP_START_TIMEOUT:-180}"
API_USER_ID="${API_USER_ID:-1}"
API_WARMUP="${API_WARMUP:-20}"
API_REQUESTS="${API_REQUESTS:-200}"
API_SLOW_MS="${API_SLOW_MS:-5000}"
API_SLOW_WARMUP="${API_SLOW_WARMUP:-1}"
API_SLOW_REQUESTS="${API_SLOW_REQUESTS:-3}"
API_REQUEST_TIMEOUTS="${API_REQUEST_TIMEOUTS:-}"
CURL_MAX_TIME=900
API_CACHE_MODE="${API_CACHE_MODE:-off}"
API_ONLY="${API_ONLY:-}"
REDIS_CLI="${REDIS_CLI:-docker exec redis-master redis-cli}"
REDIS_STOP_CMD="${REDIS_STOP_CMD:-docker stop redis-master redis-readonly}"
REDIS_START_CMD="${REDIS_START_CMD:-docker start redis-master redis-readonly}"
REDIS_PAUSE_CMD="${REDIS_PAUSE_CMD:-docker pause redis-master redis-readonly}"
REDIS_UNPAUSE_CMD="${REDIS_UNPAUSE_CMD:-docker unpause redis-master redis-readonly}"
FAULT_REQUESTS="${FAULT_REQUESTS:-20}"
FAULT_REF_LABEL="${FAULT_REF_LABEL:-cache-off}"
CACHE_VERSION_KEY="products:list:version"
BRANDS="${BRANDS:-200}"
PRODUCTS="${PRODUCTS:-100000}"
LIKES="${LIKES:-1000000}"
USERS="${USERS:-50000}"
CHUNK="${CHUNK:-100000}"
CHUNK_SLEEP="${CHUNK_SLEEP:-0}"
MYSQL_CMD="${MYSQL_CMD:-docker compose -f $ROOT_DIR/docker/infra-compose.yml exec -T mysql mysql -uroot -proot}"

mysql_db() {
    # 비밀번호 경고만 숨기고 나머지 오류는 그대로 보여준다
    $MYSQL_CMD --default-character-set=utf8mb4 "$@" 2> >(grep -v "Using a password" >&2)
}

seed() {
    echo "[seed] $PERF_DB: 브랜드 $BRANDS / 상품 $PRODUCTS / 좋아요 시도 $LIKES (청크 $CHUNK, 쉬는 시간 ${CHUNK_SLEEP}초)"
    echo "CREATE DATABASE IF NOT EXISTS \`$PERF_DB\` DEFAULT CHARACTER SET utf8mb4;" | mysql_db
    {
        echo "SET @perf_brands = $BRANDS, @perf_products = $PRODUCTS, @perf_likes = $LIKES,"
        echo "    @perf_users = $USERS, @perf_chunk = $CHUNK, @perf_sleep = $CHUNK_SLEEP;"
        cat "$PERF_DIR/seed.sql"
    } | mysql_db --batch --skip-column-names "$PERF_DB"
}

# queries.sql을 "이름<TAB>설명<TAB>스냅샷 형식<TAB>SQL" 줄로 변환
list_queries() {
    awk '
        /^-- @name / { name = substr($0, 10); snap = "list"; next }
        /^-- @desc / { desc = substr($0, 10); next }
        /^-- @snapshot / { snap = substr($0, 14); next }
        /^--/ || /^[[:space:]]*$/ { next }
        { sql = $0; sub(/;[[:space:]]*$/, "", sql); print name "\t" desc "\t" snap "\t" sql }
    ' "$PERF_DIR/queries.sql"
}

# "Q6_likes_all=300000 Q7=..." 형식에서 쿼리 이름의 값을 찾는다. 없으면 기본값
lookup() {
    local table="$1" key="$2" default="$3" pair
    for pair in $table; do
        if [ "${pair%%=*}" = "$key" ]; then
            echo "${pair#*=}"
            return
        fi
    done
    echo "$default"
}

with_timeout_hint() {
    # 맨 앞 SELECT에 실행 시간 제한 힌트를 붙인다
    echo "$1" | sed -E "s/^[[:space:]]*SELECT /SELECT \/*+ MAX_EXECUTION_TIME($2) *\/ /I"
}

# 같은 세션에서 쿼리를 count회 실행하고 SHOW PROFILES까지 출력한다(열 이름 포함).
run_session() {
    local sql="$1" count="$2" script="" i
    script="SET profiling_history_size = 100; SET profiling = 1;"
    for ((i = 0; i < count; i++)); do
        script="$script $sql;"
    done
    script="$script SHOW PROFILES;"
    echo "$script" | mysql_db --force --batch "$PERF_DB" 2>/dev/null
}

# run_session 출력에서 서버 실행 시간(초)만 뽑는다. 클라이언트·docker exec 오버헤드는 포함되지 않는다.
profile_seconds() {
    awk -F'\t' 'NF == 3 && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+\.[0-9]+$/ && toupper($3) ~ /^SELECT/ { print $2 }'
}

# run_session 출력에서 첫 결과 집합을 스냅샷 형식으로 바꾼다.
#   list: id, price, created_at (+ likes 형식이면 좋아요 수) / agg: product_id, total
snapshot_rows() {
    local snap="$1"
    awk -F'\t' -v snap="$snap" '
        /^Query_ID\tDuration\tQuery$/ { exit }
        !header_done {
            for (i = 1; i <= NF; i++) { col[$i] = i }
            header_done = 1
            next
        }
        snap == "agg" { print $(col["product_id"]) "\t" $(col["total"]); next }
        { print $(col["id"]) "\t" $(col["price"]) "\t" $(col["created_at"]) }
    '
}

# 목록 스냅샷(id, price, created_at)에 상품별 좋아요 수를 붙인다. 순서는 그대로 유지한다.
append_like_counts() {
    local rows="$1" ids
    ids="$(cut -f1 "$rows" | paste -sd, -)"
    if [ -z "$ids" ]; then
        return
    fi
    echo "SELECT product_id, COUNT(*) FROM product_likes WHERE product_id IN ($ids) GROUP BY product_id;" \
        | mysql_db --batch --skip-column-names "$PERF_DB" \
        | awk -F'\t' 'NR == FNR { likes[$1] = $2; next } { print $0 "\t" (($1 in likes) ? likes[$1] : 0) }' - "$rows"
}

write_snapshot() {
    local file="$1" snap="$2" session="$3" rows
    rows="$(mktemp)"
    snapshot_rows "$snap" < "$session" > "$rows"
    case "$snap" in
        # GROUP BY 결과는 순서가 보장되지 않으므로 product_id로 정렬해 비교한다
        agg) { printf 'product_id\ttotal\n'; sort -n "$rows"; } > "$file" ;;
        list_likes) { printf 'id\tprice\tcreated_at\tlikes\n'; append_like_counts "$rows"; } > "$file" ;;
        *) { printf 'id\tprice\tcreated_at\n'; cat "$rows"; } > "$file" ;;
    esac
    rm -f "$rows"
}

stats_ms() {
    # 입력: 초 단위 값 목록 / 출력: 평균 중앙값 최소 최대 (ms)
    sort -n | awk '{ v[NR] = $1 * 1000; s += v[NR] } END {
        if (NR == 0) { print "-\t-\t-\t-"; exit }
        med = (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2
        printf "%.2f\t%.2f\t%.2f\t%.2f\n", s / NR, med, v[1], v[NR]
    }'
}

env_line() {
    local os cpu mem java_ver
    if [ "$(uname)" = "Darwin" ]; then
        os="macOS $(sw_vers -productVersion 2>/dev/null) ($(uname -m))"
        cpu="$(sysctl -n machdep.cpu.brand_string 2>/dev/null), $(sysctl -n hw.ncpu 2>/dev/null)코어"
        mem="$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 / 1024 ))GB"
    else
        os="$(uname -sr) ($(uname -m))"
        cpu="$(awk -F': ' '/model name/ { print $2; exit }' /proc/cpuinfo 2>/dev/null), $(nproc 2>/dev/null)코어"
        mem="$(awk '/MemTotal/ { printf "%d", $2 / 1024 / 1024 + 0.5 }' /proc/meminfo 2>/dev/null)GB"
    fi
    java_ver="$(java -version 2>&1 | grep -v 'Picked up' | head -1)"
    echo "- 환경: $os / CPU $cpu / 메모리 $mem / MySQL $(echo 'SELECT VERSION();' | mysql_db --batch --skip-column-names) / Java: ${java_ver:-없음}"
    echo "- 디스크 여유: $(disk_free) / buffer pool $(echo 'SELECT @@innodb_buffer_pool_size DIV 1048576;' | mysql_db --batch --skip-column-names)MB"
}

# MySQL 데이터 디렉터리가 이 호스트에 있으면 그 위치, 아니면(도커 등) 스크립트를 실행한 위치의 디스크 여유
disk_free() {
    local dir
    dir="$(echo 'SELECT @@datadir;' | mysql_db --batch --skip-column-names 2>/dev/null)"
    if [ -n "$dir" ] && [ -d "$dir" ] && df -h "$dir" > /dev/null 2>&1; then
        df -h "$dir" | awk 'NR == 2 { print $4 " (MySQL 데이터 디렉터리)" }'
    else
        df -h "$ROOT_DIR" | awk 'NR == 2 { print $4 " (스크립트 실행 위치, MySQL이 컨테이너면 참고값)" }'
    fi
}

data_lines() {
    echo -n "- 데이터: "
    echo "SELECT CONCAT('users ', (SELECT COUNT(*) FROM users), ' / brands ', (SELECT COUNT(*) FROM brands),
          ' (삭제 ', (SELECT COUNT(*) FROM brands WHERE deleted = 1), ') / products ', (SELECT COUNT(*) FROM products),
          ' (미삭제 ', (SELECT COUNT(*) FROM products WHERE deleted = 0), ') / product_likes ',
          (SELECT COUNT(*) FROM product_likes));" | mysql_db --batch --skip-column-names "$PERF_DB"
    echo -n "- 테이블 크기(데이터+인덱스): "
    echo "SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, ' ', ROUND((DATA_LENGTH + INDEX_LENGTH) / 1048576), 'MB',
                 '(인덱스 ', ROUND(INDEX_LENGTH / 1048576), 'MB)') ORDER BY TABLE_NAME SEPARATOR ' / ')
          FROM information_schema.TABLES
          WHERE TABLE_SCHEMA = '$PERF_DB' AND TABLE_NAME IN ('users', 'brands', 'products', 'product_likes');" \
        | mysql_db --batch --skip-column-names "$PERF_DB"
    echo -n "- 인덱스: "
    echo "SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '.', INDEX_NAME, '(', cols, ')') ORDER BY TABLE_NAME, INDEX_NAME SEPARATOR ', ')
          FROM (SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = '$PERF_DB' AND TABLE_NAME IN ('users', 'brands', 'products', 'product_likes')
                GROUP BY TABLE_NAME, INDEX_NAME) t;" | mysql_db --batch --skip-column-names "$PERF_DB"
}

bench() {
    local label="${1:?측정 이름을 지정하세요. 예: baseline}"
    local doc="$ROOT_DIR/docs/week4/query-performance-raw.md"
    local out_dir="$RESULTS_DIR/$label"
    local tmp_rows tmp_explain session
    mkdir -p "$out_dir"
    tmp_rows="$(mktemp)"
    tmp_explain="$(mktemp)"
    session="$(mktemp)"
    trap 'rm -f "$tmp_rows" "$tmp_explain" "$session"' RETURN

    local name desc snap sql timeout runs hinted warm plan result timed_out
    while IFS=$'\t' read -r name desc snap sql; do
        timeout="$(lookup "$QUERY_TIMEOUTS" "$name" "$TIMEOUT_MS")"
        hinted="$(with_timeout_hint "$sql" "$timeout")"
        echo "[bench] $name - $desc (제한 ${timeout}ms)"

        # 첫 테이블(최상위 SELECT)의 EXPLAIN 한 줄을 요약표에 쓴다
        plan="$(echo "EXPLAIN $sql;" | mysql_db --batch --skip-column-names "$PERF_DB" | head -1 \
            | awk -F'\t' '{ printf "%s\t%s\t%s\t%s", $5, $7, $10, $12 }')"

        # 워밍업 1회: 실행 시간으로 느린 쿼리인지 판단하고, 결과 행을 스냅샷으로 저장한다
        run_session "$hinted" 1 > "$session"
        warm="$(profile_seconds < "$session")"
        timed_out="false"
        if [ -z "$warm" ] || awk -v w="$warm" -v t="$timeout" 'BEGIN { exit !(w * 1000 >= t * 0.99) }'; then
            timed_out="true"
            runs="-"
            result="TIMEOUT(>${timeout})\t-\t-\t-"
            echo "# TIMEOUT (>${timeout}ms) - 결과 없음" > "$out_dir/sql-$name.tsv"
        else
            write_snapshot "$out_dir/sql-$name.tsv" "$snap" "$session"
            runs="$RUNS"
            if awk -v w="$warm" -v s="$SLOW_MS" 'BEGIN { exit !(w * 1000 >= s) }' && [ "$SLOW_RUNS" -lt "$runs" ]; then
                runs="$SLOW_RUNS"
            fi
            runs="$(lookup "$QUERY_RUNS" "$name" "$runs")"
            result="$(run_session "$hinted" "$runs" | profile_seconds | stats_ms)"
        fi

        printf "| %s | %s | %s | %s | %s |\n" "$name" "$desc" "$runs" \
            "$(printf '%b' "$result" | sed 's/\t/ | /g')" "$(echo "$plan" | sed 's/\t/ | /g')" >> "$tmp_rows"

        {
            echo "-- ==================== $name: $desc"
            echo "$sql;"
            echo
            echo "-- EXPLAIN"
            echo "EXPLAIN $sql;" | mysql_db --table "$PERF_DB"
            if [ "$timed_out" = "true" ]; then
                echo "-- EXPLAIN ANALYZE: 생략 (${timeout}ms 안에 끝나지 않음)"
            else
                echo "-- EXPLAIN ANALYZE"
                echo "EXPLAIN ANALYZE $sql;" | mysql_db --batch --raw --skip-column-names "$PERF_DB"
            fi
            echo
        } >> "$tmp_explain"
    done < <(list_queries)

    {
        echo
        echo "## 측정: $label ($(date '+%Y-%m-%d %H:%M'))"
        echo
        env_line
        echo "- 방식: 쿼리별 워밍업 1회 후 '실행 횟수'만큼 실행, SHOW PROFILES의 서버 실행 시간(ms)."
        echo "  기본 제한 ${TIMEOUT_MS}ms, 워밍업이 ${SLOW_MS}ms 이상이면 ${SLOW_RUNS}회만 실행. 쿼리별 제한: ${QUERY_TIMEOUTS:-없음}"
        echo "- 결과 스냅샷: \`docs/week4/perf-results/$label/sql-*.tsv\`"
        data_lines
        echo
        echo '| 쿼리 | 설명 | 실행 횟수 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |'
        echo '| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |'
        cat "$tmp_rows"
        echo
        echo '<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>'
        echo
        echo '```text'
        cat "$tmp_explain"
        echo '```'
        echo
        echo '</details>'
    } >> "$doc"

    echo
    echo "[bench] 완료: docs/week4/query-performance-raw.md 에 '측정: $label' 섹션 추가, 스냅샷 ${out_dir#$ROOT_DIR/}/"
}

# 두 측정의 결과 스냅샷을 비교한다. 결과가 다른 쿼리·요청은 성능 비교에서 제외해야 한다.
compare() {
    local base="${1:?기준 측정 이름}" target="${2:?비교 측정 이름}"
    local base_dir="$RESULTS_DIR/$base" target_dir="$RESULTS_DIR/$target"
    local file name differ=0 skipped=0 same=0
    [ -d "$base_dir" ] || { echo "스냅샷 없음: ${base_dir#$ROOT_DIR/}" >&2; exit 1; }
    [ -d "$target_dir" ] || { echo "스냅샷 없음: ${target_dir#$ROOT_DIR/}" >&2; exit 1; }

    for file in "$base_dir"/*.tsv; do
        name="$(basename "$file" .tsv)"
        if [ ! -f "$target_dir/$name.tsv" ]; then
            echo "[없음] $name: $target 에 스냅샷이 없음"
            differ=$((differ + 1))
        elif grep -q '^# TIMEOUT' "$file" "$target_dir/$name.tsv"; then
            echo "[비교 불가] $name: 한쪽 이상이 TIMEOUT이라 결과가 없음"
            skipped=$((skipped + 1))
        elif cmp -s "$file" "$target_dir/$name.tsv"; then
            same=$((same + 1))
        else
            echo "[다름] $name (< $base / > $target)"
            diff "$file" "$target_dir/$name.tsv" | grep "^[<>]" | head -20 || true
            differ=$((differ + 1))
        fi
    done
    for file in "$target_dir"/*.tsv; do
        name="$(basename "$file" .tsv)"
        if [ ! -f "$base_dir/$name.tsv" ]; then
            echo "[없음] $name: $base 에 스냅샷이 없음"
            differ=$((differ + 1))
        fi
    done
    echo "[compare] 같음 $same / 다름 $differ / 비교 불가 $skipped"
    [ "$differ" -eq 0 ]
}

# ---------------------------------------------------------------- api-bench
# 측정 대상 요청: 이름<TAB>설명<TAB>경로
API_REQUESTS_LIST="R1\t브랜드 1 가격순 1페이지 (과제 측정 대상)\t/api/v1/products?brandId=1&sort=price_asc&page=0&size=20
R2\t브랜드 200 가격순 1페이지\t/api/v1/products?brandId=200&sort=price_asc&page=0&size=20
R3\t브랜드 200 좋아요순 1페이지\t/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20
R4\t상품 1 상세\t/api/v1/products/1"

APP_PID=""

stop_app() {
    if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
        echo "[app] 앱 종료 (pid $APP_PID)"
        kill "$APP_PID" 2>/dev/null || true
        local i
        for ((i = 0; i < 30; i++)); do
            kill -0 "$APP_PID" 2>/dev/null || break
            sleep 1
        done
        kill -9 "$APP_PID" 2>/dev/null || true
    fi
    APP_PID=""
}

app_args() {
    # 설정 파일은 바꾸지 않고 local 프로필 위에 명령행 인자로만 덮어쓴다. 인자: [DB 이름] [ddl-auto]
    #   ddl-auto=none: local 기본값 create면 기동 시 측정 DB 테이블이 지워진다 (ddl-check만 create로 띄운다)
    #   username/password: compose의 application 계정은 loopers DB 권한만 있어 loopers_perf에 접근하지 못한다
    #   show-sql=false: SQL 콘솔 출력 비용을 측정에서 뺀다
    # 개선 전후 비교는 이 인자 그대로(같은 계정·옵션) 측정해야 한다.
    local db="${1:-$PERF_DB}" ddl="${2:-none}"
    echo "--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=$ddl --spring.jpa.show-sql=false"
    echo "--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/$db"
    echo "--datasource.mysql-jpa.main.username=$APP_DB_USER --datasource.mysql-jpa.main.password=$APP_DB_PASSWORD"
    echo "--server.port=$APP_PORT"
}

start_app() {
    local log="$1" db="${2:-$PERF_DB}" ddl="${3:-none}" jar="$APP_JAR" i code
    if (echo > "/dev/tcp/127.0.0.1/$APP_PORT") 2>/dev/null; then
        echo "[app] $APP_PORT 포트가 이미 사용 중이다. 실행 중인 앱을 끄거나 APP_PORT를 바꿔야 한다." >&2
        exit 1
    fi
    if [ -z "$jar" ]; then
        echo "[app] bootJar 빌드"
        (cd "$ROOT_DIR" && ./gradlew :apps:commerce-api:bootJar -x test -q)
        jar="$(ls -t "$ROOT_DIR"/apps/commerce-api/build/libs/*.jar | grep -v -- "-plain" | head -1)"
    fi
    echo "[app] 앱 기동 (DB $db, ddl-auto=$ddl): ${jar#$ROOT_DIR/}"
    # shellcheck disable=SC2046
    java -jar "$jar" $(app_args "$db" "$ddl") > "$log" 2>&1 &
    APP_PID=$!
    trap stop_app EXIT INT TERM
    for ((i = 0; i < APP_START_TIMEOUT; i++)); do
        if ! kill -0 "$APP_PID" 2>/dev/null; then
            echo "[app] 앱이 기동 중 종료됐다. 로그 마지막 부분:" >&2
            tail -30 "$log" >&2
            exit 1
        fi
        code="$(curl -s -o /dev/null -w '%{http_code}' -H "X-USER-ID: $API_USER_ID" \
            "http://127.0.0.1:$APP_PORT/api/v1/products/1" || true)"
        if [ "$code" != "000" ]; then
            echo "[app] 앱 준비 완료 (${i}초, 첫 응답 $code)"
            return
        fi
        sleep 1
    done
    echo "[app] ${APP_START_TIMEOUT}초 안에 앱이 응답하지 않았다." >&2
    exit 1
}

# 요청 1회: 본문은 $2 파일에, "상태코드 초" 를 출력한다
one_request() {
    local out
    out="$(curl -s -o "$2" -w '%{http_code} %{time_total}' --max-time "$CURL_MAX_TIME" -H "X-USER-ID: $API_USER_ID" \
        "http://127.0.0.1:$APP_PORT$1")" || true
    echo "${out:-000 0}"
}

percentiles_ms() {
    # 입력: 초 단위 값 목록 / 출력: p50 p95 p99 최대 (ms, nearest-rank)
    sort -n | awk '{ v[NR] = $1 * 1000 } END {
        if (NR == 0) { print "-\t-\t-\t-"; exit }
        split("0.50 0.95 0.99", q, " ")
        for (k = 1; k <= 3; k++) { r = int(q[k] * NR); if (r < q[k] * NR) r++; if (r < 1) r = 1; p[k] = v[r] }
        printf "%.1f\t%.1f\t%.1f\t%.1f\n", p[1], p[2], p[3], v[NR]
    }'
}

# 응답 본문(JSON)에서 상품별 id·price·likeCount를 순서대로 뽑는다 (목록·상세 공통)
response_snapshot() {
    printf 'id\tprice\tlikeCount\n'
    { grep -oE '"(id|price|likeCount)"[[:space:]]*:[[:space:]]*[0-9]+' "$1" || true; } | tr -d ' ' | awk -F: '
        $1 == "\"id\"" { id = $2 }
        $1 == "\"price\"" { price = $2 }
        $1 == "\"likeCount\"" { print id "\t" price "\t" $2 }
    '
}

# ---------------------------------------------------------------- 캐시 측정용 Redis 도구 (측정용 키만 다룬다, FLUSHALL·KEYS 금지)
redis_cmd() {
    $REDIS_CLI "$@"
}

# 버전 값과 목록 키 수(SCAN으로 셈). 출력: "version=N keys=M"
redis_state() {
    local version keys
    version="$(redis_cmd GET "$CACHE_VERSION_KEY" 2>/dev/null || echo '?')"
    keys="$(redis_cmd --scan --pattern 'products:list:v[0-9]*' 2>/dev/null | grep -c . || true)"
    echo "version=${version:-(없음)} keys=${keys:-?}"
}

# INFO commandstats를 "명령<TAB>호출 수"로 출력한다
redis_cmdstats() {
    redis_cmd INFO commandstats 2>/dev/null | tr -d '\r' \
        | awk -F'[:,=]' '/^cmdstat_/ { sub(/^cmdstat_/, "", $1); print $1 "\t" $3 }'
}

# 두 commandstats의 차이를 요청 수로 나눠 "GET g / SET s / INCR i / 기타 o"로 출력한다. $4 = 도구가 보낸 INCR 수(빼야 함)
redis_cmd_per_request() {
    local before="$1" after="$2" n="$3" tool_incr="$4"
    awk -F'\t' -v n="$n" -v tool="$tool_incr" '
        NR == FNR { b[$1] = $2; next }
        { d = $2 - b[$1]; if ($1 == "incr") d -= tool
          if ($1 == "get" || $1 == "set" || $1 == "incr") v[$1] += d
          else if ($1 != "info" && $1 != "scan" && $1 != "ping") other += d }
        END { printf "GET %.1f / SET %.1f / INCR %.1f / 기타 %.1f", v["get"] / n, v["set"] / n, v["incr"] / n, other / n }
    ' "$before" "$after"
}

bump_cache_version() {
    redis_cmd INCR "$CACHE_VERSION_KEY" > /dev/null
}

# 앱 커넥션(기본 DB가 측정 DB)에서 5초 넘게 실행 중인 쿼리를 끊는다
kill_app_queries() {
    local id
    for id in $(echo "SELECT ID FROM information_schema.PROCESSLIST
                      WHERE DB = '$PERF_DB' AND COMMAND = 'Query' AND TIME >= 5;" | perf_schema); do
        echo "KILL QUERY $id;" | perf_schema || true
        echo "[api-bench] 앱 쿼리 중단: KILL QUERY $id"
    done
}

perf_schema() {
    # 측정용 클라이언트는 기본 DB 없이 접속하므로 SCHEMA_NAME이 NULL이다. 앱 커넥션만 $PERF_DB로 집계된다.
    mysql_db --batch --skip-column-names
}

api_bench() {
    local label="${1:?측정 이름을 지정하세요. 예: baseline}"
    local doc="$ROOT_DIR/docs/week4/query-performance-raw.md"
    local out_dir="$RESULTS_DIR/$label"
    local work log body times digests rows details
    mkdir -p "$out_dir"
    work="$(mktemp -d)"
    log="$work/app.log"
    body="$work/body.json"
    rows="$work/rows.md"
    details="$work/details.txt"
    : > "$rows"
    : > "$details"
    case "$API_CACHE_MODE" in
        off | hit | miss) ;;
        *) echo "[api-bench] API_CACHE_MODE는 off|hit|miss 중 하나다: $API_CACHE_MODE" >&2; exit 1 ;;
    esac

    local products_before products_after redis_before="" redis_after="" stats_before stats_after tool_incr list_select redis_per
    products_before="$(count_products)"
    echo "[api-bench] 시작 전 $PERF_DB.products: $products_before"
    if [ "${products_before:-0}" -eq 0 ]; then
        echo "[api-bench] $PERF_DB.products가 비어 있다. scripts/perf/run.sh seed를 먼저 실행해야 한다." >&2
        exit 1
    fi

    start_app "$log"

    local name desc path first code secs warm_n n i ok fail stats sql_total sql_select sql_ms failed=0
    if [ "$API_CACHE_MODE" != "off" ]; then
        redis_before="$(redis_state)"
        echo "[api-bench] 캐시 모드 $API_CACHE_MODE, 시작 전 Redis: $redis_before"
    fi
    stats_before="$work/cmdstats.before"
    stats_after="$work/cmdstats.after"

    while IFS=$'\t' read -r name desc path; do
        if [ -n "$API_ONLY" ] && ! printf ' %s ' "$API_ONLY" | grep -q " $name "; then
            continue
        fi
        echo "[api-bench] $name $path"
        times="$work/$name.times"
        digests="$work/$name.digests"
        : > "$times"
        ok=0
        fail=0

        CURL_MAX_TIME="$(lookup "$API_REQUEST_TIMEOUTS" "$name" 900)"
        [ "$API_CACHE_MODE" = "miss" ] && bump_cache_version
        first="$(one_request "$path" "$body")"
        code="${first%% *}"
        secs="${first#* }"
        if [ "$code" = "000" ] && awk -v s="$secs" -v t="$CURL_MAX_TIME" 'BEGIN { exit !(s >= t - 0.5) }'; then
            # 요청별 제한 시간 초과: 측정 실패가 아니라 "제한 초과"로 기록하고 이 요청은 더 보내지 않는다.
            # 클라이언트가 끊어도 앱의 SQL은 계속 돌기 때문에 남은 쿼리를 끊어 다음 측정에 영향이 없게 한다.
            echo "[api-bench] $name: ${CURL_MAX_TIME}초 안에 응답 없음 → TIMEOUT으로 기록"
            kill_app_queries
            echo "# TIMEOUT (>${CURL_MAX_TIME}s) - 응답 없음" > "$out_dir/api-$name.tsv"
            printf "| %s | %s | \`%s\` | %s | %s | TIMEOUT(>%ss) | - | - | - | - | - | - | - |\n" \
                "$name" "$desc" "$path" 1 0 "$CURL_MAX_TIME" >> "$rows"
            {
                echo "-- $name $path: ${CURL_MAX_TIME}초 제한 초과로 측정하지 않음 (SQL 집계 없음)"
                echo
            } >> "$details"
            CURL_MAX_TIME=900
            continue
        fi
        [ "$code" = "200" ] || fail=$((fail + 1))
        if awk -v s="$secs" -v t="$API_SLOW_MS" 'BEGIN { exit !(s * 1000 > t) }'; then
            warm_n="$API_SLOW_WARMUP"
            n="$API_SLOW_REQUESTS"
        else
            warm_n="$API_WARMUP"
            n="$API_REQUESTS"
        fi
        for ((i = 1; i < warm_n; i++)); do
            [ "$API_CACHE_MODE" = "miss" ] && bump_cache_version
            code="$(one_request "$path" "$body" | cut -d' ' -f1)"
            [ "$code" = "200" ] || fail=$((fail + 1))
        done

        echo "TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;" | perf_schema
        tool_incr=0
        [ "$API_CACHE_MODE" != "off" ] && redis_cmdstats > "$stats_before"
        for ((i = 0; i < n; i++)); do
            if [ "$API_CACHE_MODE" = "miss" ]; then
                # 측정 구간 밖에서 버전을 올려 다음 요청이 반드시 새 키(miss)를 보게 한다
                bump_cache_version
                tool_incr=$((tool_incr + 1))
            fi
            first="$(one_request "$path" "$body")"
            code="${first%% *}"
            if [ "$code" = "200" ]; then
                ok=$((ok + 1))
            else
                fail=$((fail + 1))
            fi
            echo "${first#* }" >> "$times"
        done
        [ "$API_CACHE_MODE" != "off" ] && redis_cmdstats > "$stats_after"
        echo "SELECT COUNT_STAR, ROUND(SUM_TIMER_WAIT / 1000000000, 2), LEFT(REPLACE(DIGEST_TEXT, '\`', ''), 160)
              FROM performance_schema.events_statements_summary_by_digest
              WHERE SCHEMA_NAME = '$PERF_DB' ORDER BY COUNT_STAR DESC, SUM_TIMER_WAIT DESC;" | perf_schema > "$digests"

        response_snapshot "$body" > "$out_dir/api-$name.tsv"
        stats="$(percentiles_ms < "$times")"
        sql_total="$(awk -F'\t' -v n="$n" '{ c += $1 } END { printf "%.1f", c / n }' "$digests")"
        sql_select="$(awk -F'\t' -v n="$n" 'toupper($3) ~ /^SELECT/ { c += $1 } END { printf "%.1f", c / n }' "$digests")"
        sql_ms="$(awk -F'\t' -v n="$n" '{ t += $2 } END { printf "%.1f", t / n }' "$digests")"
        [ "$fail" -eq 0 ] || failed=$((failed + 1))

        if [ "$API_CACHE_MODE" = "off" ]; then
            printf "| %s | %s | \`%s\` | %s | %s | %s | %s/%s | %s | %s | %s |\n" "$name" "$desc" "$path" "$warm_n" "$n" \
                "$(echo "$stats" | sed 's/\t/ | /g')" "$ok" "$((n))" "$sql_total" "$sql_select" "$sql_ms" >> "$rows"
        else
            # 목록 검색 SELECT(SELECT p.* FROM products p ...)가 측정 구간에 0이면 캐시 hit였다는 증거다
            list_select="$(awk -F'\t' -v n="$n" '$3 ~ /^SELECT p \. \* FROM products p WHERE/ { c += $1 } END { printf "%.1f", c / n }' "$digests")"
            redis_per="$(redis_cmd_per_request "$stats_before" "$stats_after" "$n" "$tool_incr")"
            printf "| %s | %s | \`%s\` | %s | %s | %s | %s/%s | %s | %s | %s | %s | %s |\n" "$name" "$desc" "$path" "$warm_n" "$n" \
                "$(echo "$stats" | sed 's/\t/ | /g')" "$ok" "$((n))" "$sql_total" "$sql_select" "$sql_ms" \
                "$list_select" "$redis_per" >> "$rows"
        fi
        CURL_MAX_TIME=900
        if [ "$fail" -ne 0 ]; then
            echo "[api-bench] $name: 200이 아닌 응답 ${fail}건 (워밍업 포함). 측정 실패로 본다." >&2
        fi
        {
            echo "-- $name $path (요청 ${n}회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)"
            cat "$digests"
            echo
        } >> "$details"
    done < <(printf '%b\n' "$API_REQUESTS_LIST")

    stop_app
    trap - EXIT INT TERM
    if [ "$API_CACHE_MODE" != "off" ]; then
        redis_after="$(redis_state)"
        echo "[api-bench] 종료 후 Redis: $redis_after"
    fi

    # 앱 기동이 측정 데이터를 지우지 않았는지 확인한다 (ddl-auto 덮어쓰기가 빠지면 create로 테이블이 다시 만들어진다)
    products_after="$(count_products)"
    echo "[api-bench] 종료 후 $PERF_DB.products: $products_after"
    if [ "$products_after" != "$products_before" ]; then
        echo "[api-bench] 측정 중 products 건수가 바뀌었다 ($products_before → $products_after). 이 측정은 실패로 본다." >&2
        failed=$((failed + 1))
    fi

    {
        echo
        if [ "$failed" -ne 0 ]; then
            echo "## API 측정: $label ($(date '+%Y-%m-%d %H:%M')) — 실패: 200이 아닌 응답 또는 데이터 변경, 비교에 쓰지 않는다"
        else
            echo "## API 측정: $label ($(date '+%Y-%m-%d %H:%M'))"
        fi
        echo
        env_line
        echo "- 앱: commerce-api local 프로필 + 명령행 인자 \`$(app_args | tr '\n' ' ' | sed "s/password=[^ ]*/password=***/")\`"
        echo "  개선 후(after) 측정도 같은 계정(${APP_DB_USER})·옵션으로 실행해야 비교할 수 있다."
        echo "- 데이터 보존 확인: $PERF_DB.products 시작 전 $products_before / 종료 후 $products_after"
        echo "- 방식: curl 순차 1개, X-USER-ID: $API_USER_ID. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank)."
        echo "  첫 요청이 ${API_SLOW_MS}ms를 넘으면 워밍업 ${API_SLOW_WARMUP}회 + 측정 ${API_SLOW_REQUESTS}회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다."
        echo "- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=$PERF_DB(앱 커넥션) 실행 수를 요청 수로 나눈 값"
        echo "- 응답 스냅샷: \`docs/week4/perf-results/$label/api-*.tsv\` (마지막 응답의 id·price·likeCount)"
        if [ "$API_CACHE_MODE" != "off" ]; then
            echo "- 캐시 모드: $API_CACHE_MODE $( [ "$API_CACHE_MODE" = "miss" ] && echo "(측정·워밍업 요청마다 직전에 INCR $CACHE_VERSION_KEY, 요청 시간 밖)" || echo "(워밍업이 채운 캐시를 읽음)")"
            echo "- Redis 상태: 시작 전 $redis_before / 종료 후 $redis_after (목록 키 수는 SCAN으로 셈)"
            echo "- 요청당 Redis 명령: 측정 구간 전후 INFO commandstats 차이 ÷ 표본 (도구가 보낸 INCR·INFO·SCAN 제외)"
        fi
        data_lines
        echo
        if [ "$API_CACHE_MODE" = "off" ]; then
            echo '| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) |'
            echo '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |'
        else
            echo '| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) | 요청당 목록 검색 SELECT | 요청당 Redis 명령 |'
            echo '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |'
        fi
        cat "$rows"
        echo
        echo '<details><summary>요청별 SQL digest</summary>'
        echo
        echo '```text'
        cat "$details"
        echo '```'
        echo
        echo '</details>'
    } >> "$doc"

    rm -rf "$work"
    echo
    echo "[api-bench] 완료: docs/week4/query-performance-raw.md 에 'API 측정: $label' 섹션 추가, 스냅샷 ${out_dir#$ROOT_DIR/}/"
    if [ "$failed" -ne 0 ]; then
        echo "[api-bench] 실패 항목이 ${failed}개다(200이 아닌 응답 또는 데이터 변경). 이 측정은 실패로 본다." >&2
        exit 1
    fi
}

count_products() {
    echo "SELECT COUNT(*) FROM \`$PERF_DB\`.products;" | mysql_db --batch --skip-column-names 2>/dev/null || echo 0
}

# ---------------------------------------------------------------- ddl-check
DDL_TABLES="users brands products product_likes"
DDL_TABLES_SQL="'users', 'brands', 'products', 'product_likes'"

# 컬럼(이름·타입·NULL·기본값·extra)과 인덱스(유일 여부·컬럼 목록·정렬 방향)를 이름과 무관하게 비교할 수 있는 형태로 출력한다.
# 컬럼 순서와 유일 제약 이름은 결과·실행 계획에 영향이 없어 비교에서 뺀다.
schema_signature() {
    local db="$1"
    echo "SELECT CONCAT('column ', TABLE_NAME, '.', COLUMN_NAME, ' ', COLUMN_TYPE,
                  IF(IS_NULLABLE = 'NO', ' NOT NULL', ' NULL'),
                  IF(COLUMN_DEFAULT IS NULL, '', CONCAT(' DEFAULT ', COLUMN_DEFAULT)), IF(EXTRA = '', '', CONCAT(' ', EXTRA)))
          FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = '$db' AND TABLE_NAME IN ($DDL_TABLES_SQL)
          UNION ALL
          SELECT CONCAT('index ', TABLE_NAME, IF(INDEX_NAME = 'PRIMARY', ' PRIMARY', IF(NON_UNIQUE = 0, ' UNIQUE', ' INDEX')),
                  ' (', GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(COLLATION = 'D', ' DESC', '')) ORDER BY SEQ_IN_INDEX), ')')
          FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA = '$db' AND TABLE_NAME IN ($DDL_TABLES_SQL)
          GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE;" | mysql_db --batch --skip-column-names | sort
}

show_create_tables() {
    local db="$1" table
    for table in $DDL_TABLES; do
        echo "SHOW CREATE TABLE \`$db\`.\`$table\`;" | mysql_db --batch --raw --skip-column-names | cut -f2-
        echo
    done
}

ddl_check() {
    local app_db="loopers_ddlcheck_app" seed_db="loopers_ddlcheck_seed" work log result=0
    work="$(mktemp -d)"
    log="$work/app.log"
    echo "[ddl-check] 임시 DB $app_db(앱 ddl-auto=create) / $seed_db(seed.sql CREATE TABLE)를 만든다"
    echo "DROP DATABASE IF EXISTS \`$app_db\`; CREATE DATABASE \`$app_db\` DEFAULT CHARACTER SET utf8mb4;
          DROP DATABASE IF EXISTS \`$seed_db\`; CREATE DATABASE \`$seed_db\` DEFAULT CHARACTER SET utf8mb4;" | mysql_db

    # seed.sql에서 CREATE TABLE IF NOT EXISTS 문만 뽑아 빈 DB에 실행한다
    awk '/^CREATE TABLE IF NOT EXISTS/ { on = 1 } on { print } on && /;[[:space:]]*$/ { on = 0 }' "$PERF_DIR/seed.sql" \
        | mysql_db "$seed_db"

    start_app "$log" "$app_db" "create"
    stop_app
    trap - EXIT INT TERM

    echo "==================== 앱(Hibernate ddl-auto=create)"
    show_create_tables "$app_db"
    echo "==================== seed.sql"
    show_create_tables "$seed_db"

    schema_signature "$app_db" > "$work/app.sig"
    schema_signature "$seed_db" > "$work/seed.sig"
    echo "==================== 비교 (< 앱 / > seed.sql, 컬럼 순서·유일 제약 이름 제외)"
    if [ ! -s "$work/app.sig" ]; then
        echo "[ddl-check] 앱 DB에 대상 테이블이 없다. 앱 로그 마지막 부분:" >&2
        tail -30 "$log" >&2
        result=1
    elif diff "$work/app.sig" "$work/seed.sig"; then
        echo "[ddl-check] 차이 없음 ($DDL_TABLES)"
    else
        echo "[ddl-check] 차이가 있다. seed.sql의 CREATE TABLE을 앱 스키마에 맞춰야 한다." >&2
        result=1
    fi

    echo "DROP DATABASE IF EXISTS \`$app_db\`; DROP DATABASE IF EXISTS \`$seed_db\`;" | mysql_db
    rm -rf "$work"
    return "$result"
}

# ---------------------------------------------------------------- cache-fault
FAULT_RECOVER_CMD=""

fault_cleanup() {
    if [ -n "$FAULT_RECOVER_CMD" ]; then
        echo "[cache-fault] Redis 복구: $FAULT_RECOVER_CMD"
        $FAULT_RECOVER_CMD > /dev/null 2>&1 || true
        FAULT_RECOVER_CMD=""
    fi
    stop_app
}

wait_redis() {
    local i
    for ((i = 0; i < 60; i++)); do
        if [ "$(redis_cmd PING 2>/dev/null)" = "PONG" ]; then
            echo "$i"
            return 0
        fi
        sleep 1
    done
    echo "-1"
    return 1
}

# 응답 본문이 기준 스냅샷과 같은지 (id·price·likeCount). 빈 목록·가격 0이면 다르다고 본다.
same_as_ref() {
    local body="$1" ref="$2"
    [ -f "$ref" ] && cmp -s <(response_snapshot "$body") "$ref"
}

cache_fault() {
    local label="${1:?측정 이름을 지정하세요. 예: cache-redis-stop}" mode="${2:?stop|pause}"
    local doc="$ROOT_DIR/docs/week4/query-performance-raw.md"
    local out_dir="$RESULTS_DIR/$label" ref="$RESULTS_DIR/$FAULT_REF_LABEL/api-R1.tsv"
    local path="/api/v1/products?brandId=1&sort=price_asc&page=0&size=20"
    local work log body times codes i r code same=0 ok=0 fault_cmd recover_cmd
    local state_before state_after_recover warn_lines redis_wait refill="" refill_tries=0 key version stats
    case "$mode" in
        stop) fault_cmd="$REDIS_STOP_CMD"; recover_cmd="$REDIS_START_CMD" ;;
        pause) fault_cmd="$REDIS_PAUSE_CMD"; recover_cmd="$REDIS_UNPAUSE_CMD" ;;
        *) echo "[cache-fault] 모드는 stop|pause: $mode" >&2; exit 1 ;;
    esac
    [ -f "$ref" ] || { echo "[cache-fault] 기준 스냅샷이 없다: ${ref#$ROOT_DIR/}" >&2; exit 1; }
    mkdir -p "$out_dir"
    work="$(mktemp -d)"
    log="$work/app.log"
    body="$work/body.json"
    times="$work/times"
    codes="$work/codes"
    : > "$times"
    : > "$codes"
    [ "$(count_products)" -gt 0 ] || { echo "[cache-fault] $PERF_DB.products가 비어 있다." >&2; exit 1; }

    start_app "$log"
    trap fault_cleanup EXIT INT TERM
    # 장애 전: 정상 상태에서 R1을 한 번 보내 캐시를 채워 둔다
    one_request "$path" "$body" > /dev/null
    state_before="$(redis_state)"
    echo "[cache-fault] 장애 전 Redis: $state_before"

    echo "[cache-fault] 장애 주입: $fault_cmd"
    FAULT_RECOVER_CMD="$recover_cmd"
    $fault_cmd > /dev/null
    for ((i = 0; i < FAULT_REQUESTS; i++)); do
        r="$(one_request "$path" "$body")"
        code="${r%% *}"
        echo "$code" >> "$codes"
        echo "${r#* }" >> "$times"
        [ "$code" = "200" ] && ok=$((ok + 1))
        if [ "$code" = "200" ] && same_as_ref "$body" "$ref"; then
            same=$((same + 1))
        fi
    done
    response_snapshot "$body" > "$out_dir/api-R1.tsv"
    stats="$(percentiles_ms < "$times")"

    echo "[cache-fault] 복구: $recover_cmd"
    $recover_cmd > /dev/null
    FAULT_RECOVER_CMD=""
    redis_wait="$(wait_redis || true)"
    # 복구 후: 요청을 보내며 현재 버전의 R1 목록 키가 다시 생기는지 확인한다(최대 10회)
    for ((i = 1; i <= 10; i++)); do
        r="$(one_request "$path" "$body")"
        version="$(redis_cmd GET "$CACHE_VERSION_KEY" 2>/dev/null)"
        key="products:list:v${version:-0}:brand=1:sort=price_asc:page=0:size=20"
        if [ "$(redis_cmd EXISTS "$key" 2>/dev/null)" = "1" ]; then
            refill="${i}번째 요청 후 재채움 (${r%% *}, ${r#* }초, 키 $key)"
            refill_tries=$i
            break
        fi
        sleep 0.5
    done
    [ -n "$refill" ] || refill="10회 안에 재채움 확인 못 함"
    state_after_recover="$(redis_state)"
    warn_lines="$(grep -c '상품 목록 캐시' "$log" || true)"
    stop_app
    trap - EXIT INT TERM

    {
        echo
        echo "## 캐시 장애 측정: $label (Redis $mode, $(date '+%Y-%m-%d %H:%M'))"
        echo
        env_line
        echo "- 앱: commerce-api local 프로필 + 명령행 인자 \`$(app_args | tr '\n' ' ' | sed "s/password=[^ ]*/password=***/")\`"
        echo "- 방식: 정상 상태에서 R1 1회로 캐시를 채운 뒤 \`$fault_cmd\` → R1을 워밍업 없이 ${FAULT_REQUESTS}회 순차 요청 → \`$recover_cmd\`"
        echo "- 응답 비교 기준: \`perf-results/$FAULT_REF_LABEL/api-R1.tsv\` (id·price·likeCount 순서, 빈 목록·가격 0이면 다름)"
        echo "- Redis 상태: 장애 전 $state_before / 복구 후 $state_after_recover"
        echo "- 복구: Redis 응답까지 ${redis_wait}초, $refill"
        echo "- 앱 로그의 캐시 경고(\"상품 목록 캐시\") 줄 수: $warn_lines"
        data_lines
        echo
        echo '| 상태 | 요청 | 200 응답 | 기준과 같은 응답 | p50 | p95 | p99 | 최대 | 응답 코드 |'
        echo '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |'
        printf "| Redis %s | %s | %s | %s | %s | %s |\n" "$mode" "$FAULT_REQUESTS" "$ok" "$same" \
            "$(echo "$stats" | sed 's/\t/ | /g')" "$(sort "$codes" | uniq -c | awk '{ printf "%s×%s ", $2, $1 }')"
    } >> "$doc"
    rm -rf "$work"
    echo "[cache-fault] 완료: 'Redis $mode' 200 응답 $ok/$FAULT_REQUESTS, 기준과 같음 $same, p50/p95/p99/최대 $(echo "$stats" | tr '\t' '/')ms, $refill"
}

case "${1:-}" in
    seed) seed ;;
    bench) shift; bench "$@" ;;
    compare) shift; compare "$@" ;;
    api-bench) shift; api_bench "$@" ;;
    ddl-check) ddl_check ;;
    cache-fault) shift; cache_fault "$@" ;;
    *)
        sed -n '2,/^set -euo/p' "$0" | sed '$d'
        exit 1
        ;;
esac

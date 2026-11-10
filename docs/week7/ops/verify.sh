#!/usr/bin/env bash
# 운영 SQL 확인: 새 MySQL 컨테이너에 schema.sql + 상태별 데이터(seed.sql)를 넣고 observe.sql · retention.sql · release-lock.sql을 실행한다.
# 스크립트가 만든 컨테이너만 지운다. 실행: 저장소 루트에서  bash docs/week7/ops/verify.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
NAME="w7-ops-$(date +%Y%m%d-%H%M%S)"
OUT="$HERE/results/$NAME.txt"
mkdir -p "$HERE/results"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$NAME" -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ops mysql:8.0 >/dev/null  # 서버 기본 collation = 로컬 infra(docker/infra-compose.yml)와 같다
for _ in $(seq 1 90); do docker exec "$NAME" mysql -uroot -proot -e "SELECT 1" ops >/dev/null 2>&1 && break; sleep 1; done
sql() { docker exec -i "$NAME" mysql -uroot -proot --default-character-set=utf8mb4 -t ops 2>/dev/null; }
grep -v 'DROP TABLE' "$HERE/../schema.sql" | sql
sql < "$HERE/seed.sql"
{
  echo "# $NAME — MySQL $(echo 'SELECT VERSION()' | docker exec -i "$NAME" mysql -uroot -proot -N ops 2>/dev/null) · UTC 기준"
  echo; echo "## observe.sql"; sed "s/EVENT_ID/e-06/" "$HERE/observe.sql" | sql
  echo; echo "## O-2 실행 계획"; echo "EXPLAIN SELECT COUNT(*) FROM order_outbox WHERE status IN ('PENDING','SENDING') AND next_attempt_at <= UTC_TIMESTAMP(6);" | sql
  echo; echo "## retention.sql (영향 행)"; (cat "$HERE/retention.sql"; echo "SELECT ROW_COUNT() AS deleted;") | sql
  echo "남은 행:"; echo "SELECT event_id, status, published_at FROM order_outbox WHERE event_id IN ('e-09','e-10','e-11') ORDER BY id;" | sql
  echo; echo "## release-lock.sql"; (cat "$HERE/release-lock.sql"; echo "SELECT name, lock_until > UTC_TIMESTAMP(3) AS held_now FROM shedlock;") | sql
} | tee "$OUT"
echo "[ops] 결과 $OUT"

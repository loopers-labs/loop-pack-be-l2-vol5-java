#!/usr/bin/env python3
"""
상품 목록 조회 쿼리 실행 시간 측정 — PostgreSQL 판 (docs/week4/product-list-performance.md).

측정 방식은 bench.py(MySQL)와 같다.
케이스마다 EXPLAIN ANALYZE 를 워밍업 N회(버림) + 측정 M회, 한 번에 하나씩 순차 실행한다.
실행 시간은 맨 위 노드의 `actual time=시작..끝` 의 끝 값(ms, 서버 안에서 잰 값)이다.

케이스 SQL 은 문서의 `### <ID>` 아래 ```sql 블록(MySQL 에서 잡은 실제 SQL)을 읽어 PostgreSQL 형태로 바꾼다.
local-pg 프로필 앱의 statement 로그로 확인한 결과, PostgreSQL 에서 실제로 나가는 SQL 은 페이징만 다르다.
  MySQL      : ... limit 4950,50
  PostgreSQL : ... offset 4950 rows fetch first 50 rows only
앱은 brand_id·offset·limit 을 바인딩 변수($1..)로 보내지만, 여기서는 bench.py 와 같이 값을 직접 넣어 실행한다.
실제로 실행한 SQL 은 결과 디렉터리의 <ID>.sql 에 남긴다.
Q 케이스(쿼리 변경 후, 문서 8장)는 statement 로그로 잡은 PostgreSQL SQL 을 그대로 적어 두었으므로 바꿀 것이 없다.

사용
  python3 docs/week4/bench_pg.py pg-after                # 전체 케이스
  python3 docs/week4/bench_pg.py pg-after L03 L09 C01    # 일부 케이스만
  python3 docs/week4/bench_pg.py pg-cte Q01 Q02 Q04 Q05 Q07 Q08 Q10 Q11   # 쿼리 변경 후 (문서 8장)
  python3 docs/week4/bench_pg.py pg-after --warmup 1 --runs 5
  python3 docs/week4/bench_pg.py pg-after --dry-run      # 실행할 SQL 만 출력 (DB 접속 안 함)

결과
  docs/week4/results/<stage>/summary.tsv     케이스별 평균·최소·최대·각 회차(ms)
  docs/week4/results/<stage>/<ID>.plan.txt   마지막 측정 회차의 EXPLAIN ANALYZE
  docs/week4/results/<stage>/<ID>.sql        실행한 SQL

환경 변수: PG_CONTAINER (기본 docker-postgres-1), PG_USER (application), PG_DB (loopers)
"""
import argparse
import datetime
import os
import re
import statistics
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DOC = HERE / "product-list-performance.md"
RESULTS = HERE / "results"

CONTAINER = os.environ.get("PG_CONTAINER", "docker-postgres-1")
USER = os.environ.get("PG_USER", "application")
DB = os.environ.get("PG_DB", "loopers")

CASE_SQL = re.compile(r"^### ([LCQ]\d{2})\s*$.*?^```sql\n(.*?)\n```", re.M | re.S)
MYSQL_LIMIT = re.compile(r" limit (\d+),(\d+)$")
ACTUAL_TIME = re.compile(r"actual time=([\d.]+)\.\.([\d.]+)")


def to_postgres(sql):
    """MySQL `limit offset,count` 를 앱(Hibernate PostgreSQL dialect)이 내는 `offset .. fetch first ..` 로 바꾼다."""
    return MYSQL_LIMIT.sub(lambda m: f" offset {m.group(1)} rows fetch first {m.group(2)} rows only", sql)


def load_cases():
    cases = []
    for cid, sql in CASE_SQL.findall(DOC.read_text(encoding="utf-8")):
        sql = to_postgres(" ".join(sql.split()))
        if " limit " in sql:
            sys.exit(f"{cid}: PostgreSQL 로 바꾸지 못한 limit 이 남아 있다:\n{sql}")
        cases.append((cid, sql))
    if not cases:
        sys.exit(f"{DOC} 에서 케이스 SQL 을 찾지 못했다.")
    return cases


def explain_analyze(sql):
    """EXPLAIN ANALYZE 한 번. (실행 시간 ms, 실행 계획 텍스트)."""
    proc = subprocess.run(
        ["docker", "exec", "-i", CONTAINER,
         "psql", "-U", USER, "-d", DB, "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1"],
        input=f"EXPLAIN (ANALYZE) {sql};\n", capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip())
    plan = proc.stdout.rstrip("\n")
    match = ACTUAL_TIME.search(plan)
    if not match:
        raise RuntimeError(f"actual time 을 찾지 못했다:\n{plan}")
    return float(match.group(2)), plan


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("stage", help="결과 디렉터리 이름 (예: pg-after)")
    parser.add_argument("ids", nargs="*", help="측정할 케이스 ID. 없으면 전체")
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--dry-run", action="store_true", help="실행할 SQL 만 출력하고 끝낸다")
    args = parser.parse_args()

    cases = load_cases()
    if args.ids:
        known = {cid for cid, _ in cases}
        unknown = [cid for cid in args.ids if cid not in known]
        if unknown:
            sys.exit(f"없는 케이스: {', '.join(unknown)}")
        cases = [(cid, sql) for cid, sql in cases if cid in args.ids]

    if args.dry_run:
        for cid, sql in cases:
            print(f"{cid}\t{sql}")
        return

    out = RESULTS / args.stage
    out.mkdir(parents=True, exist_ok=True)
    summary = out / "summary.tsv"
    if not summary.exists():
        summary.write_text("id\tavg_ms\tmin_ms\tmax_ms\truns_ms\tmeasured_at\n", encoding="utf-8")

    print(f"[{args.stage}] {len(cases)}개 케이스, 워밍업 {args.warmup}회 + 측정 {args.runs}회", flush=True)
    for cid, sql in cases:
        (out / f"{cid}.sql").write_text(sql + "\n", encoding="utf-8")
        for i in range(args.warmup):
            ms, _ = explain_analyze(sql)
            print(f"  {cid} warmup {i + 1}: {ms:.1f} ms", flush=True)
        times, plan = [], ""
        for i in range(args.runs):
            ms, plan = explain_analyze(sql)
            times.append(ms)
            print(f"  {cid} run {i + 1}: {ms:.1f} ms", flush=True)

        (out / f"{cid}.plan.txt").write_text(plan + "\n", encoding="utf-8")
        avg = statistics.mean(times)
        with summary.open("a", encoding="utf-8") as f:
            f.write(f"{cid}\t{avg:.1f}\t{min(times):.1f}\t{max(times):.1f}\t"
                    f"{','.join(f'{t:.1f}' for t in times)}\t{datetime.datetime.now().isoformat(timespec='seconds')}\n")
        print(f"{cid}: avg {avg:.1f} ms (min {min(times):.1f}, max {max(times):.1f})", flush=True)


if __name__ == "__main__":
    main()

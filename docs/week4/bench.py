#!/usr/bin/env python3
"""
상품 목록 조회 쿼리 실행 시간 측정 (docs/week4/product-list-performance.md).

케이스 SQL 은 문서의 `### <ID>` 아래 ```sql 블록을 그대로 읽는다 (문서가 원본).
케이스마다 EXPLAIN ANALYZE 를 워밍업 N회(버림) + 측정 M회, 한 번에 하나씩 순차 실행한다.
실행 시간은 맨 위 노드의 `actual time=시작..끝` 의 끝 값(ms, 서버 안에서 잰 값)이다.

사용
  python3 docs/week4/bench.py before                # 전체 케이스
  python3 docs/week4/bench.py after L03 L09 C01     # 일부 케이스만
  python3 docs/week4/bench.py before --warmup 1 --runs 5
  python3 docs/week4/bench.py cte Q01 Q02 Q04 Q05 Q07 Q08 Q10 Q11   # 쿼리 변경 후 (문서 8장)

결과
  docs/week4/results/<stage>/summary.tsv     케이스별 평균·최소·최대·각 회차(ms)
  docs/week4/results/<stage>/<ID>.plan.txt   마지막 측정 회차의 EXPLAIN ANALYZE

환경 변수: MYSQL_CONTAINER (기본 docker-mysql-1), MYSQL_USER (root), MYSQL_PWD (root), MYSQL_DB (loopers)
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

CONTAINER = os.environ.get("MYSQL_CONTAINER", "docker-mysql-1")
USER = os.environ.get("MYSQL_USER", "root")
PASSWORD = os.environ.get("MYSQL_PWD", "root")
DB = os.environ.get("MYSQL_DB", "loopers")

CASE_SQL = re.compile(r"^### ([LCQ]\d{2})\s*$.*?^```sql\n(.*?)\n```", re.M | re.S)
ACTUAL_TIME = re.compile(r"actual time=([\d.]+)\.\.([\d.]+)")


def load_cases():
    cases = [(cid, " ".join(sql.split())) for cid, sql in CASE_SQL.findall(DOC.read_text(encoding="utf-8"))]
    if not cases:
        sys.exit(f"{DOC} 에서 케이스 SQL 을 찾지 못했다.")
    return cases


def explain_analyze(sql):
    """EXPLAIN ANALYZE 한 번. (실행 시간 ms, 실행 계획 텍스트)."""
    proc = subprocess.run(
        ["docker", "exec", "-i", "-e", f"MYSQL_PWD={PASSWORD}", CONTAINER,
         "mysql", f"-u{USER}", DB, "--batch", "--raw", "--skip-column-names"],
        input=f"EXPLAIN ANALYZE {sql};\n", capture_output=True, text=True,
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
    parser.add_argument("stage", help="결과 디렉터리 이름 (예: before, after)")
    parser.add_argument("ids", nargs="*", help="측정할 케이스 ID. 없으면 전체")
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--runs", type=int, default=5)
    args = parser.parse_args()

    cases = load_cases()
    if args.ids:
        known = {cid for cid, _ in cases}
        unknown = [cid for cid in args.ids if cid not in known]
        if unknown:
            sys.exit(f"없는 케이스: {', '.join(unknown)}")
        cases = [(cid, sql) for cid, sql in cases if cid in args.ids]

    out = RESULTS / args.stage
    out.mkdir(parents=True, exist_ok=True)
    summary = out / "summary.tsv"
    if not summary.exists():
        summary.write_text("id\tavg_ms\tmin_ms\tmax_ms\truns_ms\tmeasured_at\n", encoding="utf-8")

    print(f"[{args.stage}] {len(cases)}개 케이스, 워밍업 {args.warmup}회 + 측정 {args.runs}회", flush=True)
    for cid, sql in cases:
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

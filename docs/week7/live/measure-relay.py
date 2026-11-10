"""relay 주기(fixedDelay 1s)·묶음(200)·직렬 처리의 비용과 한계를 live 환경에서 잰다 (docs/week7/live/up.sh 뒤).

M1 쉬는 동안의 DB 비용 — 보낼 행이 없을 때 60초 동안 relay가 DB에 보내는 문장 수·시간 (performance_schema 차이)
M2 몰린 backlog 비우기 — PENDING N행을 한 번에 넣고 모두 PUBLISHED가 될 때까지: 회차별 고른 수·회차 시간·행당 시간
M3 일정 유입 — 초당 R행을 S초 동안 넣으며 1초마다 backlog(차례가 된 미발행 행)와 가장 오래 기다린 행의 대기를 본다

행은 확정 TX가 아니라 SQL로 직접 넣는다(relay만 재기 위해). payload는 consumer 계약 그대로라 streamer가 실제로 소비한다.
주문 ID는 1,000,000부터 써서 시나리오 주문과 겹치지 않는다.
실행: python3 -I docs/week7/live/measure-relay.py [m1|m2|m3|all]   결과: docs/week7/live/results/relay-<시각>.json
"""
import json
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone

DB = "loopers_w7"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RUN = os.path.join(os.environ.get("TMPDIR", "/tmp"), "w7-live")
KST = timezone(timedelta(hours=9))
STATE = {"next_order": 1_000_000}


def sql(query, user="application", database=DB):
    pw = "root" if user == "root" else "application"
    out = subprocess.run(["docker", "exec", "-i", "docker-mysql-1", "mysql", f"-u{user}", f"-p{pw}", "-N", "-B",
                          "--default-character-set=utf8mb4", database],
                         input=query, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    return [line.split("\t") for line in out.stdout.strip().splitlines() if line]


def insert_pending(count):
    """PENDING 행 count개 (next_attempt_at = 지금 → 바로 차례)"""
    rows = []
    now = datetime.now(KST)
    for _ in range(count):
        order_id = STATE["next_order"]
        STATE["next_order"] += 1
        event_id = str(uuid.uuid4())
        payload = json.dumps({"eventId": event_id, "eventType": "OrderConfirmed", "schemaVersion": 1, "orderId": order_id,
                              "confirmedAt": now.isoformat(timespec="microseconds"),
                              "items": [{"productId": 1 + order_id % 10, "quantity": 1}]}, separators=(",", ":"))
        rows.append(f"('{event_id}',{order_id},'OrderConfirmed',1,'{order_id}','{payload}','PENDING',0,"
                    "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")
    for i in range(0, len(rows), 500):
        sql("INSERT INTO order_outbox (event_id,order_id,event_type,schema_version,message_key,payload,status,attempt_count,"
            "next_attempt_at,created_at,updated_at) VALUES " + ",".join(rows[i:i + 500]) + ";")


def backlog():
    r = sql("SELECT COUNT(*), COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(6)), 0) FROM order_outbox "
            "WHERE status IN ('PENDING','SENDING');")[0]
    return int(r[0]), int(r[1]) / 1e6


def statement_totals():
    """commerce-api가 loopers_w7에 보낸 문장 (status.sh·이 스크립트의 조회는 뺀다)"""
    rows = sql("SELECT DIGEST, DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT FROM performance_schema.events_statements_summary_by_digest "
               f"WHERE SCHEMA_NAME='{DB}';", user="root", database="performance_schema")
    totals = {}
    for digest, text, count, timer in rows:
        # 측정 도구 자신의 문장(mysql CLI 접속 · 상태 조회 · 행 넣기)은 뺀다
        if any(x in text for x in ("CONVERT_TZ", "COUNT ( * )", "version_comment", "INSERT INTO `order_outbox`", "character_set_results")):
            continue
        totals[digest] = (int(count), int(timer), text[:110])
    return totals


def round_logs(since):
    """api.log의 'relay 회차: 고름 N · PUBLISHED M' 줄 (since 이후)"""
    pattern = re.compile(r"^(\S+)\s.*relay 회차: 고름 (\d+) · PUBLISHED (\d+)")
    rounds = []
    with open(os.path.join(RUN, "api.log"), encoding="utf-8", errors="replace") as f:
        for line in f:
            m = pattern.match(line)
            if m:
                at = datetime.fromisoformat(m.group(1))
                if at >= since:
                    rounds.append((at, int(m.group(2)), int(m.group(3))))
    return rounds


def m1(seconds=60):
    assert backlog()[0] == 0, "보낼 행이 남아 있으면 쉬는 비용이 아니다"
    before = statement_totals()
    time.sleep(seconds)
    after = statement_totals()
    diff = {}
    for key, (count, timer, text) in after.items():
        c0, t0, _ = before.get(key, (0, 0, ""))
        if count - c0 > 0:
            diff[text] = {"count": count - c0, "per_sec": round((count - c0) / seconds, 2),
                         "total_ms": round((timer - t0) / 1e9, 2)}
    total = sum(v["count"] for v in diff.values())
    total_ms = sum(v["total_ms"] for v in diff.values())
    return {"seconds": seconds, "statements": total, "statements_per_sec": round(total / seconds, 2),
            "db_time_ms_total": round(total_ms, 2), "db_time_ms_per_sec": round(total_ms / seconds, 3), "by_statement": diff}


def m2(count=2000):
    since = datetime.now(KST)
    t0 = time.monotonic()
    insert_pending(count)
    inserted = time.monotonic() - t0
    samples = []
    while True:
        n, oldest = backlog()
        samples.append({"t": round(time.monotonic() - t0, 1), "backlog": n})
        if n == 0:
            break
        time.sleep(0.5)
    drained = time.monotonic() - t0
    time.sleep(1.5)
    rounds = [r for r in round_logs(since) if r[1] > 0]
    gaps = [(rounds[i][0] - rounds[i - 1][0]).total_seconds() for i in range(1, len(rounds))]
    full = [g for i, g in enumerate(gaps) if rounds[i][1] == 200]
    return {"rows": count, "insert_seconds": round(inserted, 2), "drain_seconds": round(drained, 2),
            "rows_per_sec": round(count / drained, 1), "rounds": len(rounds),
            "round_selected": [r[1] for r in rounds], "round_published": sum(r[2] for r in rounds),
            "round_start_gap_seconds": [round(g, 3) for g in gaps],
            "full_round_seconds_estimate": round(sum(full) / len(full) - 1.0, 3) if full else None,
            "per_row_ms_estimate": round((sum(full) / len(full) - 1.0) / 200 * 1000, 2) if full else None,
            "backlog_samples": samples[::4]}


def m3(rate, seconds):
    t0 = time.monotonic()
    samples = []
    for s in range(seconds):
        insert_pending(rate)
        n, oldest = backlog()
        samples.append({"t": s + 1, "backlog": n, "oldest_wait_s": round(oldest, 2)})
        time.sleep(max(0.0, (t0 + s + 1) - time.monotonic()))
    while True:
        n, oldest = backlog()
        samples.append({"t": round(time.monotonic() - t0, 1), "backlog": n, "oldest_wait_s": round(oldest, 2)})
        if n == 0:
            break
        time.sleep(1)
    peak = max(samples, key=lambda x: x["backlog"])
    return {"rate_per_sec": rate, "seconds": seconds, "rows": rate * seconds,
            "peak_backlog": peak["backlog"], "max_oldest_wait_s": max(x["oldest_wait_s"] for x in samples),
            "drained_at_s": samples[-1]["t"], "samples": samples}


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    STATE["next_order"] = 1_000_000 + int(sql("SELECT COUNT(*) FROM order_outbox;")[0][0])
    result = {"at": datetime.now(KST).isoformat(timespec="seconds"), "tick": "fixedDelay 1s", "batch": 200}
    if which in ("m1", "all"):
        result["m1_idle"] = m1()
        print("M1", json.dumps({k: v for k, v in result["m1_idle"].items() if k != "by_statement"}, ensure_ascii=False))
    if which in ("m2", "all"):
        result["m2_burst"] = m2()
        print("M2", json.dumps({k: v for k, v in result["m2_burst"].items() if k not in ("backlog_samples", "round_start_gap_seconds", "round_selected")}, ensure_ascii=False))
    if which in ("m3", "all"):
        result["m3_steady"] = [m3(100, 30), m3(300, 30)]
        for r in result["m3_steady"]:
            print("M3", json.dumps({k: v for k, v in r.items() if k != "samples"}, ensure_ascii=False))
    out_dir = os.path.join(os.path.dirname(__file__), "results")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "relay-" + datetime.now(KST).strftime("%Y%m%d-%H%M%S") + ".json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print("결과", path)

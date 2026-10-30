"""W6 실험 ① — consumer 프로세스가 group을 떠날 때 rebalance까지의 공백.

같은 streamer jar를 프로세스 두 개(S1 8085 · S2 8087)로 띄워 같은 group에 member 6개를 만든다(partition 3).
1초에 1건씩 OrderConfirmed record를 넣다가 KILL_AT초에 partition을 가진 프로세스 하나를 끈다.
  --mode term : SIGTERM — Spring이 정상 종료하며 consumer를 close(LeaveGroup)한다
  --mode kill : SIGKILL(kill -9) — 아무것도 알리지 못하고 죽는다
끝나면 record마다 보낸 시각 · partition · 처리한 프로세스 · 처리까지 걸린 시간을, 그리고 배정 변화를 표로 찍는다.

준비: compose broker 3대 · 실험 DB loopers_w6 (docs/week6/k6/README 0 ~ 1절), streamer jar
  ./gradlew :apps:commerce-streamer:bootJar
실행: python3 -I docs/week6/rebalance/rebalance.py --mode kill
"""
import argparse
import glob
import json
import os
import re
import signal
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CONFIG = os.path.join(ROOT, "docs/week6/k6/config-base.yml")
TOPIC = "order-events"
PARTITIONS = 3


def murmur2(data: bytes) -> int:
    """Kafka의 key → partition 계산 (org.apache.kafka.common.utils.Utils.murmur2)."""
    length = len(data)
    seed, m, r = 0x9747B28C, 0x5BD1E995, 24
    h = (seed ^ length) & 0xFFFFFFFF
    for i in range(0, length - length % 4, 4):
        k = int.from_bytes(data[i:i + 4], "little")
        k = (k * m) & 0xFFFFFFFF
        k ^= k >> r
        k = (k * m) & 0xFFFFFFFF
        h = (h * m) & 0xFFFFFFFF
        h ^= k
    rest = length % 4
    tail = data[length - rest:]
    if rest == 3:
        h ^= tail[2] << 16
    if rest >= 2:
        h ^= tail[1] << 8
    if rest >= 1:
        h ^= tail[0]
        h = (h * m) & 0xFFFFFFFF
    h ^= h >> 13
    h = (h * m) & 0xFFFFFFFF
    h ^= h >> 15
    return h


def partition_of(key: str) -> int:
    return (murmur2(key.encode()) & 0x7FFFFFFF) % PARTITIONS


def start_streamer(name, port, log_dir):
    jar = sorted(glob.glob(os.path.join(ROOT, "apps/commerce-streamer/build/libs/commerce-streamer-*.jar")))[-1]
    log = open(os.path.join(log_dir, f"{name}.log"), "w")
    proc = subprocess.Popen(
        ["java", "-jar", jar, "--spring.profiles.active=local",
         f"--spring.config.additional-location=file:{CONFIG}",
         f"--server.port={port}", f"--management.server.port={port + 1}"],
        stdout=log, stderr=subprocess.STDOUT, cwd=ROOT)
    return proc


LOG_TIME = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2})")


def log_time(line):
    m = LOG_TIME.match(line)
    return datetime.fromisoformat(m.group(1)).timestamp() if m else None


def read_log(log_dir, name):
    path = os.path.join(log_dir, f"{name}.log")
    return open(path, encoding="utf-8", errors="replace").read().splitlines() if os.path.exists(path) else []


def assignments(lines):
    """[(시각, 'assigned'|'revoked', [partition...])] — Spring Kafka 컨테이너의 INFO 로그."""
    out = []
    for line in lines:
        # 같은 앱의 다른 listener(스타터 예제, group loopers-default-consumer) 줄은 뺀다
        m = re.search(r"purchase-activity: partitions (assigned|revoked): \[(.*?)\]", line)
        if m:
            parts = sorted(int(p.rsplit("-", 1)[1]) for p in m.group(2).split(", ") if p.strip())
            out.append((log_time(line), m.group(1), parts))
    return out


def current_owned(lines):
    owned = set()
    for _, kind, parts in assignments(lines):
        if kind == "revoked":
            owned -= set(parts)
        else:
            owned |= set(parts)
    return owned


def wait_until(predicate, timeout, label):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return
        time.sleep(0.5)
    sys.exit(f"[실험] 시간 초과: {label}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["term", "kill"], required=True)
    parser.add_argument("--kill-at", type=int, default=10)
    parser.add_argument("--duration", type=int, default=70)
    parser.add_argument("--settle", type=int, default=20, help="마지막 record 뒤 처리를 기다리는 초")
    args = parser.parse_args()

    run = datetime.now().strftime("%H%M%S")
    log_dir = os.path.join(ROOT, "docs/week6/rebalance/logs", f"{args.mode}-{run}")
    os.makedirs(log_dir, exist_ok=True)

    print(f"[실험] streamer S1(8085) · S2(8087) 기동 — 로그 {os.path.relpath(log_dir, ROOT)}")
    procs = {"S1": start_streamer("S1", 8085, log_dir), "S2": start_streamer("S2", 8087, log_dir)}
    wait_until(lambda: len(current_owned(read_log(log_dir, "S1")) | current_owned(read_log(log_dir, "S2"))) == PARTITIONS
               and all("Started CommerceStreamerApplication" in "\n".join(read_log(log_dir, n)) for n in procs),
               120, "두 프로세스 기동과 partition 3개 배정")
    time.sleep(3)  # 두 번째 프로세스가 들어오며 생긴 rebalance가 끝나기를 기다린다
    owned = {n: current_owned(read_log(log_dir, n)) for n in procs}
    target = next(n for n in ("S1", "S2") if owned[n])
    print(f"[실험] 시작 배정: S1={sorted(owned['S1'])} S2={sorted(owned['S2'])} → {args.kill_at}초에 {target}을 "
          f"{'SIGTERM(정상 종료)' if args.mode == 'term' else 'SIGKILL(kill -9)'}")

    producer = subprocess.Popen(
        ["docker", "exec", "-i", "kafka-1", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka-1:9092",
         "--topic", TOPIC, "--property", "parse.key=true", "--property", "key.separator=|", "--producer-property", "acks=all", "--producer-property", "linger.ms=0"],
        stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True)
    base_order = 7_000_000 + int(time.time()) % 1_000_000
    sent = []
    t0 = time.time()
    killed_at = None
    for i in range(args.duration):
        while time.time() < t0 + i:
            time.sleep(0.01)
        if i == args.kill_at:
            killed_at = time.time()
            procs[target].send_signal(signal.SIGTERM if args.mode == "term" else signal.SIGKILL)
            print(f"[실험] t={killed_at - t0:5.1f}s {target} {'SIGTERM' if args.mode == 'term' else 'SIGKILL'}")
        order_id = base_order + i
        event = {"eventId": str(uuid.uuid4()), "eventType": "OrderConfirmed", "schemaVersion": 1, "orderId": order_id,
                 "confirmedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="microseconds"),
                 "items": [{"productId": (i % 10) + 1, "quantity": 1}]}
        producer.stdin.write(f"{order_id}|{json.dumps(event, separators=(',', ':'))}\n")
        producer.stdin.flush()
        sent.append((order_id, time.time(), partition_of(str(order_id))))
        if i % 10 == 0:
            print(f"[실험] t={time.time() - t0:5.1f}s {i + 1}건 보냄")
    producer.stdin.close()
    producer.wait(timeout=30)
    print(f"[실험] 다 보냄. 처리를 {args.settle}초 더 기다린다")
    time.sleep(args.settle)

    # 처리한 프로세스와 시각 — 로그의 "구매 활동 저장 … orderId=" / no-op 줄
    handled = {}
    for name in procs:
        for line in read_log(log_dir, name):
            m = re.search(r"(구매 활동 저장|no-op).*?orderId=(\d+)", line)
            if m:
                handled.setdefault(int(m.group(2)), []).append((log_time(line), name, "저장" if m.group(1) != "no-op" else "no-op"))

    print()
    print(f"## 배정 변화 (t=0은 첫 record를 보낸 시각, {target}을 t={killed_at - t0:.1f}s에 {args.mode})")
    print("| t(s) | 프로세스 | 변화 | partition |")
    print("|---:|---|---|---|")
    events = []
    for name in procs:
        for when, kind, parts in assignments(read_log(log_dir, name)):
            if when and when >= t0 - 1:
                events.append((when, name, kind, parts))
    for when, name, kind, parts in sorted(events):
        print(f"| {when - t0:6.1f} | {name} | {kind} | {parts} |")

    print()
    print("## record별 처리")
    print("| # | 보낸 t(s) | orderId | partition | 처리 | 처리 t(s) | 걸린 시간(s) |")
    print("|---:|---:|---:|---:|---|---:|---:|")
    worst = 0.0
    for idx, (order_id, at, part) in enumerate(sent):
        records = sorted(handled.get(order_id, []))
        if not records:
            print(f"| {idx + 1} | {at - t0:5.1f} | {order_id} | {part} | **처리 안 됨** | | |")
            continue
        first = records[0]
        who = ", ".join(f"{n} {k}" for _, n, k in records)
        delay = first[0] - at
        worst = max(worst, delay)
        mark = " ⏳" if delay > 2 else ""
        print(f"| {idx + 1} | {at - t0:5.1f} | {order_id} | {part} | {who} | {first[0] - t0:5.1f} | {delay:5.1f}{mark} |")
    print()
    missing = sum(1 for order_id, _, _ in sent if order_id not in handled)
    duplicated = sum(1 for order_id, _, _ in sent if len(handled.get(order_id, [])) > 1)
    print(f"요약: 보낸 {len(sent)}건 · 처리 안 됨 {missing}건 · 두 번 이상 처리 {duplicated}건 · 가장 오래 기다린 record {worst:.1f}s")

    for proc in procs.values():
        if proc.poll() is None:
            proc.send_signal(signal.SIGTERM)
    for proc in procs.values():
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    main()

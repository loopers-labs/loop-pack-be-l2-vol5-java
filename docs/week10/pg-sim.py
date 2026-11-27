"""10주차 가짜 PG — run.sh 실행 동안 127.0.0.1:8082에서 PG 역할을 한다 (plan.md D-2).

W6 docs/week6/k6/pg-sim.py를 복사해 고쳤다. 과제 로컬 PG 계약(LocalTestPgServer와 같은 계약)은 그대로 두고,
응답 유실 지정 · callback 보관 · 중복 create 기록을 더했다. W6 전용 기능(hold 모드 · complete-all · _pending)은 뺐다.

실행: python3 -I docs/week10/pg-sim.py   (8082가 비어 있어야 한다. 끝내려면 Ctrl+C)
표준 라이브러리만 쓴다. 카드 번호와 X-USER-ID 값은 저장·출력하지 않는다.

PG 계약 경로 — commerce-api가 부른다 (진짜 PG에도 있는 경로)
  POST /api/v1/payments                   거래 생성 (X-USER-ID: 135135)
  GET  /api/v1/payments/{transactionKey}  거래 조회 (없으면 404)
  GET  /api/v1/payments?orderId=          PG 주문 식별자의 거래 목록

실험용 경로 — run.sh만 부른다 (진짜 PG에는 없는 경로)
  POST /test/drop {"orderIds": [...]}     응답 유실 주문 지정 (PG 주문 식별자 LP-ORD-…). [] 이면 해제
  POST /test/callbacks/release            보관한 callback 전송 → {"sent": n, "failed": n}
  GET  /test/transactions                 거래 전부 (만든 순서)
  GET  /test/stats                        create 수 · 주문별 create 수 · 중복 · callback · traceparent · PID · 시작 시각

거래를 만든 뒤
  보통 주문      1초 뒤 SUCCESS로 바꾸고 callbackUrl로 {transactionKey, orderId, status}를 보낸다.
  응답 유실 주문  1초 뒤 SUCCESS로 바꾸되 callback은 보관한다. 생성 응답은 5초 뒤에 보낸다
                 → commerce-api의 생성 읽기 timeout(3초)이 먼저 끝나 결제가 UNKNOWN으로 남는다.
  같은 주문의 create가 또 오면 거절하지 않고 새 거래를 만들고 duplicates에 남긴다 (이중 결제를 숨기지 않는다).
"""
import json
import os
import random
import string
import threading
import time
import urllib.request
from datetime import date, datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

HOST, PORT = "127.0.0.1", 8082
USER_ID = "135135"
PAYMENTS = "/api/v1/payments"
COMPLETE_AFTER_S = 1
DROPPED_RESPONSE_AFTER_S = 5
STARTED_AT = datetime.now(timezone.utc).isoformat(timespec="seconds")

lock = threading.Lock()
transactions = {}      # transactionKey -> dict(transactionKey, orderId, amount, status, reason, callbackUrl, callback)
dropped_orders = set()  # 응답 유실로 지정한 PG 주문 식별자
creates_by_order = {}  # PG 주문 식별자 -> 받은 create 수 (검증 전에 센다)
traceparents = {}      # PG 주문 식별자 -> create 요청의 traceparent 목록 (D-12)
stats = {"create": 0, "lookup": 0}


def next_key():
    suffix = "".join(random.choices(string.hexdigits.lower()[:16], k=7))
    return f"{date.today():%Y%m%d}:TR:{suffix}"


def body_of(tx):
    return {k: tx[k] for k in ("transactionKey", "orderId", "amount", "status", "reason")}


def send_callback(key):
    """거래의 현재 상태를 callbackUrl로 보낸다. 성공·실패를 거래의 callback 칸에 남긴다."""
    with lock:
        tx = transactions[key]
        payload = json.dumps({"transactionKey": key, "orderId": tx["orderId"], "status": tx["status"]}).encode()
        url = tx["callbackUrl"]
    try:
        request = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=5) as response:
            response.read()
        result = "sent"
        print(f"[pg-sim] callback {tx['orderId']} {key} → 200", flush=True)
    except Exception as e:  # noqa: BLE001 — 실험 도구라 실패를 남기고 넘어간다
        result = "failed"
        print(f"[pg-sim] callback {tx['orderId']} {key} 실패: {e}", flush=True)
    with lock:
        tx["callback"] = result
    return result


def complete_later(key):
    """1초 뒤 SUCCESS. 보관 표시가 없으면 callback을 보낸다."""
    time.sleep(COMPLETE_AFTER_S)
    with lock:
        tx = transactions[key]
        tx["status"] = "SUCCESS"
        held = tx["callback"] == "held"
    if held:
        print(f"[pg-sim] {tx['orderId']} SUCCESS · callback 보관", flush=True)
    else:
        send_callback(key)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def respond(self, status, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_json(self):
        # 앱의 HTTP 클라이언트는 본문을 chunked로 보낼 수 있다 — Content-Length만 보면 빈 본문으로 읽힌다.
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            data = b""
            while True:
                size = int(self.rfile.readline().strip().split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                data += self.rfile.read(size)
                self.rfile.readline()
        else:
            data = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        return json.loads(data or b"{}")

    def authorized(self):
        if self.headers.get("X-USER-ID") != USER_ID:
            self.respond(401, {"message": "X-USER-ID가 없거나 다릅니다"})
            return False
        return True

    def do_POST(self):
        path = urlparse(self.path).path
        if path == PAYMENTS:
            self.create()
        elif path == "/test/drop":
            self.set_drop()
        elif path == "/test/callbacks/release":
            self.release_callbacks()
        else:
            self.respond(404, {"message": "없는 경로"})

    def create(self):
        # 오류로 끝나도 호출 사실은 남긴다 — 본문을 먼저 읽고 센 뒤에 검증한다.
        body = self.read_json()
        order_id = body.get("orderId")
        with lock:
            stats["create"] += 1
            if order_id:
                creates_by_order[order_id] = creates_by_order.get(order_id, 0) + 1
                if self.headers.get("traceparent"):
                    traceparents.setdefault(order_id, []).append(self.headers["traceparent"])
        if not self.authorized():
            return
        if not order_id or not body.get("callbackUrl") or int(body.get("amount") or 0) <= 0:
            self.respond(400, {"message": "요청을 거절했습니다"})
            return
        with lock:
            dropped = order_id in dropped_orders
            if creates_by_order[order_id] > 1:
                print(f"[pg-sim] 중복 create {order_id} {creates_by_order[order_id]}번째 — 새 거래를 만들고 기록한다", flush=True)
            tx = {"transactionKey": next_key(), "orderId": order_id, "amount": int(body["amount"]),
                  "status": "PENDING", "reason": None, "callbackUrl": body["callbackUrl"],
                  "callback": "held" if dropped else "waiting"}
            transactions[tx["transactionKey"]] = tx
            response = body_of(tx)
        threading.Thread(target=complete_later, args=(tx["transactionKey"],), daemon=True).start()
        if not dropped:
            self.respond(200, response)
            return
        print(f"[pg-sim] 응답 유실 {order_id} {tx['transactionKey']} — 생성 응답을 {DROPPED_RESPONSE_AFTER_S}초 뒤에 보낸다", flush=True)
        time.sleep(DROPPED_RESPONSE_AFTER_S)
        try:
            self.respond(200, response)
            print(f"[pg-sim] 응답 유실 {order_id} 늦은 응답을 썼다 (받는 쪽이 이미 끊었으면 버려진다)", flush=True)
        except OSError as e:
            print(f"[pg-sim] 응답 유실 {order_id} 늦은 응답 실패 (받는 쪽이 이미 끊음): {e}", flush=True)
        self.close_connection = True

    def set_drop(self):
        order_ids = self.read_json().get("orderIds")
        if not isinstance(order_ids, list) or not all(isinstance(o, str) and o for o in order_ids):
            self.respond(400, {"message": "orderIds는 PG 주문 식별자 문자열 목록"})
            return
        with lock:
            dropped_orders.clear()
            dropped_orders.update(order_ids)
            current = sorted(dropped_orders)
        print(f"[pg-sim] 응답 유실 목록 = {current}", flush=True)
        self.respond(200, {"drop": current})

    def release_callbacks(self):
        # 아직 SUCCESS가 안 된 보관 거래는 남겨 둔다 — callback은 최종 상태만 보낸다.
        with lock:
            keys = [k for k, tx in transactions.items() if tx["callback"] == "held" and tx["status"] == "SUCCESS"]
            for key in keys:
                transactions[key]["callback"] = "releasing"
        results = [send_callback(key) for key in keys]
        released = {"sent": results.count("sent"), "failed": results.count("failed")}
        print(f"[pg-sim] 보관 callback 전송 {released}", flush=True)
        self.respond(200, released)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == PAYMENTS or url.path.startswith(PAYMENTS + "/"):
            self.lookup(url)
        elif url.path == "/test/transactions":
            with lock:
                self.respond(200, [body_of(tx) for tx in transactions.values()])
        elif url.path == "/test/stats":
            self.respond(200, self.snapshot())
        else:
            self.respond(404, {"message": "없는 경로"})

    def lookup(self, url):
        with lock:
            stats["lookup"] += 1
        if not self.authorized():
            return
        if url.path == PAYMENTS:
            order_id = (parse_qs(url.query).get("orderId") or [""])[0]
            if not order_id:
                self.respond(400, {"message": "orderId가 필요합니다"})
                return
            with lock:
                found = [body_of(tx) for tx in transactions.values() if tx["orderId"] == order_id]
            self.respond(200, found)
            return
        key = unquote(url.path[len(PAYMENTS) + 1:])
        with lock:
            tx = transactions.get(key)
            found = body_of(tx) if tx else None
        if found is None:
            self.respond(404, {"message": "거래가 없습니다"})
        else:
            self.respond(200, found)

    @staticmethod
    def snapshot():
        with lock:
            callbacks = [tx["callback"] for tx in transactions.values()]
            return {
                "pid": os.getpid(),
                "startedAt": STARTED_AT,
                "drop": sorted(dropped_orders),
                "create": stats["create"],
                "lookup": stats["lookup"],
                "transactions": len(transactions),
                "createsByOrder": dict(creates_by_order),
                "duplicates": {o: n for o, n in creates_by_order.items() if n > 1},
                "callback": {"sent": callbacks.count("sent"), "failed": callbacks.count("failed"),
                             "held": callbacks.count("held")},
                "traceparents": {o: list(v) for o, v in traceparents.items()},
            }


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"[pg-sim] http://{HOST}:{PORT} pid={os.getpid()} startedAt={STARTED_AT}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass

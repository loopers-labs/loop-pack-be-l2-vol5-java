"""6주차 로컬 가짜 PG — 과제 로컬 PG 계약(LocalTestPgServer와 같은 계약)을 127.0.0.1:8082에 띄운다.

실행: python3 -I docs/week6/k6/pg-sim.py   (끝내려면 Ctrl+C. 8082가 비어 있어야 한다)
표준 라이브러리만 쓴다. 카드 번호는 저장·출력하지 않는다.

모드
  auto (기본): 거래를 만들고 COMPLETE_AFTER_MS 뒤 SUCCESS로 바꿔 callbackUrl로 {transactionKey, orderId, status}를 보낸다.
               전체 흐름 실행(C-1·C-9)에 쓴다.
  hold       : 거래를 PENDING으로 두고 callback을 보내지 않는다. E-6 준비(결제 대기 주문 N건)에 쓴다.
               준비가 끝나면 /_control/complete-all 로 전부 SUCCESS로 바꾼다(callback 없음) — callback은 k6가 보낸다.

경로
  POST /api/v1/payments                 거래 생성 (X-USER-ID: 135135)
  GET  /api/v1/payments/{transactionKey} 거래 조회 (없으면 404)
  GET  /api/v1/payments?orderId=        주문의 거래 목록
  POST /_control  {"mode": "auto"|"hold"}
  POST /_control/complete-all           PENDING 거래를 모두 SUCCESS로 (callback 없음)
  GET  /_pending?status=SUCCESS         해당 상태의 거래 [{transactionKey, orderId}] — k6 setup이 읽는다
  GET  /_stats                          생성·조회·callback 수
"""
import json
import random
import string
import threading
import time
import urllib.request
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

HOST, PORT = "127.0.0.1", 8082
USER_ID = "135135"
PAYMENTS = "/api/v1/payments"
COMPLETE_AFTER_MS = 1000

lock = threading.Lock()
transactions = {}  # transactionKey -> dict(transactionKey, orderId, amount, status, reason, callbackUrl)
state = {"mode": "auto"}
stats = {"create": 0, "lookup": 0, "callback_sent": 0, "callback_failed": 0}


def next_key():
    suffix = "".join(random.choices(string.hexdigits.lower()[:16], k=7))
    return f"{date.today():%Y%m%d}:TR:{suffix}"


def body_of(tx):
    return {k: tx[k] for k in ("transactionKey", "orderId", "amount", "status", "reason")}


def send_callback(key):
    time.sleep(COMPLETE_AFTER_MS / 1000)
    with lock:
        tx = transactions[key]
        tx["status"] = "SUCCESS"
        payload = json.dumps({"transactionKey": key, "orderId": tx["orderId"], "status": "SUCCESS"}).encode()
        url = tx["callbackUrl"]
    try:
        request = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=5) as response:
            response.read()
        with lock:
            stats["callback_sent"] += 1
        print(f"[pg-sim] callback {key} → {url} 200", flush=True)
    except Exception as e:  # noqa: BLE001 — 실험 도구라 실패를 세고 넘어간다
        with lock:
            stats["callback_failed"] += 1
        print(f"[pg-sim] callback {key} 실패: {e}", flush=True)


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
            with lock:
                stats["create"] += 1
            if not self.authorized():
                return
            body = self.read_json()
            if not body.get("orderId") or not body.get("callbackUrl") or int(body.get("amount") or 0) <= 0:
                self.respond(400, {"message": "요청을 거절했습니다"})
                return
            tx = {"transactionKey": next_key(), "orderId": body["orderId"], "amount": int(body["amount"]),
                  "status": "PENDING", "reason": None, "callbackUrl": body["callbackUrl"]}
            with lock:
                transactions[tx["transactionKey"]] = tx
                mode = state["mode"]
            if mode == "auto":
                threading.Thread(target=send_callback, args=(tx["transactionKey"],), daemon=True).start()
            self.respond(200, body_of(tx))
        elif path == "/_control":
            body = self.read_json()
            if body.get("mode") not in ("auto", "hold"):
                self.respond(400, {"message": "mode는 auto 또는 hold"})
                return
            with lock:
                state["mode"] = body["mode"]
            print(f"[pg-sim] mode = {body['mode']}", flush=True)
            self.respond(200, state)
        elif path == "/_control/complete-all":
            with lock:
                pending = [tx for tx in transactions.values() if tx["status"] == "PENDING"]
                for tx in pending:
                    tx["status"] = "SUCCESS"
            print(f"[pg-sim] PENDING {len(pending)}건 → SUCCESS (callback 없음)", flush=True)
            self.respond(200, {"completed": len(pending)})
        else:
            self.respond(404, {"message": "없는 경로"})

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == PAYMENTS or url.path.startswith(PAYMENTS + "/"):
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
        elif url.path == "/_pending":
            status = (parse_qs(url.query).get("status") or ["SUCCESS"])[0]
            with lock:
                found = [{"transactionKey": tx["transactionKey"], "orderId": tx["orderId"]}
                         for tx in transactions.values() if tx["status"] == status]
            self.respond(200, found)
        elif url.path == "/_stats":
            with lock:
                self.respond(200, {**stats, "mode": state["mode"], "transactions": len(transactions)})
        else:
            self.respond(404, {"message": "없는 경로"})


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"[pg-sim] http://{HOST}:{PORT} mode={state['mode']}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass

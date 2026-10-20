"""PG 관찰 실험용 서버 (Toss 결제위젯 · PortOne V1). Spring 코드와 무관한 일회성 도구.

키는 apps/commerce-api/src/main/resources/application-secret.yml 에서 읽고, 화면·로그에 비밀 키를 내지 않는다.
터널(ngrok)로 들어온 요청은 /webhook/* 만 허용한다.

실행: python3 -I spike/pg/server.py            (http://127.0.0.1:8090)
웹훅: PUBLIC_URL=https://xxxx.ngrok-free.app python3 -I spike/pg/server.py
"""
import base64
import json
import os
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SECRET = ROOT / "apps/commerce-api/src/main/resources/application-secret.yml"
LOG = HERE / "logs/events.jsonl"
PORT = 8090
PUBLIC_URL = os.environ.get("PUBLIC_URL", "").rstrip("/")

TOSS_API = "https://api.tosspayments.com"
PORTONE_API = "https://api.iamport.kr"


def load_secret(path):
    """application-secret.yml 의 중첩 key: value 만 읽는다 (PyYAML 없이)."""
    result, stack = {}, []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split(" #", 1)[0].rstrip()
        if not line.strip() or line.lstrip().startswith("#") or ":" not in line:
            continue
        indent = len(line) - len(line.lstrip())
        key, value = line.strip().split(":", 1)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, key))
        value = value.strip().strip("'\"")
        if value:
            result[".".join(k for _, k in stack)] = value
    return result


S = load_secret(SECRET)
TOSS_SECRET = S.get("pg.toss.secret-key", "")
TOSS_CLIENT = S.get("pg.toss.client-key", "")
IMP_CODE = S.get("pg.portone.v1.imp-code", "")
IMP_KEY = S.get("pg.portone.v1.api-key", "")
IMP_SECRET = S.get("pg.portone.v1.api-secret", "")
IMP_PROVIDER = S.get("pg.portone.v1.provider", "")


def log(kind, **data):
    LOG.parent.mkdir(exist_ok=True)
    entry = {"at": time.strftime("%H:%M:%S"), "ms": int(time.time() * 1000), "kind": kind, **data}
    with LOG.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    print(f"[{entry['at']}] {kind} {json.dumps(data, ensure_ascii=False)[:300]}")
    return entry


def call(method, url, headers=None, body=None, timeout=10.0):
    """외부 호출 한 번. 재시도하지 않는다. timeout 이면 outcome=TIMEOUT 으로 돌려준다."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            out = {"outcome": "HTTP", "status": res.status, "body": json.loads(res.read() or b"null")}
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = raw.decode(errors="replace")
        out = {"outcome": "HTTP", "status": e.code, "body": parsed}
    except (socket.timeout, TimeoutError):
        out = {"outcome": "TIMEOUT", "timeoutSec": timeout}
    except urllib.error.URLError as e:
        reason = e.reason
        out = {"outcome": "TIMEOUT" if isinstance(reason, (socket.timeout, TimeoutError)) else "ERROR",
               "error": str(reason)}
    out["elapsedMs"] = int((time.monotonic() - started) * 1000)
    return out


# ---------- Toss ----------
def toss_headers(idempotency_key=None):
    token = base64.b64encode(f"{TOSS_SECRET}:".encode()).decode()
    headers = {"Authorization": f"Basic {token}"}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    return headers


def toss_confirm(body):
    payload = {k: body[k] for k in ("paymentKey", "orderId", "amount")}
    timeout = float(body.get("timeoutMs") or 10000) / 1000
    res = call("POST", f"{TOSS_API}/v1/payments/confirm", toss_headers(body.get("idempotencyKey")), payload, timeout)
    return log("toss.confirm", request={**payload, "timeoutMs": body.get("timeoutMs"),
                                        "idempotencyKey": body.get("idempotencyKey")}, response=res)


def toss_lookup_order(order_id):
    res = call("GET", f"{TOSS_API}/v1/payments/orders/{urllib.parse.quote(order_id, safe='')}", toss_headers())
    return log("toss.lookup", orderId=order_id, response=res)


# ---------- PortOne V1 ----------
_token = {"value": None, "expiresAt": 0}


def portone_token():
    if _token["value"] and _token["expiresAt"] - 60 > time.time():
        return _token["value"]
    res = call("POST", f"{PORTONE_API}/users/getToken", body={"imp_key": IMP_KEY, "imp_secret": IMP_SECRET})
    body = res.get("body") or {}
    if res.get("status") != 200 or body.get("code") != 0:
        raise RuntimeError(f"PortOne 토큰 발급 실패: {res.get('status')} {body.get('message')}")
    _token["value"] = body["response"]["access_token"]
    _token["expiresAt"] = body["response"]["expired_at"]
    return _token["value"]


def portone(method, path, body=None):
    return call(method, f"{PORTONE_API}{path}", {"Authorization": portone_token()}, body)


def portone_prepare(body):
    payload = {"merchant_uid": body["merchantUid"], "amount": int(body["amount"])}
    return log("portone.prepare", request=payload, response=portone("POST", "/payments/prepare", payload))


def portone_payment(imp_uid):
    # 테스트 결제는 include_sandbox=true 가 없으면 단건 조회가 404(존재하지 않는 결제정보)다
    path = f"/payments/{urllib.parse.quote(imp_uid)}?include_sandbox=true"
    return log("portone.lookup", impUid=imp_uid, response=portone("GET", path))


def portone_find(merchant_uid):
    path = f"/payments/find/{urllib.parse.quote(merchant_uid, safe='')}"
    return log("portone.find", merchantUid=merchant_uid, response=portone("GET", path))


# ---------- HTTP ----------
class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(HERE / "static"), **kwargs)

    def via_tunnel(self):
        return any(self.headers.get(h) for h in ("X-Forwarded-For", "X-Forwarded-Host", "Ngrok-Trace-Id"))

    def guard(self):
        if self.via_tunnel() and not self.path.startswith("/webhook/"):
            self.send_json(403, {"message": "터널로는 /webhook/* 만 받는다"})
            return False
        return True

    def send_json(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_body(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if "application/x-www-form-urlencoded" in (self.headers.get("Content-Type") or ""):
            return {k: v[0] for k, v in urllib.parse.parse_qs(raw.decode()).items()}
        return json.loads(raw or b"{}")

    def do_GET(self):
        if not self.guard():
            return
        path = urllib.parse.urlparse(self.path).path
        try:
            if path == "/api/config":
                return self.send_json(200, {
                    "tossClientKey": TOSS_CLIENT, "impCode": IMP_CODE, "portoneProvider": IMP_PROVIDER,
                    "noticeUrl": f"{PUBLIC_URL}/webhook/portone" if PUBLIC_URL else None})
            if path == "/api/logs":
                lines = LOG.read_text(encoding="utf-8").splitlines()[-80:] if LOG.exists() else []
                return self.send_json(200, [json.loads(l) for l in reversed(lines)])
            if path.startswith("/api/toss/orders/"):
                return self.send_json(200, toss_lookup_order(urllib.parse.unquote(path.rsplit("/", 1)[1])))
            if path.startswith("/api/portone/payments/"):
                return self.send_json(200, portone_payment(path.rsplit("/", 1)[1]))
            if path.startswith("/api/portone/find/"):
                return self.send_json(200, portone_find(urllib.parse.unquote(path.rsplit("/", 1)[1])))
        except Exception as e:  # 실험 도구라 원인을 그대로 보여 준다
            return self.send_json(500, {"message": str(e)})
        return super().do_GET()

    def do_POST(self):
        if not self.guard():
            return
        path = urllib.parse.urlparse(self.path).path
        try:
            body = self.read_body()
            if path == "/api/toss/confirm":
                return self.send_json(200, toss_confirm(body))
            if path == "/api/portone/prepare":
                return self.send_json(200, portone_prepare(body))
            if path == "/webhook/portone":
                # 알림은 계기일 뿐: 본문을 남기고, imp_uid 로 다시 조회한 결과를 같이 남긴다
                log("portone.webhook", body=body, viaTunnel=self.via_tunnel())
                if body.get("imp_uid"):
                    portone_payment(body["imp_uid"])
                return self.send_json(200, {"ok": True})
            if path == "/webhook/toss":
                log("toss.webhook", body=body, viaTunnel=self.via_tunnel())
                return self.send_json(200, {"ok": True})
        except Exception as e:
            return self.send_json(500, {"message": str(e)})
        self.send_json(404, {"message": "없는 경로"})

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    missing = [k for k, v in {"toss.secret-key": TOSS_SECRET, "toss.client-key": TOSS_CLIENT,
                              "portone.v1.imp-code": IMP_CODE, "portone.v1.api-key": IMP_KEY,
                              "portone.v1.api-secret": IMP_SECRET}.items() if not v]
    print(f"키 확인: {'누락 ' + ', '.join(missing) if missing else '모두 있음'}")
    print(f"웹훅 notice_url: {PUBLIC_URL + '/webhook/portone' if PUBLIC_URL else '(PUBLIC_URL 미설정 — 웹훅 안 받음)'}")
    print(f"http://127.0.0.1:{PORT}/")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()

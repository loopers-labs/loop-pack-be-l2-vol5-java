# 로컬 확인용 가짜 webhook 수신기. Alertmanager가 보낸 본문을 경로와 함께 stdout에 한 줄로 남기고 200을 돌려준다.
# 표준 라이브러리만 쓴다. 받은 알림을 어디로도 다시 보내지 않는다.
import json
import os
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY_BYTES = 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY_BYTES:
            self.send_response(413)
            self.end_headers()
            return
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw)
        except ValueError:
            body = raw.decode("utf-8", errors="replace")
        record = {
            "receivedAt": datetime.now(timezone.utc).isoformat(),
            "path": self.path,
            "body": body,
        }
        print(json.dumps(record, ensure_ascii=False), flush=True)
        self.send_response(200)
        self.end_headers()

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok\n")

    def log_message(self, format, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), format % args))


def main():
    port = int(os.environ.get("PORT", "8080"))
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(json.dumps({"listening": port}), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

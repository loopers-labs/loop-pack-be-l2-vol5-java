# 가이드 서버 테스트(10주차 4b). 실행: scripts/ops/check-alerting.sh (python:3.12.11-slim, 네트워크 없음)
# 가짜 Prometheus·Loki·LLM·Slack HTTP 서버를 띄우고 실제 가이드 HTTP 서버에 webhook을 보낸다.
import json
import re
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import guide

CARD_FORMS = ["4111 1111 1111 1111", "4111-1111-1111-1111", "4111111111111111"]
ATTEMPT_KEY = "3f2b8c1e-9a4d-4e6f-b1c2-7d8e9f0a1b2c"


def log_line(message, order_id="101"):
    return json.dumps({
        "@timestamp": "2026-11-25T23:00:00.123+09:00",
        "level": "WARN",
        "logger_name": "com.loopers.domains.order.app.OrderApplicationService",
        "message": message,
        "orderId": order_id,
        "providerOrderId": f"LP-ORD-{int(order_id):010d}",
        "attemptKey": ATTEMPT_KEY,
        "transactionKey": "TR-7c1d2e",
    }, ensure_ascii=False)


WARN_LINES = [
    log_line("PG 결제 생성 시도 결과. orderId=101, providerId=test-pg, result=Unknown, reason=HttpTimeoutException", "101"),
    log_line("PG 결제 생성 시도 결과. orderId=102, providerId=test-pg, result=Unknown, reason=HttpTimeoutException", "102"),
    log_line("PG 결제 생성 시도 결과. orderId=1234567, providerId=test-pg, result=Unknown, reason=HttpTimeoutException",
             "1234567"),
    log_line("PG 조회 판정. orderId=101, source=AUTO, result=Uncertain, kind=NOT_FOUND, "
             "summary=providerOrderId=LP-ORD-0000000101 transactionKey=TR-7c1d2e attemptKey=" + ATTEMPT_KEY
             + " userId=135135, reason=HttpTimeoutException", "101"),
]
CARD_LINE = log_line("PG 결제 생성 시도 결과. orderId=103, providerId=test-pg, result=Unknown, reason=card "
                     + CARD_FORMS[0] + " / " + CARD_FORMS[1] + " / " + CARD_FORMS[2]
                     + ", password=hunter2, Authorization: Bearer abcdefghijklmnop", "103")

ALERT = {
    "version": "4",
    "status": "firing",
    "receiver": "guide-webhook",
    "groupLabels": {"alertname": "PaymentUnresolvedPiling", "severity": "critical"},
    "commonLabels": {"alertname": "PaymentUnresolvedPiling", "severity": "critical"},
    "commonAnnotations": {},
    "alerts": [{
        "status": "firing",
        "labels": {"alertname": "PaymentUnresolvedPiling", "severity": "critical"},
        "annotations": {
            "summary": "결과 모름 결제가 새로 계속 생기고 20건 이상 쌓였습니다. 자동 재조회로 풀리지 않고 있습니다.",
            "runbook_url": "docs/runbook/week10.md#paymentunresolvedpiling",
            "dashboard_url": "http://localhost:3000/d/commerce-payment-incident",
        },
    }],
}


class FakeServer:
    """경로별 응답을 정하는 가짜 HTTP 서버. 받은 요청을 기록한다."""

    def __init__(self, respond):
        self.respond = respond
        self.requests = []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def _handle(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                fake.requests.append({"path": self.path, "body": body, "headers": dict(self.headers)})
                status, payload = fake.respond(self.path, body)
                data = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # 제한 시간 테스트에서 가이드가 먼저 끊은 연결이다.

            do_GET = _handle
            do_POST = _handle

            def log_message(self, format, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def prometheus_ok(path, body):
    query = parse_qs(urlparse(path).query)["query"][0]
    if "commerce_payment_unresolved" in query and "by (status)" in query:
        result = [{"metric": {"status": "unknown"}, "value": [0, "50"]},
                  {"metric": {"status": "needs_review"}, "value": [0, "2"]}]
    elif "commerce_payment_refund_cases" in query and "by (status)" in query:
        result = [{"metric": {"status": "handed_to_human"}, "value": [0, "12"]},
                  {"metric": {"status": "refund_unknown"}, "value": [0, "1"]}]
    elif "refund_outcome" in query:
        result = [{"metric": {}, "value": [0, "4"]}]
    elif "unknown_entered" in query:
        result = [{"metric": {}, "value": [0, "0.006006006006006006"]}]
    else:
        result = [{"metric": {}, "value": [0, "7"]}]
    return 200, {"status": "success", "data": {"resultType": "vector", "result": result}}


def loki_with(lines):
    def respond(path, body):
        return 200, {"status": "success", "data": {"resultType": "streams", "result": [
            {"stream": {"app": "commerce-api", "level": "WARN"}, "values": [[str(i), line] for i, line in enumerate(lines)]},
        ]}}
    return respond


def llm_ok(path, body):
    return 200, {"type": "message", "stop_reason": "end_turn",
                 "content": [{"type": "thinking", "thinking": ""},
                             {"type": "text", "text": "응답 유실로 결과 모름이 쌓이는 것 같다. 유입 비율 패널부터 보라."}]}


def slack_ok(path, body):
    # 실제 Slack webhook과 로컬 가짜 수신기는 JSON이 아닌 본문(ok, 빈 본문)을 돌려준다.
    return 200, b"ok"


class GuideTestBase(unittest.TestCase):
    def start(self, prometheus=prometheus_ok, loki=None, llm=llm_ok, slack=slack_ok, api_key="", timeout=5.0):
        self.prometheus = FakeServer(prometheus)
        self.loki = FakeServer(loki or loki_with(WARN_LINES))
        self.llm = FakeServer(llm)
        self.slack = FakeServer(slack)
        for fake in (self.prometheus, self.loki, self.llm, self.slack):
            self.addCleanup(fake.close)
        config = guide.Config(
            prometheus_url=self.prometheus.url,
            loki_url=self.loki.url,
            slack_url=self.slack.url + "/slack",
            anthropic_api_key=api_key,
            anthropic_base_url=self.llm.url,
            timeout_seconds=timeout,
        )
        self.server, self.guide = guide.serve(config, 0, "127.0.0.1")
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def post_alert(self, raw):
        request = urllib.request.Request(self.url + "/alert", data=raw, method="POST",
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status
        except urllib.error.HTTPError as e:
            return e.code

    def slack_messages(self):
        return [json.loads(r["body"]) for r in self.slack.requests]


class GuideTest(GuideTestBase):
    def test_g1_정상_근거가_붙은_본문을_Slack_자리로_보낸다(self):
        self.start()

        status = self.post_alert(json.dumps(ALERT).encode())

        self.assertEqual(200, status)
        messages = self.slack_messages()
        self.assertEqual(1, len(messages))
        self.assertEqual("guided", messages[0]["guide"])
        text = messages[0]["text"]
        self.assertIn("PaymentUnresolvedPiling", text)
        self.assertIn("unknown=50", text)
        self.assertIn("needs_review=2", text)
        self.assertIn("유입 비율(30분): 0.60%", text)
        self.assertIn("만료 종결 증가(10분): 7", text)
        self.assertIn("3줄 WARN PG 결제 생성 시도 결과. orderId=<값>, providerId=test-pg, result=Unknown, "
                      "reason=HttpTimeoutException", text)
        self.assertIn("docs/runbook/week10.md#paymentunresolvedpiling", text)
        self.assertIn('{app="commerce-api"} | orderId="<주문 번호>"', text)
        self.assertEqual(3, text.count("\n  WARN "), "대표 줄은 max_log_lines(3)까지만")

    def test_g2_Prometheus_오류면_원본을_그대로_보내고_실패를_표시한다(self):
        self.start(prometheus=lambda path, body: (500, {"status": "error"}))
        raw = json.dumps(ALERT, ensure_ascii=False).encode()

        status = self.post_alert(raw)

        self.assertEqual(200, status)
        messages = self.slack_messages()
        self.assertEqual(1, len(messages))
        self.assertEqual("fallback", messages[0]["guide"])
        self.assertTrue(messages[0]["text"].startswith("[가이드 실패: Prometheus 조회 실패"))
        self.assertTrue(messages[0]["text"].endswith(raw.decode()), "원본 본문이 바뀌지 않고 그대로 붙어야 한다")

    def test_g3_제한_시간을_넘으면_유한하게_기다린_뒤_원본을_보낸다(self):
        def slow(path, body):
            time.sleep(3)
            return prometheus_ok(path, body)

        self.start(prometheus=slow, timeout=0.5)
        raw = json.dumps(ALERT).encode()

        started = time.monotonic()
        status = self.post_alert(raw)
        elapsed = time.monotonic() - started

        self.assertEqual(200, status)
        self.assertLess(elapsed, 2.0, f"제한 시간 0.5s인데 {elapsed:.2f}s 기다림")
        messages = self.slack_messages()
        self.assertEqual(1, len(messages))
        self.assertEqual("fallback", messages[0]["guide"])
        self.assertIn("[가이드 실패: ", messages[0]["text"])
        self.assertTrue(messages[0]["text"].endswith(raw.decode()))

    def test_g4_API_키가_없으면_LLM을_부르지_않고_결정적_요약만_보낸다(self):
        self.start(api_key="")

        status = self.post_alert(json.dumps(ALERT).encode())

        self.assertEqual(200, status)
        self.assertEqual(0, len(self.llm.requests))
        text = self.slack_messages()[0]["text"]
        self.assertIn("AI 요약 없음: ANTHROPIC_API_KEY 없음", text)
        self.assertIn("unknown=50", text)

    def test_g5_대표_줄의_카드번호_형태와_비밀값을_가린다(self):
        self.start(loki=loki_with([CARD_LINE]))

        self.post_alert(json.dumps(ALERT).encode())

        text = self.slack_messages()[0]["text"]
        for card in CARD_FORMS:
            self.assertNotIn(card, text)
        self.assertNotIn("hunter2", text)
        self.assertNotIn("abcdefghijklmnop", text)
        self.assertEqual(3 * 2, text.count("[카드 가림]"), "대표 줄과 종류 줄에서 카드 형태 3개씩 가림")
        self.assertIn("orderId=103", text, "사람에게 가는 대표 줄은 주문 번호를 보여 준다")

    def test_g6_깨진_webhook_본문은_400이고_원본_전달을_시도한다(self):
        self.start()
        raw = b'{"status": "firing", "alerts": [ broken'

        status = self.post_alert(raw)

        self.assertEqual(400, status)
        messages = self.slack_messages()
        self.assertEqual(1, len(messages))
        self.assertEqual("fallback", messages[0]["guide"])
        self.assertIn("webhook 본문을 읽지 못함", messages[0]["text"])
        self.assertTrue(messages[0]["text"].endswith(raw.decode()))

    def test_g7_LLM이_받은_본문에는_식별자_형태가_하나도_없다(self):
        self.start(loki=loki_with(WARN_LINES + [CARD_LINE]), api_key="test-key")

        status = self.post_alert(json.dumps(ALERT).encode())

        self.assertEqual(200, status)
        self.assertEqual(1, len(self.llm.requests), "키가 있으면 LLM을 한 번 부른다")
        sent = self.llm.requests[0]["body"].decode("utf-8")
        content = json.loads(sent)["messages"][0]["content"]
        forbidden = {
            "PG 주문 번호": r"LP-ORD-",
            "거래 키": r"TR-[A-Za-z0-9]",
            "UUID attemptKey": r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-",
            "식별자 키 뒤 값": r"(orderId|providerOrderId|attemptKey|transactionKey|userId)\s*[=:]\s*\"?[0-9A-Za-z]",
            "주문 번호 숫자": r"\b(101|102|103|1234567|135135)\b",
            "카드": r"4111",
            "비밀값": r"hunter2|abcdefghijklmnop",
        }
        for name, pattern in forbidden.items():
            self.assertIsNone(re.search(pattern, sent), f"{name} 형태가 LLM 본문에 있음: {re.search(pattern, sent)}")
        self.assertIn("result=Unknown", content, "패턴(종류)은 보낸다")
        self.assertIn('"count": 3', content, "종류별 건수는 보낸다")
        self.assertIn("AI 요약(집계·패턴만 보냄):", self.slack_messages()[0]["text"])

    def test_g7b_보낼_본문에_식별자가_남으면_LLM을_부르지_않는다(self):
        for leaked in ["orderId=5", "LP-ORD-0000000005", "TR-abc", ATTEMPT_KEY, "4111111111111111"]:
            with self.subTest(leaked=leaked):
                self.assertFalse(guide.llm_payload_is_clean(json.dumps({"x": "a " + leaked})))
        self.assertTrue(guide.llm_payload_is_clean(json.dumps({"pattern": "orderId=<값>, result=Unknown", "count": 3})))

    def test_g7c_reason_causeType이_예외_클래스_이름_형태면_LLM_본문에_살리고_식별자_형태면_가린다(self):
        exception_line = log_line("PG 결제 생성 시도 결과. orderId=104, providerId=test-pg, result=Unknown, "
                                  "reason=ResourceAccessException(HttpTimeoutException), "
                                  "causeType=ResourceAccessException(HttpTimeoutException)", "104")
        identifier_lines = [
            log_line("PG 조회 판정. orderId=105, result=Uncertain, reason=LP-ORD-0000000105", "105"),
            log_line("PG 조회 판정. orderId=106, result=Uncertain, reason=Failed(LP-ORD-0000000106)", "106"),
            log_line("PG 조회 판정. orderId=107, result=Uncertain, causeType=Timeout(TR-7c1d2e)", "107"),
            log_line("PG 조회 판정. orderId=108, result=Uncertain, causeType=Order108Exception(Retry)", "108"),
            log_line("PG 조회 판정. orderId=109, result=Uncertain, reason=Failed(" + ATTEMPT_KEY + ")", "109"),
        ]
        self.start(loki=loki_with([exception_line] + identifier_lines), api_key="test-key")

        self.post_alert(json.dumps(ALERT).encode())

        self.assertEqual(1, len(self.llm.requests))
        sent = self.llm.requests[0]["body"].decode("utf-8")
        content = json.loads(sent)["messages"][0]["content"]
        self.assertIn("reason=ResourceAccessException(HttpTimeoutException)", content)
        self.assertIn("causeType=ResourceAccessException(HttpTimeoutException)", content)
        self.assertIn("result=Uncertain, reason=<값>", content, "식별자가 섞인 reason 값은 가린다")
        self.assertIn("result=Uncertain, causeType=<값>", content, "식별자·숫자가 섞인 causeType 값은 가린다")
        for leaked in ["LP-ORD-", "TR-7c1d2e", ATTEMPT_KEY, "Exception(Retry)", "Failed("]:
            self.assertNotIn(leaked, sent)

    def test_g8_Slack_전송이_실패하면_502로_답해_Alertmanager가_재시도하게_한다(self):
        self.start(slack=lambda path, body: (500, b"server error"))

        status = self.post_alert(json.dumps(ALERT).encode())

        self.assertEqual(502, status)
        self.assertEqual(1, len(self.slack.requests), "근거 메시지 전송을 한 번 시도한다")
        self.assertEqual("guided", self.slack_messages()[0]["guide"])

    def test_g9_LLM이_오류를_내면_결정적_근거_메시지는_그대로_가고_LLM_호출_실패를_표시한다(self):
        self.start(llm=lambda path, body: (500, {"type": "error"}), api_key="test-key")

        status = self.post_alert(json.dumps(ALERT).encode())

        self.assertEqual(200, status)
        self.assertEqual(1, len(self.llm.requests))
        messages = self.slack_messages()
        self.assertEqual(1, len(messages))
        self.assertEqual("guided", messages[0]["guide"])
        self.assertIn("unknown=50", messages[0]["text"])
        self.assertIn("AI 요약 없음: LLM 호출 실패(HTTPError)", messages[0]["text"])

    def test_g10_LLM이_제한_시간을_넘기면_결정적_근거_메시지는_그대로_가고_LLM_호출_실패를_표시한다(self):
        def slow_llm(path, body):
            time.sleep(3)
            return llm_ok(path, body)

        self.start(llm=slow_llm, api_key="test-key", timeout=2.0)

        started = time.monotonic()
        status = self.post_alert(json.dumps(ALERT).encode())
        elapsed = time.monotonic() - started

        self.assertEqual(200, status)
        self.assertLess(elapsed, 2.5, f"제한 시간 2s인데 {elapsed:.2f}s 기다림")
        self.assertEqual(1, len(self.llm.requests))
        messages = self.slack_messages()
        self.assertEqual(1, len(messages))
        self.assertEqual("guided", messages[0]["guide"], "LLM 제한 시간 초과로 원본 fallback이 되면 안 된다")
        self.assertIn("unknown=50", messages[0]["text"])
        self.assertIn("AI 요약 없음: LLM 호출 실패(", messages[0]["text"])

    def test_g11_환불_누적_critical에는_환불_확인_대상_상태별과_사람_이관_증가를_근거로_붙인다(self):
        self.start(api_key="test-key")
        alert = json.loads(json.dumps(ALERT))
        alert["groupLabels"]["alertname"] = alert["commonLabels"]["alertname"] = "PaymentRefundPiling"
        alert["alerts"][0]["labels"]["alertname"] = "PaymentRefundPiling"
        alert["alerts"][0]["annotations"]["runbook_url"] = "docs/runbook/week10.md#paymentrefundpiling"

        status = self.post_alert(json.dumps(alert).encode())

        self.assertEqual(200, status)
        text = self.slack_messages()[0]["text"]
        self.assertIn("환불 확인 대상 상태별: handed_to_human=12, refund_unknown=1", text)
        self.assertIn("사람 이관 증가(30분): 4", text)
        sent = json.loads(self.llm.requests[0]["body"].decode("utf-8"))["messages"][0]["content"]
        self.assertIn('"handed_to_human": 12', sent, "LLM에도 집계만 보낸다")
        self.assertIn("환불 확인 대상 상태별", sent, "어디부터 볼지에 환불 패널 이름을 준다")

    def test_metrics_가동과_처리_실패_수를_낸다(self):
        self.start(prometheus=lambda path, body: (500, {"status": "error"}))
        self.post_alert(json.dumps(ALERT).encode())

        with urllib.request.urlopen(self.url + "/metrics", timeout=5) as response:
            body = response.read().decode()

        self.assertIn("guide_up 1", body)
        self.assertIn("guide_webhooks_received_total 1", body)
        self.assertIn("guide_fallback_sent_total 1", body)
        self.assertIn("guide_guided_sent_total 0", body)


if __name__ == "__main__":
    unittest.main()

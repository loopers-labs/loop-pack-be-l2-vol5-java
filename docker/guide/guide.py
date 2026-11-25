# critical 알림 가이드 서버(10주차 4b). 표준 라이브러리만 쓴다.
# 흐름: Alertmanager webhook 수신 → Prometheus·Loki에서 근거를 결정적으로 모음 → (키가 있으면) LLM 요약 → Slack 자리로 전송.
# 원본 보장: 근거 수집이 실패하거나 제한 시간을 넘으면 받은 원본 본문을 그대로, 실패 표시 한 줄과 함께 보낸다.
#   Alertmanager는 critical을 Slack 자리에도 따로 보낸다(continue). 이 서버가 죽어도 사람은 원본을 받는다.
# LLM에는 집계와 패턴만 보낸다(사용자 결정 w10s4b-1). 주문 번호·PG 주문 번호·시도 키·거래 키·사용자 ID·카드·비밀값은
#   가린 뒤에도 남아 있으면 LLM을 부르지 않는다(fail closed).
# 사람에게 가는 결정적 메시지는 대표 로그 줄에 주문 번호를 보여 줄 수 있다. 카드·비밀값은 가리고 줄 수는 제한한다.
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeout
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY_BYTES = 1024 * 1024
ANTHROPIC_VERSION = "2026-10-21"

# 결제 사고 근거. 규칙 파일(payment.yml)의 식과 같은 창을 쓴다.
QUERIES = {
    "unresolved_by_status": "max by (status) (commerce_payment_unresolved)",
    "unknown_inflow_ratio_30m": (
        "sum(increase(commerce_payment_unknown_entered_total[30m]))"
        " / clamp_min(sum(increase(commerce_payment_started_total[30m])), 1)"
    ),
    "expired_increase_10m": 'sum(increase(commerce_payment_outcome_total{result="expired"}[10m]))',
}
# 경계 로그는 logger OrderApplicationService가 남긴다(설계 문서 10주차 2-2). label은 app·level만 있다.
LOKI_QUERY = '{app="commerce-api", level=~"WARN|ERROR"} |= "OrderApplicationService"'
LOKI_WINDOW_SECONDS = 30 * 60
LOKI_LIMIT = 1000
# LLM 호출은 전체 제한 시간보다 이만큼 먼저 끝낸다. 같은 시각에 끝나면 바깥 대기가 먼저 끝나 결정적 메시지 대신 원본이 간다.
LLM_RESERVE_SECONDS = 0.5
LLM_MIN_SECONDS = 0.5

# 값이 유한한 분류 키만 패턴에 남긴다. 그 밖의 key=value 값은 모두 가린다.
ENUM_KEYS = {"result", "source", "status", "providerStatus", "kind", "causeType", "reason", "providerId"}
ENUM_VALUE = re.compile(r"^[A-Za-z][A-Za-z_-]{0,39}$")
KEY_VALUE = re.compile(r"(\b[A-Za-z][A-Za-z0-9_]*)=([^,\s]+)")
# reason·causeType은 예외 클래스 이름 형태(대문자로 시작하는 영문 이름과 괄호 중첩, 예: A(B(C)))도 살린다. 숫자·하이픈이 섞이면 가린다.
EXCEPTION_KEYS = {"reason", "causeType"}
EXCEPTION_VALUE = re.compile(r"^[A-Z][A-Za-z]*(?:\([A-Z][A-Za-z]*)*\)*$")

CARD = re.compile(r"(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)")
SECRET_KV = re.compile(
    r"(?i)\b(password|passwd|secret|token|api[_-]?key|authorization|card[_-]?no|card[_-]?number|cvc|cvv)"
    r"(\s*[=:]\s*)(\"[^\"]*\"|[^,\s]+)"
)
BEARER = re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}")
API_KEY_LIKE = re.compile(r"\bsk-[A-Za-z0-9_-]{8,}")

PROVIDER_ORDER_ID = re.compile(r"\bLP-ORD-[A-Za-z0-9-]+")
TRANSACTION_KEY = re.compile(r"\bTR-[A-Za-z0-9-]+")
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
DIGITS = re.compile(r"\d+")
ID_KEYS = r"(?:orderId|providerOrderId|attemptKey|transactionKey|userId|memberId|loginId)"
# LLM으로 나가기 직전 마지막 확인. 하나라도 걸리면 LLM을 부르지 않는다.
LLM_FORBIDDEN = [
    PROVIDER_ORDER_ID,
    TRANSACTION_KEY,
    UUID,
    re.compile(ID_KEYS + r"\s*[=:]\s*(?:\\?\")?[^\s<\\\",}]"),
    re.compile(r"(?<!\d)\d{6,}(?!\d)"),
    CARD,
    API_KEY_LIKE,
]


@dataclass
class Config:
    prometheus_url: str
    loki_url: str
    slack_url: str
    anthropic_api_key: str = ""
    anthropic_base_url: str = "https://api.anthropic.com"
    llm_model: str = "claude-opus-5-5"
    timeout_seconds: float = 10.0
    max_log_lines: int = 3
    dashboard_url: str = "http://localhost:3000/d/commerce-payment-incident"
    runbook_base: str = "docs/runbook/week10.md"

    @staticmethod
    def from_env():
        return Config(
            prometheus_url=os.environ.get("PROMETHEUS_URL", "http://prometheus:9090"),
            loki_url=os.environ.get("LOKI_URL", "http://loki:3100"),
            slack_url=os.environ.get("SLACK_URL", "http://fake-receiver:8080/slack"),
            anthropic_api_key=os.environ.get("ANTHROPIC_API_KEY", ""),
            anthropic_base_url=os.environ.get("ANTHROPIC_BASE_URL", "https://api.anthropic.com"),
            llm_model=os.environ.get("GUIDE_LLM_MODEL", "claude-opus-5-5"),
            timeout_seconds=float(os.environ.get("GUIDE_TIMEOUT_SECONDS", "10")),
            max_log_lines=int(os.environ.get("GUIDE_MAX_LOG_LINES", "3")),
        )


class Metrics:
    def __init__(self):
        self._lock = threading.Lock()
        self.counters = {
            "guide_webhooks_received_total": 0,
            "guide_guided_sent_total": 0,
            "guide_fallback_sent_total": 0,
            "guide_bad_request_total": 0,
            "guide_slack_send_failures_total": 0,
            "guide_llm_requests_total": 0,
            "guide_llm_failures_total": 0,
            "guide_llm_blocked_total": 0,
        }

    def inc(self, name):
        with self._lock:
            self.counters[name] += 1

    def render(self):
        lines = ["# TYPE guide_up gauge", "guide_up 1"]
        with self._lock:
            for name, value in self.counters.items():
                lines.append(f"# TYPE {name} counter")
                lines.append(f"{name} {value}")
        return "\n".join(lines) + "\n"


class EvidenceError(Exception):
    pass


def mask_sensitive(text):
    # Bearer·Basic 토큰을 먼저 가린다. 'Authorization: Bearer x'에서 키-값 규칙이 'Bearer'만 가리고 토큰을 남기지 않게.
    text = BEARER.sub(lambda m: f"{m.group(1)} [가림]", text)
    text = SECRET_KV.sub(lambda m: f"{m.group(1)}{m.group(2)}[가림]", text)
    text = API_KEY_LIKE.sub("[가림]", text)
    return CARD.sub("[카드 가림]", text)


def to_pattern(message):
    """경계 로그 문구에서 식별자와 숫자를 모두 지운 '종류'를 만든다. LLM과 집계 키로 쓴다."""
    def replace(m):
        key, value = m.group(1), m.group(2)
        if key in ENUM_KEYS and ENUM_VALUE.match(value):
            return f"{key}={value}"
        if key in EXCEPTION_KEYS and is_exception_name(value):
            return f"{key}={value}"
        return f"{key}=<값>"

    text = mask_sensitive(message)
    text = KEY_VALUE.sub(replace, text)
    text = PROVIDER_ORDER_ID.sub("<PG주문번호>", text)
    text = TRANSACTION_KEY.sub("<거래키>", text)
    text = UUID.sub("<키>", text)
    return DIGITS.sub("#", text)


def is_exception_name(value):
    return len(value) <= 120 and bool(EXCEPTION_VALUE.match(value)) and value.count("(") == value.count(")")


def llm_payload_is_clean(text):
    return not any(p.search(text) for p in LLM_FORBIDDEN)


def http_json(url, timeout, data=None, headers=None):
    body = None if data is None else json.dumps(data).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers=headers or {}, method="POST" if body else "GET")
    if body is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=max(timeout, 0.05)) as response:
        return json.loads(response.read())


def remaining(deadline):
    return deadline - time.monotonic()


def query_prometheus(config, deadline):
    results = {}
    for name, expr in QUERIES.items():
        url = f"{config.prometheus_url}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
        try:
            body = http_json(url, remaining(deadline))
        except (urllib.error.URLError, OSError, ValueError) as e:
            raise EvidenceError(f"Prometheus 조회 실패({name}: {type(e).__name__})") from e
        if body.get("status") != "success":
            raise EvidenceError(f"Prometheus 조회 실패({name}: status={body.get('status')})")
        results[name] = body["data"]["result"]
    unresolved = {}
    for sample in results["unresolved_by_status"]:
        unresolved[sample["metric"].get("status", "?")] = round(float(sample["value"][1]), 1)

    # 반올림한다. 긴 소수 자릿수가 숫자 ID처럼 보여 LLM 전송 확인에 걸리지 않게 하기 위해서다.
    def scalar(rows):
        return round(float(rows[0]["value"][1]), 4) if rows else 0.0

    return {
        "unresolved_by_status": dict(sorted(unresolved.items())),
        "unknown_inflow_ratio_30m": scalar(results["unknown_inflow_ratio_30m"]),
        "expired_increase_10m": scalar(results["expired_increase_10m"]),
    }


def query_loki(config, deadline, now_seconds):
    params = {
        "query": LOKI_QUERY,
        "start": str(int((now_seconds - LOKI_WINDOW_SECONDS) * 1e9)),
        "end": str(int(now_seconds * 1e9)),
        "limit": str(LOKI_LIMIT),
        "direction": "backward",
    }
    url = f"{config.loki_url}/loki/api/v1/query_range?" + urllib.parse.urlencode(params)
    try:
        body = http_json(url, remaining(deadline))
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise EvidenceError(f"Loki 조회 실패({type(e).__name__})") from e
    if body.get("status") != "success":
        raise EvidenceError(f"Loki 조회 실패(status={body.get('status')})")
    kinds = {}
    samples = []
    for stream in body["data"]["result"]:
        level = stream.get("stream", {}).get("level", "?")
        for _, line in stream.get("values", []):
            try:
                message = json.loads(line).get("message", "")
            except ValueError:
                message = line
            key = (level, to_pattern(message))
            kinds[key] = kinds.get(key, 0) + 1
            samples.append(f"{level} {mask_sensitive(message)}")
    ordered = sorted(kinds.items(), key=lambda item: (-item[1], item[0]))
    return {
        "kinds": [{"level": level, "pattern": pattern, "count": count} for (level, pattern), count in ordered],
        "samples": samples[: config.max_log_lines],
        "total": sum(kinds.values()),
    }


def collect_evidence(config, alert, deadline):
    return {
        "prometheus": query_prometheus(config, deadline),
        "loki": query_loki(config, deadline, time.time()),
    }


def alert_overview(alert):
    alerts = alert.get("alerts") or []
    names = sorted({a.get("labels", {}).get("alertname", "?") for a in alerts}) or [
        alert.get("groupLabels", {}).get("alertname", "?")
    ]
    annotations = (alerts[0].get("annotations") if alerts else None) or alert.get("commonAnnotations") or {}
    return {
        "status": alert.get("status", "?"),
        "alertnames": names,
        "severity": alert.get("commonLabels", {}).get("severity", "?"),
        "count": len(alerts),
        "summary": annotations.get("summary", ""),
        "runbook_url": annotations.get("runbook_url", ""),
        "dashboard_url": annotations.get("dashboard_url", ""),
    }


def build_llm_input(overview, evidence, config):
    """LLM에 보낼 집계·패턴. 식별자·숫자 ID·로그 원문은 넣지 않는다."""
    return {
        "alert": {
            "status": overview["status"],
            "alertnames": overview["alertnames"],
            "severity": overview["severity"],
            "count": overview["count"],
            "summary": to_pattern(overview["summary"]),
        },
        "metrics": evidence["prometheus"],
        "boundary_log_kinds": evidence["loki"]["kinds"][:15],
        "where_to_look": {
            "dashboard_panels": ["미확인 결제 상태별", "시작 대비 결과 모름 유입 비율(30분)", "만료 종결 증가(10분)",
                                 "주문 번호로 경계 로그"],
            "runbook_anchors": [f"{config.runbook_base}#{name.lower()}" for name in overview["alertnames"]],
            "loki_query_examples": [
                '{app="commerce-api", level="WARN"} |= "result=Unknown"',
                '{app="commerce-api"} | orderId="<주문 번호>"',
            ],
        },
    }


LLM_SYSTEM = (
    "너는 결제 사고 당직자를 돕는다. 받은 것은 집계와 로그 종류(패턴)뿐이다. 개별 주문·식별자는 없고 추측하지 않는다. "
    "한국어로 5줄 이내: 1) 무엇이 일어나는 것 같은지(가설과 근거 숫자) 2) 어디부터 볼지(대시보드 패널 이름, runbook 앵커, "
    "Loki 쿼리 예시 중에서 고른다). 확정적으로 단정하지 말고, 조치(환불·확정)를 지시하지 않는다."
)


def call_llm(config, llm_input, timeout):
    body = {
        "model": config.llm_model,
        "max_tokens": 1024,
        "output_config": {"effort": "low"},
        "system": LLM_SYSTEM,
        "messages": [{"role": "user", "content": json.dumps(llm_input, ensure_ascii=False)}],
    }
    headers = {"x-api-key": config.anthropic_api_key, "anthropic-version": ANTHROPIC_VERSION}
    response = http_json(f"{config.anthropic_base_url}/v1/messages", timeout, data=body, headers=headers)
    if response.get("stop_reason") == "refusal":
        raise EvidenceError("LLM 거절")
    text = "\n".join(b.get("text", "") for b in response.get("content", []) if b.get("type") == "text").strip()
    if not text:
        raise EvidenceError("LLM 빈 응답")
    return text


def render_guided(overview, evidence, ai_text, ai_note):
    p = evidence["prometheus"]
    lines = [
        f"[가이드] {overview['status']} {', '.join(overview['alertnames'])} ({overview['severity']}, {overview['count']}건)",
        overview["summary"],
        "",
        "근거(Prometheus):",
        "- 미확인 상태별: " + (", ".join(f"{k}={v:g}" for k, v in p["unresolved_by_status"].items()) or "없음"),
        f"- 결과 모름 유입 비율(30분): {p['unknown_inflow_ratio_30m'] * 100:.2f}%",
        f"- 만료 종결 증가(10분): {p['expired_increase_10m']:g}",
        f"근거(Loki, 최근 30분 경계 로그 WARN·ERROR {evidence['loki']['total']}줄):",
    ]
    lines += [f"- {k['count']}줄 {k['level']} {k['pattern']}" for k in evidence["loki"]["kinds"][:5]] or ["- 없음"]
    if evidence["loki"]["samples"]:
        lines.append("대표 줄:")
        lines += [f"  {s}" for s in evidence["loki"]["samples"]]
    lines += [
        "",
        f"대시보드: {overview['dashboard_url']}",
        f"runbook: {overview['runbook_url']}",
        'Loki: {app="commerce-api"} | orderId="<주문 번호>"',
    ]
    if ai_text:
        lines += ["", "AI 요약(집계·패턴만 보냄):", ai_text]
    elif ai_note:
        lines += ["", f"AI 요약 없음: {ai_note}"]
    return "\n".join(lines)


def render_fallback(raw_body, reason):
    original = raw_body.decode("utf-8", errors="replace")
    return f"[가이드 실패: {reason}] 원본 알림을 그대로 보냅니다.\n{original}"


class Guide:
    def __init__(self, config, metrics=None):
        self.config = config
        self.metrics = metrics or Metrics()
        self._pool = ThreadPoolExecutor(max_workers=4)

    def send_slack(self, payload):
        # Slack webhook은 JSON이 아닌 본문("ok")을 돌려준다. 2xx면 성공으로 보고 본문은 읽지 않는다.
        request = urllib.request.Request(self.config.slack_url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                                         headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=5.0):
                return True
        except (urllib.error.URLError, OSError):
            self.metrics.inc("guide_slack_send_failures_total")
            return False

    def send_fallback(self, raw_body, reason):
        self.metrics.inc("guide_fallback_sent_total")
        return self.send_slack({"text": render_fallback(raw_body, reason), "guide": "fallback"})

    def _guided_text(self, alert, deadline):
        overview = alert_overview(alert)
        evidence = collect_evidence(self.config, alert, deadline)
        ai_text, ai_note = None, None
        if not self.config.anthropic_api_key:
            ai_note = "ANTHROPIC_API_KEY 없음"
        else:
            llm_input = json.dumps(build_llm_input(overview, evidence, self.config), ensure_ascii=False)
            if not llm_payload_is_clean(llm_input):
                self.metrics.inc("guide_llm_blocked_total")
                ai_note = "보낼 본문에 식별자 형태가 남아 보내지 않음"
            elif remaining(deadline) - LLM_RESERVE_SECONDS < LLM_MIN_SECONDS:
                ai_note = "제한 시간 부족"
            else:
                self.metrics.inc("guide_llm_requests_total")
                try:
                    ai_text = call_llm(self.config, json.loads(llm_input), remaining(deadline) - LLM_RESERVE_SECONDS)
                except (urllib.error.URLError, OSError, ValueError, EvidenceError) as e:
                    self.metrics.inc("guide_llm_failures_total")
                    ai_note = f"LLM 호출 실패({type(e).__name__})"
        return render_guided(overview, evidence, ai_text, ai_note)

    def handle(self, raw_body):
        """(HTTP 상태, Slack 전송 성공 여부)를 돌려준다."""
        self.metrics.inc("guide_webhooks_received_total")
        try:
            alert = json.loads(raw_body)
            if not isinstance(alert, dict):
                raise ValueError("object 아님")
        except ValueError:
            self.metrics.inc("guide_bad_request_total")
            self.send_fallback(raw_body, "webhook 본문을 읽지 못함")
            return 400, False
        deadline = time.monotonic() + self.config.timeout_seconds
        future = self._pool.submit(self._guided_text, alert, deadline)
        try:
            text = future.result(timeout=self.config.timeout_seconds)
        except FutureTimeout:
            sent = self.send_fallback(raw_body, f"제한 시간 {self.config.timeout_seconds:g}s 초과")
            return (200 if sent else 502), sent
        except Exception as e:  # 근거 수집 어떤 실패든 원본을 보낸다. 원인 종류는 실패 표시에 남긴다.
            reason = str(e) if isinstance(e, EvidenceError) else f"근거 수집 오류({type(e).__name__})"
            sent = self.send_fallback(raw_body, reason)
            return (200 if sent else 502), sent
        sent = self.send_slack({"text": text, "guide": "guided"})
        if sent:
            self.metrics.inc("guide_guided_sent_total")
            return 200, True
        return 502, False


def make_handler(guide):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/alert":
                self._reply(404, b"not found\n")
                return
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY_BYTES:
                self._reply(413, b"too large\n")
                return
            status, _ = guide.handle(self.rfile.read(length))
            self._reply(status, b"ok\n" if status == 200 else b"see slack\n")

        def do_GET(self):
            if self.path == "/metrics":
                self._reply(200, guide.metrics.render().encode(), "text/plain; version=0.0.4")
            elif self.path == "/healthz":
                self._reply(200, b"ok\n")
            else:
                self._reply(404, b"not found\n")

        def _reply(self, status, body, content_type="text/plain"):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            sys.stderr.write("%s %s\n" % (self.address_string(), format % args))

    return Handler


def serve(config, port, host="0.0.0.0"):
    guide = Guide(config)
    server = ThreadingHTTPServer((host, port), make_handler(guide))
    return server, guide


def main():
    port = int(os.environ.get("PORT", "8080"))
    server, _ = serve(Config.from_env(), port)
    print(json.dumps({"listening": port}), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

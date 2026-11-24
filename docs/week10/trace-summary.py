"""Tempo가 돌려준 trace 하나(OTLP JSON)를 span 목록으로 줄인다 — run.sh가 jq로 검사한다 (plan.md D-12).

실행: python3 -I docs/week10/trace-summary.py <trace.json>
출력: {"services": [...], "spans": [{"service", "name", "kind", "durationMs", "error", "url"}...]} (시작 순)
Tempo 버전에 따라 batches/resourceSpans, scopeSpans/instrumentationLibrarySpans 이름이 다르다 — 둘 다 읽는다.
"""
import json
import sys


def attributes(items):
    found = {}
    for item in items or []:
        value = item.get("value", {})
        found[item.get("key")] = next(iter(value.values()), None) if value else None
    return found


def main(path):
    with open(path, encoding="utf-8") as f:
        trace = json.load(f)
    trace = trace.get("trace", trace)
    batches = trace.get("batches") or trace.get("resourceSpans") or []
    spans = []
    for batch in batches:
        service = attributes(batch.get("resource", {}).get("attributes")).get("service.name", "?")
        for scope in batch.get("scopeSpans") or batch.get("instrumentationLibrarySpans") or []:
            for span in scope.get("spans", []):
                attrs = attributes(span.get("attributes"))
                start, end = int(span.get("startTimeUnixNano", 0)), int(span.get("endTimeUnixNano", 0))
                status = span.get("status", {}).get("code", "")
                exception = attrs.get("exception") or attrs.get("error")
                spans.append({
                    "service": service,
                    "name": span.get("name"),
                    "kind": span.get("kind", ""),
                    "start": start,
                    "durationMs": round((end - start) / 1_000_000),
                    "error": status in ("STATUS_CODE_ERROR", 2) or (exception not in (None, "", "none")),
                    "url": attrs.get("http.url") or attrs.get("url.full") or attrs.get("uri") or "",
                })
    spans.sort(key=lambda s: s["start"])
    for span in spans:
        del span["start"]
    print(json.dumps({"services": sorted({s["service"] for s in spans}), "spans": spans}, ensure_ascii=False))


if __name__ == "__main__":
    main(sys.argv[1])

#!/usr/bin/env bash
# W10 AI 진단 실험 (plan.md D-13 ⑥) — fault 상태에서 AI가 Grafana MCP만으로 가설을 판정한다.
#
#   1) PAUSE_AFTER_FAULT=1 KEEP=1 bash docs/week10/run.sh      → "멈춤"이 찍힐 때까지 기다린다
#   2) bash docs/week10/ai-diagnose.sh docs/week10/results/<실행 ID>
#   3) touch <작업 폴더>/continue                               → run.sh가 recovery로 이어 간다
#
# AI는 빈 폴더에서 돈다 — 저장소 코드 · 설계 문서 · run.sh의 판정(2-fault/hypotheses.tsv)을 보지 못한다.
# 내장 도구(파일 읽기 · 셸)는 끄고(--tools ""), MCP는 example.mcp.json의 읽기 전용 Grafana MCP 하나만 쓴다.
# 결과: <결과 폴더>/ai-diagnosis/ — prompt.md · stream.jsonl(원문) · queries.txt(쓴 질의) · answer.md(답)
# AI 판정은 결론이 아니다. 사람이 hypotheses.tsv · 질의 결과와 대조해 compare.md에 적는다.
set -euo pipefail

REPO="$(git rev-parse --show-toplevel)"
W10="$REPO/docs/week10"
RESULT="${1:?사용: bash docs/week10/ai-diagnose.sh docs/week10/results/<실행 ID>}"
OUT="$RESULT/ai-diagnosis"
mkdir -p "$OUT"
cp "$W10/ai-diagnosis-prompt.md" "$OUT/prompt.md"
date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/started-at.txt"

EMPTY="$(mktemp -d "${TMPDIR:-/tmp}/w10-ai.XXXX")"
(cd "$EMPTY" && claude -p "$(cat "$W10/ai-diagnosis-prompt.md")" \
  --mcp-config "$W10/example.mcp.json" --strict-mcp-config \
  --tools "" --allowedTools "mcp__grafana-w10" \
  --output-format stream-json --verbose --no-session-persistence) > "$OUT/stream.jsonl"
rm -rf "$EMPTY"

jq -r 'select(.type == "result") | .result' "$OUT/stream.jsonl" > "$OUT/answer.md"
jq -r 'select(.type == "assistant") | .message.content[]? | select(.type == "tool_use") | "\(.name)\t\(.input | tojson)"' \
  "$OUT/stream.jsonl" > "$OUT/queries.txt"
echo "답: $OUT/answer.md · 쓴 질의 $(wc -l < "$OUT/queries.txt" | tr -d ' ')개: $OUT/queries.txt"

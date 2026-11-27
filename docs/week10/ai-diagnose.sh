#!/usr/bin/env bash
# W10 AI 진단 실험 (plan.md D-13 ⑥) — fault 상태에서 AI가 Grafana MCP만으로 가설을 판정한다.
#
#   1) PAUSE_AFTER_FAULT=1 KEEP=1 bash docs/week10/run.sh      → "멈춤"이 찍힐 때까지 기다린다
#   2) bash docs/week10/ai-diagnose.sh docs/week10/results/<실행 ID>
#   3) touch <작업 폴더>/continue                               → run.sh가 recovery로 이어 간다
#
# AI는 빈 폴더에서 돈다 — 저장소 코드 · 설계 문서 · run.sh의 판정(2-fault/hypotheses.tsv)을 보지 못한다.
# 내장 도구(파일 읽기 · 셸)는 끄고(--tools ""), MCP는 example.mcp.json의 읽기 전용 Grafana MCP 하나만 쓴다.
# 결과: <결과 폴더>/ai-diagnosis/ — prompt.md · transcript.jsonl(질의·결과·답) · queries.txt(쓴 질의) · answer.md(답)
#   원문 stream에는 이 컴퓨터의 홈 경로·설치 플러그인·세션 ID가 들어 있어 저장소에 두지 않는다 — 대화만 남기고 홈 경로는 ~로 가린다
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
  --output-format stream-json --verbose --no-session-persistence) > "$EMPTY.stream.jsonl"
rm -rf "$EMPTY"

STREAM="$EMPTY.stream.jsonl"
jq -c 'select(.type == "assistant" or .type == "user" or .type == "result")
  | if .type == "result" then {type, num_turns, duration_ms, total_cost_usd, result}
    else {type, content: [.message.content[]? | select(.type != "thinking")]} end' "$STREAM" \
  | sed -e "s#$HOME#~#g" -E -e 's#/(private/)?var/folders/[^"]*/w10-ai\.[A-Za-z0-9]+#<빈 작업 폴더>#g' > "$OUT/transcript.jsonl"
jq -r 'select(.type == "result") | .result' "$OUT/transcript.jsonl" > "$OUT/answer.md"
jq -r 'select(.type == "assistant") | .content[] | select(.type == "tool_use") | "\(.name)\t\(.input | tojson)"' \
  "$OUT/transcript.jsonl" > "$OUT/queries.txt"
rm -f "$STREAM"
echo "답: $OUT/answer.md · 쓴 질의 $(wc -l < "$OUT/queries.txt" | tr -d ' ')개: $OUT/queries.txt"

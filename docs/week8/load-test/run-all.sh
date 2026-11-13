#!/usr/bin/env bash
# C를 차례로 바꿔 가며 단계마다 앱을 새로 띄워 측정하고, 끝나면 앱을 내린다.
#   ./run-all.sh            → C = 1 3 6 12 24 40
#   ./run-all.sh 3 12       → 고른 C만
#   VU_EQ_C=1 ./run-all.sh  → 보조 측정: 발급 VU = C (결과 접두사 vu-eq-c-)
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
CS=("$@"); [ ${#CS[@]} -eq 0 ] && CS=(1 3 6 12 24 40)
for c in "${CS[@]}"; do
  if [ -n "${VU_EQ_C:-}" ]; then
    VUS="$c" PREFIX=vu-eq-c "$DIR/run-stage.sh" "$c"
  else
    "$DIR/run-stage.sh" "$c"
  fi
done
pid="$(powershell.exe -NoProfile -Command '(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).OwningProcess' | tr -d '\r')"
[ -n "$pid" ] && powershell.exe -NoProfile -Command "Stop-Process -Id $pid -Force" > /dev/null || true
echo "all done $(date +%H:%M:%S)"

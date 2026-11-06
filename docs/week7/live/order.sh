#!/usr/bin/env bash
# 주문 1건: 주문 생성 → 확정 요청(카드 결제). pg-sim이 1초 뒤 SUCCESS callback → 확정 TX에 Outbox 행 → relay가 1초 안에 발행.
# 사용: bash docs/week7/live/order.sh [사용자ID] [상품ID:수량 ...]   예) bash docs/week7/live/order.sh 7 3:2 1:1
set -euo pipefail
USER_ID="${1:-7}"; shift || true
ITEMS="${*:-3:2 1:1}"
JSON=$(python3 -c "import json,sys;print(json.dumps({'items':[{'productId':int(a),'quantity':int(b)} for a,b in (x.split(':') for x in sys.argv[1:])]}))" $ITEMS)
ORDER=$(curl -s -XPOST localhost:8080/api/v1/orders -H 'Content-Type: application/json' -H "X-USER-ID: $USER_ID" -d "$JSON")
ID=$(echo "$ORDER" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['id'])") || { echo "주문 실패: $ORDER"; exit 1; }
CONFIRM=$(curl -s -o /dev/null -w '%{http_code}' -XPOST "localhost:8080/api/v1/orders/$ID/confirm" -H 'Content-Type: application/json' \
  -H "X-USER-ID: $USER_ID" -H "Idempotency-Key: live-$ID-$(date +%s)" -d '{"cardType":"SAMSUNG","cardNo":"1234-5678-9814-1451"}')
echo "주문 $ID (사용자 $USER_ID, $ITEMS) → 확정 요청 $CONFIRM. 1~2초 뒤 status.sh / kafka-ui에서 보세요."

// E-6 준비: 결제 대기 주문을 N건 만든다 (pg-sim은 hold 모드 — 거래는 PENDING, callback 없음).
// 실행: k6 run -e COUNT=6000 -e RATE=100 docs/week6/k6/prepare.js
// 주문 생성 → 확정 요청(202). 확정 요청이 PG 생성을 보내고, 결제는 transactionKey를 받은 PENDING이 된다.
import http from 'k6/http';
import { check } from 'k6';

const API = __ENV.API || 'http://127.0.0.1:8080';
const COUNT = Number(__ENV.COUNT || 6000);
const RATE = Number(__ENV.RATE || 100);

export const options = {
  scenarios: {
    prepare: {
      executor: 'shared-iterations',
      vus: Math.min(RATE, 200),
      iterations: COUNT,
      maxDuration: '20m',
    },
  },
  thresholds: { checks: ['rate>0.99'] },
};

export default function () {
  const userId = String(((__ITER + __VU * 100000) % 1000) + 1);
  const productId = (__ITER % 10) + 1;
  const created = http.post(`${API}/api/v1/orders`, JSON.stringify({ items: [{ productId, quantity: 1 }] }), {
    headers: { 'Content-Type': 'application/json', 'X-USER-ID': userId },
    tags: { name: 'order' },
  });
  if (!check(created, { 'order 200': (r) => r.status === 200 })) return;
  const orderId = created.json('data.id');
  const confirmed = http.post(`${API}/api/v1/orders/${orderId}/confirm`,
    JSON.stringify({ cardType: 'SAMSUNG', cardNo: '1234-5678-9814-1451' }), {
      headers: { 'Content-Type': 'application/json', 'X-USER-ID': userId, 'Idempotency-Key': `e6-${__VU}-${__ITER}-${Date.now()}` },
      tags: { name: 'confirm' },
    });
  check(confirmed, { 'confirm 202': (r) => r.status === 202 });
}

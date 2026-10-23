// 주문 생성 → 확정(PG 결제 시작)을 쉬지 않고 반복한다. 가상 사용자(VU) 수와 시간은 실행할 때 정한다.
//   k6 run -e VUS=50 -e DURATION=1m --summary-export ../results/stage0-vu50.json confirm.js
//
// seed 프로필 데이터(docs/week4)를 쓴다: 사용자 1~500, 상품 1~10000(재고 각 100).
// 삭제된 상품(브랜드 100, id % 11 = 0)은 고르지 않는다 — 재고·삭제 거절이 측정에 섞이지 않게.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '1m',
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const confirmDuration = new Trend('confirm_duration', true);
const confirmStatus = new Counter('confirm_status');

function activeProductId() {
  for (;;) {
    const id = 1 + Math.floor(Math.random() * 10000);
    if (id % 11 !== 0 && (id % 100) + 1 !== 100) {
      return id;
    }
  }
}

export default function () {
  const userId = 1 + Math.floor(Math.random() * 500);
  const headers = { 'Content-Type': 'application/json', 'X-USER-ID': String(userId) };

  const created = http.post(`${BASE_URL}/api/v1/orders`,
    JSON.stringify({ items: [{ productId: activeProductId(), quantity: 1 }] }),
    { headers, tags: { name: 'create-order' } });
  if (!check(created, { 'order created (201)': (r) => r.status === 201 })) {
    return;
  }
  const orderId = created.json('data.id');

  const confirmed = http.post(`${BASE_URL}/api/v1/orders/${orderId}/confirm`,
    JSON.stringify({ paymentAttemptKey: `k6-${__VU}-${__ITER}-${orderId}`, cardType: 'TEST', cardNo: '0000-0000-0000-0000' }),
    { headers, tags: { name: 'confirm' } });
  confirmDuration.add(confirmed.timings.duration);
  confirmStatus.add(1, { status: String(confirmed.status) });
  check(confirmed, { 'confirm accepted (202/200)': (r) => r.status === 202 || r.status === 200 });
}

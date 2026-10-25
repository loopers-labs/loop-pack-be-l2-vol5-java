import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import execution from 'k6/execution';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

// fixture는 별도로 준비한다. 이 스크립트는 기존 주문을 재사용하거나 결제 생성을 재시도하지 않는다.
const baseUrl = (__ENV.PG_BENCH_BASE_URL || '').replace(/\/$/, '');
const userIds = (__ENV.PG_BENCH_USER_IDS || '').split(',').filter(Boolean);
const productId = Number(__ENV.PG_BENCH_PRODUCT_ID);
const mode = __ENV.PG_BENCH_MODE || 'smoke';
const phase = __ENV.PG_BENCH_PHASE || mode;
const rate = Number(__ENV.PG_BENCH_RATE);
const vus = Number(__ENV.PG_BENCH_VUS);
const duration = __ENV.PG_BENCH_DURATION;
const confirmBudget = Number(__ENV.PG_BENCH_CONFIRM_MS || 1000);
const finalBudget = Number(__ENV.PG_BENCH_FINAL_MS || 5000);
const observationBudget = Number(__ENV.PG_BENCH_OBSERVE_MS || 10000);
const pollMs = Number(__ENV.PG_BENCH_POLL_MS || 250);
const output = __ENV.PG_BENCH_OUTPUT || '/tmp';

if (!/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(baseUrl)
  || !Number.isSafeInteger(productId) || productId < 1
  || userIds.length === 0 || !userIds.every((id) => /^[1-9]\d*$/.test(id))) {
  throw new Error('로컬 BASE_URL과 별도로 준비한 PRODUCT_ID·USER_IDS가 필요합니다.');
}
if (!['smoke', 'load'].includes(mode) || !/^[a-zA-Z0-9_-]+$/.test(phase)
  || ![confirmBudget, finalBudget, observationBudget, pollMs].every((v) => Number.isFinite(v) && v > 0)
  || observationBudget < finalBudget) {
  throw new Error('모드·phase·시간 예산이 올바르지 않습니다.');
}
if (mode === 'load' && (!Number.isSafeInteger(rate) || rate < 1
  || !Number.isSafeInteger(vus) || vus < 1 || !/^\d+[smh]$/.test(duration || ''))) {
  throw new Error('부하 측정은 RATE·VUS·DURATION을 명시해야 합니다.');
}

const attempts = new Counter('payment_attempts_started');
const accepted = new Counter('payment_requests_accepted');
const confirmed = new Counter('payment_orders_confirmed');
const unfinished = new Counter('payment_orders_unconfirmed');
const errors = new Counter('payment_flow_errors');
const confirmGood = new Rate('payment_confirm_good');
const finalGood = new Rate('payment_final_good');
const confirmMs = new Trend('payment_confirm_ms', true);
// 완료한 표본의 지연과 미완료 수를 함께 보고한다. SLO 분모는 finalGood의 모든 시도다.
const completedMs = new Trend('payment_confirmed_ms', true);

export const options = {
  scenarios: {
    normal_payment: mode === 'load' ? {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
      preAllocatedVUs: vus, gracefulStop: '20s',
    } : {
      executor: 'shared-iterations', vus: 1, iterations: 1,
      maxDuration: '30s', gracefulStop: '20s',
    },
  },
  systemTags: ['status', 'method', 'name', 'scenario', 'error_code'],
  summaryTrendStats: ['count', 'avg', 'min', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    payment_confirm_good: ['rate>=0.99'],
    payment_final_good: ['rate>=0.99'],
    payment_flow_errors: ['count==0'],
    payment_orders_unconfirmed: ['count==0'],
    ...(mode === 'load' ? { dropped_iterations: ['count==0'] } : {}),
  },
};

function uuid() {
  const bytes = new Uint8Array(crypto.randomBytes(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const h = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function body(response) {
  try { return response.json(); } catch (_) { return null; }
}

function runPayment(userId, params) {
  const result = { kind: 'payment_normal_attempt', phase, userId, orderId: null,
    terminal: 'NOT_STARTED', confirmStartedAt: null, confirmMs: null,
    confirmedMs: null, confirmGood: false, finalGood: false };
  const created = http.post(`${baseUrl}/api/v1/orders`, JSON.stringify({
    items: [{ productId, quantity: 1 }],
  }), params('POST /api/v1/orders'));
  const draft = body(created);
  if (!check(created, { 'DRAFT 주문 생성': (r) => r.status === 201
    && draft?.meta?.result === 'SUCCESS' && draft?.data?.status === 'DRAFT' })) {
    result.terminal = 'ORDER_CREATE_ERROR';
    errors.add(1);
    return result;
  }
  result.orderId = draft.data.id;
  const expectedAmount = draft.data.totalAmount;
  result.confirmStartedAt = Date.now();
  const response = http.post(`${baseUrl}/api/v1/orders/${result.orderId}/confirm`, JSON.stringify({
    paymentAttemptKey: uuid(), cardType: 'TEST', cardNo: '0000-0000-0000-0000',
  }), params('POST /api/v1/orders/{id}/confirm'));
  result.confirmMs = Date.now() - result.confirmStartedAt;
  confirmMs.add(result.confirmMs);
  const confirmation = body(response);
  const payment = confirmation?.data;
  const correct = [200, 202].includes(response.status)
    && confirmation?.meta?.result === 'SUCCESS'
    && payment?.orderId === result.orderId && payment?.paymentMethod === 'PG'
    && ['PAYMENT_PENDING', 'CONFIRMED'].includes(payment?.orderStatus);
  result.confirmGood = correct && result.confirmMs <= confirmBudget;
  if (correct) accepted.add(1);
  else errors.add(1);

  // confirm 응답을 잃어도 주문 GET으로만 관측하고 POST를 재전송하지 않는다.
  result.terminal = 'UNCONFIRMED';
  while (Date.now() - result.confirmStartedAt < observationBudget) {
    const observed = http.get(`${baseUrl}/api/v1/orders/${result.orderId}`,
      params('GET /api/v1/orders/{id}'));
    const observation = body(observed);
    const order = observation?.data;
    if (observed.status === 200 && order?.id === result.orderId && order?.status === 'CONFIRMED'
      && observation?.meta?.result === 'SUCCESS' && order?.paymentAmount === expectedAmount
      && order?.payment?.paymentMethod === 'PG' && order?.payment?.paymentResult === 'SUCCESS'
      && order?.payment?.attemptStatus === 'SUCCEEDED' && order?.payment?.paymentAmount === expectedAmount) {
      result.confirmedMs = Date.now() - result.confirmStartedAt;
      completedMs.add(result.confirmedMs);
      confirmed.add(1);
      result.finalGood = result.confirmedMs <= finalBudget;
      result.terminal = 'CONFIRMED';
      return result;
    }
    if (observed.status === 200 && order?.status === 'PAYMENT_FAILED') {
      result.terminal = 'PAYMENT_FAILED';
      errors.add(1);
      return result;
    }
    sleep(pollMs / 1000);
  }
  unfinished.add(1);
  return result;
}

export default function () {
  const userId = userIds[(execution.vu.idInTest - 1) % userIds.length];
  const params = (name) => ({
    headers: { 'Content-Type': 'application/json', 'X-USER-ID': userId },
    timeout: '5s', redirects: 0, tags: { name, phase },
  });
  attempts.add(1);
  errors.add(0);
  unfinished.add(0);
  const result = runPayment(userId, params);
  confirmGood.add(result.confirmGood);
  finalGood.add(result.finalGood);
  console.log(JSON.stringify({ ...result, endedAt: Date.now() }));
}

export function handleSummary(data) {
  return {
    [`${output}/${phase}-summary.json`]: JSON.stringify({
      recordedAt: new Date().toISOString(), baseUrl, mode, phase,
      workload: { rate: mode === 'load' ? rate : null, preparedVus: mode === 'load' ? vus : 1,
        duration: mode === 'load' ? duration : null, userCount: userIds.length, productId },
      budgets: { confirmMs: confirmBudget, finalMs: finalBudget,
        observationMs: observationBudget, pollMs },
      timing: 'confirm: wall time including connection; final: confirm start to GET observation',
      metrics: data.metrics,
    }, null, 2),
  };
}

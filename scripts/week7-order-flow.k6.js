// 주문 생성 → 결제 확정 → PG 결과 알림(callback)을 한 흐름으로 보내는 부하 시나리오.
// 단계마다 도착률을 고정한다(constant-arrival-rate). 앞 요청이 느려져도 새 흐름은 정해진 속도로 시작한다.
// OrderFlowLoadExperimentTest가 설정 파일을 만들어 실행하고, 흐름마다 남긴 로그 한 줄을 집계한다.
import http from 'k6/http';
import execution from 'k6/execution';

const config = JSON.parse(open(__ENV.CONFIG_FILE));
if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(config.baseUrl)) {
  throw new Error('This test requires the isolated local API server');
}

const scenarios = {};
let startSeconds = 0;
config.stages.forEach((stage, index) => {
  scenarios[`stage_${index}`] = {
    executor: 'constant-arrival-rate', exec: 'orderFlow', rate: stage.rate, timeUnit: '1s',
    duration: `${stage.seconds}s`, startTime: `${startSeconds}s`, gracefulStop: '30s',
    preAllocatedVUs: Math.max(10, stage.rate * 2), maxVUs: Math.max(50, stage.rate * 10),
  };
  startSeconds += stage.seconds;
});

export const options = {
  scenarios, maxRedirects: 0,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function pick(values) {
  return values[Math.floor(Math.random() * values.length)];
}

function post(path, userId, body, name) {
  const headers = { 'Content-Type': 'application/json' };
  if (userId !== null) headers['X-USER-ID'] = String(userId);
  return http.post(config.baseUrl + path, JSON.stringify(body), { headers, timeout: '15s', tags: { name } });
}

export function orderFlow() {
  const stage = Number(execution.scenario.name.split('_')[1]);
  const userId = pick(config.userIds);
  const result = { kind: 'flow', stage, startedEpochMs: Date.now() };
  try {
    const created = post('/api/v1/orders', userId, { items: [{ productId: pick(config.productIds), quantity: 1 }] }, 'create');
    result.createMs = created.timings.duration;
    result.createStatus = created.status;
    if (created.status !== 201) { result.failedAt = 'create'; return; }
    const orderId = created.json('data.id');
    result.orderId = orderId;

    const confirmed = post(`/api/v1/orders/${orderId}/confirm`, userId,
      { paymentAttemptKey: `load-${orderId}`, cardType: 'TEST', cardNo: '0000-0000-0000-0000' }, 'confirm');
    result.confirmMs = confirmed.timings.duration;
    result.confirmStatus = confirmed.status;
    if (confirmed.status !== 202 && confirmed.status !== 200) { result.failedAt = 'confirm'; return; }

    // 실제로는 PG가 보내는 결과 알림이다. 앱은 본문을 믿지 않고 PG 조회로 결과를 확인한다.
    const callback = post('/api/v1/payments/callback', null,
      { providerOrderId: `LP-ORD-${String(orderId).padStart(10, '0')}`, status: 'SUCCESS' }, 'callback');
    result.callbackMs = callback.timings.duration;
    result.callbackStatus = callback.status;
    if (callback.status !== 200) { result.failedAt = 'callback'; return; }
    result.ok = true;
  } catch (error) {
    result.failedAt = result.failedAt || 'transport';
    result.error = String(error);
  } finally {
    result.flowMs = Date.now() - result.startedEpochMs;
    console.log(JSON.stringify(result));
  }
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_FILE]: JSON.stringify(data, null, 2) + '\n' };
}

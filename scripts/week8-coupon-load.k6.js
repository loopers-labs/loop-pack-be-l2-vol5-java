// 쿠폰 발급을 단계별로 정해진 속도(constant-arrival-rate)로 보내고, 상품 목록 조회를 낮은 속도로 함께 보낸다.
// 앞 요청이 느려져도 새 요청은 정해진 속도로 시작한다. 발급 요청마다 새 사용자를 쓴다.
// duplicateEvery번째 요청마다 같은 사용자의 요청 두 개를 동시에 보낸다(따닥).
// CouponLoadExperimentTest가 설정 파일을 만들어 실행하고, 요청마다 남긴 로그 한 줄을 집계한다.
import http from 'k6/http';
import execution from 'k6/execution';

const config = JSON.parse(open(__ENV.CONFIG_FILE));
if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(config.baseUrl)) {
  throw new Error('This test requires the isolated local API server');
}

const ends = [];
const scenarios = {};
let startSeconds = 0;
config.stages.forEach((stage, index) => {
  scenarios[`coupon_${index}`] = {
    executor: 'constant-arrival-rate', exec: 'issue', rate: stage.rate, timeUnit: '1s',
    duration: `${stage.seconds}s`, startTime: `${startSeconds}s`, gracefulStop: '20s',
    // 가상 사용자 수 상한. 같은 PC에서 k6가 자원을 지나치게 쓰지 않게 한다.
    preAllocatedVUs: Math.min(500, Math.max(20, stage.rate)), maxVUs: Math.min(1500, Math.max(100, stage.rate * 5)),
  };
  startSeconds += stage.seconds;
  ends.push(startSeconds * 1000);
});
scenarios.products = {
  executor: 'constant-arrival-rate', exec: 'browse', rate: config.productRate, timeUnit: '1s',
  duration: `${startSeconds}s`, startTime: '0s', gracefulStop: '20s', preAllocatedVUs: 10, maxVUs: 200,
};

export const options = { scenarios, maxRedirects: 0, summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'] };

function stageAt(elapsedMs) {
  const index = ends.findIndex(end => elapsedMs < end);
  return index === -1 ? ends.length - 1 : index;
}

function record(kind, stage, response, extra) {
  const line = { kind, stage, status: response.status, ms: response.timings.duration,
    t: Date.now(), elapsed: execution.instance.currentTestRunDuration, ...extra };
  if (response.status !== 200 && response.status !== 201) {
    try { line.message = response.json('meta.message'); } catch (ignored) { line.message = null; }
  }
  console.log(JSON.stringify(line));
}

export function issue() {
  const stage = Number(execution.scenario.name.split('_')[1]);
  const iteration = execution.scenario.iterationInTest;
  // 단계마다 겹치지 않는 사용자 구간을 쓴다. 따닥 말고는 같은 사용자가 다시 오지 않는다.
  const userId = config.firstUserId + config.userOffsets[stage] + iteration;
  const url = `${config.baseUrl}/api/v1/coupon-campaigns/${config.campaignId}/issues`;
  const params = { headers: { 'X-USER-ID': String(userId) }, timeout: '15s', tags: { name: 'issue' } };
  const requestId = `issue:${stage}:${iteration}`;
  const withId = id => ({ ...params, headers: { ...params.headers, 'X-Experiment-Request-ID': id } });
  if (config.duplicateEvery > 0 && iteration % config.duplicateEvery === 0) {
    const responses = http.batch([['POST', url, null, withId(`${requestId}:a`)], ['POST', url, null, withId(`${requestId}:b`)]]);
    responses.forEach((response, index) => record('double', stage, response,
      { userId, requestId: `${requestId}:${index === 0 ? 'a' : 'b'}` }));
    return;
  }
  record('coupon', stage, http.post(url, null, withId(requestId)), { userId, requestId });
}

export function browse() {
  const stage = stageAt(execution.instance.currentTestRunDuration);
  const requestId = `product:${execution.scenario.iterationInTest}`;
  const response = http.get(`${config.baseUrl}/api/v1/products?page=0&size=20&sort=latest`,
    { timeout: '15s', tags: { name: 'products' }, headers: { 'X-Experiment-Request-ID': requestId } });
  record('product', stage, response, { requestId });
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_FILE]: JSON.stringify(data, null, 2) + '\n' };
}

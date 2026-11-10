// 선착순 쿠폰 발급 스파이크. open model: 응답과 상관없이 초당 RATE 건을 DURATION 동안 보낸다.
// 요청마다 다른 사용자(USER_START + 순번)를 쓰고, 앱 3대에 번갈아 보낸다.
// 실행: k6 run -e APPS=ip:8080,ip:8080,ip:8080 -e PROMOTION_ID=1 -e USER_START=2 -e RATE=1000 -e DURATION=20s issue-spike.js
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const APPS = __ENV.APPS.split(',');
const PROMOTION_ID = __ENV.PROMOTION_ID;
const USER_START = Number(__ENV.USER_START);
const RATE = Number(__ENV.RATE || 1000);
const DURATION = __ENV.DURATION || '20s';

// 응답 결과별 건수. thresholds 는 실패 조건이 아니라 결과별 건수를 요약에 드러내려고 둔다.
const outcomes = new Counter('issue_outcomes');
const OUTCOMES = ['SUCCESS', 'COUPON_SOLD_OUT', 'COUPON_ALREADY_ISSUED', 'COUPON_ISSUE_BUSY', 'USER_NOT_FOUND', 'PROMOTION_NOT_ACTIVE', 'OTHER'];

export const options = {
  scenarios: {
    issue: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // 필요 VU ≈ RATE × 응답 시간. 실행 중 VU 생성은 느려 요청을 흘리므로 시작 전에 전부 만들어 둔다.
      preAllocatedVUs: 10000,
      maxVUs: 10000,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: Object.fromEntries(OUTCOMES.map((o) => [`issue_outcomes{outcome:${o}}`, ['count>=0']])),
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const app = APPS[i % APPS.length];
  const res = http.post(`http://${app}/api/v1/promotions/${PROMOTION_ID}/issues`, null, {
    headers: { 'X-USER-ID': String(USER_START + i) },
    tags: { name: 'issue' },
  });

  let outcome = 'SUCCESS';
  if (res.status !== 200) {
    let code = null;
    try {
      code = res.json('meta.errorCode');
    } catch (e) {
      code = null;
    }
    outcome = OUTCOMES.includes(code) ? code : 'OTHER';
  }
  outcomes.add(1, { outcome });
  check(res, { 'no 5xx': (r) => r.status > 0 && r.status < 500 });
}

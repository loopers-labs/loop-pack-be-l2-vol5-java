// 선착순 쿠폰 발급 스파이크. open model: 응답과 상관없이 단계마다 정한 초당 건수를 그 기간 동안 보낸다.
// 요청마다 다른 사용자(USER_START + 순번)를 쓰고, 앱 3대에 번갈아 보낸다.
// 실행: k6 run -e APPS=ip:8080,ip:8080,ip:8080 -e PROMOTION_ID=1 -e USER_START=2 -e STAGES=3s:1000,6s:10000 -e VUS=20000 issue-spike.js
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const APPS = __ENV.APPS.split(',');
const PROMOTION_ID = __ENV.PROMOTION_ID;
const USER_START = Number(__ENV.USER_START);
// 단계 "기간:초당 건수" 를 쉼표로 잇는다. 예: 3s:1000,6s:10000 → 3초 동안 초당 1000건, 이어서 6초 동안 초당 10000건
const STAGES = (__ENV.STAGES || '3s:1000,6s:10000').split(',').map((s) => {
  const [duration, rate] = s.split(':');
  return { duration, target: Number(rate) };
});
const VUS = Number(__ENV.VUS || 20000);

// 응답 결과별 건수. thresholds 는 실패 조건이 아니라 결과별 건수를 요약에 드러내려고 둔다.
const outcomes = new Counter('issue_outcomes');
const OUTCOMES = ['SUCCESS', 'COUPON_SOLD_OUT', 'COUPON_ALREADY_ISSUED', 'COUPON_ISSUE_BUSY', 'USER_NOT_FOUND', 'PROMOTION_NOT_ACTIVE', 'OTHER'];

export const options = {
  scenarios: {
    issue: {
      executor: 'ramping-arrival-rate',
      startRate: STAGES[0].target,
      timeUnit: '1s',
      // ramping 은 단계 사이를 선형으로 잇는다. 계단처럼 바로 바뀌도록 단계마다 0s 짜리 전환을 앞에 둔다.
      stages: STAGES.flatMap((s) => [{ duration: '0s', target: s.target }, s]),
      // 필요 VU ≈ 초당 건수 × 응답 시간. 실행 중 VU 생성은 느려 요청을 흘리므로 시작 전에 전부 만들어 둔다.
      preAllocatedVUs: VUS,
      maxVUs: VUS,
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

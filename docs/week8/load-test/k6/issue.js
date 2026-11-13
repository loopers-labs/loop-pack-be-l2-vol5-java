// 쿠폰 발급을 쉬지 않고 보내면서(issue), 같은 서버의 다른 기능(내 쿠폰 목록)을 일정한 속도로 부른다(list).
//   k6 run -e CAMPAIGN_ID=1 -e VUS=200 -e DURATION=60s -e LIST_RATE=20 --summary-export ../results/c3.json issue.js
//
// - issue: 매 반복이 처음 보는 사용자다(scenario.iterationInTest로 id를 만든다). 그래서 모든 요청이 기존 쿠폰 조회에서 걸러지지 않고
//   자리(Redis) 판단까지 간다. 429·503을 받아도 다시 보내지 않는다 — 클라이언트 재시도는 이 측정의 대상이 아니다.
// - list: GET /api/v1/coupons. 캐시 없이 매번 DB를 읽는다(CouponQuery.listMine, readOnly TX 한 번).
//   사용자는 issue가 맨 처음 발급한 500명(1,000,001~1,000,500) 중에서 고른다 — 시작 직후 몇 초를 빼면 쿠폰 1장이 있는 목록이다.
//   도착 속도를 고정했으므로(constant-arrival-rate) 응답 시간 변화는 이쪽 부하가 아니라 서버 쪽 자원 경쟁에서 온다.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID || '1';
const DURATION = __ENV.DURATION || '60s';
const USER_BASE = 1000000;

export const options = {
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'],
  scenarios: {
    issue: {
      executor: 'constant-vus',
      exec: 'issue',
      vus: Number(__ENV.VUS || 200),
      duration: DURATION,
    },
    list: {
      executor: 'constant-arrival-rate',
      exec: 'list',
      rate: Number(__ENV.LIST_RATE || 20),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 200,
    },
  },
};

const issueDuration = new Trend('issue_duration', true);
const issueCreatedDuration = new Trend('issue_201_duration', true);
const listDuration = new Trend('list_duration', true);
const statusCounters = {
  201: new Counter('issue_201'),
  200: new Counter('issue_200'),
  409: new Counter('issue_409'),
  429: new Counter('issue_429'),
  503: new Counter('issue_503'),
  500: new Counter('issue_500'),
};
const otherStatus = new Counter('issue_other');
const listFailed = new Counter('list_not_200');

export function issue() {
  const userId = USER_BASE + 1 + exec.scenario.iterationInTest;
  const res = http.post(`${BASE_URL}/api/v1/coupon-campaigns/${CAMPAIGN_ID}/issues`, null,
    { headers: { 'X-USER-ID': String(userId) }, tags: { name: 'issue' } });
  issueDuration.add(res.timings.duration);
  if (res.status === 201) {
    issueCreatedDuration.add(res.timings.duration);
  }
  (statusCounters[res.status] || otherStatus).add(1);
}

export function list() {
  const userId = USER_BASE + 1 + Math.floor(Math.random() * 500);
  const res = http.get(`${BASE_URL}/api/v1/coupons`,
    { headers: { 'X-USER-ID': String(userId) }, tags: { name: 'list' } });
  listDuration.add(res.timings.duration);
  if (res.status !== 200) {
    listFailed.add(1);
  }
}

// 선착순 쿠폰 몰림 부하. 한도 QUOTA 캠페인에 서로 다른 USERS 명이 동시에 한 번씩 발급을 요청한다.
// 실행: k6 run -e QUOTA=100 -e USERS=1000 k6/coupon-rush.js  (run-coupon-rush.sh 가 서버 기동·시드·정리를 함께 한다)
import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const QUOTA = Number(__ENV.QUOTA || 100);
const USERS = Number(__ENV.USERS || 1000);
const ADMIN = 'Basic ' + encoding.b64encode(`${__ENV.ADMIN_USER || 'local-admin'}:${__ENV.ADMIN_PASSWORD || 'local-admin'}`);

const statusCount = {
  201: new Counter('issue_201_issued'),
  200: new Counter('issue_200_existing'),
  202: new Counter('issue_202_pending'),
  409: new Counter('issue_409_rejected'),
  503: new Counter('issue_503_unavailable'),
};
const otherStatus = new Counter('issue_other_status');
const winnerLatency = new Trend('latency_winner_201', true);
const pendingLatency = new Trend('latency_pending_202', true);
const loserLatency = new Trend('latency_loser_409', true);

export const options = {
  scenarios: {
    rush: {
      executor: 'per-vu-iterations', // 모든 VU 가 같은 시각에 출발해 한 번씩만 요청한다
      vus: USERS,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
};

// 캠페인 준비 → 열기는 운영과 같은 관리자 API 로 한다. 수용은 열기 전까지 닫혀 있다.
export function setup() {
  const headers = { Authorization: ADMIN, 'Content-Type': 'application/json' };
  const prepared = http.post(`${BASE}/api-admin/v1/coupon-campaigns`,
    JSON.stringify({ name: '부하 테스트 5,000원 할인권', discountAmount: 5000, totalQuota: QUOTA }), { headers });
  if (prepared.status !== 200) {
    throw new Error(`캠페인 준비 실패: ${prepared.status} ${prepared.body}`);
  }
  const campaignId = prepared.json('data.id');
  const opened = http.post(`${BASE}/api-admin/v1/coupon-campaigns/${campaignId}/open`, null, { headers });
  if (opened.status !== 200) {
    throw new Error(`캠페인 열기 실패: ${opened.status} ${opened.body}`);
  }
  return { campaignId };
}

export default function (data) {
  const userId = __VU; // 1..USERS, 시드한 사용자 id 와 같다
  const res = http.post(`${BASE}/api/v1/coupon-campaigns/${data.campaignId}/issues`, null,
    { headers: { 'X-USER-ID': String(userId) }, tags: { name: 'issue' } });
  (statusCount[res.status] || otherStatus).add(1);
  if (res.status === 201) winnerLatency.add(res.timings.duration);
  if (res.status === 202) pendingLatency.add(res.timings.duration);
  if (res.status === 409) loserLatency.add(res.timings.duration);
  check(res, { '예상한 상태 코드': (r) => [201, 202, 409].includes(r.status) });
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY_OUT || 'k6/results/summary.json';
  return { [out]: JSON.stringify(data, null, 2), stdout: textSummary(data) };
}

function textSummary(data) {
  const m = data.metrics;
  const v = (name, key = 'count') => (m[name] && m[name].values[key] !== undefined ? m[name].values[key] : 0);
  const t = (name) => m[name]
    ? `med ${v(name, 'med').toFixed(1)} / p95 ${v(name, 'p(95)').toFixed(1)} / p99 ${v(name, 'p(99)').toFixed(1)} / max ${v(name, 'max').toFixed(1)} ms`
    : '-';
  return [
    `201 ${v('issue_201_issued')} · 202 ${v('issue_202_pending')} · 200 ${v('issue_200_existing')} · 409 ${v('issue_409_rejected')} · 503 ${v('issue_503_unavailable')} · 기타 ${v('issue_other_status')}`,
    `당첨 201: ${t('latency_winner_201')}`,
    `대기 202: ${t('latency_pending_202')}`,
    `탈락 409: ${t('latency_loser_409')}`,
    `전체 http_req_duration: ${t('http_req_duration')}`,
    '',
  ].join('\n');
}

// 상품 목록 A 응답 시간 측정. VUS / ITERS / OUT 은 환경변수로 받는다. 보통 scripts/k6-run.sh 가 호출한다.
//   k6 run -e VUS=1 -e ITERS=500 -e OUT=docs/week4/perf/baseline/k6-A-vu1-r1.json docs/week4/perf/scripts/k6-products.js
// shared-iterations: VUS 명이 ITERS 회를 나눠 실행한다 (VUS=1 이면 순차 500회).
import http from 'k6/http';
import { check } from 'k6';

const VUS = Number(__ENV.VUS || 1);
const ITERS = Number(__ENV.ITERS || 500);
const OUT = __ENV.OUT || '';
const URL = __ENV.URL || 'http://127.0.0.1:8080/api/v1/products?brandId=7&sort=price_asc&page=0&size=20';

export const options = {
  scenarios: {
    run: { executor: 'shared-iterations', vus: VUS, iterations: ITERS, maxDuration: '10m' },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
};

export default function () {
  const res = http.get(URL);
  check(res, {
    'status 200': (r) => r.status === 200,
    '20 items': (r) => {
      try { return r.json('data').length === 20; } catch (e) { return false; }
    },
  });
}

// OUT 이 주어지면 전체 요약을 JSON 으로 저장하고, 표준출력에는 핵심 수치만 한 줄로 찍는다.
export function handleSummary(data) {
  const d = data.metrics.http_req_duration.values;
  const reqs = data.metrics.http_reqs.values;
  const fails = data.metrics.http_req_failed ? data.metrics.http_req_failed.values.passes : 0;
  const checks = data.metrics.checks ? data.metrics.checks.values : { passes: 0, fails: 0 };
  const line =
    `vus=${VUS} iters=${ITERS} reqs=${reqs.count} rps=${reqs.rate.toFixed(2)} ` +
    `avg=${d.avg.toFixed(2)} p50=${d.med.toFixed(2)} p90=${d['p(90)'].toFixed(2)} ` +
    `p95=${d['p(95)'].toFixed(2)} max=${d.max.toFixed(2)} ` +
    `http_failed=${fails} checks_pass=${checks.passes} checks_fail=${checks.fails}\n`;
  const out = { stdout: line };
  if (OUT) out[OUT] = JSON.stringify(data, null, 2);
  return out;
}

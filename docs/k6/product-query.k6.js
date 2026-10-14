import { check } from 'k6';
import crypto from 'k6/crypto';
import execution from 'k6/execution';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseline = JSON.parse(open('../product-query-baseline/response-canonical.json'));
const responseSha256 = crypto.sha256(JSON.stringify(baseline), 'hex');
const samples = Number(__ENV.PRODUCT_QUERY_SAMPLES || 1000);
const warmup = Number(__ENV.PRODUCT_QUERY_WARMUP || 3);
const vus = Number(__ENV.PRODUCT_QUERY_VUS || 1);
const baseUrl = (__ENV.PRODUCT_QUERY_BASE_URL || 'http://localhost:18081').replace(/\/$/, '');
const output = __ENV.PRODUCT_QUERY_RESULTS || '/tmp';
const runName = __ENV.PRODUCT_QUERY_RUN || (vus === 1 ? 'baseline-vu1' : `concurrent-vu${vus}`);
if (!Number.isSafeInteger(samples) || samples < 1 || !Number.isSafeInteger(warmup) || warmup < 0
  || !Number.isSafeInteger(vus) || vus < 1 || samples % vus !== 0) {
  throw new Error('실측 횟수는 양수이며 VU 수로 나누어 떨어져야 하고, 준비 횟수는 0 이상, VU 수는 양수여야 합니다.');
}
if (!/^[a-zA-Z0-9_-]+$/.test(runName)) {
  throw new Error('PRODUCT_QUERY_RUN은 영문자, 숫자, 하이픈, 밑줄만 사용할 수 있습니다.');
}

const samplesPerVu = samples / vus;
const url = `${baseUrl}/api/v1/products?brandId=7&page=0&size=20&sort=price_asc`;
const measuredRequests = new Counter('product_query_requests');
const warmupRequests = new Counter('product_query_warmup_requests');
const responseMatches = new Rate('product_query_response_matches');
const duration = new Trend('product_query_duration', true);
let mismatchLogged = false;

export const options = {
  scenarios: {
    product_query: {
      executor: 'per-vu-iterations',
      vus,
      iterations: warmup + samplesPerVu,
      maxDuration: '15m',
      gracefulStop: '15s',
    },
  },
  noConnectionReuse: true,
  summaryTrendStats: ['avg', 'min', 'p(50)', 'p(95)', 'p(99)', 'p(99.9)', 'max', 'count'],
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    product_query_requests: [`count==${samples}`],
    product_query_warmup_requests: [`count==${warmup * vus}`],
    product_query_response_matches: ['rate==1'],
  },
};

function verifyResponse(response) {
  let payload;
  let checksum = null;
  try {
    payload = response.json();
    const rows = payload.data.map((item) => baseline.fields.map((field) => item[field]));
    checksum = crypto.sha256(JSON.stringify({
      request: baseline.request,
      fields: baseline.fields,
      rows,
    }), 'hex');
  } catch (_) {
    payload = null;
  }
  const correct = check(response, {
    'HTTP 200': (result) => result.status === 200,
    'SUCCESS 응답': () => payload !== null && payload.meta && payload.meta.result === 'SUCCESS',
    '상품 20개': () => payload !== null && Array.isArray(payload.data) && payload.data.length === 20,
    '상품·정렬·가격·좋아요 수 일치': () => checksum === responseSha256,
  });
  if (!correct) {
    if (!mismatchLogged) {
      mismatchLogged = true;
      console.error(JSON.stringify({
        status: response.status,
        checksum,
        expectedChecksum: responseSha256,
        actualCommonRows: payload && Array.isArray(payload.data)
          ? payload.data.map((item) => baseline.fields.map((field) => item[field]))
          : null,
      }));
    }
  }
  return correct;
}

export default function () {
  const preparing = execution.vu.iterationInScenario < warmup;
  const response = http.get(url, {
    timeout: '10s',
    redirects: 0,
    tags: { phase: preparing ? 'warmup' : 'measurement' },
  });
  const correct = verifyResponse(response);
  if (preparing) {
    warmupRequests.add(1, { phase: 'warmup' });
  } else {
    measuredRequests.add(1, { phase: 'measurement' });
    responseMatches.add(correct, { phase: 'measurement' });
    duration.add(response.timings.duration, { phase: 'measurement' });
  }
}

export function handleSummary(data) {
  const metric = data.metrics.product_query_duration;
  const values = metric ? metric.values : {};
  const completed = data.metrics.product_query_requests;
  const warmed = data.metrics.product_query_warmup_requests;
  const matched = data.metrics.product_query_response_matches;
  const failed = data.metrics.http_req_failed;
  const summary = {
    tool: 'k6',
    recorded_at_utc: new Date().toISOString(),
    url,
    request: baseline.request,
    vus,
    samples_requested: samples,
    samples_per_vu: samplesPerVu,
    samples_completed: completed ? completed.values.count : 0,
    warmup_per_vu: warmup,
    warmup_requested: warmup * vus,
    warmup_completed: warmed ? warmed.values.count : 0,
    response_sha256: responseSha256,
    response_match_rate: matched ? matched.values.rate : null,
    response_mismatch_count: matched ? matched.values.fails : null,
    http_failure_rate: failed ? failed.values.rate : null,
    duration_definition: 'response.timings.duration: sending + waiting + receiving, ms',
    avg_ms: values.avg ?? null,
    min_ms: values.min ?? null,
    p50_ms: values['p(50)'] ?? null,
    p95_ms: values['p(95)'] ?? null,
    p99_ms: values['p(99)'] ?? null,
    p99_9_ms: values['p(99.9)'] ?? null,
    max_ms: values.max ?? null,
    thresholds_passed: Object.values(data.metrics).every((item) =>
      !item.thresholds || Object.values(item.thresholds).every((threshold) => threshold.ok)),
  };
  return {
    stdout: `${JSON.stringify(summary, null, 2)}\n`,
    [`${output}/${runName}-summary.json`]: `${JSON.stringify(summary, null, 2)}\n`,
    [`${output}/${runName}-raw-summary.json`]: `${JSON.stringify(data, null, 2)}\n`,
  };
}

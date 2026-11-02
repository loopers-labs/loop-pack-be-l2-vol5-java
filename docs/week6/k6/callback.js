// E-6 본 실험: PG callback을 일정 도착률로 보낸다. callback 하나 = 결과 적용 → 주문 확정 commit → AFTER_COMMIT send.
// 실행: k6 run -e RATE=100 -e DURATION=60s -e LABEL=B-1s --summary-export=docs/week6/k6/results/B-1s.json docs/week6/k6/callback.js
//
// setup()이 pg-sim에서 SUCCESS 거래 목록을 받아 반복마다 하나씩 쓴다 (같은 거래를 두 번 쓰지 않는다).
// probe 시나리오가 1초마다 api의 /actuator/prometheus를 읽어 Tomcat busy 스레드 · Hikari active 연결을 기록한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const API = __ENV.API || 'http://127.0.0.1:8080';
const ACTUATOR = __ENV.ACTUATOR || 'http://127.0.0.1:8081/actuator/prometheus';
const PG = __ENV.PG || 'http://127.0.0.1:8082';
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '60s';

const busyThreads = new Trend('tomcat_busy_threads');
const hikariActive = new Trend('hikari_active');
const hikariPending = new Trend('hikari_pending');
const callbackStatus = new Counter('callback_status');
const exhausted = new Counter('transactions_exhausted');

export const options = {
  scenarios: {
    callback: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 400,
      maxVUs: 1000,
      exec: 'callback',
    },
    probe: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 2,
      exec: 'probe',
    },
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const txs = http.get(`${PG}/_pending?status=SUCCESS`).json();
  console.log(`[E-6 ${__ENV.LABEL || ''}] 쓸 수 있는 SUCCESS 거래 ${txs.length}건`);
  return { txs };
}

export function callback(data) {
  const i = exec.scenario.iterationInTest;
  if (i >= data.txs.length) {
    exhausted.add(1);
    return;
  }
  const tx = data.txs[i];
  const res = http.post(`${API}/api/v1/payments/callback`,
    JSON.stringify({ transactionKey: tx.transactionKey, orderId: tx.orderId, status: 'SUCCESS' }), {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'callback' },
      timeout: '90s',
    });
  callbackStatus.add(1, { status: String(res.status) });
  check(res, { 'callback 200': (r) => r.status === 200 });
}

function metric(text, name) {
  const line = text.split('\n').find((l) => l.startsWith(name));
  return line ? Number(line.trim().split(' ').pop()) : NaN;
}

export function probe() {
  const res = http.get(ACTUATOR, { tags: { name: 'probe' }, timeout: '2s' });
  if (res.status !== 200) return;
  const busy = metric(res.body, 'tomcat_threads_busy_threads');
  const active = metric(res.body, 'hikaricp_connections_active');
  const pending = metric(res.body, 'hikaricp_connections_pending');
  busyThreads.add(busy);
  hikariActive.add(active);
  hikariPending.add(pending);
  console.log(`[probe] t=${Math.round(exec.instance.currentTestRunDuration / 1000)}s busy=${busy} hikariActive=${active} hikariPending=${pending}`);
}

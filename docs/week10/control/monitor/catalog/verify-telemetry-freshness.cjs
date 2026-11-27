const assert = require('node:assert/strict');
const { parseArgs } = require('node:util');

async function main() {
  const { values } = parseArgs({ options: { url: { type: 'string' }, at: { type: 'string' } } });
  const url = new URL(values.url);
  assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) && url.protocol === 'http:');
  const uid = url.pathname.match(/^\/d\/([^/]+)/)?.[1];
  const at = Date.parse(values.at);
  assert.ok(uid && Number.isFinite(at), 'Pass the local dashboard URL and an ISO timestamp during the 1,000-user plateau');
  const get = await fetch(new URL('/api/dashboards/uid/' + uid, url));
  assert.equal(get.status, 200);
  const { dashboard } = await get.json();
  const panels = ['탐색 중인 사용자', '물 결제 요청 · 초당'].map(title => {
    const panel = dashboard.panels.find(p => p.title === title);
    assert.ok(panel, title);
    return panel;
  });
  // Keep the historical plateau inside the stale query's range to catch accidental latest-value carry-forward.
  const from = String(at - 300000);
  for (const [label, end] of [['plateau', at], ['live after stop', Date.now()]]) {
    for (const panel of panels) {
      const response = await fetch(new URL('/api/ds/query', url), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ from, to: String(end), queries: panel.targets })
      });
      assert.equal(response.status, 200);
      const result = (await response.json()).results.A;
      assert.equal(result.status, 200);
      const frame = result.frames[0];
      assert.equal(frame.data.values[0].length, 1, 'Grafana needs one row to render the null mapping');
      const value = frame.data.values[0][0];
      console.log(dashboard.uid, label, panel.title, value);
      if (label === 'plateau') {
        if (panel === panels[0]) assert.equal(Number(value), 1000);
        else assert.ok(Number(value) > 0.85 && Number(value) < 1.15, 'Expected one dispatched water payment per second');
      } else {
        assert.equal(value, null);
        assert.equal(panel.fieldConfig.defaults.noValue, '측정 종료·대기');
      }
    }
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });

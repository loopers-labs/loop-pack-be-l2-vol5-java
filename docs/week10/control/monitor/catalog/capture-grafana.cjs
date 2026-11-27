const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { parseArgs } = require('node:util');
const { chromium } = require('playwright');

function timestamp(value) {
  const time = /^\d{13}$/.test(value) ? Number(value) : Date.parse(value);
  assert.ok(Number.isFinite(time) && (/^\d{13}$/.test(value) || /(Z|[+-]\d\d:\d\d)$/i.test(value)),
    'from/to must be epoch milliseconds or an ISO timestamp with an explicit time zone');
  return time;
}

async function main() {
  const { values } = parseArgs({ options: {
    url: { type: 'string' }, from: { type: 'string' }, to: { type: 'string' },
    out: { type: 'string' }, panel: { type: 'string' }, overview: { type: 'boolean' }
  } });
  const url = new URL(values.url);
  assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) && url.protocol === 'http:');
  const uid = url.pathname.split('/')[2];
  assert.ok(uid && url.pathname.startsWith('/d/'), 'Pass a local Grafana dashboard URL');
  const from = timestamp(values.from);
  const to = timestamp(values.to);
  assert.ok(from < to && values.out, 'Choose an increasing time range and an output directory');
  const dir = path.resolve(values.out);
  await fs.mkdir(dir, { recursive: true });
  url.searchParams.set('from', String(from));
  url.searchParams.set('to', String(to));
  url.searchParams.set('timezone', 'Asia/Seoul');
  url.searchParams.set('theme', 'dark');
  url.searchParams.delete('refresh');
  url.searchParams.delete('kiosk');
  url.searchParams.delete('viewPanel');

  const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge', headless: true });
  try {
    const context = await browser.newContext({
      viewport: { width: 1880, height: 1140 }, deviceScaleFactor: 1,
      locale: 'ko-KR', timezoneId: 'Asia/Seoul'
    });
    const response = await context.request.get(new URL('/api/dashboards/uid/' + uid, url).href);
    assert.equal(response.status(), 200);
    const { dashboard, meta } = await response.json();
    assert.equal(meta.provisioned, true);
    let sections = [[null, 'overview']];
    if (values.panel) {
      sections = [[Number(values.panel), 'panel-' + Number(values.panel)]];
    } else if (!values.overview) {
      const latency = dashboard.panels.find(p => p.title === '서버 응답 시간 · p95');
      const collection = dashboard.panels.find(p => p.title === 'Prometheus 수집 상태');
      assert.ok(latency && collection, 'Use --panel or --overview for dashboards without the catalog panels');
      sections.push([latency.id, 'traffic'], [collection.id, 'payment-health']);
    }
    const suffix = new Date(to).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');
    const evidence = {
      dashboard: dashboard.title, uid, revision: dashboard.version, timezone: 'Asia/Seoul',
      from: new Date(from).toISOString(), to: new Date(to).toISOString(),
      fromKst: new Date(from).toLocaleString('sv-SE', { timeZone: 'Asia/Seoul' }),
      toKst: new Date(to).toLocaleString('sv-SE', { timeZone: 'Asia/Seoul' }), captures: []
    };
    const page = await context.newPage();
    const queryErrors = [];
    let successfulQueries = 0;
    page.on('response', async res => {
      if (new URL(res.url()).pathname !== '/api/ds/query') return;
      try {
        const body = await res.json();
        for (const q of Object.values(body.results || {})) {
          if (res.status() !== 200 || q.error) queryErrors.push(q.error || String(res.status()));
          else successfulQueries++;
        }
      } catch (error) { queryErrors.push(String(error)); }
    });

    if (values.panel) url.searchParams.set('viewPanel', String(Number(values.panel)));
    await page.goto(url.href, { waitUntil: 'domcontentloaded', timeout: 60000 });
    for (const [id, name] of sections) {
      const panel = id === null ? dashboard.panels[0] : dashboard.panels.find(item => item.id === id);
      assert.ok(panel, 'Unknown panel ' + id);
      const title = page.getByText(panel.title, { exact: true }).first();
      if (id && !values.panel) {
        await page.mouse.move(1100, 800);
        for (let step = 0; step < 15 && !await title.count(); step++) {
          await page.mouse.wheel(0, 620);
          await page.waitForTimeout(300);
        }
      }
      await title.waitFor({ state: 'attached', timeout: 45000 });
      if (id && !values.panel) await title.evaluate(el => el.scrollIntoView({ block: 'center', behavior: 'instant' }));
      await title.waitFor({ state: 'visible', timeout: 30000 });
      await page.waitForLoadState('networkidle', { timeout: 30000 });
      await page.evaluate(() => document.fonts.ready);
      // Anonymous Grafana sessions can briefly show an account notification over the fourth stat.
      // Wait for Grafana to dismiss the transient message before taking an unmodified viewport.
      const notice = page.getByText('Unauthorized', { exact: true }).first();
      if (await notice.isVisible()) await notice.waitFor({ state: 'hidden', timeout: 30000 });
      await page.waitForTimeout(550);
      assert.ok(successfulQueries > 0 && queryErrors.length === 0, JSON.stringify(queryErrors));
      await page.mouse.move(2, 2);
      const file = name + '-' + suffix + '.png';
      const text = await page.locator('body').innerText();
      const clock = text.split('\n').find(line => line.includes(evidence.fromKst) && line.includes(evidence.toKst));
      assert.ok(clock, 'Grafana did not display both absolute bounds in its toolbar');
      const image = await page.screenshot({ animations: 'disabled' });
      await fs.writeFile(path.join(dir, file), image, { flag: 'wx' });
      evidence.captures.push({ file, focus: panel.title, capturedAt: new Date().toISOString(), url: page.url(),
        toolbarRange: clock || null, successfulQueries, bytes: image.length });
      console.log(file + ' · ' + panel.title + ' · ' + (clock || evidence.toKst));
    }
    await fs.writeFile(path.join(dir, (values.panel ? 'panel-' + values.panel : 'capture') + '-' + suffix + '.json'),
      JSON.stringify(evidence, null, 2) + '\n', { flag: 'wx' });
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });

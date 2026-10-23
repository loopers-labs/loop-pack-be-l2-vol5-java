// 실험 페이지 공통: 서버 호출, 결과 출력, 이벤트 로그 갱신
async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  return res.json();
}

function show(id, value) {
  document.getElementById(id).textContent =
    typeof value === "string" ? value : JSON.stringify(value, null, 2);
}

function newId(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
}

function summarize(e) {
  const r = e.response || {};
  const b = r.body || {};
  const what = b.status || (b.response && b.response.status) || b.code || b.message || "";
  return `${e.at}  ${e.kind.padEnd(16)} ${(r.outcome || "").padEnd(7)} ${r.status || ""} ${r.elapsedMs != null ? r.elapsedMs + "ms" : ""}  ${what}`;
}

async function refreshLogs() {
  const el = document.getElementById("logs");
  if (!el) return;
  const logs = await api("GET", "/api/logs");
  el.innerHTML = "";
  for (const e of logs) {
    const d = document.createElement("details");
    const s = document.createElement("summary");
    s.textContent = summarize(e);
    const pre = document.createElement("pre");
    pre.textContent = JSON.stringify(e, null, 2);
    d.append(s, pre);
    el.append(d);
  }
}

setInterval(refreshLogs, 2000);
window.addEventListener("load", refreshLogs);

(() => {
  "use strict";

  const lab = document.getElementById("lab");
  if (!lab) return;
  const svg = lab.querySelector("#diagram");
  const pages = [...lab.querySelectorAll(".stage")].map(page => ({
    page,
    title: page.querySelector("h3").textContent,
    note: page.querySelector(".stage-note").textContent,
    nodes: [...page.querySelectorAll(".nodes > div")].map(pair => ({
      name: pair.querySelector("dt").textContent,
      value: pair.querySelector("dd").textContent
    })),
    edges: [...page.querySelectorAll(".edges > li")].map(edge => ({
      from: Number(edge.dataset.from), to: Number(edge.dataset.to), verb: edge.textContent
    }))
  }));
  if (!svg || !pages.length) return;
  const control = key => lab.querySelector("[data-" + key + "]");
  const picks = [...lab.querySelectorAll("[data-pick]")];
  const play = control("play"), repeat = control("repeat"), live = control("live");
  const repeatCount = document.createElement("span");
  repeatCount.className = "repeat-count";
  repeat.after(repeatCount);
  const reduced = matchMedia("(prefers-reduced-motion: reduce)");
  const ns = "http://www.w3.org/2000/svg";
  const travelTime = 1250, readingTime = 2000;
  let selected = 0, replays = 0, frame = 0, narrow = false;
  let partials = null;
  let run = null;
  let routes = [];

  function shape(name, attrs = {}, text) {
    const item = document.createElementNS(ns, name);
    for (const [key, value] of Object.entries(attrs)) item.setAttribute(key, value);
    if (text !== undefined) item.textContent = text;
    return item;
  }

  // Adjacent boxes use a direct route. A route skipping a box goes around its outside edge.
  function route(from, to) {
    const direction = Math.sign(to - from);
    const skip = Math.abs(to - from) === 2;
    if (narrow) {
      const x = 178, y = 48 + 99 * from, end = 48 + 99 * to;
      if (skip) return { d: `M 63 ${y} H 24 V ${end} H 60`, x: 44, y: 100 };
      return { d: `M ${x} ${y + direction * 31} V ${end - direction * 35}`, x: x + 57, y: (y + end) / 2 };
    }
    const x = 118 + 258 * from, end = 118 + 258 * to;
    if (skip) return { d: `M ${x} 71 V 35 H ${end} V 67`, x: (x + end) / 2, y: 22 };
    return { d: `M ${x + direction * 86} 107 H ${end - direction * 90}`, x: (x + end) / 2, y: 91 };
  }

  function addValue(group, value, width) {
    const text = shape("text", { class: "lesson-node-value", "text-anchor": "middle" });
    group.append(text);
    // Measure in the loaded browser font; Korean and Latin text have different character widths.
    const words = value.split(/\s+/);
    const lines = [""];
    for (const word of words) {
      const line = lines.length - 1;
      const candidate = lines[line] ? lines[line] + " " + word : word;
      text.textContent = candidate;
      if (lines[line] && text.getComputedTextLength() > width - 18) lines.push(word);
      else lines[line] = candidate;
    }
    text.replaceChildren(...lines.map((line, i) => shape("tspan", {
      x: 0, y: (lines.length === 1 ? 13 : 5) + 18 * i
    }, line)));
  }

  function paintDiagram() {
    const scene = pages[selected];
    const [width, height] = narrow ? [230, 62] : [172, 72];
    const names = scene.nodes.map(node => node.name);
    svg.setAttribute("viewBox", narrow ? "0 0 308 296" : "0 0 752 158");
    svg.setAttribute("aria-labelledby", "diagram-title diagram-description");
    const defs = shape("defs");
    const arrow = shape("marker", { id: "lesson-arrow", viewBox: "0 0 8 8", refX: 7, refY: 4,
      markerWidth: 6, markerHeight: 6, orient: "auto", markerUnits: "userSpaceOnUse" });
    arrow.append(shape("path", { d: "M0 0 L8 4 L0 8Z", fill: "currentColor" }));
    defs.append(arrow);
    const description = [...scene.nodes.map(n => n.name + ", " + n.value),
      ...scene.edges.map(e => names[e.from] + "에서 " + names[e.to] + "로 " + e.verb)].join(". ");
    svg.replaceChildren(shape("title", { id: "diagram-title" }, scene.title),
      shape("desc", { id: "diagram-description" }, description), defs);
    routes = scene.edges.map((edge, i) => {
      const line = shape("g", { class: "lesson-edge", "data-edge": edge.from + "-" + edge.to });
      const geometry = route(edge.from, edge.to);
      const path = shape("path", { class: "lesson-wire", d: geometry.d, "marker-end": "url(#lesson-arrow)" });
      line.append(path, shape("text", { class: "lesson-verb", x: geometry.x, y: geometry.y,
        "text-anchor": "middle", "dominant-baseline": "central" }, edge.verb));
      svg.append(line);
      // The second arrow of a chain follows the first. Forks and joins move concurrently.
      const beat = i && scene.edges[i - 1].to === edge.from ? 1 : 0;
      return { ...edge, line, path, length: path.getTotalLength(), beat, token: null };
    });
    scene.nodes.forEach((node, i) => {
      const x = narrow ? 178 : 118 + 258 * i, y = narrow ? 48 + 99 * i : 107;
      const group = shape("g", { class: "lesson-node", "data-node": i, transform: `translate(${x} ${y})` });
      group.append(shape("rect", { x: -width / 2, y: -height / 2, width, height, rx: 8 }),
        shape("text", { class: "lesson-node-name", x: 0, y: -11, "text-anchor": "middle" }, node.name));
      svg.append(group);
      addValue(group, node.value, width);
    });
    if (run) paintTime();
  }

  function syncButtons() {
    lab.dataset.playback = !run ? "idle" : run.paused ? "paused" : "playing";
    play.textContent = run ? (run.paused ? "이어 재생" : "일시정지") : (selected === pages.length - 1 ? "처음부터 재생" : "흐름 재생");
    control("prev").disabled = selected === 0;
    control("next").disabled = selected === pages.length - 1;
    repeat.hidden = pages[selected].page.dataset.repeat !== "true";
    repeatCount.hidden = repeat.hidden || replays === 0;
    repeatCount.textContent = replays ? "추가 재전달 " + replays + "회" : "";
    lab.dataset.replays = String(replays);
    control("count").textContent = (selected + 1) + " / " + pages.length;
  }
  function choose(index) {
    if (index !== selected) { replays = 0; partials = null; }
    selected = index;
    pages.forEach(({ page }, i) => { page.hidden = i !== selected; });
    picks.forEach(button => {
      if (Number(button.dataset.pick) === selected) button.setAttribute("aria-current", "step");
      else button.removeAttribute("aria-current");
    });
    paintDiagram();
    syncButtons();
    live.textContent = (index + 1) + "단계. " + pages[index].title + ". " + pages[index].note;
  }
  function removeTokens() {
    for (const r of routes) {
      r.token?.remove();
      r.token = null;
      r.line.classList.remove("moving");
    }
  }
  function stop() {
    cancelAnimationFrame(frame);
    run = null;
    removeTokens();
    syncButtons();
  }
  function paintTime() {
    for (const r of routes) {
      const progress = run.elapsed / travelTime - r.beat;
      const moving = progress >= 0 && progress < 1;
      r.line.classList.toggle("moving", moving);
      if (!moving || reduced.matches) {
        r.token?.remove();
        r.token = null;
        continue;
      }
      if (!r.token) {
        r.token = shape("circle", { class: "lesson-token", r: narrow ? 4.5 : 5.5, "data-from": r.from, "data-to": r.to });
        svg.append(r.token);
      }
      const point = r.path.getPointAtLength(r.length * progress);
      r.token.setAttribute("cx", point.x);
      r.token.setAttribute("cy", point.y);
    }
  }
  function tick(timestamp) {
    if (!run || run.paused) return;
    run.elapsed += Math.max(0, timestamp - run.last);
    run.last = timestamp;
    paintTime();
    const duration = travelTime * (1 + Math.max(0, ...routes.map(r => r.beat))) + (run.auto ? readingTime : 0);
    if (run.elapsed >= duration) {
      if (!run.auto || selected === pages.length - 1) return stop();
      run.elapsed = 0;
      choose(selected + 1);
    }
    frame = requestAnimationFrame(tick);
  }
  function start(index, auto) {
    stop();
    choose(index);
    if (!auto && (reduced.matches || routes.length === 0)) return;
    run = { auto, elapsed: 0, last: performance.now(), paused: false };
    paintTime();
    syncButtons();
    frame = requestAnimationFrame(tick);
  }
  function pause() {
    if (!run || run.paused) return;
    cancelAnimationFrame(frame);
    run.paused = true;
    syncButtons();
  }
  play.addEventListener("click", () => {
    if (!run) return start(selected === pages.length - 1 ? 0 : selected, true);
    if (!run.paused) return pause();
    run.paused = false;
    run.last = performance.now();
    syncButtons();
    frame = requestAnimationFrame(tick);
  });
  control("prev").addEventListener("click", () => start(selected - 1, false));
  control("next").addEventListener("click", () => start(selected + 1, false));
  picks.forEach(pick => pick.addEventListener("click", () => start(Number(pick.dataset.pick), false)));
  repeat.addEventListener("click", () => {
    const scene = pages[selected];
    // This exercise holds snapshot and product 7 fixed, so only the order range varies in the key.
    partials ??= new Map(JSON.parse(scene.page.dataset.rows));
    const [range, score] = JSON.parse(scene.page.dataset.delivery);
    if (partials.has(range) && partials.get(range) !== score) {
      live.textContent = "같은 키에 다른 점수가 도착했습니다. 기존 점수를 유지하고 공개를 보류합니다.";
      return;
    }
    if (!partials.has(range)) partials.set(range, score);
    scene.nodes[1].value = [...partials].map(([key, value]) => key + " " + value + "점").join(" · ");
    const total = [...partials.values()].reduce((sum, value) => sum + value, 0);
    scene.nodes[2].value = partials.size + "행 · " + total + "점";
    replays++;
    start(selected, false);
    live.textContent = "재전달 " + replays + "회. " + pages[selected].nodes.map(node => node.value).join(". ");
  });
  document.addEventListener("visibilitychange", () => { if (document.hidden) pause(); });
  addEventListener("pagehide", pause);
  reduced.addEventListener("change", () => { if (run) paintTime(); });
  function resize() {
    const compact = lab.clientWidth < 625;
    if (compact === narrow) return;
    narrow = compact;
    paintDiagram();
  }
  for (const selector of [".controls", ".step-tabs", "#diagram"]) lab.querySelector(selector).removeAttribute("hidden");
  lab.classList.add("enhanced");
  narrow = lab.clientWidth < 625;
  choose(0);
  new ResizeObserver(resize).observe(lab);
})();

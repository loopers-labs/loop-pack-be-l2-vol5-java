(() => {
  "use strict";
  const examples = {
    balanced: [[10, 1], [20, 1], [30, 2], [90, 4]],
    heavy: [[10, 1], [20, 1], [30, 2], [90, 12]],
    single: [[90, 4]]
  };
  const members = document.getElementById("planning-members");
  const orders = document.getElementById("planning-orders");
  const names = ["A", "B", "C"];
  function element(tag, text) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function draw() {
    const count = Number(members.value);
    const input = examples[orders.value];
    const total = input.reduce((n, [, weight]) => n + weight, 0);
    const groups = Array.from({ length: count }, () => ({ orders: [], weight: 0 }));
    let cumulative = 0, previousBucket = -1, groupIndex = -1;
    const strip = document.getElementById("weight-strip");
    const tbody = document.getElementById("weight-rows");
    strip.replaceChildren();
    tbody.replaceChildren();
    for (const [id, weight] of input) {
      cumulative += weight;
      const middle = cumulative - weight / 2;
      const bucket = Math.floor(middle * count / total);
      if (bucket !== previousBucket) groupIndex++;
      previousBucket = bucket;
      const group = groups[groupIndex];
      group.orders.push(id);
      group.weight += weight;
      const tr = element("tr");
      for (const value of [id, weight, cumulative, middle, names[groupIndex]]) tr.append(element("td", value));
      tbody.append(tr);
      const bundle = element("div");
      bundle.className = "order-bundle range-" + groupIndex;
      bundle.style.flexGrow = String(weight);
      bundle.append(element("span", id), element("i"));
      strip.append(bundle);
    }
    for (let i = 1; i < count; i++) {
      const cut = element("i");
      cut.className = "weight-cut";
      cut.style.left = (i * 100 / count) + "%";
      strip.append(cut);
    }
    const boundaries = groups.slice(1).filter(g => g.orders.length).map(g => g.orders[0]);
    const empty = groups.filter(g => !g.orders.length).length;
    const boundaryText = boundaries.length ? "주문번호 경계 " + boundaries.join(", ") : "주문번호 경계 없음";
    document.getElementById("boundary-summary").textContent =
      "서버 " + count + "대 · 저장된 구간 " + count + "개 · " + boundaryText + (empty ? " · 빈 구간 " + empty + "개" : "");
    const ranges = document.getElementById("boundary-ranges");
    ranges.replaceChildren(...groups.map((group, i) => {
      const limits = [];
      if (i > 0 && group.orders.length) limits.push(group.orders[0] + " 이상");
      if (boundaries[i] !== undefined) limits.push(boundaries[i] + " 미만");
      const label = !group.orders.length ? "빈 구간" : limits.length ? "주문번호 " + limits.join(" ~ ") : "모든 주문번호";
      return element("li", names[i] + " · " + label + " · 주문 " + group.orders.length + "건 · 품목 " + group.weight + "행");
    }));
    let explanation = "각 주문을 통째로 배정한 결과, 구간별 품목 행 수는 " + groups.map(group => group.weight).join(" · ") + "입니다.";
    if (count === 1) explanation = "활성 JVM이 한 대라서 이 판본의 모든 주문을 하나의 구간으로 저장합니다.";
    else if (input.length === 1) explanation = "주문이 한 건이라 첫 구간에 전체 주문을 넣습니다. 나머지 " + empty + "개 구간도 계획에 남습니다.";
    else if (orders.value === "balanced" && count === 2)
      explanation = "총 8행을 둘로 가르는 위치는 4입니다. 주문 90의 가운데는 6이므로 B가 맡습니다. B의 첫 주문번호 90이 저장할 경계입니다.";
    else if (orders.value === "heavy") explanation += " 주문 90의 12행은 가운데 위치에 따라 한 구간에 함께 배정됩니다.";
    document.getElementById("boundary-explanation").textContent = explanation;
  }
  members.addEventListener("change", draw);
  orders.addEventListener("change", draw);
  document.querySelector(".bounds-controls").hidden = false;
  draw();
})();

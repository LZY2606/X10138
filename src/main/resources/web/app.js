"use strict";

const state = {
  session: null,
  selectedPath: null,
  pendingResolutions: new Map(),
};

const $ = (id) => document.getElementById(id);

async function api(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const json = text ? JSON.parse(text) : {};
  if (!res.ok) {
    const err = new Error(json.error || `HTTP ${res.status}`);
    err.payload = json;
    err.status = res.status;
    throw err;
  }
  return json;
}

function toast(msg, kind = "") {
  const el = $("toast");
  el.textContent = msg;
  el.className = "toast " + kind;
  el.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.hidden = true; }, 2600);
}

// ---------- 示例数据 ----------

const SAMPLE = {
  base: `service:
  name: orders-api
  replicas: 2
  timeout: 30
  features:
    cache: false
    tracing: true
servers:
  - id: web-1
    host: 10.0.0.1
    port: 8080
  - id: web-2
    host: 10.0.0.2
    port: 8080
regions:
  - cn-north-1
  - us-west-2
`,
  a: `service:
  name: orders-api
  replicas: 3
  timeout: 30
  features:
    cache: true
    tracing: true
servers:
  - id: web-1
    host: 10.0.0.1
    port: 9090
  - id: web-3
    host: 10.0.0.3
    port: 8080
  - id: web-2
    host: 10.0.0.2
    port: 8080
regions:
  - cn-north-1
  - ap-east-1
  - us-west-2
`,
  b: `service:
  name: orders-api
  replicas: 2
  timeout: 45
  features:
    cache: false
    tracing: false
servers:
  - id: web-1
    host: 10.0.0.1
    port: 8080
  - id: web-2
    host: 10.0.0.2
    port: 8443
  - id: web-4
    host: 10.0.0.4
    port: 8080
regions:
  - us-west-2
`,
};

function loadSample() {
  $("baseText").value = SAMPLE.base;
  $("aText").value = SAMPLE.a;
  $("bText").value = SAMPLE.b;
  ["baseFormat", "aFormat", "bFormat"].forEach((id) => ($(id).value = "YAML"));
}

function fmtOf(selectId) {
  const v = $(selectId).value;
  return v === "AUTO" ? null : v;
}

async function startMerge() {
  $("composerError").textContent = "";
  const payload = {
    base: { text: $("baseText").value, format: fmtOf("baseFormat"), fileName: "base.yaml" },
    a: { text: $("aText").value, format: fmtOf("aFormat"), fileName: "a.yaml" },
    b: { text: $("bText").value, format: fmtOf("bFormat"), fileName: "b.yaml" },
  };
  if (payload.base.format === null) delete payload.base.format;
  if (payload.a.format === null) delete payload.a.format;
  if (payload.b.format === null) delete payload.b.format;
  try {
    const s = await api("POST", "/api/sessions", payload);
    state.session = s;
    state.pendingResolutions.clear();
    renderSession();
    $("composer").hidden = true;
    document.querySelector(".workspace").hidden = false;
    toast("合并完成", "success");
  } catch (e) {
    $("composerError").textContent = e.message;
  }
}

// ---------- 渲染 ----------

function renderSession() {
  const s = state.session;
  if (!s) return;
  $("sessionMeta").textContent =
    `会话 ${s.id} · revision=${s.revision} · 策略版本 v${s.policyVersion} · 已解决 ${s.resolvedCount}/${s.conflictCount}`;
  $("fpBase").textContent = "fp " + short(s.fingerprints.base);
  $("fpA").textContent = "fp " + short(s.fingerprints.a);
  $("fpB").textContent = "fp " + short(s.fingerprints.b);

  const baseNode = makeNodeViewFromInputs(s.inputs.base);
  const aNode = makeNodeViewFromInputs(s.inputs.a);
  const bNode = makeNodeViewFromInputs(s.inputs.b);
  $("treeBase").innerHTML = "";
  $("treeBase").appendChild(renderTree(baseNode, "$", "base"));
  $("treeA").innerHTML = "";
  $("treeA").appendChild(renderTree(aNode, "$", "a"));
  $("treeB").innerHTML = "";
  $("treeB").appendChild(renderTree(bNode, "$", "b"));
  $("treeResult").innerHTML = "";
  $("treeResult").appendChild(renderTree(s.result, "$", "result"));

  const unresolved = s.conflicts.filter((c) => !c.resolved).length;
  const badge = $("conflictBadge");
  badge.textContent = unresolved === 0 ? "全部解决" : `${unresolved} 个未决 / ${s.conflictCount} 冲突`;
  badge.className = "badge " + (unresolved === 0 ? "ok" : "");

  if (state.selectedPath) renderDetailForSelection();
}

function short(fp) { return fp.slice(0, 10); }

// 输入文本已经在后端解析；详情面板按需再展示原文，树视图从 result/source 数据构建。
// 这里直接把三边原始文本轻量解析为 JSON 树（复用后端返回的 parsed 不可得，改用逐行 YAML 概览）。
function makeNodeViewFromInputs(input) {
  // 仅用于侧边树概览：调用后端解析模型过重，前端做一个 YAML/JSON 结构摘要解析。
  try {
    if (input.format === "JSON") return parseClientJson(input.text);
    return parseClientYaml(input.text);
  } catch (e) {
    return { kind: "scalar", value: { type: "string", v: "<解析失败: " + e.message + ">" }, path: "$", sources: [] };
  }
}

function parseClientJson(text) {
  return jsonToView(JSON.parse(text), "$");
}
function jsonToView(v, path) {
  if (v === null) return { kind: "scalar", value: { type: "null" }, path, sources: [] };
  if (typeof v === "boolean") return { kind: "scalar", value: { type: "bool", v }, path, sources: [] };
  if (typeof v === "number") return { kind: "scalar", value: { type: "number", v: String(v) }, path, sources: [] };
  if (typeof v === "string") return { kind: "scalar", value: { type: "string", v }, path, sources: [] };
  if (Array.isArray(v)) {
    return { kind: "arr", path, items: v.map((x, i) => jsonToView(x, `${path}[${i}]`)), sources: [] };
  }
  const children = {};
  Object.entries(v).forEach(([k, x]) => { children[k] = jsonToView(x, path === "$" ? "$." + keySeg(k) : `${path}.${keySeg(k)}`); });
  return { kind: "obj", path, children, sources: [] };
}
function keySeg(k) { return /^[A-Za-z0-9_-]+$/.test(k) ? k : JSON.stringify(k); }

function parseClientYaml(text) {
  // 缩进块解析器（仅用于侧栏概览：支持 map/seq/引号/标量/锚点别名的简单展开）
  const rawLines = text.split("\n");
  const lines = [];
  rawLines.forEach((rl, idx) => {
    const stripped = stripClientComment(rl);
    const indent = stripped.search(/\S/);
    if (indent < 0) return;
    const body = stripped.slice(indent).trim();
    if (body === "---" || body === "..." || !body) return;
    lines.push({ indent, body, line: idx + 1 });
  });
  let cursor = 0;
  function parseBlock(minIndent) {
    if (cursor >= lines.length) return null;
    const first = lines[cursor];
    if (first.body.startsWith("- ") || first.body === "-") return parseSeq(first.indent);
    return parseMap(first.indent);
  }
  function parseMap(indent) {
    const children = {};
    while (cursor < lines.length) {
      const ln = lines[cursor];
      if (ln.indent !== indent || ln.body.startsWith("- ")) break;
      const ci = colonIndex(ln.body);
      if (ci < 0) break;
      const key = unquote(ln.body.slice(0, ci).trim());
      const rest = ci + 1 < ln.body.length ? ln.body.slice(ci + 1).trim() : "";
      cursor++;
      let value;
      if (rest) {
        if (rest.startsWith("{") || rest.startsWith("[")) value = jsonToViewSafeFlow(rest);
        else if (rest.startsWith("*")) value = { kind: "scalar", value: { type: "string", v: aliasText(rest) }, path: "", sources: [] };
        else value = clientScalar(rest);
      } else if (cursor < lines.length && lines[cursor].indent > indent) {
        value = parseBlock(lines[cursor].indent);
      } else value = { kind: "scalar", value: { type: "null" }, path: "", sources: [] };
      children[key] = value;
    }
    return { kind: "obj", path: "", children, sources: [] };
  }
  function parseSeq(indent) {
    const items = [];
    while (cursor < lines.length) {
      const ln = lines[cursor];
      if (ln.indent !== indent || !(ln.body.startsWith("- ") || ln.body === "-")) break;
      const rest = ln.body === "-" ? "" : ln.body.slice(2).trim();
      cursor++;
      if (rest) items.push(clientScalar(rest));
      else if (cursor < lines.length && lines[cursor].indent > indent) items.push(parseBlock(lines[cursor].indent));
      else items.push({ kind: "scalar", value: { type: "null" }, path: "", sources: [] });
    }
    return { kind: "arr", path: "", items, sources: [] };
  }
  const root = parseBlock(lines[0] ? lines[0].indent : 0);
  root.path = "$";
  assignPaths(root, "$");
  return root;

  function assignPaths(node, path) {
    node.path = path;
    if (node.kind === "obj") Object.entries(node.children).forEach(([k, v]) => assignPaths(v, path === "$" ? "$." + keySeg(k) : `${path}.${keySeg(k)}`));
    if (node.kind === "arr") node.items.forEach((v, i) => assignPaths(v, `${path}[${i}]`));
  }
}

function stripClientComment(line) {
  let sq = false, dq = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (sq) { if (c === "'") sq = false; continue; }
    if (dq) { if (c === '"') dq = false; if (c === "\\") i++; continue; }
    if (c === "'") sq = true;
    else if (c === '"') dq = true;
    else if (c === "#" && (i === 0 || line[i - 1] === " " || line[i - 1] === "\t")) return line.slice(0, i);
  }
  return line;
}
function colonIndex(body) {
  let sq = false, dq = false, depth = 0;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (sq) { if (c === "'") sq = false; continue; }
    if (dq) { if (c === '"') dq = false; continue; }
    if (c === "'") sq = true; else if (c === '"') dq = true;
    else if (c === "{" || c === "[") depth++;
    else if (c === "}" || c === "]") depth--;
    else if (c === ":" && depth === 0 && (i === body.length - 1 || body[i + 1] === " ")) return i;
  }
  return -1;
}
function unquote(s) {
  if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
    try { return JSON.parse(s.startsWith("'") ? '"' + s.slice(1, -1).replace(/"/g, '\\"') + '"' : s); } catch (_) { return s.slice(1, -1); }
  }
  return s;
}
function aliasText(t) { return "(别名展开见结果树)"; }
function jsonToViewSafeFlow(t) {
  try { return jsonToView(JSON.parse(t.replace(/'/g, '"')), ""); } catch (_) {
    return { kind: "scalar", value: { type: "string", v: t }, path: "", sources: [] };
  }
}
function clientScalar(raw) {
  const t = raw.trim();
  if (t === "" || t === "~" || t === "null") return { kind: "scalar", value: { type: "null" }, path: "", sources: [] };
  if (t === "true" || t === "True") return { kind: "scalar", value: { type: "bool", v: true }, path: "", sources: [] };
  if (t === "false" || t === "False") return { kind: "scalar", value: { type: "bool", v: false }, path: "", sources: [] };
  if (/^-?[0-9]+$/.test(t)) return { kind: "scalar", value: { type: "number", v: t }, path: "", sources: [] };
  if (/^-?[0-9]*\.?[0-9]+([eE][-+]?[0-9]+)?$/.test(t) && t.includes(".")) {
    return { kind: "scalar", value: { type: "number", v: t }, path: "", sources: [] };
  }
  return { kind: "scalar", value: { type: "string", v: unquote(t.replace(/,\s*$/, "")) }, path: "", sources: [] };
}

// ---------- 结果树 DOM ----------

function renderTree(node, keyLabel, kind) {
  const wrap = document.createElement("div");
  wrap.className = "node";
  wrap.appendChild(buildNode(node, keyLabel, kind, 0));
  return wrap;
}

function buildNode(node, label, kind, depth) {
  const row = document.createElement("div");
  row.className = "node-row";
  const path = node.path || "";
  if (state.selectedPath && state.selectedPath.path === path && state.selectedPath.kind === kind) {
    row.classList.add("selected");
  }

  const conflictId = node.kind === "conflict" ? node.conflictId : node.orderConflictId;
  const conflict = conflictId ? (state.session.conflicts || []).find((c) => c.id === conflictId) : null;
  if (conflict && !conflict.resolved) row.parentMarker = "conflict";

  const toggle = document.createElement("span");
  toggle.className = "toggle";
  const hasChildren =
    (node.kind === "obj" && Object.keys(node.children || {}).length) ||
    (node.kind === "arr" && (node.items || []).length);
  toggle.textContent = hasChildren ? "▸" : "";
  row.appendChild(toggle);

  const keyEl = document.createElement("span");
  keyEl.className = "node-key";
  keyEl.textContent = displayLabel(label, node, kind);
  row.appendChild(keyEl);

  if (node.kind === "obj") {
    const t = document.createElement("span");
    t.className = "node-type";
    t.textContent = `{${Object.keys(node.children || {}).length}}`;
    row.appendChild(t);
  } else if (node.kind === "arr") {
    const t = document.createElement("span");
    t.className = "node-type";
    t.textContent = `[${(node.items || []).length}]${node.policy ? " · " + policyLabel(node.policy) : ""}`;
    row.appendChild(t);
  } else if (node.kind === "scalar") {
    const v = document.createElement("span");
    v.className = "node-val";
    v.textContent = scalarText(node.value);
    row.appendChild(v);
  } else if (node.kind === "delete") {
    const v = document.createElement("span");
    v.className = "node-val";
    v.textContent = "∅ 已删除";
    row.appendChild(v);
  } else if (node.kind === "conflict") {
    const v = document.createElement("span");
    v.className = "node-val";
    v.textContent = "⚠ 冲突";
    row.appendChild(v);
  }

  const tags = document.createElement("span");
  tags.className = "src-tags";
  (node.sources || []).forEach((s) => {
    const tag = document.createElement("span");
    tag.className = `tag ${s.side}`;
    if (!s.present) tag.classList.add("absent");
    if (!s.equal && s.present) tag.classList.add("changed");
    tag.textContent = s.side === "BASE" ? "祖" : s.side === "A" ? "A" : "B";
    tag.title = sourceTitle(s);
    tags.appendChild(tag);
  });
  row.appendChild(tags);

  const container = document.createElement("div");
  const nodeWrap = document.createElement("div");
  nodeWrap.className = "node";
  if (conflict && !conflict.resolved) nodeWrap.classList.add("conflict");
  if (node.kind === "delete") nodeWrap.classList.add("deleted");
  nodeWrap.appendChild(row);

  if (hasChildren) {
    const children = document.createElement("div");
    children.className = "children";
    children.hidden = true;
    toggle.textContent = "▸";
    if (node.kind === "obj") {
      Object.entries(node.children).forEach(([k, child]) => children.appendChild(buildNode(child, k, kind, depth + 1)));
    } else {
      node.items.forEach((child, i) => children.appendChild(buildNode(child, i, kind, depth + 1)));
    }
    row.addEventListener("click", (ev) => {
      ev.stopPropagation();
      children.hidden = !children.hidden;
      toggle.textContent = children.hidden ? "▸" : "▾";
      selectNode(node, kind, row);
    });
    nodeWrap.appendChild(children);
  } else {
    row.addEventListener("click", (ev) => { ev.stopPropagation(); selectNode(node, kind, row); });
  }
  return nodeWrap;
}

function policyLabel(code) {
  return { replace: "替换", id: "按id", seq: "有序序列" }[code] || code;
}

function displayLabel(label, node, kind) {
  if (typeof label === "number") return "-";
  if (label === "$") return kind === "result" ? "结果" : kind === "base" ? "祖先" : "分支 " + kind.toUpperCase();
  return label;
}

function scalarText(v) {
  if (!v) return "?";
  if (v.type === "null") return "null";
  if (v.type === "bool") return v.v ? "true" : "false";
  if (v.type === "number") return v.v;
  const s = v.v;
  return s.length > 80 ? s.slice(0, 77) + "..." : s;
}

function sourceTitle(s) {
  const o = s.origin;
  const where = o ? `${o.file}:${o.line}:${o.column}` : "不存在";
  const state = !s.present ? "该边缺失" : s.equal ? "与结果一致" : "与结果不同";
  return `${s.sideLabel} · ${state} · ${where}`;
}

function selectNode(node, kind, rowEl) {
  document.querySelectorAll(".node-row.selected").forEach((el) => el.classList.remove("selected"));
  rowEl.classList.add("selected");
  state.selectedPath = { path: node.path || "", kind, node };
  showDetail(node, kind);
}

// ---------- 详情面板：来源链、冲突原因、逐项裁决 ----------

function showDetail(node, kind) {
  const panel = $("detailPanel");
  panel.hidden = false;
  $("detailTitle").textContent = (node.path || kind) + " · " + kindLabel(kind);
  const body = $("detailBody");
  body.innerHTML = "";

  const typeLine = document.createElement("div");
  typeLine.className = "kv";
  typeLine.innerHTML = `<div class="k">节点类型 / 路径</div><div><code>${nodeKindText(node.kind)}</code> &nbsp; <code>${node.path || ""}</code></div>`;
  body.appendChild(typeLine);

  if (node.kind === "scalar") {
    const val = document.createElement("div");
    val.className = "kv";
    val.innerHTML = `<div class="k">结果值</div><pre class="node-val">${escapeHtml(scalarText(node.value))}</pre>`;
    body.appendChild(val);
  }

  // 来源链
  const srcTitle = document.createElement("h3");
  srcTitle.className = "section";
  srcTitle.textContent = "来源链（祖先 / A / B）";
  body.appendChild(srcTitle);
  const list = document.createElement("div");
  list.className = "src-list";
  (node.sources || []).forEach((s) => list.appendChild(sourceCard(s, node, kind)));
  body.appendChild(list);

  // 冲突信息（结果树上的节点）
  if (kind === "result") {
    const cid = node.kind === "conflict" ? node.conflictId : node.orderConflictId;
    const conflict = cid ? state.session.conflicts.find((c) => c.id === cid) : null;
    if (conflict) renderConflict(body, conflict);

    // 数组且没有策略 -> 也可能是 MISSING_POLICY
    if (node.kind === "arr" && !node.policy) {
      const mp = state.session.conflicts.find(
        (c) => c.type === "MISSING_POLICY" && c.path === node.path
      );
      if (mp) renderConflict(body, mp);
    }
  }

  // 路径导航：在三边树中同路径节点的快速对比
  renderPathComparison(body, node, kind);
}

function kindLabel(kind) {
  return { result: "合并结果", base: "祖先", a: "分支A", b: "分支B" }[kind] || kind;
}
function nodeKindText(k) {
  return { scalar: "标量", obj: "对象", arr: "数组", delete: "删除", conflict: "未决冲突" }[k] || k;
}

function sourceCard(s, node, kind) {
  const card = document.createElement("div");
  card.className = "src-card " + s.side;
  const o = s.origin;
  const stateText = !s.present ? "缺失（未出现该字段）" : s.equal ? "与当前结果一致" : "与当前结果不同";
  card.innerHTML = `<h4>${s.sideLabel} <span class="resolution-note">— ${stateText}</span></h4>
    <div class="resolution-note">${o ? escapeHtml(`${o.file}:${o.line}:${o.column}`) : "无出处"}</div>`;
  const sideNode = findSideNodeForSource(s.side, node.path);
  if (sideNode) {
    const pre = document.createElement("pre");
    pre.textContent = summarizeNode(sideNode);
    card.appendChild(pre);
  }
  return card;
}

function findSideNodeForSource(side, path) {
  if (!path) return null;
  const inputKey = side === "BASE" ? "base" : side === "A" ? "a" : "b";
  const input = state.session.inputs[inputKey];
  if (!input) return null;
  const tree = makeNodeViewFromInputs(input);
  return resolvePath(tree, path);
}

function resolvePath(root, path) {
  // path 形态：$ 或 $.a.b 或 $.a[0]
  if (path === "$") return root;
  const segs = [];
  const re = /\.([^.\[]+)|\[(\d+)\]/g;
  let m;
  while ((m = re.exec(path)) !== null) {
    if (m[1] !== undefined) segs.push({ type: "key", value: stripQuotes(m[1]) });
    else segs.push({ type: "index", value: Number(m[2]) });
  }
  let cur = root;
  for (const seg of segs) {
    if (!cur) return null;
    if (seg.type === "key") cur = cur.children ? cur.children[seg.value] : null;
    else cur = cur.items ? cur.items[seg.value] : null;
  }
  return cur;
}
function stripQuotes(s) {
  if ((s.startsWith('"') && s.endsWith('"'))) { try { return JSON.parse('"' + s.slice(1, -1) + '"'); } catch (_) {} }
  return s;
}

function summarizeNode(node) {
  if (!node) return "<缺失>";
  if (node.kind === "scalar") return scalarText(node.value);
  if (node.kind === "obj") return JSON.stringify(
    Object.fromEntries(Object.entries(node.children).slice(0, 8).map(([k, v]) => [k, preview(v)])), null, 2);
  if (node.kind === "arr") return JSON.stringify(node.items.slice(0, 8).map(preview), null, 2);
  return "?";
}
function preview(v) {
  if (v.kind === "scalar") {
    if (v.value.type === "string") return v.value.v;
    if (v.value.type === "null") return null;
    return v.value.v;
  }
  if (v.kind === "obj") return Object.fromEntries(Object.entries(v.children).map(([k, c]) => [k, preview(c)]));
  if (v.kind === "arr") return v.items.map(preview);
  return null;
}

function renderPathComparison(body, node, kind) {
  const h = document.createElement("h3");
  h.className = "section";
  h.textContent = "同路径三边对比";
  body.appendChild(h);
  const grid = document.createElement("div");
  [["BASE", "祖先"], ["A", "分支 A"], ["B", "分支 B"]].forEach(([side, label]) => {
    const found = findSideNodeForSource(side, node.path);
    const card = document.createElement("div");
    card.className = "src-card " + side;
    card.innerHTML = `<h4>${label}</h4>`;
    const pre = document.createElement("pre");
    pre.textContent = found === null ? "<缺失（键不存在）>" : summarizeNode(found);
    card.appendChild(pre);
    grid.appendChild(card);
  });
  grid.className = "src-list";
  body.appendChild(grid);
}

function renderConflict(body, conflict) {
  const box = document.createElement("div");
  box.className = "conflict-box";
  box.innerHTML = `<h4>⚠ ${conflictTypeText(conflict.type)}</h4><div>${escapeHtml(conflict.message)}</div>
    <div class="resolution-note">冲突 id: <code>${conflict.id}</code></div>`;

  if (conflict.resolved) {
    box.innerHTML += `<div class="resolution-note" style="color:#047857;margin-top:6px">已解决：${describeResolution(conflict.resolution)}</div>`;
  }
  body.appendChild(box);

  if (conflict.suggested) {
    const sg = document.createElement("div");
    sg.className = "suggestion";
    sg.innerHTML = `<div>检测到该路径的历史裁决（内容指纹已变化，仅作为建议，不会自动套用）。</div>
      <div class="resolution-note">原因：${escapeHtml(conflict.suggested.reason || "(无)")} · ${conflict.suggested.createdAt}</div>`;
    const use = document.createElement("button");
    use.textContent = "查看并采用该建议";
    use.onclick = () => prefillSuggestion(conflict, sg);
    sg.appendChild(document.createElement("br"));
    sg.appendChild(use);
    body.appendChild(sg);
  }

  if (!conflict.resolved) body.appendChild(resolutionControls(conflict));
}

function conflictTypeText(t) {
  return {
    VALUE: "值冲突",
    SEQ_HUNK: "有序序列冲突段",
    ORDER: "数组顺序冲突",
    DUP_ID: "重复稳定 id",
    MISSING_POLICY: "数组策略未登记",
  }[t] || t;
}

function describeResolution(r) {
  switch (r.kind) {
    case "take": return "采用 " + ({ BASE: "祖先", A: "分支A", B: "分支B" }[r.side]);
    case "set": return "手工设值";
    case "delete": return "删除（区别于置 null）";
    case "takeHunk": return "采用 " + ({ BASE: "祖先", A: "分支A", B: "分支B" }[r.side]) + " 的序列段";
    case "customHunk": return "手工序列段";
    case "takeOrder": return "采用 " + ({ BASE: "祖先", A: "分支A", B: "分支B" }[r.side]) + " 顺序";
    case "customOrder": return "手工 id 顺序";
    case "choosePolicy": return "登记数组策略：" + policyLabel(r.policy);
  }
  return JSON.stringify(r);
}

function resolutionControls(conflict) {
  const box = document.createElement("div");
  box.className = "resolve-controls";

  const addTakeButtons = (hunks = false) => {
    const row = document.createElement("div");
    row.className = "btn-row";
    const opts = [["BASE", "保留祖先"], ["A", "采用 A"], ["B", "采用 B"]];
    opts.forEach(([side, label]) => {
      const btn = document.createElement("button");
      btn.textContent = label;
      btn.onclick = () => enqueue(conflict.id, { kind: hunks ? "takeHunk" : "take", side }, box);
      row.appendChild(btn);
    });
    box.appendChild(row);
  };

  if (conflict.type === "VALUE") {
    addTakeButtons(false);
    // 显式删除（与置 null 分开）
    const delRow = document.createElement("div");
    delRow.className = "btn-row";
    const del = document.createElement("button");
    del.className = "danger";
    del.textContent = "删除该字段";
    del.onclick = () => enqueue(conflict.id, { kind: "delete" }, box);
    delRow.appendChild(del);
    box.appendChild(delRow);

    const custom = document.createElement("textarea");
    custom.placeholder = "手工裁决值（JSON 标量/对象/数组；写 null 表示显式置 null，而非删除）";
    box.appendChild(custom);
    const setBtn = document.createElement("button");
    setBtn.textContent = "手工设值（可为 null）";
    setBtn.onclick = () => {
      try {
        const parsed = JSON.parse(custom.value);
        enqueue(conflict.id, { kind: "set", node: jsonValueToNode(parsed) }, box);
      } catch (e) { toast("手工值不是合法 JSON: " + e.message, "error"); }
    };
    box.appendChild(setBtn);
  } else if (conflict.type === "SEQ_HUNK") {
    addTakeButtons(true);
    const custom = document.createElement("textarea");
    custom.placeholder = "手工序列段（JSON 数组）";
    box.appendChild(custom);
    const btn = document.createElement("button");
    btn.textContent = "提交手工序列段";
    btn.onclick = () => {
      try {
        const parsed = JSON.parse(custom.value);
        if (!Array.isArray(parsed)) throw new Error("必须是数组");
        enqueue(conflict.id, { kind: "customHunk", nodes: parsed.map(jsonValueToNode) }, box);
      } catch (e) { toast(e.message, "error"); }
    };
    box.appendChild(btn);
  } else if (conflict.type === "ORDER") {
    const row = document.createElement("div");
    row.className = "btn-row";
    [["BASE", "祖先顺序"], ["A", "A 顺序"], ["B", "B 顺序"]].forEach(([side, label]) => {
      const btn = document.createElement("button");
      btn.textContent = label;
      btn.onclick = () => enqueue(conflict.id, { kind: "takeOrder", side }, box);
      row.appendChild(btn);
    });
    box.appendChild(row);
    const input = document.createElement("input");
    input.type = "text";
    input.placeholder = "手工 id 顺序，逗号分隔，例如 web-1,web-2,web-3";
    box.appendChild(input);
    const btn = document.createElement("button");
    btn.textContent = "提交自定义顺序";
    btn.onclick = () => enqueue(conflict.id, { kind: "customOrder", ids: input.value.split(",").map((s) => s.trim()).filter(Boolean) }, box);
    box.appendChild(btn);
  } else if (conflict.type === "DUP_ID") {
    addTakeButtons(false);
    const note = document.createElement("div");
    note.className = "resolution-note";
    note.textContent = "选择一边整体采用可消除重复；也可以修改输入后重新合并。";
    box.appendChild(note);
  } else if (conflict.type === "MISSING_POLICY") {
    const detail = conflict.detail || {};
    const note = document.createElement("div");
    note.className = "resolution-note";
    note.innerHTML = `数组路径 <code>${escapeHtml(detail.pathKey || conflict.path)}</code> 未登记策略，必须显式选择：`;
    box.appendChild(note);
    const row = document.createElement("div");
    row.className = "btn-row";
    [["replace", "整体替换"], ["id", "按稳定 id 合并"], ["seq", "视为有序序列"]].forEach(([p, label]) => {
      const btn = document.createElement("button");
      btn.textContent = label;
      btn.onclick = () => {
        const idKey = p === "id" ? prompt("用于识别元素的字段名", "id") : null;
        if (p === "id" && !idKey) return;
        enqueue(conflict.id, { kind: "choosePolicy", policy: p, idKey }, box);
      };
      row.appendChild(btn);
    });
    box.appendChild(row);
  }

  addBatchControls(box);
  return box;
}

function addBatchControls(box) {
  const note = document.createElement("div");
  note.className = "resolution-note";
  note.textContent = "裁决会进入本页批量提交（乐观版本）；过期页面不会覆盖他人已解决项。";
  box.appendChild(note);
  const queueInfo = document.createElement("div");
  queueInfo.className = "resolution-note";
  const refreshInfo = () => {
    queueInfo.textContent = state.pendingResolutions.size
      ? `待提交 ${state.pendingResolutions.size} 项`
      : "暂无待提交裁决";
  };
  refreshInfo();
  box.appendChild(queueInfo);
  const row = document.createElement("div");
  row.className = "btn-row";
  const reason = document.createElement("input");
  reason.type = "text";
  reason.placeholder = "裁决理由（可选，会写入历史）";
  reason.style.flex = "1";
  row.appendChild(reason);
  const submit = document.createElement("button");
  submit.className = "primary";
  submit.textContent = "批量提交裁决";
  submit.onclick = async () => {
    if (state.pendingResolutions.size === 0) { toast("还没有待提交的裁决"); return; }
    const items = [...state.pendingResolutions.entries()].map(([conflictId, resolution]) => ({
      conflictId, resolution, reason: reason.value || "",
    }));
    try {
      const s = await api("POST", `/api/sessions/${state.session.id}/resolve`, {
        expectedRevision: state.session.revision,
        items,
      });
      state.session = s;
      state.pendingResolutions.clear();
      renderSession();
      toast(`已提交 ${items.length} 项裁决`, "success");
    } catch (e) {
      if (e.status === 409) {
        toast("版本已过期/部分冲突已被解决：已为你刷新，未提交项保留", "error");
        state.session = await api("GET", `/api/sessions/${state.session.id}`);
        // 清掉已解决项
        for (const id of (e.payload.alreadyResolved || [])) state.pendingResolutions.delete(id);
        renderSession();
      } else toast(e.message, "error");
    }
  };
  row.appendChild(submit);
  box.appendChild(row);
  box._refreshInfo = refreshInfo;
}

function enqueue(conflictId, resolution, controlsEl) {
  state.pendingResolutions.set(conflictId, resolution);
  const info = controlsEl.querySelector(".resolution-note:last-of-type");
  toast("已加入待提交：" + describeResolution({ kind: resolution.kind }), "success");
  const parent = controlsEl.closest(".resolve-controls");
  // 重新渲染该冲突控件以反映排队状态
  if (parent) parent.querySelectorAll("div.resolution-note").forEach((n) => {
    if (n.textContent.startsWith("待提交")) n.textContent = `待提交 ${state.pendingResolutions.size} 项`;
  });
}

function prefillSuggestion(conflict, host) {
  const d = conflict.suggested;
  // 建议仅展示，需人工确认：重新打开冲突面板并提示
  alert(`历史裁决 ${d.id}\n类型: ${describeResolution(deserializePayload(d.resolutionType, d.resolution))}\n理由: ${d.reason || "(无)"}\n\n如需采用，请在下方手动选择相同动作。`);
}

function deserializePayload(type, p) {
  if (type === "take") return { kind: "take", side: p.side };
  if (type === "delete") return { kind: "delete" };
  if (type === "set") return { kind: "set" };
  if (type === "choosePolicy") return { kind: "choosePolicy", policy: p.policy };
  if (type === "takeOrder") return { kind: "takeOrder", side: p.side };
  if (type === "customOrder") return { kind: "customOrder", ids: p.ids };
  if (type === "takeHunk") return { kind: "takeHunk", side: p.side };
  return { kind: type };
}

function jsonValueToNode(v) {
  if (v === null) return { kind: "scalar", value: { type: "null" } };
  if (typeof v === "boolean") return { kind: "scalar", value: { type: "bool", v } };
  if (typeof v === "number") return { kind: "scalar", value: { type: "number", v: String(v) } };
  if (typeof v === "string") return { kind: "scalar", value: { type: "string", v } };
  if (Array.isArray(v)) return { kind: "arr", items: v.map(jsonValueToNode) };
  const children = {};
  Object.entries(v).forEach(([k, x]) => { children[k] = jsonValueToNode(x); });
  return { kind: "obj", children };
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// ---------- 预览 / 导出 / 导入 ----------

async function openPreview() {
  if (!state.session) return;
  const fmt = $("exportFormat").value;
  openModal("导出文本预览（" + fmt + "）");
  const body = $("modalBody");
  body.innerHTML = "生成中…";
  try {
    const bundle = await api("GET", `/api/sessions/${state.session.id}/export?format=${fmt}`);
    body.innerHTML = `<div class="resolution-note">结果指纹：<code>${bundle.resultFingerprint}</code></div><pre>${escapeHtml(bundle.output.text)}</pre>`;
    setModalFoot([closeModalButton("关闭")]);
  } catch (e) {
    body.innerHTML = `<div class="error">${escapeHtml(e.message)}</div>`;
    if (e.payload && e.payload.unresolved) {
      const ul = document.createElement("ul");
      e.payload.unresolved.forEach((id) => {
        const li = document.createElement("li");
        const a = document.createElement("a");
        a.href = "#";
        a.textContent = id;
        a.onclick = () => { closeModal(); jumpToConflict(id); return false; };
        li.appendChild(a);
        ul.appendChild(li);
      });
      body.appendChild(ul);
    }
    setModalFoot([closeModalButton("关闭")]);
  }
}

async function exportBundle() {
  if (!state.session) return;
  const fmt = $("exportFormat").value;
  openModal("导出 / 下载（可重放）");
  const body = $("modalBody");
  body.innerHTML = "生成中…";
  try {
    const bundle = await api("GET", `/api/sessions/${state.session.id}/export?format=${fmt}`);
    const ta = document.createElement("textarea");
    ta.value = JSON.stringify(bundle, null, 2);
    body.innerHTML = "";
    body.appendChild(ta);
    const dl = document.createElement("button");
    dl.textContent = "下载 bundle.json";
    dl.onclick = () => {
      const blob = new Blob([ta.value], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url; a.download = "merge-bundle.json"; a.click();
      URL.revokeObjectURL(url);
    };
    setModalFoot([dl, closeModalButton("关闭")]);
  } catch (e) {
    body.innerHTML = `<div class="error">${escapeHtml(e.message)}</div>`;
    setModalFoot([closeModalButton("关闭")]);
  }
}

function importBundlePrompt() {
  openModal("导入 bundle（验证路径/来源链/结果指纹）");
  const body = $("modalBody");
  const ta = document.createElement("textarea");
  ta.placeholder = "粘贴之前导出的 bundle JSON";
  body.innerHTML = "";
  body.appendChild(ta);
  const go = document.createElement("button");
  go.className = "primary";
  go.textContent = "导入并重放";
  go.onclick = async () => {
    try {
      const parsed = JSON.parse(ta.value);
      const res = await api("POST", "/api/import", parsed);
      const ok = res.inputFingerprintsMatch && res.resultFingerprintMatches && res.outputTextMatches;
      body.innerHTML = `<pre>${escapeHtml(JSON.stringify({
        新会话: res.sessionId,
        输入指纹一致: res.inputFingerprintsMatch,
        结果指纹: res.resultFingerprint,
        结果指纹一致: res.resultFingerprintMatches,
        输出文本逐字节一致: res.outputTextMatches,
        未决冲突数: res.unresolvedCount,
      }, null, 2))}</pre>`;
      if (ok) toast("导入校验通过：路径/来源链/结果指纹一致", "success");
      else toast("导入完成但存在差异，请查看报告", "error");
      state.session = res.session;
      $("composer").hidden = true;
      document.querySelector(".workspace").hidden = false;
      renderSession();
    } catch (e) {
      toast("导入失败: " + e.message, "error");
    }
  };
  setModalFoot([go, closeModalButton("取消")]);
}

function jumpToConflict(id) {
  const conflict = state.session.conflicts.find((c) => c.id === id);
  if (!conflict) return;
  // 展开结果树到该路径：简化处理——直接在详情面板展示该冲突
  const fake = { kind: "conflict", conflictId: id, path: conflict.path, sources: [] };
  $("detailPanel").hidden = false;
  state.selectedPath = { path: conflict.path, kind: "result", node: fake };
  showDetail(fake, "result");
}

function openModal(title) {
  $("modalTitle").textContent = title;
  $("modalBody").innerHTML = "";
  $("modalFoot").innerHTML = "";
  $("modalOverlay").hidden = false;
}
function setModalFoot(buttons) {
  const foot = $("modalFoot");
  foot.innerHTML = "";
  buttons.forEach((b) => foot.appendChild(b));
}
function closeModalButton(label) {
  const b = document.createElement("button");
  b.textContent = label || "关闭";
  b.onclick = closeModal;
  return b;
}
function closeModal() { $("modalOverlay").hidden = true; }

function renderDetailForSelection() {
  if (!state.selectedPath) return;
  showDetail(state.selectedPath.node, state.selectedPath.kind);
}

// ---------- 初始化 ----------

$("btnSample").onclick = loadSample;
$("btnMerge").onclick = startMerge;
$("btnPreview").onclick = openPreview;
$("btnExport").onclick = exportBundle;
$("btnImport").onclick = importBundlePrompt;
$("btnCloseModal").onclick = closeModal;
$("btnCloseDetail").onclick = () => { $("detailPanel").hidden = true; state.selectedPath = null; renderSession(); };
$("exportFormat").onchange = () => {};

$("btnUpdateInputs").onclick = async () => {
  try {
    const s = await api("PUT", `/api/sessions/${state.session.id}/inputs`, {
      base: { text: $("baseText").value, format: fmtOf("baseFormat") || "YAML", fileName: "base.yaml" },
      a: { text: $("aText").value, format: fmtOf("aFormat") || "YAML", fileName: "a.yaml" },
      b: { text: $("bText").value, format: fmtOf("bFormat") || "YAML", fileName: "b.yaml" },
    });
    state.session = s;
    renderSession();
    toast("输入已更新并重算", "success");
  } catch (e) { toast(e.message, "error"); }
};

loadSample();

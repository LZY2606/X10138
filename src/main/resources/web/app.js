"use strict";
const $ = (id) => document.getElementById(id);
const state = {
  session: null, merge: null, selectedPath: null,
  expanded: new Set(["$"]), choice: {}, custom: {},
  inputTexts: { base: "", a: "", b: "" }, inputFormats: { base: "YAML", a: "YAML", b: "YAML" }
};

async function api(method, path, body) {
  const opt = { method, headers: {} };
  if (body !== undefined) { opt.headers["Content-Type"] = "application/json"; opt.body = JSON.stringify(body); }
  const r = await fetch(path, opt);
  const j = await r.json();
  if (!r.ok || j.error) { const e = new Error(j.message || ("HTTP " + r.status)); e.status = r.status; e.busy = j.busy; throw e; }
  return j;
}
function toast(msg, isErr) {
  const t = $("toast"); t.textContent = msg; t.className = "toast" + (isErr ? " err" : "");
  t.style.display = "block"; clearTimeout(toast._h); toast._h = setTimeout(() => t.style.display = "none", 4200);
}
function esc(s) { return String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }
function numFmt(v) { return v == null ? "" : (typeof v === "string" ? v : String(v)); }

async function loadSessions(selectId) {
  const list = await api("GET", "/api/sessions");
  const sel = $("sessions");
  sel.innerHTML = "";
  list.forEach(s => {
    const o = document.createElement("option");
    o.value = s.id; o.textContent = `${s.title} (${s.id}) v${s.version}`;
    sel.appendChild(o);
  });
  return list;
}

async function pickSession(id) {
  const s = await api("GET", `/api/sessions/${id}`);
  state.session = s; state.merge = null; state.selectedPath = null;
  fillSession(s);
  clearTrees();
  if (s.ready) { try { await doMerge(true); } catch (e) { /* NO_STRATEGY 等正常 */ } }
}
function fillSession(s) {
  $("title").value = s.title || "";
  $("st-ver").textContent = "会话 v" + s.version;
  $("out-format").value = s.outputFormat;
  let fp = "";
  if (s.baseFp) fp += "base: " + s.baseFp.slice(0, 12) + "\n";
  if (s.aFp) fp += "A: " + s.aFp.slice(0, 12) + "\n";
  if (s.bFp) fp += "B: " + s.bFp.slice(0, 12);
  $("fp").textContent = fp;
  $("btn-merge").disabled = !(s.hasBase && s.hasA && s.hasB);
  renderStrategies(s.strategies || []);
}
function renderStrategies(list) {
  const box = $("strat-list"); box.innerHTML = "";
  list.forEach(e => {
    const d = document.createElement("div");
    d.className = "confitem";
    d.innerHTML = `<div class="p">${esc(e.path)}</div>
      <div>${esc(e.strategy)}${e.strategy === "ID" ? " by " + esc(e.idField) : ""}</div>`;
    const del = document.createElement("button");
    del.textContent = "删除"; del.style.marginTop = "4px";
    del.onclick = async () => {
      await api("DELETE", `/api/sessions/${state.session.id}/strategies?path=${encodeURIComponent(e.path)}`);
      await pickSession(state.session.id);
    };
    d.appendChild(del); box.appendChild(d);
  });
}

// ---------- 输入对话框 ----------
function openInput(side) {
  const cur = state.inputTexts[side] || "";
  const fmt = state.inputFormats[side] || "YAML";
  const text = promptModal(`输入${sideName(side)}的配置（${fmt}，支持 YAML/JSON；删除用 !delete 或 {"$delete":true}）`, cur);
  return text;
}
function sideName(s) { return { base: "共同祖先", a: "分支 A", b: "分支 B" }[s]; }

// 极简模态编辑（用固定遮罩）
function promptModal(label, initial) {
  return new Promise((resolve) => {
    const wrap = document.createElement("div");
    wrap.style.cssText = "position:fixed;inset:0;background:rgba(5,8,15,.75);z-index:100;display:flex;align-items:center;justify-content:center";
    wrap.innerHTML = `<div style="background:#161c2b;border:1px solid #2a3450;border-radius:10px;width:min(820px,92vw);padding:16px">
      <h3 style="margin:0 0 8px">${esc(label)}</h3>
      <textarea id="m-text" style="min-height:320px;width:100%;font-family:ui-monospace,monospace"></textarea>
      <label class="fl">格式
        <select id="m-fmt"><option>YAML</option><option>JSON</option><option>AUTO</option></select>
      </label>
      <div style="text-align:right;margin-top:8px">
        <button id="m-cancel">取消</button> <button id="m-ok" class="primary">保存输入</button>
      </div></div>`;
    document.body.appendChild(wrap);
    $("m-text").value = initial || "";
    $("m-text").focus();
    const done = (v) => { wrap.remove(); resolve(v); };
    $("m-cancel").onclick = () => done(null);
    $("m-ok").onclick = () => done({ text: $("m-text").value, fmt: $("m-fmt").value });
  });
}

document.querySelectorAll("[data-in]").forEach(btn => {
  btn.onclick = async () => {
    const side = btn.getAttribute("data-in");
    const res = await openInput(side);
    if (!res) return;
    if (!state.session) await createSession();
    try {
      const s = await api("POST", `/api/sessions/${state.session.id}/inputs?side=${side}`,
        { raw: res.text, format: res.fmt === "AUTO" ? null : res.fmt });
      state.inputTexts[side] = res.text; state.inputFormats[side] = res.fmt;
      fillSession(s); toast(`${sideName(side)} 已保存并解析`);
      if (s.hasBase && s.hasA && s.hasB) { /* 等待手动运行合并 */ }
    } catch (e) { toast(e.message, true); }
  };
});

async function createSession() {
  const s = await api("POST", "/api/sessions", { title: $("title").value || "未命名合并" });
  state.session = s;
  await loadSessions();
  document.querySelector(`#sessions option[value="${s.id}"]`).selected = true;
  fillSession(await api("GET", `/api/sessions/${s.id}`));
}

// ---------- 合并与三栏树 ----------
async function doMerge(silent) {
  const out = await api("POST", `/api/sessions/${state.session.id}/merge`);
  state.session = out.session; state.merge = out.merge;
  fillSession(out.session);
  renderMerge();
  if (!silent) toast(`合并完成：自动 ${out.merge.autoCount}，冲突 ${out.merge.unresolvedCount}`);
}
$("btn-merge").onclick = () => doMerge(false).catch(e => toast(e.message, true));

function valueClass(v) {
  if (!v || !v.present) return v && v.kind === "删除" ? "tag-del" : "tag-miss";
  if (v.kind === "null") return "tag-null";
  return "";
}
function valueText(v) {
  if (!v) return "<缺失>";
  if (!v.present) return v.kind === "删除" ? "<删除>" : "<缺失>";
  return v.preview;
}

// 用同一个合并节点，分别展示 base/a/b 三方值；结构按 children 展开
function renderTree(elId, sideKey) {
  const root = state.merge.root;
  const el = $(elId); el.innerHTML = "";
  el.appendChild(buildNode(root, sideKey, 0, true));
}

function buildNode(node, sideKey, depth, isRoot) {
  const wrap = document.createElement("div");
  wrap.className = "node" + (isRoot ? " node-root" : "");
  const row = document.createElement("div");
  row.className = "row";
  if (node.path === state.selectedPath) row.classList.add("sel");
  const hasKids = node.children && node.children.length > 0;
  const open = state.expanded.has(node.path);
  const twist = document.createElement("span");
  twist.className = "twist"; twist.textContent = hasKids ? (open ? "▾" : "▸") : "";
  twist.onclick = (e) => { e.stopPropagation(); if (!hasKids) return;
    if (open) state.expanded.delete(node.path); else state.expanded.add(node.path); rerenderTrees(); };
  row.appendChild(twist);

  const dot = document.createElement("span");
  dot.className = "dot " + node.status; row.appendChild(dot);

  const kv = document.createElement("span"); kv.className = "kv"; kv.textContent = node.label;
  row.appendChild(kv);

  const v = node[sideKey];
  const val = document.createElement("span"); val.className = "val " + valueClass(v);
  val.textContent = (sideKey !== "result") ? valueText(v) : (node.result || "");
  row.appendChild(val);

  const pill = document.createElement("span");
  pill.className = "pill " + (node.resolved ? "RESOLVED" : node.status);
  pill.textContent = node.resolved ? "已决" :
    (node.status === "AUTO" ? "自动" : node.status === "MAP" ? "对象" :
     node.status === "ARRAY" ? "数组" : node.status === "BLOCKED" ? "阻塞" : "冲突");
  if (node.status === "MAP" || node.status === "AUTO") pill.style.display = "none";
  row.appendChild(pill);

  row.onclick = () => selectPath(node.path);
  wrap.appendChild(row);

  if (hasKids && open) {
    node.children.forEach(c => wrap.appendChild(buildNode(c.node, sideKey, depth + 1, false)));
  }
  return wrap;
}

function rerenderTrees() {
  if (!state.merge) return;
  renderTree("tree-base", "base");
  renderTree("tree-a", "a");
  renderTree("tree-b", "b");
}

function renderMerge() {
  const m = state.merge;
  $("st-auto").textContent = "自动 " + m.autoCount;
  $("st-conf").textContent = "未决 " + m.unresolvedCount;
  $("st-res").textContent = "已决 " + m.resolvedCount;
  state.expanded.add(m.root.path);
  rerenderTrees();
  renderConflicts();
  if (state.selectedPath) renderDetail(findNode(m.root, state.selectedPath));
  renderExport();
  loadHistorySilent();
}

function findNode(node, path) {
  if (node.path === path) return node;
  for (const c of node.children || []) { const f = findNode(c.node, path); if (f) return f; }
  return null;
}
function collectConflicts(node, out) {
  if (node.conflict && !node.resolved) out.push(node);
  (node.children || []).forEach(c => collectConflicts(c.node, out));
}

function renderConflicts() {
  const box = $("conf-list");
  const list = []; collectConflicts(state.merge.root, list);
  if (!list.length) { box.innerHTML = '<div class="muted">无未决冲突</div>'; return; }
  box.innerHTML = "";
  list.forEach(n => {
    const d = document.createElement("div"); d.className = "confitem";
    d.innerHTML = `<div class="p">${esc(n.path)}</div><div>${esc(n.conflict.kind)}</div>`;
    d.onclick = () => selectPath(n.path);
    box.appendChild(d);
  });
}

function selectPath(path) {
  state.selectedPath = path;
  rerenderTrees();
  const node = findNode(state.merge.root, path);
  renderDetail(node);
  $("tab-detail").click();
}

// ---------- 节点详情 / 逐项裁决 ----------
function srcBlock(v) {
  const anchors = (v.anchors && v.anchors.length) ? `<div class="anchor-hint">锚点: ${esc(v.anchors.join(", "))}</div>` : "";
  return `<div class="k">${esc(v.kind)}</div><div><span class="${valueClass(v)}">${esc(valueText(v))}</span>${anchors}</div>`;
}
function renderDetail(node) {
  if (!node) return;
  const c = node.conflict;
  let html = `<div class="card">
    <h4>${esc(node.label)} <span class="muted" style="font-size:11px">${esc(node.path)}</span></h4>
    <div class="kvsrc">
      <div class="k">共同祖先</div><div></div>${srcBlock(node.base)}
      <div class="k">分支 A</div><div></div>${srcBlock(node.a)}
      <div class="k">分支 B</div><div></div>${srcBlock(node.b)}
      <div class="k">当前结果</div><div><b>${esc(node.result || "<未物化>")}</b></div>
    </div></div>`;

  html += `<div class="card"><h4>来源链</h4><ul class="prov">`;
  (node.provenance || []).forEach(p => {
    html += `<li><b>${esc(p.sideName)}</b> <span class="muted">${esc(p.path)}</span> — ${esc(p.action)}` +
      (p.anchor ? ` <span class="anchor-hint">⚓${esc(p.anchor)}</span>` : "") + `</li>`;
  });
  if (!node.provenance || !node.provenance.length) html += `<li class="muted">（叶子合并后出现）</li>`;
  html += `</ul></div>`;

  if (node.autoSummary) html += `<div class="card"><span class="badge auto">自动合并</span> ${esc(node.autoSummary)}</div>`;

  if (c) {
    if (node.resolvedBy) {
      html += `<div class="card"><h4>已解决</h4><div>裁决 <b>${esc(node.resolvedBy.decisionId)}</b> 选择了 ${esc(node.resolvedBy.choiceId)}${node.resolvedBy.custom ? "（自定义）" : ""}<br>
        <span class="muted">${esc(node.resolvedBy.at)}</span></div></div>`;
    } else {
      if (node.advisory) {
        html += `<div class="card" style="border-color:var(--warn)"><h4>旧裁决仅作建议</h4>
          <div>${esc(node.advisory.reason)}</div><div class="muted">建议选择: ${esc(node.advisory.suggestedChoice)}（${esc(node.advisory.oldDecisionId)}）</div></div>`;
      }
      html += `<div class="card"><h4>冲突原因</h4><div style="margin-bottom:8px"><span class="badge conf">${esc(c.kind)}</span> ${esc(c.reason)}</div>`;
      if (!c.choices || !c.choices.length) {
        html += `<div class="muted">该冲突由缺失策略引起，请先在左侧为数组路径登记策略，再重新合并。</div>`;
      } else {
        c.choices.forEach(ch => {
          const cur = state.choice[node.path] || "A";
          html += `<button class="choice ${cur === ch.id ? "sel" : ""}" data-choice="${ch.id}">
            <b>${esc(ch.label)}</b><div class="preview">${esc(ch.preview)}</div></button>`;
        });
        html += `<button class="choice ${state.choice[node.path] === "CUSTOM" ? "sel" : ""}" data-choice="CUSTOM"><b>自定义（编辑文本）</b></button>
          <textarea id="custom-text" placeholder="自定义结果，例如 keepalive: true" style="margin-top:6px">${esc(state.custom[node.path] || "")}</textarea>
          <select id="custom-fmt"><option value="YAML">YAML</option><option value="JSON" ${(state.customFmt && state.customFmt[node.path] === "JSON") ? "selected" : ""}>JSON</option></select>
          <button class="primary" id="btn-resolve" style="width:100%;margin-top:8px">提交此项裁决（乐观版本 v${state.session.version}）</button>`;
      }
      html += `</div>`;
    }
  }
  $("pane-detail").innerHTML = html;
  $("pane-detail").querySelectorAll("[data-choice]").forEach(btn => {
    btn.onclick = () => {
      state.choice[node.path] = btn.getAttribute("data-choice");
      renderDetail(node);
    };
  });
  const ta = $("custom-text");
  if (ta) ta.oninput = () => { state.custom[node.path] = ta.value; state.choice[node.path] = "CUSTOM"; };
  const cf = $("custom-fmt");
  if (cf) cf.onchange = () => { state.customFmt = state.customFmt || {}; state.customFmt[node.path] = cf.value; };
  const br = $("btn-resolve");
  if (br) br.onclick = () => resolveOne(node);
}

async function resolveOne(node) {
  const choice = state.choice[node.path] || "A";
  const item = { path: node.path, choiceId: choice };
  if (choice === "CUSTOM") {
    item.customText = state.custom[node.path] || "";
    item.customFormat = (state.customFmt && state.customFmt[node.path]) || "YAML";
  }
  try {
    const r = await api("POST", `/api/sessions/${state.session.id}/decisions`,
      { baseVersion: state.session.version, author: "local", items: [item] });
    state.session = r.outcome.session; state.merge = r.outcome.merge;
    fillSession(r.outcome.session); renderMerge();
    toast("已提交裁决 " + r.accepted.join(","));
  } catch (e) {
    if (e.status === 409) {
      toast("版本过期或该项已被解决，正在刷新合并结果…", true);
      await doMerge(true);
    } else toast(e.message, true);
  }
}

// ---------- 导出 / 历史 ----------
function renderExport() {
  const m = state.merge; if (!m) return;
  $("export-preview").textContent = m.exported || "";
  $("export-fp").textContent = "结果指纹 sha256: " + m.resultFingerprint;
  $("btn-export").disabled = !m.exportable;
}
$("out-format").onchange = async () => {
  await api("POST", `/api/sessions/${state.session.id}/output-format`, { format: $("out-format").value });
  await doMerge(true);
};
$("btn-export").onclick = async () => {
  const j = await api("GET", `/api/sessions/${state.session.id}/export`);
  const blob = new Blob([JSON.stringify(j, null, 2)], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob); a.download = `merge-bundle-${state.session.id}.json`;
  a.click();
};
$("import-file").onchange = async (ev) => {
  const f = ev.target.files[0]; if (!f) return;
  const text = await f.text();
  try {
    const parsed = JSON.parse(text);
    const out = await api("POST", "/api/import", parsed);
    state.session = out.session; state.merge = out.merge;
    await loadSessions();
    document.querySelector(`#sessions option[value="${out.session.id}"]`).selected = true;
    fillSession(out.session); renderMerge();
    toast("导入重放成功，路径与结果指纹一致");
  } catch (e) { toast("导入失败: " + e.message, true); }
};
async function loadHistorySilent() {
  if (!state.session) return;
  const list = await api("GET", `/api/sessions/${state.session.id}/history`);
  if (!list.length) { $("history").className = "muted"; $("history").textContent = "暂无裁决"; return; }
  $("history").className = "";
  $("history").innerHTML = list.map(d => `<div class="confitem">
    <div class="p">${esc(d.path)}</div>
    <div>${esc(d.choiceId)} · ${esc(d.conflictKind)} · ${esc(d.author)}</div>
    <div class="muted" style="word-break:break-all">${esc(d.id)} @ ${esc(d.createdAt)}<br>
    base ${d.baseFingerprint.slice(0,8)} A ${d.aFingerprint.slice(0,8)} B ${d.bFingerprint.slice(0,8)}</div></div>`).join("");
}
$("tab-history").onclick = () => { showTab("history"); loadHistorySilent(); };
$("tab-export").onclick = () => showTab("export");
$("tab-detail").onclick = () => showTab("detail");
function showTab(name) {
  ["detail","export","history"].forEach(t => {
    $("pane-" + t).style.display = t === name ? "" : "none";
    $("tab-" + t).className = t === name ? "primary" : "";
  });
}
function clearTrees() { ["tree-base","tree-a","tree-b"].forEach(id => $(id).innerHTML = ""); }

$("btn-new").onclick = async () => { await createSession(); clearTrees(); toast("已创建新会话"); };
$("sessions").onchange = (e) => { if (e.target.value) pickSession(e.target.value); };
$("strat-add").onclick = async () => {
  if (!state.session) await createSession();
  try {
    const s = await api("POST", `/api/sessions/${state.session.id}/strategies`, {
      path: $("strat-path").value.trim(), strategy: $("strat-kind").value, idField: $("strat-idfield").value
    });
    fillSession(s); toast("策略已登记（策略版本 v" + s.strategies.length + "）");
    if (state.merge) await doMerge(true);
  } catch (e) { toast(e.message, true); }
};
$("title").onchange = async () => {
  // 标题跟随新建；这里仅本地保留
};

(async function init() {
  const list = await loadSessions();
  if (list.length) { await pickSession(list[0].id); }
  else { state.session = null; }
})();

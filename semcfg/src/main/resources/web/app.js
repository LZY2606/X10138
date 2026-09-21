"use strict";

const state = {
    data: null,
    branchTab: "A",
    selectedPath: null,
    selectedPane: null,
    pendingVersion: null,
};

const STATUS_LABEL = {
    UNCHANGED: "未变化",
    AUTO_A: "自动·A",
    AUTO_B: "自动·B",
    AUTO_BOTH: "自动·两侧一致",
    DELETED_A: "删除·A",
    DELETED_B: "删除·B",
    CONFLICT: "冲突",
    RESOLVED: "已裁决",
    SUGGESTED: "旧裁决建议",
    NEEDS_POLICY: "缺少策略",
};

const SIDE_LABEL = {
    BASE: "共同祖先",
    BRANCH_A: "分支 A",
    BRANCH_B: "分支 B",
    MERGED: "合并结果",
    DECISION: "人工裁决",
};

const ACTION_LABEL = {
    missing: "缺失",
    value: "值",
    "null": "显式 null",
    delete: "删除",
    map: "对象",
    list: "数组",
};

async function api(path, body) {
    const opts = body !== undefined
        ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
        : {};
    const resp = await fetch(path, opts);
    const json = await resp.json();
    if (!resp.ok) {
        const err = new Error(json.error || ("HTTP " + resp.status));
        err.status = resp.status;
        err.payload = json;
        throw err;
    }
    return json;
}

function toast(msg, bad) {
    const el = document.getElementById("toast");
    el.textContent = msg;
    el.className = "toast" + (bad ? " bad" : "");
    setTimeout(() => el.classList.add("hidden"), 4200);
}

function fpShort(fp) {
    if (!fp || fp.startsWith("missing:") || fp.startsWith("deleted:")) return fp.split(":")[0];
    return fp.slice(0, 10);
}

function loadState(json) {
    state.data = json;
    state.pendingVersion = json.version;
    render();
}

function render() {
    const d = state.data;
    document.getElementById("session-version").textContent = d.version;
    document.getElementById("policy-version").textContent = d.policyVersion;
    document.getElementById("stat-auto").textContent = d.stats.autoMerged;
    document.getElementById("stat-conflicts").textContent =
        d.conflicts.filter(c => c.status === "open").length + "/" + d.conflicts.length;
    document.getElementById("stat-resolved").textContent = d.stats.resolved;
    document.getElementById("stat-advisory").textContent = d.stats.advisory;
    document.getElementById("result-fp").textContent = "fp " + (d.stats.resultFingerprint || "").slice(0, 16);

    document.getElementById("tree-base").innerHTML = "";
    document.getElementById("tree-base").appendChild(renderSourceTree(d.inputs.base.tree, "base"));
    document.getElementById("tree-merged").innerHTML = "";
    document.getElementById("tree-merged").appendChild(renderMergedTree(d.merged.tree));

    const sideKey = state.branchTab === "A" ? "branchA" : "branchB";
    document.getElementById("tree-branch").innerHTML = "";
    document.getElementById("tree-branch").appendChild(renderSourceTree(d.inputs[sideKey].tree, "branch"));
    document.getElementById("tab-a").classList.toggle("active", state.branchTab === "A");
    document.getElementById("tab-b").classList.toggle("active", state.branchTab === "B");

    if (state.selectedPath) renderDetail();
}

/* ---------- source trees (base / branches) ---------- */

function renderSourceTree(node, pane) {
    const ul = document.createElement("ul");
    if (!node) return ul;
    const li = document.createElement("li");
    li.appendChild(sourceNodeEl(node, pane));
    if (node.children) {
        const childUl = document.createElement("ul");
        for (const ch of node.children) {
            const cli = document.createElement("li");
            cli.appendChild(sourceNodeEl(ch.node, pane, ch.key));
            if (ch.node.children) cli.appendChild(sourceChildren(ch.node, pane));
            childUl.appendChild(cli);
        }
        li.appendChild(childUl);
    }
    ul.appendChild(li);
    return ul;
}

function sourceChildren(node, pane) {
    const childUl = document.createElement("ul");
    for (const ch of node.children) {
        const cli = document.createElement("li");
        cli.appendChild(sourceNodeEl(ch.node, pane, ch.key));
        if (ch.node.children) cli.appendChild(sourceChildren(ch.node, pane));
        childUl.appendChild(cli);
    }
    return childUl;
}

function sourceNodeEl(node, pane, key) {
    const row = document.createElement("div");
    row.className = "node";
    if (state.selectedPane === pane && state.selectedPath === node.path) row.classList.add("selected");
    const twist = document.createElement("span");
    twist.className = "twist";
    twist.textContent = node.children ? "▾" : " ";
    row.appendChild(twist);
    if (key !== undefined) {
        const k = document.createElement("span");
        k.className = "k";
        k.textContent = key + ":";
        row.appendChild(k);
    }
    const v = document.createElement("span");
    v.className = "v scalar";
    if (node.kind === "scalar") {
        v.textContent = node.scalarType === "null" ? "null" : node.value;
        if (node.scalarType === "null") v.classList.add("null");
    } else {
        v.className = "v";
        v.textContent = node.kind === "map" ? `{…${node.children.length} 项}` : `[…${node.children.length} 项]`;
        v.style.color = "#8994ac";
    }
    row.appendChild(v);
    row.onclick = () => {
        state.selectedPane = pane;
        state.selectedPath = node.path;
        showDetailSource(node, pane);
    };
    return row;
}

/* ---------- merged tree ---------- */

function renderMergedTree(node) {
    const ul = document.createElement("ul");
    const li = document.createElement("li");
    li.appendChild(mergedNodeEl(node));
    li.appendChild(mergedChildren(node));
    ul.appendChild(li);
    return ul;
}

function mergedChildren(node) {
    const ul = document.createElement("ul");
    const kids = mergedKidList(node);
    for (const k of kids) {
        const li = document.createElement("li");
        li.appendChild(mergedNodeEl(k.node, k.label));
        li.appendChild(mergedChildren(k.node));
        ul.appendChild(li);
    }
    return ul;
}

function mergedKidList(node) {
    if (node.kind === "map" && node.children) {
        return node.children.map(c => ({ label: c.key, node: c.node }));
    }
    if (node.kind === "list") {
        const out = [];
        if (node.children) for (const it of node.children) {
            out.push({ label: "id=" + it.id + " [" + statusShort(it.order) + "]", node: it.node });
        }
        if (node.hunks) for (const h of node.hunks) for (const e of h.merged) {
            out.push({ label: "#" + node.path + " hunk", node: e.node });
        }
        return out;
    }
    return [];
}

function statusShort(o) {
    return { FROM_BASE: "base", FROM_A: "A", FROM_B: "B", CONFLICT: "顺序冲突", RESOLVED: "已裁决" }[o] || o;
}

function mergedNodeEl(node, keyLabel) {
    const row = document.createElement("div");
    row.className = "node";
    if (state.selectedPane === "merged" && state.selectedPath === node.path) row.classList.add("selected");
    const twist = document.createElement("span");
    twist.className = "twist";
    twist.textContent = (node.kind === "map" || node.kind === "list") ? "▾" : " ";
    row.appendChild(twist);

    const badge = document.createElement("span");
    badge.className = "badge s-" + node.status;
    badge.textContent = STATUS_LABEL[node.status] || node.status;
    row.appendChild(badge);

    if (keyLabel !== undefined) {
        const k = document.createElement("span");
        k.className = "k";
        k.textContent = keyLabel + ":";
        row.appendChild(k);
    }
    const v = document.createElement("span");
    if (node.kind === "deleted") {
        v.className = "v deleted";
        v.textContent = "<已删除>";
    } else if (node.kind === "scalar") {
        v.className = "v scalar" + (node.value.type === "null" ? " null" : "");
        v.textContent = node.value.type === "null" ? "null" : node.value.text;
    } else {
        v.className = "v";
        v.textContent = node.kind === "map"
            ? `{…${(node.children || []).length} 项}`
            : `[策略 ${node.strategy}]`;
    }
    row.appendChild(v);
    row.onclick = () => {
        state.selectedPane = "merged";
        state.selectedPath = node.path;
        showDetailMerged(node);
    };
    return row;
}

/* ---------- detail panel ---------- */

function showDetailSource(node, pane) {
    const panel = document.getElementById("detail");
    panel.classList.remove("hidden");
    const body = document.getElementById("detail-body");
    const sideName = pane === "base" ? "共同祖先" : (state.branchTab === "A" ? "分支 A" : "分支 B");
    body.innerHTML = "";
    body.appendChild(kvHtml("来源", sideName));
    body.appendChild(kvHtml("路径", node.path));
    body.appendChild(kvHtml("类型", node.kind === "scalar" ? "标量 · " + node.scalarType : node.kind));
    if (node.kind === "scalar") body.appendChild(kvHtml("值", node.scalarType === "null" ? "null（显式）" : node.value));
    body.appendChild(small("缺失键不会出现在树中；显式 null 与删除在合并结果里使用不同标记。"));
}

function kvHtml(label, value) {
    const div = document.createElement("div");
    div.className = "kv";
    const l = document.createElement("div");
    l.className = "label";
    l.textContent = label;
    const v = document.createElement("div");
    v.textContent = value;
    div.appendChild(l); div.appendChild(v);
    return div;
}

function small(text) {
    const p = document.createElement("p");
    p.className = "small";
    p.textContent = text;
    return p;
}

function showDetailMerged(node) {
    const panel = document.getElementById("detail");
    panel.classList.remove("hidden");
    const body = document.getElementById("detail-body");
    body.innerHTML = "";

    body.appendChild(kvHtml("路径", node.path));
    body.appendChild(kvHtml("状态", STATUS_LABEL[node.status] || node.status));
    body.appendChild(kvHtml("说明", node.explanation));

    if (node.conflict) {
        const h = document.createElement("h4");
        h.textContent = "冲突原因";
        body.appendChild(h);
        const r = document.createElement("div");
        r.className = "reason";
        r.textContent = `[${node.conflict.kind}] ${node.conflict.message}`;
        body.appendChild(r);
        const keyP = document.createElement("p");
        keyP.className = "small";
        keyP.textContent = "冲突键: " + node.conflict.key;
        body.appendChild(keyP);
        buildResolver(body, node.conflict.key, node);
    }
    if (node.orderConflict) {
        const h = document.createElement("h4");
        h.textContent = "顺序冲突";
        body.appendChild(h);
        const r = document.createElement("div");
        r.className = "reason";
        r.textContent = node.orderConflict.message;
        body.appendChild(r);
        buildOrderResolver(body, node.orderConflict.key, node);
    }
    if (node.suggestion) {
        const adv = document.createElement("div");
        adv.className = "reason";
        adv.style.background = "#3a3320";
        adv.textContent = `旧裁决仅作建议：${node.suggestion.resolutionLabel}（${node.suggestion.reason}），不会自动套用。`;
        body.appendChild(adv);
    }

    const ch = document.createElement("h4");
    ch.textContent = "来源链（祖先 → A → B → 裁决）";
    body.appendChild(ch);
    for (const s of node.chain) {
        const row = document.createElement("div");
        row.className = "chain-row";
        row.title = s.fingerprint + (s.detail ? "\n" + s.detail : "");
        row.innerHTML =
            `<div class="chain-side">${SIDE_LABEL[s.side] || s.side}</div>` +
            `<div class="chain-action">${ACTION_LABEL[s.action] || s.action}</div>` +
            `<div class="chain-fp">${fpShort(s.fingerprint)}${s.detail ? " · " + escapeHtml(s.detail) : ""}</div>`;
        body.appendChild(row);
    }
    if (node.fingerprints) {
        body.appendChild(small("fp base=" + node.fingerprints.base.slice(0, 12) +
            " A=" + node.fingerprints.branchA.slice(0, 12) +
            " B=" + node.fingerprints.branchB.slice(0, 12)));
    }

    if (node.kind === "list") buildPolicyBox(body, node);
}

function escapeHtml(t) {
    return String(t).replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
}

/* ---------- conflict resolution ---------- */

function buildResolver(body, conflictKey, node) {
    const conflict = state.data.conflicts.find(c => c.key === conflictKey);
    if (!conflict) return;
    const h = document.createElement("h4");
    h.textContent = "人工裁决（绑定三侧指纹，重放前校验）";
    body.appendChild(h);
    if (conflict.status === "resolved") {
        body.appendChild(small("该冲突已应用裁决: " + conflict.priorResolution));
    }
    if (conflict.status === "stale-advice") {
        body.appendChild(small("存在旧裁决 " + conflict.priorResolution + "，但" + conflict.mismatchReason + "；仅作建议。"));
    }
    const row = document.createElement("div");
    row.className = "resolve-row";
    const buttons = [
        ["takeA", "采纳 A"], ["takeB", "采纳 B"], ["keepBase", "保留祖先"],
        ["setNull", "设为 null"], ["delete", "删除"], ["custom", "自定义值…"],
    ];
    for (const [type, label] of buttons) {
        const b = document.createElement("button");
        b.textContent = label;
        b.onclick = () => submitOne(conflictKey, { type }, type === "custom");
        row.appendChild(b);
    }
    body.appendChild(row);
}

async function submitOne(conflictKey, resolution, needsCustom) {
    if (needsCustom) {
        const text = await askCustom();
        if (text == null) return;
        resolution = { type: "custom", text, format: "yaml" };
    }
    await submitResolutions([{ conflictKey, resolution }]);
}

async function submitResolutions(items) {
    try {
        const resp = await api("/api/resolve", { version: state.pendingVersion, items });
        if (resp.rejectedStale.length) {
            toast("部分冲突已被其他会话解决，未覆盖: " + resp.rejectedStale.join(", "), true);
        } else {
            toast("已提交 " + resp.accepted.length + " 项裁决");
        }
        loadState(resp.state);
    } catch (e) {
        if (e.status === 409) {
            toast("乐观版本过期（当前 v" + e.payload.currentVersion + "），已刷新他人已解决的内容", true);
            return refresh();
        }
        toast(e.message, true);
    }
}

function buildOrderResolver(body, conflictKey) {
    const conflict = state.data.conflicts.find(c => c.key === conflictKey);
    const row = document.createElement("div");
    row.className = "resolve-row";
    const takeA = document.createElement("button");
    takeA.textContent = "采用 A 的顺序";
    takeA.onclick = () => orderFromSide(conflictKey, "branchA");
    const takeB = document.createElement("button");
    takeB.textContent = "采用 B 的顺序";
    takeB.onclick = () => orderFromSide(conflictKey, "branchB");
    const custom = document.createElement("button");
    custom.textContent = "手动排序…";
    custom.onclick = () => customOrder(conflictKey);
    row.append(takeA, takeB, custom);
    body.appendChild(row);
    if (conflict && conflict.status === "resolved") body.appendChild(small("顺序冲突已裁决: " + conflict.priorResolution));
}

async function orderFromSide(conflictKey, side) {
    const listNode = findListByPath(state.data.merged.tree, pathOfConflict(conflictKey));
    const ids = collectIdsFromInput(side, pathOfConflict(conflictKey));
    await submitResolutions([{ conflictKey, resolution: { type: "customOrder", ids } }]);
}

function pathOfConflict(key) {
    return key.split("#")[0];
}

function findListByPath(node, path) {
    if (node.path === path && node.kind === "list") return node;
    for (const k of mergedKidList(node)) {
        const found = findListByPath(k.node, path);
        if (found) return found;
    }
    return null;
}

function collectIdsFromInput(side, path) {
    const root = state.data.inputs[side].tree;
    let cur = root;
    const parts = path.replace(/^\$\.?/, "").match(/[^.\[\]]+(\[\d+\])?/g) || [];
    for (const part of parts) {
        const m = part.match(/^([^\[]+)(?:\[(\d+)\])?$/);
        const key = m ? m[1] : part;
        const child = (cur.children || []).find(c => String(c.key) === key);
        if (!child) return [];
        cur = child.node;
        if (m && m[2] !== undefined) {
            const item = cur.children[Number(m[2])];
            cur = item.node;
        }
    }
    return (cur.children || []).map(c => {
        const idNode = (c.node.children || []).find(x => x.key === "id");
        return idNode ? idNode.node.value : null;
    }).filter(Boolean);
}

async function customOrder(conflictKey) {
    const listNode = findListByPath(state.data.merged.tree, pathOfConflict(conflictKey));
    const ids = (listNode.children || []).map(c => c.id).filter(id => !id.startsWith("#"));
    const order = [...ids];
    const wrapper = document.createElement("div");
    wrapper.className = "order-editor";
    function draw() {
        wrapper.innerHTML = "";
        order.forEach((id, idx) => {
            const row = document.createElement("div");
            row.className = "order-item";
            const label = document.createElement("span");
            label.textContent = id;
            label.style.flex = "1";
            const up = document.createElement("button");
            up.textContent = "↑";
            up.onclick = () => { if (idx > 0) { [order[idx - 1], order[idx]] = [order[idx], order[idx - 1]]; draw(); } };
            const down = document.createElement("button");
            down.textContent = "↓";
            down.onclick = () => { if (idx < order.length - 1) { [order[idx + 1], order[idx]] = [order[idx], order[idx + 1]]; draw(); } };
            row.append(label, up, down);
            wrapper.appendChild(row);
        });
    }
    draw();
    const ok = await openModal("自定义 id 顺序", wrapper, null);
    if (ok) await submitResolutions([{ conflictKey, resolution: { type: "customOrder", ids: order } }]);
}

function buildPolicyBox(body, node) {
    const h = document.createElement("h4");
    h.textContent = "数组策略（未登记时系统不猜测）";
    body.appendChild(h);
    const existing = state.data.policies.find(p => p.path === node.path);
    body.appendChild(kvHtml("当前策略", existing ? existing.label : "未登记"));
    const row = document.createElement("div");
    row.className = "resolve-row";
    const idFieldInput = document.createElement("input");
    idFieldInput.type = "text";
    idFieldInput.value = existing ? existing.idField : "id";
    idFieldInput.placeholder = "稳定 id 字段";
    idFieldInput.style.width = "110px";
    for (const st of ["replace", "id", "sequence"]) {
        const b = document.createElement("button");
        b.textContent = { replace: "替换", id: "按 id 合并", sequence: "有序序列" }[st];
        b.onclick = async () => {
            try {
                const resp = await api("/api/policies", {
                    version: state.pendingVersion,
                    path: node.path, strategy: st, idField: idFieldInput.value || "id",
                });
                loadState(resp);
                toast("策略已登记，新版本 v" + resp.version);
            } catch (e) { handleErr(e); }
        };
        row.appendChild(b);
    }
    const rm = document.createElement("button");
    rm.textContent = "删除策略";
    rm.className = "danger";
    rm.onclick = async () => {
        try {
            const resp = await api("/api/policies/remove", {
                version: state.pendingVersion, path: node.path,
            });
            loadState(resp);
        } catch (e) { handleErr(e); }
    };
    row.append(idFieldInput, rm);
    body.appendChild(row);
}

/* ---------- modal ---------- */

let modalResolver = null;
function openModal(title, contentNode, setup) {
    return new Promise(resolve => {
        document.getElementById("modal-title").textContent = title;
        const body = document.getElementById("modal-body");
        body.innerHTML = "";
        body.appendChild(contentNode);
        document.getElementById("modal-error").textContent = "";
        document.getElementById("modal").classList.remove("hidden");
        modalResolver = resolve;
        if (setup) setup(body);
    });
}
function closeModal(value) {
    document.getElementById("modal").classList.add("hidden");
    const r = modalResolver;
    modalResolver = null;
    if (r) r(value);
}
document.getElementById("modal-ok").onclick = () => closeModal(true);
document.getElementById("modal-cancel").onclick = () => closeModal(false);
document.getElementById("modal-close").onclick = () => closeModal(false);
document.getElementById("detail-close").onclick = () =>
    document.getElementById("detail").classList.add("hidden");

function askCustom() {
    const ta = document.createElement("textarea");
    ta.placeholder = "输入 YAML 或 JSON 标量/结构（空映射表示 null 请用 null）";
    ta.value = "null";
    return new Promise(resolve => {
        openModal("自定义合并值", ta, () => ta.focus());
        const ok = document.getElementById("modal-ok");
        const handler = async () => {
            try {
                const probe = ta.value.trim().startsWith("{") || ta.value.trim().startsWith("[")
                    || ta.value.trim().startsWith("\"");
                const format = probe ? "json" : "yaml";
                await api("/api/remerge");
                document.getElementById("modal-error").textContent = "";
                ok.removeEventListener("click", handler);
                closeModal(null);
                resolve({ text: ta.value, format });
            } catch (e) {
                document.getElementById("modal-error").textContent = e.message;
            }
        };
        ok.addEventListener("click", handler, { once: true });
    });
}

/* ---------- input editing ---------- */

async function editInput(side) {
    const sideKey = side === "base" ? "base" : (state.branchTab === "A" ? "branchA" : "branchB");
    const input = state.data.inputs[sideKey];
    const wrap = document.createElement("div");
    const fmtLabel = document.createElement("div");
    fmtLabel.className = "small";
    fmtLabel.textContent = "格式（改动原文会使相关旧裁决变成建议）";
    const fmt = document.createElement("select");
    fmt.innerHTML = `<option value="yaml">YAML</option><option value="json">JSON</option>`;
    fmt.value = input.format;
    const ta = document.createElement("textarea");
    ta.value = input.text;
    wrap.append(fmtLabel, fmt, ta);
    const ok = await openModal("编辑原文 · " + sideKey, wrap, () => ta.focus());
    if (!ok) return;
    try {
        const resp = await api("/api/inputs", {
            version: state.pendingVersion, side: sideKey,
            format: fmt.value, text: ta.value,
        });
        loadState(resp);
        toast("原文已更新并重新合并");
    } catch (e) { handleErr(e); }
}

/* ---------- export / import / preview ---------- */

async function doExport() {
    try {
        const format = document.getElementById("export-format").value;
        const bundle = await api("/api/export", { format });
        if (bundle.stats && bundle.stats.conflictCount > bundle.stats.appliedCount) {
            if (!confirm("仍有未解决冲突，导出将被服务器拒绝。仍要查看当前状态吗？")) return;
        }
        const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: "application/json" });
        const a = document.createElement("a");
        a.href = URL.createObjectURL(blob);
        a.download = "semcfg-export.json";
        a.click();
        toast("已导出（同时持久化到 outputs/）");
    } catch (e) { handleErr(e); }
}

async function doImport(file) {
    const text = await file.text();
    let bundle;
    try { bundle = JSON.parse(text); } catch (e) { toast("导入包不是合法 JSON", true); return; }
    try {
        const resp = await api("/api/import", { bundle });
        loadState(resp.state);
        const ok = resp.verification.ok;
        toast(ok ? "导入成功：路径、来源链、结果指纹一致" :
            "导入完成但校验有差异: " + resp.verification.errors.join("; "), !ok);
    } catch (e) { handleErr(e); }
}

async function preview() {
    const format = document.getElementById("export-format").value;
    const pre = document.createElement("pre");
    pre.className = "preview";
    pre.textContent = format === "json"
        ? state.data.merged.outputPreviewJson || state.data.merged.outputPreviewYaml
        : state.data.merged.outputPreviewYaml;
    const ok = await openModal("导出文本预览（稳定序列化）", pre, null);
}

/* ---------- demo data ---------- */

const DEMO_BASE = `database:
  host: db.internal
  port: 5432
  pool: 10
features:
  - id: cache
    enabled: true
    ttl: 60
  - id: metrics
    enabled: false
region: cn
logging:
  level: info
`;

const DEMO_A = `database:
  host: db-a.internal
  port: 5432
  pool: 20
features:
  - id: metrics
    enabled: true
  - id: cache
    enabled: true
    ttl: 60
region: cn
logging:
  level: debug
`;

const DEMO_B = `database:
  host: db.internal
  port: 5433
  pool: 10
  timeout: 30
features:
  - id: cache
    enabled: true
    ttl: 90
  - id: metrics
    enabled: false
  - id: tracing
    enabled: true
region: null
`;

async function loadDemo() {
    const sides = [
        ["base", "yaml", DEMO_BASE],
        ["branchA", "yaml", DEMO_A],
        ["branchB", "yaml", DEMO_B],
    ];
    try {
        let resp;
        for (const [side, format, text] of sides) {
            resp = await api("/api/inputs", {
                version: state.pendingVersion, side, format, text,
            });
            loadState(resp);
        }
        const withPolicy = await api("/api/policies", {
            version: state.pendingVersion, path: "$.features",
            strategy: "id", idField: "id",
        });
        loadState(withPolicy);
        toast("示例已载入：含 id 数组合并、删除 vs null、同值冲突、顺序移动");
    } catch (e) { handleErr(e); }
}

/* ---------- helpers / wiring ---------- */

function handleErr(e) {
    if (e.status === 409) {
        toast("版本冲突：页面已过期，服务器保留了别人已解决的项；正在刷新…", true);
        return refresh();
    }
    toast(e.message, true);
}

async function refresh() {
    const json = await api("/api/state");
    loadState(json);
}

document.getElementById("tab-a").onclick = () => { state.branchTab = "A"; render(); };
document.getElementById("tab-b").onclick = () => { state.branchTab = "B"; render(); };
document.querySelectorAll(".btn-edit").forEach(b =>
    b.onclick = () => editInput(b.dataset.side));
document.getElementById("btn-edit-branch").onclick = () => editInput("branch");
document.getElementById("btn-demo").onclick = loadDemo;
document.getElementById("btn-export").onclick = doExport;
document.getElementById("btn-preview").onclick = preview;
document.getElementById("btn-reset").onclick = async () => {
    if (!confirm("确定清空所有输入、策略和裁决历史？")) return;
    loadState(await api("/api/reset"));
};
document.getElementById("import-file").onchange = (e) => {
    const f = e.target.files[0];
    if (f) doImport(f);
    e.target.value = "";
};

refresh();

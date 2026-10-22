// The AutoEQ page's try-out: pick a headphone from /data/headphones.json
// (built by tools/headphone_index/build.py from AutoEq and squig.link), or
// load a file, and fit it with the app's engine (js/autoeq-engine.js) in the
// browser. Measurements are fetched from where they were published; the
// targets are the app's own (/data/targets).
(() => {
  "use strict";
  const E = window.TryptifyAutoEq;
  const box = document.getElementById("tryout");
  if (!E || !box) return;
  const M = window.Motion;
  const moving = document.documentElement.classList.contains("motion") && !!M;
  const $ = (id) => document.getElementById(id);

  const TARGETS = [
    ["Harman_OE_2018", "Harman over-ear 2018", "over-ear"],
    ["Harman_IE_2019", "Harman in-ear 2019", "in-ear"],
    ["SEAP", "SEAP target", null],
    ["SEAP_Bass", "SEAP bass boost", null],
    ["Diffuse_Field", "Diffuse field", null],
    ["Knowles", "Knowles", null],
    ["Moondrop_VDSF", "Moondrop VDSF", null],
    ["HiFiEndgame_2026", "HiFi Endgame 2026", null],
    ["HiFiEndgame_MKIII", "HiFi Endgame MKIII", null],
    ["PEQdB_Ultra", "PEQdB Ultra", null],
    ["PEQdB_Diamond", "PEQdB Diamond", null],
    ["Flat_Line", "Flat (calibration)", null],
  ];
  const targetCache = new Map();
  let index = null, indexing = null;
  let phone = null;        // { n, t, m } from the index, or { n, own: points }
  let measurement = null;  // parsed points of the current measurement
  let lastFit = null;

  const say = (text, bad) => { const m = $("try-msg"); m.textContent = text; m.classList.toggle("bad", !!bad); };

  // ── Targets ─────────────────────────────────────────────────────────
  const tSel = $("hp-target");
  for (const [id, name] of TARGETS) {
    const o = document.createElement("option");
    o.value = id; o.textContent = name;
    tSel.append(o);
  }
  async function target(id) {
    if (!targetCache.has(id)) {
      const r = await fetch(`/data/targets/${id}.txt`);
      if (!r.ok) throw new Error("The target could not load.");
      targetCache.set(id, E.parse(await r.text()));
    }
    return targetCache.get(id);
  }

  // ── Search ──────────────────────────────────────────────────────────
  const input = $("hp-search");
  const results = $("hp-results");
  const loadIndex = () => indexing || (indexing = fetch("/data/headphones.json")
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
    .then((d) => {
      index = d;
      for (const p of d.phones) p.k = p.n.toLowerCase().replace(/[^a-z0-9]+/g, " ");
      return d;
    }));
  input.addEventListener("focus", () => loadIndex().catch(() => say("The headphone list could not load.", true)), { once: true });

  let active = -1, shown = [];
  const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  function search() {
    const q = norm(input.value);
    if (!q || !index) { results.hidden = true; return; }
    const words = q.split(" ");
    shown = [];
    for (const p of index.phones) {
      if (words.every((w) => p.k.includes(w))) {
        // Names that start with the query first, then shorter names.
        const score = (p.k.startsWith(q) ? 0 : 1) * 1000 + p.n.length;
        shown.push([score, p]);
      }
    }
    shown.sort((a, b) => a[0] - b[0]);
    shown = shown.slice(0, 40).map((x) => x[1]);
    results.textContent = "";
    active = -1;
    if (!shown.length) {
      const li = document.createElement("li");
      li.className = "muted";
      li.textContent = "No headphone matches that.";
      results.append(li);
    }
    shown.forEach((p, i) => {
      const li = document.createElement("li");
      li.setAttribute("role", "option");
      li.dataset.i = String(i);
      const n = document.createElement("strong");
      n.textContent = p.n;
      const meta = document.createElement("span");
      meta.className = "muted small";
      meta.textContent = `${p.t} · ${p.m.length} measurement${p.m.length === 1 ? "" : "s"}`;
      li.append(n, meta);
      li.addEventListener("mousedown", (e) => { e.preventDefault(); choose(p); });
      results.append(li);
    });
    results.hidden = false;
  }
  input.addEventListener("input", () => { if (index) search(); else loadIndex().then(search); });
  input.addEventListener("keydown", (e) => {
    const items = [...results.querySelectorAll("li[role=option]")];
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      active = Math.max(0, Math.min(items.length - 1, active + (e.key === "ArrowDown" ? 1 : -1)));
      items.forEach((li, i) => li.classList.toggle("on", i === active));
      items[active]?.scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter" && shown.length) {
      e.preventDefault();
      choose(shown[Math.max(0, active)]);
    } else if (e.key === "Escape") results.hidden = true;
  });
  input.addEventListener("blur", () => setTimeout(() => { results.hidden = true; }, 120));

  function choose(p, source = 0) {
    phone = p;
    input.value = p.n;
    results.hidden = true;
    const sel = $("hp-source");
    sel.textContent = "";
    p.m.forEach(([src, file], i) => {
      const s = index.sources[src];
      const o = document.createElement("option");
      o.value = String(i);
      o.textContent = s.target === "squig.link" ? `${s.label} (squig.link)` : `${s.label} · ${s.target}`;
      sel.append(o);
    });
    sel.value = String(source < p.m.length ? source : 0);
    // Harman for the headphone's kind, as the app suggests.
    const def = p.t === "in-ear" || p.t === "earbud" ? "Harman_IE_2019" : "Harman_OE_2018";
    if (!tSel.dataset.touched) tSel.value = def;
    loadMeasurement();
  }

  async function loadMeasurement() {
    if (!phone || phone.own) return fit();
    const [src, file] = phone.m[Number($("hp-source").value) || 0];
    const s = index.sources[src];
    say(`Loading ${phone.n}…`);
    measurement = null;
    try {
      let text = null;
      if (s.target === "squig.link") {
        // Left ear first, as the app shows it; then the other files it tries.
        for (const ch of ["L", "L1", "R", "R1"]) {
          const r = await fetch(s.base + encodeURIComponent(`${file} ${ch}.txt`));
          if (r.ok) { text = await r.text(); break; }
        }
      } else {
        const r = await fetch(s.base + file.split("/").map(encodeURIComponent).join("/"));
        if (r.ok) text = await r.text();
      }
      if (!text) throw new Error("not found");
      measurement = E.parse(text);
      if (measurement.length < 20) throw new Error("too few points");
      fit();
    } catch (_) {
      say("That measurement could not be loaded. Try another one from the list.", true);
    }
  }

  $("hp-file").addEventListener("change", async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    const pts = E.parse(await f.text());
    e.target.value = "";
    if (pts.length < 20) return say("That file has no frequency and dB columns this can read.", true);
    phone = { n: f.name.replace(/\.[^.]+$/, ""), own: true };
    measurement = pts;
    input.value = phone.n;
    $("hp-source").innerHTML = "<option>Your file</option>";
    fit();
  });

  // ── Fitting ─────────────────────────────────────────────────────────
  ["hp-target", "hp-bands", "hp-max", "hp-alg", "hp-smooth"].forEach((id) =>
    $(id).addEventListener("change", () => { if (id === "hp-target") tSel.dataset.touched = "1"; fit(); }));
  $("hp-source").addEventListener("change", loadMeasurement);

  const rmse = (meas, offset, eq, tgt, lo = 20, hi = 16000) => {
    let s = 0, n = 0;
    meas.forEach((p, i) => {
      if (p.freq < lo || p.freq > hi) return;
      const d = p.gain + offset + (eq ? eq[i] : 0) - E.interpolate(p.freq, tgt);
      s += d * d; n++;
    });
    return n ? Math.sqrt(s / n) : 0;
  };

  async function fit() {
    if (!measurement) return;
    let tgt;
    try { tgt = await target(tSel.value); } catch (e) { return say(e.message, true); }
    const smoothed = E.smoothCurve(measurement, Number($("hp-smooth").value));
    const opts = { bandCount: Number($("hp-bands").value), maxFrequency: Number($("hp-max").value), algorithm: $("hp-alg").value };
    const t0 = performance.now();
    const bands = E.run(smoothed, tgt, opts);
    const ms = Math.round(performance.now() - t0);
    const preamp = E.peakBoostDb(bands);
    const offset = E.normalizationOffset(tgt) - E.normalizationOffset(smoothed);
    const eqAt = E.eqCurve(bands, smoothed.map((p) => p.freq));
    lastFit = { bands, preamp, name: phone.n, target: tSel.options[tSel.selectedIndex].text, opts };
    const before = rmse(smoothed, offset, null, tgt), after = rmse(smoothed, offset, eqAt, tgt);
    $("try-result").hidden = false;
    say(`${phone.n} → ${lastFit.target}: ${bands.length} bands, fitted in ${ms} ms.`);
    const stats = $("try-stats");
    stats.innerHTML = "";
    for (const [label, value, note] of [
      ["Fit error, no EQ", `${before.toFixed(2)} dB`, "20 Hz–16 kHz"],
      ["Fit error, with EQ", `${after.toFixed(2)} dB`, "lower is closer"],
      ["Preamp", `−${preamp.toFixed(1)} dB`, "headroom kept"],
    ]) {
      const d = document.createElement("div");
      d.innerHTML = `<span class="muted small"></span><strong></strong><span class="muted small"></span>`;
      d.children[0].textContent = label; d.children[1].textContent = value; d.children[2].textContent = note;
      stats.append(d);
    }
    draw(smoothed, offset, eqAt, tgt);
    table(bands);
    // The web player (/player) plays each fit and remembers how to make it again.
    box.dispatchEvent(new CustomEvent("tryptify:fit", { bubbles: true, detail: {
      ...lastFit, phone: phone.own ? null : phone.n, source: Number($("hp-source").value) || 0,
      targetId: tSel.value, smooth: Number($("hp-smooth").value),
    } }));
  }

  // ── Drawing ─────────────────────────────────────────────────────────
  const NS = "http://www.w3.org/2000/svg";
  const W = 1000, H = 420, P = { l: 44, r: 14, t: 14, b: 30 };
  const node = (tag, attrs, parent) => {
    const e = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    parent && parent.append(e);
    return e;
  };
  function draw(meas, offset, eqAt, tgt) {
    const host = $("try-graph");
    host.textContent = "";
    const svg = node("svg", { viewBox: `0 0 ${W} ${H}`, class: "graph", role: "img", "aria-label": "Measurement, target and result" }, host);
    const lx0 = Math.log10(20), lx1 = Math.log10(20000);
    const x = (f) => P.l + ((Math.log10(f) - lx0) / (lx1 - lx0)) * (W - P.l - P.r);
    const raw = meas.map((p) => p.gain + offset);
    const after = raw.map((g, i) => g + eqAt[i]);
    const tg = meas.map((p) => E.interpolate(p.freq, tgt));
    const inRange = (arr) => arr.filter((_, i) => meas[i].freq >= 20 && meas[i].freq <= 20000);
    const all = [...inRange(raw), ...inRange(after), ...inRange(tg)];
    const step = 5;
    let lo = Math.floor((Math.min(...all) - 2) / step) * step, hi = Math.ceil((Math.max(...all) + 2) / step) * step;
    // The EQ curve is drawn around the bottom of the graph, on its own scale.
    const eqLo = Math.min(-6, ...eqAt), eqHi = Math.max(6, ...eqAt);
    const y = (db) => P.t + ((hi - db) / (hi - lo)) * (H - P.t - P.b);
    const yEq = (db) => H - P.b - 6 - ((db - eqLo) / (eqHi - eqLo)) * (H - P.t - P.b) * 0.28;
    for (let v = lo; v <= hi; v += step) {
      node("line", { x1: P.l, x2: W - P.r, y1: y(v), y2: y(v), class: "g-h" }, svg);
      node("text", { x: P.l - 8, y: y(v) + 4, class: "g-tick", "text-anchor": "end" }, svg).textContent = String(v);
    }
    for (const f of [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000]) {
      node("line", { x1: x(f), x2: x(f), y1: P.t, y2: H - P.b, class: "g-v major" }, svg);
      node("text", { x: x(f), y: H - P.b + 18, class: "g-tick", "text-anchor": "middle" }, svg).textContent = f >= 1000 ? `${f / 1000}k` : String(f);
    }
    const path = (vals, sy) => meas.map((p, i) => (p.freq < 20 || p.freq > 20000 ? null : `${x(p.freq).toFixed(1)},${sy(vals[i]).toFixed(1)}`))
      .filter(Boolean).map((pt, i) => (i ? "L" : "M") + pt).join("");
    node("line", { x1: P.l, x2: W - P.r, y1: yEq(0), y2: yEq(0), class: "g-zero" }, svg);
    const lines = [
      [path(eqAt, yEq), "s-sq"],
      [path(tg, y), "s-target"],
      [path(raw, y), "s-raw"],
      [path(after, y), "s-ts"],
    ];
    for (const [d, cls] of lines) {
      const p = node("path", { d, class: `g-line ${cls}` }, svg);
      if (moving) M.animate(p, { pathLength: [0, 1] }, { duration: 1, ease: [0.22, 1, 0.36, 1] });
    }
  }

  function table(bands) {
    const body = $("try-table").querySelector("tbody");
    body.textContent = "";
    const names = { PEAKING: "Peak", LOWSHELF: "Low shelf", HIGHSHELF: "High shelf" };
    bands.forEach((b, i) => {
      const tr = document.createElement("tr");
      for (const v of [i + 1, names[b.type], b.freq >= 1000 ? `${(b.freq / 1000).toFixed(2)} kHz` : `${Math.round(b.freq)} Hz`,
        `${b.gain > 0 ? "+" : ""}${b.gain.toFixed(1)} dB`, b.q.toFixed(2)]) {
        const td = document.createElement("td");
        td.textContent = String(v);
        tr.append(td);
      }
      body.append(tr);
    });
  }

  // ── Restoring (the web player, after a reload) ───────────────────────
  window.TryptifyTryout = {
    /** Picks [saved.phone] again with the settings it was fitted with. */
    async restore(saved) {
      if (!saved || !saved.phone) return;
      const o = saved.opts || {};
      for (const [id, v] of [["hp-bands", o.bandCount], ["hp-max", o.maxFrequency], ["hp-alg", o.algorithm], ["hp-smooth", saved.smooth]])
        if (v != null) $(id).value = String(v);
      if (saved.targetId) { tSel.value = saved.targetId; tSel.dataset.touched = "1"; }
      await loadIndex();
      const p = index.phones.find((x) => x.n === saved.phone);
      if (p) { input.value = p.n; choose(p, saved.source || 0); }
    },
  };

  // ── Export ──────────────────────────────────────────────────────────
  function apo() {
    const f = lastFit;
    const code = { PEAKING: "PK", LOWSHELF: "LSC", HIGHSHELF: "HSC" };
    return [
      `# ${f.name} → ${f.target}, ${f.bands.length} bands up to ${f.opts.maxFrequency / 1000} kHz (${f.opts.algorithm === "SHELF_ENDS" ? "shelf ends" : "peaking"})`,
      "# Tryptify AutoEQ · tryptify.com/autoeq",
      `Preamp: -${f.preamp.toFixed(1)} dB`,
      ...f.bands.map((b, i) => `Filter ${i + 1}: ON ${code[b.type]} Fc ${Math.round(b.freq)} Hz Gain ${b.gain.toFixed(1)} dB Q ${b.q.toFixed(2)}`),
    ].join("\n") + "\n";
  }
  $("try-copy").addEventListener("click", async (e) => {
    if (!lastFit) return;
    try {
      await navigator.clipboard.writeText(apo());
      e.target.textContent = "Copied";
      setTimeout(() => { e.target.textContent = "Copy for Equalizer APO"; }, 1500);
    } catch (_) { say("Copying was blocked; use Download instead.", true); }
  });
  $("try-download").addEventListener("click", () => {
    if (!lastFit) return;
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([apo()], { type: "text/plain" }));
    a.download = `${lastFit.name} ParametricEQ.txt`.replace(/[\\/:*?"<>|]/g, "_");
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  });
})();

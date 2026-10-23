// The AutoEQ page's two graphs, from SeapEngine's bench curves
// (/data/autoeq-curves.json, loaded when the graphs come near the screen):
// the frequency response after each engine's EQ against the target, and the
// EQ curves themselves (no preamp). They follow the ranking's frequency limit
// and band count (js/autoeq.js sends "rank-change"); "Avg" shows the 10-band
// fits, as SeapEngine does. A legend toggles curves; hovering reads values.
(() => {
  "use strict";
  const root = document.getElementById("graphs");
  if (!root) return;
  const M = window.Motion;
  const moving = document.documentElement.classList.contains("motion") && !!M;
  const NS = "http://www.w3.org/2000/svg";
  const W = 1000, PAD = { l: 44, r: 14, t: 14, b: 30 };

  const SERIES = [
    { key: "target", name: T("SEAP target"), cls: "s-target", on: true },
    { key: "raw", name: T("Truthear Nova, no EQ"), cls: "s-raw", on: false, fr: true },
    { key: "ts", name: T("Tryptify Shelf ends"), cls: "s-ts", on: true },
    { key: "tp", name: T("Tryptify Peaking"), cls: "s-tp", on: true },
    { key: "ap", name: T("AutoEq peaking"), cls: "s-ap", on: true },
    { key: "as", name: T("AutoEq shelves"), cls: "s-as", on: true },
    { key: "sq", name: "squig.link (PEQdB)", cls: "s-sq", on: true },
  ];
  const TICKS = [20, 30, 40, 50, 60, 80, 100, 150, 200, 300, 400, 500, 800, 1000, 1500, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 20000];
  const MAJOR = new Set([20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000]);
  const fmtHz = (f) => (f >= 1000 ? `${+(f / 1000).toFixed(f % 1000 ? 1 : 0)}k` : `${f}`);

  let data = null;
  let view = { limit: "default", bands: "avg" };
  const shown = Object.fromEntries(SERIES.map((s) => [s.key, s.on]));
  const charts = [];

  const el = (tag, attrs = {}, parent) => {
    const e = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    if (parent) parent.append(e);
    return e;
  };

  function makeChart(box, kind, height) {
    const svg = el("svg", { viewBox: `0 0 ${W} ${height}`, class: "graph", role: "img", "aria-label": kind === "fr" ? T("Frequency response graph") : T("EQ curves graph") });
    box.append(svg);
    const grid = el("g", { class: "g-grid" }, svg);
    const lines = el("g", { class: "g-lines" }, svg);
    const hover = el("g", { class: "g-hover", visibility: "hidden" }, svg);
    el("line", { class: "g-cursor", y1: PAD.t, y2: height - PAD.b }, hover);
    const tip = document.createElement("div");
    tip.className = "g-tip";
    tip.hidden = true;
    box.append(tip);
    const paths = {};
    for (const s of SERIES) {
      if (kind === "eq" && (s.key === "target" || s.key === "raw")) continue;
      paths[s.key] = el("path", { class: `g-line ${s.cls}` }, lines);
    }
    const chart = { kind, svg, grid, paths, hover, tip, box, height, x: null, y: null, curves: {} };
    svg.addEventListener("pointermove", (e) => readAt(chart, e));
    svg.addEventListener("pointerleave", () => { chart.hover.setAttribute("visibility", "hidden"); chart.tip.hidden = true; });
    charts.push(chart);
    return chart;
  }

  function curvesFor(kind) {
    const lim = data.limits[view.limit] || data.limits.default;
    const bands = view.bands === "avg" ? "10" : view.bands;
    const src = kind === "fr" ? lim.after[bands] : lim.eq[bands];
    const out = {};
    if (kind === "fr") { out.target = data.targetCurve; out.raw = data.raw; }
    for (const k of ["ts", "tp", "ap", "as", "sq"]) if (src && src[k]) out[k] = src[k];
    return out;
  }

  function draw(chart, first) {
    const curves = curvesFor(chart.kind);
    chart.curves = curves;
    const f = data.f;
    const lx0 = Math.log10(20), lx1 = Math.log10(20000);
    const x = (hz) => PAD.l + ((Math.log10(hz) - lx0) / (lx1 - lx0)) * (W - PAD.l - PAD.r);
    let lo = Infinity, hi = -Infinity;
    for (const [k, c] of Object.entries(curves)) {
      if (!shown[k]) continue;
      for (let i = 0; i < c.length; i++) if (f[i] <= 20000) { lo = Math.min(lo, c[i]); hi = Math.max(hi, c[i]); }
    }
    if (!Number.isFinite(lo)) { lo = 0; hi = 10; }
    const step = 5;
    lo = Math.floor((lo - 1) / step) * step;
    hi = Math.ceil((hi + 1) / step) * step;
    const y = (db) => PAD.t + ((hi - db) / (hi - lo)) * (chart.height - PAD.t - PAD.b);
    chart.x = x; chart.y = y;

    // Grid and axes, redrawn each time (cheap).
    chart.grid.textContent = "";
    for (let v = lo; v <= hi; v += step) {
      el("line", { x1: PAD.l, x2: W - PAD.r, y1: y(v), y2: y(v), class: v === 0 && chart.kind === "eq" ? "g-zero" : "g-h" }, chart.grid);
      const t = el("text", { x: PAD.l - 8, y: y(v) + 4, class: "g-tick", "text-anchor": "end" }, chart.grid);
      t.textContent = String(v);
    }
    const narrow = chart.box.clientWidth < 560;
    for (const hz of TICKS) {
      el("line", { x1: x(hz), x2: x(hz), y1: PAD.t, y2: chart.height - PAD.b, class: MAJOR.has(hz) ? "g-v major" : "g-v" }, chart.grid);
      if (narrow && !MAJOR.has(hz)) continue;
      const t = el("text", { x: x(hz), y: chart.height - PAD.b + 18, class: "g-tick", "text-anchor": "middle" }, chart.grid);
      t.textContent = hz === 20 ? "20Hz" : hz === 20000 ? "20kHz" : fmtHz(hz);
    }

    for (const [k, p] of Object.entries(chart.paths)) {
      const c = curves[k];
      const visible = !!c && shown[k];
      p.style.display = visible ? "" : "none";
      if (!c) continue;
      let d = "";
      for (let i = 0; i < c.length; i++) d += `${i ? "L" : "M"}${x(f[i]).toFixed(1)},${y(c[i]).toFixed(1)}`;
      const was = p.getAttribute("d");
      if (moving && was && !first && visible) M.animate(p, { d: [was, d] }, { duration: 0.6, ease: [0.22, 1, 0.36, 1] }).then(() => p.setAttribute("d", d));
      else p.setAttribute("d", d);
      if (moving && first && visible) M.animate(p, { pathLength: [0, 1] }, { duration: 1.4, ease: [0.22, 1, 0.36, 1], delay: 0.1 });
    }
  }

  function readAt(chart, e) {
    if (!chart.x) return;
    const r = chart.svg.getBoundingClientRect();
    const sx = ((e.clientX - r.left) / r.width) * W;
    if (sx < PAD.l || sx > W - PAD.r) return;
    // The nearest frequency point.
    const f = data.f;
    let best = 0;
    for (let i = 1; i < f.length; i++) if (Math.abs(chart.x(f[i]) - sx) < Math.abs(chart.x(f[best]) - sx)) best = i;
    const cx = chart.x(f[best]);
    const cursor = chart.hover.querySelector(".g-cursor");
    cursor.setAttribute("x1", cx); cursor.setAttribute("x2", cx);
    chart.hover.setAttribute("visibility", "visible");
    const rows = SERIES.filter((s) => shown[s.key] && chart.curves[s.key])
      .map((s) => `<span class="${s.cls}"><i></i>${s.name}</span><b>${chart.curves[s.key][best].toFixed(1)} dB</b>`);
    chart.tip.innerHTML = `<strong>${f[best] >= 1000 ? (f[best] / 1000).toFixed(2) + " kHz" : Math.round(f[best]) + " Hz"}</strong>${rows.join("")}`;
    chart.tip.hidden = false;
    const left = (cx / W) * r.width;
    chart.tip.style.left = `${Math.min(Math.max(8, left + 14), r.width - chart.tip.offsetWidth - 8)}px`;
  }

  function legend() {
    const box = document.getElementById("graph-legend");
    box.textContent = "";
    for (const s of SERIES) {
      const b = document.createElement("button");
      b.type = "button";
      b.className = `g-key ${s.cls}`;
      b.setAttribute("aria-pressed", String(shown[s.key]));
      b.innerHTML = "<i></i>";
      b.append(s.name);
      b.addEventListener("click", () => {
        shown[s.key] = !shown[s.key];
        b.setAttribute("aria-pressed", String(shown[s.key]));
        charts.forEach((c) => draw(c, false));
      });
      box.append(b);
    }
  }

  function caption() {
    const n = view.bands === "avg" ? "10" : view.bands;
    const lim = view.limit === "default" ? T("app defaults") : T("up to {khz} kHz", { khz: +view.limit / 1000 });
    document.getElementById("graph-caption").textContent = view.bands === "avg"
      ? T("{bands} bands · {limit} (the Avg view shows the 10-band fits)", { bands: n, limit: lim })
      : T("{bands} bands · {limit}", { bands: n, limit: lim });
  }

  document.addEventListener("rank-change", (e) => {
    view = { limit: e.detail.limit, bands: e.detail.bands };
    if (!data) return;
    caption();
    charts.forEach((c) => draw(c, false));
  });

  let started = false;
  const start = () => {
    if (started) return;
    started = true;
    fetch("/data/autoeq-curves.json")
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((d) => {
        data = d;
        legend();
        caption();
        makeChart(document.getElementById("graph-fr"), "fr", 440);
        makeChart(document.getElementById("graph-eq"), "eq", 320);
        charts.forEach((c) => draw(c, true));
        let t;
        addEventListener("resize", () => { clearTimeout(t); t = setTimeout(() => charts.forEach((c) => draw(c, false)), 150); });
      })
      .catch(() => { document.getElementById("graph-caption").textContent = T("The graphs could not load."); });
  };
  if ("IntersectionObserver" in window) {
    const io = new IntersectionObserver((en) => { if (en.some((x) => x.isIntersecting)) { io.disconnect(); start(); } }, { rootMargin: "400px" });
    io.observe(root);
  } else start();
})();

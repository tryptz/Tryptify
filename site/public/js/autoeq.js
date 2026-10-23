// The AutoEQ ranking: SeapEngine's bench results (a snapshot in
// /data/autoeq-bench.json), ranked here by whichever measure is picked.
// "Avg" is the plain mean over the band counts, as SeapEngine shows it.
(() => {
  "use strict";
  const M = window.Motion;
  const moving = document.documentElement.classList.contains("motion") && !!M;
  const list = document.getElementById("rank-list");
  if (!list) return;

  const ENGINES = {
    ts: [T("Tryptify Shelf ends"), "Tryptify"],
    tp: [T("Tryptify Peaking"), "Tryptify"],
    ap: [T("AutoEq peaking"), "autoeq.app"],
    as: [T("AutoEq shelves"), "autoeq.app"],
    sq: ["squig.link (PEQdB)", "squig.link"],
  };
  // name, unit, decimals, higher is better, description (SeapEngine's words)
  const MEASURES = {
    rmse: [T("Fit error"), "dB", 2, false, T("How far measurement + EQ lands from the target, 20 Hz to 16 kHz. Lower is better.")],
    harman: [T("Preference score"), T("pts"), 1, true, T("Olive in-ear preference model (AutoEq's implementation) against this target. Higher is better.")],
    maxerr: [T("Worst error"), "dB", 2, false, T("The single biggest miss below 10 kHz. Lower is better.")],
    headroom: [T("Headroom lost"), "dB", 1, false, T("Auto-preamp needed to avoid clipping, which is lost loudness. Lower is better.")],
    drift: [T("Sample-rate drift"), "dB", 2, false, T("Fitted at 48 kHz, played at 44.1 or 96 kHz: the worst change in the curve. Lower is better.")],
    ring: [T("Ringing above 300 Hz"), "ms", 2, false, T("Time for bands at 300 Hz and up to decay by 60 dB. Smears transients. Lower is better.")],
    stack: [T("Largest stacked boost"), "dB", 2, false, T("Biggest total boost piled on one frequency by bands within 1/12 octave. Lower is better.")],
  };
  const SIDE = ["rmse", "harman", "headroom"];

  const state = { limit: "default", bands: "avg", by: "rmse" };
  let data = null;
  const rows = new Map();

  const fmt = (key, v) => `${v.toFixed(MEASURES[key][2])} ${MEASURES[key][1]}`;

  const valuesFor = () => {
    const byBands = data.limits[state.limit] || {};
    const out = {};
    for (const e of Object.keys(ENGINES)) {
      const sets = state.bands === "avg" ? Object.values(byBands) : [byBands[state.bands] || {}];
      const m = {};
      for (const key of Object.keys(MEASURES)) {
        const vals = sets.map((s) => s[e] && s[e][key]).filter((v) => typeof v === "number");
        if (vals.length) m[key] = vals.reduce((a, b) => a + b, 0) / vals.length;
      }
      if (typeof m[state.by] === "number") out[e] = m;
    }
    return out;
  };

  const rowFor = (e) => {
    if (rows.has(e)) return rows.get(e);
    const li = document.createElement("li");
    li.className = "rank-row" + (ENGINES[e][1] === "Tryptify" ? " ours" : "");
    li.innerHTML =
      '<span class="rank-n"></span>' +
      '<span class="rank-who"><strong></strong><small></small></span>' +
      '<span class="rank-side"></span>' +
      '<span class="rank-value"></span>' +
      '<span class="rank-bar"><i></i></span>';
    li.querySelector("strong").textContent = ENGINES[e][0];
    li.querySelector("small").textContent = ENGINES[e][1];
    rows.set(e, li);
    return li;
  };

  const render = (first) => {
    const values = valuesFor();
    const [, , , higher, desc] = MEASURES[state.by];
    document.getElementById("rank-desc").textContent = desc; // kept for reading without the explainer
    document.dispatchEvent(new CustomEvent("rank-change", { detail: { limit: state.limit, bands: state.bands, by: state.by } }));
    // Where Tryptify places in this exact view, said plainly either way.
    const place = document.getElementById("explain-place");
    if (place) {
      const ranked = Object.keys(values).sort((a, b) =>
        higher ? values[b][state.by] - values[a][state.by] : values[a][state.by] - values[b][state.by]);
      const best = ranked.findIndex((e) => ENGINES[e][1] === "Tryptify");
      place.textContent = best === 0
        ? (ENGINES[ranked[1]][1] === "Tryptify"
          ? T("In this view: {first} is #1, and {second} #2.", { first: ENGINES[ranked[0]][0], second: ENGINES[ranked[1]][0] })
          : T("In this view: {first} is #1.", { first: ENGINES[ranked[0]][0] }))
        : T("In this view: {first} leads; the best Tryptify fit is #{place}.", { first: ENGINES[ranked[0]][0], place: best + 1 });
      place.classList.toggle("wins", best === 0);
    }
    const order = Object.keys(values).sort((a, b) =>
      higher ? values[b][state.by] - values[a][state.by] : values[a][state.by] - values[b][state.by]);
    const nums = order.map((e) => values[e][state.by]);
    const lo = Math.min(...nums), hi = Math.max(...nums);

    // FLIP: remember where each row was, put them in their new order, then
    // slide each from its old place to its new one.
    const before = new Map([...rows].map(([e, li]) => [e, li.isConnected ? li.getBoundingClientRect().top : null]));
    if (first) list.textContent = "";
    order.forEach((e, i) => {
      const li = rowFor(e);
      li.querySelector(".rank-n").textContent = String(i + 1);
      li.querySelector(".rank-value").textContent = fmt(state.by, values[e][state.by]);
      li.querySelector(".rank-side").textContent = SIDE.filter((k) => k !== state.by && typeof values[e][k] === "number")
        .slice(0, 2).map((k) => `${MEASURES[k][0]} ${fmt(k, values[e][k])}`).join(" · ");
      const spread = hi - lo || 1;
      const t = (values[e][state.by] - lo) / spread;
      const fill = 0.22 + 0.78 * (higher ? t : 1 - t);
      const bar = li.querySelector(".rank-bar i");
      if (moving) M.animate(bar, { scaleX: fill }, { type: "spring", stiffness: 160, damping: 22, delay: first ? 0.25 + i * 0.07 : 0 });
      else bar.style.transform = `scaleX(${fill})`;
      li.classList.toggle("first", i === 0);
      list.append(li);
    });
    [...rows.keys()].filter((e) => !order.includes(e)).forEach((e) => rows.get(e).remove());

    if (!moving) return;
    if (first) {
      M.animate(order.map((e) => rows.get(e)), { opacity: [0, 1], y: [24, 0] },
        { duration: 0.6, ease: [0.22, 1, 0.36, 1], delay: M.stagger(0.07) });
      return;
    }
    order.forEach((e) => {
      const li = rows.get(e);
      const was = before.get(e);
      if (was == null) return;
      const dy = was - li.getBoundingClientRect().top;
      if (dy) M.animate(li, { y: [dy, 0] }, { type: "spring", stiffness: 260, damping: 26 });
      M.animate(li.querySelector(".rank-value"), { opacity: [0.3, 1] }, { duration: 0.35 });
    });
  };

  // Controls.
  document.querySelectorAll(".chips[data-control]").forEach((group) => {
    group.addEventListener("click", (ev) => {
      const b = ev.target.closest("button");
      if (!b || !data) return;
      state[group.dataset.control] = b.dataset.value;
      group.querySelectorAll("button").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
      render(false);
    });
  });
  // The explanation of the measure in use, and a card for every measure.
  const X = window.TryptifyMeasures;
  let explained = null;
  const explain = () => {
    if (!X || explained === state.by) return;
    explained = state.by;
    const t = X.TEXT[state.by];
    document.getElementById("explain-kicker").textContent = T("Ranking by · {better}", { better: t.better });
    document.getElementById("explain-title").textContent = t.title;
    document.getElementById("explain-what").textContent = t.what;
    document.getElementById("explain-why").textContent = t.why;
    document.getElementById("explain-good").textContent = t.good;
    document.getElementById("explain-tryptify").textContent = t.tryptify || "";
    const loses = document.getElementById("explain-loses");
    loses.textContent = t.loses ? T("Where it doesn't win: {text}", { text: t.loses }) : "";
    loses.hidden = !t.loses;
    X.draw(state.by, document.getElementById("explain-visual"), moving ? M : null);
    if (moving) M.animate("#explain .explain-text > *", { opacity: [0, 1], y: [8, 0] }, { duration: 0.4, delay: M.stagger(0.05) });
    document.querySelectorAll(".measure").forEach((c) => c.setAttribute("aria-pressed", String(c.dataset.measure === state.by)));
  };
  const rankBy = (key) => {
    state.by = key;
    document.getElementById("rank-by").value = key;
    explain();
    if (data) render(false);
  };
  document.getElementById("rank-by").addEventListener("change", (ev) => rankBy(ev.target.value));

  const cards = document.getElementById("measure-cards");
  if (X && cards) {
    for (const key of Object.keys(MEASURES)) {
      const t = X.TEXT[key];
      const b = document.createElement("button");
      b.type = "button";
      b.className = "measure";
      b.dataset.measure = key;
      b.setAttribute("aria-pressed", String(key === state.by));
      const viz = document.createElement("div");
      viz.className = "measure-viz";
      const h = document.createElement("strong");
      h.textContent = t.title;
      const p = document.createElement("span");
      p.textContent = t.what;
      const g = document.createElement("small");
      g.textContent = t.better;
      b.append(viz, h, p, g);
      cards.append(b);
      // Draw each diagram as its card scrolls into view, so it animates then.
      if (moving) M.inView(b, () => { X.draw(key, viz, M); }, { amount: 0.4 });
      else X.draw(key, viz, null);
      b.addEventListener("click", () => {
        rankBy(key);
        document.getElementById("ranking").scrollIntoView({ behavior: moving ? "smooth" : "auto" });
      });
    }
  }
  explain();

  fetch("/data/autoeq-bench.json")
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
    .then((d) => {
      data = d;
      const n = d.noEq || {};
      document.getElementById("rank-baseline").textContent =
        T("For scale: {headphone} with no EQ at all has a fit error of {rmse} dB, a preference score of {harman} pts and a worst error of {maxerr} dB.",
          { headphone: d.headphone, rmse: n.rmse, harman: n.harman, maxerr: n.maxerr });
      render(true);
    })
    .catch(() => {
      list.innerHTML = '<li class="muted">' + T("The ranking could not load.") + ' <a href="https://seapengine.lol/">' + T("See it in SeapEngine") + '</a>.</li>';
    });
})();

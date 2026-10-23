// The full-screen player: the song's art and its lyrics, timed to the second,
// or its spectrum as a waterfall drawn the way the app draws it. Opened from
// the bar's expand button or its cover. It shows what the bar shows
// (TryptifyPlayer.shown): this browser's song, or the one another device plays
// while this browser follows it, so the lyrics run with that device too. The
// waterfall needs the sound itself, so it draws only what plays here.
//
// Lyrics come from LRCLIB (lrclib.net, free, sends CORS), the first source the
// app asks too. Nothing runs while the view is shut or the tab is hidden.
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const P = () => window.TryptifyPlayer;
  const Eng = window.TryptifyEngine;
  const view = $("wp-full");
  if (!view || !Eng) return;

  const ICON = {
    close: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><path d="m6 6 12 12M18 6 6 18"/></svg>',
    fill: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/></svg>',
    unfill: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/></svg>',
  };
  $("full-close").innerHTML = ICON.close;
  $("full-screen").innerHTML = ICON.fill;

  let mode = (() => { try { return localStorage.getItem("tryptify-player-full-mode") === "waterfall" ? "waterfall" : "lyrics"; } catch (_) { return "lyrics"; } })();
  let open = false, raf = 0;

  // ── Opening and closing ─────────────────────────────────────────────
  function show() {
    if (open) return;
    open = true;
    view.hidden = false;
    document.body.classList.add("has-full");
    fitAboveBar();
    setMode(mode);
    loop();
    $("full-close").focus();
  }
  function hide() {
    if (!open) return;
    open = false;
    view.hidden = true;
    document.body.classList.remove("has-full");
    cancelAnimationFrame(raf);
    stopWaterfall();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  }
  // The bar stays below, with its controls: on a phone it is taller than its
  // CSS height says. It floats, so the gap under it counts too.
  function fitAboveBar() {
    const bar = $("wp-bar");
    if (bar) view.style.setProperty("--full-bottom", `${bar.offsetHeight + (parseFloat(getComputedStyle(bar).bottom) || 0)}px`);
  }
  addEventListener("resize", () => { if (open) { fitAboveBar(); sizeWaterfall(); } });
  $("full-close").addEventListener("click", hide);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && open && !document.fullscreenElement) hide();
  });
  $("full-screen").addEventListener("click", () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else document.documentElement.requestFullscreen().catch(() => {});
  });
  document.addEventListener("fullscreenchange", () => {
    const on = !!document.fullscreenElement;
    const b = $("full-screen");
    b.innerHTML = on ? ICON.unfill : ICON.fill;
    b.setAttribute("aria-pressed", String(on));
    b.setAttribute("aria-label", on ? T("Leave full screen") : T("Fill the screen"));
    setTimeout(() => { fitAboveBar(); sizeWaterfall(); }, 100);
  });
  document.addEventListener("visibilitychange", () => {
    if (!open) return;
    if (document.hidden) cancelAnimationFrame(raf); else loop();
  });

  for (const b of view.querySelectorAll(".wp-full-mode")) b.addEventListener("click", () => setMode(b.dataset.mode));
  function setMode(m) {
    mode = m;
    try { localStorage.setItem("tryptify-player-full-mode", m); } catch (_) {}
    for (const b of view.querySelectorAll(".wp-full-mode")) b.setAttribute("aria-selected", String(b.dataset.mode === m));
    $("full-lyrics").hidden = m !== "lyrics";
    $("full-waterfall").hidden = m !== "waterfall";
    if (m === "waterfall") startWaterfall(); else stopWaterfall();
    if (open) loop();
  }

  // ── The song, its art ───────────────────────────────────────────────
  let shownKey = null;
  function paintSong(s) {
    const key = s ? s.key : null;
    if (key === shownKey) return;
    shownKey = key;
    $("full-title").textContent = s ? s.title : T("Nothing playing");
    $("full-artist").textContent = s ? [s.artist, s.album].filter(Boolean).join(" · ") : "";
    $("full-on").textContent = s && s.on ? T("Playing on {name}", { name: s.on }) : "";
    const img = $("full-cover");
    img.hidden = !(s && s.cover);
    $("full-cover-none").hidden = !!(s && s.cover);
    if (s && s.cover) img.src = s.cover; else img.removeAttribute("src");
    shownCover = s ? s.cover : null;
    coverColour(shownCover);
    // The art, blurred, behind everything.
    $("full-bg").style.backgroundImage = s && s.cover ? `url("${s.cover.replace(/["\\]/g, "")}")` : "";
    loadLyrics(s);
  }

  // ── Lyrics ──────────────────────────────────────────────────────────
  const cache = new Map(); // song key → { lines: [{ t, text }], timed } | null
  let lyrics = null, lyricsFor = null, nowLine = -1, userScrolledAt = -Infinity;

  /** "[01:02.50] words" lines → [{ t: 62.5, text }], in time order. A line may carry several times. */
  function parseLrc(lrc) {
    const out = [];
    for (const raw of lrc.split(/\r?\n/)) {
      const times = [...raw.matchAll(/\[(\d+):(\d+(?:[.:]\d+)?)\]/g)];
      if (!times.length) continue;
      const text = raw.replace(/\[[^\]]*\]/g, "").trim();
      for (const m of times) out.push({ t: Number(m[1]) * 60 + Number(m[2].replace(":", ".")), text });
    }
    return out.sort((a, b) => a.t - b.t);
  }
  async function fetchLyrics(s) {
    const q = { track_name: s.title, artist_name: s.artist };
    const exact = new URLSearchParams({ ...q, album_name: s.album || "", duration: String(Math.round(s.duration || 0)) });
    let hit = null;
    if (s.artist && s.duration) {
      const r = await fetch(`https://lrclib.net/api/get?${exact}`);
      if (r.ok) hit = await r.json();
    }
    if (!hit || (!hit.syncedLyrics && !hit.plainLyrics)) {
      const r = await fetch(`https://lrclib.net/api/search?${new URLSearchParams(q)}`);
      const list = r.ok ? await r.json() : [];
      // The closest in length among those with words, timed ones first.
      const near = (x) => Math.abs((x.duration || 0) - (s.duration || 0));
      const usable = (Array.isArray(list) ? list : []).filter((x) => x.syncedLyrics || x.plainLyrics)
        .filter((x) => !s.duration || near(x) < 8)
        .sort((a, b) => (!!b.syncedLyrics - !!a.syncedLyrics) || near(a) - near(b));
      hit = usable[0] || hit;
    }
    if (!hit) return null;
    if (hit.instrumental) return { lines: [], timed: false, instrumental: true };
    if (hit.syncedLyrics) return { lines: parseLrc(hit.syncedLyrics), timed: true };
    if (hit.plainLyrics) return { lines: hit.plainLyrics.split(/\r?\n/).map((text) => ({ t: null, text: text.trim() })), timed: false };
    return null;
  }
  async function loadLyrics(s) {
    const box = $("full-lines"), note = $("full-lyrics-note");
    lyricsFor = s ? s.key : null;
    lyrics = null; nowLine = -1;
    box.textContent = "";
    if (!s) { note.textContent = ""; return; }
    note.textContent = T("Finding the lyrics…");
    let found = cache.get(s.key);
    if (found === undefined) {
      try { found = await fetchLyrics(s); } catch (_) { found = undefined; }
      if (found !== undefined) cache.set(s.key, found);
    }
    if (lyricsFor !== s.key) return; // the song moved on while they came
    if (found === undefined) { note.textContent = T("Couldn’t reach the lyrics. Check the connection."); return; }
    if (!found || (!found.lines.length && !found.instrumental)) { note.textContent = T("No lyrics found for this song."); return; }
    if (found.instrumental) { note.textContent = T("An instrumental."); return; }
    lyrics = found;
    note.textContent = found.timed ? "" : T("These lyrics aren’t timed.");
    for (const [i, l] of found.lines.entries()) {
      const li = document.createElement("li");
      li.textContent = l.text || "♪";
      if (found.timed) {
        li.tabIndex = 0;
        li.addEventListener("click", () => P() && P().seek(l.t));
        li.addEventListener("keydown", (e) => { if (e.key === "Enter") P() && P().seek(l.t); });
      }
      li.dataset.i = String(i);
      box.append(li);
    }
    $("full-lines-box").scrollTop = 0;
  }
  // A scroll by hand holds the lyrics where they are for a few seconds.
  for (const ev of ["wheel", "touchmove"]) $("full-lines-box").addEventListener(ev, () => { userScrolledAt = performance.now(); }, { passive: true });

  function followLyrics(s) {
    if (!lyrics || !lyrics.timed || !s) return;
    const t = s.position + 0.15; // a touch early, as the words land
    const lines = lyrics.lines;
    let lo = 0, hi = lines.length - 1, at = -1;
    while (lo <= hi) { const mid = (lo + hi) >> 1; if (lines[mid].t <= t) { at = mid; lo = mid + 1; } else hi = mid - 1; }
    if (at === nowLine) return;
    const box = $("full-lines");
    const was = box.children[nowLine];
    if (was) was.classList.remove("is-now");
    nowLine = at;
    for (const li of box.children) li.classList.toggle("is-past", Number(li.dataset.i) < at);
    const li = box.children[at];
    if (!li) return;
    li.classList.add("is-now");
    if (performance.now() - userScrolledAt < 4000) return;
    const scroller = $("full-lines-box");
    const target = li.offsetTop - scroller.clientHeight * 0.38;
    const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
    scroller.scrollTo({ top: Math.max(0, target), behavior: still ? "auto" : "smooth" });
  }

  // ── The frame loop ──────────────────────────────────────────────────
  function loop() {
    cancelAnimationFrame(raf);
    const tick = () => {
      if (!open || document.hidden) return;
      const s = P() ? P().shown() : null;
      paintSong(s);
      if (mode === "lyrics") followLyrics(s);
      else drawWaterfall(s);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
  }

  // ── The waterfall ───────────────────────────────────────────────────
  // The app's spectrum waterfall at its defaults (Lines style): the spectrum
  // on a log axis from 20 Hz, tilted +4 dB an octave around 1 kHz and centred
  // on its 200 Hz–2 kHz mean, smoothed as the app smooths it, laid down as a
  // line every depth/48 s and drawn as 48 history lines plus the live one,
  // receding in perspective and fading, in the cover's own colour lightening
  // toward the top. Numbers from SpectrumAnalyzerTap.kt, SpectrumAverager.kt,
  // spectrum_waterfall.h and SpectrumOverlay.kt.
  const WF = {
    fft: 8192, bins: 256, points: 128, rows: 48,
    depth: 2.5, fadeStart: 0.3, angle: (45 * Math.PI) / 180, lineDp: 1.6,
    floorDb: -24, spanDb: 60, tilt: 4, rise: 30.3, fall: 138.9, // ms
  };
  const KERNEL = [0.03, 0.10, 0.22, 0.30, 0.22, 0.10, 0.03];
  let analyser = null, buf = null, map = null;
  const sm = new Float32Array(WF.bins), avg = new Float32Array(WF.bins), work = new Float32Array(WF.bins);
  let live = new Float32Array(WF.points), prevLive = new Float32Array(WF.points), prevAt = 0, lastRowAt = 0, lastFrame = 0;
  let history = []; // [{ at, pts }], oldest first
  let colour = [126, 182, 255]; // #7EB6FF, the app's fallback, until the cover's own is known

  function startWaterfall() { sizeWaterfall(); lastFrame = 0; }
  function stopWaterfall() { if (analyser) { Eng.release(analyser); analyser = null; } history = []; }
  function sizeWaterfall() {
    const c = $("full-wf-canvas");
    if (!c || $("full-waterfall").hidden) return;
    const dpr = Math.min(devicePixelRatio || 1, 2);
    c.width = Math.max(1, Math.round(c.clientWidth * dpr));
    c.height = Math.max(1, Math.round(c.clientHeight * dpr));
  }
  /** Each of the 256 log-spaced bins: the FFT bin it reads, its tilt, and whether it is in the 200 Hz–2 kHz band. */
  function binMap(sr) {
    const n = WF.fft, top = Math.min(20000, sr / 2 - 1), width = sr / n;
    const idx = new Int32Array(WF.bins), tilt = new Float32Array(WF.bins), mid = [];
    for (let b = 0; b < WF.bins; b++) {
      const f = 20 * Math.pow(top / 20, b / (WF.bins - 1));
      idx[b] = Math.min(n / 2 - 1, Math.max(1, Math.floor(f / width)));
      tilt[b] = WF.tilt * Math.log2(f / 1000);
      if (f >= 200 && f <= 2000) mid.push(b);
    }
    return { sr, idx, tilt, mid };
  }
  /** One analysis: the live line's 128 points, 0..1. */
  function analyse(dt) {
    analyser.getFloatFrequencyData(buf);
    if (!map || map.sr !== analyser.context.sampleRate) map = binMap(analyser.context.sampleRate);
    let mean = 0;
    for (let b = 0; b < WF.bins; b++) {
      const db = buf[map.idx[b]];
      work[b] = (Number.isFinite(db) ? Math.max(db, -120) : -120) + map.tilt[b];
    }
    for (const b of map.mid) mean += work[b];
    mean /= map.mid.length || 1;
    for (let b = 0; b < WF.bins; b++) {
      const v = work[b] - mean;
      sm[b] += (v > sm[b] ? 0.55 : 0.09) * (v - sm[b]);
    }
    for (let b = 0; b < WF.bins; b++) {
      let g = 0;
      for (let k = 0; k < 7; k++) g += KERNEL[k] * sm[Math.min(WF.bins - 1, Math.max(0, b + k - 3))];
      const tau = (g > avg[b] ? WF.rise : WF.fall) / 1000;
      avg[b] += (g - avg[b]) * (1 - Math.exp(-dt / tau));
    }
    const pts = new Float32Array(WF.points);
    for (let j = 0; j < WF.points; j++) {
      const db = Math.max(avg[2 * j], avg[2 * j + 1]);
      pts[j] = Math.min(1, Math.max(0, (db - WF.floorDb) / WF.spanDb));
    }
    return pts;
  }
  /** Lays down history lines on the clock, every depth/48 s, in between frames by interpolation. */
  function lay(now) {
    const period = WF.depth / WF.rows;
    if (!lastRowAt || now - lastRowAt > WF.depth) { lastRowAt = now - period; }
    while (now - lastRowAt >= period) {
      const at = lastRowAt + period;
      const k = prevAt && now > prevAt ? Math.min(1, Math.max(0, (at - prevAt) / (now - prevAt))) : 1;
      const pts = new Float32Array(WF.points);
      for (let j = 0; j < WF.points; j++) pts[j] = prevLive[j] + (live[j] - prevLive[j]) * k;
      history.push({ at, pts });
      lastRowAt = at;
    }
    while (history.length && now - history[0].at >= WF.depth) history.shift();
  }
  function drawWaterfall(s) {
    const c = $("full-wf-canvas"), note = $("full-wf-note");
    const g = c.getContext("2d");
    if (!s || !s.local || !Eng.webAudio) {
      g.clearRect(0, 0, c.width, c.height);
      note.textContent = !s ? T("Play a song to see its waterfall.")
        : !s.local ? T("The waterfall draws the sound playing here. This song plays on {name}.", { name: s.on })
        : T("Press play to start the waterfall.");
      return;
    }
    note.textContent = "";
    if (!analyser) { analyser = Eng.analyser(WF.fft); buf = new Float32Array(WF.fft / 2); history = []; lastRowAt = 0; }
    if (!analyser) return;
    if (c.width < 2) sizeWaterfall();
    const now = performance.now() / 1000;
    const dt = lastFrame ? Math.min(0.1, now - lastFrame) : 1 / 60;
    lastFrame = now;
    prevLive = live;
    live = analyse(dt);
    lay(now);
    prevAt = now;
    paintWaterfall(g, c.width, c.height, now);
  }
  function paintWaterfall(g, W, H, now) {
    g.clearRect(0, 0, W, H);
    const P = 1.25, sBack = 1 / (1 + P), rise = 0.62 * Math.sin(WF.angle);
    const roomBack = (0.985 - rise) / sBack;
    const amp = Math.min(0.985 * 0.94, roomBack * 0.96) * (0.35 + 0.65 * Math.cos(WF.angle)) * H;
    const dpr = Math.min(devicePixelRatio || 1, 2);
    // The app draws in a box about 130 dp tall; lines thicken a little on a big screen so they still read.
    const stroke = WF.lineDp * dpr * Math.min(1.5, Math.max(1, H / dpr / 320));
    const [r, gr, b] = colour;
    const light = [r, gr, b].map((v) => Math.round(v + (255 - v) * 0.55));
    const grad = g.createLinearGradient(0, 0, 0, H);
    grad.addColorStop(0, `rgb(${light[0]},${light[1]},${light[2]})`);
    grad.addColorStop(1, `rgb(${r},${gr},${b})`);
    g.strokeStyle = grad;
    g.lineJoin = "round";
    g.lineCap = "round";
    const line = (pts, t) => {
      if (t >= 1) return;
      const fade = t <= WF.fadeStart ? 1 : Math.pow(1 - (t - WF.fadeStart) / (1 - WF.fadeStart), 1.5);
      const alpha = 0.92 * fade;
      if (alpha <= 0.004) return;
      const sc = 1 / (1 + P * t);
      const d = (1 - sc) / (1 - sBack);
      const base = H * 0.985 - d * rise * H;
      g.globalAlpha = alpha;
      g.lineWidth = stroke * (0.45 + 0.55 * sc);
      g.beginPath();
      for (let j = 0; j < WF.points; j++) {
        const x = W / 2 + (j / (WF.points - 1) - 0.5) * W * sc;
        const y = base - pts[j] * amp * sc;
        if (j) g.lineTo(x, y); else g.moveTo(x, y);
      }
      g.stroke();
    };
    for (const row of history) line(row.pts, (now - row.at) / WF.depth);
    line(live, 0);
    g.globalAlpha = 1;
  }

  // The cover's own colour, as the app takes it from the album (js/glass.js);
  // a grey cover, or one that cannot be read, keeps the app's fallback blue.
  function coverColour(url) {
    const G = window.TryptifyGlass;
    if (!url || !G) { colour = [126, 182, 255]; return; }
    G.vibrant(url).then((c) => { if (shownCover === url) colour = c || [126, 182, 255]; });
  }
  let shownCover = null;

  window.TryptifyFull = { open: show, close: hide, get isOpen() { return open; } };
})();

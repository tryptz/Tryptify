// The web player (/player): the dashboard, the folders, Tryptify Cloud, Trypt
// HiFi, search, the queue and the bar at the bottom. The sound is
// wp-engine.js, the music wp-sources.js, and the AutoEQ view is the AutoEQ
// page's own try-out (tryout.js), whose every fit this plays.
// Nothing here polls: the audio's own events and the listener's taps redraw
// what changed, so an idle tab costs nothing.
(() => {
  "use strict";
  const Eng = window.TryptifyEngine, S = window.TryptifySources, E = window.TryptifyAutoEq, C = window.TryptifyConnect;
  if (!Eng || !S) return;
  const { Local, Cloud, HiFi, Subsonic, Library } = S;
  const $ = (id) => document.getElementById(id);
  const loadJSON = (k) => { try { return JSON.parse(localStorage.getItem(k)) || {}; } catch (_) { return {}; } };
  const saveJSON = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (_) {} };
  const clock = (s) => {
    if (!Number.isFinite(s) || s < 0) return "0:00";
    const m = Math.floor(s / 60);
    return `${m}:${String(Math.floor(s % 60)).padStart(2, "0")}`;
  };
  const SRC = { local: T("Files"), cloud: "Tryptify Cloud", hifi: "Trypt HiFi", subsonic: T("Music server") };

  const ICON = {
    play: '<svg viewBox="0 0 24 24"><path d="M8 5.5v13a1 1 0 0 0 1.5.86l10.6-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5Z" fill="currentColor"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><rect x="6.5" y="5" width="4" height="14" rx="1.2" fill="currentColor"/><rect x="13.5" y="5" width="4" height="14" rx="1.2" fill="currentColor"/></svg>',
    prev: '<svg viewBox="0 0 24 24"><rect x="5" y="5.5" width="2.4" height="13" rx="1" fill="currentColor"/><path d="M19 6.3v11.4a.9.9 0 0 1-1.38.76L9.3 13.06a1.25 1.25 0 0 1 0-2.12l8.32-5.4A.9.9 0 0 1 19 6.3Z" fill="currentColor"/></svg>',
    next: '<svg viewBox="0 0 24 24"><rect x="16.6" y="5.5" width="2.4" height="13" rx="1" fill="currentColor"/><path d="M5 6.3v11.4a.9.9 0 0 0 1.38.76l8.32-5.4a1.25 1.25 0 0 0 0-2.12l-8.32-5.4A.9.9 0 0 0 5 6.3Z" fill="currentColor"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7h3.5c4.5 0 6.5 10 11 10H21M3 17h3.5c1.8 0 3.1-1.6 4.2-3.6M14.3 9.6C15.3 8 16.5 7 18 7h3M18.5 4.5 21 7l-2.5 2.5M18.5 14.5 21 17l-2.5 2.5"/></svg>',
    repeat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 11V9.5A3.5 3.5 0 0 1 7.5 6H19M16.5 3.5 19 6l-2.5 2.5M20 13v1.5a3.5 3.5 0 0 1-3.5 3.5H5M7.5 20.5 5 18l2.5-2.5"/></svg>',
    repeatOne: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 11V9.5A3.5 3.5 0 0 1 7.5 6H19M16.5 3.5 19 6l-2.5 2.5M20 13v1.5a3.5 3.5 0 0 1-3.5 3.5H5M7.5 20.5 5 18l2.5-2.5"/><path d="M11.2 10.6 12.5 9.8v4.6" stroke-width="1.6"/></svg>',
    volume: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9.5h3l4.5-4v13l-4.5-4H4z" fill="currentColor" stroke="none"/><path d="M15.5 9a4.2 4.2 0 0 1 0 6M18.3 6.5a8 8 0 0 1 0 11"/></svg>',
    mute: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 9.5h3l4.5-4v13l-4.5-4H4z" fill="currentColor" stroke="none"/><path d="m16 9.5 5 5M21 9.5l-5 5"/></svg>',
    queue: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 6h12M4 11h12M4 16h7"/><path d="M17 14v6l4-3z" fill="currentColor" stroke="none"/></svg>',
    close: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><path d="m6 6 12 12M18 6 6 18"/></svg>',
    plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
    sun: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/></svg>',
    moon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z"/></svg>',
    devices: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="3" y="4" width="11" height="9" rx="1.5"/><path d="M6 17h5"/><rect x="16" y="7" width="5" height="12" rx="1.5"/></svg>',
    phone: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="7" y="3" width="10" height="18" rx="2.5"/><path d="M11 17.5h2"/></svg>',
    expand: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7"/></svg>',
    folder: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 7.5A1.5 1.5 0 0 1 4.5 6H9l2 2h8.5A1.5 1.5 0 0 1 21 9.5v8a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5z"/></svg>',
  };

  /** A DOM element; text always goes in as text, never as markup (track names come from servers). */
  function h(tag, props, ...kids) {
    const e = document.createElement(tag);
    if (props) for (const [k, v] of Object.entries(props)) {
      if (v == null || v === false) continue;
      if (k === "class") e.className = v;
      else if (k === "text") e.textContent = v;
      else if (k === "icon") e.innerHTML = ICON[v]; // our own SVGs only
      else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? "" : String(v));
    }
    for (const k of kids.flat()) if (k != null && k !== false) e.append(k instanceof Node ? k : String(k));
    return e;
  }
  /** el.append, skipping the empty slots of optional parts (append itself would print "null"). */
  const put = (el, ...kids) => el.append(...kids.flat().filter((k) => k != null && k !== false));
  const setIcon = (btn, name) => { if (btn.dataset.icon !== name) { btn.innerHTML = ICON[name]; btn.dataset.icon = name; } };
  const songs = (n) => (n === 1 ? T("{count} song", { count: n }) : T("{count} songs", { count: n }));
  const bandsOf = (n) => (n === 1 ? T("{count} band", { count: n }) : T("{count} bands", { count: n }));
  // A cover that will not load (a server without one) becomes the plain square.
  document.addEventListener("error", (e) => {
    const img = e.target;
    if (!(img instanceof HTMLImageElement)) return;
    if (img.id === "bar-cover") { img.dataset.failed = img.getAttribute("src"); img.hidden = true; $("bar-cover-none").hidden = false; return; }
    if (img.closest(".wp-main")) img.replaceWith(h("span", { class: `${img.className} wp-cover-none`.trim() }));
  }, true);
  const head = (title, lead) => h("div", { class: "wp-head" }, h("div", null, h("h1", { text: title }), lead ? h("p", { class: "muted", text: lead }) : null));

  let toastTimer = 0;
  function toast(msg, bad) {
    const t = $("wp-toast");
    t.textContent = msg;
    t.classList.toggle("bad", !!bad);
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 6000);
  }

  // ── Settings ────────────────────────────────────────────────────────
  const settings = Object.assign({ volume: 0.8, speed: 1, preservePitch: true, mono: false, gapless: true, shuffle: false, repeat: "off" },
    loadJSON("tryptify-player-settings"));
  const saveSettings = () => saveJSON("tryptify-player-settings", settings);
  let eq = Object.assign({ on: false, bands: [], preamp: 0 }, loadJSON("tryptify-player-eq"));
  const saveEq = () => saveJSON("tryptify-player-eq", eq);
  Eng.setVolume(settings.volume);
  Eng.setSpeed(settings.speed);
  Eng.setPreservePitch(settings.preservePitch);
  Eng.setMono(settings.mono);
  Eng.setEq(eq);

  // ── The queue ───────────────────────────────────────────────────────
  let queue = [], order = [], pos = -1, current = null, starting = 0, failures = 0;
  const shuffled = (n, first) => {
    const a = [...Array(n).keys()].filter((i) => i !== first);
    for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
    return first == null ? a : [first, ...a];
  };
  const upcoming = () => order.slice(pos + 1).map((i) => queue[i]);

  function playList(tracks, i = 0) {
    if (!tracks.length) return;
    queue = tracks.slice();
    order = settings.shuffle ? shuffled(queue.length, i) : [...queue.keys()];
    pos = order.indexOf(i);
    start();
  }
  function enqueue(track) {
    queue.push(track);
    const i = queue.length - 1;
    if (pos < 0) { order = [i]; pos = 0; start(); return; }
    order.splice(pos + 1, 0, i);
    toast(T("“{title}” plays next.", { title: track.title }));
    renderQueue(); renderNext(); preloadNext();
  }
  async function start(autoplay = true, startAt = 0) {
    const t = queue[order[pos]];
    if (!t) return;
    current = t;
    const my = ++starting;
    renderAll();
    try {
      if (t.src === "local") await Local.tags(t).catch(() => {});
      if (t.src === "cloud") Cloud.cover(t).then(() => { if (my === starting) { renderBar(); renderNow(); session(); } }).catch(() => {});
      const url = await S.url(t);
      if (my !== starting) return;
      await Eng.load(url, t.key, autoplay, startAt);
      failures = 0;
    } catch (e) {
      if (my === starting) fail(e);
      return;
    }
    renderAll();
    session();
    preloadNext();
  }
  function fail(e) {
    if (!current) return;
    const why = e && e.name === "NotAllowedError" ? T("the browser wants a tap first.") : (e && e.message) || T("it would not load.");
    toast(T("Could not play “{title}”: {reason}", { title: current.title, reason: why }), true);
    renderBar();
    if (e && e.name === "NotAllowedError") return;
    if (++failures < 3 && pos + 1 < order.length) { pos++; start(); }
  }
  async function preloadNext() {
    if (!settings.gapless || settings.repeat === "one") return;
    const nx = queue[order[pos + 1]] || (settings.repeat === "all" && !settings.shuffle ? queue[order[0]] : null);
    if (!nx || nx === current) return;
    try {
      if (nx.src === "local") await Local.tags(nx).catch(() => {});
      Eng.preload(await S.url(nx), nx.key);
    } catch (_) { /* it loads when its turn comes */ }
  }
  function next(auto) {
    if (!queue.length) return;
    if (auto && settings.repeat === "one") { Eng.seek(0); Eng.play(); return; }
    if (pos + 1 < order.length) pos++;
    else if (settings.repeat === "all") { if (settings.shuffle) order = shuffled(queue.length, null); pos = 0; }
    else { renderAll(); return; }
    start();
  }
  function prev() {
    if (Eng.time > 3 || pos <= 0) { Eng.seek(0); publish(); return; }
    pos--;
    start();
  }
  function setShuffle(on) {
    settings.shuffle = on;
    saveSettings();
    if (queue.length) {
      const at = order[pos];
      order = on ? shuffled(queue.length, at) : [...queue.keys()];
      pos = order.indexOf(at);
      preloadNext();
    }
    renderBar(); renderQueue(); renderNext();
  }
  function cycleRepeat() {
    settings.repeat = settings.repeat === "off" ? "all" : settings.repeat === "all" ? "one" : "off";
    saveSettings();
    renderBar();
    preloadNext();
  }

  Eng.on("ended", () => next(true));
  Eng.on("error", () => fail(new Error(T("this browser cannot play that file."))));
  Eng.on("play", () => { if (C) playSince = C.serverNow(); renderBar(); renderNow(); publish(); renderConnect(); if (navigator.mediaSession) navigator.mediaSession.playbackState = "playing"; });
  Eng.on("pause", () => { renderBar(); renderNow(); publish(); renderConnect(); if (navigator.mediaSession) navigator.mediaSession.playbackState = "paused"; });
  // Waiting on the network: the other devices stop the clock until sound comes back.
  let stalled = false;
  Eng.on("waiting", () => { if (!Eng.paused) { stalled = true; publish(); } });
  Eng.on("playing", () => { if (stalled) { stalled = false; publish(); } });
  Eng.on("durationchange", () => {
    if (remoteNow()) return; // the bar is following another device
    const s = $("bar-seek");
    // A song still arriving has no length in the browser yet: the catalogue's stands in.
    const length = Eng.duration || (current && current.duration) || 0;
    s.max = String(length);
    $("bar-dur").textContent = clock(length);
    position();
  });
  let dragging = false;
  Eng.on("timeupdate", () => {
    if (!dragging && !remoteNow()) { $("bar-seek").value = String(Eng.time); $("bar-time").textContent = clock(Eng.time); }
    const bar = $("now-bar");
    if (bar && Eng.duration) bar.style.width = `${(Eng.time / Eng.duration) * 100}%`;
  });

  // ── The phone's lock screen and the keyboard's media keys ───────────
  function position() {
    const ms = navigator.mediaSession;
    if (!ms || !ms.setPositionState || !Eng.duration) return;
    try { ms.setPositionState({ duration: Eng.duration, position: Math.min(Eng.time, Eng.duration), playbackRate: settings.speed }); } catch (_) {}
  }
  function session() {
    const ms = navigator.mediaSession;
    if (!ms || !window.MediaMetadata || !current) return;
    ms.metadata = new MediaMetadata({
      title: current.title, artist: current.artist || "", album: current.album || "",
      artwork: current.cover ? [{ src: current.cover, sizes: "512x512" }] : [{ src: "/apple-touch-icon.png", sizes: "180x180", type: "image/png" }],
    });
    position();
  }
  if (navigator.mediaSession) {
    const set = (a, fn) => { try { navigator.mediaSession.setActionHandler(a, fn); } catch (_) {} };
    set("play", () => Eng.play());
    set("pause", () => Eng.pause());
    set("previoustrack", prev);
    set("nexttrack", () => next(false));
    set("seekto", (d) => { seekTo(d.seekTime); });
  }

  // ── The bar ─────────────────────────────────────────────────────────
  for (const b of document.querySelectorAll("[data-act]")) {
    const name = { toggle: "play", prev: "prev", next: "next", shuffle: "shuffle", repeat: "repeat", queue: "queue", mute: "volume", devices: "devices", full: "expand" }[b.dataset.act];
    if (name) setIcon(b, b.closest(".wp-queue") ? "close" : name);
  }
  document.addEventListener("click", (e) => {
    const b = e.target.closest("[data-act]");
    if (!b) return;
    const act = b.dataset.act;
    // Following another device, as Spotify does: the bar's buttons steer it.
    const r = (act === "toggle" || act === "prev" || act === "next") && !b.closest("#wp-elsewhere") ? remoteNow() : null;
    if (r) { remote(act === "toggle" ? (r.playing ? "pause" : "play") : act); return; }
    if (act === "toggle") current ? Eng.toggle().catch(fail) : playFirst();
    else if (act === "prev") prev();
    else if (act === "next") next(false);
    else if (act === "shuffle") setShuffle(!settings.shuffle);
    else if (act === "repeat") cycleRepeat();
    else if (act === "queue") toggleQueue();
    else if (act === "devices") toggleDevices();
    else if (act === "full") window.TryptifyFull && window.TryptifyFull.open();
    else if (act === "mute") { setVolume(settings.volume > 0 ? 0 : settings.lastVolume || 0.8); }
  });
  function playFirst() {
    if (Local.tree && Local.count) return playList(S.util.allFiles(Local.tree));
    location.hash = "#/files";
  }
  const seek = $("bar-seek");
  seek.addEventListener("input", () => { dragging = true; $("bar-time").textContent = clock(Number(seek.value)); });
  seek.addEventListener("change", () => {
    dragging = false;
    if (remoteNow()) { seekRemote(Number(seek.value)); return; }
    seekTo(Number(seek.value));
  });
  /**
   * The playing song to [t] seconds. One its server is still putting
   * together (a TrypT HiFi stream, sent as it arrives) cannot be sought in,
   * so it is loaded again, whole this time (`whole=1`: the server answers
   * once the file is complete, with its length and ranges), from [t].
   */
  async function seekTo(t) {
    if (!current) return;
    if (Eng.canSeek(t)) { Eng.seek(t); position(); publish(); return; }
    const t0 = current, playing = !Eng.paused;
    try {
      let url = await S.url(t0);
      if (t0 !== current) return;
      if (t0.src === "hifi") url += (url.includes("?") ? "&" : "?") + "whole=1";
      await Eng.load(url, null, playing, t);
    } catch (e) { if (t0 === current) fail(e); return; }
    position(); publish();
  }
  const vol = $("bar-volume");
  vol.value = String(settings.volume);
  function setVolume(v) {
    if (v > 0) settings.lastVolume = v;
    settings.volume = v;
    vol.value = String(v);
    Eng.setVolume(v);
    setIcon($("bar-mute"), v > 0 ? "volume" : "mute");
    $("bar-mute").setAttribute("aria-label", v > 0 ? T("Mute") : T("Unmute"));
    renderPath();
  }
  vol.addEventListener("input", () => setVolume(Number(vol.value)));
  vol.addEventListener("change", saveSettings);
  setVolume(settings.volume);

  function renderBar() {
    const t = current;
    $("bar-title").textContent = t ? t.title : T("Nothing playing");
    $("bar-artist").textContent = t ? [t.artist, t.album].filter(Boolean).join(" · ") : "";
    const img = $("bar-cover");
    const cover = t && t.cover && img.dataset.failed !== t.cover ? t.cover : null;
    img.hidden = !cover;
    $("bar-cover-none").hidden = !!cover;
    if (cover && img.getAttribute("src") !== cover) img.src = cover;
    const play = $("bar-play");
    setIcon(play, Eng.paused ? "play" : "pause");
    play.setAttribute("aria-label", Eng.paused ? T("Play") : T("Pause"));
    $("bar-shuffle").setAttribute("aria-pressed", String(settings.shuffle));
    const rep = $("bar-repeat");
    rep.setAttribute("aria-pressed", String(settings.repeat !== "off"));
    rep.setAttribute("aria-label", settings.repeat === "off" ? T("Repeat: off") : settings.repeat === "all" ? T("Repeat: all") : T("Repeat: this song"));
    setIcon(rep, settings.repeat === "one" ? "repeatOne" : "repeat");
    const badge = $("bar-badge");
    badge.hidden = !t;
    if (t) badge.textContent = [t.quality, eqActive() ? "AutoEQ" : null].filter(Boolean).join(" · ");
    document.title = t && !Eng.paused ? `${t.title} · Tryptify` : T("Player · Tryptify");
    // Following another device: its song, and its play state on the button.
    const r = remoteNow();
    if (r) {
      $("bar-title").textContent = r.track.title || T("Untitled");
      $("bar-artist").textContent = T("{meta} · on {name}", { meta: [r.track.artist, r.track.album].filter(Boolean).join(" · "), name: nameOf(r.device, r.name) });
      const rc = r.track.cover && img.dataset.failed !== r.track.cover ? r.track.cover : null;
      img.hidden = !rc;
      $("bar-cover-none").hidden = !!rc;
      if (rc && img.getAttribute("src") !== rc) img.src = rc;
      setIcon(play, r.playing ? "pause" : "play");
      play.setAttribute("aria-label", r.playing ? T("Pause on {name}", { name: nameOf(r.device, r.name) }) : T("Play on {name}", { name: nameOf(r.device, r.name) }));
      badge.hidden = true;
    }
    tintBar(img.hidden ? null : img.getAttribute("src"));
  }
  // Dynamic colours, as the app's: the playing song's cover colours the
  // player. Its vibrant colour is the accent (buttons, links, the bar's play
  // button and timeline), made legible on the page's own background, and its
  // dominant colour a wash at the top of the page, as the app washes its
  // player with the album's. The page's backgrounds and text stay the
  // design's. No cover, or one that cannot be read: the design's own colours.
  let tintFor = null, coverColours = null;
  function tintBar(url) {
    if (url === tintFor) return;
    tintFor = url;
    if (!url || !window.TryptifyGlass) { coverColours = null; paintCover(); return; }
    TryptifyGlass.palette(url).then((p) => {
      if (tintFor !== url) return;
      coverColours = p.vibrant || p.dominant ? p : null;
      paintCover();
    });
  }
  const lum = (c) => {
    const f = (v) => { v /= 255; return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
  };
  const contrast = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
  const mixRgb = (a, b, k) => a.map((v, i) => Math.round(v + (b[i] - v) * k));
  // [c] moved toward white on a dark page, black on a light one, just until
  // text in it reads at 4.5:1 on [ground].
  const legible = (c, ground, dark) => {
    const toward = dark ? [255, 255, 255] : [0, 0, 0];
    for (let k = 0; k <= 1.0001; k += 0.04) {
      const m = mixRgb(c, toward, Math.min(1, k));
      if (contrast(m, ground) >= 4.5) return m;
    }
    return toward;
  };
  // A CSS colour as written in the design ("#0a0a0a", "#fff", "rgb(…)"); null if neither.
  const rgbOf = (css) => {
    const s = css.trim();
    if (s[0] === "#") {
      const hex = s.length === 4 ? [...s.slice(1)].map((c) => c + c).join("") : s.slice(1, 7);
      return [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16));
    }
    const m = s.match(/\d+(\.\d+)?/g);
    return m && m.length >= 3 ? m.slice(0, 3).map(Number) : null;
  };
  function paintCover() {
    const page = document.body, bar = $("wp-bar");
    const p = coverColours;
    if (p && p.vibrant) bar.style.setProperty("--tint", p.vibrant.join(" ")); else bar.style.removeProperty("--tint");
    page.classList.toggle("dyn", !!p);
    if (!p) {
      for (const k of ["--dyn-accent", "--dyn-accent-2", "--dyn-on-accent", "--dyn-wash"]) page.style.removeProperty(k);
      return;
    }
    const dark = document.documentElement.dataset.design !== "white";
    const ground = rgbOf(getComputedStyle(page).getPropertyValue("--bg")) || (dark ? [10, 10, 10] : [255, 255, 255]);
    const accent = legible(p.vibrant || p.dominant, ground, dark);
    // The quieter accent of links and labels: the accent a third of the way to grey.
    const quiet = legible(mixRgb(accent, dark ? [189, 189, 189] : [85, 85, 79], 0.35), ground, dark);
    const on = contrast(accent, [10, 10, 10]) >= contrast(accent, [255, 255, 255]) ? [10, 10, 10] : [255, 255, 255];
    const wash = p.dominant || p.vibrant;
    page.style.setProperty("--dyn-accent", `rgb(${accent.join(" ")})`);
    page.style.setProperty("--dyn-accent-2", `rgb(${quiet.join(" ")})`);
    page.style.setProperty("--dyn-on-accent", `rgb(${on.join(" ")})`);
    page.style.setProperty("--dyn-wash", `rgb(${wash.join(" ")} / ${dark ? 0.34 : 0.22})`);
  }
  // Black and white make different accents legible: painted again on a switch.
  new MutationObserver(() => requestAnimationFrame(paintCover))
    .observe(document.documentElement, { attributes: true, attributeFilter: ["data-design"] });
  function markRows() {
    for (const li of document.querySelectorAll(".wp-track")) li.classList.toggle("is-current", !!current && li.dataset.key === current.key);
  }
  function renderAll() { renderBar(); markRows(); renderQueue(); if (!$("view-home").hidden) renderHome(); }

  // ── The queue panel ─────────────────────────────────────────────────
  function toggleQueue(force) {
    const q = $("wp-queue");
    q.hidden = force != null ? !force : !q.hidden;
    $("bar-queue").setAttribute("aria-expanded", String(!q.hidden));
    renderQueue();
  }
  function renderQueue() {
    if ($("wp-queue").hidden) return;
    const ol = $("wp-queue-list");
    ol.textContent = "";
    if (!order.length) { ol.append(h("li", { class: "muted", text: T("Nothing in the queue yet.") })); return; }
    order.forEach((qi, j) => {
      if (j < pos) return;
      const t = queue[qi];
      ol.append(h("li", { class: j === pos ? "is-current" : null },
        h("button", { type: "button", class: "wp-queue-song", onclick: () => { pos = j; start(); } },
          h("strong", { text: t.title }), h("span", { class: "muted", text: [t.artist, SRC[t.src]].filter(Boolean).join(" · ") })),
        j === pos ? null : h("button", { type: "button", class: "wp-icon", icon: "close", "aria-label": T("Remove {title}", { title: t.title }),
          onclick: () => { order.splice(j, 1); renderQueue(); renderNext(); preloadNext(); } })));
    });
  }

  // ── Track lists ─────────────────────────────────────────────────────
  function trackList(tracks, { album = true, numbered = false } = {}) {
    const ol = h("ol", { class: "wp-tracks" + (album ? "" : " no-album") });
    tracks.forEach((t, i) => {
      ol.append(h("li", { class: "wp-track" + (current && current.key === t.key ? " is-current" : ""), "data-key": t.key },
        h("button", { type: "button", class: "wp-track-play", onclick: () => playList(tracks, i) },
          h("span", { class: "wp-track-n", text: String(numbered && t.n ? t.n : i + 1) }),
          h("span", { class: "wp-track-main" }, h("strong", { text: t.title }), h("span", { class: "muted", text: t.artist || "" })),
          album ? h("span", { class: "wp-track-album muted", text: t.album || "" }) : null,
          h("span", { class: "wp-track-q", text: t.quality || "" }),
          h("span", { class: "wp-track-len muted", text: t.duration ? clock(t.duration) : "" })),
        h("button", { type: "button", class: "wp-icon wp-track-add", icon: "plus", "aria-label": T("Play {title} next", { title: t.title }), title: T("Play next"), onclick: () => enqueue(t) })));
    });
    return ol;
  }
  const playButtons = (tracks) => h("div", { class: "buttons" },
    h("button", { type: "button", class: "btn primary", text: T("Play"), onclick: () => { settings.shuffle && setShuffle(false); playList(tracks); } }),
    h("button", { type: "button", class: "btn", text: T("Shuffle"), onclick: () => { setShuffle(true); playList(tracks, Math.floor(Math.random() * tracks.length)); } }));

  // ── Files ───────────────────────────────────────────────────────────
  function pickButton(label) {
    const go = () => { localChanged(); location.hash = "#/files"; route(); };
    if (Local.canKeep) return h("button", { type: "button", class: "btn primary", text: label,
      onclick: async () => { try { await Local.pick(); go(); } catch (e) { if (e.name !== "AbortError") toast(e.message, true); } } });
    const input = h("input", { type: "file", webkitdirectory: true, multiple: true, hidden: true });
    input.addEventListener("change", () => { if (input.files.length) { Local.fromFileList(input.files); go(); } });
    return h("label", { class: "btn primary" }, label, input);
  }
  function reconnectButton() {
    return h("button", { type: "button", class: "btn primary", text: T("Open “{name}” again", { name: Local.pending.name }),
      onclick: async () => { if (await Local.reconnect()) { localChanged(); route(); } } });
  }
  function localChanged() {
    $("wp-count-local").textContent = Local.count ? String(Local.count) : "";
    if (!$("view-home").hidden) renderSources();
  }
  function renderFiles(path) {
    const v = $("view-files");
    v.textContent = "";
    v.append(head(T("Files"), T("Your music folder, read right here in the browser. Nothing is uploaded.")));
    if (!Local.tree) {
      v.append(h("div", { class: "card wp-empty" },
        h("p", { text: Local.pending ? T("Your browser asks before Tryptify reads the folder again.") : T("Choose the folder your music is in. The folders inside it become your library.") }),
        Local.pending ? reconnectButton() : pickButton(T("Choose your music folder")),
        Local.canKeep ? null : h("p", { class: "muted small", text: T("This browser picks the folder again on each visit. Chrome and Edge remember it.") })));
      return;
    }
    let node = Local.tree;
    for (const p of path) { const d = node.byName.get(p); if (!d) break; node = d; }
    const crumbs = h("nav", { class: "wp-crumbs", "aria-label": T("Folder") }, h("a", { href: "#/files", text: Local.tree.name }));
    node.path.forEach((p, i) => crumbs.append(h("span", { "aria-hidden": "true", text: "/" }),
      h("a", { href: "#/files/" + node.path.slice(0, i + 1).map(encodeURIComponent).join("/"), text: p, "aria-current": i === node.path.length - 1 ? "page" : null })));
    const all = S.util.allFiles(node);
    v.append(crumbs, h("div", { class: "wp-row" }, all.length ? playButtons(all) : null,
      h("span", { class: "muted small", text: songs(all.length) }),
      h("span", { class: "wp-grow" }), pickButtonSmall()));
    if (node.dirs.length) {
      const ul = h("ul", { class: "wp-folders" });
      for (const d of node.dirs) {
        const n = S.util.allFiles(d).length;
        ul.append(h("li", null, h("a", { href: "#/files/" + d.path.map(encodeURIComponent).join("/") },
          h("span", { class: "wp-i", icon: "folder", "aria-hidden": "true" }), h("strong", { text: d.name }), h("small", { class: "muted", text: String(n) }))));
      }
      v.append(ul);
    }
    if (node.files.length) v.append(trackList(node.files, { album: false, numbered: true }));
  }
  function pickButtonSmall() {
    const b = pickButton(T("Change folder"));
    b.className = "btn";
    return b;
  }

  // ── Tryptify Cloud ──────────────────────────────────────────────────
  const cloud = { q: "", tracks: [], total: 0 };
  async function renderCloud() {
    const v = $("view-cloud");
    v.textContent = "";
    v.append(head("Tryptify Cloud", T("Your Cloud library, played straight from storage, through your AutoEQ.")));
    if (!(await Cloud.signedIn().catch(() => false))) {
      v.append(h("div", { class: "card wp-empty" }, h("p", { text: T("Sign in with the same account as the app, then come back.") }),
        h("a", { class: "btn primary", href: "/account", text: T("Sign in") })));
      return;
    }
    const input = h("input", { type: "search", class: "wp-input", placeholder: T("Search your Cloud"), value: cloud.q, "aria-label": T("Search your Cloud") });
    const form = h("form", { class: "wp-row", role: "search" }, input, h("button", { type: "submit", class: "btn", text: T("Search") }));
    const list = h("div");
    const more = h("button", { type: "button", class: "btn", text: T("More"), hidden: true });
    v.append(form, list, more);
    const load = async (reset) => {
      try {
        if (reset) { cloud.tracks = []; }
        const r = await Cloud.songs(cloud.tracks.length, cloud.q);
        cloud.tracks.push(...r.tracks);
        cloud.total = r.total;
        list.textContent = "";
        list.append(cloud.tracks.length ? trackList(cloud.tracks) : h("p", { class: "muted", text: cloud.q ? T("Nothing matches that.") : T("Nothing here yet. Upload music on the Account page or from the app.") }));
        more.hidden = cloud.tracks.length >= cloud.total;
      } catch (e) { list.textContent = ""; list.append(h("p", { class: "bad", text: e.message })); }
    };
    form.addEventListener("submit", (e) => { e.preventDefault(); cloud.q = input.value.trim(); load(true); });
    more.addEventListener("click", () => load(false));
    load(true);
  }

  // ── Trypt HiFi ──────────────────────────────────────────────────────
  const hifi = { q: "", result: null };
  function hifiForm() {
    const addr = h("input", { type: "url", class: "wp-input", id: "hifi-addr", placeholder: "https://your-trypt-hifi-server", value: HiFi.base || "", autocomplete: "off", spellcheck: "false" });
    const key = h("input", { type: "password", class: "wp-input", id: "hifi-key", placeholder: T("If the server asks for one"), value: HiFi.password, autocomplete: "current-password" });
    const qual = h("select", { class: "wp-input", id: "hifi-quality" });
    for (const [v, l] of [["HI_RES_LOSSLESS", T("Hi-Res Lossless (24-bit)")], ["LOSSLESS", T("Lossless (16-bit)")], ["HIGH", "AAC 320"], ["LOW", "AAC 96"]])
      qual.append(h("option", { value: v, text: l, selected: HiFi.quality === v }));
    qual.addEventListener("change", () => { HiFi.quality = qual.value; renderPath(); });
    const msg = h("p", { class: "small muted", role: "status" });
    const unlocked = !!HiFi.base;
    const form = h("form", { class: "wp-form" },
      h("label", { for: "hifi-addr", text: T("API URL") }), addr,
      h("label", { for: "hifi-key", text: T("Password") }), key,
      unlocked ? [h("label", { for: "hifi-quality", text: T("Stream quality") }), qual] : null,
      h("div", { class: "buttons" }, h("button", { type: "submit", class: "btn primary", text: unlocked ? T("Save") : T("Unlock") }),
        unlocked ? h("button", { type: "button", class: "btn", text: T("Remove"), onclick: () => {
          HiFi.base = null; HiFi.password = null; paintUnlock(); pill(); toast(T("Trypt HiFi removed.")); location.hash = "#/"; route();
        } }) : null),
      msg);
    // A URL is kept only once it answers, so a typo never unlocks an empty Trypt HiFi.
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const base = S.util.normalizeBase(addr.value);
      if (!base) { msg.textContent = T("That is not a URL."); return; }
      if (base.startsWith("http://") && !/^http:\/\/(localhost|127\.|\[::1\])/.test(base)) { msg.textContent = T("Use https://: this page is secure, so the browser blocks http:// addresses."); return; }
      msg.textContent = T("Checking…");
      try {
        const ms = await HiFi.test(base, key.value);
        HiFi.base = base;
        HiFi.password = key.value;
        pill(`Trypt HiFi · ${ms} ms`, true);
        paintUnlock();
        if (unlocked) msg.textContent = T("Saved. Answered in {ms} ms.", { ms });
        else { toast(T("Trypt HiFi unlocked.")); route(); }
      } catch (err) {
        msg.textContent = err.message === "Failed to fetch" ? T("No answer from that URL. Check it, and that the server is running.") : err.message;
      }
    });
    return form;
  }
  async function testHifi(msg) {
    try {
      const ms = await HiFi.test();
      pill(`Trypt HiFi · ${ms} ms`, true);
      if (msg) msg.textContent = T("Connected in {ms} ms.", { ms });
      return true;
    } catch (e) {
      pill(T("Trypt HiFi unreachable"), false);
      if (msg) msg.textContent = e.message === "Failed to fetch" ? T("No answer. Check the address, and that the server is running.") : e.message;
      return false;
    }
  }
  /** Trypt HiFi shows nowhere until its API URL is in. */
  function paintUnlock() {
    $("wp-side-hifi").hidden = !HiFi.base;
    $("wp-q").placeholder = HiFi.base ? T("Search your music and Trypt HiFi") : T("Search your music");
  }
  function pill(text, ok) {
    const p = $("wp-hifi-pill");
    p.hidden = !HiFi.base || !text;
    if (text) { p.textContent = text; p.classList.toggle("ok", !!ok); p.classList.toggle("bad", ok === false); }
  }
  function albumGrid(albums, base = "#/hifi/album/") {
    const ul = h("ul", { class: "wp-albums" });
    for (const a of albums) ul.append(h("li", null, h("a", { href: base + encodeURIComponent(a.id) },
      a.cover ? h("img", { src: a.cover, alt: "", loading: "lazy", width: 160, height: 160 }) : h("span", { class: "wp-cover-none" }),
      h("strong", { text: a.title }), h("span", { class: "muted small", text: [a.artist, a.year, a.hires ? "Hi-Res" : null].filter(Boolean).join(" · ") }))));
    return ul;
  }
  function renderHifi() {
    const v = $("view-hifi");
    v.textContent = "";
    v.append(head("Trypt HiFi", T("Streams from your own Trypt HiFi server, through your AutoEQ.")));
    if (!HiFi.base) { v.append(h("div", { class: "card" }, hifiForm())); return; }
    const input = h("input", { type: "search", class: "wp-input", placeholder: T("Search Trypt HiFi"), value: hifi.q, "aria-label": T("Search Trypt HiFi") });
    const form = h("form", { class: "wp-row", role: "search" }, input, h("button", { type: "submit", class: "btn", text: T("Search") }));
    const out = h("div");
    v.append(form, out);
    const show = () => {
      out.textContent = "";
      if (!hifi.result) return;
      const { albums, tracks } = hifi.result;
      if (!albums.length && !tracks.length) { out.append(h("p", { class: "muted", text: T("Nothing matches that.") })); return; }
      if (tracks.length) out.append(h("h2", { text: T("Songs") }), trackList(tracks));
      if (albums.length) out.append(h("h2", { text: T("Albums") }), albumGrid(albums));
    };
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      hifi.q = input.value.trim();
      if (!hifi.q) return;
      out.textContent = "";
      out.append(h("p", { class: "muted", text: T("Searching…") }));
      try { hifi.result = await HiFi.search(hifi.q); show(); } catch (err) { out.textContent = ""; out.append(h("p", { class: "bad", text: err.message })); }
    });
    show();
    v.append(h("details", { class: "card wp-details" }, h("summary", { text: T("Server and quality") }), hifiForm()));
  }
  async function renderHifiAlbum(id) {
    const v = $("view-hifi");
    v.textContent = "";
    v.append(h("p", { class: "muted", text: T("Loading the album…") }));
    try {
      const { album, tracks } = await HiFi.album(id);
      v.textContent = "";
      v.append(h("div", { class: "wp-album-head" },
        album.cover ? h("img", { src: album.cover, alt: "", width: 200, height: 200 }) : h("span", { class: "wp-cover-none" }),
        h("div", null, h("p", { class: "eyebrow", text: T("Album · Trypt HiFi") + (album.hires ? " · Hi-Res" : "") }), h("h1", { text: album.title }),
          h("p", { class: "muted", text: [album.artist, album.year, songs(tracks.length)].filter(Boolean).join(" · ") }), playButtons(tracks))),
      trackList(tracks, { album: false, numbered: true }));
    } catch (e) { v.textContent = ""; v.append(h("p", { class: "bad", text: e.message })); }
  }

  // ── The account's liked songs and playlists ─────────────────────────
  const signInCard = (text) => h("div", { class: "card wp-empty" },
    h("p", { text }), h("a", { class: "btn primary", href: "/account", text: T("Sign in") }));
  const needsHifi = () => {
    if (HiFi.base) return null;
    // {link} is where the link to the dashboard goes.
    const [before, after] = T("These songs need an API URL to play. Add one under Sources, on {link}.").split("{link}");
    return h("p", { class: "card wp-note" }, before, h("a", { href: "#/", text: T("the dashboard") }), after || "");
  };
  const library = { liked: null, playlists: null };
  async function renderLiked() {
    const v = $("view-liked");
    v.textContent = "";
    v.append(head(T("Liked songs"), T("The songs you liked in the app, kept in your account.")));
    if (!(await Cloud.signedIn().catch(() => false))) { v.append(signInCard(T("Sign in with the same account as the app to see your liked songs."))); return; }
    const box = h("div", null, h("p", { class: "muted", text: T("Loading…") }));
    v.append(needsHifi() || "", box);
    try {
      library.liked = await Library.liked();
      $("wp-count-liked").textContent = library.liked.length ? String(library.liked.length) : "";
      box.textContent = "";
      box.append(...(library.liked.length ? [playButtons(library.liked), trackList(library.liked)] : [h("p", { class: "muted", text: T("No liked songs yet. Tap the heart on a song in the app.") })]));
    } catch (e) { box.textContent = ""; box.append(h("p", { class: "bad", text: e.message })); }
  }
  async function renderPlaylists(id) {
    const v = $("view-playlists");
    v.textContent = "";
    if (!(await Cloud.signedIn().catch(() => false))) { v.append(head(T("Playlists"), null), signInCard(T("Sign in with the same account as the app to see your playlists."))); return; }
    const box = h("div", null, h("p", { class: "muted", text: T("Loading…") }));
    if (id) {
      v.append(box);
      try {
        const p = await Library.playlist(id);
        box.textContent = "";
        box.append(head(p.name, p.description || songs(p.tracks.length)), needsHifi() || "",
          ...(p.tracks.length ? [playButtons(p.tracks), trackList(p.tracks)] : [h("p", { class: "muted", text: T("This playlist is empty.") })]));
      } catch (e) { box.textContent = ""; box.append(h("p", { class: "bad", text: e.message })); }
      return;
    }
    v.append(head(T("Playlists"), T("The playlists you made in the app, kept in your account.")), box);
    try {
      library.playlists = await Library.playlists();
      $("wp-count-playlists").textContent = library.playlists.length ? String(library.playlists.length) : "";
      box.textContent = "";
      if (!library.playlists.length) { box.append(h("p", { class: "muted", text: T("No playlists yet. Make one in the app.") })); return; }
      const ul = h("ul", { class: "wp-folders" });
      for (const p of library.playlists) ul.append(h("li", null, h("a", { href: `#/playlists/${encodeURIComponent(p.id)}` },
        h("strong", { text: p.name }), h("small", { class: "muted", text: String(p.count) }))));
      box.append(ul);
    } catch (e) { box.textContent = ""; box.append(h("p", { class: "bad", text: e.message })); }
  }

  // ── Music server (Navidrome and other Subsonic servers) ────────────
  function subsonicForm() {
    const srv = Subsonic.server;
    const url = h("input", { type: "url", class: "wp-input", id: "sub-url", placeholder: "https://music.example.com", value: srv ? srv.url : "", autocomplete: "off", spellcheck: "false" });
    const user = h("input", { type: "text", class: "wp-input", id: "sub-user", value: srv ? srv.user : "", autocomplete: "username", spellcheck: "false" });
    const pass = h("input", { type: "password", class: "wp-input", id: "sub-pass", autocomplete: "current-password" });
    const msg = h("p", { class: "small muted", role: "status", text: T("Navidrome, Gonic, Airsonic and other Subsonic servers. Your password is not kept: only a token made from it.") });
    const form = h("form", { class: "wp-form" },
      h("label", { for: "sub-url", text: T("Server address") }), url,
      h("label", { for: "sub-user", text: T("Username") }), user,
      h("label", { for: "sub-pass", text: T("Password") }), pass,
      h("div", { class: "buttons" }, h("button", { type: "submit", class: "btn primary", text: srv ? T("Sign in again") : T("Connect") }),
        srv ? h("button", { type: "button", class: "btn", text: T("Remove"), onclick: () => { Subsonic.server = null; route(); } }) : null),
      msg);
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const base = S.util.normalizeBase(url.value);
      if (base && base.startsWith("http://") && !/^http:\/\/(localhost|127\.|\[::1\])/.test(base)) { msg.textContent = T("Use https://: this page is secure, so the browser blocks http:// servers."); return; }
      msg.textContent = T("Connecting…");
      try {
        await Subsonic.connect(url.value, user.value.trim(), pass.value);
        pass.value = "";
        msg.textContent = T("Connected.");
        route();
      } catch (err) {
        msg.textContent = err.message === "Failed to fetch" ? T("No answer. Check the address, and that the server allows this site (CORS).") : err.message;
      }
    });
    return form;
  }
  const subsonic = { tab: "albums", q: "" };
  async function renderSubsonic(kind, id) {
    const v = $("view-subsonic");
    v.textContent = "";
    if (!Subsonic.server) {
      v.append(head(T("Music server"), T("Your own Navidrome or other Subsonic server, through your AutoEQ.")), h("div", { class: "card" }, subsonicForm()));
      return;
    }
    const box = h("div", null, h("p", { class: "muted", text: T("Loading…") }));
    const fill = (...kids) => { box.textContent = ""; box.append(...kids); };
    const failed = (e) => fill(h("p", { class: "bad", text: e.message }));
    if (kind === "album" && id) {
      v.append(box);
      Subsonic.album(id).then(({ album, tracks }) => fill(h("div", { class: "wp-album-head" },
        album.cover ? h("img", { src: album.cover, alt: "", width: 200, height: 200 }) : h("span", { class: "wp-cover-none" }),
        h("div", null, h("p", { class: "eyebrow", text: T("Album · Music server") }), h("h1", { text: album.title }),
          h("p", { class: "muted", text: [album.artist, album.year, songs(tracks.length)].filter(Boolean).join(" · ") }), playButtons(tracks))),
        trackList(tracks, { album: false, numbered: true }))).catch(failed);
      return;
    }
    if (kind === "playlist" && id) {
      v.append(box);
      Subsonic.playlist(id).then((p) => fill(head(p.name, songs(p.tracks.length)), ...(p.tracks.length ? [playButtons(p.tracks), trackList(p.tracks)] : [h("p", { class: "muted", text: T("This playlist is empty.") })]))).catch(failed);
      return;
    }
    v.append(head(T("Music server"), new URL(Subsonic.server.url).host));
    const input = h("input", { type: "search", class: "wp-input", placeholder: T("Search your server"), value: subsonic.q, "aria-label": T("Search your server") });
    const form = h("form", { class: "wp-row", role: "search" }, input, h("button", { type: "submit", class: "btn", text: T("Search") }));
    const tabs = h("div", { class: "chips wp-tabs", role: "group", "aria-label": T("Show") });
    for (const [t, label] of [["albums", T("New albums")], ["recent", T("Played lately")], ["starred", T("Starred")], ["playlists", T("Playlists")]])
      tabs.append(h("button", { type: "button", "aria-pressed": String(subsonic.tab === t), text: label, onclick: () => { subsonic.tab = t; subsonic.q = ""; renderSubsonic(); } }));
    v.append(form, tabs, box, h("details", { class: "card wp-details" }, h("summary", { text: T("Server and sign-in") }), subsonicForm()));
    form.addEventListener("submit", (e) => { e.preventDefault(); subsonic.q = input.value.trim(); renderSubsonic(); });
    if (subsonic.q) {
      Subsonic.search(subsonic.q).then((r) => fill(...(r.tracks.length || r.albums.length
        ? [r.tracks.length ? [h("h2", { text: T("Songs") }), trackList(r.tracks)] : [], r.albums.length ? [h("h2", { text: T("Albums") }), albumGrid(r.albums, "#/subsonic/album/")] : []].flat()
        : [h("p", { class: "muted", text: T("Nothing matches that.") })]))).catch(failed);
    } else if (subsonic.tab === "starred") {
      Subsonic.starred().then((t) => fill(...(t.length ? [playButtons(t), trackList(t)] : [h("p", { class: "muted", text: T("No starred songs on this server.") })]))).catch(failed);
    } else if (subsonic.tab === "playlists") {
      Subsonic.playlists().then((ps) => {
        if (!ps.length) return fill(h("p", { class: "muted", text: T("No playlists on this server.") }));
        const ul = h("ul", { class: "wp-folders" });
        for (const p of ps) ul.append(h("li", null, h("a", { href: `#/subsonic/playlist/${encodeURIComponent(p.id)}` }, h("strong", { text: p.name }), h("small", { class: "muted", text: String(p.count) }))));
        fill(ul);
      }).catch(failed);
    } else {
      Subsonic.albums(subsonic.tab === "recent" ? "recent" : "newest").then((a) => fill(a.length ? albumGrid(a, "#/subsonic/album/") : h("p", { class: "muted", text: T("No albums yet.") }))).catch(failed);
    }
  }

  // ── Search, everywhere at once ──────────────────────────────────────
  async function renderSearch(q) {
    const v = $("view-search");
    v.textContent = "";
    v.append(head(`“${q}”`, null));
    if (!q) return;
    const words = q.toLowerCase().split(/\s+/).filter(Boolean);
    if (Local.tree) {
      const hits = S.util.allFiles(Local.tree).filter((t) => {
        const hay = `${t.title} ${t.artist} ${t.album} ${t.file}`.toLowerCase();
        return words.every((w) => hay.includes(w));
      }).slice(0, 50);
      v.append(h("h2", { text: T("Files") }), hits.length ? trackList(hits) : h("p", { class: "muted", text: T("Nothing in your folder.") }));
    }
    const section = (title) => { const box = h("div", null, h("h2", { text: title }), h("p", { class: "muted", text: T("Searching…") })); v.append(box); return box; };
    if (await Cloud.signedIn().catch(() => false)) {
      const box = section("Tryptify Cloud");
      Cloud.songs(0, q).then((r) => { box.lastChild.replaceWith(r.tracks.length ? trackList(r.tracks.slice(0, 20)) : h("p", { class: "muted", text: T("Nothing in your Cloud.") })); })
        .catch((e) => box.lastChild.replaceWith(h("p", { class: "bad", text: e.message })));
    }
    if (Subsonic.server) {
      const box = section(T("Music server"));
      Subsonic.search(q).then((r) => {
        const kids = [];
        if (r.tracks.length) kids.push(trackList(r.tracks));
        if (r.albums.length) kids.push(albumGrid(r.albums, "#/subsonic/album/"));
        box.lastChild.replaceWith(...(kids.length ? kids : [h("p", { class: "muted", text: T("Nothing on your server.") })]));
      }).catch((e) => box.lastChild.replaceWith(h("p", { class: "bad", text: e.message })));
    }
    if (HiFi.base) {
      const box = section("Trypt HiFi");
      HiFi.search(q).then((r) => {
        const kids = [];
        if (r.tracks.length) kids.push(trackList(r.tracks));
        if (r.albums.length) kids.push(albumGrid(r.albums));
        box.lastChild.replaceWith(...(kids.length ? kids : [h("p", { class: "muted", text: T("Nothing on Trypt HiFi.") })]));
      }).catch((e) => box.lastChild.replaceWith(h("p", { class: "bad", text: e.message })));
    }
  }
  $("wp-search").addEventListener("submit", (e) => {
    e.preventDefault();
    const q = $("wp-q").value.trim();
    if (q) location.hash = "#/search/" + encodeURIComponent(q);
  });

  // ── The dashboard ───────────────────────────────────────────────────
  const eqActive = () => eq.on && eq.bands.length > 0;
  function tile(id, title, ...kids) {
    const t = $(id);
    if (!t) return null;
    t.textContent = "";
    if (title) t.append(h("h2", { text: title }));
    t.append(...kids.filter(Boolean));
    return t;
  }
  function renderNow() {
    if ($("view-home").hidden) return;
    const t = current;
    $("tile-now").classList.toggle("is-empty", !t);
    if (!t) {
      tile("tile-now", null, h("p", { class: "eyebrow", text: T("Now playing") }), h("p", { class: "wp-now-title", text: T("Nothing yet") }),
        h("p", { class: "muted", text: T("Start with your music folder, your Cloud or your music server.") }),
        h("div", { class: "buttons" }, h("a", { class: "btn primary", href: "#/files", text: T("Files") }),
          h("a", { class: "btn", href: "#/cloud", text: "Tryptify Cloud" }), h("a", { class: "btn", href: "#/subsonic", text: T("Music server") }),
          HiFi.base ? h("a", { class: "btn", href: "#/hifi", text: "Trypt HiFi" }) : null));
      return;
    }
    const playBtn = h("button", { type: "button", class: "wp-icon wp-play", "data-act": "toggle", "aria-label": Eng.paused ? T("Play") : T("Pause"), icon: Eng.paused ? "play" : "pause" });
    tile("tile-now", null,
      t.cover ? h("img", { class: "wp-now-cover", src: t.cover, alt: "", width: 200, height: 200 }) : h("span", { class: "wp-now-cover wp-cover-none" }),
      h("div", { class: "wp-now-text" },
        h("div", { class: "wp-badges" }, ...[t.quality, T("from {source}", { source: SRC[t.src] }), eqActive() ? `AutoEQ · ${eq.name}` : null].filter(Boolean).map((x) => h("span", { text: x }))),
        h("p", { class: "wp-now-title", text: t.title }),
        h("p", { class: "muted", text: [t.artist, t.album].filter(Boolean).join(" · ") }),
        h("div", { class: "wp-progress" }, h("span", { id: "now-bar", style: `width: ${Eng.duration ? (Eng.time / Eng.duration) * 100 : 0}%` })),
        h("div", { class: "wp-controls" },
          h("button", { type: "button", class: "wp-icon", "data-act": "prev", "aria-label": T("Previous"), icon: "prev" }), playBtn,
          h("button", { type: "button", class: "wp-icon", "data-act": "next", "aria-label": T("Next"), icon: "next" }))));
  }
  function renderEqTile() {
    if ($("view-home").hidden) return;
    const sw = h("label", { class: "wp-switch" }, h("input", { type: "checkbox", checked: eq.on, disabled: !eq.bands.length, onchange: (e) => setEqOn(e.target.checked) }), h("span", { text: T("On") }));
    if (!eq.bands.length) {
      tile("tile-eq", "AutoEQ", h("p", { class: "muted", text: T("Pick your headphone once and everything plays corrected, with the same engine as the app.") }),
        h("a", { class: "btn primary", href: "#/autoeq", text: T("Pick a headphone") }));
      return;
    }
    const graph = h("div", { class: "wp-mini-graph" });
    drawEq(graph, eq.bands, { W: 1000, H: 260, labels: false });
    tile("tile-eq", "AutoEQ", h("div", { class: "wp-row" }, h("strong", { text: `${eq.name} → ${eq.target}` }), h("span", { class: "wp-grow" }), sw),
      h("p", { class: "muted small", text: T("{bands} · preamp −{db} dB", { bands: bandsOf(eq.bands.length), db: eq.preamp.toFixed(1) }) }), graph,
      h("a", { class: "btn", href: "#/autoeq", text: T("Change") }));
  }
  function renderPath() {
    if ($("view-home").hidden) return;
    const on = (x) => (x ? "on" : "");
    const rows = [
      [T("Source"), current ? SRC[current.src] : "—", !!current],
      [T("File"), current ? current.quality || "—" : "—", !!current],
      ["AutoEQ", eqActive() ? bandsOf(eq.bands.length) : T("off"), eqActive()],
      [T("Preamp"), eqActive() ? `−${eq.preamp.toFixed(1)} dB` : "0 dB", eqActive()],
      [T("Mono"), settings.mono ? T("on") : T("off"), settings.mono],
      [T("Speed"), settings.speed !== 1 && settings.preservePitch ? T("{speed}× · pitch kept", { speed: settings.speed.toFixed(2) }) : `${settings.speed.toFixed(2)}×`, settings.speed !== 1],
      [T("Volume"), `${Math.round(settings.volume * 100)} %`, true],
      [T("Output"), Eng.rate ? T("{rate} kHz · this browser", { rate: +(Eng.rate / 1000).toFixed(1) }) : T("this browser"), true],
    ];
    tile("tile-path", T("Signal path"), h("ol", { class: "wp-path" }, ...rows.map(([k, val, lit]) =>
      h("li", { class: on(lit) }, h("span", { class: "wp-dot", "aria-hidden": "true" }), h("span", { text: k }), h("span", { class: "muted", text: val })))));
  }
  function renderSources() {
    if ($("view-home").hidden) return;
    const folder = Local.tree && { count: Local.count, name: Local.tree.name };
    const local = h("li", null, h("div", null, h("strong", { text: T("Files") }),
      h("span", { class: "muted small", text: Local.tree
        ? (Local.count === 1 ? T("{count} song in “{name}” · read in the browser, never uploaded", folder) : T("{count} songs in “{name}” · read in the browser, never uploaded", folder))
        : Local.pending ? T("“{name}” · needs a tap to open again", { name: Local.pending.name }) : T("No folder chosen") })),
      Local.pending && !Local.tree ? reconnectButton() : Local.tree ? h("a", { class: "btn", href: "#/files", text: T("Open") }) : pickButton(T("Choose folder")));
    const cloudLi = h("li", null, h("div", null, h("strong", { text: "Tryptify Cloud" }), h("span", { class: "muted small", text: T("Checking…") })), h("a", { class: "btn", href: "#/cloud", text: T("Open") }));
    Cloud.signedIn().then((s) => { cloudLi.querySelector(".small").textContent = s ? T("Signed in") : T("Signed out · sign in on the Account page"); }).catch(() => {});
    const subLi = h("li", null, h("div", null, h("strong", { text: T("Music server") }),
      h("span", { class: "muted small", text: Subsonic.server ? T("{user} on {host}", { user: Subsonic.server.user, host: new URL(Subsonic.server.url).host }) : T("Navidrome or another Subsonic server") })),
      h("a", { class: Subsonic.server ? "btn" : "btn primary", href: "#/subsonic", text: Subsonic.server ? T("Open") : T("Connect") }));
    const hifiLi = HiFi.base
      ? h("li", { class: "wp-src-hifi" }, h("div", null, h("strong", { text: "Trypt HiFi" }), h("span", { class: "muted small", text: new URL(HiFi.base).host })), hifiForm())
      : h("li", { class: "wp-src-hifi" }, h("details", { class: "wp-unlock" }, h("summary", { text: T("Add an API URL") }), hifiForm()));
    tile("tile-sources", T("Sources"), h("ul", { class: "wp-sources" }, local, cloudLi, subLi, hifiLi));
  }
  function renderPlayback() {
    if ($("view-home").hidden) return;
    const toggle = (label, key, fn, hint) => h("label", { class: "wp-set" }, h("span", null, label, hint ? h("small", { class: "muted", text: hint }) : null),
      h("input", { type: "checkbox", checked: settings[key], onchange: (e) => { settings[key] = e.target.checked; saveSettings(); fn && fn(settings[key]); renderPath(); } }));
    const speedVal = h("span", { class: "wp-mono", text: `${settings.speed.toFixed(2)}×` });
    const speed = h("input", { type: "range", min: 0.25, max: 4, step: 0.05, value: settings.speed, "aria-label": T("Speed") });
    speed.addEventListener("input", () => { settings.speed = Number(speed.value); speedVal.textContent = `${settings.speed.toFixed(2)}×`; Eng.setSpeed(settings.speed); });
    speed.addEventListener("change", () => { saveSettings(); position(); renderPath(); publish(); });
    tile("tile-playback", T("Playback"),
      toggle(T("Gapless"), "gapless", (on) => on && preloadNext(), T("Loads the next song early so it starts right away")),
      toggle(T("Mono"), "mono", (on) => Eng.setMono(on)),
      toggle(T("Keep pitch when the speed changes"), "preservePitch", (on) => Eng.setPreservePitch(on)),
      h("label", { class: "wp-set" }, h("span", { text: T("Speed") }), speed, speedVal,
        h("button", { type: "button", class: "btn wp-small", text: "1×", onclick: () => { settings.speed = 1; Eng.setSpeed(1); saveSettings(); renderPlayback(); renderPath(); } })));
  }
  function renderNext() {
    if ($("view-home").hidden) return;
    const list = upcoming().slice(0, 5);
    tile("tile-next", T("Up next"), list.length
      ? h("ol", { class: "wp-next" }, ...list.map((t) => h("li", null, h("strong", { text: t.title }), h("span", { class: "muted small", text: [t.artist, SRC[t.src], t.quality].filter(Boolean).join(" · ") }))))
      : h("p", { class: "muted", text: T("Nothing after this song.") }),
      h("button", { type: "button", class: "btn", "data-act": "queue", text: T("Show the queue") }));
  }
  function renderHome() { renderNow(); renderConnectTile(); renderEqTile(); renderPath(); renderSources(); renderPlayback(); renderNext(); }

  // ── AutoEQ ──────────────────────────────────────────────────────────
  const NS = "http://www.w3.org/2000/svg";
  const svgNode = (tag, attrs, parent) => {
    const e = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    parent && parent.append(e);
    return e;
  };
  /** The EQ curve alone (the AutoEQ page's "EQ curves, no preamp"), in the page's graph style. */
  function drawEq(host, bands, { W = 1000, H = 300, labels = true } = {}) {
    host.textContent = "";
    if (!E || !bands.length) return;
    const P = labels ? { l: 44, r: 14, t: 14, b: 30 } : { l: 6, r: 6, t: 8, b: 8 };
    const N = 220, freqs = [];
    for (let i = 0; i < N; i++) freqs.push(20 * Math.pow(1000, i / (N - 1)));
    const vals = E.eqCurve(bands, freqs);
    const lo = Math.min(-5, Math.floor((Math.min(...vals) - 1) / 5) * 5), hi = Math.max(10, Math.ceil((Math.max(...vals) + 1) / 5) * 5);
    const svg = svgNode("svg", { viewBox: `0 0 ${W} ${H}`, class: "graph", role: "img", "aria-label": T("The EQ curve, without preamp") }, host);
    const x = (f) => P.l + ((Math.log10(f) - Math.log10(20)) / 3) * (W - P.l - P.r);
    const y = (db) => P.t + ((hi - db) / (hi - lo)) * (H - P.t - P.b);
    for (let v = lo; v <= hi; v += 5) {
      svgNode("line", { x1: P.l, x2: W - P.r, y1: y(v), y2: y(v), class: "g-h" }, svg);
      if (labels) svgNode("text", { x: P.l - 8, y: y(v) + 4, class: "g-tick", "text-anchor": "end" }, svg).textContent = String(v);
    }
    for (const f of [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000]) {
      svgNode("line", { x1: x(f), x2: x(f), y1: P.t, y2: H - P.b, class: "g-v major" }, svg);
      if (labels) svgNode("text", { x: x(f), y: H - P.b + 18, class: "g-tick", "text-anchor": "middle" }, svg).textContent = f >= 1000 ? `${f / 1000}k` : String(f);
    }
    svgNode("line", { x1: P.l, x2: W - P.r, y1: y(0), y2: y(0), class: "g-zero" }, svg);
    svgNode("path", { d: freqs.map((f, i) => `${i ? "L" : "M"}${x(f).toFixed(1)},${y(vals[i]).toFixed(1)}`).join(""), class: "g-line s-sq" }, svg);
  }
  const BAND_NAMES = { PEAKING: T("Peak"), LOWSHELF: T("Low shelf"), HIGHSHELF: T("High shelf") };
  function bandTable(bands) {
    const body = $("try-table").querySelector("tbody");
    body.textContent = "";
    bands.forEach((b, i) => body.append(h("tr", null, ...[i + 1, BAND_NAMES[b.type],
      b.freq >= 1000 ? `${(b.freq / 1000).toFixed(2)} kHz` : `${Math.round(b.freq)} Hz`,
      `${b.gain > 0 ? "+" : ""}${b.gain.toFixed(1)} dB`, b.q.toFixed(2)].map((v) => h("td", { text: String(v) })))));
  }
  function setEqOn(on) {
    eq.on = !!on && eq.bands.length > 0;
    saveEq();
    Eng.setEq(eq);
    renderEqState();
  }
  function renderEqState() {
    const has = eq.bands.length > 0;
    $("wp-eq-on").checked = eqActive();
    $("wp-eq-on").disabled = !has;
    $("wp-eq-side").textContent = eqActive() ? T("On") : "";
    const fit = has && { name: eq.name, target: eq.target, bands: bandsOf(eq.bands.length), db: eq.preamp.toFixed(1) };
    $("wp-eq-active").textContent = has
      ? (eq.on ? T("Playing everything through {name} → {target}, {bands}, preamp −{db} dB.", fit) : T("Off. Saved: {name} → {target}, {bands}, preamp −{db} dB.", fit))
      : T("Pick a headphone and Tryptify fits an EQ to it with the app's own engine, then plays everything through it.");
    $("wp-eq-card").hidden = !has;
    $("wp-bands").hidden = !has;
    if (has) { drawEq($("wp-eq-graph"), eq.bands); bandTable(eq.bands); }
    const o = eq.opts;
    $("wp-fr-caption").textContent = o ? T("{bands} · up to {khz} kHz · {method}", { bands: bandsOf(eq.bands.length), khz: o.maxFrequency / 1000, method: o.algorithm === "SHELF_ENDS" ? T("shelf ends") : T("peaking") }) : "";
    renderBar();
    renderEqTile();
    renderPath();
  }
  let restoring = false, restored = false;
  const chipSync = [];
  function initAutoEq() {
    const host = $("wp-chips");
    for (const [id, label] of [["hp-max", T("Highest EQ frequency")], ["hp-bands", T("Band count")], ["hp-alg", T("Method")]]) {
      const sel = $(id);
      if (!sel) continue;
      const chips = h("div", { class: "chips", role: "group", "aria-label": label });
      const buttons = [...sel.options].map((o) => h("button", { type: "button", text: o.textContent,
        onclick: () => { sel.value = o.value; sel.dispatchEvent(new Event("change")); sync(); } }));
      const sync = () => buttons.forEach((b, i) => b.setAttribute("aria-pressed", String(sel.selectedIndex === i)));
      chipSync.push(sync);
      sel.addEventListener("change", sync);
      sync();
      chips.append(...buttons);
      host.append(h("div", { class: "rank-group" }, h("span", { class: "rank-label", text: label }), chips));
    }
    $("wp-eq-on").addEventListener("change", (e) => setEqOn(e.target.checked));
    $("tryout").addEventListener("tryptify:fit", (e) => {
      const d = e.detail;
      eq = { on: restoring ? eq.on : true, bands: d.bands, preamp: d.preamp, name: d.name, target: d.target,
        phone: d.phone, source: d.source, targetId: d.targetId, smooth: d.smooth, opts: d.opts };
      restoring = false;
      saveEq();
      Eng.setEq(eq);
      renderEqState();
    });
    renderEqState();
  }
  function renderAutoEq() {
    // Fit the saved headphone again, so its graphs show; only when this view opens (the headphone list is large).
    if (restored || !eq.phone || !window.TryptifyTryout) return;
    restored = true;
    restoring = true;
    window.TryptifyTryout.restore(eq).then(() => chipSync.forEach((f) => f())).catch(() => { restoring = false; });
  }

  // ── Tryptify Connect: the listener's other devices ───────────────────
  // A song travels as a descriptor any device can find again: a TIDAL id for
  // Trypt HiFi, the Cloud's song id, the music server's id. Covers travel only
  // as plain https links: a music server's carry its sign-in, and blob: ones
  // are this browser's alone. Files on a computer stay on that computer.
  const shareable = (u) => (typeof u === "string" && /^https:\/\//.test(u) && !/[?&](t|s|p|u)=/.test(u) ? u : null);
  function descriptor(t) {
    return {
      src: t.src, id: t.src === "local" ? t.key : String(t.ref.id), title: t.title, artist: t.artist || "", album: t.album || "",
      duration: t.duration || (t === current ? Eng.duration : 0) || null, quality: t.quality || "",
      cover: t.src === "subsonic" ? null : shareable(t.cover), coverArt: (t.ref && t.ref.coverArt) || null,
    };
  }
  function trackFrom(d) {
    if (!d) return null;
    const base = { title: d.title || "Untitled", artist: d.artist || "", album: d.album || "", n: null, duration: d.duration || null, quality: d.quality || "", cover: d.cover || null };
    if (d.src === "hifi") return HiFi.base ? { ...base, key: `hifi:${d.id}`, src: "hifi", ref: { id: d.id, hires: true } } : null;
    if (d.src === "cloud") return { ...base, key: `cloud:${d.id}`, src: "cloud", cover: null, ref: { id: d.id, hasCover: true } };
    if (d.src === "subsonic") return Subsonic.server ? Subsonic.withCover({ ...base, key: `subsonic:${d.id}`, src: "subsonic", ref: { id: d.id, coverArt: d.coverArt } }) : null;
    if (d.src === "local" && Local.tree) return S.util.allFiles(Local.tree).find((t) => t.key === d.id) || null;
    return null;
  }
  const whyNot = (d, from) => (d.src === "local" ? T("“{title}” is a file on {name}, so it stays there.", { title: d.title, name: from })
    : d.src === "hifi" ? T("“{title}” needs an API URL in this browser first.", { title: d.title })
    : d.src === "subsonic" ? T("“{title}” is on a music server this browser is not signed in to.", { title: d.title }) : T("“{title}” cannot play here.", { title: d.title }));
  const nameOf = (id, fallback) => ((C && C.devices.find((d) => d.id === id)) || {}).name || fallback || T("your other device");

  let playSince = 0; // when this browser last started playing, by the server's clock
  function publish() {
    if (!C || !current) return;
    // Read when it is sent (and again on reconnecting), so the second is the one at that moment.
    C.publish(() => current
      ? { playing: !Eng.paused && !stalled, track: descriptor(current), position: Eng.time, rate: settings.speed, since: playSince }
      : null);
  }
  let transferTimer = 0;
  /** Plays what is playing on [id] instead (this browser included). */
  function playOn(id) {
    const n = C.now;
    const holder = n && C.online(n.device) ? n.device : null;
    if (!C.transfer(id, !holder)) { toast(T("Tryptify Connect is not connected."), true); return; }
    toggleDevices(false);
    clearTimeout(transferTimer);
    transferTimer = setTimeout(() => toast(T("No answer from {name}. It may be asleep; try again.", { name: nameOf(holder || id) }), true), 7000);
  }
  function remote(action, value) {
    const n = C && C.elsewhere();
    if (n && C.online(n.device)) C.command(n.device, action, value);
  }

  // ── Following another device ────────────────────────────────────────
  /**
   * Another device's song while this browser plays nothing (or is paused):
   * the bar shows it, its time runs in step, and the bar steers it, as
   * Spotify's does. Null when this browser is the one playing.
   */
  function remoteNow() {
    if (!C || (current && !Eng.paused)) return null;
    const n = C.elsewhere();
    return n && C.online(n.device) ? n : null;
  }
  let seekHold = null; // { value, until }: a seek just sent, shown until that device answers
  function seekRemote(seconds) {
    remote("seek", seconds);
    seekHold = { value: seconds, until: Date.now() + 3000 };
    followRemote();
  }
  let following = false;
  /** Twice a second: the bar and the strip's clock, at the other device's second. */
  function followRemote() {
    const n = remoteNow();
    if (!n) {
      if (following) { following = false; restoreBar(); }
      const strip = $("wp-elsewhere-time"), e = C && C.elsewhere();
      if (strip && e) strip.textContent = timeOf(e);
      return;
    }
    if (!following) { following = true; renderBar(); }
    if (seekHold && Date.now() > seekHold.until) seekHold = null;
    const dur = n.track.duration || 0, at = seekHold ? seekHold.value : C.positionOf(n);
    const s = $("bar-seek");
    s.max = String(dur);
    if (!dragging) { s.value = String(at); $("bar-time").textContent = clock(at); }
    $("bar-dur").textContent = clock(dur);
    const strip = $("wp-elsewhere-time");
    if (strip) strip.textContent = timeOf(n, at);
  }
  const timeOf = (n, at = C.positionOf(n)) => (n.track.duration ? `${clock(at)} / ${clock(n.track.duration)}` : clock(at));
  /** Back to this browser's own song. */
  function restoreBar() {
    const s = $("bar-seek");
    s.max = String(Eng.duration || 0);
    s.value = String(Eng.time || 0);
    $("bar-time").textContent = clock(Eng.time || 0);
    $("bar-dur").textContent = clock(Eng.duration || 0);
    renderBar();
  }
  if (C) setInterval(followRemote, 500);

  // The bar's cover opens the full-screen player too.
  for (const id of ["bar-cover", "bar-cover-none"]) $(id).addEventListener("click", () => window.TryptifyFull && window.TryptifyFull.open());

  /** For the full-screen player (wp-full.js): what the bar shows, and seeking it. */
  window.TryptifyPlayer = {
    /** This browser's song, or the other device's it follows; null with neither. */
    shown() {
      const r = remoteNow();
      if (r) {
        const t = r.track;
        return { key: `${t.src}:${t.id}`, title: t.title || T("Untitled"), artist: t.artist || "", album: t.album || "", duration: t.duration || 0,
          cover: t.cover || null, position: seekHold ? seekHold.value : C.positionOf(r), playing: r.playing, local: false, on: nameOf(r.device, r.name) };
      }
      if (!current) return null;
      return { key: current.key, title: current.title, artist: current.artist || "", album: current.album || "", duration: Eng.duration || current.duration || 0,
        cover: current.cover || null, position: Eng.time, playing: !Eng.paused, local: true, on: null };
    },
    seek(t) {
      if (remoteNow()) { seekRemote(t); return; }
      seekTo(t);
    },
  };

  if (C) {
    // Asked to give it up: our place to the second, and what comes next.
    C.on("release", (m) => {
      C.handoff(m.to, current ? { playing: !Eng.paused, track: descriptor(current), position: Eng.time, rate: settings.speed, since: playSince } : null,
        upcoming().slice(0, 100).map(descriptor));
      if (current && !Eng.paused) { Eng.pause(); toast(T("Now playing on {name}.", { name: m.toName || nameOf(m.to) })); }
      clearTimeout(transferTimer);
    });
    // Handed it: carry on from that second.
    C.on("takeover", (m) => {
      clearTimeout(transferTimer);
      const n = m.now, from = nameOf(m.from, n && n.name);
      if (!n || !n.track) { toast(T("Nothing was playing there.")); return; }
      const first = trackFrom(n.track);
      if (!first) { toast(whyNot(n.track, from), true); return; }
      queue = [first, ...(m.queue || []).map(trackFrom).filter(Boolean)];
      order = [...queue.keys()];
      pos = 0;
      start(n.playing, C.positionOf(n));
      toast(T("Playing here, from {name}.", { name: from }));
    });
    // Steered from another device.
    C.on("command", (m) => {
      if (m.action === "play") (current ? Eng.play() : Promise.resolve()).catch(fail);
      else if (m.action === "pause") Eng.pause();
      else if (m.action === "next") next(false);
      else if (m.action === "prev") prev();
      else if (m.action === "seek" && m.value != null) seekTo(m.value);
    });
    // Another device started playing: this one makes way, as Spotify does.
    C.on("now", (n) => {
      // Only for a device that started after this one did: a late message from an earlier start does not count.
      if (n && n.playing && n.device !== C.id && current && !Eng.paused && (n.since || n.at) >= playSince) { Eng.pause(); toast(T("{name} started playing, so this browser paused.", { name: nameOf(n.device, n.name) })); }
      seekHold = null; // the device answered with where it is
      renderConnect();
      renderBar();
      followRemote();
    });
    C.on("devices", renderConnect);
    C.on("status", renderConnect);
    C.on("error", (m) => { clearTimeout(transferTimer); toast(m.message || T("Tryptify Connect said no."), true); });
  }

  /** The strip over the bar ("Playing on Pixel 8"), the Devices button and its list. */
  function renderConnect() {
    if (!C) return;
    const btn = $("bar-devices"), others = C.devices.filter((d) => d.id !== C.id);
    btn.classList.toggle("lit", others.length > 0);
    btn.setAttribute("aria-label", others.length === 1 ? T("Devices: {count} other online", { count: 1 }) : others.length ? T("Devices: {count} others online", { count: others.length }) : T("Devices"));
    const strip = $("wp-elsewhere"), n = C.elsewhere();
    const show = !!n && (Eng.paused || !current);
    strip.hidden = !show;
    document.body.classList.toggle("has-elsewhere", show);
    if (show) {
      const online = C.online(n.device), playing = online && n.playing, name = nameOf(n.device, n.name);
      strip.textContent = "";
      const bar = h("span");
      const at = C.positionOf(n), dur = n.track.duration || 0;
      // The other device's progress moves by CSS alone: no timer ticks here for it.
      if (dur) bar.style.cssText = `--from: ${(Math.min(at, dur) / dur) * 100}%; --left: ${Math.max(0.1, (dur - at) / (n.rate || 1))}s; --state: ${playing ? "running" : "paused"}`;
      put(strip,
        h("span", { class: "wp-i", icon: "phone", "aria-hidden": "true" }),
        h("div", { class: "wp-elsewhere-text" },
          h("strong", { text: playing ? T("Playing on {name}", { name }) : T("Last played on {name}", { name }) }),
          h("span", { text: [n.track.title, n.track.artist].filter(Boolean).join(" · ") })),
        h("span", { class: "wp-elsewhere-time", id: "wp-elsewhere-time", text: timeOf(n, at) }),
        online ? h("div", { class: "wp-controls" },
          h("button", { type: "button", class: "wp-icon", icon: "prev", "aria-label": T("Previous on {name}", { name }), onclick: () => remote("prev") }),
          h("button", { type: "button", class: "wp-icon", icon: n.playing ? "pause" : "play", "aria-label": n.playing ? T("Pause on {name}", { name }) : T("Play on {name}", { name }), onclick: () => remote(n.playing ? "pause" : "play") }),
          h("button", { type: "button", class: "wp-icon", icon: "next", "aria-label": T("Next on {name}", { name }), onclick: () => remote("next") })) : null,
        h("button", { type: "button", class: "btn wp-small", text: T("Play here"), onclick: () => playOn(C.id) }),
        dur ? h("div", { class: "wp-elsewhere-bar", "aria-hidden": "true" }, bar) : null);
    }
    if (!$("wp-devices").hidden) renderDevices();
    renderConnectTile();
  }
  /** The other device's progress, moved by CSS alone: no timer ticks here for it. */
  function progressOf(n) {
    const bar = h("span"), dur = n.track.duration || 0;
    if (!dur) return null;
    const at = C.positionOf(n), playing = C.online(n.device) && n.playing;
    bar.style.cssText = `--from: ${(Math.min(at, dur) / dur) * 100}%; --left: ${Math.max(0.1, (dur - at) / (n.rate || 1))}s; --state: ${playing ? "running" : "paused"}`;
    return h("div", { class: "wp-connect-bar", "aria-hidden": "true" }, bar);
  }
  /** Tryptify Connect on the dashboard: what plays elsewhere, with its controls and Play here, and every device. */
  function renderConnectTile() {
    if (!C || $("view-home").hidden) return;
    const n = C.elsewhere(), box = h("div");
    let card = null;
    if (n && C.status === "on") {
      const online = C.online(n.device), name = nameOf(n.device, n.name);
      card = h("div", { class: "wp-connect-card" },
        h("p", { class: "eyebrow", text: online && n.playing ? T("Playing on {name}", { name }) : T("Last played on {name}", { name }) }),
        h("strong", { text: n.track.title || T("Untitled") }),
        h("span", { class: "muted small", text: [n.track.artist, n.track.quality].filter(Boolean).join(" · ") }),
        progressOf(n),
        h("div", { class: "wp-row" },
          online ? h("div", { class: "wp-controls" },
            h("button", { type: "button", class: "wp-icon", icon: "prev", "aria-label": T("Previous on {name}", { name }), onclick: () => remote("prev") }),
            h("button", { type: "button", class: "wp-icon", icon: n.playing ? "pause" : "play", "aria-label": n.playing ? T("Pause on {name}", { name }) : T("Play on {name}", { name }), onclick: () => remote(n.playing ? "pause" : "play") }),
            h("button", { type: "button", class: "wp-icon", icon: "next", "aria-label": T("Next on {name}", { name }), onclick: () => remote("next") })) : null,
          h("span", { class: "wp-grow" }),
          h("button", { type: "button", class: "btn primary wp-small", text: T("Play here"), onclick: () => playOn(C.id) })));
    }
    devicesInto(box);
    tile("tile-connect", "Tryptify Connect", card, box);
  }
  function toggleDevices(force) {
    const p = $("wp-devices");
    p.hidden = force != null ? !force : !p.hidden;
    $("bar-devices").setAttribute("aria-expanded", String(!p.hidden));
    if (!p.hidden) renderDevices();
  }
  function renderDevices() { devicesInto($("wp-devices-list")); }
  /** Your devices into [box]: this browser first, each a tap to move the music there. */
  function devicesInto(box) {
    box.textContent = "";
    const st = C ? C.status : "off";
    if (st === "signed-out") { box.append(h("p", { class: "muted", text: T("Sign in to see your other devices and move music between them.") }), h("a", { class: "btn primary", href: "/account", text: T("Sign in") })); return; }
    if (st === "replaced") { box.append(h("p", { class: "muted", text: T("Tryptify Connect is on in another tab of this browser.") }), h("button", { type: "button", class: "btn", text: T("Use it in this tab"), onclick: () => C.reconnect() })); return; }
    if (st !== "on") { box.append(h("p", { class: "muted", text: T("Connecting…") })); return; }
    // The device playing counts only while it is online; a song left on one that is gone can still be resumed here.
    const n = C.now, holder = n && n.playing && C.online(n.device) ? n.device : null;
    const list = [...C.devices].sort((a, b) => (a.id === C.id ? -1 : b.id === C.id ? 1 : a.name.localeCompare(b.name)));
    const ul = h("ul", { class: "wp-device-list" });
    for (const d of list) {
      const here = d.id === C.id, active = d.id === holder;
      const can = here ? !!(n && n.track && n.device !== C.id) : !active && (!!holder || !!current);
      const detail = [here ? T("This browser") : d.kind === "android" ? T("Tryptify app") : "tryptify.com", active ? T("Playing") : null].filter(Boolean).join(" · ");
      ul.append(h("li", null, h("button", { type: "button", class: "wp-device" + (active ? " is-active" : ""), disabled: !can,
        onclick: () => playOn(d.id) },
        h("span", { class: "wp-i", icon: d.kind === "android" ? "phone" : "devices", "aria-hidden": "true" }),
        h("span", { class: "wp-device-text" }, h("strong", { text: d.name }), h("small", { class: "muted", text: detail })),
        active ? null : h("span", { class: "wp-device-go", text: here ? T("Play here") : T("Play there") }))));
    }
    put(box, ul, list.length < 2 ? h("p", { class: "muted small", text: T("Open Tryptify on another device, signed in to the same account, and it shows up here.") }) : null);
  }

  // ── Pages ───────────────────────────────────────────────────────────
  const ROUTES = ["home", "liked", "playlists", "files", "cloud", "hifi", "subsonic", "search", "autoeq"];
  function route() {
    const parts = location.hash.replace(/^#\/?/, "").split("/").filter(Boolean).map((p) => { try { return decodeURIComponent(p); } catch (_) { return p; } });
    const asked = ROUTES.includes(parts[0]) ? parts[0] : "home";
    const view = asked === "hifi" && !HiFi.base ? "home" : asked;
    for (const r of ROUTES) $("view-" + r).hidden = r !== view;
    for (const a of document.querySelectorAll(".wp-side a[data-route]")) {
      if (a.dataset.route === view) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
    }
    if (view === "home") renderHome();
    else if (view === "liked") renderLiked();
    else if (view === "playlists") renderPlaylists(parts[1]);
    else if (view === "files") renderFiles(parts.slice(1));
    else if (view === "cloud") renderCloud();
    else if (view === "subsonic") renderSubsonic(parts[1], parts[2]);
    else if (view === "hifi") parts[1] === "album" && parts[2] ? renderHifiAlbum(parts[2]) : renderHifi();
    else if (view === "search") { $("wp-q").value = parts.slice(1).join("/"); renderSearch(parts.slice(1).join("/")); }
    else if (view === "autoeq") renderAutoEq();
  }
  addEventListener("hashchange", () => { route(); window.scrollTo(0, 0); });

  // ── Black or white, shared with the rest of the site ────────────────
  const themeBtn = $("wp-theme");
  const paintTheme = () => setIcon(themeBtn, document.documentElement.dataset.design === "white" ? "moon" : "sun");
  themeBtn.addEventListener("click", () => {
    const next = document.documentElement.dataset.design === "white" ? "dark" : "white";
    document.documentElement.dataset.design = next;
    try { localStorage.setItem("tryptify-theme", next); } catch (_) {}
    paintTheme();
  });
  paintTheme();

  // Space plays and pauses; "/" goes to search. Not while typing.
  document.addEventListener("keydown", (e) => {
    if (e.target.closest("input, select, textarea, [contenteditable]") || e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key === " " && !e.target.closest("button, a")) { e.preventDefault(); current ? Eng.toggle().catch(fail) : playFirst(); }
    else if (e.key === "/") { e.preventDefault(); $("wp-q").focus(); }
  });

  // ── Glyph holes, as the app's mini player ───────────────────────────
  // The bar's icons are cut out of its glass: its tint is a layer of its own
  // (.wp-bar-tint) with each icon's shape masked out of it, so through the
  // icon shows the frosted page under the glass, lit in the icon's own colour
  // (.wp-bar-holes, the green of a shuffle that is on). The play disc keeps
  // its drawn icon, as it is glass of its own. The real icons stay in their
  // buttons, unseen, for the buttons' size and for screen readers' sake.
  function punchHoles(bar) {
    const tint = h("div", { class: "wp-bar-tint", "aria-hidden": "true" });
    const lit = h("div", { class: "wp-bar-holes", "aria-hidden": "true" });
    bar.prepend(tint, lit);
    bar.classList.add("holed");
    const url = (svg) => `url("data:image/svg+xml,${encodeURIComponent(svg)}")`;
    const ATTRS = ["fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin"];
    // One icon, its colour set, placed where it is drawn on the bar.
    const icon = (svg, colour, x, y, w, ht) => {
      const attrs = ATTRS.filter((a) => svg.hasAttribute(a))
        .map((a) => ` ${a}="${svg.getAttribute(a).replace(/currentColor/g, colour)}"`).join("");
      return `<svg x="${x}" y="${y}" width="${w}" height="${ht}" viewBox="${svg.getAttribute("viewBox") || "0 0 24 24"}"${attrs}>` +
        `${svg.innerHTML.replace(/currentColor/g, colour)}</svg>`;
    };
    const draw = () => {
      const r = bar.getBoundingClientRect();
      const W = Math.round(r.width), H = Math.round(r.height);
      if (!W || !H) return;
      let cut = "", glow = "";
      for (const b of bar.querySelectorAll(".wp-icon:not(.wp-play)")) {
        const svg = b.querySelector("svg");
        const s = svg && svg.getBoundingClientRect();
        if (!s || !s.width) continue;
        const x = (s.left - r.left).toFixed(2), y = (s.top - r.top).toFixed(2), w = s.width.toFixed(2), ht = s.height.toFixed(2);
        cut += icon(svg, "#000", x, y, w, ht);
        glow += icon(svg, getComputedStyle(b).color, x, y, w, ht);
      }
      const box = `xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"`;
      const mask = url(`<svg ${box}><defs><mask id="m"><rect width="${W}" height="${H}" fill="#fff"/>${cut}</mask></defs>` +
        `<rect width="${W}" height="${H}" fill="#fff" mask="url(#m)"/></svg>`);
      tint.style.webkitMaskImage = mask;
      tint.style.maskImage = mask;
      lit.style.backgroundImage = url(`<svg ${box}>${glow}</svg>`);
    };
    let queued = false;
    const later = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(() => { queued = false; draw(); });
    };
    new ResizeObserver(later).observe(bar);
    // An icon swapped (mute), switched on (shuffle, repeat) or hidden moves a hole.
    new MutationObserver(later).observe(bar, { subtree: true, childList: true, attributes: true, attributeFilter: ["aria-pressed", "hidden", "data-icon"] });
    // A switch of design fades the icons' colour over 0.35 s (styles.css,
    // .theme-fade): drawn again once it has, or the holes keep the old colour.
    new MutationObserver(() => { later(); setTimeout(later, 450); })
      .observe(document.documentElement, { attributes: true, attributeFilter: ["data-design"] });
    addEventListener("resize", later);
    if (document.fonts) document.fonts.ready.then(later);
    later();
  }

  // ── The bar's glass ─────────────────────────────────────────────────
  // The lens and the glint from js/glass.js: the site's menu glass. The room kept
  // free under the pages (--bar-h) follows the bar's real height, which on a
  // phone depends on its content, plus the gap it floats in.
  (() => {
    const bar = $("wp-bar"), shell = document.querySelector(".wp");
    if (window.TryptifyGlass) {
      TryptifyGlass.glint(bar); TryptifyGlass.giveLens(bar);
      // Playing on another device: the same glass, over the bar.
      const strip = $("wp-elsewhere");
      TryptifyGlass.glint(strip); TryptifyGlass.giveLens(strip);
    }
    punchHoles(bar);
    const room = () => shell.style.setProperty("--bar-h", `${bar.offsetHeight + (parseFloat(getComputedStyle(bar).bottom) || 0)}px`);
    new ResizeObserver(room).observe(bar);
    addEventListener("resize", room);
    room();
  })();

  // ── The timeline: the app's glass tube ─────────────────────────────
  // One outline, not a track and a thumb: a thin tube that swells smoothly
  // around the playhead (a raised cosine), tinted up to it, with a glass rim
  // lit from the top left. The range input stays on top, unseen, so the mouse,
  // touch, keys and screen readers work as before; its thumb is as wide as the
  // swelling, so the input's own travel matches where the swelling is drawn.
  // Dragging swells it further on a bouncy spring, as in the app.
  // The volume is the same tube, smaller, so its swelling leaves it room to move.
  function glassTube(input, { tube: TUBE, reach: REACH, rest: REST, held: HELD, height: H }) {
    const NS = "http://www.w3.org/2000/svg", id = input.id;
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("class", "wp-tube");
    svg.setAttribute("aria-hidden", "true");
    svg.innerHTML =
      '<defs><clipPath id="' + id + '-played"><rect x="0" y="0" width="0" height="' + H + '"/></clipPath>' +
      '<linearGradient id="' + id + '-rim" x1="0" y1="0" x2="0" y2="1">' +
      '<stop offset="0" stop-color="#fff" stop-opacity="0.95"/><stop offset="0.45" stop-color="#d1e0ff" stop-opacity="0.25"/>' +
      '<stop offset="0.7" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#a6d1ff" stop-opacity="0.45"/></linearGradient></defs>' +
      '<path class="wp-tube-rest"/><path class="wp-tube-played" clip-path="url(#' + id + '-played)"/>' +
      '<circle class="wp-tube-core"/><path class="wp-tube-rim" fill="none" stroke="url(#' + id + '-rim)"/>';
    const wrap = document.createElement("span");
    wrap.className = "wp-tube-wrap";
    wrap.style.setProperty("--tube-h", `${H}px`);
    wrap.style.setProperty("--tube-thumb", `${2 * REACH}px`);
    input.replaceWith(wrap);
    wrap.append(svg, input);
    const [clip] = svg.getElementsByTagName("rect");
    const paths = svg.querySelectorAll("path"), core = svg.querySelector("circle");
    let bulge = REST, speed = 0, target = REST, raf = 0, queued = false;
    const draw = () => {
      queued = false;
      const w = wrap.clientWidth;
      if (!w) return;
      svg.setAttribute("viewBox", `0 0 ${w} ${H}`);
      const max = Number(input.max) || 0, frac = max > 0 ? Math.min(1, Math.max(0, Number(input.value) / max)) : 0;
      const inset = Math.min(REACH, w / 4), px = inset + (w - 2 * inset) * frac, cy = H / 2;
      const half = (x) => { const d = Math.abs(x - px); return d >= REACH ? TUBE : TUBE + (bulge - TUBE) * 0.5 * (1 + Math.cos(Math.PI * d / REACH)); };
      const top = [], bottom = [];
      for (let x = TUBE; x <= w - TUBE; x += 1) { const h = half(x); top.push(`${x.toFixed(1)},${(cy - h).toFixed(2)}`); bottom.push(`${x.toFixed(1)},${(cy + h).toFixed(2)}`); }
      const d = `M${top.join("L")}A${TUBE},${TUBE} 0 0 1 ${bottom[bottom.length - 1]}L${bottom.reverse().join("L")}A${TUBE},${TUBE} 0 0 1 ${top[0]}Z`;
      for (const p of paths) p.setAttribute("d", d);
      clip.setAttribute("width", px.toFixed(1));
      core.setAttribute("cx", px.toFixed(1)); core.setAttribute("cy", cy);
      core.setAttribute("r", (TUBE + (bulge - TUBE) * 0.6).toFixed(2));
    };
    const schedule = () => { if (!queued) { queued = true; requestAnimationFrame(draw); } };
    // The swelling's spring: bouncy (damping 0.6), as the app's.
    const spring = () => {
      const dt = 1 / 60, k = 420, c = 2 * 0.6 * Math.sqrt(k);
      speed += (k * (target - bulge) - c * speed) * dt;
      bulge += speed * dt;
      draw();
      if (Math.abs(target - bulge) > 0.01 || Math.abs(speed) > 0.01) raf = requestAnimationFrame(spring); else { bulge = target; raf = 0; draw(); }
    };
    const hold = (on) => { target = on ? HELD : REST; if (!raf) raf = requestAnimationFrame(spring); };
    input.addEventListener("pointerdown", () => hold(true));
    for (const ev of ["pointerup", "pointercancel", "change", "blur"]) input.addEventListener(ev, () => hold(false));
    input.addEventListener("input", schedule);
    // The player sets value and max from code (playing, following another
    // device), which fires no event: redraw on those too.
    for (const key of ["value", "max"]) {
      const own = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, key);
      Object.defineProperty(input, key, { configurable: true, get() { return own.get.call(this); }, set(v) { own.set.call(this, v); schedule(); } });
    }
    new ResizeObserver(schedule).observe(wrap);
    draw();
  }
  glassTube($("bar-seek"), { tube: 2.6, reach: 22, rest: 8, held: 10.5, height: 24 });
  glassTube($("bar-volume"), { tube: 2.2, reach: 13, rest: 6.5, held: 8.5, height: 20 });

  // ── Start ───────────────────────────────────────────────────────────
  initAutoEq();
  paintUnlock();
  renderBar();
  route();
  Local.restore().then(() => { localChanged(); if (!$("view-files").hidden) route(); }).catch(() => {});
  if (HiFi.base) { pill("Trypt HiFi", null); testHifi(); }
  if (C) C.start({ token: () => Cloud.token(), playing: () => !!current && !Eng.paused });
  document.addEventListener("click", (e) => {
    const p = $("wp-devices");
    if (!p.hidden && !e.target.closest("#wp-devices, #bar-devices")) toggleDevices(false);
  });
})();

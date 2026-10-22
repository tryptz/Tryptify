// The web player on /account: plays the signed-in person's Tryptify Cloud
// library in the browser. Each song is fetched through a short-lived signed
// link from the cloud server (GET /v1/songs/:id/play), straight from storage,
// with seeking; nothing is transcoded and nothing is relayed. A queue with
// shuffle and repeat, the phone's lock-screen controls (Media Session), and a
// small spectrum read from the audio itself. account.js gives it the links
// and the covers; it draws its own bar at the bottom of the page.
(() => {
  "use strict";

  const TYPES = {
    flac: "audio/flac", mp3: "audio/mpeg", m4a: "audio/mp4", mp4: "audio/mp4", aac: "audio/aac",
    ogg: "audio/ogg", oga: "audio/ogg", opus: 'audio/ogg; codecs="opus"', wav: "audio/wav",
    aif: "audio/aiff", aiff: "audio/aiff", ape: "audio/ape", wma: "audio/x-ms-wma", alac: "audio/mp4",
  };
  const probe = document.createElement("audio");
  /** Whether this browser can play [song]'s file at all ("maybe" is a yes: try it). */
  const playable = (song) => {
    const type = TYPES[(song.suffix || "").toLowerCase()];
    return !!type && probe.canPlayType(type) !== "";
  };
  const clock = (s) => {
    if (!Number.isFinite(s) || s < 0) return "0:00";
    const m = Math.floor(s / 60);
    return `${m}:${String(Math.floor(s % 60)).padStart(2, "0")}`;
  };
  const remember = (k, v) => { try { localStorage.setItem(k, v); } catch (_) {} };
  const recall = (k) => { try { return localStorage.getItem(k); } catch (_) { return null; } };

  const ICON = {
    play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.5v13a1 1 0 0 0 1.5.86l10.6-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5Z" fill="currentColor"/></svg>',
    pause: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6.5" y="5" width="4" height="14" rx="1.2" fill="currentColor"/><rect x="13.5" y="5" width="4" height="14" rx="1.2" fill="currentColor"/></svg>',
    prev: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="5.5" width="2.4" height="13" rx="1" fill="currentColor"/><path d="M19 6.3v11.4a.9.9 0 0 1-1.38.76L9.3 13.06a1.25 1.25 0 0 1 0-2.12l8.32-5.4A.9.9 0 0 1 19 6.3Z" fill="currentColor"/></svg>',
    next: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="16.6" y="5.5" width="2.4" height="13" rx="1" fill="currentColor"/><path d="M5 6.3v11.4a.9.9 0 0 0 1.38.76l8.32-5.4a1.25 1.25 0 0 0 0-2.12l-8.32-5.4A.9.9 0 0 0 5 6.3Z" fill="currentColor"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7h3.5c4.5 0 6.5 10 11 10H21M3 17h3.5c1.8 0 3.1-1.6 4.2-3.6M14.3 9.6C15.3 8 16.5 7 18 7h3M18.5 4.5 21 7l-2.5 2.5M18.5 14.5 21 17l-2.5 2.5"/></svg>',
    repeat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 11V9.5A3.5 3.5 0 0 1 7.5 6H19M16.5 3.5 19 6l-2.5 2.5M20 13v1.5a3.5 3.5 0 0 1-3.5 3.5H5M7.5 20.5 5 18l2.5-2.5"/></svg>',
    volume: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 9.5h3l4.5-4v13l-4.5-4H4z" fill="currentColor" stroke="none"/><path d="M15.5 9a4.2 4.2 0 0 1 0 6M18.3 6.5a8 8 0 0 1 0 11"/></svg>',
    mute: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="M4 9.5h3l4.5-4v13l-4.5-4H4z" fill="currentColor" stroke="none"/><path d="m16 9.5 5 5M21 9.5l-5 5"/></svg>',
    note: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 17.5V6.8l10-2.3v10.3" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="6.8" cy="17.6" r="2.4" fill="currentColor"/><circle cx="16.8" cy="15.2" r="2.4" fill="currentColor"/></svg>',
  };

  const audio = new Audio();
  audio.preload = "auto";
  audio.crossOrigin = "anonymous"; // the server sends CORS to this site, so the spectrum can read it

  let deps = null;
  let bar = null;
  let paint = () => {}; // the play button and the rest, from audio.paused (set in build)
  const state = { list: [], order: [], pos: -1, shuffle: false, repeat: "off" };
  const links = new Map(); // song id -> { url, until }
  let skipped = 0; // songs skipped in a row; a whole queue of them stops the player
  const listeners = new Set();

  const current = () => (state.pos >= 0 ? state.list[state.order[state.pos]] : null);
  const notify = () => listeners.forEach((fn) => fn(current(), !audio.paused));

  async function linkFor(song, fresh) {
    const cached = links.get(song.id);
    if (!fresh && cached && cached.until > Date.now() + 60_000) return cached.url;
    const r = await deps.link(song);
    links.set(song.id, { url: r.url, until: Date.parse(r.expiresAt) || Date.now() + 3_600_000 });
    return r.url;
  }

  function orderFor(length, start) {
    const order = Array.from({ length }, (_, i) => i);
    if (!state.shuffle) return order;
    for (let i = order.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [order[i], order[j]] = [order[j], order[i]];
    }
    if (start >= 0) { order.splice(order.indexOf(start), 1); order.unshift(start); }
    return order;
  }

  // ── The bar ─────────────────────────────────────────────────────────
  function build() {
    bar = document.createElement("section");
    bar.className = "player glass";
    bar.setAttribute("aria-label", "Player");
    bar.hidden = true;
    bar.innerHTML = `
      <div class="pl-now">
        <div class="pl-art"><img alt="" hidden><span class="pl-art-ph">${ICON.note}</span></div>
        <div class="pl-text"><strong class="pl-title"></strong><span class="pl-artist"></span></div>
      </div>
      <div class="pl-middle">
        <div class="pl-buttons">
          <button type="button" class="pl-btn pl-shuffle" aria-pressed="false" title="Shuffle" aria-label="Shuffle">${ICON.shuffle}</button>
          <button type="button" class="pl-btn pl-prev" title="Previous" aria-label="Previous">${ICON.prev}</button>
          <button type="button" class="pl-btn pl-play" title="Play" aria-label="Play">${ICON.play}</button>
          <button type="button" class="pl-btn pl-next" title="Next" aria-label="Next">${ICON.next}</button>
          <button type="button" class="pl-btn pl-repeat" aria-pressed="false" title="Repeat: off" aria-label="Repeat: off">${ICON.repeat}<i class="pl-one">1</i></button>
        </div>
        <div class="pl-seek">
          <span class="pl-time pl-at">0:00</span>
          <input type="range" class="pl-range" min="0" max="1000" value="0" step="1" aria-label="Seek">
          <span class="pl-time pl-length">0:00</span>
        </div>
      </div>
      <div class="pl-right">
        <canvas class="pl-spectrum" width="120" height="36" aria-hidden="true"></canvas>
        <button type="button" class="pl-btn pl-mute" title="Mute" aria-label="Mute">${ICON.volume}</button>
        <input type="range" class="pl-volume" min="0" max="100" value="100" aria-label="Volume">
      </div>
      <p class="pl-msg" role="status" hidden></p>`;
    document.body.append(bar);
    document.body.classList.add("has-player");

    const q = (c) => bar.querySelector(c);
    q(".pl-play").addEventListener("click", toggle);
    q(".pl-prev").addEventListener("click", previous);
    q(".pl-next").addEventListener("click", () => next(true));
    q(".pl-shuffle").addEventListener("click", () => setShuffle(!state.shuffle));
    q(".pl-repeat").addEventListener("click", () => setRepeat({ off: "all", all: "one", one: "off" }[state.repeat]));
    const range = q(".pl-range");
    let dragging = false;
    range.addEventListener("input", () => {
      dragging = true;
      if (Number.isFinite(audio.duration)) q(".pl-at").textContent = clock((range.value / 1000) * audio.duration);
    });
    range.addEventListener("change", () => {
      dragging = false;
      if (Number.isFinite(audio.duration)) audio.currentTime = (range.value / 1000) * audio.duration;
    });
    audio.addEventListener("timeupdate", () => {
      if (dragging || !Number.isFinite(audio.duration)) return;
      range.value = String(Math.round((audio.currentTime / audio.duration) * 1000));
      range.style.setProperty("--fill", `${(audio.currentTime / audio.duration) * 100}%`);
      q(".pl-at").textContent = clock(audio.currentTime);
      if ("setPositionState" in (navigator.mediaSession || {})) {
        try { navigator.mediaSession.setPositionState({ duration: audio.duration, position: audio.currentTime, playbackRate: audio.playbackRate }); } catch (_) {}
      }
    });
    audio.addEventListener("durationchange", () => { q(".pl-length").textContent = clock(audio.duration); });

    const vol = q(".pl-volume");
    const saved = Number(recall("tryptify-player-volume"));
    audio.volume = Number.isFinite(saved) && recall("tryptify-player-volume") !== null ? Math.min(1, Math.max(0, saved)) : 1;
    vol.value = String(Math.round(audio.volume * 100));
    vol.style.setProperty("--fill", `${vol.value}%`);
    vol.addEventListener("input", () => {
      audio.volume = vol.value / 100;
      audio.muted = false;
      vol.style.setProperty("--fill", `${vol.value}%`);
      remember("tryptify-player-volume", String(audio.volume));
    });
    q(".pl-mute").addEventListener("click", () => { audio.muted = !audio.muted; });
    audio.addEventListener("volumechange", () => {
      const off = audio.muted || audio.volume === 0;
      q(".pl-mute").innerHTML = off ? ICON.mute : ICON.volume;
      q(".pl-mute").setAttribute("aria-label", off ? "Unmute" : "Mute");
    });

    const playing = paint = () => {
      const on = !audio.paused && !audio.error;
      q(".pl-play").innerHTML = on ? ICON.pause : ICON.play;
      q(".pl-play").setAttribute("aria-label", on ? "Pause" : "Play");
      q(".pl-play").title = on ? "Pause" : "Play";
      bar.classList.toggle("is-playing", on);
      if (navigator.mediaSession) navigator.mediaSession.playbackState = on ? "playing" : "paused";
      notify();
    };
    audio.addEventListener("play", playing);
    audio.addEventListener("pause", playing);
    audio.addEventListener("ended", () => (state.repeat === "one" ? replay() : next(false)));
    audio.addEventListener("error", onError);
    audio.addEventListener("waiting", () => bar.classList.add("is-loading"));
    audio.addEventListener("playing", () => { bar.classList.remove("is-loading"); skipped = 0; });

    // Space plays and pauses, unless something you type in has the focus.
    document.addEventListener("keydown", (e) => {
      if (e.code !== "Space" || bar.hidden) return;
      const t = e.target;
      if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT|BUTTON)$/.test(t.tagName))) return;
      e.preventDefault();
      toggle();
    });

    if (navigator.mediaSession) {
      const on = (action, fn) => { try { navigator.mediaSession.setActionHandler(action, fn); } catch (_) {} };
      on("play", () => audio.play());
      on("pause", () => audio.pause());
      on("previoustrack", previous);
      on("nexttrack", () => next(true));
      on("seekto", (d) => { if (Number.isFinite(d.seekTime)) audio.currentTime = d.seekTime; });
      on("seekbackward", (d) => { audio.currentTime = Math.max(0, audio.currentTime - (d.seekOffset || 10)); });
      on("seekforward", (d) => { audio.currentTime = Math.min(audio.duration || 0, audio.currentTime + (d.seekOffset || 10)); });
    }
  }

  function say(text) {
    const m = bar.querySelector(".pl-msg");
    m.textContent = text || "";
    m.hidden = !text;
    clearTimeout(say.t);
    if (text) say.t = setTimeout(() => { m.hidden = true; }, 5000);
  }

  // ── The spectrum: an analyser on the audio itself ───────────────────
  let analyser = null;
  function spectrum() {
    if (analyser || !window.AudioContext) return;
    try {
      const ctx = new AudioContext();
      const source = ctx.createMediaElementSource(audio);
      analyser = ctx.createAnalyser();
      analyser.fftSize = 256;
      analyser.smoothingTimeConstant = 0.8;
      source.connect(analyser);
      analyser.connect(ctx.destination);
      audio.addEventListener("play", () => { if (ctx.state === "suspended") ctx.resume(); });
      const canvas = bar.querySelector(".pl-spectrum");
      const g = canvas.getContext("2d");
      const bins = new Uint8Array(analyser.frequencyBinCount);
      const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
      const draw = () => {
        requestAnimationFrame(draw);
        if (audio.paused && !draw.wasPlaying) return;
        draw.wasPlaying = !audio.paused;
        analyser.getByteFrequencyData(bins);
        const w = canvas.width, h = canvas.height, n = 24, gap = 2, bw = (w - gap * (n - 1)) / n;
        g.clearRect(0, 0, w, h);
        g.fillStyle = getComputedStyle(bar).color;
        for (let i = 0; i < n; i++) {
          // Bars on a rough log scale, so the bass doesn't take them all.
          const from = Math.floor(Math.pow(bins.length, i / n)), to = Math.max(from + 1, Math.floor(Math.pow(bins.length, (i + 1) / n)));
          let v = 0;
          for (let k = from; k < to; k++) v = Math.max(v, bins[k]);
          const bh = still ? 2 : Math.max(2, (v / 255) * h);
          g.globalAlpha = 0.35 + 0.65 * (v / 255);
          g.fillRect(i * (bw + gap), h - bh, bw, bh);
        }
        g.globalAlpha = 1;
      };
      draw();
    } catch (_) {
      analyser = null; // the bar still plays; it just has no spectrum
    }
  }

  // ── Playing ─────────────────────────────────────────────────────────
  async function load(autoplay) {
    const song = current();
    if (!song) return;
    bar.hidden = false;
    const q = (c) => bar.querySelector(c);
    q(".pl-title").textContent = song.title || "Untitled";
    q(".pl-artist").textContent = [song.artist, song.album].filter(Boolean).join(" · ");
    q(".pl-at").textContent = "0:00";
    q(".pl-length").textContent = clock(song.duration);
    q(".pl-range").value = "0";
    q(".pl-range").style.setProperty("--fill", "0%");
    const img = q(".pl-art img");
    img.hidden = true;
    img.removeAttribute("src");
    if (!playable(song)) {
      say(`"${song.title}" is ${String(song.suffix || "this format").toUpperCase()}, which plays in the app but not in this browser. Skipping.`);
      if (++skipped >= state.order.length) { skipped = 0; audio.pause(); return notify(); }
      return next(false, true);
    }
    notify();
    let artwork = null;
    if (song.hasCover && deps.cover) {
      deps.cover(song).then((url) => {
        if (!url || current() !== song) return;
        img.src = url;
        img.hidden = false;
        artwork = url;
        session(song, artwork);
      }).catch(() => {});
    }
    session(song, artwork);
    try {
      audio.src = await linkFor(song, false);
      if (current() !== song) return;
      if (autoplay) {
        spectrum();
        await audio.play();
      }
    } catch (e) {
      if (e && e.name === "AbortError") return; // another song was picked first
      if (e && e.name === "NotAllowedError") return; // the browser wants a tap first
      say(e && e.message ? e.message : "This song couldn't be played.");
    }
  }

  function session(song, artwork) {
    if (!navigator.mediaSession || !window.MediaMetadata) return;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: song.title || "",
      artist: song.artist || "",
      album: song.album || "",
      artwork: artwork ? [{ src: artwork, sizes: "512x512" }] : [],
    });
  }

  let retried = null;
  async function onError() {
    const song = current();
    if (!song || !audio.src) return;
    // A link that ran out: get a fresh one and carry on from the same place.
    if (retried !== song.id) {
      retried = song.id;
      const at = audio.currentTime;
      try {
        audio.src = await linkFor(song, true);
        audio.currentTime = at;
        await audio.play();
        return;
      } catch (_) { /* fall through */ }
    }
    say(`"${song.title}" couldn't be played here. Skipping.`);
    audio.pause();
    paint();
    if (++skipped >= state.order.length) { skipped = 0; return; }
    next(false, true);
  }

  function replay() {
    audio.currentTime = 0;
    audio.play().catch(() => {});
  }

  function next(byHand, skipping) {
    if (!state.order.length) return;
    retried = null;
    if (state.pos + 1 < state.order.length) state.pos += 1;
    else if (state.repeat === "all" || byHand) {
      if (state.shuffle) state.order = orderFor(state.list.length, -1);
      state.pos = 0;
    } else {
      audio.pause();
      if (!skipping) audio.currentTime = 0;
      return paint();
    }
    load(true);
  }

  function previous() {
    if (audio.currentTime > 3 || state.pos <= 0) return replay();
    retried = null;
    state.pos -= 1;
    load(true);
  }

  function toggle() {
    if (!current()) return;
    if (audio.paused) {
      spectrum();
      if (!audio.src) load(true);
      else audio.play().catch(() => {});
    } else audio.pause();
  }

  function setShuffle(on) {
    state.shuffle = on;
    const playing = state.order[state.pos];
    state.order = orderFor(state.list.length, playing ?? -1);
    state.pos = playing === undefined ? -1 : state.order.indexOf(playing);
    const b = bar.querySelector(".pl-shuffle");
    b.setAttribute("aria-pressed", String(on));
    b.title = on ? "Shuffle: on" : "Shuffle: off";
  }

  function setRepeat(mode) {
    state.repeat = mode;
    const b = bar.querySelector(".pl-repeat");
    b.setAttribute("aria-pressed", String(mode !== "off"));
    b.classList.toggle("is-one", mode === "one");
    const label = { off: "Repeat: off", all: "Repeat: all", one: "Repeat: this song" }[mode];
    b.title = label;
    b.setAttribute("aria-label", label);
  }

  window.TryptifyPlayer = {
    /** [link](song) → { url, expiresAt }; [cover](song) → an image URL or null. */
    init(options) {
      deps = options;
      if (!bar) build();
    },
    /** Plays [list] from [index], [list] becoming the queue. */
    play(list, index) {
      if (bar) say("");
      state.list = list.slice();
      state.order = orderFor(state.list.length, index);
      state.pos = state.order.indexOf(index);
      retried = null;
      load(true);
    },
    /** Plays [list] in a random order. */
    shuffle(list) {
      if (!list.length) return;
      setShuffle(true);
      this.play(list, Math.floor(Math.random() * list.length));
    },
    /** Songs the player gets when the list it is playing grows ("Show more"). */
    extend(list) {
      if (!state.list.length || list.length <= state.list.length) return;
      if (list[0]?.id !== state.list[0]?.id) return; // a different list now
      const added = list.slice(state.list.length);
      const start = state.list.length;
      state.list = list.slice();
      const more = added.map((_, i) => start + i);
      if (state.shuffle) for (let i = more.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [more[i], more[j]] = [more[j], more[i]]; }
      state.order = state.order.concat(more);
    },
    /** A song deleted from the library: out of the queue, and skipped if it was playing. */
    forget(id) {
      const index = state.list.findIndex((s) => s.id === id);
      if (index < 0) return;
      const wasCurrent = current()?.id === id;
      state.list.splice(index, 1);
      const at = state.order.indexOf(index);
      state.order = state.order.filter((i) => i !== index).map((i) => (i > index ? i - 1 : i));
      if (at < state.pos || (wasCurrent && state.pos >= state.order.length)) state.pos -= 1;
      if (wasCurrent) { audio.pause(); audio.removeAttribute("src"); if (state.pos >= 0) load(true); }
      notify();
    },
    /** Stops and hides the bar (signing out). */
    stop() {
      audio.pause();
      audio.removeAttribute("src");
      state.list = []; state.order = []; state.pos = -1;
      links.clear();
      if (bar) bar.hidden = true;
      notify();
    },
    playable,
    current: () => current()?.id || null,
    isPlaying: () => !audio.paused,
    toggle,
    onChange(fn) { listeners.add(fn); },
  };
})();

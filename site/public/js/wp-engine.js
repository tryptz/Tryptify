// The web player's sound (/player): two <audio> elements that take turns, so
// the next song can load while this one plays and start the moment it ends,
// both feeding one Web Audio chain:
//   source → preamp → AutoEQ (the app's own matched-Z filters, as IIR
//   filters at the context's rate) → mono → volume → speakers.
// Nothing here runs on a timer: the elements' own events drive the page, so a
// paused player costs nothing. wp-app.js draws it.
(() => {
  "use strict";
  const E = window.TryptifyAutoEq;
  const dbToGain = (db) => Math.pow(10, db / 20);

  const els = [new Audio(), new Audio()];
  for (const a of els) {
    a.preload = "auto";
    // Web Audio hears nothing from another site's file without CORS; the
    // Cloud, Trypt HiFi's /api routes and local files all allow it.
    a.crossOrigin = "anonymous";
  }
  let cur = 0;                 // the element playing now; the other is next
  let nextKey = null;          // what the other element holds, if preloaded
  const listeners = new Map(); // event name → set of functions

  const state = {
    volume: 0.8, speed: 1, preservePitch: true, mono: false,
    eq: { on: false, bands: [], preamp: 0 },
  };

  function emit(name, arg) { for (const fn of listeners.get(name) || []) fn(arg); }
  for (const [i, a] of els.entries()) {
    for (const name of ["play", "pause", "timeupdate", "durationchange", "ended", "error", "waiting", "playing"])
      a.addEventListener(name, () => { if (i === cur) emit(name, a); });
  }

  // ── The chain ───────────────────────────────────────────────────────
  let ctx = null, input = null, preamp = null, monoNode = null, volume = null, filters = [];
  /** Builds the graph on the first play (browsers only start audio after a tap). */
  function graph() {
    if (ctx || !window.AudioContext) return;
    try {
      ctx = new AudioContext({ latencyHint: "playback" }); // larger buffers: fewer wake-ups, less battery
      input = ctx.createGain();
      for (const a of els) ctx.createMediaElementSource(a).connect(input);
      preamp = ctx.createGain();
      monoNode = ctx.createGain();
      volume = ctx.createGain();
      wire();
    } catch (_) { ctx = null; } // no Web Audio: the elements play straight out, without EQ
  }

  /** (Re)connects input → preamp → filters → mono → volume → out. */
  function wire() {
    if (!ctx) { for (const a of els) a.volume = state.volume * state.volume; return; }
    for (const n of [input, preamp, ...filters, monoNode]) n.disconnect();
    filters = [];
    const eq = state.eq;
    if (eq.on && E) {
      for (const b of eq.bands) {
        const c = E.coeffs(b, ctx.sampleRate);
        try {
          filters.push(ctx.createIIRFilter([c[0], c[1], c[2]], [1, c[3], c[4]]));
        } catch (_) {
          const f = ctx.createBiquadFilter(); // the browser's own, near enough
          f.type = b.type === "LOWSHELF" ? "lowshelf" : b.type === "HIGHSHELF" ? "highshelf" : "peaking";
          f.frequency.value = b.freq; f.gain.value = b.gain; f.Q.value = b.q;
          filters.push(f);
        }
      }
    }
    preamp.gain.value = eq.on ? dbToGain(-eq.preamp) : 1;
    // One channel in, both speakers out: a mono gain node downmixes, the
    // destination spreads it back over left and right.
    monoNode.channelCount = state.mono ? 1 : 2;
    monoNode.channelCountMode = "explicit";
    monoNode.channelInterpretation = "speakers";
    volume.gain.value = state.volume * state.volume; // a curve that sounds even
    const chain = [input, preamp, ...filters, monoNode, volume, ctx.destination];
    for (let i = 0; i < chain.length - 1; i++) chain[i].connect(chain[i + 1]);
    for (const a of els) a.volume = 1;
  }

  function applyRate(a) {
    a.playbackRate = state.speed;
    a.defaultPlaybackRate = state.speed;
    a.preservesPitch = state.preservePitch;
  }

  // ── Public ──────────────────────────────────────────────────────────
  const el = () => els[cur];
  window.TryptifyEngine = {
    on(name, fn) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); },

    /** Plays [url] now; [key] names the song so a preload of it is reused. */
    async load(url, key, autoplay = true) {
      graph();
      const other = els[1 - cur];
      if (key != null && key === nextKey && other.src) {
        el().pause();
        cur = 1 - cur;            // the preloaded element takes over
      } else {
        el().src = url;
      }
      nextKey = null;
      const a = el();
      applyRate(a);
      emit("durationchange", a);
      if (autoplay) await this.play();
    },
    /** Loads [url] into the waiting element, so it can start without a gap. */
    preload(url, key) {
      const other = els[1 - cur];
      if (nextKey === key) return;
      nextKey = key;
      other.src = url;
      other.load();
    },
    async play() {
      graph();
      if (ctx && ctx.state === "suspended") await ctx.resume().catch(() => {});
      try { await el().play(); } catch (e) { if (e.name !== "AbortError") throw e; }
    },
    pause() { el().pause(); },
    toggle() { return el().paused ? this.play() : this.pause(); },
    seek(t) { const a = el(); if (Number.isFinite(a.duration)) a.currentTime = Math.max(0, Math.min(a.duration, t)); },
    stop() { for (const a of els) { a.pause(); a.removeAttribute("src"); a.load(); } nextKey = null; },
    get time() { return el().currentTime || 0; },
    get duration() { const d = el().duration; return Number.isFinite(d) ? d : 0; },
    get paused() { return el().paused; },
    get rate() { return ctx ? ctx.sampleRate : null; },
    /** Whether the Web Audio chain is running (false before the first play, or without Web Audio). */
    get webAudio() { return !!ctx; },

    settings() { return JSON.parse(JSON.stringify(state)); },
    setVolume(v) { state.volume = Math.max(0, Math.min(1, v)); if (volume) volume.gain.value = state.volume * state.volume; else wire(); },
    setSpeed(r) { state.speed = Math.max(0.25, Math.min(4, r)); for (const a of els) applyRate(a); },
    setPreservePitch(on) { state.preservePitch = !!on; for (const a of els) applyRate(a); },
    setMono(on) { state.mono = !!on; if (ctx) wire(); },
    /** { on, bands: [{ type, freq, gain, q }], preamp } with preamp in dB of cut. */
    setEq(eq) { state.eq = { on: !!eq.on, bands: eq.bands || [], preamp: eq.preamp || 0 }; if (ctx) wire(); },
  };
})();

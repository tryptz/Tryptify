// Tryptify Connect in the web player: this browser as one of the listener's
// devices (server/connect), so it sees what plays on the others, can play it
// here instead, or steer it from here, like Spotify Connect.
// Light on purpose: one WebSocket, a message only when something changes
// (a song, play, pause, a seek; never the position every second), a "ping"
// every 50 s that Cloudflare answers without waking anything, and no
// connection at all while the tab is hidden with nothing playing.
(() => {
  "use strict";
  const recall = (k) => { try { return localStorage.getItem(k); } catch (_) { return null; } };
  const remember = (k, v) => { try { localStorage.setItem(k, v); } catch (_) {} };
  // Only the real room, or one on this computer (for testing): the address
  // gets the account's sign-in, so a stored one elsewhere is ignored.
  const BASE = ((u) => /^(wss:\/\/connect\.tryptify\.com|wss?:\/\/(localhost|127\.0\.0\.1)(:\d+)?)$/.test(u || "") ? u : "wss://connect.tryptify.com")(recall("tryptify-connect-url"));

  /** This browser's id, made once and kept. */
  const deviceId = (() => {
    let id = recall("tryptify-connect-device");
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(id || "")) {
      id = "web-" + [...crypto.getRandomValues(new Uint8Array(9))].map((b) => b.toString(16).padStart(2, "0")).join("");
      remember("tryptify-connect-device", id);
    }
    return id;
  })();
  /** "Chrome on Windows", from the browser's own description of itself. */
  function deviceName() {
    const ua = navigator.userAgent;
    const browser = /Edg\//.test(ua) ? "Edge" : /OPR\//.test(ua) ? "Opera" : /Firefox\//.test(ua) ? "Firefox"
      : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
    const os = /Android/.test(ua) ? "Android" : /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /CrOS/.test(ua) ? "ChromeOS"
      : /Windows/.test(ua) ? "Windows" : /Mac OS X/.test(ua) ? "Mac" : /Linux/.test(ua) ? "Linux" : "";
    return os ? `${browser} on ${os}` : browser;
  }

  const listeners = new Map();
  const emit = (name, arg) => { for (const fn of listeners.get(name) || []) { try { fn(arg); } catch (e) { console.error(e); } } };
  let ws = null, ping = 0, retry = 0, retryTimer = 0, idleTimer = 0, offset = 0;
  let status = "off";         // off | connecting | on | signed-out | replaced
  let devices = [], now = null, last = null, opts = null;

  function setStatus(s) { if (status !== s) { status = s; emit("status", s); } }
  function send(msg) { if (ws && ws.readyState === 1) { ws.send(JSON.stringify(msg)); return true; } return false; }

  async function connect() {
    if (!opts || ws || status === "replaced") return;
    clearTimeout(retryTimer);
    let token = null;
    try { token = await opts.token(); } catch (_) {}
    if (!token) { setStatus("signed-out"); return; }
    if (ws) return;
    setStatus("connecting");
    const q = new URLSearchParams({ device: deviceId, name: deviceName(), kind: "web" });
    const sock = new WebSocket(`${BASE}/v1/connect?${q}`, ["tryptify-connect", `bearer.${token}`]);
    ws = sock;
    sock.onopen = () => {
      retry = 0;
      setStatus("on");
      clearInterval(ping);
      ping = setInterval(() => { if (sock.readyState === 1) sock.send("ping"); }, 50_000);
      // Back after a drop: where it is now, not where it was when last told.
      const again = last && last();
      if (again && again.playing) send({ type: "state", ...again });
    };
    sock.onmessage = (e) => {
      if (e.data === "pong") return;
      let m;
      try { m = JSON.parse(e.data); } catch (_) { return; }
      if (typeof m.serverTime === "number") offset = m.serverTime - Date.now();
      if (m.type === "welcome") { devices = m.devices || []; now = m.now; emit("devices", devices); emit("now", now); }
      else if (m.type === "devices") { devices = m.devices || []; emit("devices", devices); }
      else if (m.type === "now") { now = m.now; emit("now", now); }
      else if (m.type === "command" || m.type === "release" || m.type === "takeover" || m.type === "error") emit(m.type, m);
    };
    sock.onclose = (e) => {
      if (ws !== sock) return;
      ws = null;
      clearInterval(ping);
      devices = [];
      emit("devices", devices);
      if (e.code === 4000) { setStatus("replaced"); return; } // this browser opened Connect in another tab
      setStatus("off");
      if (wanted()) {
        retry = Math.min(retry + 1, 6);
        retryTimer = setTimeout(connect, Math.min(60_000, 1000 * 2 ** retry)); // 2 s … 60 s
      }
    };
  }
  function disconnect() { clearTimeout(retryTimer); if (ws) { const s = ws; ws = null; s.close(1000); clearInterval(ping); setStatus("off"); } }
  /** Connected while the tab is seen, or while it plays. */
  const wanted = () => !!opts && (document.visibilityState === "visible" || opts.playing());

  document.addEventListener("visibilitychange", () => {
    clearTimeout(idleTimer);
    if (document.visibilityState === "visible") connect();
    else idleTimer = setTimeout(() => { if (!wanted()) disconnect(); }, 60_000);
  });
  addEventListener("online", () => { retry = 0; connect(); });

  /** Where [n] is now, by the server's clock. */
  function positionOf(n) {
    if (!n) return 0;
    if (!n.playing) return n.position;
    const p = n.position + Math.max(0, Date.now() + offset - n.at) / 1000 * (n.rate || 1);
    return n.track && n.track.duration ? Math.min(p, n.track.duration) : p;
  }

  let publishTimer = 0;
  window.TryptifyConnect = {
    id: deviceId,
    get name() { return deviceName(); },
    get status() { return status; },
    get devices() { return devices; },
    get now() { return now; },
    on(name, fn) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); },
    /** { token: () => Promise<string|null>, playing: () => boolean } */
    start(o) { opts = o; connect(); },
    reconnect() { if (status === "replaced") status = "off"; disconnect(); connect(); },
    stop() { opts = null; disconnect(); },
    positionOf,
    /** The server's clock, as this browser reckons it. */
    serverNow: () => Date.now() + offset,
    online: (id) => devices.some((d) => d.id === id),
    /** Another device's song, while it plays there (or last played there, if that device is gone). */
    elsewhere() { return now && now.device !== deviceId && now.track ? now : null; },
    /**
     * What plays here changed. Sent once things settle (a new song is a load
     * and a play). [read] gives the state when it goes, so the position is
     * the one at sending, not 250 ms stale; null when nothing plays here.
     */
    publish(read) {
      last = read;
      clearTimeout(publishTimer);
      publishTimer = setTimeout(() => {
        const state = last && last();
        if (state && send({ type: "state", ...state })) { now = { ...state, device: deviceId, at: Date.now() + offset }; emit("now", now); }
      }, 250);
    },
    command(to, action, value) { return send({ type: "command", to, action, value }); },
    transfer(to, force) { return send({ type: "transfer", to, force: !!force }); },
    handoff(to, state, queue) { return send({ type: "handoff", to, now: state, queue }); },
  };
})();

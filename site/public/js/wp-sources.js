// The web player's music (/player), from three places, each turned into the
// same track shape for the queue:
//   { key, src: "local" | "cloud" | "hifi", title, artist, album, n, duration, quality, cover, ref }
// - Local: a folder on this computer, read in the browser and never uploaded.
//   Chrome and Edge keep the folder between visits (File System Access, the
//   handle in IndexedDB); other browsers pick it again each visit.
// - Cloud: the signed-in person's Tryptify Cloud, through the cloud server's
//   /v1 API with the Supabase session /account uses.
// - Trypt HiFi: a TrypT-hifi server the listener names, through its /api/tidal
//   routes (the ones that send CORS). Its stream-file route joins TIDAL's
//   pieces into one file the <audio> element can play and seek.
// - Music server: the listener's own Navidrome or other Subsonic server, as
//   the app's Settings › Connections › Music server: a token and salt are kept,
//   never the password, unless the server refuses tokens (API older than
//   1.13.0, or error 41/42), and then the password's hex, as the app does.
// - Library: the account's liked songs and playlists, as the app syncs them to
//   Supabase (favorite_tracks, user_playlists, playlist_tracks; each row is
//   its owner's alone by RLS). Their songs are TIDAL ids, so they play
//   through Trypt HiFi.
(() => {
  "use strict";
  const remember = (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (_) {} };
  const recall = (k) => { try { return localStorage.getItem(k); } catch (_) { return null; } };

  // ── Pure helpers (site/tests/webplayer.mjs) ─────────────────────────
  const AUDIO = new Set(["flac", "mp3", "m4a", "mp4", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "alac", "webm"]);
  const extOf = (name) => { const i = name.lastIndexOf("."); return i > 0 ? name.slice(i + 1).toLowerCase() : ""; };
  const isAudio = (name) => AUDIO.has(extOf(name)) && !name.startsWith("._");

  /** Track number, title, artist and album from a file's name and the folders above it (…/Artist/Album/01 - Title.flac). */
  function parseFileName(name, parents) {
    let base = name.replace(/\.[^.]+$/, "");
    let n = null;
    const m = base.match(/^(?:(\d)[-.])?(\d{1,3})(?:\s*[-._)]\s*|\s+)(.+)$/);
    if (m) { n = Number(m[2]); base = m[3]; }
    const album = parents.length ? parents[parents.length - 1] : "";
    const artist = parents.length > 1 ? parents[parents.length - 2] : "";
    return { n, title: base.trim() || name, artist, album };
  }

  /** Folders as { name, path, dirs: [...], files: [track] }, sorted, from flat { parents, name, file } entries. */
  function buildTree(entries, rootName) {
    const root = { name: rootName, path: [], dirs: [], files: [], byName: new Map() };
    for (const e of entries) {
      let node = root;
      for (const part of e.parents) {
        if (!node.byName.has(part)) {
          const d = { name: part, path: [...node.path, part], dirs: [], files: [], byName: new Map() };
          node.byName.set(part, d);
          node.dirs.push(d);
        }
        node = node.byName.get(part);
      }
      node.files.push(e.track);
    }
    const sort = (node) => {
      node.dirs.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" }));
      node.files.sort((a, b) => (a.n ?? 1e9) - (b.n ?? 1e9) || a.file.localeCompare(b.file, undefined, { numeric: true }));
      node.dirs.forEach(sort);
    };
    sort(root);
    return root;
  }

  /** Every track under [node], folder by folder. */
  const allFiles = (node) => [...node.files, ...node.dirs.flatMap(allFiles)];

  /** A Trypt HiFi address as typed → "https://host[/path]" without a trailing slash, or null. */
  function normalizeBase(v) {
    v = String(v || "").trim();
    if (!v) return null;
    if (!/^https?:\/\//i.test(v)) v = "https://" + v;
    try {
      const u = new URL(v);
      return (u.origin + u.pathname).replace(/\/+$/, "");
    } catch (_) { return null; }
  }

  const HIFI_LABEL = { HI_RES_LOSSLESS: "FLAC 24-bit", LOSSLESS: "FLAC 16/44.1", HIGH: "AAC 320", LOW: "AAC 96" };
  /** A Trypt HiFi track (Qobuz-shaped, lib/tidal.tsx normalizeTidalTrack) → a queue track. */
  function hifiTrack(t, album, quality) {
    const a = album || t.album || {};
    const img = a.image || {};
    const q = quality === "HI_RES_LOSSLESS" && !t.hires ? "LOSSLESS" : quality;
    return {
      key: `hifi:${t.id}`, src: "hifi", title: t.version ? `${t.title} (${t.version})` : t.title,
      artist: (t.performer && t.performer.name) || (a.artist && a.artist.name) || "",
      album: a.title || "", n: t.track_number || null, duration: t.duration || null,
      quality: HIFI_LABEL[q] || q, cover: img.small || img.thumbnail || img.large || null,
      ref: { id: String(t.id), albumId: a.id ? String(a.id) : null, hires: !!t.hires },
    };
  }
  function hifiAlbum(a) {
    const img = a.image || {};
    return {
      id: String(a.id), title: a.title || "", artist: (a.artist && a.artist.name) || "",
      cover: img.small || img.thumbnail || img.large || null, hires: !!a.hires, count: a.tracks_count || 0,
      year: (a.release_date_original || "").slice(0, 4),
    };
  }

  /** A TIDAL cover id as the app keeps it → its picture (Models.kt buildCoverUrl); a device path has none here. */
  const coverUrl = (c, size = 320) => (!c ? null : c.includes("://") ? c : c.startsWith("/") ? null
    : `https://resources.tidal.com/images/${c.replace(/-/g, "/")}/${size}x${size}.jpg`);
  /** A favorite_tracks or playlist_tracks row → a queue track, played through Trypt HiFi. */
  function savedTrack(r, quality) {
    const id = r.id != null ? r.id : r.track_id;
    // Liked songs know their quality; playlist rows do not, so ask for the best and take what comes.
    const hires = !r.audio_quality || /HI_RES/.test(r.audio_quality);
    const q = quality === "HI_RES_LOSSLESS" && !hires ? "LOSSLESS" : quality;
    return {
      key: `hifi:${id}`, src: "hifi", title: r.title || T("Untitled"), artist: r.artist_name || "",
      album: r.album_title || "", n: r.track_number || null, duration: r.duration || null,
      quality: HIFI_LABEL[q] || "", cover: coverUrl(r.album_cover),
      ref: { id: String(id), albumId: r.album_id ? String(r.album_id) : null, hires },
    };
  }

  /** A Tryptify Cloud song (GET /v1/songs) → a queue track. */
  function cloudTrack(s) {
    const suffix = (s.suffix || "").toUpperCase();
    const rate = s.sampleRate ? `/${+(s.sampleRate / 1000).toFixed(1)}` : "";
    return {
      key: `cloud:${s.id}`, src: "cloud", title: s.title || T("Untitled"), artist: s.artist || s.albumArtist || "",
      album: s.album || "", n: s.track || null, duration: s.duration || null,
      quality: s.bitDepth ? `${suffix} ${s.bitDepth}${rate}` : suffix, cover: null, ref: s,
    };
  }

  // ── IndexedDB, for the folder handle only ───────────────────────────
  const db = () => new Promise((resolve, reject) => {
    const r = indexedDB.open("tryptify-player", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("kv");
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
  const idb = async (mode, fn) => {
    const d = await db();
    return new Promise((resolve, reject) => {
      const tx = d.transaction("kv", mode);
      const req = fn(tx.objectStore("kv"));
      tx.oncomplete = () => resolve(req && req.result);
      tx.onerror = () => reject(tx.error);
    });
  };

  // ── Local folder ────────────────────────────────────────────────────
  const urls = new Map(); // key → object URL; only the last few are kept
  const Local = {
    canKeep: "showDirectoryPicker" in window,
    tree: null,
    count: 0,

    /** Asks for a folder (keeps it where the browser can). */
    async pick() {
      const h = await window.showDirectoryPicker({ id: "tryptify-music", mode: "read" });
      await idb("readwrite", (s) => s.put(h, "music")).catch(() => {});
      return this.scan(h);
    },
    /** The kept folder: "ready" when it was read, "ask" when the browser wants a tap first, null when none. */
    async restore() {
      if (!this.canKeep) return null;
      const h = await idb("readonly", (s) => s.get("music")).catch(() => null);
      if (!h) return null;
      const p = await h.queryPermission({ mode: "read" });
      if (p === "granted") { await this.scan(h); return "ready"; }
      this.pending = h;
      return "ask";
    },
    /** Asks again for the kept folder (needs a tap). */
    async reconnect() {
      const h = this.pending;
      if (!h || (await h.requestPermission({ mode: "read" })) !== "granted") return false;
      this.pending = null;
      await this.scan(h);
      return true;
    },
    async forget() { await idb("readwrite", (s) => s.delete("music")).catch(() => {}); this.tree = null; this.count = 0; },

    async scan(dir) {
      const entries = [];
      const walk = async (d, parents) => {
        for await (const [name, h] of d.entries()) {
          if (name.startsWith(".")) continue;
          if (h.kind === "directory") await walk(h, [...parents, name]);
          else if (isAudio(name)) entries.push({ parents, name, handle: h });
        }
      };
      await walk(dir, []);
      return this.fromEntries(entries.map((e) => ({ ...e, get: () => e.handle.getFile() })), dir.name);
    },
    /** For browsers without folder access: an <input webkitdirectory> FileList. */
    fromFileList(list) {
      const entries = [];
      for (const f of list) {
        const parts = (f.webkitRelativePath || f.name).split("/");
        const name = parts.pop();
        if (isAudio(name)) entries.push({ parents: parts.slice(1), name, get: async () => f, root: parts[0] });
      }
      return this.fromEntries(entries, entries[0] ? entries[0].root : T("Music"));
    },
    fromEntries(entries, rootName) {
      const items = entries.map((e) => {
        const p = parseFileName(e.name, e.parents);
        return {
          parents: e.parents,
          track: {
            key: `local:${[...e.parents, e.name].join("/")}`, src: "local", file: e.name,
            title: p.title, artist: p.artist, album: p.album, n: p.n, duration: null,
            quality: extOf(e.name).toUpperCase(), cover: null, ref: { get: e.get, path: e.parents },
          },
        };
      });
      this.tree = buildTree(items, rootName);
      this.count = items.length;
      return this.tree;
    },
    async url(track) {
      if (urls.has(track.key)) return urls.get(track.key);
      const u = URL.createObjectURL(await track.ref.get());
      urls.set(track.key, u);
      while (urls.size > 3) { const [k, v] = urls.entries().next().value; URL.revokeObjectURL(v); urls.delete(k); }
      return u;
    },
    /** Title, artist, album and cover from the file's own tags (only for the song playing and the next). */
    async tags(track) {
      if (track.tagged) return track;
      track.tagged = true;
      const read = await tagReader();
      if (!read) return track;
      const file = await track.ref.get();
      const t = await new Promise((resolve) => read.read(file, { onSuccess: (r) => resolve(r.tags || {}), onError: () => resolve({}) }));
      if (t.title) track.title = t.title;
      if (t.artist) track.artist = t.artist;
      if (t.album) track.album = t.album;
      if (t.picture && t.picture.data) {
        const blob = new Blob([new Uint8Array(t.picture.data)], { type: t.picture.format || "image/jpeg" });
        track.cover = URL.createObjectURL(blob);
      }
      return track;
    },
  };
  let reader = null;
  const tagReader = () => reader || (reader = new Promise((resolve) => {
    if (window.jsmediatags) return resolve(window.jsmediatags);
    const s = document.createElement("script");
    s.src = "/js/vendor/jsmediatags-3.9.7.min.js";
    s.onload = () => resolve(window.jsmediatags || null);
    s.onerror = () => resolve(null);
    document.head.append(s);
  }));

  // ── Tryptify Cloud ──────────────────────────────────────────────────
  const CLOUD = "https://cloud.tryptify.com";
  const SUPABASE_URL = "https://lvzorvfhhopillzlwgau.supabase.co";
  const SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx2em9ydmZoaG9waWxsemx3Z2F1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQzNTc0NDQsImV4cCI6MjA4OTkzMzQ0NH0.Y_TN9r19WS96HyVZSQeNa0TyOqyBGuqFARaj8-7Ylow";
  let sb = null;
  const client = () => sb || (sb = window.supabase && window.supabase.createClient(SUPABASE_URL, SUPABASE_KEY, {
    auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false, flowType: "pkce" },
  }));
  const links = new Map();
  const Cloud = {
    /** The session's access token (refreshed when due), or null when signed out. Tryptify Connect signs in with it. */
    async token() {
      const c = client();
      return c ? ((await c.auth.getSession()).data.session || {}).access_token || null : null;
    },
    async signedIn() {
      const c = client();
      return !!(c && (await c.auth.getSession()).data.session);
    },
    async api(path) {
      const c = client();
      if (!c) throw new Error(T("Tryptify Cloud could not load."));
      const go = (t) => fetch(CLOUD + "/v1" + path, { headers: { authorization: `Bearer ${t}` } });
      let t = (await c.auth.getSession()).data.session?.access_token;
      if (!t) throw new Error(T("Sign in on the Account page first."));
      let res = await go(t);
      if (res.status === 401) {
        const { data } = await c.auth.refreshSession();
        if (!data.session) throw new Error(T("Sign in on the Account page first."));
        res = await go(data.session.access_token);
      }
      if (!res.ok) throw new Error(T("Tryptify Cloud said: {code}", { code: (await res.json().catch(() => ({}))).error || res.status }));
      return res;
    },
    async songs(offset = 0, q = "") {
      const r = await (await this.api(`/songs?offset=${offset}${q ? `&q=${encodeURIComponent(q)}` : ""}`)).json();
      return { total: r.total || 0, tracks: (r.songs || []).map(cloudTrack) };
    },
    async url(track) {
      const c = links.get(track.ref.id);
      if (c && c.until > Date.now() + 60_000) return c.url;
      const r = await (await this.api(`/songs/${track.ref.id}/play`)).json();
      links.set(track.ref.id, { url: r.url, until: Date.parse(r.expiresAt) || Date.now() + 3_600_000 });
      return r.url;
    },
    async cover(track) {
      if (track.cover || !track.ref.hasCover) return track.cover;
      track.cover = URL.createObjectURL(await (await this.api(`/songs/${track.ref.id}/cover`)).blob());
      return track.cover;
    },
  };

  // ── Trypt HiFi ──────────────────────────────────────────────────────
  const HiFi = {
    get base() { return recall("tryptify-player-hifi"); },
    set base(v) { remember("tryptify-player-hifi", normalizeBase(v)); },
    get quality() { return recall("tryptify-player-hifi-quality") || "LOSSLESS"; },
    set quality(v) { remember("tryptify-player-hifi-quality", v); },
    // The server's password, when it has one (trypt-hifi's ACCESS_PASSWORD).
    // This browser's alone, like the address: never sent to Connect or synced.
    get password() { return recall("tryptify-player-hifi-key") || ""; },
    set password(v) { remember("tryptify-player-hifi-key", v || null); },
    async get(path, params, base = this.base, password = this.password) {
      if (!base) throw new Error(T("Add the Trypt HiFi API URL first."));
      const qs = new URLSearchParams(params).toString();
      const res = await fetch(`${base}${path}?${qs}`, password ? { headers: { "X-Access-Key": password } } : undefined);
      if (res.status === 401) throw new Error(password ? T("That password isn’t right for this server.") : T("This server asks for a password."));
      const j = await res.json().catch(() => null);
      if (!res.ok || !j || !j.success) throw new Error((j && typeof j.error === "string" && j.error) || T("Trypt HiFi answered {status}.", { status: res.status }));
      return j.data;
    },
    /** Round-trip time in ms to [base] (the saved one by default), or throws with the reason. */
    async test(base = this.base, password = this.password) {
      const t0 = performance.now();
      await this.get("/api/tidal/get-music", { q: "a", offset: 0 }, base, password);
      return Math.round(performance.now() - t0);
    },
    async search(q) {
      const d = await this.get("/api/tidal/get-music", { q, offset: 0 });
      return {
        tracks: ((d.tracks && d.tracks.items) || []).filter((t) => t.streamable !== false).map((t) => hifiTrack(t, null, this.quality)),
        albums: ((d.albums && d.albums.items) || []).map(hifiAlbum),
      };
    },
    async album(id) {
      const a = await this.get("/api/tidal/get-album", { album_id: id });
      return { album: hifiAlbum(a), tracks: ((a.tracks && a.tracks.items) || []).map((t) => hifiTrack(t, a, this.quality)) };
    },
    url(track) {
      if (!this.base) throw new Error(T("it needs an API URL: add one under Sources, on the dashboard."));
      const q = this.quality === "HI_RES_LOSSLESS" && !track.ref.hires ? "LOSSLESS" : this.quality;
      // An <audio> element sends no headers: the password rides in the link.
      const key = this.password ? `&access_key=${encodeURIComponent(this.password)}` : "";
      return `${this.base}/api/tidal/stream-file?track_id=${encodeURIComponent(track.ref.id)}&quality=${q}${key}`;
    },
  };

  // ── Music server (Navidrome and other Subsonic servers) ─────────────
  /** MD5 of a UTF-8 string, as hex: Subsonic's token is md5(password + salt). */
  function md5(str) {
    const bytes = new TextEncoder().encode(str);
    const n = ((bytes.length + 8) >> 6) + 1, w = new Uint32Array(n * 16);
    for (let i = 0; i < bytes.length; i++) w[i >> 2] |= bytes[i] << ((i % 4) * 8);
    w[bytes.length >> 2] |= 0x80 << ((bytes.length % 4) * 8);
    w[n * 16 - 2] = bytes.length * 8;
    const K = [], R = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
    for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 2 ** 32) >>> 0;
    let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
    for (let o = 0; o < w.length; o += 16) {
      let a = a0, b = b0, c = c0, d = d0;
      for (let i = 0; i < 64; i++) {
        let f, g;
        if (i < 16) { f = (b & c) | (~b & d); g = i; }
        else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) % 16; }
        else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
        else { f = c ^ (b | ~d); g = (7 * i) % 16; }
        const t = d; d = c; c = b;
        const x = (a + f + K[i] + w[o + g]) >>> 0, r = R[(i >> 4) * 4 + (i % 4)];
        b = (b + ((x << r) | (x >>> (32 - r)))) >>> 0;
        a = t;
      }
      a0 = (a0 + a) >>> 0; b0 = (b0 + b) >>> 0; c0 = (c0 + c) >>> 0; d0 = (d0 + d) >>> 0;
    }
    return [a0, b0, c0, d0].map((v) => [0, 8, 16, 24].map((s) => ((v >>> s) & 255).toString(16).padStart(2, "0")).join("")).join("");
  }
  const hex = (str) => [...new TextEncoder().encode(str)].map((b) => b.toString(16).padStart(2, "0")).join("");

  /** A Subsonic song (OpenSubsonic fields where the server has them) → a queue track. */
  function subsonicTrack(s) {
    const suffix = (s.suffix || "").toUpperCase();
    const rate = s.samplingRate ? `/${+(s.samplingRate / 1000).toFixed(1)}` : "";
    const quality = s.bitDepth ? `${suffix} ${s.bitDepth}${rate}` : s.bitRate && !/FLAC|WAV|ALAC|AIFF?/.test(suffix) ? `${suffix} ${s.bitRate}` : suffix;
    return {
      key: `subsonic:${s.id}`, src: "subsonic", title: s.title || T("Untitled"), artist: s.artist || "",
      album: s.album || "", n: s.track || null, duration: s.duration || null, quality, cover: null,
      ref: { id: String(s.id), coverArt: s.coverArt || null },
    };
  }
  const subsonicAlbum = (a) => ({
    id: String(a.id), title: a.name || a.title || "", artist: a.artist || "", year: a.year ? String(a.year) : "",
    count: a.songCount || 0, coverArt: a.coverArt || null, cover: null, hires: false,
  });
  const list = (x) => (Array.isArray(x) ? x : x ? [x] : []);

  const Subsonic = {
    /** { url, user, salt, token } or { url, user, hexPassword } (servers that refuse tokens). */
    get server() { try { return JSON.parse(recall("tryptify-player-subsonic")); } catch (_) { return null; } },
    set server(v) { remember("tryptify-player-subsonic", v ? JSON.stringify(v) : null); },
    auth(srv = this.server) {
      const p = new URLSearchParams({ u: srv.user, v: "1.16.1", c: "tryptify-web", f: "json" });
      if (srv.hexPassword) p.set("p", `enc:${srv.hexPassword}`);
      else { p.set("t", srv.token); p.set("s", srv.salt); }
      return p;
    },
    link(view, params, srv = this.server) {
      const p = this.auth(srv);
      for (const [k, v] of Object.entries(params || {})) p.set(k, v);
      return `${srv.url}/rest/${view}?${p}`;
    },
    async get(view, params, srv = this.server) {
      if (!srv) throw new Error(T("Add your music server first."));
      const res = await fetch(this.link(view, params, srv));
      const j = await res.json().catch(() => null);
      const r = j && j["subsonic-response"];
      if (!r) throw new Error(T("The server answered {status}, not Subsonic.", { status: res.status }));
      if (r.status !== "ok") { const e = new Error((r.error && r.error.message) || T("The server said no.")); e.code = r.error && r.error.code; throw e; }
      return r;
    },
    /** Signs in with a token; if the server refuses tokens, with the password's hex. Never keeps the password itself. */
    async connect(url, user, password) {
      const base = normalizeBase(url);
      if (!base) throw new Error(T("That is not an address."));
      const salt = [...crypto.getRandomValues(new Uint8Array(8))].map((b) => b.toString(16).padStart(2, "0")).join("");
      let srv = { url: base, user, salt, token: md5(password + salt) };
      try { await this.get("ping.view", null, srv); } catch (e) {
        if (e.code !== 41 && e.code !== 42) throw e;
        srv = { url: base, user, hexPassword: hex(password) };
        await this.get("ping.view", null, srv);
      }
      this.server = srv;
      return srv;
    },
    cover(id, size = 300) { return id && this.server ? this.link("getCoverArt.view", { id, size }) : null; },
    withCover(t) { t.cover = this.cover(t.ref.coverArt); return t; },
    async albums(type = "newest", offset = 0) {
      const r = await this.get("getAlbumList2.view", { type, size: 60, offset });
      return list(r.albumList2 && r.albumList2.album).map(subsonicAlbum).map((a) => ({ ...a, cover: this.cover(a.coverArt) }));
    },
    async album(id) {
      const a = (await this.get("getAlbum.view", { id })).album || {};
      return { album: { ...subsonicAlbum(a), cover: this.cover(a.coverArt) }, tracks: list(a.song).map(subsonicTrack).map((t) => this.withCover(t)) };
    },
    async playlists() {
      return list(((await this.get("getPlaylists.view")).playlists || {}).playlist).map((p) => ({ id: String(p.id), name: p.name || T("Untitled"), count: p.songCount || 0 }));
    },
    async playlist(id) {
      const p = (await this.get("getPlaylist.view", { id })).playlist || {};
      return { name: p.name || T("Playlist"), tracks: list(p.entry).map(subsonicTrack).map((t) => this.withCover(t)) };
    },
    async starred() { return list(((await this.get("getStarred2.view")).starred2 || {}).song).map(subsonicTrack).map((t) => this.withCover(t)); },
    async search(q) {
      const r = (await this.get("search3.view", { query: q, songCount: 40, albumCount: 20, artistCount: 0 })).searchResult3 || {};
      return {
        tracks: list(r.song).map(subsonicTrack).map((t) => this.withCover(t)),
        albums: list(r.album).map(subsonicAlbum).map((a) => ({ ...a, cover: this.cover(a.coverArt) })),
      };
    },
    /** The file as it is (format=raw): no transcoding, so lossless stays lossless. */
    url(track) { if (!this.server) throw new Error(T("connect your music server to play this.")); return this.link("stream.view", { id: track.ref.id, format: "raw" }); },
  };

  // ── The account's liked songs and playlists (Supabase) ──────────────
  const Library = {
    /** Every row a query gives, a thousand at a time (PostgREST's page). */
    async rows(table, columns, shape) {
      const c = client();
      if (!c) throw new Error(T("Your account could not load."));
      const out = [];
      for (let from = 0; ; from += 1000) {
        const { data, error } = await shape(c.from(table).select(columns)).range(from, from + 999);
        if (error) throw new Error(error.message);
        out.push(...data);
        if (data.length < 1000) return out;
      }
    },
    async liked() {
      return (await this.rows("favorite_tracks", "*", (q) => q.order("added_at", { ascending: false }))).map((r) => savedTrack(r, HiFi.quality));
    },
    async playlists() {
      const rows = await this.rows("user_playlists", "id,name,description,updated_at,playlist_tracks(count)", (q) => q.order("updated_at", { ascending: false }));
      return rows.map((p) => ({ id: p.id, name: p.name || T("Untitled"), description: p.description || "",
        count: (p.playlist_tracks && p.playlist_tracks[0] && p.playlist_tracks[0].count) || 0 }));
    },
    async playlist(id) {
      const [meta] = await this.rows("user_playlists", "id,name,description", (q) => q.eq("id", id));
      const rows = await this.rows("playlist_tracks", "*", (q) => q.eq("playlist_id", id).order("position"));
      return { name: meta ? meta.name : T("Playlist"), description: (meta && meta.description) || "", tracks: rows.map((r) => savedTrack(r, HiFi.quality)) };
    },
  };

  window.TryptifySources = {
    Local, Cloud, HiFi, Subsonic, Library,
    /** The playable address of any queue track. */
    url: (t) => (t.src === "local" ? Local.url(t) : t.src === "cloud" ? Cloud.url(t)
      : Promise.resolve().then(() => (t.src === "subsonic" ? Subsonic.url(t) : HiFi.url(t)))),
    util: { parseFileName, buildTree, allFiles, normalizeBase, hifiTrack, hifiAlbum, cloudTrack, savedTrack, coverUrl, isAudio, md5, subsonicTrack },
  };
})();

// The listening party for guests: /party (enter a code) and /party/<code>
// (the invite link), on translated pages /<lang>/party/<code> too. A guest
// joins with a name, hears the party's song at the party's moment, chats,
// votes to skip and, when the rooms server has a catalogue, adds songs. The
// rooms server is ROOMS: its socket at /ws and its songs over HTTP, which it
// serves with CORS for tryptify.com (no cookies; songs carry ?token=).
// Text this script writes goes through T() (js/theme-boot.js).
(() => {
  "use strict";
  const ROOMS = "https://rooms.tryptify.com";

  const params = new URLSearchParams(location.search);
  const isLocal = (host) => host === "localhost" || host === "127.0.0.1";
  // ?rooms=<url> points the page at a test server, only while the page itself
  // is served on this computer (wrangler dev).
  const base = (() => {
    const asked = params.get("rooms");
    if (asked && isLocal(location.hostname)) {
      try {
        const u = new URL(asked);
        if (u.protocol === "https:" || (u.protocol === "http:" && isLocal(u.hostname))) return (u.origin + u.pathname).replace(/\/+$/, "");
      } catch (_) {}
    }
    return ROOMS;
  })();

  // The code: from the path (/party/ABCD, /de/party/ABCD), else ?code=, else typed in.
  const LANG = (/^\/(zh|ja|fr|es|tr|de|it)(?=\/)/.exec(location.pathname) || [])[1] || "";
  const clean = (s) => String(s || "").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 8);
  const linked = clean((/\/party\/([^/]+)\/?$/.exec(location.pathname) || [])[1] || params.get("code"));
  let code = linked;

  const $ = (id) => document.getElementById(id);
  const audio = $("audio");
  const locale = document.documentElement.lang || "en";

  // Following the party's clock. A seek drops the sound while the browser
  // refills, so it is the last resort: a small drift is caught up by playing a
  // little faster or slower (pitch kept), and only a big one is seeked, aiming
  // ahead by what the last seek cost, and never twice in a row.
  const IN_STEP_S = 0.08;   // closer than this is in step
  // Vinyl style hears any change of speed as pitch, so it leaves up to a
  // second alone, the phones' tolerance; past that it nudges, then seeks.
  const IN_STEP_VINYL_S = 1.0;
  const NUDGE_S = 1.5;      // up to this, caught up by speed
  const MAX_NUDGE = 0.05;   // at most 5% faster or slower
  const MAX_NUDGE_VINYL = 0.02; // when the pitch rides the speed, 2%
  const SETTLE_MS = 4000;   // after loading or seeking, left alone this long
  let seekLead = 0.25, quietUntil = 0, measuring = false, strikes = 0, anchor = "";

  let ws = null, me = null, key = "", token = "", name = "";
  let party = null, joined = false, ended = false, tries = 0, connecting = false, refused = false, joinTimer = 0;
  let offset = 0, samples = [], pinger = null, loaded = "", toastTimer = 0;
  // Whether the server has a catalogue to search, which also plays its songs here.
  let catalog = false, failed = "";

  const serverNow = () => Date.now() + offset;
  const fmt = (ms) => {
    const s = Math.max(0, Math.floor(ms / 1000));
    return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
  };
  // The party's moment: at the host's speed, so 1.25x runs 1.25 s of the song a second.
  const position = (pb) => pb.paused ? pb.positionMs : pb.positionMs + Math.max(0, serverNow() - pb.atServerMs) * (pb.rate || 1);
  const show = (el, on) => { el.hidden = !on; };
  const toast = (text) => {
    const t = $("toast");
    t.textContent = text; show(t, true);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => show(t, false), 4500);
  };
  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  // Each listener a colour of their own, by name.
  const tone = (s) => { let h = 0; for (const c of s) h = (h * 31 + c.charCodeAt(0)) | 0; return "t" + (Math.abs(h) % 5); };
  const partyPath = (c) => (LANG ? "/" + LANG : "") + "/party/" + c;

  // ── Joining ──────────────────────────────────────────────────────────
  try { name = localStorage.getItem("tryptify-party-name") || ""; } catch (_) {}
  $("guestName").value = name;
  const joinLabel = $("joinButton").textContent;

  function showCode() {
    const has = !!linked;
    show($("invited"), has);
    show($("codeLine"), has);
    show($("codeField"), !has);
    $("linkCode").textContent = linked;
    footText();
    // On Android, the invite opens in the app when it is installed.
    if (code && /Android/i.test(navigator.userAgent)) {
      const open = $("openApp");
      open.href = "intent://" + location.host + "/party/" + encodeURIComponent(code) +
        "#Intent;scheme=https;package=tf.monotrypt.android;S.browser_fallback_url=" + encodeURIComponent(location.href) + ";end";
      show(open, true);
    }
    if (code) keepCodeInLangs();
  }

  // The language list keeps the party's code.
  function keepCodeInLangs() {
    document.querySelectorAll(".langs a[href]").forEach((a) => {
      const href = a.getAttribute("href");
      if (/\/party$/.test(href)) a.setAttribute("href", href + "/" + code);
    });
  }

  function footText() {
    const foot = $("foot");
    show(foot, !!code);
    if (!code) return;
    foot.textContent = catalog
      ? T("In the Tryptify app? Join with code {code} to share songs from your phone.", { code })
      : T("Want to add songs too? Open the Tryptify app and join with code {code}.", { code });
  }

  function joinMessage(text) {
    const m = $("joinMsg");
    m.textContent = text || "";
    show(m, !!text);
  }

  function busy(on) {
    connecting = on;
    const b = $("joinButton");
    b.disabled = on || ended;
    b.textContent = on ? T("Joining…") : joinLabel;
  }

  showCode();
  if (!linked) $("codeInput").focus({ preventScroll: true });

  fetch(base + "/api/multiplayer/info").then((r) => r.json()).then((info) => {
    catalog = ((info.data && info.data.features) || []).includes("catalog");
    if (!catalog) return;
    $("joinFine").textContent = T("Joining lets this page play sound. Add songs from the catalogue, or listen to what the app shares.");
    footText();
    if (party) render();
  }).catch(() => {});

  // ── The cover, and its colour on the light behind the glass ──────────
  const coverUrl = (item) => base + "/api/party/cover?item=" + encodeURIComponent(item.id) + "&token=" + encodeURIComponent(token);
  let coverFor = "";

  function tint(rgb) {
    const root = document.documentElement.style;
    if (rgb) root.setProperty("--pt-glow", "rgb(" + rgb.join(" ") + ")"); else root.removeProperty("--pt-glow");
  }

  /** The cover of [item] on the page, and its colour behind the glass; the design's own light without one. */
  function showCover(item) {
    const want = item && item.cover ? item.id : "";
    if (want === coverFor) return;
    coverFor = want;
    const img = $("cover");
    if (!want) { show(img, false); img.removeAttribute("src"); tint(null); return; }
    const url = coverUrl(item);
    const probe = new Image();
    probe.onload = () => {
      if (coverFor !== want) return;
      img.src = url; img.alt = T("Cover of {title}", { title: item.title }); show(img, true);
    };
    probe.onerror = () => { if (coverFor === want) { show(img, false); tint(null); } };
    probe.src = url;
    // The colour needs the picture's pixels, so it is read with CORS; a cover
    // that cannot be read leaves the light as it is.
    const vibrant = window.TryptifyGlass && window.TryptifyGlass.vibrant;
    if (vibrant) vibrant(url).then((rgb) => { if (coverFor === want) tint(rgb); });
  }

  // ── The socket ───────────────────────────────────────────────────────
  function send(msg) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(msg)); }

  function connect() {
    refused = false;
    try {
      ws = new WebSocket(base.replace(/^http/, "ws") + "/ws");
    } catch (_) {
      joinMessage(T("Couldn't reach the party server. Check your connection and try again."));
      busy(false);
      return;
    }
    if (!joined) {
      // A server that never answers is given up on, as one that refuses is.
      clearTimeout(joinTimer);
      joinTimer = setTimeout(() => { if (!joined && ws) ws.close(); }, 10000);
    }
    ws.onopen = () => {
      send({ t: "hello", name, client: "web", guest: true, party: code, resume: key });
      samples = [];
      let quick = 5;
      clearInterval(pinger);
      pinger = setInterval(() => {
        send({ t: "ping", c: Date.now() });
        if (quick > 0 && --quick === 0) {
          clearInterval(pinger);
          pinger = setInterval(() => send({ t: "ping", c: Date.now() }), 2000);
        }
      }, 200);
    };
    ws.onmessage = (e) => { let m; try { m = JSON.parse(e.data); } catch (_) { return; } handle(m); };
    ws.onclose = () => {
      clearInterval(pinger);
      clearTimeout(joinTimer);
      if (ended) return;
      if (!joined) {
        if (!refused) joinMessage(T("Couldn't reach the party server. Check your connection and try again."));
        busy(false);
        return;
      }
      setState(T("Reconnecting"), "warn");
      // Back within five minutes, the server keeps the seat; later, the
      // hello's party code joins it again.
      setTimeout(connect, Math.min(8000, 500 * Math.pow(2, tries++)));
    };
  }

  // The server's words, in the page's language where they are known.
  function said(message) {
    switch (message) {
      case "slow down a little": return T("Slow down a little.");
      case "the party is full": return T("This party is full.");
      case "the queue is full": return T("Up next is full.");
      case "already in the party": return T("You're already at this party.");
      case "a website guest can add only songs from the catalogue": return T("From the web, you can add songs from the catalogue only.");
      default: return T("The party server said: {message}", { message });
    }
  }

  function handle(m) {
    switch (m.t) {
      case "welcome":
        me = m.id; key = m.key; tries = 0;
        if (!samples.length) offset = m.serverTime - Date.now();
        break;
      case "pong": {
        // As NTP does: the gap to the server's clock, from the quickest round trips.
        const rtt = Date.now() - m.c;
        samples.push({ rtt, off: m.s - (m.c + rtt / 2) });
        samples = samples.slice(-8);
        offset = samples.reduce((a, b) => (b.rtt < a.rtt ? b : a)).off;
        break;
      }
      case "joined":
        if (!joined) {
          try { localStorage.setItem("tryptify-party-name", name); } catch (_) {}
          // A typed code becomes the page's address, so a reload or a share lands here again.
          if (!linked) {
            if (!/\/party\/[^/]+\/?$/.test(location.pathname)) history.replaceState(null, "", partyPath(code));
            keepCodeInLangs();
            footText();
          }
        }
        joined = true; token = m.songToken; clearTimeout(joinTimer); busy(false);
        show($("joinCard"), false); show($("hero"), false); show($("room"), true);
        break;
      case "history": $("lines").textContent = ""; m.lines.forEach(addLine); emptyChat(); break;
      case "line": addLine(m.line); break;
      case "party": party = m.party; render(); break;
      case "added": {
        const b = pendingAdds.get(m.ref);
        if (b) { pendingAdds.delete(m.ref); b.textContent = T("Added"); }
        break;
      }
      case "left":
        endParty(m.reason === "kicked by the host" ? T("The host took you out of the party")
          : m.reason === "the host ended the party" ? T("The host ended the party") : T("You left the party"));
        break;
      case "error":
        if (m.message === "no party with that code") {
          if (joined) endParty(T("The party ended"));
          else if (linked) {
            ended = true; refused = true;
            joinMessage(T("This party has ended, or the link is wrong."));
            busy(false);
          } else {
            refused = true;
            joinMessage(T("There's no party with that code. Check it and try again."));
            busy(false);
            $("codeInput").focus();
          }
        } else if (!joined) {
          refused = true;
          joinMessage(said(m.message)); busy(false);
        } else {
          // An add the server refused can be tried again.
          pendingAdds.forEach((b) => { b.disabled = false; b.textContent = T("Add"); });
          pendingAdds.clear();
          toast(said(m.message));
        }
        break;
    }
  }

  function endParty(text) {
    ended = true; audio.pause(); forget([]);
    if (ws) ws.close();
    if (!$("adding").hidden) closeAdding();
    show($("addSongs"), false);
    setState(text, "warn");
    $("vote").disabled = true;
    $("draft").disabled = true;
    toast(text);
  }

  // ── Chat ─────────────────────────────────────────────────────────────
  function emptyChat() {
    const box = $("lines");
    if (!box.children.length) box.append(el("p", "sys pt-none", T("No messages yet. Say hello.")));
  }

  // The server's own lines carry their kind, so they are said in the page's language.
  function addLine(l) {
    const box = $("lines");
    let p;
    switch (l.kind) {
      case "say": {
        p = el("p");
        p.append(el("b", l.id === me ? "me" : "", l.name), document.createTextNode(l.text));
        break;
      }
      case "join": p = el("p", "sys", l.text === "web" ? T("{name} joined from the web", { name: l.name }) : T("{name} joined", { name: l.name })); break;
      case "leave": p = el("p", "sys", T("{name} left", { name: l.name })); break;
      case "add": p = el("p", "sys", T("{name} added {song}", { name: l.name, song: l.text })); break;
      case "skip": p = el("p", "sys", l.name ? T("{name} skipped {song}", { name: l.name, song: l.text }) : T("Skipped {song}", { song: l.text })); break;
      case "host": p = el("p", "sys", T("{name} is now the host", { name: l.name })); break;
      default: return;
    }
    const atEnd = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
    // A first message clears "No messages yet".
    const none = box.querySelector(".pt-none");
    if (none) none.remove();
    box.append(p);
    while (box.children.length > 150) box.firstChild.remove();
    if (atEnd) box.scrollTop = box.scrollHeight;
  }

  // ── The party on the page ────────────────────────────────────────────
  // A song nobody shares plays from the catalogue: by its TIDAL id, or found
  // there by its name (a file on a phone, a Qobuz, Apple or Deezer pick).
  const fromCatalog = (item) => catalog && (!item.shared || sharedFailed.has(item.id));
  // Shared songs this browser could not fetch or play (a format it does not
  // know, like ALAC or WMA): played from the catalogue instead, by name.
  const sharedFailed = new Set();

  function shareChip(item) {
    const s = item.shared;
    if (fromCatalog(item)) return el("span", "pt-chip ok", T("Catalogue"));
    if (!s) return el("span", "pt-chip", T("Not shared"));
    if (s.ready) return el("span", "pt-chip ok", T("Shared"));
    const pct = s.bytes ? Math.floor(100 * s.received / s.bytes) : 0;
    return el("span", "pt-chip warn", s.bytes ? T("Uploading {percent}%", { percent: pct }) : T("Uploading"));
  }

  function setState(text, kind) {
    const st = $("state");
    st.className = "pt-status" + (kind ? " " + kind : "");
    const span = st.querySelector("span");
    if (span.textContent !== text) span.textContent = text;
  }

  const addedBy = (item) => item.addedBy === me ? T("Added by you") : T("Added by {name}", { name: item.addedByName });

  function render() {
    const p = party, now = p.now;
    document.title = T("{name} · Tryptify listening party", { name: p.name });
    const host = p.members.find((x) => x.id === p.hostId);
    $("partyName").textContent = p.name;
    $("partyMeta").textContent = host ? T("Hosted by {name}", { name: host.name }) : "";
    $("partyCode").textContent = p.code;

    if (now) {
      $("songTitle").textContent = now.title;
      $("songBy").textContent = [now.artist, addedBy(now)].filter(Boolean).join(" · ");
      $("length").textContent = fmt(now.durationMs);
      showCover(now);
    } else {
      $("songTitle").textContent = T("Nothing playing yet");
      $("songBy").textContent = catalog ? T("Add a song to start the party.") : T("Someone in the app adds the next song.");
      $("length").textContent = "0:00";
      showCover(null);
    }

    const vote = $("vote");
    const voted = p.skip.votes.includes(me);
    vote.disabled = !now || ended;
    vote.setAttribute("aria-pressed", String(voted));
    const counts = { votes: p.skip.votes.length, needed: p.skip.needed };
    $("voteText").textContent = voted ? T("Voted to skip {votes}/{needed}", counts) : T("Vote skip {votes}/{needed}", counts);

    const list = $("upNext");
    list.textContent = "";
    p.queue.forEach((item, i) => {
      const li = el("li");
      const text = el("div");
      text.append(el("div", "pt-t", item.title));
      text.append(el("div", "pt-s", [item.artist, addedBy(item)].filter(Boolean).join(" · ")));
      li.append(el("span", "pt-n", String(i + 1)), text, shareChip(item));
      list.append(li);
    });
    show($("nothingNext"), p.queue.length === 0);
    show($("addSongs"), catalog && !ended);
    const pb = p.playback, sp = $("speed");
    const fast = pb.rate && Math.abs(pb.rate - 1) > 0.004;
    show(sp, !!fast);
    if (fast) {
      const rate = pb.rate.toLocaleString(locale, { maximumFractionDigits: 2 });
      sp.textContent = pb.keepPitch === false ? T("{rate}× · vinyl", { rate }) : T("{rate}×", { rate });
    }

    const people = $("people");
    people.textContent = "";
    [...p.members].sort((a, b) => (b.id === p.hostId) - (a.id === p.hostId)).forEach((m) => {
      const li = el("li", m.away ? "away" : "");
      const av = el("span", "pt-av " + tone(m.name), (Array.from(m.name)[0] || "?").toUpperCase());
      av.setAttribute("aria-hidden", "true");
      const tags = [m.id === p.hostId ? T("Host") : null, m.web ? T("Web") : null, m.away ? T("Away") : null].filter(Boolean);
      li.append(av, el("span", "pt-nm", m.id === me ? T("{name} (you)", { name: m.name }) : m.name));
      if (tags.length) li.append(el("span", "pt-tag" + (m.id === p.hostId ? " host" : ""), tags.join(" · ")));
      people.append(li);
    });
    follow();
  }

  // ── Playing along ────────────────────────────────────────────────────
  // Songs are downloaded whole before they play, so nothing buffers mid-song
  // and a seek is instant. Each is kept in this tab's memory only, for the
  // song playing and the next one, and is gone when the song moves on, the
  // party ends or the tab closes. Nothing is written to the disk.
  const files = new Map();

  const playable = (item) => fromCatalog(item) || !!(item.shared && item.shared.ready);
  const sharedSrc = (src) => src.includes("/api/party/song?");
  // A shared song as a file this browser plays (web=1: converted on the
  // server when its codec is one no browser plays).
  const songSrc = (item) => base + (fromCatalog(item) ? "/api/party/stream" : "/api/party/song") +
    "?item=" + encodeURIComponent(item.id) + "&token=" + encodeURIComponent(token) + (fromCatalog(item) ? "" : "&web=1");

  function download(src) {
    let f = files.get(src);
    if (f) return f;
    f = { url: "", got: 0, total: 0, failed: false, abort: new AbortController() };
    files.set(src, f);
    (async () => {
      try {
        const r = await fetch(src, { signal: f.abort.signal });
        if (!r.ok) throw new Error("HTTP " + r.status);
        f.total = Number(r.headers.get("content-length")) || 0;
        const reader = r.body.getReader(), parts = [];
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          // The once-a-second follow shows how far it has got.
          parts.push(value); f.got += value.length;
        }
        const url = URL.createObjectURL(new Blob(parts, { type: r.headers.get("content-type") || "audio/mpeg" }));
        // Let go while downloading: the song has gone, so its file goes too.
        if (files.get(src) !== f) { URL.revokeObjectURL(url); return; }
        f.url = url;
      } catch (e) {
        if (files.get(src) === f) f.failed = true;
      }
      follow();
    })();
    return f;
  }

  /** Every downloaded song but [keep] let go of. */
  function forget(keep) {
    for (const [src, f] of files) {
      if (keep.includes(src)) continue;
      f.abort.abort();
      if (f.url) URL.revokeObjectURL(f.url);
      files.delete(src);
    }
  }
  addEventListener("pagehide", () => {
    forget([]);
    // Closing the tab leaves the party, so coming back is one guest, not two.
    if (joined && !ended) send({ t: "leave" });
  });

  /** The song the party is on, at the party's moment, in this browser. */
  function follow() {
    if (!party || ended) return;
    const now = party.now, pb = party.playback;
    const next = party.queue.find(playable);
    if (!now) { forget(next ? [songSrc(next)] : []); stop(); setState(T("Waiting for a song")); tick(); return; }
    const s = now.shared;
    if (!playable(now)) {
      forget(next ? [songSrc(next)] : []);
      stop();
      setState(s ? T("Waiting for the upload") : T("Not shared, skipped here"), "warn");
      tick(); return;
    }
    // A shared song from the rooms server, or one from the catalogue by way of it.
    const src = songSrc(now);
    forget([src, next ? songSrc(next) : ""]);
    const f = download(src);
    if (f.failed || (f.url && failed === f.url)) {
      // A shared file that would not come or would not play: the catalogue's copy.
      if (sharedSrc(src) && catalog && !sharedFailed.has(now.id)) { sharedFailed.add(now.id); follow(); return; }
      stop(); setState(T("This song can't play here"), "warn"); tick(); return;
    }
    if (!f.url) {
      stop();
      setState(f.total ? T("Downloading {percent}%", { percent: Math.floor(100 * f.got / f.total) }) : T("Downloading"), "warn");
      tick(); return;
    }
    // This one is in; the next downloads while it plays.
    if (next) download(songSrc(next));
    if (loaded !== f.url) {
      loaded = f.url; audio.src = f.url;
      quietUntil = Date.now() + SETTLE_MS; measuring = false; strikes = 0;
    }
    if (audio.readyState < 1) { setState(T("Loading"), "warn"); tick(); return; }
    const want = position(pb) / 1000;
    // The host's speed, and how: vinyl style lets the pitch ride it.
    const speed = pb.rate || 1, vinyl = pb.keepPitch === false;
    setPitchKept(!vinyl);
    // A seek costs real time; at speed the song runs on further meanwhile.
    const lead = seekLead * speed;
    if (pb.paused) {
      if (!audio.paused) audio.pause();
      if (Math.abs(audio.currentTime - want) > 0.05 && want < audio.duration) audio.currentTime = want;
      audio.playbackRate = speed;
      setState(T("Paused by the host"));
      tick(); return;
    }
    if (audio.paused) {
      // Started where the party will be once the sound comes out.
      if (want < audio.duration) audio.currentTime = Math.min(audio.duration, want + lead);
      audio.playbackRate = speed;
      quietUntil = Date.now() + SETTLE_MS;
      audio.play().catch(() => setState(T("Tap anywhere to play"), "warn"));
      tick(); return;
    }
    const drift = audio.currentTime - want;
    // The host seeked (or resumed): the party moved on purpose, so follow at once.
    const moved = pb.itemId + ":" + pb.positionMs + ":" + pb.atServerMs + ":" + speed;
    if (moved !== anchor) {
      const first = anchor === "";
      anchor = moved;
      if (!first && Math.abs(drift) > NUDGE_S && want < audio.duration) {
        audio.playbackRate = speed;
        audio.currentTime = Math.min(audio.duration, want + lead);
        quietUntil = Date.now() + SETTLE_MS; measuring = true; strikes = 0;
        setState(T("Catching up"), "warn");
        tick(); return;
      }
    }
    if (Date.now() >= quietUntil) {
      if (measuring) {
        // Where the last seek landed says what a seek costs here.
        seekLead = Math.min(2, Math.max(0, seekLead - drift / speed));
        measuring = false;
      }
      if (Math.abs(drift) > NUDGE_S) {
        if (++strikes >= 2 && want < audio.duration) {
          strikes = 0;
          audio.playbackRate = speed;
          audio.currentTime = Math.min(audio.duration, want + lead);
          quietUntil = Date.now() + SETTLE_MS; measuring = true;
        }
      } else {
        strikes = 0;
        // Behind plays a little faster, ahead a little slower, until in step.
        // Vinyl style hears a nudge as pitch, so it nudges more gently.
        const most = vinyl ? MAX_NUDGE_VINYL : MAX_NUDGE;
        const still = vinyl ? IN_STEP_VINYL_S : IN_STEP_S;
        const rate = speed * (Math.abs(drift) <= still ? 1 : 1 - Math.max(-most, Math.min(most, drift / 4)));
        if (Math.abs(audio.playbackRate - rate) > 0.002) audio.playbackRate = rate;
      }
    }
    if (audio.readyState < 3) setState(T("Catching up"), "warn");
    else if (Math.abs(drift) <= 1.0) setState(T("Listening along · in sync"), "good");
    else setState(T("Catching up"), "warn");
    tick();
  }

  function stop() {
    audio.pause();
    audio.playbackRate = 1;
    if (loaded) { audio.removeAttribute("src"); audio.load(); loaded = ""; }
  }

  function tick() {
    const now = party && party.now;
    const ms = now ? Math.min(position(party.playback), now.durationMs) : 0;
    $("elapsed").textContent = fmt(ms);
    $("bar").style.width = now ? (100 * ms / now.durationMs).toFixed(2) + "%" : "0";
  }

  audio.addEventListener("error", () => { if (loaded) { failed = loaded; stop(); follow(); } });

  // ── Add songs: the catalogue searched as you type, each found song one
  // tap from the queue ─────────────────────────────────────────────────
  const pendingAdds = new Map();
  let findTimer = 0, findSeq = 0, nextRef = 1, opener = null;

  function openAdding() {
    opener = document.activeElement;
    show($("adding"), true);
    document.body.style.overflow = "hidden";
    $("find").focus();
  }
  function closeAdding() {
    show($("adding"), false);
    document.body.style.overflow = "";
    if (opener && opener.focus) opener.focus();
  }
  function findNote(text) { const n = $("findNote"); n.textContent = text; show(n, !!text); }

  async function find(query) {
    const seq = ++findSeq;
    const list = $("found");
    if (!query) { list.textContent = ""; findNote(T("Find a song and add it to the queue for everyone.")); return; }
    findNote(T("Searching…"));
    let items;
    try {
      const r = await fetch(base + "/api/party/search?q=" + encodeURIComponent(query) + "&token=" + encodeURIComponent(token));
      const body = await r.json();
      if (!r.ok) throw new Error(body.error || "search");
      items = body.data.items;
    } catch (e) {
      if (seq === findSeq) { list.textContent = ""; findNote(T("The search did not work. Try again.")); }
      return;
    }
    if (seq !== findSeq) return;
    list.textContent = "";
    findNote(items.length ? "" : T("No songs found for “{query}”.", { query }));
    items.forEach((f) => {
      const li = el("li");
      let pic;
      if (f.cover) { pic = el("img"); pic.src = f.cover; pic.alt = ""; pic.loading = "lazy"; } else pic = el("div", "ph");
      const text = el("div");
      const t = el("div", "pt-t");
      if (f.explicit) { const e = el("span", "pt-e", "E"); e.title = T("Explicit"); t.append(e); }
      t.append(document.createTextNode(f.title));
      text.append(t, el("div", "pt-s", [f.artist, f.album, fmt(f.durationMs)].filter(Boolean).join(" · ")));
      const add = el("button", "btn", T("Add"));
      add.type = "button";
      add.setAttribute("aria-label", f.artist ? T("Add {title} by {artist} to the queue", { title: f.title, artist: f.artist }) : T("Add {title} to the queue", { title: f.title }));
      add.addEventListener("click", () => {
        const ref = "w" + nextRef++;
        pendingAdds.set(ref, add);
        add.disabled = true; add.textContent = T("Adding");
        send({ t: "queueAdd", ref, item: { title: f.title, artist: f.artist, album: f.album, durationMs: f.durationMs,
          isrc: f.isrc, catalogId: f.catalogId, cover: f.cover, share: false } });
      });
      li.append(pic, text, add);
      list.append(li);
    });
  }

  $("addSongs").addEventListener("click", openAdding);
  $("closeAdd").addEventListener("click", closeAdding);
  $("adding").addEventListener("click", (e) => { if (e.target === $("adding")) closeAdding(); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !$("adding").hidden) closeAdding(); });
  $("find").addEventListener("input", (e) => {
    clearTimeout(findTimer);
    const q = e.target.value.trim();
    findTimer = setTimeout(() => find(q), 350);
  });
  $("findForm").addEventListener("submit", (e) => { e.preventDefault(); clearTimeout(findTimer); find($("find").value.trim()); $("find").blur(); });

  // Whether a change of speed keeps the pitch (and the older browsers' names for it).
  function setPitchKept(on) {
    if (audio.preservesPitch === on) return;
    audio.preservesPitch = on; audio.mozPreservesPitch = on; audio.webkitPreservesPitch = on;
  }
  setPitchKept(true);
  audio.addEventListener("loadedmetadata", follow);
  audio.addEventListener("canplay", follow);
  setInterval(follow, 1000);
  document.addEventListener("click", () => { if (loaded && audio.paused && party && !party.playback.paused) follow(); });
  audio.volume = 0.8;
  $("volume").addEventListener("input", (e) => { audio.volume = e.target.value / 100; });

  $("codeInput").addEventListener("input", (e) => {
    const v = clean(e.target.value);
    if (v !== e.target.value) e.target.value = v;
    joinMessage("");
  });
  $("joinForm").addEventListener("submit", (e) => {
    e.preventDefault();
    if (ended || connecting) return;
    if (!linked) code = clean($("codeInput").value);
    name = $("guestName").value.trim();
    if (!code) { joinMessage(T("Enter the party's code.")); $("codeInput").focus(); return; }
    if (!name) { joinMessage(T("Enter a name, so people know who's here.")); $("guestName").focus(); return; }
    joinMessage("");
    busy(true);
    // The tap lets this page play sound from now on.
    audio.play().catch(() => {});
    connect();
  });
  $("sayForm").addEventListener("submit", (e) => {
    e.preventDefault();
    const text = $("draft").value.trim();
    if (!text) return;
    send({ t: "chat", text }); $("draft").value = "";
  });
  $("vote").addEventListener("click", () => {
    if (!party || !party.now) return;
    send({ t: "skipVote", itemId: party.now.id, vote: !party.skip.votes.includes(me) });
  });

  // The glint follows the pointer over the party's glass, as over the menu's.
  const glass = window.TryptifyGlass;
  if (glass) document.querySelectorAll(".pt-panel.glass").forEach((g) => glass.glint(g));
})();

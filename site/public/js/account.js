// Your Cloud (/account): the web app for Tryptify Cloud. Signs in with the
// same Supabase account as the app (supabase-js, js/vendor), then talks to
// the cloud server's /v1 API with that session, exactly as the app does:
// plan and storage, checkout and the billing portal, uploads in the server's
// parts, the library and its player (js/player.js), and the devices signed in
// to the Subsonic API.
(() => {
  "use strict";
  const API = "https://cloud.tryptify.com";
  const SUPABASE_URL = "https://lvzorvfhhopillzlwgau.supabase.co";
  const SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx2em9ydmZoaG9waWxsemx3Z2F1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQzNTc0NDQsImV4cCI6MjA4OTkzMzQ0NH0.Y_TN9r19WS96HyVZSQeNa0TyOqyBGuqFARaj8-7Ylow";
  const M = window.Motion;
  const moving = document.documentElement.classList.contains("motion") && !!M;

  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const say = (id, text, bad) => {
    const m = $(id);
    m.textContent = text || "";
    m.classList.toggle("bad", !!bad);
  };
  const gb = (bytes) => {
    if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(bytes >= 1e10 ? 0 : 1)} GB`;
    if (bytes >= 1e6) return `${Math.round(bytes / 1e6)} MB`;
    return `${Math.max(1, Math.round(bytes / 1e3))} KB`;
  };
  const date = (iso) => (iso ? new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" }) : "?");
  const clock = (s) => (s ? `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, "0")}` : "");

  const ERRORS = {
    signed_out: "Your session ended. Sign in again.",
    no_plan: "You need a plan to upload.",
    read_only: "Your plan has ended, so nothing new can be uploaded.",
    over_quota: "Not enough space left in your plan.",
    not_audio: "That file type can't be stored.",
    file_too_big: "The file is too big (2 GB at most).",
    cover_too_big: "The cover is too big.",
    trial_used: "This account has already had its free trial.",
    too_many_uploads: "Too many uploads at once. Try again in a moment.",
    no_subscription: "There is no subscription to manage yet.",
    billing_not_set_up: "Payments aren't available right now.",
    not_set_up: "Tryptify Cloud isn't available right now.",
  };
  class ApiError extends Error {
    constructor(status, code) { super(ERRORS[code] || `Tryptify Cloud said: ${code || status}`); this.code = code; this.status = status; }
  }

  const sb = window.supabase.createClient(SUPABASE_URL, SUPABASE_KEY, {
    auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true, flowType: "pkce" },
  });
  const token = async () => (await sb.auth.getSession()).data.session?.access_token || null;

  /** One /v1 call with the session; a 401 refreshes it once. */
  async function api(method, path, body, raw) {
    const go = async (t) => fetch(API + "/v1" + path, {
      method,
      headers: { authorization: `Bearer ${t}`, ...(body !== undefined && !raw ? { "content-type": "application/json" } : {}) },
      body: body === undefined ? undefined : raw ? body : JSON.stringify(body),
    });
    let t = await token();
    if (!t) throw new ApiError(401, "signed_out");
    let res = await go(t);
    if (res.status === 401) {
      const { data } = await sb.auth.refreshSession();
      if (!data.session) throw new ApiError(401, "signed_out");
      res = await go(data.session.access_token);
    }
    if (!res.ok) {
      const j = await res.json().catch(() => ({}));
      throw new ApiError(res.status, j.error);
    }
    return res.status === 204 ? null : res.headers.get("content-type")?.includes("json") ? res.json() : res;
  }

  // ── Signing in ──────────────────────────────────────────────────────
  let creating = false;
  $("auth-switch").addEventListener("click", (e) => {
    e.preventDefault();
    creating = !creating;
    $("auth-title").textContent = creating ? "Create an account" : "Sign in";
    $("auth-submit").textContent = creating ? "Create account" : "Sign in";
    $("auth-switch").textContent = creating ? "Have an account? Sign in" : "No account yet? Create one";
    $("auth-form").password.autocomplete = creating ? "new-password" : "current-password";
  });
  $("auth-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const f = e.target;
    say("auth-msg", creating ? "Creating your account…" : "Signing in…");
    const creds = { email: f.email.value.trim(), password: f.password.value };
    const { data, error } = creating
      ? await sb.auth.signUp({ ...creds, options: { emailRedirectTo: location.origin + "/account" } })
      : await sb.auth.signInWithPassword(creds);
    if (error) return say("auth-msg", error.message, true);
    if (creating && !data.session) say("auth-msg", "Check your email to confirm your account, then sign in here.");
    else say("auth-msg", "");
  });
  $("auth-reset").addEventListener("click", async (e) => {
    e.preventDefault();
    const email = $("auth-form").email.value.trim();
    if (!email) return say("auth-msg", "Type your email above first.", true);
    const { error } = await sb.auth.resetPasswordForEmail(email, { redirectTo: location.origin + "/account" });
    say("auth-msg", error ? error.message : "If that email has an account, a reset link is on its way.", !!error);
  });
  $("google").addEventListener("click", () =>
    sb.auth.signInWithOAuth({ provider: "google", options: { redirectTo: location.origin + "/account" } }));
  $("discord").addEventListener("click", () =>
    sb.auth.signInWithOAuth({ provider: "discord", options: { redirectTo: location.origin + "/account" } }));
  $("sign-out").addEventListener("click", () => sb.auth.signOut());

  // ── The player ──────────────────────────────────────────────────────
  const player = window.TryptifyPlayer;
  player.init({
    link: (song) => api("GET", `/songs/${song.id}/play`),
    cover: async (song) => {
      if (!coverCache.has(song.id)) {
        const res = await api("GET", `/songs/${song.id}/cover`);
        coverCache.set(song.id, URL.createObjectURL(await res.blob()));
      }
      return coverCache.get(song.id);
    },
  });
  player.onChange((song, playing) => {
    for (const li of document.querySelectorAll("#songs > li.song")) {
      const on = !!song && li.dataset.id === song.id;
      li.classList.toggle("is-current", on);
      li.classList.toggle("is-playing", on && playing);
    }
  });
  $("play-all").addEventListener("click", () => songs.length && player.play(songs, 0));
  $("shuffle-all").addEventListener("click", () => player.shuffle(songs));

  let shownFor; // undefined until the first answer, so "signed out" is shown too
  sb.auth.onAuthStateChange((event, session) => {
    if (event === "PASSWORD_RECOVERY") {
      const pw = prompt("Choose a new password (6 characters or more):");
      if (pw) sb.auth.updateUser({ password: pw }).then(({ error }) => alert(error ? error.message : "Password changed."));
    }
    const id = session?.user?.id || null;
    if (id === shownFor) return;
    shownFor = id;
    $("signed-out").hidden = !!id;
    $("signed-in").hidden = !id;
    if (!id) player.stop();
    if (id) {
      $("who").textContent = session.user.email || "your account";
      if (moving) M.animate("#signed-in > section", { opacity: [0, 1], y: [20, 0] }, { delay: M.stagger(0.08), duration: 0.5 });
      loadAll();
    }
  });

  async function loadAll() {
    await Promise.allSettled([loadPlan(), loadSongs(true), loadServer()]);
  }

  // ── Plan and storage ────────────────────────────────────────────────
  let plans = null;
  async function loadPlan() {
    const card = $("plan-card");
    try {
      const [a, p] = await Promise.all([api("GET", "/account"), plans || fetch(API + "/plans").then((r) => r.json())]);
      plans = p;
      card.textContent = "";
      const name = a.plan ? a.plan[0].toUpperCase() + a.plan.slice(1) : null;
      const title = el("h3", null,
        a.unlimited ? "Unlimited storage"
        : a.access === "none" ? "No plan yet"
          : a.trial ? `Free trial until ${date(a.paidUntil)}`
            : `${name} plan, paid until ${date(a.paidUntil)}`);
      card.append(title);
      if (a.unlimited) {
        // Given, not sold: no quota to fill and no subscription to manage.
        card.append(el("p", "muted small", `${gb(a.usedBytes)} used · no limit, no end date`));
      } else if (a.access !== "none") {
        const frac = a.quotaBytes ? Math.min(1, a.usedBytes / a.quotaBytes) : 0;
        const bar = el("div", "meter");
        const fill = el("i");
        bar.append(fill);
        card.append(bar, el("p", "muted small", `${gb(a.usedBytes)} of ${gb(a.quotaBytes)} used`));
        if (moving) M.animate(fill, { scaleX: [0, frac] }, { type: "spring", stiffness: 90, damping: 18 });
        else fill.style.transform = `scaleX(${frac})`;
      }
      if (a.access === "read_only") {
        card.append(el("p", "bad", `Your plan has ended. Your music can still be played and downloaded until ${date(a.deleteAfter)}, then it is deleted. Pick a plan to keep it.`));
      }
      const buttons = el("div", "buttons");
      if (a.plan && !a.unlimited) {
        const manage = el("button", "btn", "Manage or cancel subscription");
        manage.type = "button";
        manage.addEventListener("click", () => act("plan-msg", async () => {
          location.href = (await api("POST", "/billing-portal")).url;
        }));
        buttons.append(manage);
      }
      card.append(buttons);
      $("upload").hidden = a.access !== "full";
      $("plan-picker").hidden = a.access === "full";
      if (a.access !== "full") drawPicker(a.trialAvailable);
    } catch (e) {
      card.textContent = "";
      card.append(el("p", "bad", e.message));
    }
  }

  function drawPicker(trialAvailable) {
    $("plan-note").textContent = trialAvailable
      ? `Try any plan free for ${plans.trial.days} days with ${plans.trial.storageGb} GB. A card is needed; cancel before the trial ends and you pay nothing.`
      : "Pick a plan:";
    const list = $("plan-list");
    list.textContent = "";
    for (const p of plans.plans) {
      const card = el("div", "plan" + (p.id === "plus" ? " featured" : ""));
      card.append(
        el("div", "name", p.id[0].toUpperCase() + p.id.slice(1)),
        el("div", "size", `${p.storageGb} GB`),
        el("div", "year", `$${p.monthly.toFixed(2)} a month or $${p.yearly.toFixed(2)} a year`),
      );
      const row = el("div", "buttons");
      const choice = (label, period, trial) => {
        const b = el("button", "btn" + (trial ? " primary" : ""), label);
        b.type = "button";
        b.addEventListener("click", () => act("plan-msg", async () => {
          location.href = (await api("POST", "/checkout", { plan: p.id, period, trial })).url;
        }));
        row.append(b);
      };
      if (trialAvailable) choice("Start free trial", "monthly", true);
      else choice("Monthly", "monthly", false);
      choice("Yearly", "yearly", false);
      card.append(row);
      list.append(card);
    }
  }

  async function act(msgId, fn) {
    say(msgId, "One moment…");
    try {
      await fn();
    } catch (e) {
      say(msgId, e.message, true);
    }
  }

  // ── Uploads ─────────────────────────────────────────────────────────
  const queue = [];
  let busy = false;
  const drop = $("drop");
  ["dragenter", "dragover"].forEach((t) => drop.addEventListener(t, (e) => { e.preventDefault(); drop.classList.add("over"); }));
  ["dragleave", "drop"].forEach((t) => drop.addEventListener(t, () => drop.classList.remove("over")));
  drop.addEventListener("drop", (e) => { e.preventDefault(); add(e.dataTransfer.files); });
  $("files").addEventListener("change", (e) => { add(e.target.files); e.target.value = ""; });

  function add(files) {
    for (const file of files) {
      const li = el("li", "q-item");
      const name = el("span", "q-name", file.name);
      const status = el("span", "q-status muted small", "Waiting");
      const bar = el("div", "meter small");
      const fill = el("i");
      bar.append(fill);
      li.append(name, status, bar);
      $("queue").append(li);
      if (moving) M.animate(li, { opacity: [0, 1], x: [-16, 0] }, { duration: 0.35 });
      queue.push({ file, li, status, fill });
    }
    next();
  }

  async function next() {
    if (busy) return;
    const job = queue.shift();
    if (!job) return loadPlan();
    busy = true;
    try {
      await upload(job);
      job.status.textContent = "Done";
      job.li.classList.add("done");
      loadSongs(true);
    } catch (e) {
      job.status.textContent = e.message || "Failed";
      job.li.classList.add("failed");
    }
    busy = false;
    next();
  }

  const progress = (job, frac) => { job.fill.style.transform = `scaleX(${frac})`; };

  async function upload(job) {
    const { file } = job;
    job.status.textContent = "Reading tags…";
    const tags = await readTags(file);
    const duration = await readDuration(file);
    const suffix = (file.name.split(".").pop() || "").toLowerCase();
    const meta = {
      title: tags.title || file.name.replace(/\.[^.]+$/, ""),
      artist: tags.artist, album: tags.album, albumArtist: tags.albumArtist,
      track: tags.track, disc: tags.disc, year: tags.year, genre: tags.genre,
      duration: Math.round(duration || 0),
    };
    const start = await api("POST", "/uploads", { size: file.size, suffix, meta });
    try {
      const parts = [];
      let sent = 0;
      for (let n = 1; n <= start.parts; n++) {
        const chunk = file.slice((n - 1) * start.partSize, Math.min(file.size, n * start.partSize));
        const part = await sendPart(start.id, n, chunk, (loaded) => {
          progress(job, (sent + loaded) / file.size);
          job.status.textContent = `Uploading ${Math.round(((sent + loaded) / file.size) * 100)}%`;
        });
        sent += chunk.size;
        parts.push(part);
      }
      job.status.textContent = "Finishing…";
      const song = await api("POST", `/uploads/${start.id}/complete`, { parts });
      if (tags.picture) {
        const cover = await coverOf(tags.picture).catch(() => null);
        if (cover) await api("PUT", `/songs/${song.id}/cover`, cover, true).catch(() => {});
      }
      progress(job, 1);
    } catch (e) {
      // Frees the reserved space now rather than at the server's cleanup.
      api("DELETE", `/uploads/${start.id}`).catch(() => {});
      throw e;
    }
  }

  /** One part, with upload progress (fetch has none), tried again on a dropped connection. */
  async function sendPart(id, n, chunk, onProgress) {
    for (let attempt = 1; ; attempt++) {
      const t = await token();
      try {
        return await new Promise((resolve, reject) => {
          const x = new XMLHttpRequest();
          x.open("PUT", `${API}/v1/uploads/${id}/parts/${n}`);
          x.setRequestHeader("authorization", `Bearer ${t}`);
          x.upload.onprogress = (e) => onProgress(e.loaded);
          x.onload = () => {
            if (x.status >= 200 && x.status < 300) resolve(JSON.parse(x.responseText));
            else {
              let code;
              try { code = JSON.parse(x.responseText).error; } catch (_) {}
              reject(new ApiError(x.status, code));
            }
          };
          x.onerror = () => reject(new Error("network"));
          x.send(chunk);
        });
      } catch (e) {
        if (e instanceof ApiError || attempt >= 4) throw e.message === "network" ? new Error("The connection dropped.") : e;
        await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt));
      }
    }
  }

  function readTags(file) {
    return new Promise((resolve) => {
      if (!window.jsmediatags) return resolve({});
      const done = setTimeout(() => resolve({}), 15000);
      window.jsmediatags.read(file, {
        onSuccess: ({ tags }) => {
          clearTimeout(done);
          const num = (v) => { const n = parseInt(String(v ?? "").split("/")[0], 10); return Number.isFinite(n) && n > 0 ? n : null; };
          const text = (v) => (typeof v === "string" && v.trim() ? v.trim() : typeof v?.data === "string" && v.data.trim() ? v.data.trim() : null);
          resolve({
            title: text(tags.title), artist: text(tags.artist), album: text(tags.album),
            albumArtist: text(tags.TPE2) || text(tags.ALBUMARTIST) || text(tags.aART),
            track: num(tags.track), disc: num(text(tags.TPOS) || text(tags.DISCNUMBER) || tags.disk?.data?.disk),
            year: num(tags.year), genre: text(tags.genre), picture: tags.picture || null,
          });
        },
        onError: () => { clearTimeout(done); resolve({}); },
      });
    });
  }

  function readDuration(file) {
    return new Promise((resolve) => {
      const audio = new Audio();
      const url = URL.createObjectURL(file);
      const finish = (v) => { URL.revokeObjectURL(url); resolve(Number.isFinite(v) ? v : 0); };
      const stop = setTimeout(() => finish(0), 10000);
      audio.preload = "metadata";
      audio.onloadedmetadata = () => { clearTimeout(stop); finish(audio.duration); };
      audio.onerror = () => { clearTimeout(stop); finish(0); };
      audio.src = url;
    });
  }

  /** The embedded picture as JPEG, PNG or WebP under 1 MB; anything else is redrawn as a 600 px JPEG. */
  async function coverOf(picture) {
    const bytes = new Uint8Array(picture.data);
    const type = (picture.format || "").toLowerCase();
    const ok = /jpe?g|png|webp/.test(type);
    if (ok && bytes.length <= 1_000_000) return new Blob([bytes], { type: type.includes("/") ? type : `image/${type}` });
    const bmp = await createImageBitmap(new Blob([bytes], { type: type || "image/jpeg" }));
    const scale = Math.min(1, 600 / Math.max(bmp.width, bmp.height));
    const c = document.createElement("canvas");
    c.width = Math.round(bmp.width * scale);
    c.height = Math.round(bmp.height * scale);
    c.getContext("2d").drawImage(bmp, 0, 0, c.width, c.height);
    return new Promise((r) => c.toBlob(r, "image/jpeg", 0.86));
  }

  // ── The library ─────────────────────────────────────────────────────
  let offset = 0;
  let songs = []; // the songs listed, in order: the player's queue when it plays from here
  let query = "";
  let searchTimer = null;
  $("lib-search").addEventListener("input", (e) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { query = e.target.value.trim(); loadSongs(true); }, 250);
  });
  $("lib-more").addEventListener("click", () => loadSongs(false));

  const coverCache = new Map();
  const coverObserver = "IntersectionObserver" in window ? new IntersectionObserver((entries) => {
    for (const en of entries) {
      if (!en.isIntersecting) continue;
      coverObserver.unobserve(en.target);
      loadCover(en.target);
    }
  }, { rootMargin: "200px" }) : null;

  async function loadCover(img) {
    const id = img.dataset.song;
    try {
      if (!coverCache.has(id)) {
        const res = await api("GET", `/songs/${id}/cover`);
        coverCache.set(id, URL.createObjectURL(await res.blob()));
      }
      img.src = coverCache.get(id);
    } catch (_) { /* no cover: keep the placeholder */ }
  }

  async function loadSongs(reset) {
    if (reset) offset = 0;
    try {
      const r = await api("GET", `/songs?offset=${offset}${query ? `&q=${encodeURIComponent(query)}` : ""}`);
      const list = $("songs");
      if (reset) { list.textContent = ""; songs = []; }
      $("lib-count").textContent = r.total ? `${r.total} song${r.total === 1 ? "" : "s"}` : "";
      if (!r.total && reset) list.append(el("li", "muted", query ? "Nothing matches that." : "Nothing here yet. Upload some music above, or from the app."));
      for (const s of r.songs) { songs.push(s); list.append(songRow(s)); }
      offset += r.songs.length;
      if (!reset) player.extend(songs);
      $("lib-play").hidden = !songs.length;
      $("lib-more").hidden = offset >= r.total;
      if (moving && reset) M.animate("#songs > li", { opacity: [0, 1], y: [10, 0] }, { delay: M.stagger(0.02, { from: "first" }), duration: 0.3 });
    } catch (e) {
      $("songs").textContent = "";
      $("songs").append(el("li", "bad", e.message));
    }
  }

  function songRow(s) {
    const li = el("li", "song");
    li.dataset.id = s.id;
    if (player.current() === s.id) li.classList.add("is-current");
    const img = el("img", "cover");
    img.alt = "";
    img.width = 44;
    img.height = 44;
    img.dataset.song = s.id;
    img.src = "data:image/svg+xml;utf8," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 44 44"><rect width="44" height="44" rx="8" fill="#8884"/></svg>');
    if (s.hasCover) coverObserver ? coverObserver.observe(img) : loadCover(img);
    const text = el("div", "song-text");
    text.append(el("strong", null, s.title), el("span", "muted small", [s.artist, s.album].filter(Boolean).join(" · ")));
    // The cover is the play button; the title plays too.
    const play = el("button", "song-play");
    play.type = "button";
    const canPlay = player.playable(s);
    play.setAttribute("aria-label", canPlay ? `Play ${s.title}` : `${s.title} plays in the app only`);
    play.disabled = !canPlay;
    play.append(img, el("span", "song-play-icon"));
    const start = () => {
      if (!canPlay) return;
      if (player.current() === s.id) return player.toggle();
      player.play(songs, songs.findIndex((x) => x.id === s.id));
    };
    play.addEventListener("click", start);
    text.addEventListener("click", start);
    if (canPlay) text.classList.add("is-clickable");
    else text.append(el("span", "app-only small", "App only"));
    const facts = el("span", "song-facts muted small",
      [clock(s.duration), (s.suffix || "").toUpperCase(), s.bitDepth && s.sampleRate ? `${s.bitDepth}/${(s.sampleRate / 1000).toFixed(1).replace(/\.0$/, "")}` : null, gb(s.size)]
        .filter(Boolean).join(" · "));
    const del = el("button", "icon-btn", "Delete");
    del.type = "button";
    del.setAttribute("aria-label", `Delete ${s.title}`);
    del.addEventListener("click", async () => {
      if (!confirm(`Delete "${s.title}" from Tryptify Cloud? This can't be undone.`)) return;
      try {
        await api("DELETE", `/songs/${s.id}`);
        if (moving) await M.animate(li, { opacity: 0, x: 40 }, { duration: 0.25 });
        li.remove();
        songs = songs.filter((x) => x.id !== s.id);
        player.forget(s.id);
        $("lib-play").hidden = !songs.length;
        loadPlan();
      } catch (e) {
        alert(e.message);
      }
    });
    li.append(play, text, facts, del);
    return li;
  }

  // ── Your server ─────────────────────────────────────────────────────
  async function loadServer() {
    try {
      const r = await api("GET", "/subsonic-keys");
      $("srv-url").textContent = API;
      $("srv-user").textContent = r.username;
      const list = $("devices");
      list.textContent = "";
      if (!r.devices.length) list.append(el("li", "muted", "No apps or devices signed in yet."));
      for (const d of r.devices) {
        const li = el("li", "device");
        li.append(el("span", null, d.label || "Unnamed device"), el("span", "muted small", `since ${date(d.createdAt)}`));
        const out = el("button", "icon-btn", "Sign out");
        out.type = "button";
        out.addEventListener("click", () => act("server-msg", async () => {
          await api("DELETE", `/subsonic-keys/${d.id}`);
          say("server-msg", `${d.label || "That device"} is signed out.`);
          loadServer();
        }));
        li.append(out);
        list.append(li);
      }
    } catch (e) {
      say("server-msg", e.message, true);
    }
  }
  $("key-form").addEventListener("submit", (e) => {
    e.preventDefault();
    act("server-msg", async () => {
      const r = await api("POST", "/subsonic-keys", { label: e.target.label.value.trim() });
      $("key-pass").textContent = r.password;
      $("new-key").hidden = false;
      e.target.reset();
      say("server-msg", "Use the address, username and this password in the app. It is shown once.");
      loadServer();
    });
  });
  $("sign-out-all").addEventListener("click", () => {
    if (!confirm("Sign out every app and device, including the Tryptify app? Each will need to connect again.")) return;
    act("server-msg", async () => {
      await api("DELETE", "/subsonic-keys");
      say("server-msg", "Every app and device is signed out.");
      loadServer();
    });
  });
  document.addEventListener("click", (e) => {
    const b = e.target.closest(".copy");
    if (!b) return;
    navigator.clipboard?.writeText($(b.dataset.copy).textContent).then(() => {
      b.textContent = "Copied";
      setTimeout(() => { b.textContent = "Copy"; }, 1400);
    });
  });
})();

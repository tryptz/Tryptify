// SPDX-License-Identifier: GPL-3.0-or-later
// Every room and every phone, in one Durable Object: the Rust server's hub
// (tryptz/tryptify-party, src/lib.rs), moved to Cloudflare as it was. Trypt
// Mania's game rooms (src/room.ts) and the listening parties (src/party.ts)
// keep their rules; this holds the sockets, the seats, the song keys, the
// timers, and the shared songs, which live in R2 instead of on a disk.
//
// One object for everything, as the Rust server was one process: a room's
// code, a song key and a website token are looked up across all of them, and
// the rooms are listed for everyone. It lives in memory, so a deploy ends the
// rooms and parties, as a Railway redeploy did. Sockets are not hibernated:
// the rooms' and parties' timers run while anyone is connected.

import { DurableObject } from "cloudflare:workers";
import { Catalog, catalogKey, tidalId } from "./catalog";
import { PLAYS_AS_IS, webKey } from "./ffmpeg";
import { Party, RESUME_MS, PARTY_QUOTA_BYTES, type MemberIn, type Out as PartyOut } from "./party";
import { audioType, byteRange, cleanExt, parseClient, randomHex, type ClientMsg } from "./protocol";
import * as R from "./room";

export interface Env {
  HUB: DurableObjectNamespace<Hub>;
  SONGS: R2Bucket;
  SUPABASE_URL?: string;
  SUPABASE_ANON_KEY?: string;
  TRYPT_HIFI_URL?: string;
  TRYPT_HIFI_KEY?: string;
  /** The ffmpeg service (ffmpeg/, on Railway) and its key. Without them the original goes. */
  FFMPEG_URL?: string;
  FFMPEG_KEY?: string;
}

export const VERSION = "0.2.0";
export const SERVICE = "tryptmania-multiplayer";

/** How often a party checks whether its song is over, and scores go round a game. */
const TICK_MS = 250;
/** A socket silent this long is let go (the phones and the website ping every 2 s). */
const SILENT_MS = 30_000;
const HEARTBEAT_MS = 10_000;
/** How often an upload's progress goes round the party, at most. */
const PROGRESS_MS = 500;
/**
 * The biggest song that may be shared. A Worker takes a request body of at
 * most 100 MB on this zone's plan, so a little under that, not the Rust
 * server's 128 MiB.
 */
export const SONG_LIMIT = 99_000_000;
const MAX_MESSAGE = R.MAX_NOTES_BYTES + 64 * 1024;
const MAX_COVER_BYTES = 4 * 1024 * 1024;
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

interface Conn {
  name: string;
  user: string | null;
  /** Where its messages go; null while a party member is away. */
  ws: WebSocket | null;
  /** Which socket holds the seat now; an older one that closes late leaves it alone. */
  socket: number;
  room: string | null;
  key: string;
  guest: boolean;
  web: boolean;
}

interface Session {
  no: number;
  ws: WebSocket;
  id: string | null;
  heard: number;
  queue: Promise<void>;
  closed: boolean;
}

type Msg = Record<string, unknown>;
type Deliveries = { to: string; msg: Msg }[];
type Refusal = { status: number; error: string };

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
const refuse = ({ status, error }: Refusal) => json({ success: false, error }, status);
const now = () => Date.now();
/** A message as it goes on the wire: a game room's as serde wrote it (src/room.ts encode), everything else as JSON.stringify. */
const wireOf = (msg: Msg, serde: boolean) => (serde ? R.encode(msg) : JSON.stringify(msg));

export class Hub extends DurableObject<Env> {
  rooms = new Map<string, R.Room>();
  parties = new Map<string, Party>();
  conns = new Map<string, Conn>();
  /** Song-route key to player id. */
  keys = new Map<string, string>();
  sessions = new Set<Session>();
  timers = new Map<string, ReturnType<typeof setInterval>>();
  nextId = 1;
  nextSocket = 1;
  resumeMs = RESUME_MS;
  /** Per shared song: its website copy's key once made, null when the original plays in a browser, or the making of it. */
  webCopies = new Map<string, string | null | Promise<string | null>>();
  catalog: Catalog | null;
  heartbeat: ReturnType<typeof setInterval> | null = null;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.catalog = Catalog.from(env);
    // A fresh hub has no rooms, so any song left in the bucket belongs to nobody.
    ctx.waitUntil(this.sweep());
  }

  async sweep() {
    let cursor: string | undefined;
    do {
      const page = await this.env.SONGS.list({ cursor, limit: 1000 });
      if (page.objects.length) await this.env.SONGS.delete(page.objects.map((o) => o.key));
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
  }

  deleteFiles(keys: Iterable<string>) {
    // And the website's copy of each, where one was made.
    const list = [...keys].filter(Boolean).flatMap((k) => [k, webKey(k)]);
    for (const k of list) this.webCopies.delete(k);
    if (list.length) this.ctx.waitUntil(this.env.SONGS.delete(list).catch(() => {}));
  }

  // ── HTTP ──────────────────────────────────────────────────────────────

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const q = url.searchParams;
    if (path === "/ws") {
      if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") return new Response("expected a WebSocket", { status: 426 });
      return this.upgrade();
    }
    if (path === "/api/multiplayer/info") return this.info();
    const m = request.method;
    if (path === "/api/multiplayer/song") {
      if (!q.has("hash")) return new Response("Failed to deserialize query string: missing field `hash`", { status: 400 });
      if (m === "PUT") return this.songPut(request, q.get("hash")!, cleanExt(q.get("ext") ?? ""));
      if (m === "GET" || m === "HEAD") return this.songGet(request, q.get("hash")!);
    }
    if (path === "/api/party/song") {
      if (!q.has("item")) return new Response("Failed to deserialize query string: missing field `item`", { status: 400 });
      if (m === "PUT") return this.partySongPut(request, q.get("item")!, cleanExt(q.get("ext") ?? ""));
      if (m === "GET" || m === "HEAD") return this.partySongGet(request, q.get("item")!, (q.get("token") ?? "").trim(), q.get("web") === "1");
    }
    if (m === "GET" && path === "/api/party/cover") return this.partyCover((q.get("token") ?? "").trim(), q.get("item") ?? "");
    if (m === "GET" && path === "/api/party/search") return this.partySearch((q.get("token") ?? "").trim(), q.get("q") ?? "");
    if (m === "GET" && path === "/api/party/stream") return this.partyStream((q.get("token") ?? "").trim(), q.get("item") ?? "");
    return new Response(null, { status: 404 });
  }

  info() {
    return json({
      success: true,
      data: {
        service: SERVICE,
        version: VERSION,
        rooms: this.rooms.size,
        parties: this.parties.size,
        features: this.catalog ? ["party", "catalog"] : ["party"],
        partyQuotaBytes: PARTY_QUOTA_BYTES,
        maxPlayers: R.MAX_PLAYERS,
        maxSongBytes: SONG_LIMIT,
        leadMs: R.LEAD_MS,
        serverTime: now(),
      },
    });
  }

  // ── Sockets ───────────────────────────────────────────────────────────

  upgrade(): Response {
    const pair = new WebSocketPair();
    const ws = pair[1];
    ws.accept();
    const session: Session = { no: this.nextSocket++, ws, id: null, heard: now(), queue: Promise.resolve(), closed: false };
    this.sessions.add(session);
    this.startHeartbeat();
    ws.addEventListener("message", (event) => {
      session.heard = now();
      if (typeof event.data !== "string") return;
      const text = event.data;
      if (text.length > MAX_MESSAGE) { this.endSession(session); return; }
      // In order, one at a time: a hello waits on its sign-in check, and what
      // the phone says next waits behind it.
      session.queue = session.queue.then(() => this.onText(session, text)).catch((e) => console.error("message", e));
    });
    const gone = () => this.endSession(session);
    ws.addEventListener("close", gone);
    ws.addEventListener("error", gone);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  startHeartbeat() {
    if (this.heartbeat) return;
    this.heartbeat = setInterval(() => {
      const t = now();
      for (const s of this.sessions) if (t - s.heard > SILENT_MS) this.endSession(s);
      if (!this.sessions.size && this.heartbeat) { clearInterval(this.heartbeat); this.heartbeat = null; }
    }, HEARTBEAT_MS);
  }

  /**
   * Sends to one socket; a dead one is let go. A game room's messages go out
   * as serde wrote them (src/room.ts encode: 1.0, not 1); everything else as
   * JSON.stringify writes it, as the party's rules in C++ did.
   */
  push(ws: WebSocket | null, msg: Msg, serde = false) {
    if (!ws) return;
    try { ws.send(wireOf(msg, serde)); } catch { /* closed already */ }
  }

  pushText(ws: WebSocket, wire: string) {
    try { ws.send(wire); } catch { /* closed already */ }
  }

  endSession(session: Session) {
    if (session.closed) return;
    session.closed = true;
    this.sessions.delete(session);
    try { session.ws.close(1000, "bye"); } catch { /* gone */ }
    if (session.id) this.disconnected(session.id, session.no);
  }

  async onText(session: Session, text: string) {
    if (session.closed) return;
    const msg = parseClient(text);
    if ("error" in msg) { this.push(session.ws, { t: "error", message: msg.error }); return; }
    if (session.id) { this.handle(session.id, msg); return; }
    if (msg.t === "ping") { this.push(session.ws, { t: "pong", c: msg.c, s: now() }); return; }
    if (msg.t !== "hello") { this.push(session.ws, { t: "error", message: "say hello first" }); return; }
    const party = msg.party.trim().toUpperCase();
    if (msg.guest && !this.parties.has(party)) {
      // A website guest needs no account, only a party's code.
      this.push(session.ws, { t: "error", message: "no party with that code" });
      this.endSession(session);
      return;
    }
    let user: string | null = null;
    if (!msg.guest && this.env.SUPABASE_URL?.trim() && this.env.SUPABASE_ANON_KEY?.trim()) {
      const who = await this.verify(msg.token);
      if ("error" in who) {
        this.push(session.ws, { t: "error", message: who.error });
        this.endSession(session);
        return;
      }
      user = who.id;
    }
    if (session.closed) return;
    if (msg.resume.trim()) {
      const back = this.resume(msg.resume.trim(), user, session);
      if (back) { session.id = back; return; }
    }
    let name = Array.from(msg.name.trim()).slice(0, 24).join("");
    if (!name) name = msg.guest ? "Guest" : "Player";
    const id = `p${this.nextId++}`;
    const key = randomHex(32);
    this.keys.set(key, id);
    this.conns.set(id, { name, user, ws: session.ws, socket: session.no, room: null, key, guest: msg.guest, web: msg.guest || msg.client === "web" });
    session.id = id;
    this.push(session.ws, { t: "welcome", id, serverTime: now(), version: VERSION, user, key, resumed: false });
    if (msg.guest) this.joinParty(id, party);
  }

  /** The account id behind a Supabase access token, or why it is not one. */
  async verify(token: string): Promise<{ id: string } | { error: string }> {
    const t = token.trim();
    if (!t) return { error: "sign in to play with others" };
    try {
      const base = this.env.SUPABASE_URL!.trim().replace(/\/+$/, "");
      const r = await fetch(`${base}/auth/v1/user`, {
        headers: { authorization: `Bearer ${t}`, apikey: this.env.SUPABASE_ANON_KEY!.trim() },
        signal: AbortSignal.timeout(10_000),
      });
      if (!r.ok) return { error: "sign in to play with others" };
      const body = (await r.json()) as { id?: unknown };
      return typeof body.id === "string" && body.id ? { id: body.id } : { error: "sign in to play with others" };
    } catch (e) {
      return { error: `could not check your sign-in: ${(e as Error).message}` };
    }
  }

  // ── Delivery ──────────────────────────────────────────────────────────

  send(id: string, msg: Msg, serde = false) {
    this.push(this.conns.get(id)?.ws ?? null, msg, serde);
  }

  /** A room's or a party's answers: "" or "all" to everyone in it as they are now. */
  deliver(code: string, out: Deliveries) {
    const room = this.rooms.get(code);
    for (const { to, msg } of out) {
      if (to === "" || to === "all") {
        const ids = room ? room.players.map((p) => p.id) : this.parties.get(code)?.memberIds() ?? [];
        // Everyone gets the same text: written once, not once per player (a
        // game's live scores go round four times a second, a party's view of
        // its whole queue on every move), and not at all when nobody is here.
        let wire: string | undefined;
        for (const id of ids) {
          const ws = this.conns.get(id)?.ws;
          if (ws) this.pushText(ws, (wire ??= wireOf(msg, !!room)));
        }
      } else this.send(to, msg, !!room);
    }
  }

  newCode(): string {
    for (;;) {
      const bytes = crypto.getRandomValues(new Uint8Array(4));
      const code = Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join("");
      if (!this.rooms.has(code) && !this.parties.has(code)) return code;
    }
  }

  stopTimer(code: string) {
    const t = this.timers.get(code);
    if (t) { clearInterval(t); this.timers.delete(code); }
  }

  /** Takes `id` out of its room, if in one; an empty room goes with them. */
  leaveRoom(id: string) {
    const conn = this.conns.get(id);
    const code = conn?.room;
    if (!conn || !code) return;
    conn.room = null;
    const party = this.parties.get(code);
    if (party) {
      const out = party.leave(id, now());
      const gone = party.takeDropped();
      if (party.empty()) {
        // The party is over, and every song shared with it goes.
        gone.push(...party.files());
        this.parties.delete(code);
        this.stopTimer(code);
      }
      this.deleteFiles(gone);
      this.deliver(code, out);
      return;
    }
    const room = this.rooms.get(code);
    if (!room) return;
    const out = room.leave(id, now());
    if (room.isEmpty()) {
      // The room is gone, and its song with it.
      this.deleteFiles(room.takeFiles());
      this.rooms.delete(code);
      this.stopTimer(code);
    }
    this.deliver(code, out);
  }

  // ── What a phone says ─────────────────────────────────────────────────

  handle(id: string, msg: ClientMsg) {
    const conn = this.conns.get(id);
    if (!conn) return;
    const reply = (m: Msg) => this.send(id, m);
    const error = (message: string) => reply({ t: "error", message });
    if (msg.t === "hello") return;
    if (conn.guest && !["ping", "leave", "chat", "skipVote", "queueAdd"].includes(msg.t)) {
      error("guests can listen, chat, vote to skip and add songs from the catalogue");
      return;
    }
    switch (msg.t) {
      case "ping": reply({ t: "pong", c: msg.c, s: now() }); return;
      case "rooms": {
        this.send(id, R.listRooms([...this.rooms.values()]), true);
        return;
      }
      case "create":
        if (msg.kind === "party") {
          this.leaveRoom(id);
          this.releaseOtherSeats(id);
          const code = this.newCode();
          const host = this.member(id, conn);
          conn.room = code;
          const party = new Party(code, msg.name, host, SONG_LIMIT);
          this.parties.set(code, party);
          this.deliver(code, party.welcomeBack(id));
          this.runParty(code);
        } else {
          this.leaveRoom(id);
          const code = this.newCode();
          const { room, out } = R.Room.create(code, msg.name, R.newPlayer(id, conn.name, conn.user));
          conn.room = code;
          this.rooms.set(code, room);
          this.deliver(code, out);
        }
        return;
      case "join": {
        const code = msg.code.trim().toUpperCase();
        if (conn.room === code) return;
        if (this.parties.has(code)) {
          this.leaveRoom(id);
          this.joinParty(id, code);
          return;
        }
        this.leaveRoom(id);
        const room = this.rooms.get(code);
        const result = room ? room.join(R.newPlayer(id, conn.name, conn.user)) : { ok: false as const, error: "no room with that code" };
        if (result.ok) { conn.room = code; this.deliver(code, result.out); } else error(result.error);
        return;
      }
      case "leave":
        this.leaveRoom(id);
        reply({ t: "left", reason: "left" });
        return;
    }
    const code = conn.room;
    if (!code) { error("not in a room"); return; }
    const party = this.parties.get(code);
    if (party) { this.handleParty(id, code, party, msg); return; }
    const room = this.rooms.get(code);
    if (room) this.handleRoom(id, conn, code, room, msg);
  }

  handleParty(id: string, code: string, party: Party, msg: ClientMsg) {
    const t = now();
    let kicked: string | null = null;
    let ended = false;
    let result: { ok: true; out: PartyOut } | { ok: false; error: string };
    switch (msg.t) {
      case "chat": result = party.chat(id, msg.text, t); break;
      case "queueAdd": result = party.add(id, msg.item, msg.ref, t); break;
      case "queueRemove": result = party.remove(id, msg.id, t); break;
      case "queueMove": result = party.moveItem(id, msg.id, msg.to); break;
      case "pause": result = party.pause(id, t); break;
      case "resume": result = party.resume(id, t); break;
      case "seek": result = party.seek(id, msg.positionMs, t); break;
      case "setRate": result = party.setRate(id, msg.rate, msg.keepPitch, t); break;
      case "skip": result = party.skip(id, msg.itemId, t); break;
      case "skipVote": result = party.vote(id, msg.itemId, msg.vote, t); break;
      case "kick": result = party.kick(id, msg.id, t); if (result.ok) kicked = msg.id; break;
      case "passHost": result = party.passHost(id, msg.id, t); break;
      case "endParty": result = party.end(id); if (result.ok) ended = true; break;
      default: result = { ok: false, error: "that is for a game room, not a party" };
    }
    this.deleteFiles(party.takeDropped());
    if (!result.ok) { this.send(id, { t: "error", message: result.error }); return; }
    this.deliver(code, result.out);
    if (kicked) { const c = this.conns.get(kicked); if (c) c.room = null; }
    if (ended) {
      // Everyone was told; every seat and every shared song goes.
      for (const member of party.memberIds()) { const c = this.conns.get(member); if (c) c.room = null; }
      this.deleteFiles(party.files());
      this.parties.delete(code);
      this.stopTimer(code);
    }
  }

  handleRoom(id: string, conn: Conn, code: string, room: R.Room, msg: ClientMsg) {
    const t = now();
    let kicked: string | null = null;
    let started = false;
    let result: { ok: true; out: Deliveries } | { ok: false; error: string };
    switch (msg.t) {
      case "pick": {
        const chart = R.parseChart(msg.chart);
        result = chart.ok ? room.pick(id, chart.value) : { ok: false, error: chart.error };
        break;
      }
      case "have": result = { ok: true, out: room.have(id, msg.audio) }; break;
      case "ready": result = room.ready(id, msg.ready); break;
      case "start": result = room.start(id, t); if (result.ok) started = true; break;
      case "kick": result = room.kick(id, msg.id, t); if (result.ok) kicked = msg.id; break;
      case "frame": {
        const live = R.parseLive(msg.live);
        if (!live.ok) result = { ok: false, error: live.error };
        else { room.frame(id, live.value); result = { ok: true, out: [] }; }
        break;
      }
      case "finish": {
        const fin = R.parseFinished(msg.result);
        result = fin.ok ? { ok: true, out: room.finish(id, fin.value, t) } : { ok: false, error: fin.error };
        break;
      }
      case "chat": {
        result = { ok: true, out: R.chatOut(id, conn.name, msg.text) };
        break;
      }
      case "queueAdd": case "queueRemove": case "queueMove": case "pause": case "resume": case "seek":
      case "setRate": case "skip": case "skipVote": case "passHost": case "endParty":
        result = { ok: false, error: "that is for a party, not a game room" };
        break;
      default: result = { ok: true, out: [] };
    }
    // A new chart lets the last one's song go.
    this.deleteFiles(room.takeDropped());
    if (!result.ok) { this.send(id, { t: "error", message: result.error }); return; }
    this.deliver(code, result.out);
    if (kicked) { const c = this.conns.get(kicked); if (c) c.room = null; }
    if (started) this.relayFrames(code);
  }

  member(id: string, conn: Conn): MemberIn {
    return { id, name: conn.name, user: conn.user, guest: conn.guest, web: conn.web, token: randomHex(32) };
  }

  /**
   * One account, one party: before `id` makes or joins a party, every other
   * party seat its account holds is let go, as if it had left.
   */
  releaseOtherSeats(id: string) {
    const conn = this.conns.get(id);
    if (!conn || conn.guest || !conn.user) return;
    const others = [...this.conns].filter(([other, c]) => other !== id && c.user === conn.user && !!c.room && this.parties.has(c.room)).map(([other]) => other);
    for (const other of others) {
      this.send(other, { t: "left", reason: "you joined another party" });
      this.leaveRoom(other);
      const c = this.conns.get(other);
      if (c) {
        this.conns.delete(other);
        this.keys.delete(c.key);
        for (const s of this.sessions) if (s.id === other) { s.id = null; this.endSession(s); }
      }
    }
  }

  /** `id` into party `code`: told the party, the chat and its song token. */
  joinParty(id: string, code: string) {
    this.releaseOtherSeats(id);
    const conn = this.conns.get(id);
    if (!conn) return;
    const party = this.parties.get(code);
    const result = party ? party.join(this.member(id, conn), now()) : { ok: false as const, error: "no party with that code" };
    if (result.ok) { conn.room = code; this.deliver(code, result.out); } else this.send(id, { t: "error", message: result.error });
  }

  /** A party's beat, for as long as it lasts: a song whose length has run out moves it on. */
  runParty(code: string) {
    this.stopTimer(code);
    this.timers.set(code, setInterval(() => {
      const party = this.parties.get(code);
      if (!party) { this.stopTimer(code); return; }
      const out = party.tick(now());
      this.deleteFiles(party.takeDropped());
      this.deliver(code, out);
    }, TICK_MS));
  }

  /** While a song plays: live scores round the room four times a second, and results when it is long over. */
  relayFrames(code: string) {
    this.stopTimer(code);
    const beat = () => {
      const room = this.rooms.get(code);
      if (!room || room.state !== "playing") { this.stopTimer(code); return; }
      const frames = room.frames();
      const ended = room.timeout(now());
      this.deliver(code, [{ to: "all", msg: frames }]);
      if (ended.length) { this.deliver(code, ended); this.stopTimer(code); }
    };
    this.timers.set(code, setInterval(beat, TICK_MS));
    beat();
  }

  /** A socket gone. A game player leaves at once; a party member is away, keeping their seat. */
  disconnected(id: string, socket: number) {
    const conn = this.conns.get(id);
    if (!conn || conn.socket !== socket) return;
    const code = conn.room && this.parties.has(conn.room) && !conn.guest ? conn.room : null;
    if (!code) {
      this.leaveRoom(id);
      this.conns.delete(id);
      this.keys.delete(conn.key);
      return;
    }
    conn.ws = null;
    const party = this.parties.get(code)!;
    const out = party.setAway(id, true, now());
    this.deleteFiles(party.takeDropped());
    this.deliver(code, out);
    setTimeout(() => {
      const c = this.conns.get(id);
      if (c && c.socket === socket && !c.ws) {
        this.leaveRoom(id);
        this.conns.delete(id);
        this.keys.delete(c.key);
      }
    }, this.resumeMs);
  }

  /** A phone back on a new socket: its seat, host role and all, and what it missed. */
  resume(key: string, user: string | null, session: Session): string | null {
    const id = this.keys.get(key);
    const conn = id ? this.conns.get(id) : undefined;
    if (!id || !conn || !conn.room || !this.parties.has(conn.room)) return null;
    if (!conn.guest && conn.user !== user) return null;
    // An old socket not yet known to be dead gives way.
    for (const s of this.sessions) if (s.id === id && s !== session) { s.id = null; this.endSession(s); }
    conn.ws = session.ws;
    conn.socket = session.no;
    this.send(id, { t: "welcome", id, serverTime: now(), version: VERSION, user: conn.user, key, resumed: true });
    const party = this.parties.get(conn.room)!;
    this.deliver(conn.room, [...party.welcomeBack(id), ...party.setAway(id, false, now())]);
    return id;
  }

  // ── Game songs ────────────────────────────────────────────────────────

  playerFor(key: string): { id: string; code: string } | Refusal {
    const id = this.keys.get(key);
    if (!id) return { status: 401, error: "no player with that key" };
    const code = this.conns.get(id)?.room;
    if (!code) return { status: 403, error: "not in a room" };
    return { id, code };
  }

  async songPut(request: Request, hash: string, ext: string): Promise<Response> {
    const key = roomKey(request);
    const who = this.playerFor(key);
    if ("error" in who) return refuse(who);
    const room = this.rooms.get(who.code);
    if (!room) return refuse({ status: 403, error: "not in a room" });
    if (room.host !== who.id) return refuse({ status: 403, error: "only the host shares the song" });
    if (room.chart?.hash !== hash) return refuse({ status: 409, error: "that is not the picked chart's song" });
    const short = hash.replace(/[^A-Za-z0-9]/g, "").slice(0, 16);
    const file = `room/${who.code}-${short}-${randomHex(8)}.${ext}`;
    const written = await this.write(request, file, SONG_LIMIT);
    if ("error" in written) return refuse(written);
    const again = this.playerFor(key);
    const now_ = "error" in again ? null : this.rooms.get(again.code);
    const out = now_ && !("error" in again) ? now_.share(again.id, { hash, bytes: written.bytes, ext }, file) : null;
    if (!out || !out.ok) {
      this.deleteFiles([file]);
      return refuse("error" in again ? again : { status: 409, error: "the room picked another chart" });
    }
    this.deleteFiles(now_!.takeDropped());
    this.deliver((again as { code: string }).code, out.out);
    return json({ success: true, bytes: written.bytes });
  }

  async songGet(request: Request, hash: string): Promise<Response> {
    const who = this.playerFor(roomKey(request));
    if ("error" in who) return refuse(who);
    const found = this.rooms.get(who.code)?.sharedFor(who.id, hash);
    if (!found) return refuse({ status: 404, error: "no song shared for that chart" });
    const object = await this.env.SONGS.get(found.path);
    if (!object) return refuse({ status: 404, error: "no song shared for that chart" });
    return new Response(request.method === "HEAD" ? null : object.body, {
      headers: { "content-type": audioType(found.song.ext), "content-length": String(found.song.bytes), "x-song-ext": found.song.ext },
    });
  }

  /**
   * A request's body into the bucket at `file`, at most `limit` bytes, telling
   * `progress` as it goes. Its length must be said up front (Content-Length):
   * R2 takes a stream only of a known length, and the phones always say it.
   */
  async write(request: Request, file: string, limit: number, progress?: (received: number) => void): Promise<{ bytes: number } | Refusal> {
    const declared = Number(request.headers.get("content-length") ?? "");
    if (!request.body || !Number.isFinite(declared) || declared <= 0) {
      return declared === 0 ? { status: 400, error: "the song is empty" } : { status: 411, error: "say the song's length (Content-Length)" };
    }
    if (declared > limit) return { status: 413, error: "the song is too big to share" };
    let received = 0;
    const counter = new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        received += chunk.byteLength;
        if (received > limit) { controller.error(new Error("too big")); return; }
        progress?.(received);
        controller.enqueue(chunk);
      },
    });
    const sized = new FixedLengthStream(declared);
    try {
      await Promise.all([
        request.body.pipeThrough(counter).pipeTo(sized.writable),
        this.env.SONGS.put(file, sized.readable),
      ]);
    } catch {
      this.deleteFiles([file]);
      return received > limit ? { status: 413, error: "the song is too big to share" } : { status: 400, error: "the upload broke off" };
    }
    return { bytes: received };
  }

  // ── Party songs ───────────────────────────────────────────────────────

  partyFor(key: string): { id: string; code: string; party: Party } | Refusal {
    const who = this.playerFor(key);
    if ("error" in who) return who;
    const party = this.parties.get(who.code);
    if (!party) return { status: 403, error: "not at a party" };
    return { ...who, party };
  }

  async partySongPut(request: Request, item: string, ext: string): Promise<Response> {
    const key = roomKey(request);
    const declared = Number(request.headers.get("content-length") ?? "0") || 0;
    const who = this.partyFor(key);
    if ("error" in who) return refuse(who);
    const { code, party } = who;
    const limit = party.uploadLimit(who.id, item, declared);
    if ("error" in limit) {
      const refusal = partyRefusal(limit.error);
      // Too big to share at all: it shows as not shared, not as an upload that never moves.
      if (refusal.status === 413 && party.member(who.id)) this.deliver(code, party.uploadFailed(item));
      return refuse(refusal);
    }
    this.deliver(code, party.uploading(item, declared, 0));
    const short = item.replace(/[^A-Za-z0-9]/g, "").slice(0, 16);
    const file = `party/${code}-${short}-${randomHex(8)}.${ext}`;
    let last = 0;
    const written = await this.write(request, file, limit.limit, (received) => {
      if (now() - last < PROGRESS_MS) return;
      last = now();
      const p = this.parties.get(code);
      if (p) this.deliver(code, p.uploading(item, declared, received));
    });
    if ("error" in written) {
      const p = this.parties.get(code);
      if (p) this.deliver(code, p.uploadFailed(item));
      return refuse(written.status === 413 && limit.limit < SONG_LIMIT ? { status: 413, error: "the party has no room for more shared songs" } : written);
    }
    const again = this.partyFor(key);
    if ("error" in again) { this.deleteFiles([file]); return refuse(again); }
    const result = again.party.shared(again.id, item, written.bytes, ext, file);
    this.deleteFiles(again.party.takeDropped());
    if (!result.ok) {
      this.deliver(again.code, again.party.uploadFailed(item));
      this.deleteFiles([file]);
      return refuse(partyRefusal(result.error));
    }
    this.deliver(again.code, result.out);
    return json({ success: true, bytes: written.bytes });
  }

  /** A shared song to a member (by key, or by the website's token), all of it or the range asked for. */
  async partySongGet(request: Request, item: string, token: string, web = false): Promise<Response> {
    const key = roomKey(request);
    let found: { song: { bytes: number; ext: string }; path: string } | null = null;
    if (key) {
      const who = this.partyFor(key);
      const t = "error" in who ? null : who.party.token(who.id);
      if (t && !("error" in who)) found = who.party.songFor(t, item);
    } else if (token) {
      for (const p of this.parties.values()) { found = p.songFor(token, item); if (found) break; }
    }
    if (!found) return refuse({ status: 404, error: "that song is not shared with this party" });
    // The website gets a file its browser plays (web=1): the original, or an
    // AAC copy of one in a codec no browser does, made by ffmpeg (src/ffmpeg.ts).
    if (web && !PLAYS_AS_IS.has(found.song.ext)) {
      const copy = await this.webCopy(found.path);
      if (copy) return this.sendFile(request, copy, "m4a", item);
    }
    return this.sendFile(request, found.path, found.song.ext, item, found.song.bytes);
  }

  /**
   * The website's copy of shared song `file`: null when the original plays in
   * a browser (or ffmpeg could not help, and the page then plays the
   * catalogue's copy), else the key of its AAC copy, made the first time it
   * is asked for, once, however many ask at the same time.
   */
  async webCopy(file: string): Promise<string | null> {
    const known = this.webCopies.get(file);
    if (known !== undefined) return known;
    const making = (async (): Promise<string | null> => {
      // No look in the bucket for a copy already there: the key is the song's
      // own random file name plus a suffix, and a copy is made once, here
      // (webCopies), so none exists that this hub does not know of.
      const key = webKey(file);
      const { FFMPEG_URL, FFMPEG_KEY } = this.env;
      if (!FFMPEG_URL || !FFMPEG_KEY) return null;
      try {
        // Inside the try: a failed read must settle as "no copy", or the
        // rejected promise stays in webCopies and every later ask rethrows.
        const object = await this.env.SONGS.get(file);
        if (!object) return null;
        const r = await fetch(new URL("/web", FFMPEG_URL), {
          method: "POST",
          headers: { "content-length": String(object.size), "content-type": "application/octet-stream", "x-access-key": FFMPEG_KEY },
          body: object.body,
        });
        if (r.status === 204) return null;
        if (!r.ok || !r.body) { console.log(`ffmpeg: ${r.status} for ${file}: ${await r.text()}`); return null; }
        const size = Number(r.headers.get("content-length"));
        if (!(size > 0)) return null;
        const sized = new FixedLengthStream(size);
        await Promise.all([r.body.pipeTo(sized.writable), this.env.SONGS.put(key, sized.readable, { httpMetadata: { contentType: "audio/mp4" } })]);
        return key;
      } catch (e) {
        console.log(`ffmpeg: could not convert ${file}: ${(e as Error).message}`);
        return null;
      }
    })();
    this.webCopies.set(file, making);
    const done = await making;
    // Forgotten if the song left meanwhile (deleteFiles), and its copy with it.
    if (this.webCopies.get(file) === making) this.webCopies.set(file, done);
    else if (done) this.deleteFiles([done]);
    return done;
  }

  /**
   * A shared song's bytes, all of it or the range asked for. [known] is its
   * size where the party already holds it (an original, not a website copy),
   * which saves asking the bucket for it on every range: a player seeking
   * asks for many. A whole GET learns the size from the one read that
   * fetches it.
   */
  async sendFile(request: Request, file: string, ext: string, name: string, known?: number): Promise<Response> {
    const gone = () => refuse({ status: 404, error: "that song is gone" });
    const base = (total: number): Record<string, string> => ({
      "content-type": audioType(ext),
      "accept-ranges": "bytes",
      etag: `"${name}-${total}"`,
      "cache-control": "private, no-store",
      "content-disposition": "inline",
      "x-song-ext": ext,
    });
    const asked = request.headers.get("range");
    const headOnly = request.method === "HEAD";
    if (!asked && !headOnly) {
      const object = await this.env.SONGS.get(file);
      if (!object) return gone();
      return new Response(object.body, { headers: { ...base(object.size), "content-length": String(object.size) } });
    }
    let total = headOnly ? undefined : known;
    if (total === undefined) {
      const head = await this.env.SONGS.head(file);
      if (!head) return gone();
      total = head.size;
    }
    if (!asked) return new Response(null, { headers: { ...base(total), "content-length": String(total) } });
    const range = byteRange(asked, total);
    if (!range) return new Response(null, { status: 416, headers: { ...base(total), "content-range": `bytes */${total}` } });
    const [first, last] = range;
    const length = last - first + 1;
    const object = headOnly ? null : await this.env.SONGS.get(file, { range: { offset: first, length } });
    if (!headOnly && !object) return gone();
    return new Response(object?.body ?? null, {
      status: 206,
      headers: { ...base(total), "content-length": String(length), "content-range": `bytes ${first}-${last}/${total}` },
    });
  }

  /** A song's cover for the website, from this origin, so the page may read its colours. */
  async partyCover(token: string, item: string): Promise<Response> {
    let url = "";
    for (const p of this.parties.values()) { url = p.coverFor(token, item); if (url) break; }
    if (!url.startsWith("https://")) return refuse({ status: 404, error: "no cover for that song" });
    let r: Response;
    try {
      r = await fetch(url, { redirect: "follow", signal: AbortSignal.timeout(10_000) });
    } catch {
      return refuse({ status: 502, error: "the cover could not be fetched" });
    }
    const kind = r.headers.get("content-type") ?? "";
    if (!r.ok || !kind.startsWith("image/")) return refuse({ status: 502, error: "the cover could not be fetched" });
    if (Number(r.headers.get("content-length") ?? 0) > MAX_COVER_BYTES) return refuse({ status: 502, error: "the cover is too big" });
    const body = await r.arrayBuffer();
    if (body.byteLength > MAX_COVER_BYTES) return refuse({ status: 502, error: "the cover is too big" });
    return new Response(body, { headers: { "content-type": kind, "cache-control": "private, max-age=3600" } });
  }

  /** Songs from the catalogue for the website's Add songs, for a party member only. */
  async partySearch(token: string, query: string): Promise<Response> {
    if (!this.catalog) return refuse({ status: 404, error: "this server has no catalogue" });
    if (![...this.parties.values()].some((p) => p.knowsToken(token))) return refuse({ status: 403, error: "not at the party" });
    const q = query.trim();
    if (!q || Array.from(q).length > 200) return refuse({ status: 400, error: "search for a song by its name or artist" });
    try {
      return json({ success: true, data: { items: await this.catalog.search(q) } });
    } catch (e) {
      return refuse({ status: 502, error: (e as Error).message });
    }
  }

  /**
   * A queue song from the catalogue, for the website to play: sent on to the
   * catalogue's one-file address, from its own catalogue by its own id (a
   * Qobuz song from Qobuz, a Deezer song from Deezer decrypted by TrypT HiFi,
   * a TIDAL song from TIDAL), else looked for on TIDAL by name and ISRC.
   */
  async partyStream(token: string, itemId: string): Promise<Response> {
    const catalog = this.catalog;
    if (!catalog) return refuse({ status: 404, error: "this server has no catalogue" });
    let item: Record<string, unknown> | null = null;
    for (const p of this.parties.values()) { item = p.itemFor(token, itemId) as Record<string, unknown> | null; if (item) break; }
    if (!item) return refuse({ status: 404, error: "no such song from the catalogue" });
    const text = (k: string) => (typeof item![k] === "string" ? (item![k] as string) : "");
    // Sent on to the file, or, when the file needs the catalogue's password
    // (which never goes to a browser), fetched here and passed on.
    const redirect = async (url: string): Promise<Response> => {
      if (!catalog.needsKey(url)) return new Response(null, { status: 307, headers: { location: url, "cache-control": "no-store" } });
      const r = await catalog.file(url);
      if (!r.ok || !r.body) throw new Error(`HTTP ${r.status} for its file`);
      const headers = new Headers({ "cache-control": "no-store" });
      for (const k of ["content-type", "content-length"]) { const v = r.headers.get(k); if (v) headers.set(k, v); }
      return new Response(r.body, { status: 200, headers });
    };
    const catalogId = text("catalogId");
    const key = catalogKey(catalogId);
    if (key?.[0] === "qobuz") {
      try { return await redirect(await catalog.qobuzUrl(key[1])); } catch (e) { console.log(`catalogue: qobuz:${key[1]} has no file (${(e as Error).message}); looking for it by name`); }
    }
    if (key?.[0] === "deezer") {
      try { return await redirect(await catalog.deezerUrl(key[1])); } catch (e) { console.log(`catalogue: deezer:${key[1]} has no file (${(e as Error).message}); looking for it by name`); }
    }
    let known = tidalId(catalogId);
    // A TIDAL id that names another song is no id for this one.
    if (known !== null && (await catalog.isSong(known, text("title"))) === false) known = null;
    if (known !== null) {
      try { return await redirect(await catalog.streamUrl(known)); } catch (e) { console.log(`catalogue: tidal:${known} has no stream (${(e as Error).message}); looking for it by name`); }
    }
    const found = await catalog.find(text("title"), text("artist"), Number(item.durationMs) || 0, text("isrc") || null);
    if (found !== null && found !== known) {
      try { return await redirect(await catalog.streamUrl(found)); } catch (e) { console.log(`catalogue: tidal:${found} has no stream either (${(e as Error).message})`); }
    }
    return refuse({ status: 404, error: "the catalogue has no copy of that song" });
  }
}

function roomKey(request: Request): string {
  return (request.headers.get("x-room-key") ?? "").trim();
}

function partyRefusal(e: string): Refusal {
  const status = e.includes("too big") || e.includes("no room") ? 413 : e.startsWith("only") ? 403 : 409;
  return { status, error: e };
}

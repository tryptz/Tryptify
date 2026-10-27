// SPDX-License-Identifier: GPL-3.0-or-later
//
// A room's rules, with no sockets in them: every move is a function of the
// room and a player, answering with what to send to whom. The hub does the
// sending, and the tests check the rules alone.
//
// A port of tryptify-party's `src/room.rs` and the room half of
// `src/protocol.rs`, kept to the same wire format: the app parses it with
// `RoomProtocol.kt`. No I/O and no clock: times come in as UNIX ms.

// ---------------------------------------------------------------- constants

/** The most players a room holds. */
export const MAX_PLAYERS = 16;
/** A chart's notes (the `.sm` text) at most, in UTF-8 bytes. */
export const MAX_NOTES_BYTES = 512 * 1024;
/** A song the host shares with the room, at most. */
export const MAX_SONG_BYTES = 128 * 1024 * 1024;
/** From the host's start to the first beat. */
export const LEAD_MS = 4_000;
/** After a song's length, how long the room waits for the last phone's result. */
export const GRACE_MS = 30_000;
/** How often the hub relays everyone's live scores while a song plays. */
export const FRAMES_EVERY_MS = 250;
/** A chat line at most, in characters (Unicode scalar values). */
export const CHAT_MAX_CHARS = 300;
/** Room codes: no 0/O or 1/I, as the code is read out loud and typed on a phone. */
export const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const CODE_LENGTH = 4;

// ---------------------------------------------------------------- wire types

export type RoomState = "lobby" | "playing" | "results";
export type RoomKind = "game" | "party";

/** The chart the host picked. `notes` is "" when there are none. */
export interface Chart {
  hash: string;
  title: string;
  artist: string;
  difficulty: string;
  duration: number; // f64, seconds
  bpm: number; // f64
  notes: string;
}

/** A chart as it goes on the wire: `notes` left out when empty (serde's skip_serializing_if). */
export interface ChartView {
  hash: string;
  title: string;
  artist: string;
  difficulty: string;
  duration: number;
  bpm: number;
  notes?: string;
}

export interface SharedSong {
  hash: string;
  bytes: number; // u64
  ext: string;
}

export interface Live {
  score: number; // i64
  combo: number; // u32
  acc: number; // f64
  hp: number; // f64
  pos: number; // u64
}

export interface Finished {
  score: number; // i64
  acc: number; // f64
  maxCombo: number; // u32
  grade: string;
  failed: boolean;
  judgements: Record<string, unknown>;
}

export interface PlayerView {
  id: string;
  name: string;
  user: string | null;
  ready: boolean;
  hasSong: boolean | null;
  live: Live;
  finished: Finished | null;
  spectating: boolean;
}

export interface RoomView {
  code: string;
  name: string;
  hostId: string;
  state: RoomState;
  chart: ChartView | null;
  players: PlayerView[];
  startAt: number | null;
  sharedSong: SharedSong | null;
}

export interface RoomSummary {
  code: string;
  name: string;
  host: string;
  players: number;
  state: RoomState;
  chart: string | null;
}

export interface Placing {
  rank: number;
  id: string;
  name: string;
  result: Finished;
}

/** What the server sends about rooms, keys in serde's order (`t` first). */
export type ServerMsg =
  | { t: "rooms"; rooms: RoomSummary[] }
  | { t: "room"; room: RoomView }
  | { t: "chart"; chart: ChartView }
  | { t: "start"; at: number; hash: string }
  | { t: "frames"; players: [string, Live][] }
  | { t: "results"; ranking: Placing[] }
  | { t: "chat"; id: string; name: string; text: string }
  | { t: "left"; reason: string }
  | { t: "error"; message: string };

/** Who a message goes to: everyone in the room, or one player by id. */
export type To = "all" | string;
export type Out = { to: To; msg: Record<string, unknown> & ServerMsg }[];
export type Result = { ok: true; out: Out } | { ok: false; error: string };

export function errorMsg(message: string): ServerMsg {
  return { t: "error", message };
}

export function leftMsg(reason: string): ServerMsg {
  return { t: "left", reason };
}

// ---------------------------------------------------------------- players

export interface Player {
  id: string;
  name: string;
  /** The Supabase account behind the player; null for a guest. */
  user: string | null;
  ready: boolean;
  hasSong: boolean | null;
  live: Live;
  finished: Finished | null;
  spectating: boolean;
}

/** Before a note is judged: no score, full health. */
export function defaultLive(): Live {
  return { score: 0, combo: 0, acc: 0, hp: 1, pos: 0 };
}

export function defaultFinished(): Finished {
  return { score: 0, acc: 0, maxCombo: 0, grade: "", failed: false, judgements: {} };
}

export function newPlayer(id: string, name: string, user: string | null = null): Player {
  return {
    id,
    name,
    user,
    ready: false,
    hasSong: null,
    live: defaultLive(),
    finished: null,
    spectating: false,
  };
}

function copyLive(l: Live): Live {
  return { score: l.score, combo: l.combo, acc: l.acc, hp: l.hp, pos: l.pos };
}

function cloneJson(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(cloneJson);
  if (v !== null && typeof v === "object") {
    const o: Record<string, unknown> = {};
    for (const k of Object.keys(v)) o[k] = cloneJson((v as Record<string, unknown>)[k]);
    return o;
  }
  return v;
}

function copyFinished(f: Finished): Finished {
  return {
    score: f.score,
    acc: f.acc,
    maxCombo: f.maxCombo,
    grade: f.grade,
    failed: f.failed,
    judgements: cloneJson(f.judgements) as Record<string, unknown>,
  };
}

function copyPlayer(p: Player): Player {
  return { ...p, live: copyLive(p.live), finished: p.finished && copyFinished(p.finished) };
}

function copyChart(c: Chart): Chart {
  return {
    hash: c.hash,
    title: c.title,
    artist: c.artist,
    difficulty: c.difficulty,
    duration: c.duration,
    bpm: c.bpm,
    notes: c.notes,
  };
}

/** A chart for the wire, `notes` dropped when empty. */
export function chartView(c: Chart): ChartView {
  const v: ChartView = {
    hash: c.hash,
    title: c.title,
    artist: c.artist,
    difficulty: c.difficulty,
    duration: c.duration,
    bpm: c.bpm,
  };
  if (c.notes !== "") v.notes = c.notes;
  return v;
}

/** The chart without its notes, for room state. */
export function chartMeta(c: Chart): ChartView {
  return chartView({ ...c, notes: "" });
}

function playerView(p: Player): PlayerView {
  return {
    id: p.id,
    name: p.name,
    user: p.user,
    ready: p.ready,
    hasSong: p.hasSong,
    live: copyLive(p.live),
    finished: p.finished && copyFinished(p.finished),
    spectating: p.spectating,
  };
}

// ---------------------------------------------------------------- string rules

// Rust's `str::trim`: Unicode White_Space, which is not quite JS's trim
// (JS trims U+FEFF and leaves U+0085).
const WS = "\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
const TRIM = new RegExp(`^[${WS}]+|[${WS}]+$`, "g");

export function rustTrim(s: string): string {
  return s.replace(TRIM, "");
}

/** UTF-8 length, as Rust's `String::len`. */
export function utf8Bytes(s: string): number {
  let n = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c < 0x80) n += 1;
    else if (c < 0x800) n += 2;
    else if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
      const d = s.charCodeAt(i + 1);
      if (d >= 0xdc00 && d <= 0xdfff) {
        n += 4;
        i++;
      } else n += 3;
    } else n += 3;
  }
  return n;
}

/** A join code as typed: trimmed and upper-cased. */
export function normaliseCode(code: string): string {
  return rustTrim(code).toUpperCase();
}

/** A fresh room code; [randomBelow] gives an integer in [0, n). */
export function newCode(isTaken: (code: string) => boolean, randomBelow: (n: number) => number): string {
  for (;;) {
    let code = "";
    for (let i = 0; i < CODE_LENGTH; i++) code += CODE_ALPHABET[randomBelow(CODE_ALPHABET.length)];
    if (!isTaken(code)) return code;
  }
}

function codePointCompare(a: string, b: string): number {
  // Rust compares strings by UTF-8 bytes, which is code point order.
  const ia = a[Symbol.iterator]();
  const ib = b[Symbol.iterator]();
  for (;;) {
    const x = ia.next();
    const y = ib.next();
    if (x.done) return y.done ? 0 : -1;
    if (y.done) return 1;
    const cx = x.value.codePointAt(0)!;
    const cy = y.value.codePointAt(0)!;
    if (cx !== cy) return cx < cy ? -1 : 1;
  }
}

/** The open rooms, as `rooms` lists them: by name. */
export function listRooms(rooms: Iterable<Room>): { t: "rooms"; rooms: RoomSummary[] } {
  const list = Array.from(rooms, (r) => r.summary());
  list.sort((a, b) => codePointCompare(a.name, b.name));
  return { t: "rooms", rooms: list };
}

/** A game room's chat line, as the hub sends it: at most 300 characters, nothing when blank. */
export function chatOut(id: string, name: string, text: string): Out {
  const cut = Array.from(text).slice(0, CHAT_MAX_CHARS).join("");
  if (rustTrim(cut) === "") return [];
  return [{ to: "all", msg: { t: "chat", id, name, text: cut } }];
}

/** Messages for a listening party; a game room refuses them. */
export const PARTY_TAGS: ReadonlySet<string> = new Set([
  "queueAdd",
  "queueRemove",
  "queueMove",
  "pause",
  "resume",
  "seek",
  "skip",
  "setRate",
  "passHost",
  "endParty",
  "skipVote",
]);

// ---------------------------------------------------------------- the room

export class Room {
  code: string;
  name: string;
  host: string;
  state: RoomState = "lobby";
  chart: Chart | null = null;
  players: Player[];
  startAt: number | null = null;
  /** The picked chart's song as the host shared it, and where it is kept. */
  shared: { song: SharedSong; path: string } | null = null;
  /** Files no longer the room's song, for the hub to delete. */
  dropped: string[] = [];

  constructor(code: string, name: string, host: Player) {
    const trimmed = rustTrim(name);
    this.code = code;
    this.name = trimmed === "" ? `${host.name}'s room` : trimmed;
    this.host = host.id;
    this.players = [copyPlayer(host)];
  }

  /** A new room, and what the hub sends on making it: the room, to its host. */
  static create(code: string, name: string, host: Player): { room: Room; out: Out } {
    const room = new Room(code, name, host);
    return { room, out: [room.roomMsg()] };
  }

  view(): RoomView {
    return {
      code: this.code,
      name: this.name,
      hostId: this.host,
      state: this.state,
      chart: this.chart && chartMeta(this.chart),
      players: this.players.map(playerView),
      startAt: this.startAt,
      sharedSong: this.shared && { ...this.shared.song },
    };
  }

  summary(): RoomSummary {
    return {
      code: this.code,
      name: this.name,
      host: this.player(this.host)?.name ?? "",
      players: this.players.length,
      state: this.state,
      chart: this.chart ? this.chart.title : null,
    };
  }

  player(id: string): Player | undefined {
    return this.players.find((p) => p.id === id);
  }

  playerIds(): string[] {
    return this.players.map((p) => p.id);
  }

  isEmpty(): boolean {
    return this.players.length === 0;
  }

  /** The files let go of since last asked, for the hub to delete. */
  takeDropped(): string[] {
    const d = this.dropped;
    this.dropped = [];
    return d;
  }

  /** Every file the room holds or let go of, for a room that is gone. */
  takeFiles(): string[] {
    const files = this.takeDropped();
    if (this.shared) {
      files.push(this.shared.path);
      this.shared = null;
    }
    return files;
  }

  /**
   * The host shares the picked chart's song, already stored at [path]. A song
   * for a chart the room has moved on from is refused (its file is the
   * caller's to delete). A song shared before it is dropped.
   */
  share(id: string, song: SharedSong, path: string): Result {
    const refused = this.hostOnly(id);
    if (refused) return refused;
    if (this.chart?.hash !== song.hash) {
      return { ok: false, error: "that is not the picked chart's song" };
    }
    const old = this.shared;
    this.shared = { song: { hash: song.hash, bytes: song.bytes, ext: song.ext }, path };
    if (old) this.dropped.push(old.path);
    return { ok: true, out: [this.roomMsg()] };
  }

  /** The shared song, for a player in the room asking for chart [hash]'s; null when not what is shared. */
  sharedFor(id: string, hash: string): { song: SharedSong; path: string } | null {
    if (!this.player(id)) return null;
    if (!this.shared || this.shared.song.hash !== hash) return null;
    return { song: { ...this.shared.song }, path: this.shared.path };
  }

  private roomMsg(): Out[number] {
    return { to: "all", msg: { t: "room", room: this.view() } };
  }

  /** A player in: told the chart (and the start, mid-song), everyone told the room. */
  join(player: Player): Result {
    if (this.players.some((p) => p.id === player.id)) {
      return { ok: false, error: "already in the room" };
    }
    if (this.players.length >= MAX_PLAYERS) {
      return { ok: false, error: "the room is full" };
    }
    const p = copyPlayer(player);
    p.spectating = this.state === "playing";
    this.players.push(p);
    const out: Out = [];
    if (this.chart) {
      out.push({ to: p.id, msg: { t: "chart", chart: chartView(this.chart) } });
    }
    if (this.startAt !== null && this.state === "playing") {
      out.push({ to: p.id, msg: { t: "start", at: this.startAt, hash: this.chart?.hash ?? "" } });
    }
    out.push(this.roomMsg());
    return { ok: true, out };
  }

  /**
   * A player out, by their own leave, a kick or a dropped connection. The
   * host's seat passes to whoever joined next. A song with everyone else
   * finished shows its results.
   */
  leave(id: string, now: number): Out {
    const at = this.players.findIndex((p) => p.id === id);
    if (at < 0) return [];
    this.players.splice(at, 1);
    const out: Out = [];
    if (this.isEmpty()) return out;
    if (this.host === id) this.host = this.players[0].id;
    if (this.state === "playing") out.push(...this.resultsIfDone(now));
    out.push(this.roomMsg());
    return out;
  }

  /** The host picks a chart, in the lobby or at the results, never mid-song. */
  pick(id: string, chart: Chart): Result {
    const refused = this.hostOnly(id);
    if (refused) return refused;
    if (this.state === "playing") return { ok: false, error: "a song is playing" };
    if (utf8Bytes(chart.notes) > MAX_NOTES_BYTES) return { ok: false, error: "the chart is too big" };
    if (chart.hash === "" || chart.title === "") {
      return { ok: false, error: "the chart has no hash or title" };
    }
    this.state = "lobby";
    this.startAt = null;
    // Another chart's song is no use to anyone now; the same chart picked
    // again keeps the song already shared for it.
    if (this.shared && this.shared.song.hash !== chart.hash) {
      this.dropped.push(this.shared.path);
      this.shared = null;
    }
    this.chart = copyChart(chart);
    for (const p of this.players) {
      p.ready = false;
      p.hasSong = null;
      p.finished = null;
      p.live = defaultLive();
      p.spectating = false;
    }
    return { ok: true, out: [{ to: "all", msg: { t: "chart", chart: chartView(chart) } }, this.roomMsg()] };
  }

  have(id: string, audio: boolean): Out {
    const p = this.player(id);
    if (!p) return [];
    p.hasSong = audio;
    if (!audio) p.ready = false;
    return [this.roomMsg()];
  }

  /** Ready needs the song: a phone without it cannot play along. */
  ready(id: string, ready: boolean): Result {
    if (!this.chart) return { ok: false, error: "no chart is picked" };
    const p = this.player(id);
    if (!p) return { ok: true, out: [] };
    if (ready && p.hasSong !== true) return { ok: false, error: "you do not have this song" };
    p.ready = ready;
    return { ok: true, out: [this.roomMsg()] };
  }

  /**
   * The host starts: the host has the song, everyone with the song is ready,
   * and whoever has not got it watches. Everyone plays from `now + LEAD_MS`.
   */
  start(id: string, now: number): Result {
    const refused = this.hostOnly(id);
    if (refused) return refused;
    if (this.state === "playing") return { ok: false, error: "a song is playing" };
    const chart = this.chart;
    if (!chart) return { ok: false, error: "no chart is picked" };
    if (this.player(id)?.hasSong !== true) return { ok: false, error: "you do not have this song" };
    const notReady = this.players.find((p) => p.hasSong === true && !p.ready);
    if (notReady) return { ok: false, error: `${notReady.name} is not ready` };
    const at = now + LEAD_MS;
    this.state = "playing";
    this.startAt = at;
    for (const p of this.players) {
      p.finished = null;
      p.live = defaultLive();
      p.spectating = p.hasSong !== true;
    }
    return { ok: true, out: [{ to: "all", msg: { t: "start", at, hash: chart.hash } }, this.roomMsg()] };
  }

  kick(id: string, target: string, now: number): Result {
    const refused = this.hostOnly(id);
    if (refused) return refused;
    if (id === target) return { ok: false, error: "you cannot kick yourself" };
    if (!this.player(target)) return { ok: false, error: "no such player" };
    const out: Out = [{ to: target, msg: { t: "left", reason: "kicked by the host" } }];
    out.push(...this.leave(target, now));
    return { ok: true, out };
  }

  /** A score as it stands; nothing is sent, the hub relays frames on its own beat. */
  frame(id: string, live: Live): void {
    if (this.state !== "playing") return;
    const p = this.player(id);
    if (p && !p.spectating && p.finished === null) p.live = copyLive(live);
  }

  /** The live scores of everyone in the song, for the hub's relay. */
  frames(): ServerMsg {
    return {
      t: "frames",
      players: this.players.filter((p) => !p.spectating).map((p) => [p.id, copyLive(p.live)] as [string, Live]),
    };
  }

  finish(id: string, result: Finished, now: number): Out {
    if (this.state !== "playing") return [];
    const p = this.player(id);
    if (p) {
      if (p.spectating) return [];
      p.live.score = result.score;
      p.live.acc = result.acc;
      p.finished = copyFinished(result);
    }
    const out = this.resultsIfDone(now);
    if (out.length === 0) out.push(this.roomMsg());
    return out;
  }

  /**
   * The song has gone on past its end and a grace: whoever has not said is
   * taken at their last score. The hub asks on its beat.
   */
  timeout(now: number): Out {
    if (this.state !== "playing") return [];
    const at = this.startAt;
    if (at === null) return [];
    // `(duration * 1000.0) as u64`: truncated, negative and NaN to 0.
    const ms = this.chart ? this.chart.duration * 1000 : 0;
    const length = Number.isNaN(ms) || ms <= 0 ? 0 : Math.trunc(ms);
    if (now < at + length + GRACE_MS) return [];
    for (const p of this.players) {
      if (!p.spectating && p.finished === null) {
        p.finished = { ...defaultFinished(), score: p.live.score, acc: p.live.acc, maxCombo: p.live.combo };
      }
    }
    return this.resultsIfDone(now);
  }

  /**
   * One message from a player in this room, answered as the hub answers it.
   * [name] is the sender's name, for chat. `kicked` names a kicked player
   * (the hub forgets their room after delivering), `started` says a song
   * began (the hub starts relaying frames).
   */
  handle(
    id: string,
    name: string,
    msg: RoomClientMsg | { t: string },
    now: number,
  ): { ok: true; out: Out; kicked: string | null; started: boolean } | { ok: false; error: string } {
    let r: Result;
    let kicked: string | null = null;
    let started = false;
    const m = msg as RoomClientMsg;
    switch (m.t) {
      case "pick":
        r = this.pick(id, m.chart);
        break;
      case "have":
        r = { ok: true, out: this.have(id, m.audio) };
        break;
      case "ready":
        r = this.ready(id, m.ready);
        break;
      case "start":
        r = this.start(id, now);
        started = r.ok;
        break;
      case "kick":
        r = this.kick(id, m.id, now);
        if (r.ok) kicked = m.id;
        break;
      case "frame":
        this.frame(id, m.live);
        r = { ok: true, out: [] };
        break;
      case "finish":
        r = { ok: true, out: this.finish(id, m.result, now) };
        break;
      case "chat":
        r = { ok: true, out: chatOut(id, name, m.text) };
        break;
      default:
        r = PARTY_TAGS.has(msg.t)
          ? { ok: false, error: "that is for a party, not a game room" }
          : { ok: true, out: [] };
    }
    return r.ok ? { ok: true, out: r.out, kicked, started } : r;
  }

  private resultsIfDone(_now: number): Out {
    if (this.state !== "playing") return [];
    const inSong = this.players.filter((p) => !p.spectating);
    if (inSong.some((p) => p.finished === null)) return [];
    const placings: Placing[] = inSong.map((p) => ({
      rank: 0,
      id: p.id,
      name: p.name,
      result: p.finished ? copyFinished(p.finished) : defaultFinished(),
    }));
    // Fails last, then score, then accuracy, best first; a stable sort, as Rust's.
    placings.sort((a, b) => {
      if (a.result.failed !== b.result.failed) return a.result.failed ? 1 : -1;
      if (a.result.score !== b.result.score) return b.result.score - a.result.score;
      if (a.result.acc !== b.result.acc && !Number.isNaN(a.result.acc) && !Number.isNaN(b.result.acc)) {
        return b.result.acc - a.result.acc;
      }
      return 0;
    });
    placings.forEach((p, i) => (p.rank = i + 1));
    this.state = "results";
    this.startAt = null;
    for (const p of this.players) {
      p.ready = false;
      p.spectating = false;
    }
    return [{ to: "all", msg: { t: "results", ranking: placings } }, this.roomMsg()];
  }

  private hostOnly(id: string): { ok: false; error: string } | null {
    return this.host === id ? null : { ok: false, error: "only the host can do that" };
  }
}

// ---------------------------------------------------------------- incoming messages, as serde reads them

/** The room messages a phone sends, parsed. */
export type RoomClientMsg =
  | { t: "rooms" }
  | { t: "create"; name: string; kind: RoomKind }
  | { t: "join"; code: string }
  | { t: "leave" }
  | { t: "pick"; chart: Chart }
  | { t: "have"; audio: boolean }
  | { t: "ready"; ready: boolean }
  | { t: "start" }
  | { t: "kick"; id: string }
  | { t: "frame"; live: Live }
  | { t: "finish"; result: Finished }
  | { t: "chat"; text: string };

/** Every `t` the server knows, in serde's order (its "unknown variant" message lists them). */
export const CLIENT_TAGS = [
  "hello",
  "ping",
  "rooms",
  "create",
  "join",
  "leave",
  "pick",
  "have",
  "ready",
  "start",
  "kick",
  "frame",
  "finish",
  "chat",
  "queueAdd",
  "queueRemove",
  "queueMove",
  "pause",
  "resume",
  "seek",
  "skip",
  "setRate",
  "passHost",
  "endParty",
  "skipVote",
] as const;

class SerdeError extends Error {}

const TWO_63 = 2 ** 63;
const TWO_64 = 2 ** 64;

/** serde_json's f64 text: shortest digits, `.0` on whole numbers, exponents past 1e16 and under 1e-5. */
export function formatF64(x: number): string {
  if (!Number.isFinite(x)) return "null";
  if (x === 0) return Object.is(x, -0) ? "-0.0" : "0.0";
  const sign = x < 0 ? "-" : "";
  const [mant, exp] = Math.abs(x).toExponential().split("e");
  const digits = mant.replace(".", "");
  const len = digits.length;
  const kk = parseInt(exp, 10) + 1; // where the decimal point falls in the digits
  let body: string;
  if (len <= kk && kk <= 16) body = digits + "0".repeat(kk - len) + ".0";
  else if (0 < kk && kk <= 16) body = digits.slice(0, kk) + "." + digits.slice(kk);
  else if (-5 < kk && kk <= 0) body = "0." + "0".repeat(-kk) + digits;
  else {
    const e = kk - 1;
    const es = (e < 0 ? "-" : "+") + Math.abs(e);
    body = (len === 1 ? digits : digits[0] + "." + digits.slice(1)) + "e" + es;
  }
  return sign + body;
}

function isIntegerLiteral(v: number): boolean {
  // serde_json reads a number as an integer when written without a fraction
  // or exponent and in i64/u64 range; JSON.parse cannot tell `5.0` from `5`.
  return Number.isInteger(v) && !Object.is(v, -0) && v >= -TWO_63 && v < TWO_64;
}

/** Rust's `{:?}` of a str, as serde quotes one in an error. */
function debugStr(s: string): string {
  let out = '"';
  for (const ch of s) {
    const c = ch.codePointAt(0)!;
    if (ch === '"') out += '\\"';
    else if (ch === "\\") out += "\\\\";
    else if (ch === "\n") out += "\\n";
    else if (ch === "\r") out += "\\r";
    else if (ch === "\t") out += "\\t";
    else if (c === 0) out += "\\0";
    else if (
      c < 0x20 ||
      (c >= 0x7f && c <= 0x9f) ||
      c === 0xad ||
      (c >= 0x200b && c <= 0x200f) ||
      (c >= 0x2028 && c <= 0x202e) ||
      (c >= 0x2060 && c <= 0x2064) ||
      c === 0xfeff ||
      (c >= 0xd800 && c <= 0xdfff)
    ) {
      out += `\\u{${c.toString(16)}}`;
    } else out += ch;
  }
  return out + '"';
}

function unexpected(v: unknown): string {
  if (v === null) return "null";
  if (typeof v === "boolean") return `boolean \`${v}\``;
  if (typeof v === "number") {
    return isIntegerLiteral(v) ? `integer \`${BigInt(v)}\`` : `floating point \`${formatF64(v)}\``;
  }
  if (typeof v === "string") return `string ${debugStr(v)}`;
  if (Array.isArray(v)) return "sequence";
  return "map";
}

function invalidType(v: unknown, expected: string): never {
  throw new SerdeError(`invalid type: ${unexpected(v)}, expected ${expected}`);
}

function isMap(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

type Kind = "string" | "bool" | "f64" | "i64" | "u32" | "u64" | "map" | "roomKind" | Schema;

interface Field {
  key: string;
  kind: Kind;
  def?: () => unknown;
}

interface Schema {
  expecting: string;
  fields: Field[];
}

function plural(n: number): string {
  return n === 1 ? "element" : "elements";
}

function readValue(kind: Kind, v: unknown): unknown {
  if (typeof kind === "object") return readStruct(kind, v);
  switch (kind) {
    case "string":
      return typeof v === "string" ? v : invalidType(v, "a string");
    case "bool":
      return typeof v === "boolean" ? v : invalidType(v, "a boolean");
    case "f64":
      if (typeof v !== "number") return invalidType(v, "f64");
      // serde_json refuses 1e400 where JSON.parse makes it Infinity.
      if (!Number.isFinite(v)) throw new SerdeError("number out of range");
      return v;
    case "i64":
    case "u32":
    case "u64": {
      if (typeof v !== "number" || !isIntegerLiteral(v)) return invalidType(v, kind);
      const max = kind === "u32" ? 0xffffffff : kind === "i64" ? TWO_63 - 1 : TWO_64 - 1;
      const min = kind === "i64" ? -TWO_63 : 0;
      if (v < min || v > max || (kind === "i64" && v >= TWO_63)) {
        throw new SerdeError(`invalid value: integer \`${BigInt(v)}\`, expected ${kind}`);
      }
      return v;
    }
    case "map":
      return isMap(v) ? cloneJson(v) : invalidType(v, "a map");
    case "roomKind":
      return readRoomKind(v);
  }
}

function readRoomKind(v: unknown): RoomKind {
  const variant = (name: string): RoomKind => {
    if (name === "game" || name === "party") return name;
    throw new SerdeError(`unknown variant \`${name}\`, expected \`game\` or \`party\``);
  };
  if (typeof v === "string") return variant(v);
  if (isMap(v)) {
    const keys = Object.keys(v);
    if (keys.length !== 1) throw new SerdeError("invalid value: map, expected map with a single key");
    const kind = variant(keys[0]);
    if (v[keys[0]] !== null) invalidType(v[keys[0]], "unit");
    return kind;
  }
  return invalidType(v, "string or map");
}

/** A struct as serde's derive reads one: from a map, or from a sequence in field order. */
function readStruct(schema: Schema, v: unknown, seqTail?: unknown[]): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const n = schema.fields.length;
  const seq = seqTail ?? (Array.isArray(v) ? v : undefined);
  if (seq) {
    schema.fields.forEach((f, i) => {
      if (i < seq.length) out[f.key] = readValue(f.kind, seq[i]);
      else if (f.def) out[f.key] = f.def();
      else throw new SerdeError(`invalid length ${i}, expected ${schema.expecting} with ${n} ${plural(n)}`);
    });
    if (seq.length > n) {
      throw new SerdeError(`invalid length ${seq.length}, expected ${n} ${plural(n)} in sequence`);
    }
    return out;
  }
  if (!isMap(v)) return invalidType(v, schema.expecting);
  const seen = new Set<string>();
  for (const k of Object.keys(v)) {
    const f = schema.fields.find((f) => f.key === k);
    if (!f) continue;
    out[k] = readValue(f.kind, v[k]);
    seen.add(k);
  }
  for (const f of schema.fields) {
    if (seen.has(f.key)) continue;
    if (!f.def) throw new SerdeError(`missing field \`${f.key}\``);
    out[f.key] = f.def();
  }
  return out;
}

const zero = () => 0;
const empty = () => "";

const CHART: Schema = {
  expecting: "struct Chart",
  fields: [
    { key: "hash", kind: "string" },
    { key: "title", kind: "string" },
    { key: "artist", kind: "string" },
    { key: "difficulty", kind: "string", def: empty },
    { key: "duration", kind: "f64", def: zero },
    { key: "bpm", kind: "f64", def: zero },
    { key: "notes", kind: "string", def: empty },
  ],
};

const LIVE: Schema = {
  expecting: "struct Live",
  fields: [
    { key: "score", kind: "i64", def: zero },
    { key: "combo", kind: "u32", def: zero },
    { key: "acc", kind: "f64", def: zero },
    { key: "hp", kind: "f64", def: () => 1 },
    { key: "pos", kind: "u64", def: zero },
  ],
};

const FINISHED: Schema = {
  expecting: "struct Finished",
  fields: [
    { key: "score", kind: "i64", def: zero },
    { key: "acc", kind: "f64", def: zero },
    { key: "maxCombo", kind: "u32", def: zero },
    { key: "grade", kind: "string", def: empty },
    { key: "failed", kind: "bool", def: () => false },
    { key: "judgements", kind: "map", def: () => ({}) },
  ],
};

const variant = (name: string, fields: Field[]): Schema => ({
  expecting: `struct variant ClientMsg::${name}`,
  fields,
});

const ROOM_VARIANTS: Record<string, Schema> = {
  rooms: variant("Rooms", []),
  create: variant("Create", [
    { key: "name", kind: "string", def: empty },
    { key: "kind", kind: "roomKind", def: () => "game" },
  ]),
  join: variant("Join", [{ key: "code", kind: "string" }]),
  leave: variant("Leave", []),
  pick: variant("Pick", [{ key: "chart", kind: CHART }]),
  have: variant("Have", [{ key: "audio", kind: "bool" }]),
  ready: variant("Ready", [{ key: "ready", kind: "bool" }]),
  start: variant("Start", []),
  kick: variant("Kick", [{ key: "id", kind: "string" }]),
  frame: variant("Frame", [{ key: "live", kind: LIVE }]),
  finish: variant("Finish", [{ key: "result", kind: FINISHED }]),
  chat: variant("Chat", [{ key: "text", kind: "string" }]),
};

export type Parsed<T> = { ok: true; value: T } | { ok: false; error: string };

function attempt<T>(read: () => T): Parsed<T> {
  try {
    return { ok: true, value: read() };
  } catch (e) {
    if (e instanceof SerdeError) return { ok: false, error: `bad message: ${e.message}` };
    throw e;
  }
}

/** A `pick`'s chart, as serde takes one (from JSON already parsed). */
export function parseChart(v: unknown): Parsed<Chart> {
  return attempt(() => readStruct(CHART, v) as unknown as Chart);
}

/** A `frame`'s live score. */
export function parseLive(v: unknown): Parsed<Live> {
  return attempt(() => readStruct(LIVE, v) as unknown as Live);
}

/** A `finish`'s result. */
export function parseFinished(v: unknown): Parsed<Finished> {
  return attempt(() => readStruct(FINISHED, v) as unknown as Finished);
}

/**
 * A phone's message (JSON already parsed), read the way serde reads
 * `ClientMsg`: a room message comes back parsed; a bad one as the hub's
 * `bad message: …`; any other known `t` (hello, ping, the party's) as
 * `other`, for its own reader.
 */
export function parseRoomClientMsg(
  raw: unknown,
): { ok: true; msg: RoomClientMsg } | { ok: false; error: string } | { ok: "other"; t: string } {
  try {
    let tag: unknown;
    let rest: Record<string, unknown> | unknown[];
    if (Array.isArray(raw)) {
      if (raw.length === 0) throw new SerdeError("missing field `t`");
      tag = raw[0];
      rest = raw.slice(1);
    } else if (isMap(raw)) {
      if (!Object.prototype.hasOwnProperty.call(raw, "t")) throw new SerdeError("missing field `t`");
      tag = raw.t;
      rest = raw;
    } else {
      return invalidType(raw, "internally tagged enum ClientMsg");
    }
    if (typeof tag !== "string") invalidType(tag, "variant identifier");
    const t = tag as string;
    if (!(CLIENT_TAGS as readonly string[]).includes(t)) {
      throw new SerdeError(
        `unknown variant \`${t}\`, expected one of ${CLIENT_TAGS.map((x) => `\`${x}\``).join(", ")}`,
      );
    }
    const schema = ROOM_VARIANTS[t];
    if (!schema) return { ok: "other", t };
    const fields = Array.isArray(rest) ? readStruct(schema, null, rest) : readStruct(schema, rest);
    return { ok: true, msg: { t, ...fields } as RoomClientMsg };
  } catch (e) {
    if (e instanceof SerdeError) return { ok: false, error: `bad message: ${e.message}` };
    throw e;
  }
}

// ---------------------------------------------------------------- outgoing, as serde writes it

// The f64 fields of the room messages; every other number is an integer.
const F64_KEYS = new Set(["duration", "bpm", "acc", "hp"]);

function encodeValue(v: unknown, key: string | null, inValue: boolean): string {
  if (v === null || v === undefined) return "null";
  if (typeof v === "string") return JSON.stringify(v);
  if (typeof v === "boolean") return v ? "true" : "false";
  if (typeof v === "number") {
    if (!inValue && key !== null && F64_KEYS.has(key)) return formatF64(v);
    // BigInt only past 2^53, where a number's own text is rounded (2^63 comes out ...776000).
    return isIntegerLiteral(v) ? (Number.isSafeInteger(v) ? String(v) : BigInt(v).toString()) : formatF64(v);
  }
  if (Array.isArray(v)) return "[" + v.map((x) => encodeValue(x, null, inValue)).join(",") + "]";
  const o = v as Record<string, unknown>;
  let keys = Object.keys(o).filter((k) => o[k] !== undefined);
  // serde_json::Map is a BTreeMap: its keys go out sorted.
  if (inValue) keys = keys.sort(codePointCompare);
  return (
    "{" +
    keys
      .map((k) => JSON.stringify(k) + ":" + encodeValue(o[k], k, inValue || k === "judgements"))
      .join(",") +
    "}"
  );
}

/**
 * A server message as text, exactly as serde_json writes it: keys in the
 * order the objects hold them, f64 fields with their `.0`, judgements sorted.
 * (JSON.stringify gives the same object, only `1` where serde writes `1.0`.)
 */
export function encode(msg: object): string {
  return encodeValue(msg, null, false);
}

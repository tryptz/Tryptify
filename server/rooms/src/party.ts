// SPDX-License-Identifier: GPL-3.0-or-later
//
// A listening party's rules, with no sockets in them: everyone queues songs
// and hears the same moment. A line-for-line port of tryptify-party's
// `cpp/party.cpp` (the rules the Rust hub ran), so the app
// (`PartyProtocol.kt`) and the website (`party.html`) read the very same
// messages. Every move is a function of the party and a member, answering
// with what to send to whom; the Worker holds the sockets and the files.
//
// Everyone adds songs; only the host pauses, seeks, reorders and skips;
// anyone votes to skip, and half the room (rounded up) skips. The party keeps
// the one clock of the song (its playback) and moves on when a song's length
// has run out; the phones follow it.
//
// Pure: no I/O and no clock. Times are UNIX ms, passed in as `at`. Every
// message is a plain object for JSON.stringify, with the fields party.cpp
// writes, in its order: nothing is ever left out, an absent value is null.

/** The most members a party holds, as a game room's players. */
export const MAX_MEMBERS = 16;
/** The most lines of chat a party keeps, sent to whoever joins. */
export const CHAT_HISTORY = 50;
/** A chat line at most, in characters (code points). */
export const CHAT_MAX_CHARS = 300;
/** At most this many lines from one member in CHAT_WINDOW_MS. */
export const CHAT_BURST = 5;
export const CHAT_WINDOW_MS = 5_000;
/** The most songs a queue holds. */
export const MAX_QUEUE = 200;
/** All a party's shared songs together, at most. */
export const PARTY_QUOTA_BYTES = 1024 * 1024 * 1024;
/** Past a song's length, how long before the party moves on: a phone's
 *  length is rounded, and the last note should ring. */
export const END_GRACE_MS = 750;
/** A host away this long hands the seat to an app member who is here. Two
 *  minutes, not less: a host with the app in the background, or losing signal
 *  a moment, keeps the seat. */
export const HOST_AWAY_MS = 120_000;
/** The host's speed, at least and at most. */
export const MIN_RATE = 0.25;
export const MAX_RATE = 4.0;
/** How long a member who lost their connection keeps their seat (the hub's
 *  rule, `src/party.rs`): five minutes, since a phone stepping away to a chat
 *  app to send the invite was gone longer than one and came back to no seat. */
export const RESUME_MS = 300_000;
/** The hub's answer to a code no party holds (`src/lib.rs`, not the rules);
 *  the app and the website match it. */
export const NO_PARTY = "no party with that code";

/** Who a message goes to (`to`: "" is everyone) and the message itself. */
export type Msg = { to: string; msg: Record<string, unknown> };
export type Out = Msg[];
/** A move's answer: what to send, or why it was refused. */
export type Result = { ok: true; out: Out } | { ok: false; error: string };

/** Someone coming in, as the hub knows them. */
export interface MemberIn {
  id: string;
  name: string;
  /** The Supabase account, or null for none. */
  user?: string | null;
  guest?: boolean;
  web?: boolean;
  /** Lets this member's browser fetch shared songs; dies with the seat. */
  token: string;
}

export interface Member {
  id: string;
  name: string;
  user: string | null;
  guest: boolean;
  web: boolean;
  away: boolean;
  /** Since when away, server time; for handing a host's seat on. */
  awaySince: number;
  token: string;
  /** When their last lines were said, for the rate limit. */
  said: number[];
}

/** A song shared with the party: uploading until ready. */
export interface Song {
  ready: boolean;
  bytes: number;
  received: number;
  ext: string;
}

/** A song as a phone puts it in the queue (`QueueItemIn`: all but the title may be left out). */
export interface ItemIn {
  title: string;
  artist?: string;
  album?: string;
  durationMs?: number;
  isrc?: string | null;
  catalogId?: string | null;
  audioHash?: string | null;
  cover?: string | null;
  share?: boolean;
}

/** A song in the queue, or the one playing, and once shared its file. */
export interface Item {
  id: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  addedBy: string;
  addedByName: string;
  isrc: string | null;
  catalogId: string | null;
  audioHash: string | null;
  cover: string | null;
  /** Null: not shared; every phone plays its own copy or sits it out. */
  shared: Song | null;
  /** Never on the wire. */
  file: string | null;
}

/** Where the party is in its song: at server time atServerMs the song itemId
 *  was at positionMs, and it runs on from there at `rate` unless paused.
 *  keepPitch false is vinyl style, the pitch riding the speed. */
export interface Playback {
  itemId: string | null;
  positionMs: number;
  atServerMs: number;
  paused: boolean;
  rate: number;
  keepPitch: boolean;
}

export function playbackPosition(p: Playback, now: number): number {
  if (p.paused || now <= p.atServerMs) return p.positionMs;
  // (uint64)(double * rate + 0.5): the value is never negative, so floor is the cast.
  return p.positionMs + Math.floor((now - p.atServerMs) * p.rate + 0.5);
}

export type LineKind = "say" | "join" | "leave" | "add" | "skip" | "host";

export interface Line {
  kind: LineKind;
  id: string | null;
  name: string;
  text: string;
  at: number;
}

/** The shared file of a song, for whoever may fetch it. */
export interface SongFile {
  song: Song;
  path: string;
}

// ---- text ------------------------------------------------------------------

function isSpace(ch: string): boolean {
  return ch === " " || ch === "\t" || ch === "\n" || ch === "\r" || ch === "\f" || ch === "\v";
}

/** Text trimmed of (ASCII) white space at both ends and cut to `chars`
 *  characters: code points, not UTF-16 units, so nothing is cut mid-letter. */
export function trimChars(text: string, chars: number): string {
  let start = 0;
  let end = text.length;
  while (start < end && isSpace(text[start])) ++start;
  while (end > start && isSpace(text[end - 1])) --end;
  const points = Array.from(text.slice(start, end));
  return points.length <= chars ? points.join("") : points.slice(0, chars).join("");
}

/** Text as the Rust hub handed it to C++: a NUL dropped (`c()` in
 *  `src/party.rs`), and a lone surrogate, which no UTF-8 string can hold,
 *  made U+FFFD. */
function c(text: string): string {
  const s = text.includes("\0") ? text.replace(/\0/g, "") : text;
  const wf = (s as { toWellFormed?: () => string }).toWellFormed;
  return wf ? wf.call(s) : s;
}

function cOpt(text: string | null | undefined): string | null {
  return text == null ? null : c(text);
}

/** A short field as the queue keeps it: trimmed, cut, and none when blank. */
function shortField(s: string | null, n: number): string | null {
  if (s === null) return null;
  const t = trimChars(s, n);
  return t === "" ? null : t;
}

/** std::string's order (UTF-8 bytes), which is code point order. */
function byCodePoint(a: string, b: string): number {
  const x = Array.from(a);
  const y = Array.from(b);
  for (let i = 0; i < Math.min(x.length, y.length); i++) {
    const d = x[i].codePointAt(0)! - y[i].codePointAt(0)!;
    if (d !== 0) return d;
  }
  return x.length - y.length;
}

/** A speed as party.cpp writes it, printf("%.4g"): four significant digits,
 *  rounded from the double's exact value, a tie to even (glibc), so 1.25
 *  stays 1.25 and 1/3 is 0.3333. */
export function rateValue(rate: number): number {
  if (!Number.isFinite(rate) || rate === 0) return rate;
  const sign = rate < 0 ? -1 : 1;
  const view = new DataView(new ArrayBuffer(8));
  view.setFloat64(0, Math.abs(rate));
  const hi = view.getUint32(0);
  const lo = view.getUint32(4);
  const expBits = (hi >>> 20) & 0x7ff;
  let m = (BigInt(hi & 0xfffff) << 32n) | BigInt(lo);
  let q: number;
  if (expBits === 0) q = -1074;
  else {
    m |= 1n << 52n;
    q = expBits - 1075;
  }
  // The exact value, num / den.
  let num = q >= 0 ? m << BigInt(q) : m;
  let den = q >= 0 ? 1n : 1n << BigInt(-q);
  const pow10 = (k: number) => 10n ** BigInt(k);
  // 10^e <= value < 10^(e+1)
  let e = Math.floor(Math.log10(Math.abs(rate)));
  const atLeast = (k: number) => (k >= 0 ? num >= den * pow10(k) : num * pow10(-k) >= den);
  while (!atLeast(e)) e--;
  while (atLeast(e + 1)) e++;
  const k = 3 - e;
  if (k >= 0) num *= pow10(k);
  else den *= pow10(-k);
  let n = num / den;
  const twice = (num - n * den) * 2n;
  if (twice > den || (twice === den && n % 2n === 1n)) n += 1n;
  const value = k >= 0 ? Number(n) / 10 ** k : Number(n) * 10 ** -k;
  return sign * value;
}

// ---- the wire --------------------------------------------------------------

function memberJson(m: Member) {
  return { id: m.id, name: m.name, user: m.user, guest: m.guest, web: m.web, away: m.away };
}

function songJson(s: Song | null) {
  if (!s) return null;
  return { ready: s.ready, bytes: s.bytes, received: s.received, ext: s.ext };
}

function itemJson(i: Item) {
  return {
    id: i.id,
    title: i.title,
    artist: i.artist,
    album: i.album,
    durationMs: i.durationMs,
    addedBy: i.addedBy,
    addedByName: i.addedByName,
    isrc: i.isrc,
    catalogId: i.catalogId,
    audioHash: i.audioHash,
    cover: i.cover,
    shared: songJson(i.shared),
  };
}

function lineJson(l: Line) {
  return { kind: l.kind, id: l.id, name: l.name, text: l.text, at: l.at };
}

const toOne = (id: string, msg: Record<string, unknown>): Msg => ({ to: id, msg });
const toAll = (msg: Record<string, unknown>): Msg => ({ to: "", msg });
const ok = (out: Out): Result => ({ ok: true, out });
const fail = (error: string): Result => ({ ok: false, error });

function newMember(m: MemberIn): Member {
  return {
    id: c(m.id),
    name: c(m.name),
    user: cOpt(m.user),
    guest: !!m.guest,
    web: !!m.web,
    away: false,
    awaySince: 0,
    token: c(m.token),
    said: [],
  };
}

// ---- the party -------------------------------------------------------------

export class Party {
  code: string;
  name: string;
  /** Empty while only guests are left. */
  host: string;
  members: Member[] = [];
  now: Item | null = null;
  queue: Item[] = [];
  playback: Playback = { itemId: null, positionMs: 0, atServerMs: 0, paused: false, rate: 1.0, keepPitch: true };
  songLimit: number;
  quota: number = PARTY_QUOTA_BYTES;
  /** Files no longer the party's, for the hub to delete: the party itself
   *  never touches storage. */
  dropped: string[] = [];

  private votes_ = new Set<string>();
  private chat_: Line[] = [];
  private nextItem = 1;
  private rateOf: [number, number] | null = null;

  constructor(code: string, name: string, host: MemberIn, songLimit: number) {
    const h = newMember(host);
    this.code = c(code);
    this.host = h.id;
    this.songLimit = songLimit;
    const trimmed = trimChars(c(name), 40);
    this.name = trimmed === "" ? h.name + "'s party" : trimmed;
    this.members.push(h);
  }

  /** The whole party, as `party.party` on the wire. */
  viewJson(): Record<string, unknown> {
    return {
      code: this.code,
      name: this.name,
      hostId: this.host,
      members: this.members.map(memberJson),
      now: this.now ? itemJson(this.now) : null,
      queue: this.queue.map(itemJson),
      playback: {
        itemId: this.playback.itemId,
        positionMs: this.playback.positionMs,
        atServerMs: this.playback.atServerMs,
        paused: this.playback.paused,
        rate: this.rate(),
        keepPitch: this.playback.keepPitch,
      },
      skip: { votes: this.votes(), needed: this.votesNeeded() },
      sharedBytes: this.sharedBytes(),
      sharedLimit: this.quota,
    };
  }

  /** The speed as it goes on the wire; worked out once per speed, not per view (BigInt arithmetic). */
  private rate(): number {
    const rate = this.playback.rate;
    if (this.rateOf?.[0] !== rate) this.rateOf = [rate, rateValue(rate)];
    return this.rateOf[1];
  }

  member(id: string): Member | null {
    return this.members.find((m) => m.id === id) ?? null;
  }

  empty(): boolean {
    return this.members.length === 0;
  }

  votesNeeded(): number {
    const here = this.members.filter((m) => !m.away).length;
    return Math.max(Math.floor((here + 1) / 2), 1);
  }

  chatSize(): number {
    return this.chat_.length;
  }

  position(at: number): number {
    return playbackPosition(this.playback, at);
  }

  /** The skip votes, in the order the wire lists them. */
  votes(): string[] {
    return [...this.votes_].sort(byCodePoint);
  }

  /** Everyone at the party, in the order they came. */
  memberIds(): string[] {
    return this.members.map((m) => m.id);
  }

  /** A member's song token, or null when they are not at the party. */
  token(id: string): string | null {
    return this.member(id)?.token ?? null;
  }

  setQuota(quota: number): void {
    this.quota = quota;
  }

  /** Files let go of since last asked. */
  takeDropped(): string[] {
    const out = this.dropped;
    this.dropped = [];
    return out;
  }

  /** Every file the party holds, for when it ends; the queue is emptied. */
  files(): string[] {
    const out = this.dropped;
    this.dropped = [];
    if (this.now?.file != null) out.push(this.now.file);
    this.now = null;
    for (const i of this.queue) if (i.file != null) out.push(i.file);
    this.queue = [];
    return out;
  }

  private item(itemId: string): Item | null {
    if (this.now && this.now.id === itemId) return this.now;
    return this.queue.find((i) => i.id === itemId) ?? null;
  }

  private partyMsg(): Msg {
    return toAll({ t: "party", party: this.viewJson() });
  }

  private line(kind: LineKind, id: string | null, who: string, text: string, at: number): Msg {
    const l: Line = { kind, id, name: who, text, at };
    this.chat_.push(l);
    while (this.chat_.length > CHAT_HISTORY) this.chat_.shift();
    return toAll({ t: "line", line: lineJson(l) });
  }

  /**
   * What brings one member up to date: the song token, the chat, the party.
   * [withParty] false leaves the party off, for a join, which sends it to
   * everyone in the same breath.
   */
  welcomeBack(id: string, withParty = true): Out {
    const m = this.member(id);
    if (!m) return [];
    const out = [
      toOne(id, { t: "joined", code: this.code, songToken: m.token }),
      toOne(id, { t: "history", lines: this.chat_.map(lineJson) }),
    ];
    if (withParty) out.push(toOne(id, { t: "party", party: this.viewJson() }));
    return out;
  }

  join(member: MemberIn, at: number): Result {
    const m = newMember(member);
    if (this.member(m.id)) return fail("already in the party");
    if (this.members.length >= MAX_MEMBERS) return fail("the party is full");
    // An app member takes the host's seat when only guests held the party.
    if (this.host === "" && !m.guest) this.host = m.id;
    this.members.push(m);
    // Its own copy of the party goes out with everyone's below.
    const out = this.welcomeBack(m.id, false);
    out.push(this.line("join", m.id, m.name, m.web ? "web" : "", at));
    out.push(...this.skipIfVoted(at));
    out.push(this.partyMsg());
    return ok(out);
  }

  leave(id: string, at: number): Out {
    const index = this.members.findIndex((m) => m.id === id);
    if (index < 0) return [];
    const who = this.members[index].name;
    this.members.splice(index, 1);
    this.votes_.delete(id);
    if (this.empty()) return [];
    if (this.host === id) {
      // The seat passes to the app member who joined next.
      this.host = this.members.find((m) => !m.guest)?.id ?? "";
    }
    const out: Out = [this.line("leave", id, who, "", at)];
    out.push(...this.skipIfVoted(at));
    out.push(this.partyMsg());
    return out;
  }

  setAway(id: string, away: boolean, at: number): Out {
    const m = this.member(id);
    if (!m || m.away === away) return [];
    m.away = away;
    if (away) m.awaySince = at;
    const out = this.skipIfVoted(at);
    out.push(this.partyMsg());
    return out;
  }

  add(id: string, input: ItemIn, ref: string, at: number): Result {
    const m = this.member(id);
    if (!m) return fail("not at the party");
    const catalogIn = cOpt(input.catalogId);
    const share = !!input.share;
    // A website guest has no file to share, so only a song from the catalogue,
    // which every phone and the website can each play from there.
    if (m.guest && (share || catalogIn === null || catalogIn === ""))
      return fail("a website guest can add only songs from the catalogue");
    const title = trimChars(c(input.title ?? ""), 200);
    if (title === "") return fail("the song has no title");
    const durationMs = input.durationMs ?? 0;
    if (durationMs === 0) return fail("the song has no length");
    if (this.queue.length >= MAX_QUEUE) return fail("the queue is full");
    const item: Item = {
      id: "s" + this.nextItem++,
      title,
      artist: trimChars(c(input.artist ?? ""), 200),
      album: trimChars(c(input.album ?? ""), 200),
      durationMs,
      addedBy: id,
      addedByName: m.name,
      isrc: shortField(cOpt(input.isrc), 16),
      catalogId: shortField(catalogIn, 128),
      audioHash: shortField(cOpt(input.audioHash), 128),
      cover: shortField(cOpt(input.cover), 512),
      shared: null,
      file: null,
    };
    // A cover that is not https is dropped, not refused: the website shows it.
    if (item.cover !== null && !item.cover.startsWith("https://")) item.cover = null;
    if (share) item.shared = { ready: false, bytes: 0, received: 0, ext: "" };
    const itemId = item.id;
    const adder = item.addedByName;
    // With nothing playing it plays now, from the start.
    if (!this.now) this.play(item, at);
    else this.queue.push(item);
    return ok([
      toOne(id, { t: "added", ref: c(ref), itemId }),
      this.line("add", id, adder, title, at),
      this.partyMsg(),
    ]);
  }

  private play(item: Item, at: number): void {
    // The host's speed holds from song to song.
    this.playback = {
      itemId: item.id,
      positionMs: 0,
      atServerMs: at,
      paused: false,
      rate: this.playback.rate,
      keepPitch: this.playback.keepPitch,
    };
    this.now = item;
    this.votes_.clear();
  }

  private advance(at: number): void {
    if (this.now?.file != null) this.dropped.push(this.now.file);
    this.now = null;
    this.votes_.clear();
    if (this.queue.length === 0) {
      this.playback = {
        itemId: null,
        positionMs: 0,
        atServerMs: at,
        paused: false,
        rate: this.playback.rate,
        keepPitch: this.playback.keepPitch,
      };
    } else {
      this.play(this.queue.shift()!, at);
    }
  }

  remove(id: string, itemId: string, at: number): Result {
    const i = this.item(itemId);
    if (!i) return fail("that song is not in the queue");
    if (this.host !== id && i.addedBy !== id) return fail("only the host or whoever added it can take it out");
    if (this.now && this.now.id === itemId) {
      // The one playing goes as a skip does.
      this.advance(at);
    } else {
      const index = this.queue.findIndex((q) => q.id === itemId);
      if (index >= 0) {
        const gone = this.queue[index];
        if (gone.file != null) this.dropped.push(gone.file);
        this.queue.splice(index, 1);
      }
    }
    return ok([this.partyMsg()]);
  }

  /** Host: song itemId to place `to` in Up next (0 is next; past the end is last). */
  moveItem(id: string, itemId: string, to: number): Result {
    const why = this.hostOnly(id);
    if (why) return fail(why);
    const index = this.queue.findIndex((q) => q.id === itemId);
    if (index < 0) return fail("that song is not in Up next");
    const [moving] = this.queue.splice(index, 1);
    this.queue.splice(Math.min(to, this.queue.length), 0, moving);
    return ok([this.partyMsg()]);
  }

  private hostOnly(id: string): string {
    return this.host !== "" && this.host === id ? "" : "only the host can do that";
  }

  private playing(): string {
    return this.now ? "" : "nothing is playing";
  }

  private currentIs(itemId: string | null): string {
    if (itemId !== null && this.playback.itemId !== itemId) return "that song is already over";
    return "";
  }

  private firstWhy(...whys: string[]): string {
    return whys.find((w) => w !== "") ?? "";
  }

  pause(id: string, at: number): Result {
    const why = this.firstWhy(this.hostOnly(id), this.playing());
    if (why) return fail(why);
    if (!this.playback.paused) {
      this.playback.positionMs = this.position(at);
      this.playback.atServerMs = at;
      this.playback.paused = true;
    }
    return ok([this.partyMsg()]);
  }

  resume(id: string, at: number): Result {
    const why = this.firstWhy(this.hostOnly(id), this.playing());
    if (why) return fail(why);
    if (this.playback.paused) {
      this.playback.atServerMs = at;
      this.playback.paused = false;
    }
    return ok([this.partyMsg()]);
  }

  seek(id: string, positionMs: number, at: number): Result {
    const why = this.firstWhy(this.hostOnly(id), this.playing());
    if (why) return fail(why);
    this.playback.positionMs = Math.min(positionMs, this.now!.durationMs);
    this.playback.atServerMs = at;
    return ok([this.partyMsg()]);
  }

  /** Host: the party's speed, and whether the pitch is kept (false: vinyl style). */
  setRate(id: string, rate: number, keepPitch: boolean, at: number): Result {
    const why = this.hostOnly(id);
    if (why) return fail(why);
    if (!Number.isFinite(rate) || rate < MIN_RATE || rate > MAX_RATE)
      return fail("the speed must be between 0.25x and 4x");
    // From here on at the new speed: the moment so far is kept as it was.
    if (!this.playback.paused) {
      this.playback.positionMs = this.position(at);
      this.playback.atServerMs = at;
    }
    this.playback.rate = rate;
    this.playback.keepPitch = keepPitch;
    return ok([this.partyMsg()]);
  }

  /** Host: on to the next song. itemId names the song meant (null: whatever
   *  plays), so two skips at once skip one song, not two. */
  skip(id: string, itemId: string | null, at: number): Result {
    const why = this.firstWhy(this.hostOnly(id), this.playing(), this.currentIs(itemId));
    if (why) return fail(why);
    const title = this.now!.title;
    const who = this.member(id)?.name ?? "";
    this.advance(at);
    return ok([this.line("skip", id, who, title, at), this.partyMsg()]);
  }

  vote(id: string, itemId: string | null, yes: boolean, at: number): Result {
    if (!this.member(id)) return fail("not at the party");
    const why = this.firstWhy(this.playing(), this.currentIs(itemId));
    if (why) return fail(why);
    if (yes) this.votes_.add(id);
    else this.votes_.delete(id);
    const out = this.skipIfVoted(at);
    out.push(this.partyMsg());
    return ok(out);
  }

  private skipIfVoted(at: number): Out {
    if (!this.now || this.votes_.size === 0) return [];
    // Only the votes of those here count: someone away has no say.
    let here = 0;
    for (const v of this.votes_) {
      const m = this.member(v);
      if (m && !m.away) ++here;
    }
    if (here < this.votesNeeded()) return [];
    const title = this.now.title;
    this.advance(at);
    return [this.line("skip", null, "", title, at)];
  }

  /** The hub's beat: a host gone a while hands the seat on, and a song whose
   *  length has run out moves the party on. */
  tick(at: number): Out {
    const out: Out = [];
    // A host gone a while: the seat passes to an app member who is here, so
    // the party carries on with someone at its controls. Back later, the old
    // host is a member like the rest.
    const h = this.member(this.host);
    if (h && h.away && at >= h.awaySince + HOST_AWAY_MS) {
      for (const m of this.members) {
        if (m.guest || m.away || m.id === this.host) continue;
        this.host = m.id;
        out.push(this.line("host", m.id, m.name, "", at));
        out.push(this.partyMsg());
        break;
      }
    }
    if (!this.now) return out;
    if (this.playback.paused || this.position(at) < this.now.durationMs + END_GRACE_MS) return out;
    this.advance(at);
    out.push(this.partyMsg());
    return out;
  }

  chat(id: string, text: string, at: number): Result {
    const said = trimChars(c(text), CHAT_MAX_CHARS);
    // Blank lines are said by nobody.
    if (said === "") return ok([]);
    const m = this.member(id);
    if (!m) return fail("not at the party");
    while (m.said.length > 0 && at - Math.min(at, m.said[0]) >= CHAT_WINDOW_MS) m.said.shift();
    if (m.said.length >= CHAT_BURST) return fail("slow down a little");
    m.said.push(at);
    return ok([this.line("say", id, m.name, said, at)]);
  }

  kick(id: string, target: string, at: number): Result {
    const why = this.hostOnly(id);
    if (why) return fail(why);
    if (id === target) return fail("you cannot kick yourself");
    if (!this.member(target)) return fail("no such member");
    const out: Out = [toOne(target, { t: "left", reason: "kicked by the host" })];
    out.push(...this.leave(target, at));
    return ok(out);
  }

  /** Host: hands the host's seat to target, an app member. */
  passHost(id: string, target: string, at: number): Result {
    const why = this.hostOnly(id);
    if (why) return fail(why);
    if (id === target) return fail("you are the host already");
    const m = this.member(target);
    if (!m) return fail("no such member");
    if (m.guest) return fail("a website guest cannot be the host");
    this.host = target;
    return ok([this.line("host", target, m.name, "", at), this.partyMsg()]);
  }

  /** Host: ends the party for everyone. The hub then lets every seat and file go. */
  end(id: string): Result {
    const why = this.hostOnly(id);
    if (why) return fail(why);
    return ok([toAll({ t: "left", reason: "the host ended the party" })]);
  }

  /** Whether token is a member's: what lets the website search the catalogue. */
  knowsToken(token: string): boolean {
    if (token === "") return false;
    return this.members.some((m) => m.token === token);
  }

  /** The cover address of song itemId, for a member holding token; "" for none. */
  coverFor(token: string, itemId: string): string {
    if (!this.knowsToken(token)) return "";
    return this.item(itemId)?.cover ?? "";
  }

  /** The catalogue id of song itemId ("tidal:<id>"), for a member holding token; "" for none. */
  catalogFor(token: string, itemId: string): string {
    if (!this.knowsToken(token)) return "";
    return this.item(itemId)?.catalogId ?? "";
  }

  /** Song itemId as the party sends it, for a member holding token; null for none. */
  itemFor(token: string, itemId: string): Record<string, unknown> | null {
    if (!this.knowsToken(token)) return null;
    const i = this.item(itemId);
    return i ? itemJson(i) : null;
  }

  // Shared songs: uploaded over HTTP by whoever added them, one file per
  // song, deleted when the song leaves the queue or the party ends.

  sharedBytes(): number {
    let total = 0;
    const count = (i: Item) => {
      if (i.file != null && i.shared) total += i.shared.bytes;
    };
    if (this.now) count(this.now);
    for (const i of this.queue) count(i);
    return total;
  }

  /** The most an upload of song itemId by id, declared at `declared` bytes
   *  (0: not said), may be; or why it may not. */
  uploadLimit(id: string, itemId: string, declared: number): { limit: number } | { error: string } {
    const i = this.item(itemId);
    if (!i) return { error: "that song is not in the queue" };
    if (i.addedBy !== id) return { error: "only whoever added a song shares it" };
    // The file being replaced does not count against the room left.
    const held = i.file != null && i.shared ? i.shared.bytes : 0;
    const used = this.sharedBytes() - held;
    const left = this.quota > used ? this.quota - used : 0;
    const limit = Math.min(this.songLimit, left);
    if (declared > this.songLimit) return { error: "the song is too big to share" };
    if (declared > limit || limit === 0) return { error: "the party has no room for more shared songs" };
    return { limit };
  }

  uploading(itemId: string, bytes: number, received: number): Out {
    const i = this.item(itemId);
    if (!i) return [];
    if (!i.shared) i.shared = { ready: false, bytes: 0, received: 0, ext: "" };
    // A second upload over a song already shared keeps the first until it is done.
    if (i.shared.ready) return [];
    i.shared.bytes = bytes;
    i.shared.received = received;
    return [this.partyMsg()];
  }

  shared(id: string, itemId: string, bytes: number, ext: string, path: string): Result {
    const limit = this.uploadLimit(id, itemId, bytes);
    if ("error" in limit) return fail(limit.error);
    if (bytes > limit.limit) return fail("the party has no room for more shared songs");
    const i = this.item(itemId)!;
    i.shared = { ready: true, bytes, received: bytes, ext: c(ext) };
    if (i.file != null) this.dropped.push(i.file);
    i.file = c(path);
    return ok([this.partyMsg()]);
  }

  uploadFailed(itemId: string): Out {
    const i = this.item(itemId);
    if (!i) return [];
    // Unless an earlier upload of it is still there, it shows as not shared.
    if (i.file == null) i.shared = null;
    return [this.partyMsg()];
  }

  songFor(token: string, itemId: string): SongFile | null {
    if (!this.knowsToken(token)) return null;
    const i = this.item(itemId);
    if (!i || !i.shared || !i.shared.ready || i.file == null) return null;
    return { song: { ...i.shared }, path: i.file };
  }
}

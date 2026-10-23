// What a phone or the website says, read as the Rust server read it (serde):
// a JSON object tagged by `t`, required fields required, defaults where it had
// them, unknown fields ignored. Anything else is "bad message: …" and the
// socket stays open. The rooms' own types (a chart, live scores, a result)
// are read by src/room.ts; a party's song by src/party.ts's shape here.

export type RoomKind = "game" | "party";

export interface QueueItemIn {
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  isrc: string | null;
  catalogId: string | null;
  audioHash: string | null;
  cover: string | null;
  share: boolean;
}

export type ClientMsg =
  | { t: "hello"; name: string; client: string; token: string; resume: string; guest: boolean; party: string }
  | { t: "ping"; c: number }
  | { t: "rooms" }
  | { t: "create"; name: string; kind: RoomKind }
  | { t: "join"; code: string }
  | { t: "leave" }
  | { t: "pick"; chart: unknown }
  | { t: "have"; audio: boolean }
  | { t: "ready"; ready: boolean }
  | { t: "start" }
  | { t: "kick"; id: string }
  | { t: "frame"; live: unknown }
  | { t: "finish"; result: unknown }
  | { t: "chat"; text: string }
  | { t: "queueAdd"; item: QueueItemIn; ref: string }
  | { t: "queueRemove"; id: string }
  | { t: "queueMove"; id: string; to: number }
  | { t: "pause" }
  | { t: "resume" }
  | { t: "seek"; positionMs: number }
  | { t: "skip"; itemId: string | null }
  | { t: "setRate"; rate: number; keepPitch: boolean }
  | { t: "passHost"; id: string }
  | { t: "endParty" }
  | { t: "skipVote"; itemId: string | null; vote: boolean };

class Bad extends Error {}

type Obj = Record<string, unknown>;

const has = (o: Obj, k: string) => Object.hasOwn(o, k) && o[k] !== undefined;

function str(o: Obj, k: string, fallback?: string): string {
  if (!has(o, k)) {
    if (fallback !== undefined) return fallback;
    throw new Bad(`missing field \`${k}\``);
  }
  if (typeof o[k] !== "string") throw new Bad(`invalid type for \`${k}\`, expected a string`);
  return o[k] as string;
}

function optStr(o: Obj, k: string): string | null {
  if (!has(o, k) || o[k] === null) return null;
  if (typeof o[k] !== "string") throw new Bad(`invalid type for \`${k}\`, expected a string`);
  return o[k] as string;
}

function bool(o: Obj, k: string, fallback?: boolean): boolean {
  if (!has(o, k)) {
    if (fallback !== undefined) return fallback;
    throw new Bad(`missing field \`${k}\``);
  }
  if (typeof o[k] !== "boolean") throw new Bad(`invalid type for \`${k}\`, expected a boolean`);
  return o[k] as boolean;
}

/** A u64 (or usize): a whole number, not negative. */
function uint(o: Obj, k: string, fallback?: number): number {
  if (!has(o, k)) {
    if (fallback !== undefined) return fallback;
    throw new Bad(`missing field \`${k}\``);
  }
  const v = o[k];
  if (typeof v !== "number" || !Number.isInteger(v) || v < 0) throw new Bad(`invalid value for \`${k}\`, expected u64`);
  return v;
}

function num(o: Obj, k: string): number {
  if (!has(o, k)) throw new Bad(`missing field \`${k}\``);
  if (typeof o[k] !== "number" || !Number.isFinite(o[k])) throw new Bad(`invalid type for \`${k}\`, expected f64`);
  return o[k] as number;
}

function obj(o: Obj, k: string): Obj {
  if (!has(o, k)) throw new Bad(`missing field \`${k}\``);
  const v = o[k];
  if (!v || typeof v !== "object" || Array.isArray(v)) throw new Bad(`invalid type for \`${k}\`, expected a map`);
  return v as Obj;
}

function queueItem(o: Obj): QueueItemIn {
  return {
    title: str(o, "title"),
    artist: str(o, "artist", ""),
    album: str(o, "album", ""),
    durationMs: uint(o, "durationMs", 0),
    isrc: optStr(o, "isrc"),
    catalogId: optStr(o, "catalogId"),
    audioHash: optStr(o, "audioHash"),
    cover: optStr(o, "cover"),
    share: bool(o, "share", false),
  };
}

/** One message, or why it is not one ("bad message: …"). */
export function parseClient(text: string): ClientMsg | { error: string } {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch (e) {
    return { error: `bad message: ${(e as Error).message}` };
  }
  try {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) throw new Bad("expected an object");
    const o = raw as Obj;
    const t = str(o, "t");
    switch (t) {
      case "hello":
        return { t, name: str(o, "name"), client: str(o, "client", ""), token: str(o, "token", ""), resume: str(o, "resume", ""), guest: bool(o, "guest", false), party: str(o, "party", "") };
      case "ping":
        return { t, c: uint(o, "c") };
      case "rooms": case "leave": case "start": case "pause": case "resume": case "endParty":
        return { t };
      case "create": {
        const kind = str(o, "kind", "game");
        if (kind !== "game" && kind !== "party") throw new Bad(`unknown variant \`${kind}\`, expected \`game\` or \`party\``);
        return { t, name: str(o, "name", ""), kind };
      }
      case "join":
        return { t, code: str(o, "code") };
      case "pick":
        return { t, chart: obj(o, "chart") };
      case "have":
        return { t, audio: bool(o, "audio") };
      case "ready":
        return { t, ready: bool(o, "ready") };
      case "kick": case "queueRemove": case "passHost":
        return { t, id: str(o, "id") } as ClientMsg;
      case "frame":
        return { t, live: obj(o, "live") };
      case "finish":
        return { t, result: obj(o, "result") };
      case "chat":
        return { t, text: str(o, "text") };
      case "queueAdd":
        return { t, item: queueItem(obj(o, "item")), ref: str(o, "ref", "") };
      case "queueMove":
        return { t, id: str(o, "id"), to: uint(o, "to") };
      case "seek":
        return { t, positionMs: uint(o, "positionMs") };
      case "skip":
        return { t, itemId: optStr(o, "itemId") };
      case "setRate":
        return { t, rate: num(o, "rate"), keepPitch: bool(o, "keepPitch", true) };
      case "skipVote":
        return { t, itemId: optStr(o, "itemId"), vote: bool(o, "vote", true) };
      default:
        throw new Bad(`unknown variant \`${t}\``);
    }
  } catch (e) {
    return { error: `bad message: ${(e as Error).message}` };
  }
}

/** A file extension safe to keep: lower-case letters and digits, up to five; anything else is `bin`. */
export function cleanExt(ext: string): string {
  const e = ext.trim().replace(/^\.+/, "").toLowerCase();
  return /^[a-z0-9]{1,5}$/.test(e) ? e : "bin";
}

export function audioType(ext: string): string {
  switch (ext) {
    case "flac": return "audio/flac";
    case "mp3": return "audio/mpeg";
    case "ogg": case "opus": case "oga": return "audio/ogg";
    case "m4a": case "mp4": case "aac": case "alac": return "audio/mp4";
    case "wav": return "audio/wav";
    default: return "application/octet-stream";
  }
}

/** One byte range of `total`, as `Range: bytes=…` asks, or null when it cannot be served. */
export function byteRange(range: string, total: number): [number, number] | null {
  const spec = range.trim().startsWith("bytes=") ? range.trim().slice(6) : null;
  if (spec === null || spec.includes(",") || total === 0) return null;
  const dash = spec.indexOf("-");
  if (dash < 0) return null;
  const start = spec.slice(0, dash).trim(), end = spec.slice(dash + 1).trim();
  const int = (s: string) => (/^\d+$/.test(s) ? Number(s) : NaN);
  let first: number, last: number;
  if (start === "") {
    const n = int(end);
    if (!(n > 0)) return null;
    first = Math.max(0, total - n);
    last = total - 1;
  } else {
    first = int(start);
    if (Number.isNaN(first)) return null;
    if (end === "") last = total - 1;
    else {
      const e = int(end);
      if (Number.isNaN(e)) return null;
      last = Math.min(e, total - 1);
    }
  }
  return first <= last && first < total ? [first, last] : null;
}

export function randomHex(n: number): string {
  const bytes = crypto.getRandomValues(new Uint8Array(Math.ceil(n / 2)));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("").slice(0, n);
}

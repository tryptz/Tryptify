// Tryptify Connect's messages, checked and trimmed here before the room keeps
// or relays any of them. Pure, so test.ts runs it without Cloudflare.
// The whole protocol is in ../README.md.

export const MAX_MESSAGE = 64 * 1024;
export const MAX_DEVICES = 20;
export const MAX_QUEUE = 100;
export const ACTIONS = new Set(["play", "pause", "next", "prev", "seek"]);
// "qobuz", "deezer" and "apple" are the app's other catalogues: another phone can play them, a browser cannot (yet).
export const SOURCES = new Set(["hifi", "cloud", "subsonic", "local", "qobuz", "deezer", "apple"]);
export const KINDS = new Set(["web", "android", "desktop"]);

export interface Device { id: string; name: string; kind: string }

/** A song as devices pass it around: enough for another device to find and play it, and to show it. */
export interface Track {
  src: string; id: string; title: string; artist: string; album: string;
  duration: number | null; quality: string; cover: string | null; coverArt: string | null;
}

/** What is playing, on which device, as of server time [at]; [since] is when it started playing there. */
export interface Now {
  device: string; playing: boolean; track: Track | null; position: number; rate: number; at: number; since: number;
}

const ID = /^[A-Za-z0-9_-]{8,64}$/;
const str = (v: unknown, max: number): string => (typeof v === "string" ? v.slice(0, max) : "");
const num = (v: unknown, lo: number, hi: number, dflt: number): number =>
  typeof v === "number" && Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : dflt;

/** The device a connection says it is (its query string), or null when it does not say properly. */
export function deviceFrom(params: URLSearchParams): Device | null {
  const id = params.get("device") ?? "";
  if (!ID.test(id)) return null;
  const kind = params.get("kind") ?? "web";
  return { id, name: str(params.get("name"), 60).trim() || "A device", kind: KINDS.has(kind) ? kind : "web" };
}

/** Only https covers travel: a blob: is one device's own, and a music server's links carry its sign-in. */
const cover = (v: unknown): string | null => {
  const s = str(v, 500);
  return /^https:\/\/[^\s]+$/.test(s) && !/[?&](t|s|p|u)=/.test(s) ? s : null;
};

export function cleanTrack(t: unknown): Track | null {
  if (!t || typeof t !== "object") return null;
  const o = t as Record<string, unknown>;
  const src = str(o.src, 16), id = str(o.id, 128);
  if (!SOURCES.has(src) || !id) return null;
  return {
    src, id, title: str(o.title, 300), artist: str(o.artist, 300), album: str(o.album, 300),
    duration: typeof o.duration === "number" && Number.isFinite(o.duration) && o.duration > 0 ? Math.min(o.duration, 86_400) : null,
    quality: str(o.quality, 40), cover: cover(o.cover), coverArt: str(o.coverArt, 128) || null,
  };
}

/** A device's "state" message → what the room keeps as now playing. */
export function cleanState(m: Record<string, unknown>, device: string, at: number): Now {
  return {
    device, playing: m.playing === true, track: cleanTrack(m.track),
    position: num(m.position, 0, 86_400, 0), rate: num(m.rate, 0.25, 4, 1), at,
    // Server time it started playing, by the device's estimate: of two devices that both
    // started, the later start wins, whichever message arrives first.
    since: num(m.since, 0, at + 60_000, at),
  };
}

export function cleanQueue(q: unknown): Track[] {
  return Array.isArray(q) ? q.slice(0, MAX_QUEUE).map(cleanTrack).filter((t): t is Track => !!t) : [];
}

/** Where [now] is at server time [t]: playing time runs on at its rate. */
export function positionAt(now: Now, t: number): number {
  if (!now.playing) return now.position;
  const p = now.position + (Math.max(0, t - now.at) / 1000) * now.rate;
  return now.track && now.track.duration ? Math.min(p, now.track.duration) : p;
}

/** A command another device may send to the one playing. */
export function cleanCommand(m: Record<string, unknown>): { action: string; value: number | null } | null {
  const action = str(m.action, 16);
  if (!ACTIONS.has(action)) return null;
  return { action, value: action === "seek" ? num(m.value, 0, 86_400, 0) : null };
}

/** The token from a connection: "Authorization: Bearer …" (the app), or the "bearer.…" subprotocol (browsers cannot set headers on a WebSocket). */
export function tokenFrom(headers: Headers): { token: string | null; protocol: boolean } {
  const auth = headers.get("authorization") ?? "";
  if (/^Bearer\s+\S+$/i.test(auth)) return { token: auth.replace(/^Bearer\s+/i, ""), protocol: false };
  const offered = (headers.get("sec-websocket-protocol") ?? "").split(",").map((s) => s.trim());
  const b = offered.find((p) => p.startsWith("bearer."));
  return { token: b ? b.slice(7) : null, protocol: offered.includes("tryptify-connect") };
}

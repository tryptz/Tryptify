// The song catalogue the website searches and plays from: a TrypT HiFi
// server (`TRYPT_HIFI_URL`), the one the app plays through. A guest finds a
// song here and adds it to the queue by its TIDAL id. A phone adds a song by
// the id of the catalogue it played it from (`tidal:`, `qobuz:`, `deezer:`),
// and every phone and the website play that very song from that catalogue:
// TIDAL and Qobuz as one file a browser fetches from the catalogue itself.
//
// Deezer is left out here. Its files come encrypted and the Rust server
// fetched and decrypted them onto its own disk (`deezer_file`, `deezer.rs`),
// which a Worker has no disk for. `catalogKey` still answers ["deezer", id];
// the caller looks such a song up by its name instead (`find`).
//
// A TrypT HiFi with `ACCESS_PASSWORD` set answers 401 without it. The
// password (`TRYPT_HIFI_KEY`) goes out as `X-Access-Key` on every request to
// the catalogue's own origin, never in an address and never in a log.

/** The most results one search hands back. */
export const MAX_RESULTS = 25;

/** How far apart two copies of one recording's lengths may be. */
export const LENGTH_SLACK_MS = 3_000;

/** How long one ask of the catalogue may take, answer included. */
export const TIMEOUT_MS = 20_000;

/** Redirects followed by hand, so the password goes to the catalogue only. */
const MAX_REDIRECTS = 5;

/** A song the catalogue found, as the website adds it to the queue. */
export interface Found {
  catalogId: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  isrc: string | null;
  cover: string | null;
  explicit: boolean;
}

/** Why the catalogue gave no answer, in the words the website may show. */
export class CatalogError extends Error {
  override name = "CatalogError";
}

export interface CatalogEnv {
  TRYPT_HIFI_URL?: string;
  TRYPT_HIFI_KEY?: string;
}

export class Catalog {
  readonly base: string;
  readonly #origin: string;
  readonly #key: string | undefined;
  readonly #fetch: typeof fetch;

  /** `TRYPT_HIFI_URL` (and its password), or null when it is unset or not a web address. */
  static from(env: CatalogEnv, fetchImpl: typeof fetch = fetch): Catalog | null {
    const url = (env.TRYPT_HIFI_URL ?? "").trim();
    if (!isWebAddress(url)) return null;
    return new Catalog(url, env.TRYPT_HIFI_KEY, fetchImpl);
  }

  /** Throws when [baseUrl] is not an http(s) address; `from` answers null instead. */
  constructor(baseUrl: string, accessKey?: string, fetchImpl: typeof fetch = fetch) {
    const base = baseUrl.trim().replace(/\/+$/, "");
    if (!isWebAddress(base)) throw new TypeError("the catalogue is not a web address");
    this.base = base;
    this.#origin = new URL(base).origin;
    this.#key = accessKey?.trim() || undefined;
    // Called as a plain function: a Worker's fetch refuses to run with
    // another object as `this` ("Illegal invocation").
    this.#fetch = (input, init) => fetchImpl(input, init);
  }

  /** Songs for [query], best first. */
  async search(query: string): Promise<Found[]> {
    let response: Response;
    try {
      response = await this.#get("/search/", { s: query, limit: String(MAX_RESULTS) });
      if (!response.ok) throw new Error(`HTTP ${statusLine(response)}`);
    } catch (e) {
      console.error(`catalogue: search failed: ${why(e)}`);
      throw new CatalogError("the catalogue did not answer");
    }
    let body: unknown;
    try {
      body = await response.json();
    } catch (e) {
      console.error(`catalogue: search answer unreadable: ${why(e)}`);
      throw new CatalogError("the catalogue's answer was unreadable");
    }
    return foundIn(body);
  }

  /**
   * The one-file address of TIDAL song [id], for a browser to play, as the
   * catalogue names it; throws why not when it cannot (TIDAL refusing the id).
   */
  streamUrl(id: number): Promise<string> {
    // 5 is the AAC tier: one file every browser plays.
    return this.#fileUrl("/api/tidal/download-music", id, "5");
  }

  /**
   * The address of Qobuz song [id]'s file: a signed link on the catalogue
   * itself, open to any page and seekable, good for a quarter of an hour.
   */
  qobuzUrl(id: number): Promise<string> {
    // 5 is MP3 320: every browser plays it, and it downloads quickly.
    return this.#fileUrl("/api/download-music", id, "5");
  }

  /**
   * The address of Deezer song [id]'s file, decrypted by the catalogue
   * (TrypT HiFi's `decrypted=1`): Deezer's own file is striped with Blowfish
   * and no browser plays it. A signed link on the catalogue, like Qobuz's.
   */
  deezerUrl(id: number): Promise<string> {
    // 5 is MP3 128 on Deezer: every browser plays it.
    return this.#fileUrl("/api/deezer/download-music", id, "5", { decrypted: "1" });
  }

  /**
   * Whether file address [url], as the catalogue handed it out, needs its
   * password: its own routes do (TIDAL's assembled /api/tidal/stream-file),
   * except the signed /api/file links. A browser sent there is refused, so
   * the hub fetches such a file itself ([file]) and passes it on.
   */
  needsKey(url: string): boolean {
    if (!this.#key) return false;
    const u = new URL(url);
    return u.origin === this.#origin && u.pathname !== "/api/file";
  }

  /**
   * The file at [url] on the catalogue, with the password, whole (`whole=1`:
   * an assembled TIDAL file then comes with its length, so the page can say
   * how far its download has got). No time limit: a long song takes a while.
   */
  file(url: string): Promise<Response> {
    const u = new URL(url);
    u.searchParams.set("whole", "1");
    const headers = new Headers();
    if (this.#key && u.origin === this.#origin) headers.set("X-Access-Key", this.#key);
    return this.#fetch(u.toString(), { headers });
  }

  /**
   * Whether TIDAL song [id] is the song called [title]: false when TIDAL
   * names another song, null when it could not be asked. A Qobuz song's id
   * sent as a TIDAL one played whatever TIDAL song has that number.
   */
  async isSong(id: number, title: string): Promise<boolean | null> {
    let body: unknown;
    try {
      // Like the Rust, the status is not looked at: an answer naming a title is enough.
      body = await (await this.#get("/info/", { id: String(id) })).json();
    } catch {
      return null;
    }
    const named = str(at(body, "data", "title")) ?? str(at(body, "title"));
    return named === null ? null : sameTitle(named, title);
  }

  /**
   * The TIDAL copy of a song known only by its name (a file on someone's
   * phone, or a Qobuz, Apple or Deezer pick): searched by title and artist
   * and chosen by [pick].
   */
  async find(title: string, artist: string, durationMs: number, isrc?: string | null): Promise<number | null> {
    let found: Found[];
    try {
      found = await this.search(`${title} ${artist}`.trim());
    } catch {
      return null;
    }
    return pick(found, title, artist, durationMs, isrc);
  }

  /**
   * The `data.url` the catalogue's download route [path] answers for song
   * [id] at [quality], made absolute; throws why not when it refuses.
   */
  async #fileUrl(path: string, id: number, quality: string, extra: Record<string, string> = {}): Promise<string> {
    let response: Response;
    try {
      response = await this.#get(path, { track_id: String(id), quality, ...extra });
    } catch (e) {
      throw new CatalogError(`did not answer: ${why(e)}`);
    }
    let text: string;
    try {
      text = await response.text();
    } catch (e) {
      throw new CatalogError(`answer cut off: ${why(e)}`);
    }
    if (!response.ok) throw new CatalogError(`HTTP ${statusLine(response)}: ${first200(text)}`);
    let body: unknown;
    try {
      body = JSON.parse(text);
    } catch {
      throw new CatalogError(`unreadable: ${first200(text)}`);
    }
    const url = str(at(body, "data", "url")) ?? "";
    if (url.startsWith("/")) return `${this.base}${url}`;
    if (isWebAddress(url)) return url;
    throw new CatalogError(`no url in ${first200(text)}`);
  }

  /**
   * GET [path] on the catalogue with [query], within [TIMEOUT_MS] (answer
   * included). Redirects are followed by hand so that the password goes
   * only to the catalogue's own origin, never on to another host.
   */
  async #get(path: string, query: Record<string, string>): Promise<Response> {
    const signal = AbortSignal.timeout(TIMEOUT_MS);
    let url = new URL(`${this.base}${path}`);
    for (const [k, v] of Object.entries(query)) url.searchParams.append(k, v);
    for (let hop = 0; ; hop++) {
      const headers = new Headers();
      if (this.#key && url.origin === this.#origin) headers.set("X-Access-Key", this.#key);
      const response = await this.#fetch(url.toString(), { headers, signal, redirect: "manual" });
      const location = response.headers.get("location");
      if (!REDIRECTS.has(response.status) || !location || hop >= MAX_REDIRECTS) return response;
      const next = new URL(location, url);
      if (next.protocol !== "https:" && next.protocol !== "http:") return response;
      await response.body?.cancel();
      url = next;
    }
  }
}

const REDIRECTS = new Set([301, 302, 303, 307, 308]);

function isWebAddress(s: string): boolean {
  return s.startsWith("https://") || s.startsWith("http://");
}

/** Two titles for one song: the same letters, one maybe with a version on. */
export function sameTitle(a: string, b: string): boolean {
  const [x, y] = [norm(a), norm(b)];
  return x !== "" && y !== "" && (x.startsWith(y) || y.startsWith(x));
}

/** Letters and digits only, lowercased, as Rust's `is_alphanumeric` and `to_lowercase` do it. */
function norm(s: string): string {
  let out = "";
  // Code point by code point: a whole-string toLowerCase would turn a final Σ into ς, Rust does not.
  for (const c of s) if (ALPHANUMERIC.test(c)) out += c.toLowerCase();
  return out;
}

const ALPHANUMERIC = /^[\p{Alphabetic}\p{N}]$/u;

/**
 * The one of [found] that is the song: the same ISRC when it has one, else
 * the same title and artist at near enough the same length (the closest),
 * never just the first result.
 */
export function pick(
  found: readonly Found[],
  title: string,
  artist: string,
  durationMs: number,
  isrc?: string | null,
): number | null {
  const code = (isrc ?? "").trim();
  if (code !== "") {
    const same = found.find((f) => f.isrc !== null && asciiLower(f.isrc) === asciiLower(code));
    if (same) return tidalId(same.catalogId);
  }
  const [t, a] = [norm(title), norm(artist)];
  if (t === "") return null;
  let best: Found | null = null;
  let bestGap = Infinity;
  for (const f of found) {
    const ft = norm(f.title);
    if (!(ft.startsWith(t) || t.startsWith(ft))) continue;
    const fa = norm(f.artist);
    if (!(a === "" || fa.includes(a) || a.includes(fa))) continue;
    const gap = durationMs === 0 ? 0 : Math.abs(f.durationMs - durationMs);
    if (durationMs !== 0 && gap > LENGTH_SLACK_MS) continue;
    // The first of equally close ones, as Rust's min_by_key keeps it.
    if (gap < bestGap) [best, bestGap] = [f, gap];
  }
  return best === null ? null : tidalId(best.catalogId);
}

function asciiLower(s: string): string {
  return s.replace(/[A-Z]/g, (c) => c.toLowerCase());
}

/** The TIDAL id in a queue item's catalogue id (`tidal:<digits>`). */
export function tidalId(catalogId: string): number | null {
  const key = catalogKey(catalogId);
  return key !== null && key[0] === "tidal" ? key[1] : null;
}

/**
 * A queue item's catalogue id split into its catalogue and number:
 * `qobuz:12` is ["qobuz", 12]. `deezer:<id>` is answered too, though this
 * server cannot play Deezer files (see the top of this file).
 */
export function catalogKey(catalogId: string): [string, number] | null {
  const colon = catalogId.indexOf(":");
  if (colon < 0) return null;
  const kind = catalogId.slice(0, colon);
  const digits = catalogId.slice(colon + 1);
  if (kind === "" || !/^[0-9]+$/.test(digits)) return null;
  const id = Number(digits);
  // Rust takes any u64; past 2^53 a JS number is no longer that id, so none.
  return Number.isSafeInteger(id) && id > 0 ? [kind, id] : null;
}

/** A TIDAL cover's picture, from its id (a UUID). */
export function coverUrl(id: string): string | null {
  if (!/^[0-9A-Fa-f-]{36}$/.test(id)) return null;
  return `https://resources.tidal.com/images/${id.replaceAll("-", "/")}/640x640.jpg`;
}

/** The songs in a TrypT HiFi search answer that can be streamed. */
export function foundIn(body: unknown): Found[] {
  const nested = at(body, "data", "items");
  const items = Array.isArray(nested) ? nested : at(body, "items");
  if (!Array.isArray(items)) return [];
  const out: Found[] = [];
  for (const item of items) {
    const f = found(item);
    if (f) out.push(f);
    if (out.length >= MAX_RESULTS) break;
  }
  return out;
}

function found(item: unknown): Found | null {
  if (at(item, "allowStreaming") === false) return null;
  const id = u64(at(item, "id"));
  const rawTitle = str(at(item, "title"));
  if (id === null || rawTitle === null) return null;
  let title = rawTitle.trim();
  // "One More Time" and its "Radio Edit" are told apart as the app does.
  const version = str(at(item, "version"))?.trim() ?? "";
  if (version !== "" && !title.includes(version)) title = `${title} (${version})`;
  const seconds = u64(at(item, "duration"));
  if (seconds === null) return null;
  const durationMs = seconds * 1000;
  if (title === "" || durationMs === 0) return null;
  const artists = at(item, "artists");
  const names = Array.isArray(artists)
    ? artists.map((a) => str(at(a, "name"))).filter((n): n is string => n !== null)
    : [];
  const artist = names.length > 0 ? names.join(", ") : str(at(item, "artist", "name")) ?? "";
  const isrc = str(at(item, "isrc"));
  const cover = str(at(item, "album", "cover"));
  return {
    catalogId: `tidal:${id}`,
    title,
    artist,
    album: str(at(item, "album", "title")) ?? "",
    durationMs,
    isrc: isrc ? isrc : null,
    cover: cover === null ? null : coverUrl(cover),
    explicit: at(item, "explicit") === true,
  };
}

/** [v] at [path], like serde_json's `v["a"]["b"]`: undefined where there is nothing. */
function at(v: unknown, ...path: string[]): unknown {
  for (const k of path) {
    if (v === null || typeof v !== "object" || Array.isArray(v)) return undefined;
    v = (v as Record<string, unknown>)[k];
  }
  return v;
}

function str(v: unknown): string | null {
  return typeof v === "string" ? v : null;
}

/** serde_json's `as_u64`: a whole, non-negative number. */
function u64(v: unknown): number | null {
  return typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? v : null;
}

function first200(text: string): string {
  return Array.from(text).slice(0, 200).join("");
}

function why(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** "404 Not Found", as Rust's StatusCode prints itself. */
function statusLine(response: Response): string {
  const reason = REASONS[response.status] ?? (response.statusText || "<unknown status code>");
  return `${response.status} ${reason}`;
}

const REASONS: Record<number, string> = {
  300: "Multiple Choices", 301: "Moved Permanently", 302: "Found", 303: "See Other",
  307: "Temporary Redirect", 308: "Permanent Redirect",
  400: "Bad Request", 401: "Unauthorized", 402: "Payment Required", 403: "Forbidden",
  404: "Not Found", 405: "Method Not Allowed", 408: "Request Timeout", 409: "Conflict",
  410: "Gone", 422: "Unprocessable Entity", 429: "Too Many Requests",
  500: "Internal Server Error", 501: "Not Implemented", 502: "Bad Gateway",
  503: "Service Unavailable", 504: "Gateway Timeout",
};

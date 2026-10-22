// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Tryptify Cloud as a personal Subsonic server: the listener's own library,
// stored for them and served for fast streaming, over the same API Navidrome
// speaks. The app's existing Subsonic code (data/subsonic/) plays it as is.
//
// The subset the app uses, in JSON (f=json): ping, getLicense,
// getMusicFolders, getAlbumList2, getAlbum, getArtists, getArtist, getSong,
// search3, getRandomSongs, stream, download, getCoverArt. Sign-in is the
// API's token scheme (t = md5(password + s)) or p=enc:hex, with a per-device
// password from POST /v1/subsonic-keys, never the Tryptify password. The
// username is the person's account id.

import { md5 } from "@noble/hashes/legacy.js";
import { accessOf } from "./rules";
import { getSong, type Bucket, type SongRow } from "./library";
import { loadAccount, type Db } from "./store";

const API_VERSION = "1.16.1";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
const md5Hex = (s: string) => hex(md5(new TextEncoder().encode(s)));

function sameText(a: string, b: string): boolean {
  if (a.length !== b.length || a.length === 0) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

const envelope = (body: Record<string, unknown>, status: "ok" | "failed" = "ok") =>
  Response.json({
    "subsonic-response": {
      status,
      version: API_VERSION,
      type: "tryptify-cloud",
      serverVersion: "1",
      openSubsonic: true,
      ...body,
    },
  });

/** Subsonic errors travel as HTTP 200 with status "failed", as the API defines. */
const failed = (code: number, message: string) => envelope({ error: { code, message } }, "failed");

/** The person [params] signs in as, or null. */
async function signIn(db: Db, params: URLSearchParams): Promise<string | null> {
  const user = params.get("u") ?? "";
  if (!UUID.test(user)) return null;
  const keys = await db.prepare("select password from cloud_subsonic_keys where user_id = ?1").bind(user).all<{ password: string }>();
  const token = (params.get("t") ?? "").toLowerCase();
  const salt = params.get("s") ?? "";
  let plain = params.get("p") ?? "";
  if (plain.startsWith("enc:")) {
    const h = plain.slice(4);
    plain = /^[0-9a-f]*$/i.test(h) ? new TextDecoder().decode(new Uint8Array(h.match(/../g)?.map((x) => parseInt(x, 16)) ?? [])) : "";
  }
  for (const { password } of keys.results) {
    if (token && salt && sameText(token, md5Hex(password + salt))) return user;
    if (plain && sameText(plain, password)) return user;
  }
  return null;
}

// ── Shapes ───────────────────────────────────────────────────────────────────

function songJson(s: SongRow) {
  return {
    id: s.id,
    parent: s.album_id,
    isDir: false,
    type: "music",
    isVideo: false,
    title: s.title,
    album: s.album,
    albumId: s.album_id,
    artist: s.artist,
    artistId: s.artist_id,
    track: s.track ?? undefined,
    discNumber: s.disc ?? undefined,
    year: s.year ?? undefined,
    genre: s.genre ?? undefined,
    coverArt: s.cover_key ? s.id : undefined,
    size: s.size,
    contentType: s.content_type,
    suffix: s.suffix,
    duration: s.duration,
    bitRate: s.bit_rate ?? undefined,
    samplingRate: s.sample_rate ?? undefined,
    bitDepth: s.bit_depth ?? undefined,
    created: s.created_at,
  };
}

interface AlbumRow {
  album_id: string;
  album: string;
  album_artist: string;
  artist_id: string;
  song_count: number;
  duration: number;
  year: number | null;
  genre: string | null;
  created: string;
  cover: string | null;
}

const albumJson = (a: AlbumRow) => ({
  id: a.album_id,
  name: a.album,
  artist: a.album_artist,
  artistId: a.artist_id,
  coverArt: a.cover ?? undefined,
  songCount: Number(a.song_count),
  duration: Number(a.duration),
  year: a.year ?? undefined,
  genre: a.genre ?? undefined,
  created: a.created,
});

const ALBUM_COLUMNS = `album_id, min(album) as album, min(album_artist) as album_artist, min(artist_id) as artist_id,
  count(*) as song_count, sum(duration) as duration, max(year) as year, min(genre) as genre, max(created_at) as created,
  (select s2.id from cloud_songs s2 where s2.user_id = s.user_id and s2.album_id = s.album_id and s2.cover_key is not null limit 1) as cover`;

const ORDERS: Record<string, string> = {
  alphabeticalByName: "lower(min(album)), album_id",
  alphabeticalByArtist: "lower(min(album_artist)), lower(min(album)), album_id",
  newest: "max(created_at) desc, album_id",
  recent: "max(created_at) desc, album_id",
  frequent: "max(created_at) desc, album_id",
  highest: "max(created_at) desc, album_id",
  starred: "max(created_at) desc, album_id",
  byYear: "max(year), lower(min(album))",
  random: "random()",
};

const clampInt = (v: string | null, def: number, max: number) => {
  const n = Number.parseInt(v ?? "", 10);
  return Number.isFinite(n) && n >= 0 ? Math.min(n, max) : def;
};

const likeOf = (q: string) => `%${q.replace(/[\\%_]/g, (c) => "\\" + c)}%`;

// ── Streaming ────────────────────────────────────────────────────────────────

/** "bytes=a-b", "bytes=a-" or "bytes=-n" within [size]; null for none or one R2 cannot serve. */
export function parseRange(header: string | null, size: number): { offset: number; length: number } | null {
  const m = /^bytes=(\d*)-(\d*)$/.exec((header ?? "").trim());
  if (!m || (m[1] === "" && m[2] === "")) return null;
  if (m[1] === "") {
    const n = Math.min(Number(m[2]), size);
    return n > 0 ? { offset: size - n, length: n } : null;
  }
  const start = Number(m[1]);
  const end = m[2] === "" ? size - 1 : Math.min(Number(m[2]), size - 1);
  if (start >= size || end < start) return null;
  return { offset: start, length: end - start + 1 };
}

/** [song]'s file, whole or the range asked for; also the web player's (app.ts, /play). */
export async function streamSong(bucket: Bucket, song: SongRow, rangeHeader: string | null, download: boolean): Promise<Response> {
  const range = parseRange(rangeHeader, song.size);
  if (rangeHeader && !range) {
    return new Response(null, { status: 416, headers: { "content-range": `bytes */${song.size}` } });
  }
  const object = await bucket.get(song.key, range ? { range } : undefined);
  if (!object) return failed(70, "The file is missing");
  const headers = new Headers({
    "content-type": song.content_type,
    "accept-ranges": "bytes",
    "cache-control": "private, max-age=3600",
  });
  if (download) headers.set("content-disposition", `attachment; filename*=UTF-8''${encodeURIComponent(`${song.title}.${song.suffix}`)}`);
  if (range) {
    headers.set("content-range", `bytes ${range.offset}-${range.offset + range.length - 1}/${song.size}`);
    headers.set("content-length", String(range.length));
    return new Response(object.body, { status: 206, headers });
  }
  headers.set("content-length", String(song.size));
  return new Response(object.body, { status: 200, headers });
}

// ── The handler ──────────────────────────────────────────────────────────────

/** Answers /rest/<method>[.view] for [request]. */
export async function subsonic(request: Request, db: Db, bucket: Bucket | null, now: Date): Promise<Response> {
  const url = new URL(request.url);
  const method = url.pathname.replace(/^\/rest\//, "").replace(/\.view$/, "");
  // GET or POST form: parameters may come in either.
  const params = new URLSearchParams(url.search);
  if (request.method === "POST" && (request.headers.get("content-type") ?? "").includes("application/x-www-form-urlencoded")) {
    for (const [k, v] of new URLSearchParams(await request.text())) params.append(k, v);
  }
  if (!params.get("u")) return failed(10, "Required parameter is missing: u");
  const user = await signIn(db, params);
  if (!user) return failed(40, "Wrong username or password");
  if (method === "ping") return envelope({});
  if (method === "getLicense") return envelope({ license: { valid: true } });

  const { access } = accessOf(await loadAccount(db, user), now);
  if (access === "none") return failed(50, "No active Tryptify Cloud plan");

  switch (method) {
    case "getMusicFolders":
      return envelope({ musicFolders: { musicFolder: [{ id: 1, name: "Tryptify Cloud" }] } });

    case "getAlbumList2": {
      const order = ORDERS[params.get("type") ?? ""] ?? ORDERS.alphabeticalByName;
      const size = clampInt(params.get("size"), 10, 500);
      const offset = clampInt(params.get("offset"), 0, 1_000_000);
      const rows = await db
        .prepare(`select ${ALBUM_COLUMNS} from cloud_songs s where user_id = ?1 group by album_id order by ${order} limit ?2 offset ?3`)
        .bind(user, size, offset)
        .all<AlbumRow>();
      return envelope({ albumList2: { album: rows.results.map(albumJson) } });
    }

    case "getAlbum": {
      const id = params.get("id") ?? "";
      const album = await db
        .prepare(`select ${ALBUM_COLUMNS} from cloud_songs s where user_id = ?1 and album_id = ?2 group by album_id`)
        .bind(user, id)
        .first<AlbumRow>();
      if (!album) return failed(70, "Album not found");
      const songs = await db
        .prepare("select * from cloud_songs where user_id = ?1 and album_id = ?2 order by coalesce(disc, 1), coalesce(track, 0), lower(title)")
        .bind(user, id)
        .all<SongRow>();
      return envelope({ album: { ...albumJson(album), song: songs.results.map(songJson) } });
    }

    case "getArtists": {
      const rows = await db
        .prepare(
          `select artist_id as id, min(album_artist) as name, count(distinct album_id) as album_count
             from cloud_songs where user_id = ?1 group by artist_id order by lower(min(album_artist))`,
        )
        .bind(user)
        .all<{ id: string; name: string; album_count: number }>();
      const index = new Map<string, unknown[]>();
      for (const a of rows.results) {
        const first = a.name.charAt(0).toUpperCase();
        const letter = /\p{L}/u.test(first) ? first : "#";
        if (!index.has(letter)) index.set(letter, []);
        index.get(letter)!.push({ id: a.id, name: a.name, albumCount: Number(a.album_count) });
      }
      return envelope({ artists: { ignoredArticles: "", index: [...index].map(([name, artist]) => ({ name, artist })) } });
    }

    case "getArtist": {
      const id = params.get("id") ?? "";
      const albums = await db
        .prepare(`select ${ALBUM_COLUMNS} from cloud_songs s where user_id = ?1 and artist_id = ?2 group by album_id order by max(year), lower(min(album))`)
        .bind(user, id)
        .all<AlbumRow>();
      if (albums.results.length === 0) return failed(70, "Artist not found");
      return envelope({
        artist: { id, name: albums.results[0].album_artist, albumCount: albums.results.length, album: albums.results.map(albumJson) },
      });
    }

    case "getSong": {
      const song = await getSong(db, user, params.get("id") ?? "");
      return song ? envelope({ song: songJson(song) }) : failed(70, "Song not found");
    }

    case "search3": {
      const q = (params.get("query") ?? "").replace(/^"|"$/g, "").trim();
      const like = likeOf(q);
      const songCount = clampInt(params.get("songCount"), 20, 500);
      const albumCount = clampInt(params.get("albumCount"), 20, 500);
      const artistCount = clampInt(params.get("artistCount"), 20, 500);
      const songs = await db
        .prepare(
          `select * from cloud_songs where user_id = ?1
             and (?2 = '%%' or title like ?2 escape '\\' or artist like ?2 escape '\\' or album like ?2 escape '\\')
           order by lower(title) limit ?3 offset ?4`,
        )
        .bind(user, like, songCount, clampInt(params.get("songOffset"), 0, 1_000_000))
        .all<SongRow>();
      const albums = albumCount
        ? await db
            .prepare(`select ${ALBUM_COLUMNS} from cloud_songs s where user_id = ?1 and (?2 = '%%' or album like ?2 escape '\\') group by album_id order by lower(min(album)) limit ?3`)
            .bind(user, like, albumCount)
            .all<AlbumRow>()
        : { results: [] as AlbumRow[] };
      const artists = artistCount
        ? await db
            .prepare(
              `select artist_id as id, min(album_artist) as name, count(distinct album_id) as album_count from cloud_songs
                where user_id = ?1 and (?2 = '%%' or album_artist like ?2 escape '\\') group by artist_id order by lower(min(album_artist)) limit ?3`,
            )
            .bind(user, like, artistCount)
            .all<{ id: string; name: string; album_count: number }>()
        : { results: [] as { id: string; name: string; album_count: number }[] };
      return envelope({
        searchResult3: {
          song: songs.results.map(songJson),
          album: albums.results.map(albumJson),
          artist: artists.results.map((a) => ({ id: a.id, name: a.name, albumCount: Number(a.album_count) })),
        },
      });
    }

    case "getRandomSongs": {
      const rows = await db
        .prepare("select * from cloud_songs where user_id = ?1 order by random() limit ?2")
        .bind(user, clampInt(params.get("size"), 10, 500))
        .all<SongRow>();
      return envelope({ randomSongs: { song: rows.results.map(songJson) } });
    }

    case "stream":
    case "download": {
      if (!bucket) return failed(0, "Storage is not set up");
      const song = await getSong(db, user, params.get("id") ?? "");
      if (!song) return failed(70, "Song not found");
      return streamSong(bucket, song, request.headers.get("range"), method === "download");
    }

    case "getCoverArt": {
      if (!bucket) return new Response(null, { status: 404 });
      const id = params.get("id") ?? "";
      const row = id.startsWith("al-")
        ? await db.prepare("select cover_key from cloud_songs where user_id = ?1 and album_id = ?2 and cover_key is not null limit 1").bind(user, id).first<{ cover_key: string }>()
        : await db.prepare("select cover_key from cloud_songs where user_id = ?1 and id = ?2 and cover_key is not null").bind(user, id).first<{ cover_key: string }>();
      const object = row ? await bucket.get(row.cover_key) : null;
      if (!object) return new Response(null, { status: 404 });
      return new Response(object.body, {
        headers: {
          "content-type": object.httpMetadata?.contentType ?? "image/jpeg",
          "cache-control": "private, max-age=86400",
          "x-content-type-options": "nosniff",
        },
      });
    }

    default:
      return failed(0, `Not supported: ${method}`);
  }
}

/** A new random password for one device: 24 random bytes, hex. */
export function newDevicePassword(): string {
  return hex(crypto.getRandomValues(new Uint8Array(24)));
}

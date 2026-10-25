// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The listener's music: uploads into R2, the songs table, and the quota.
//
// The quota is the owner's guarantee in storage terms: what a person stores
// never exceeds what their plan pays for. So an upload's declared size is
// reserved from its first request (used + reserved + this ≤ quota, checked
// and reserved in one statement), the finished file must be exactly that
// size, and abandoned uploads are aborted by the daily cleanup. Covers count
// too.

import { accessOf, type Account } from "./rules";
import { changesOf, loadAccount, type Db } from "./store";

/** The part of an R2 bucket binding this uses (so a test can stand in). */
export interface Bucket {
  put(key: string, value: ArrayBuffer | Uint8Array | ReadableStream | string, options?: { httpMetadata?: { contentType?: string } }): Promise<{ size: number } | null>;
  get(key: string, options?: { range?: { offset: number; length?: number } | { suffix: number } }): Promise<StoredObject | null>;
  head(key: string): Promise<{ size: number } | null>;
  delete(keys: string | string[]): Promise<void>;
  list(options?: { prefix?: string; cursor?: string; limit?: number }): Promise<{ objects: { key: string }[]; truncated: boolean; cursor?: string }>;
  createMultipartUpload(key: string, options?: { httpMetadata?: { contentType?: string } }): Promise<MultipartUpload>;
  resumeMultipartUpload(key: string, uploadId: string): MultipartUpload;
}
export interface StoredObject {
  body: ReadableStream;
  size: number;
  httpMetadata?: { contentType?: string };
}
export interface MultipartUpload {
  key: string;
  uploadId: string;
  uploadPart(partNumber: number, value: ReadableStream | ArrayBuffer | Uint8Array): Promise<{ partNumber: number; etag: string }>;
  complete(parts: { partNumber: number; etag: string }[]): Promise<{ size: number }>;
  abort(): Promise<void>;
}

/** Under Cloudflare's 100 MB request body limit, and above R2's 5 MiB part minimum. */
export const PART_SIZE = 50 * 1024 * 1024;
/** One song file, at most. A 24/192 FLAC album side is far below it. */
export const MAX_FILE_BYTES = 2 * 1024 * 1024 * 1024;
export const MAX_COVER_BYTES = 1024 * 1024;
/** Uploads a person may have open at once. */
export const MAX_OPEN_UPLOADS = 6;

const AUDIO = new Map<string, string>([
  ["flac", "audio/flac"], ["mp3", "audio/mpeg"], ["m4a", "audio/mp4"], ["mp4", "audio/mp4"], ["aac", "audio/aac"],
  ["alac", "audio/mp4"], ["ogg", "audio/ogg"], ["oga", "audio/ogg"], ["opus", "audio/ogg"], ["wav", "audio/wav"],
  ["aif", "audio/aiff"], ["aiff", "audio/aiff"], ["ape", "audio/x-ape"], ["wma", "audio/x-ms-wma"],
]);

export interface SongMeta {
  title: string;
  artist: string;
  album: string;
  albumArtist: string;
  track: number | null;
  disc: number | null;
  year: number | null;
  genre: string | null;
  duration: number;
  bitRate: number | null;
  sampleRate: number | null;
  bitDepth: number | null;
}

const text = (v: unknown, max: number): string | null => {
  if (typeof v !== "string") return null;
  const t = v.replace(/[\u0000-\u001f]/g, " ").trim();
  return t ? t.slice(0, max) : null;
};
const int = (v: unknown, max: number): number | null =>
  typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= max ? v : null;

/** The tags as the app sent them, cleaned; null when there is not even a title. */
export function cleanMeta(raw: any): SongMeta | null {
  const title = text(raw?.title, 300);
  if (!title) return null;
  const artist = text(raw?.artist, 300) ?? "Unknown Artist";
  return {
    title,
    artist,
    album: text(raw?.album, 300) ?? "Unknown Album",
    albumArtist: text(raw?.albumArtist, 300) ?? artist,
    track: int(raw?.track, 9999),
    disc: int(raw?.disc, 999),
    year: int(raw?.year, 9999),
    genre: text(raw?.genre, 100),
    duration: int(raw?.duration, 7 * 24 * 3600) ?? 0,
    bitRate: int(raw?.bitRate, 100_000),
    sampleRate: int(raw?.sampleRate, 1_000_000),
    bitDepth: int(raw?.bitDepth, 64),
  };
}

async function shortHash(prefix: string, ...parts: string[]): Promise<string> {
  const data = new TextEncoder().encode(parts.map((p) => p.trim().toLowerCase()).join("\u0000"));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  return prefix + Array.from(digest.slice(0, 8), (b) => b.toString(16).padStart(2, "0")).join("");
}
export const albumIdOf = (albumArtist: string, album: string) => shortHash("al-", albumArtist, album);
export const artistIdOf = (albumArtist: string) => shortHash("ar-", albumArtist);

export function randomId(prefix: string): string {
  const b = crypto.getRandomValues(new Uint8Array(12));
  return prefix + Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
}

const folder = (userId: string) => `u/${userId}/`;

export class LibraryError extends Error {
  constructor(public status: number, public code: string) {
    super(code);
  }
}

async function reservedBytes(db: Db, userId: string): Promise<number> {
  const row = await db.prepare("select coalesce(sum(size), 0) as n from cloud_uploads where user_id = ?1").bind(userId).first<{ n: number }>();
  return Number(row?.n ?? 0);
}

/** How much a person stores, has reserved, and may store. */
export async function usage(db: Db, userId: string, now: Date) {
  const account = await loadAccount(db, userId);
  const { access, quotaBytes } = accessOf(account, now);
  return { account, access, quotaBytes, usedBytes: account.usedBytes, reservedBytes: await reservedBytes(db, userId) };
}

/** Opens an upload: checks the file, reserves its size, starts R2's multipart upload. */
export async function startUpload(
  db: Db,
  bucket: Bucket,
  userId: string,
  body: any,
  now: Date,
): Promise<{ id: string; partSize: number; parts: number }> {
  const size = body?.size;
  if (typeof size !== "number" || !Number.isInteger(size) || size <= 0) throw new LibraryError(400, "bad_request");
  if (size > MAX_FILE_BYTES) throw new LibraryError(413, "file_too_big");
  const suffix = typeof body?.suffix === "string" ? body.suffix.toLowerCase().replace(/^\./, "") : "";
  const contentType = AUDIO.get(suffix);
  if (!contentType) throw new LibraryError(415, "not_audio");
  const meta = cleanMeta(body?.meta);
  if (!meta) throw new LibraryError(400, "bad_request");

  const { access, quotaBytes } = accessOf(await loadAccount(db, userId), now);
  if (access === "none") throw new LibraryError(402, "no_plan");
  if (access !== "full") throw new LibraryError(409, "read_only");

  const id = randomId("so-");
  const key = `${folder(userId)}${id}.${suffix}`;
  const upload = await bucket.createMultipartUpload(key, { httpMetadata: { contentType } });
  // Reserve in one statement, so two uploads started at once cannot both fit
  // in the same remaining space.
  await db
    .prepare(
      `insert into cloud_uploads (id, user_id, key, r2_upload, size, meta, created_at)
       select ?1, ?2, ?3, ?4, ?5, ?6, ?7
        where coalesce((select used_bytes from cloud_accounts where user_id = ?2), 0)
              + (select coalesce(sum(size), 0) from cloud_uploads where user_id = ?2) + ?5 <= ?8
          and (select count(*) from cloud_uploads where user_id = ?2) < ?9`,
    )
    .bind(id, userId, key, upload.uploadId, size, JSON.stringify({ ...meta, suffix, contentType }), now.toISOString(), quotaBytes, MAX_OPEN_UPLOADS)
    .run();
  const reserved = await db.prepare("select 1 as ok from cloud_uploads where id = ?1").bind(id).first();
  if (!reserved) {
    await upload.abort().catch(() => {});
    const open = await db.prepare("select count(*) as n from cloud_uploads where user_id = ?1").bind(userId).first<{ n: number }>();
    throw new LibraryError(Number(open?.n ?? 0) >= MAX_OPEN_UPLOADS ? 429 : 413, Number(open?.n ?? 0) >= MAX_OPEN_UPLOADS ? "too_many_uploads" : "over_quota");
  }
  return { id, partSize: PART_SIZE, parts: Math.max(1, Math.ceil(size / PART_SIZE)) };
}

interface UploadRow {
  id: string;
  user_id: string;
  key: string;
  r2_upload: string;
  size: number;
  meta: string;
}

async function openUpload(db: Db, userId: string, id: string): Promise<UploadRow> {
  const row = await db.prepare("select id, user_id, key, r2_upload, size, meta from cloud_uploads where id = ?1 and user_id = ?2").bind(id, userId).first<UploadRow>();
  if (!row) throw new LibraryError(404, "not_found");
  return row;
}

/** One part, exactly PART_SIZE bytes except the last, which is the rest. */
export async function uploadPart(
  db: Db,
  bucket: Bucket,
  userId: string,
  id: string,
  partNumber: number,
  declaredLength: number | null,
  body: ReadableStream | ArrayBuffer | null,
): Promise<{ partNumber: number; etag: string }> {
  const row = await openUpload(db, userId, id);
  const parts = Math.max(1, Math.ceil(row.size / PART_SIZE));
  if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > parts || !body) throw new LibraryError(400, "bad_request");
  const expected = partNumber < parts ? PART_SIZE : row.size - PART_SIZE * (parts - 1);
  if (declaredLength !== expected) throw new LibraryError(400, "bad_part_size");
  return bucket.resumeMultipartUpload(row.key, row.r2_upload).uploadPart(partNumber, body);
}

/** Finishes an upload: the file must be exactly the size reserved; then it is a song. */
export async function completeUpload(
  db: Db,
  bucket: Bucket,
  userId: string,
  id: string,
  parts: unknown,
  now: Date,
): Promise<SongRow> {
  const row = await openUpload(db, userId, id);
  if (!Array.isArray(parts) || parts.length === 0) throw new LibraryError(400, "bad_request");
  const clean = parts.map((p: any) => ({ partNumber: Number(p?.partNumber), etag: String(p?.etag ?? "") }));
  const upload = bucket.resumeMultipartUpload(row.key, row.r2_upload);
  let stored: { size: number };
  try {
    stored = await upload.complete(clean);
  } catch {
    throw new LibraryError(400, "bad_parts");
  }
  if (stored.size !== row.size) {
    // Not what was reserved: neither kept nor counted.
    await bucket.delete(row.key);
    await db.prepare("delete from cloud_uploads where id = ?1").bind(id).run();
    throw new LibraryError(400, "size_mismatch");
  }
  const meta = JSON.parse(row.meta) as SongMeta & { suffix: string; contentType: string };
  const song: SongRow = {
    id,
    user_id: userId,
    key: row.key,
    size: row.size,
    cover_key: null,
    cover_size: 0,
    title: meta.title,
    artist: meta.artist,
    album: meta.album,
    album_artist: meta.albumArtist,
    album_id: await albumIdOf(meta.albumArtist, meta.album),
    artist_id: await artistIdOf(meta.albumArtist),
    track: meta.track,
    disc: meta.disc,
    year: meta.year,
    genre: meta.genre,
    duration: meta.duration,
    suffix: meta.suffix,
    content_type: meta.contentType,
    bit_rate: meta.bitRate,
    sample_rate: meta.sampleRate,
    bit_depth: meta.bitDepth,
    created_at: now.toISOString(),
  };
  // The reservation becomes usage, and the upload a song, together.
  await db.batch([
    db
      .prepare(
        `insert into cloud_songs (id, user_id, key, size, title, artist, album, album_artist, album_id, artist_id,
           track, disc, year, genre, duration, suffix, content_type, bit_rate, sample_rate, bit_depth, created_at)
         values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21)`,
      )
      .bind(song.id, userId, song.key, song.size, song.title, song.artist, song.album, song.album_artist, song.album_id, song.artist_id,
        song.track, song.disc, song.year, song.genre, song.duration, song.suffix, song.content_type, song.bit_rate, song.sample_rate, song.bit_depth, song.created_at),
    db.prepare("update cloud_accounts set used_bytes = used_bytes + ?2 where user_id = ?1").bind(userId, song.size),
    db.prepare("delete from cloud_uploads where id = ?1").bind(id),
  ]);
  return song;
}

/** Gives up an upload and its reservation. */
export async function abortUpload(db: Db, bucket: Bucket, userId: string, id: string): Promise<void> {
  const row = await openUpload(db, userId, id);
  await bucket.resumeMultipartUpload(row.key, row.r2_upload).abort().catch(() => {});
  await db.prepare("delete from cloud_uploads where id = ?1").bind(id).run();
}

const IMAGE_TYPES: [number[], string][] = [
  [[0xff, 0xd8, 0xff], "image/jpeg"],
  [[0x89, 0x50, 0x4e, 0x47], "image/png"],
  [[0x52, 0x49, 0x46, 0x46], "image/webp"],
];

/** Sets a song's cover: an image by its first bytes, at most MAX_COVER_BYTES, inside the quota. */
export async function setCover(db: Db, bucket: Bucket, userId: string, songId: string, image: ArrayBuffer, now: Date): Promise<void> {
  const song = await getSong(db, userId, songId);
  if (!song) throw new LibraryError(404, "not_found");
  const bytes = new Uint8Array(image);
  if (bytes.length === 0 || bytes.length > MAX_COVER_BYTES) throw new LibraryError(413, "cover_too_big");
  let type = IMAGE_TYPES.find(([magic]) => magic.every((b, i) => bytes[i] === b))?.[1];
  // RIFF is any RIFF file (a WAV too): WebP says so in bytes 8 to 11.
  if (type === "image/webp" && String.fromCharCode(...bytes.subarray(8, 12)) !== "WEBP") type = undefined;
  if (!type) throw new LibraryError(415, "not_an_image");
  const { access, quotaBytes } = await usage(db, userId, now);
  if (access !== "full") throw new LibraryError(access === "none" ? 402 : 409, access === "none" ? "no_plan" : "read_only");
  // Counted first, inside the quota (with what is reserved); stored only if it fit.
  const counted = changesOf(
    await db
      .prepare(
        `update cloud_accounts set used_bytes = used_bytes + ?2
          where user_id = ?1
            and used_bytes + (select coalesce(sum(size), 0) from cloud_uploads where user_id = ?1) + ?2 <= ?3`,
      )
      .bind(userId, bytes.length - song.cover_size, quotaBytes)
      .run(),
  );
  if (counted !== 1) throw new LibraryError(413, "over_quota");
  const key = `${folder(userId)}covers/${songId}`;
  await bucket.put(key, bytes, { httpMetadata: { contentType: type } });
  await db.prepare("update cloud_songs set cover_key = ?3, cover_size = ?4 where id = ?1 and user_id = ?2").bind(songId, userId, key, bytes.length).run();
}

/** Deletes a song, its file and cover, and gives the space back. */
export async function deleteSong(db: Db, bucket: Bucket, userId: string, songId: string): Promise<void> {
  const song = await getSong(db, userId, songId);
  if (!song) throw new LibraryError(404, "not_found");
  await bucket.delete([song.key, ...(song.cover_key ? [song.cover_key] : [])]);
  await db.batch([
    db.prepare("delete from cloud_songs where id = ?1 and user_id = ?2").bind(songId, userId),
    db.prepare("update cloud_accounts set used_bytes = max(0, used_bytes - ?2) where user_id = ?1").bind(userId, song.size + song.cover_size),
    // Its star, its plays, and its places in playlists go with it.
    db.prepare("delete from cloud_stars where user_id = ?1 and song_id = ?2").bind(userId, songId),
    db.prepare("delete from cloud_plays where user_id = ?1 and song_id = ?2").bind(userId, songId),
    db.prepare(
      "delete from cloud_playlist_songs where song_id = ?2 and playlist_id in (select id from cloud_playlists where user_id = ?1)",
    ).bind(userId, songId),
  ]);
}

export interface SongRow {
  id: string;
  user_id: string;
  key: string;
  size: number;
  cover_key: string | null;
  cover_size: number;
  title: string;
  artist: string;
  album: string;
  album_artist: string;
  album_id: string;
  artist_id: string;
  track: number | null;
  disc: number | null;
  year: number | null;
  genre: string | null;
  duration: number;
  suffix: string;
  content_type: string;
  bit_rate: number | null;
  sample_rate: number | null;
  bit_depth: number | null;
  created_at: string;
}

export const getSong = (db: Db, userId: string, id: string) =>
  db.prepare("select * from cloud_songs where id = ?1 and user_id = ?2").bind(id, userId).first<SongRow>();

/** Everything of [userId]'s, gone: every object under their folder, every row. */
export async function wipeLibrary(db: Db, bucket: Bucket, userId: string): Promise<number> {
  const uploads = await db.prepare("select key, r2_upload from cloud_uploads where user_id = ?1").bind(userId).all<{ key: string; r2_upload: string }>();
  for (const u of uploads.results) await bucket.resumeMultipartUpload(u.key, u.r2_upload).abort().catch(() => {});
  let removed = 0;
  let cursor: string | undefined;
  do {
    const page = await bucket.list({ prefix: folder(userId), cursor, limit: 1000 });
    if (page.objects.length) await bucket.delete(page.objects.map((o) => o.key));
    removed += page.objects.length;
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  await db.batch([
    db.prepare("delete from cloud_songs where user_id = ?1").bind(userId),
    db.prepare("delete from cloud_uploads where user_id = ?1").bind(userId),
    db.prepare("update cloud_accounts set used_bytes = 0 where user_id = ?1").bind(userId),
    db.prepare("delete from cloud_stars where user_id = ?1").bind(userId),
    db.prepare("delete from cloud_plays where user_id = ?1").bind(userId),
    db.prepare("delete from cloud_playlist_songs where playlist_id in (select id from cloud_playlists where user_id = ?1)").bind(userId),
    db.prepare("delete from cloud_playlists where user_id = ?1").bind(userId),
  ]);
  return removed;
}

export type { Account };

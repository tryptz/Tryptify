// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The HTTP API, built from its dependencies so the Worker and the tests run
// exactly the same routes.

import { Hono } from "hono";
import { cors } from "hono/cors";
import { AuthUnreachable } from "./auth";
import { hmacHex, sameHex, type Billing } from "./creem";
import { accessOf, isPlan, mayStartTrial, PLANS, READ_ONLY_DAYS, TRIAL_DAYS, TRIAL_GB } from "./rules";
import { loadAccount, record, type Db } from "./store";
import {
  abortUpload, completeUpload, deleteSong, getSong, LibraryError, MAX_COVER_BYTES, PART_SIZE, setCover, startUpload, uploadPart, usage,
  type Bucket,
} from "./library";
import { newDevicePassword, streamSong, subsonic } from "./subsonic";

/** Pages allowed to call /v1 from a browser: the site, and a local copy while it is built. */
const WEB_ORIGINS = /^(https:\/\/(www\.)?tryptify\.com|http:\/\/(localhost|127\.0\.0\.1)(:\d+)?)$/;

/** Songs one library page lists at most. */
const SONG_PAGE = 200;

/** Devices a person may have signed in to the Subsonic API at once; the oldest goes first. */
const MAX_DEVICE_KEYS = 10;

/** How long a web player's song link works: a long FLAC, paused a good while. */
const PLAY_LINK_SECONDS = 6 * 3600;

/** What a /play link signs: the person, the song and when the link stops working. */
const playSignature = (key: string, uid: string, id: string, expires: number) =>
  hmacHex(key, `play:${uid}:${id}:${expires}`);

export interface Deps {
  db: Db | null;
  /** The music bucket (R2). */
  storage?: Bucket | null;
  billing: Billing | null;
  personOf: (token: string) => Promise<string | null>;
  /** Signs the web player's song links (PLAY_KEY); without it the web player is off. */
  playKey?: string | null;
  now?: () => Date;
}

const fail = (status: number, error: string) =>
  new Response(JSON.stringify({ error }), { status, headers: { "content-type": "application/json" } });

export function createApp({ db, storage = null, billing, personOf, playKey = null, now = () => new Date() }: Deps) {
  const app = new Hono<{ Variables: { uid: string } }>();

  const health = (c: { json: (body: unknown) => Response }) =>
    c.json({ ok: true, service: "tryptify-cloud", database: db !== null, storage: storage !== null, billing: billing !== null });
  app.get("/", health);
  app.get("/health", health);

  /** The plans and the trial's terms, for the app's plan picker. No account needed. */
  app.get("/plans", (c) =>
    c.json({
      readOnlyDays: READ_ONLY_DAYS,
      trial: { days: TRIAL_DAYS, storageGb: TRIAL_GB, periods: ["monthly"] },
      plans: Object.entries(PLANS).map(([id, p]) => ({ id, ...p })),
    }),
  );

  // The web app at tryptify.com calls /v1 from the browser. Only its own
  // pages may: the session token is the gate, this keeps other sites out.
  app.use("/v1/*", cors({
    origin: (origin) => (WEB_ORIGINS.test(origin) ? origin : null),
    allowMethods: ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allowHeaders: ["authorization", "content-type"],
    maxAge: 86400,
  }));

  app.use("/v1/*", async (c, next) => {
    if (!db) return fail(503, "not_set_up");
    const header = c.req.header("authorization") ?? "";
    if (!header.startsWith("Bearer ")) return fail(401, "signed_out");
    let uid: string | null;
    try {
      uid = await personOf(header.slice(7));
    } catch (e) {
      if (e instanceof AuthUnreachable) return fail(502, "auth_unreachable");
      throw e;
    }
    if (!uid) return fail(401, "signed_out");
    c.set("uid", uid);
    await next();
  });

  /** The signed-in person's plan, what they may do, and how much they use. */
  app.get("/v1/account", async (c) => {
    const a = await loadAccount(db!, c.get("uid"));
    const { access, quotaBytes, deleteAfter } = accessOf(a, now());
    return c.json({
      plan: a.plan,
      access,
      paidUntil: a.paidUntil?.toISOString() ?? null,
      deleteAfter: deleteAfter?.toISOString() ?? null,
      usedBytes: a.usedBytes,
      quotaBytes,
      trial: a.trial ?? false,
      trialAvailable: mayStartTrial(a),
      unlimited: a.unlimited ?? false,
    });
  });

  /** A checkout link for [plan] and [period], carrying the person's id so the webhook knows who paid. */
  app.post("/v1/checkout", async (c) => {
    if (!billing) return fail(503, "billing_not_set_up");
    const body = (await c.req.json().catch(() => null)) as { plan?: unknown; period?: unknown; trial?: unknown } | null;
    const plan = body?.plan;
    const period = body?.period;
    const trial = body?.trial === true;
    if (!isPlan(plan) || (period !== "monthly" && period !== "yearly")) return fail(400, "bad_request");
    // Trials come with the monthly plans only, and once per account.
    if (trial && period !== "monthly") return fail(400, "bad_request");
    if (trial && !mayStartTrial(await loadAccount(db!, c.get("uid")))) return fail(409, "trial_used");
    const url = await billing.checkoutUrl(plan, period, c.get("uid"), trial);
    return url ? c.json({ url }) : fail(503, "billing_not_set_up");
  });

  /**
   * The payment provider's customer portal for the signed-in person, where
   * they change or cancel their plan. Creem requires that people can cancel
   * from inside the product; this is how the app's "Manage subscription" does.
   */
  app.post("/v1/billing-portal", async (c) => {
    if (!billing) return fail(503, "billing_not_set_up");
    const row = await db!.prepare("select customer_id from cloud_accounts where user_id = ?1")
      .bind(c.get("uid")).first<{ customer_id: string | null }>();
    if (!row?.customer_id) return fail(404, "no_subscription");
    const url = await billing.portalUrl(row.customer_id);
    return url ? c.json({ url }) : fail(502, "billing_unreachable");
  });

  /** The payment provider's webhook. Answers 2xx only once the event is safely recorded. */
  app.post("/webhooks/billing", async (c) => {
    if (!db || !billing) return fail(503, "not_set_up");
    const raw = await c.req.text();
    const events = await billing.parseWebhook(c.req.raw.headers, raw);
    if (events === null) return fail(401, "bad_signature");
    for (const e of events) await record(db, billing.name, e, now);
    return c.json({ ok: true, applied: events.length });
  });

  // ── The library ──────────────────────────────────────────────────────────

  const libraryError = (e: unknown) => {
    if (e instanceof LibraryError) return fail(e.status, e.code);
    throw e;
  };
  const needsStorage = () => (storage ? null : fail(503, "storage_not_set_up"));

  /** What is stored, reserved and allowed. */
  app.get("/v1/library", async (c) => {
    const u = await usage(db!, c.get("uid"), now());
    const songs = await db!.prepare("select count(*) as n from cloud_songs where user_id = ?1").bind(c.get("uid")).first<{ n: number }>();
    return c.json({
      access: u.access,
      usedBytes: u.usedBytes,
      reservedBytes: u.reservedBytes,
      quotaBytes: u.quotaBytes,
      songs: Number(songs?.n ?? 0),
      partSize: PART_SIZE,
      maxCoverBytes: MAX_COVER_BYTES,
    });
  });

  /** Starts an upload: {size, suffix, meta} → {id, partSize, parts}. Reserves the size. */
  app.post("/v1/uploads", async (c) => {
    const missing = needsStorage();
    if (missing) return missing;
    const body = await c.req.json().catch(() => null);
    try {
      return c.json(await startUpload(db!, storage!, c.get("uid"), body, now()), 201);
    } catch (e) {
      return libraryError(e);
    }
  });

  /** One part of an upload, as the raw body with its Content-Length. */
  app.put("/v1/uploads/:id/parts/:n", async (c) => {
    const missing = needsStorage();
    if (missing) return missing;
    const length = Number(c.req.header("content-length") ?? NaN);
    try {
      const part = await uploadPart(db!, storage!, c.get("uid"), c.req.param("id"), Number(c.req.param("n")),
        Number.isFinite(length) ? length : null, c.req.raw.body);
      return c.json(part);
    } catch (e) {
      return libraryError(e);
    }
  });

  /** Finishes an upload: {parts: [{partNumber, etag}]} → the new song. */
  app.post("/v1/uploads/:id/complete", async (c) => {
    const missing = needsStorage();
    if (missing) return missing;
    const body = (await c.req.json().catch(() => null)) as { parts?: unknown } | null;
    try {
      const song = await completeUpload(db!, storage!, c.get("uid"), c.req.param("id"), body?.parts, now());
      return c.json({ id: song.id, albumId: song.album_id, artistId: song.artist_id, size: song.size }, 201);
    } catch (e) {
      return libraryError(e);
    }
  });

  app.delete("/v1/uploads/:id", async (c) => {
    const missing = needsStorage();
    if (missing) return missing;
    try {
      await abortUpload(db!, storage!, c.get("uid"), c.req.param("id"));
      return c.body(null, 204);
    } catch (e) {
      return libraryError(e);
    }
  });

  /** A song's cover: the image as the body (JPEG, PNG or WebP, at most 1 MB). */
  app.put("/v1/songs/:id/cover", async (c) => {
    const missing = needsStorage();
    if (missing) return missing;
    const length = Number(c.req.header("content-length") ?? "0");
    if (length > MAX_COVER_BYTES) return fail(413, "cover_too_big");
    try {
      await setCover(db!, storage!, c.get("uid"), c.req.param("id"), await c.req.arrayBuffer(), now());
      return c.body(null, 204);
    } catch (e) {
      return libraryError(e);
    }
  });

  /** The library as a list, newest first: ?q= searches title, artist and album; ?offset= pages. */
  app.get("/v1/songs", async (c) => {
    const uid = c.get("uid");
    const q = (c.req.query("q") ?? "").trim().slice(0, 100);
    const offset = Math.max(0, Math.min(1_000_000, Number(c.req.query("offset") ?? 0) | 0));
    const like = `%${q.replace(/[\\%_]/g, (m) => "\\" + m)}%`;
    const where = q
      ? "user_id = ?1 and (title like ?2 escape '\\' or artist like ?2 escape '\\' or album like ?2 escape '\\')"
      : "user_id = ?1 and ?2 = ?2";
    const rows = await db!.prepare(
      `select id, title, artist, album, album_artist, track, disc, year, genre, duration, suffix, size,
              bit_rate, sample_rate, bit_depth, cover_key, created_at
         from cloud_songs where ${where} order by created_at desc, id limit ?3 offset ?4`,
    ).bind(uid, like, SONG_PAGE, offset).all<Record<string, any>>();
    const total = await db!.prepare(`select count(*) as n from cloud_songs where ${where}`).bind(uid, like).first<{ n: number }>();
    return c.json({
      total: Number(total?.n ?? 0),
      offset,
      songs: rows.results.map((r) => ({
        id: r.id, title: r.title, artist: r.artist, album: r.album, albumArtist: r.album_artist,
        track: r.track, disc: r.disc, year: r.year, genre: r.genre, duration: r.duration, suffix: r.suffix,
        size: Number(r.size), bitRate: r.bit_rate, sampleRate: r.sample_rate, bitDepth: r.bit_depth,
        hasCover: r.cover_key !== null, createdAt: r.created_at,
      })),
    });
  });

  /** A song's cover, for the web app's list (the Subsonic API serves the same to players). */
  app.get("/v1/songs/:id/cover", async (c) => {
    if (!storage) return fail(404, "not_found");
    const row = await db!.prepare("select cover_key from cloud_songs where user_id = ?1 and id = ?2 and cover_key is not null")
      .bind(c.get("uid"), c.req.param("id")).first<{ cover_key: string }>();
    const object = row ? await storage.get(row.cover_key) : null;
    if (!object) return fail(404, "not_found");
    return new Response(object.body, {
      headers: { "content-type": object.httpMetadata?.contentType ?? "image/jpeg", "cache-control": "private, max-age=86400" },
    });
  });

  /**
   * A link the web player's audio element can play. <audio> sends no
   * Authorization header, so the song is fetched from /play/:id with a
   * signature that names the person, the song and when the link stops
   * working. The same plan check as the Subsonic API: read-only still plays.
   */
  app.get("/v1/songs/:id/play", async (c) => {
    if (!storage || !playKey) return fail(503, "not_set_up");
    const uid = c.get("uid");
    const id = c.req.param("id");
    if (accessOf(await loadAccount(db!, uid), now()).access === "none") return fail(402, "no_plan");
    const song = await getSong(db!, uid, id);
    if (!song) return fail(404, "not_found");
    const expires = Math.floor(now().getTime() / 1000) + PLAY_LINK_SECONDS;
    const signature = await playSignature(playKey, uid, id, expires);
    const url = `${new URL(c.req.url).origin}/play/${encodeURIComponent(id)}?u=${uid}&e=${expires}&s=${signature}`;
    return c.json({ url, expiresAt: new Date(expires * 1000).toISOString(), contentType: song.content_type, suffix: song.suffix });
  });

  app.delete("/v1/songs/:id", async (c) => {
    const missing = needsStorage();
    if (missing) return missing;
    try {
      await deleteSong(db!, storage!, c.get("uid"), c.req.param("id"));
      return c.body(null, 204);
    } catch (e) {
      return libraryError(e);
    }
  });

  // ── The Subsonic API ─────────────────────────────────────────────────────

  /**
   * A password for this device's Subsonic sign-in: {server, username,
   * password}. Random, revocable, and never the Tryptify password.
   */
  app.post("/v1/subsonic-keys", async (c) => {
    const uid = c.get("uid");
    const body = (await c.req.json().catch(() => null)) as { label?: unknown } | null;
    const label = typeof body?.label === "string" ? body.label.slice(0, 60) : null;
    const password = newDevicePassword();
    const id = crypto.randomUUID();
    await db!.batch([
      db!.prepare("insert into cloud_subsonic_keys (id, user_id, password, label, created_at) values (?1, ?2, ?3, ?4, ?5)")
        .bind(id, uid, password, label, now().toISOString()),
      db!.prepare(
        `delete from cloud_subsonic_keys where user_id = ?1 and id not in
           (select id from cloud_subsonic_keys where user_id = ?1 order by created_at desc, id limit ?2)`,
      ).bind(uid, MAX_DEVICE_KEYS),
    ]);
    return c.json({ server: new URL(c.req.url).origin, username: uid, password }, 201);
  });

  /** Where to point a Subsonic app, and the devices signed in now (never their passwords). */
  app.get("/v1/subsonic-keys", async (c) => {
    const uid = c.get("uid");
    const rows = await db!.prepare("select id, label, created_at from cloud_subsonic_keys where user_id = ?1 order by created_at desc, id")
      .bind(uid).all<{ id: string; label: string | null; created_at: string }>();
    return c.json({
      server: new URL(c.req.url).origin,
      username: uid,
      devices: rows.results.map((r) => ({ id: r.id, label: r.label, createdAt: r.created_at })),
    });
  });

  /** Signs one device out of the Subsonic API. */
  app.delete("/v1/subsonic-keys/:id", async (c) => {
    await db!.prepare("delete from cloud_subsonic_keys where user_id = ?1 and id = ?2").bind(c.get("uid"), c.req.param("id")).run();
    return c.body(null, 204);
  });

  /** Signs every device out of the Subsonic API. */
  app.delete("/v1/subsonic-keys", async (c) => {
    await db!.prepare("delete from cloud_subsonic_keys where user_id = ?1").bind(c.get("uid")).run();
    return c.body(null, 204);
  });

  // ── The web player's songs ────────────────────────────────────────────

  /**
   * A song for the web player, by a link from /v1/songs/:id/play: whole or a
   * range, for seeking. The site's own pages get CORS, so the player can read
   * the audio for its spectrum; the signature is the gate, not the origin.
   */
  app.on(["GET", "HEAD"], "/play/:id", async (c) => {
    const origin = c.req.header("origin") ?? "";
    const cors = (res: Response) => {
      if (WEB_ORIGINS.test(origin)) {
        res.headers.set("access-control-allow-origin", origin);
        res.headers.set("access-control-expose-headers", "content-range, content-length, accept-ranges");
        res.headers.set("vary", "origin");
      }
      return res;
    };
    if (!db || !storage || !playKey) return cors(fail(503, "not_set_up"));
    const id = c.req.param("id");
    const uid = c.req.query("u") ?? "";
    const expires = Number(c.req.query("e"));
    const given = (c.req.query("s") ?? "").toLowerCase();
    if (!Number.isInteger(expires) || !sameHex(given, await playSignature(playKey, uid, id, expires))) {
      return cors(fail(403, "bad_link"));
    }
    if (expires * 1000 < now().getTime()) return cors(fail(403, "link_expired"));
    if (accessOf(await loadAccount(db, uid), now()).access === "none") return cors(fail(402, "no_plan"));
    const song = await getSong(db, uid, id);
    if (!song) return cors(fail(404, "not_found"));
    return cors(await streamSong(storage, song, c.req.header("range") ?? null, false));
  });

  app.all("/rest/*", async (c) => {
    if (!db) return fail(503, "not_set_up");
    return subsonic(c.req.raw, db, storage, now());
  });

  return app;
}

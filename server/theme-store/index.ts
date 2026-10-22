// Tryptify Theme Store — the API behind the app's Theme Store.
//
// Runs as a Railway Function (one file, Bun) next to a Railway Postgres. The
// app signs in with Supabase as everywhere else; every request here carries
// that session's access token. A token signed with one of the project's
// asymmetric keys is checked here against its public keys (JWKS); one signed
// with the project's shared secret is checked by asking Supabase Auth who it
// belongs to. Either way this service holds no Supabase secret at all.
//
// Everything needs an account. Packs are listed or unlisted: an unlisted pack
// stays out of the store and opens by its share code (and so by its id, which
// only the code or the author's own list hands out). Likes and downloads are
// counted here, once per person, never taken from the client.
//
// Variables: DATABASE_URL (the Postgres reference). SUPABASE_URL and
// SUPABASE_ANON_KEY default to the app's own project and its public key.

import { Hono } from "hono@4";
import { createRemoteJWKSet, decodeJwt, decodeProtectedHeader, jwtVerify } from "jose@5";
import { SQL } from "bun";

const SUPABASE_URL = (Bun.env.SUPABASE_URL ?? "https://lvzorvfhhopillzlwgau.supabase.co").replace(/\/+$/, "");
// The same public key the app ships with: it identifies the project, it grants nothing.
const SUPABASE_ANON_KEY =
  Bun.env.SUPABASE_ANON_KEY ??
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx2em9ydmZoaG9waWxsemx3Z2F1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQzNTc0NDQsImV4cCI6MjA4OTkzMzQ0NH0.Y_TN9r19WS96HyVZSQeNa0TyOqyBGuqFARaj8-7Ylow";
const ISSUER = `${SUPABASE_URL}/auth/v1`;
const JWKS = createRemoteJWKSet(new URL(`${ISSUER}/.well-known/jwks.json`));

const MAX_PACK_BYTES = 64 * 1024;
// A request carries the pack as a JSON string, which escapes every quote in
// it: up to twice the pack, plus the listing fields beside it. A picture is
// sent as itself and is the larger of the two.
const MAX_BODY_BYTES = 192 * 1024;
// A pack's store picture: a phone-shaped WebP the app draws with its own
// renderers, about 540 by 1140.
const MAX_SNAPSHOT_BYTES = 160 * 1024;
const MAX_PACKS_PER_PERSON = 50;
const PAGE = 20;
const PARTS = new Set(["COLOURS", "LYRICS", "PLAYER_GLASS", "PANELS", "FONT", "VISUALIZER", "MAPS", "LINKS", "CRT"]);
// Room for a few more parts without another migration.
const MAX_PARTS = 16;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// No 0/O or 1/I: a code is read off one screen and typed into another.
const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

const db = Bun.env.DATABASE_URL ? new SQL(Bun.env.DATABASE_URL) : null;

const SCHEMA = [
  `create table if not exists theme_packs (
     id           uuid        primary key default gen_random_uuid(),
     user_id      uuid        not null,
     author_name  text        not null default '' check (char_length(author_name) <= 40),
     name         text        not null check (char_length(btrim(name)) between 1 and 40),
     description  text        not null default '' check (char_length(description) <= 280),
     parts        text[]      not null default '{}' check (cardinality(parts) <= ${MAX_PARTS}),
     accent       integer     not null default 0,
     background   integer     not null default 0,
     pack         text        not null check (octet_length(pack) <= ${MAX_PACK_BYTES}),
     listed       boolean     not null default true,
     share_code   text        not null unique,
     likes        integer     not null default 0,
     downloads    integer     not null default 0,
     created_at   timestamptz not null default now(),
     updated_at   timestamptz not null default now()
   )`,
  // A table made when there were eight parts held them to eight; the CRT is
  // the ninth. Dropped and made again, so this runs on every start alike.
  `alter table theme_packs drop constraint if exists theme_packs_parts_check`,
  `alter table theme_packs add constraint theme_packs_parts_check check (cardinality(parts) <= ${MAX_PARTS})`,
  // A short hash of the pack's picture, or null: the app keeps pictures by it.
  `alter table theme_packs add column if not exists snapshot text`,
  `create index if not exists theme_packs_popular on theme_packs (likes desc, downloads desc, created_at desc) where listed`,
  `create index if not exists theme_packs_newest on theme_packs (created_at desc) where listed`,
  `create index if not exists theme_packs_author on theme_packs (user_id, created_at desc)`,
  `create table if not exists theme_likes (
     pack_id    uuid        not null references theme_packs (id) on delete cascade,
     user_id    uuid        not null,
     created_at timestamptz not null default now(),
     primary key (pack_id, user_id)
   )`,
  `create index if not exists theme_likes_user on theme_likes (user_id)`,
  `create table if not exists theme_downloads (
     pack_id    uuid        not null references theme_packs (id) on delete cascade,
     user_id    uuid        not null,
     created_at timestamptz not null default now(),
     primary key (pack_id, user_id)
   )`,
  `create table if not exists theme_snapshots (
     pack_id    uuid        primary key references theme_packs (id) on delete cascade,
     mime       text        not null,
     image      bytea       not null check (octet_length(image) <= ${MAX_SNAPSHOT_BYTES}),
     created_at timestamptz not null default now()
   )`,
  `create table if not exists theme_reports (
     id         bigserial   primary key,
     pack_id    uuid        not null references theme_packs (id) on delete cascade,
     user_id    uuid        not null,
     reason     text        not null default '' check (char_length(reason) <= 280),
     created_at timestamptz not null default now(),
     unique (pack_id, user_id)
   )`,
];

// A database deployed alongside can still be starting: wait for it a while
// rather than crash-looping, and only then take requests.
if (db) {
  for (let attempt = 1; ; attempt++) {
    try {
      for (const statement of SCHEMA) await db.unsafe(statement);
      break;
    } catch (err) {
      if (attempt >= 30) throw err;
      console.error(`database not ready (${attempt}/30): ${(err as Error).message}`);
      await Bun.sleep(2000);
    }
  }
}

type Vars = { Variables: { uid: string } };
const app = new Hono<Vars>();

const fail = (status: number, error: string) =>
  new Response(JSON.stringify({ error }), { status, headers: { "content-type": "application/json" } });

class TooBig extends Error {}
class AuthUnreachable extends Error {}

// Tokens Supabase Auth has vouched for, by their hash, until they expire (five
// minutes at most), so a screenful of calls costs it one question, not ten.
const vouched = new Map<string, { uid: string; until: number }>();

/** The person [token] belongs to, or null when it is not a live session of this project. */
async function personOf(token: string): Promise<string | null> {
  let alg: string | undefined;
  let exp = 0;
  try {
    alg = decodeProtectedHeader(token).alg;
    exp = Number(decodeJwt(token).exp ?? 0);
  } catch {
    return null;
  }
  if (alg !== "HS256") {
    try {
      const { payload } = await jwtVerify(token, JWKS, { issuer: ISSUER, audience: "authenticated" });
      return typeof payload.sub === "string" && UUID.test(payload.sub) ? payload.sub : null;
    } catch {
      return null;
    }
  }
  // Signed with the project's shared secret, which this service does not hold.
  const key = new Bun.CryptoHasher("sha256").update(token).digest("hex");
  const now = Date.now();
  const hit = vouched.get(key);
  if (hit && hit.until > now) return hit.uid;
  let res: Response;
  try {
    res = await fetch(`${ISSUER}/user`, {
      headers: { apikey: SUPABASE_ANON_KEY, authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    throw new AuthUnreachable();
  }
  if (res.status >= 500) throw new AuthUnreachable();
  if (!res.ok) return null;
  const user = (await res.json().catch(() => null)) as { id?: unknown } | null;
  if (typeof user?.id !== "string" || !UUID.test(user.id)) return null;
  if (vouched.size > 10_000) {
    for (const [k, v] of vouched) if (v.until <= now) vouched.delete(k);
    if (vouched.size > 10_000) vouched.clear();
  }
  vouched.set(key, { uid: user.id, until: Math.min(exp * 1000, now + 5 * 60_000) });
  return user.id;
}

/** The body as JSON, or null when it is not JSON; measured as it arrived, whatever its header said. */
async function body(req: Request): Promise<any> {
  const bytes = await req.arrayBuffer();
  if (bytes.byteLength > MAX_BODY_BYTES) throw new TooBig();
  try {
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }
}

const health = (c: { json: (body: unknown) => Response }) =>
  c.json({ ok: true, store: "tryptify-theme-store", database: db !== null });
app.get("/", health);
app.get("/health", health);

// Every store route: the database, a body no bigger than a pack, and a person.
app.use("/v1/*", async (c, next) => {
  if (!db) return fail(503, "not_set_up");
  const length = Number(c.req.header("content-length") ?? "0");
  if (length > MAX_BODY_BYTES) return fail(413, "too_big");
  const header = c.req.header("authorization") ?? "";
  if (!header.startsWith("Bearer ")) return fail(401, "signed_out");
  let uid: string | null;
  try {
    uid = await personOf(header.slice(7));
  } catch {
    // Supabase Auth did not answer: the session may be fine, so not "signed out".
    return fail(502, "auth_unreachable");
  }
  if (!uid) return fail(401, "signed_out");
  c.set("uid", uid);
  await next();
});

/**
 * A pack as anyone but its author sees it: the author's account id is shown
 * only to the author (the app compares it with its own to say "yours"); to
 * everyone else it is "", so the store does not hand out account ids.
 */
const shown = <T extends { user_id?: unknown }>(row: T, uid: string): T =>
  row.user_id === uid ? row : { ...row, user_id: "" };

/** The store's list: popular, new, or the person's own; optionally by name. */
app.get("/v1/packs", async (c) => {
  const sort = c.req.query("sort") ?? "popular";
  const q = (c.req.query("q") ?? "").replace(/[%_\\]/g, "").trim().slice(0, 40);
  const page = Math.max(0, Math.min(500, Number.parseInt(c.req.query("page") ?? "0", 10) || 0));
  const offset = page * PAGE;
  const uid = c.get("uid");
  const like = `%${q}%`;
  let rows;
  if (sort === "mine") {
    rows = await db!`
      select id, user_id, author_name, name, description, parts, accent, background, listed,
             share_code, likes, downloads, created_at, snapshot
        from theme_packs
       where user_id = ${uid} and (${q} = '' or name ilike ${like})
       order by created_at desc
       limit ${PAGE} offset ${offset}`;
  } else if (sort === "new") {
    rows = await db!`
      select id, user_id, author_name, name, description, parts, accent, background, listed,
             share_code, likes, downloads, created_at, snapshot
        from theme_packs
       where listed and (${q} = '' or name ilike ${like})
       order by created_at desc
       limit ${PAGE} offset ${offset}`;
  } else {
    rows = await db!`
      select id, user_id, author_name, name, description, parts, accent, background, listed,
             share_code, likes, downloads, created_at, snapshot
        from theme_packs
       where listed and (${q} = '' or name ilike ${like})
       order by likes desc, downloads desc, created_at desc
       limit ${PAGE} offset ${offset}`;
  }
  return c.json(rows.map((r: { user_id?: unknown }) => shown(r, uid)));
});

/** One pack, the pack itself included. */
app.get("/v1/packs/:id", async (c) => {
  const id = c.req.param("id");
  if (!UUID.test(id)) return fail(404, "not_found");
  const [row] = await db!`
    select id, user_id, author_name, name, description, parts, accent, background, listed,
           share_code, likes, downloads, created_at, snapshot, pack
      from theme_packs where id = ${id}`;
  return row ? c.json(shown(row, c.get("uid"))) : fail(404, "not_found");
});

/** A pack by the code its author shared, listed or not. */
app.get("/v1/codes/:code", async (c) => {
  const code = c.req.param("code").replace(/[^0-9a-z]/gi, "").toUpperCase().slice(0, 16);
  if (!code) return fail(404, "not_found");
  const [row] = await db!`
    select id, user_id, author_name, name, description, parts, accent, background, listed,
           share_code, likes, downloads, created_at, snapshot
      from theme_packs where share_code = ${code}`;
  return row ? c.json(shown(row, c.get("uid"))) : fail(404, "not_found");
});

app.post("/v1/packs", async (c) => {
  const uid = c.get("uid");
  const input = await body(c.req.raw);
  if (!input || typeof input !== "object") return fail(400, "bad_pack");
  const name = String(input.name ?? "").trim();
  const author = String(input.author_name ?? "").trim().slice(0, 40);
  const description = String(input.description ?? "").trim().slice(0, 280);
  const listed = input.listed !== false;
  const accent = Number.isInteger(input.accent) ? input.accent | 0 : 0;
  const background = Number.isInteger(input.background) ? input.background | 0 : 0;
  const parts: string[] = Array.isArray(input.parts) ? [...new Set(input.parts.map(String))] : [];
  const pack = typeof input.pack === "string" ? input.pack : "";
  if (name.length < 1 || name.length > 40) return fail(400, "bad_pack");
  if (parts.length === 0 || parts.length > MAX_PARTS || !parts.every((p) => PARTS.has(p))) return fail(400, "bad_pack");
  if (new TextEncoder().encode(pack).length > MAX_PACK_BYTES) return fail(400, "bad_pack");
  try {
    // The app leaves defaults out of its JSON, "format" among them, so a pack
    // without one is a pack; one that names another format is not.
    const doc = JSON.parse(pack);
    if (!doc || typeof doc !== "object" || Array.isArray(doc)) return fail(400, "bad_pack");
    if (doc.format !== undefined && doc.format !== "tryptheme") return fail(400, "bad_pack");
  } catch {
    return fail(400, "bad_pack");
  }
  const [{ count }] = await db!`select count(*)::int as count from theme_packs where user_id = ${uid}`;
  if (count >= MAX_PACKS_PER_PERSON) return fail(409, "limit_reached");
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = shareCode();
    const [row] = await db!`
      insert into theme_packs (user_id, author_name, name, description, parts, accent, background, pack, listed, share_code)
      values (${uid}, ${author}, ${name}, ${description}, string_to_array(${parts.join(",")}, ','),
              ${accent}, ${background}, ${pack}, ${listed}, ${code})
      on conflict (share_code) do nothing
      returning id, user_id, author_name, name, description, parts, accent, background, listed,
                share_code, likes, downloads, created_at, snapshot`;
    if (row) return c.json(row, 201);
  }
  return fail(503, "busy");
});

app.patch("/v1/packs/:id", async (c) => {
  const id = c.req.param("id");
  if (!UUID.test(id)) return fail(404, "not_found");
  const input = await body(c.req.raw);
  if (!input || typeof input.listed !== "boolean") return fail(400, "bad_request");
  const [row] = await db!`
    update theme_packs set listed = ${input.listed}, updated_at = now()
     where id = ${id} and user_id = ${c.get("uid")}
    returning id`;
  return row ? c.json({ ok: true }) : fail(404, "not_found");
});

app.delete("/v1/packs/:id", async (c) => {
  const id = c.req.param("id");
  if (!UUID.test(id)) return fail(404, "not_found");
  const [row] = await db!`delete from theme_packs where id = ${id} and user_id = ${c.get("uid")} returning id`;
  return row ? c.json({ ok: true }) : fail(404, "not_found");
});

/** Which of [ids] this person likes. */
app.get("/v1/likes", async (c) => {
  const ids = (c.req.query("ids") ?? "").split(",").filter((s) => UUID.test(s)).slice(0, 100);
  if (ids.length === 0) return c.json({ ids: [] });
  const rows = await db!`
    select pack_id from theme_likes
     where user_id = ${c.get("uid")} and pack_id = any(string_to_array(${ids.join(",")}, ',')::uuid[])`;
  return c.json({ ids: rows.map((r: { pack_id: string }) => r.pack_id) });
});

app.put("/v1/packs/:id/like", async (c) => {
  const id = c.req.param("id");
  if (!UUID.test(id)) return fail(404, "not_found");
  const uid = c.get("uid");
  const found = await db!.begin(async (tx) => {
    const [pack] = await tx`select id from theme_packs where id = ${id} for update`;
    if (!pack) return false;
    const added = await tx`
      insert into theme_likes (pack_id, user_id) values (${id}, ${uid})
      on conflict do nothing returning pack_id`;
    if (added.length) await tx`update theme_packs set likes = likes + 1 where id = ${id}`;
    return true;
  });
  return found ? c.json({ ok: true }) : fail(404, "not_found");
});

app.delete("/v1/packs/:id/like", async (c) => {
  const id = c.req.param("id");
  if (!UUID.test(id)) return fail(404, "not_found");
  const uid = c.get("uid");
  await db!.begin(async (tx) => {
    const gone = await tx`delete from theme_likes where pack_id = ${id} and user_id = ${uid} returning pack_id`;
    if (gone.length) await tx`update theme_packs set likes = greatest(likes - 1, 0) where id = ${id}`;
  });
  return c.json({ ok: true });
});

/** A download, counted once per person and pack: reapplying a theme does not inflate it. */
app.post("/v1/packs/:id/download", async (c) => {
  const id = c.req.param("id");
  if (!UUID.test(id)) return fail(404, "not_found");
  const uid = c.get("uid");
  const found = await db!.begin(async (tx) => {
    const [pack] = await tx`select id from theme_packs where id = ${id} for update`;
    if (!pack) return false;
    const added = await tx`
      insert into theme_downloads (pack_id, user_id) values (${id}, ${uid})
      on conflict do nothing returning pack_id`;
    if (added.length) await tx`update theme_packs set downloads = downloads + 1 where id = ${id}`;
    return true;
  });
  return found ? c.json({ ok: true }) : fail(404, "not_found");
});

/** A pack's picture, as the store shows it. */
app.get("/v1/packs/:id/snapshot", async (c) => {
  const id = c.req.param("id");
  if (!UUID.test(id)) return fail(404, "not_found");
  const [row] = await db!`
    select s.mime, s.image, p.snapshot
      from theme_snapshots s join theme_packs p on p.id = s.pack_id
     where s.pack_id = ${id}`;
  if (!row) return fail(404, "not_found");
  // Named by its hash in every listing, so a copy never goes stale.
  return new Response(row.image, {
    headers: {
      "content-type": row.mime,
      "cache-control": "private, max-age=31536000, immutable",
      etag: `"${row.snapshot}"`,
    },
  });
});

/** The author sets (or replaces) a pack's picture: the image itself as the body. */
app.put("/v1/packs/:id/snapshot", async (c) => {
  const id = c.req.param("id");
  if (!UUID.test(id)) return fail(404, "not_found");
  const bytes = new Uint8Array(await c.req.raw.arrayBuffer());
  if (bytes.byteLength > MAX_SNAPSHOT_BYTES) return fail(413, "too_big");
  const mime = imageType(bytes);
  if (!mime) return fail(400, "bad_image");
  const hash = new Bun.CryptoHasher("sha256").update(bytes).digest("hex").slice(0, 16);
  const done = await db!.begin(async (tx) => {
    const [pack] = await tx`select id from theme_packs where id = ${id} and user_id = ${c.get("uid")} for update`;
    if (!pack) return false;
    await tx`
      insert into theme_snapshots (pack_id, mime, image) values (${id}, ${mime}, ${bytes})
      on conflict (pack_id) do update set mime = excluded.mime, image = excluded.image, created_at = now()`;
    await tx`update theme_packs set snapshot = ${hash}, updated_at = now() where id = ${id}`;
    return true;
  });
  return done ? c.json({ snapshot: hash }) : fail(404, "not_found");
});

/** One report per person and pack; a second is taken as the first. */
app.post("/v1/packs/:id/report", async (c) => {
  const id = c.req.param("id");
  if (!UUID.test(id)) return fail(404, "not_found");
  const input = await body(c.req.raw);
  const reason = String(input?.reason ?? "").trim().slice(0, 280);
  const [pack] = await db!`select id from theme_packs where id = ${id}`;
  if (!pack) return fail(404, "not_found");
  await db!`
    insert into theme_reports (pack_id, user_id, reason) values (${id}, ${c.get("uid")}, ${reason})
    on conflict do nothing`;
  return c.json({ ok: true });
});

app.onError((err) => {
  if (err instanceof TooBig) return fail(413, "too_big");
  console.error(err);
  return fail(500, "server_error");
});

/** What an upload is by its first bytes, whatever it says it is: WebP, PNG or JPEG, or null. */
function imageType(b: Uint8Array): string | null {
  const ascii = (from: number, to: number) => String.fromCharCode(...b.subarray(from, to));
  if (b.length > 12 && ascii(0, 4) === "RIFF" && ascii(8, 12) === "WEBP") return "image/webp";
  if (b.length > 8 && b[0] === 0x89 && ascii(1, 4) === "PNG" && b[4] === 0x0d && b[5] === 0x0a) return "image/png";
  if (b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "image/jpeg";
  return null;
}

function shareCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  let out = "";
  for (const b of bytes) out += CODE_ALPHABET[b % CODE_ALPHABET.length];
  return out;
}

export default {
  port: Number(Bun.env.PORT ?? 3000),
  // Refused at the socket too, so a body with no length header is not read whole.
  maxRequestBodySize: MAX_BODY_BYTES,
  fetch: app.fetch,
};

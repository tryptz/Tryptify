// The store end to end against a real Postgres, with a stand-in for Supabase
// Auth: its public keys, signed for with a key made here, and its "who is this"
// endpoint for tokens signed with a shared secret. Needs an empty database it
// may drop tables in:
//
//   TEST_DATABASE_URL=postgres://postgres@127.0.0.1:55432/store bun run test.ts
//
// A plain script rather than `bun test`: the runner does not fetch versioned
// imports like "jose@5", and a plain run does, as Railway does for index.ts.

import assert from "node:assert/strict";
import { SQL } from "bun";
import { SignJWT, exportJWK, generateKeyPair } from "jose@5";

const url = Bun.env.TEST_DATABASE_URL;
const ANON = "test-anon-key";
const SECRET = new TextEncoder().encode("the project's shared secret, which the store never sees");
let ISSUER = "";
const A = "11111111-1111-4111-8111-111111111111";
const B = "22222222-2222-4222-8222-222222222222";
const CODE = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/;

let fetchStore: (r: Request) => Promise<Response>;
let keys: CryptoKeyPair;
let stranger: CryptoKeyPair;
let supabase: ReturnType<typeof Bun.serve>;
let db: SQL;
// What the stand-in for Supabase Auth knows: live secret-signed tokens, who
// they belong to, how often it was asked, and whether it is down.
const liveSecretTokens = new Map<string, string>();
let askedWho = 0;
let authDown = false;

const pack = (extra: Record<string, unknown> = {}) =>
  JSON.stringify({ version: 1, name: "Night", colours: { theme: "monochrome_dark" }, ...extra });

async function token(sub: string, opts: { key?: CryptoKey; aud?: string; exp?: string; iss?: string } = {}) {
  return new SignJWT({ role: "authenticated" })
    .setProtectedHeader({ alg: "ES256", kid: opts.key ? "stranger" : "test" })
    .setSubject(sub)
    .setIssuer(opts.iss ?? ISSUER)
    .setAudience(opts.aud ?? "authenticated")
    .setIssuedAt()
    .setExpirationTime(opts.exp ?? "5m")
    .sign(opts.key ?? keys.privateKey);
}

async function call(method: string, path: string, who?: string, body?: unknown) {
  const headers: Record<string, string> = {};
  if (who) headers.authorization = `Bearer ${who}`;
  if (body !== undefined) headers["content-type"] = "application/json";
  const res = await fetchStore(
    new Request(`http://store.test${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

const publish = (who: string, extra: Record<string, unknown> = {}) =>
  call("POST", "/v1/packs", who, {
    name: "Night",
    author_name: "tester",
    description: "dark and quiet",
    parts: ["COLOURS", "LYRICS"],
    accent: -16776961,
    background: -16777216,
    pack: pack(),
    listed: true,
    ...extra,
  });

const tests: [string, () => Promise<void>][] = [];
const test = (name: string, fn: () => Promise<void>) => tests.push([name, fn]);

let a: string;
let b: string;

async function setUp() {
    db = new SQL(url!);
    await db.unsafe("drop table if exists theme_reports, theme_downloads, theme_likes, theme_snapshots, theme_packs cascade");
    keys = await generateKeyPair("ES256");
    stranger = await generateKeyPair("ES256");
    const jwk = { ...(await exportJWK(keys.publicKey)), kid: "test", alg: "ES256", use: "sig" };
    supabase = Bun.serve({
      port: 0,
      fetch(req) {
        const path = new URL(req.url).pathname;
        if (path === "/auth/v1/.well-known/jwks.json") return Response.json({ keys: [jwk] });
        if (path !== "/auth/v1/user") return new Response("no", { status: 404 });
        askedWho++;
        if (authDown) return new Response("down", { status: 503 });
        if (req.headers.get("apikey") !== ANON) return Response.json({ msg: "no api key" }, { status: 401 });
        const id = liveSecretTokens.get((req.headers.get("authorization") ?? "").slice(7));
        return id ? Response.json({ id, aud: "authenticated" }) : Response.json({ msg: "bad jwt" }, { status: 403 });
      },
    });
    ISSUER = `http://127.0.0.1:${supabase.port}/auth/v1`;
    Bun.env.DATABASE_URL = url;
    Bun.env.SUPABASE_URL = `http://127.0.0.1:${supabase.port}/`;
    Bun.env.SUPABASE_ANON_KEY = ANON;
    const server = (await import("./index.ts")).default;
    fetchStore = (r) => Promise.resolve(server.fetch(r));
    a = await token(A);
    b = await token(B);
}

  test("health answers without an account", async () => {
    const res = await call("GET", "/health");
    assert.equal(res.status, 200);
    assert.deepEqual(res.body, { ok: true, store: "tryptify-theme-store", database: true });
  });

  test("everything else needs a valid Supabase session", async () => {
    assert.deepEqual((await call("GET", "/v1/packs")).body, { error: "signed_out" });
    const bad = [
      "not-a-jwt",
      await token(A, { key: stranger.privateKey }),
      await token(A, { aud: "anon" }),
      await token(A, { iss: "http://elsewhere.test/auth/v1" }),
      await token(A, { exp: "-1m" }),
      await token("not-a-uuid"),
    ];
    for (const t of bad) {
      const res = await call("GET", "/v1/packs", t);
      assert.equal(res.status, 401);
      assert.equal(res.body.error, "signed_out");
    }
  });

  test("a token signed with the shared secret is vouched for by Supabase, once", async () => {
    const secretSigned = async (sub: string) =>
      new SignJWT({ role: "authenticated" })
        .setProtectedHeader({ alg: "HS256" })
        .setSubject(sub)
        .setIssuer(ISSUER)
        .setAudience("authenticated")
        .setIssuedAt()
        .setExpirationTime("5m")
        .sign(SECRET);
    const live = await secretSigned("55555555-5555-4555-8555-555555555555");
    liveSecretTokens.set(live, "55555555-5555-4555-8555-555555555555");
    const before = askedWho;
    const made = await publish(live, { name: "Secret signed" });
    assert.equal(made.status, 201);
    assert.equal(made.body.user_id, "55555555-5555-4555-8555-555555555555");
    for (let i = 0; i < 3; i++) assert.equal((await call("GET", "/v1/packs?sort=mine", live)).status, 200);
    assert.equal(askedWho - before, 1);

    // One it does not know, and the app's own public key, are nobody.
    const unknown = await call("GET", "/v1/packs", await secretSigned(A));
    assert.equal(unknown.status, 401);
    assert.equal(unknown.body.error, "signed_out");
    const anonKey = await new SignJWT({ role: "anon" }).setProtectedHeader({ alg: "HS256" }).setIssuer("supabase").sign(SECRET);
    assert.equal((await call("GET", "/v1/packs", anonKey)).status, 401);

    // Supabase down is not "signed out": the app should not throw the session away.
    authDown = true;
    try {
      const down = await call("GET", "/v1/packs", await secretSigned(B));
      assert.equal(down.status, 502);
      assert.equal(down.body.error, "auth_unreachable");
      // A token it already vouched for keeps working meanwhile.
      assert.equal((await call("GET", "/v1/packs", live)).status, 200);
    } finally {
      authDown = false;
    }
  });

  test("publishing gives back the listing row and a share code", async () => {
    const res = await publish(a);
    assert.equal(res.status, 201);
    assert.equal(res.body.user_id, A);
    assert.equal(res.body.name, "Night");
    assert.deepEqual(res.body.parts, ["COLOURS", "LYRICS"]);
    assert.equal(res.body.accent, -16776961);
    assert.match(res.body.share_code, CODE);
    assert.equal(res.body.likes, 0);
    assert.equal(res.body.pack, undefined);
    assert.equal(typeof res.body.created_at, "string");
    // Only its author sees whose it is: to anyone else the account id is "".
    assert.equal((await call("GET", `/v1/packs/${res.body.id}`, a)).body.user_id, A);
    assert.equal((await call("GET", `/v1/packs/${res.body.id}`, b)).body.user_id, "");
    assert.equal((await call("GET", `/v1/codes/${res.body.share_code}`, b)).body.user_id, "");
  });

  test("a pack of all nine parts, the CRT among them, is taken", async () => {
    const all = ["COLOURS", "LYRICS", "PLAYER_GLASS", "PANELS", "FONT", "VISUALIZER", "MAPS", "LINKS", "CRT"];
    const res = await publish(a, { parts: all });
    assert.equal(res.status, 201);
    assert.deepEqual(res.body.parts, all);
  });

  test("a pack that is not a pack is refused", async () => {
    const refused = [
      { parts: [] },
      { parts: ["COLOURS", "WALLPAPER"] },
      { name: "   " },
      { name: "x".repeat(41) },
      { pack: "not json" },
      { pack: "[1,2]" },
      { pack: pack({ format: "something-else" }) },
      { pack: pack({ filler: "x".repeat(66_000) }) },
    ];
    for (const extra of refused) {
      const res = await publish(a, extra);
      assert.equal(res.status, 400);
      assert.equal(res.body.error, "bad_pack");
    }
    // At the limit and all quotes, so its escaped copy in the body is twice its size.
    // Each quote is two bytes in the pack (\") and four in the body (\\\").
    const base = pack({ filler: "" }).length;
    const atLimit = pack({ filler: '"'.repeat(Math.floor((65_536 - base) / 2)) });
    assert.ok(atLimit.length <= 65_536 && atLimit.length > 65_530);
    assert.equal((await publish(a, { pack: atLimit })).status, 201);
    // Named explicitly is as good as left out.
    assert.equal((await publish(a, { pack: pack({ format: "tryptheme" }) })).status, 201);
  });

  test("unlisted packs stay out of the store but open by code and id", async () => {
    const hidden = (await publish(a, { name: "Hidden", listed: false })).body;
    const store = (await call("GET", "/v1/packs?sort=new", b)).body;
    assert.equal(store.some((p: { id: string }) => p.id === hidden.id), false);
    const mine = (await call("GET", "/v1/packs?sort=mine", a)).body;
    assert.equal(mine.some((p: { id: string }) => p.id === hidden.id), true);
    assert.deepEqual((await call("GET", "/v1/packs?sort=mine", b)).body, []);

    // Typed by hand: lower case, a dash in the middle.
    const code = hidden.share_code.toLowerCase();
    const found = await call("GET", `/v1/codes/${code.slice(0, 4)}-${code.slice(4)}`, b);
    assert.equal(found.status, 200);
    assert.equal(found.body.id, hidden.id);
    assert.equal((await call("GET", "/v1/codes/ZZZZ0000", b)).status, 404);

    const full = await call("GET", `/v1/packs/${hidden.id}`, b);
    assert.equal(full.status, 200);
    assert.equal(JSON.parse(full.body.pack).name, "Night");
    assert.equal((await call("GET", "/v1/packs/not-an-id", b)).status, 404);
  });

  test("search matches names, and wildcards are just characters gone", async () => {
    await publish(b, { name: "Sunrise Glow" });
    const res = (await call("GET", "/v1/packs?q=sunr", a)).body;
    assert.deepEqual(res.map((p: { name: string }) => p.name), ["Sunrise Glow"]);
    assert.ok((await call("GET", "/v1/packs?q=%25", a)).body.length > 1);
  });

  test("only the author lists, unlists or deletes", async () => {
    const id = (await publish(a, { name: "Mine" })).body.id;
    assert.equal((await call("PATCH", `/v1/packs/${id}`, b, { listed: false })).status, 404);
    assert.equal((await call("DELETE", `/v1/packs/${id}`, b)).status, 404);
    assert.equal((await call("PATCH", `/v1/packs/${id}`, a, { listed: "no" })).status, 400);
    assert.equal((await call("PATCH", `/v1/packs/${id}`, a, { listed: false })).status, 200);
    assert.equal((await call("GET", `/v1/packs/${id}`, a)).body.listed, false);
    assert.equal((await call("DELETE", `/v1/packs/${id}`, a)).status, 200);
    assert.equal((await call("GET", `/v1/packs/${id}`, a)).status, 404);
  });

  test("likes and downloads count once per person", async () => {
    const id = (await publish(a, { name: "Counted" })).body.id;
    await call("PUT", `/v1/packs/${id}/like`, b);
    await call("PUT", `/v1/packs/${id}/like`, b);
    await call("PUT", `/v1/packs/${id}/like`, a);
    assert.equal((await call("GET", `/v1/packs/${id}`, a)).body.likes, 2);
    assert.deepEqual((await call("GET", `/v1/likes?ids=${id},bogus`, b)).body, { ids: [id] });
    await call("DELETE", `/v1/packs/${id}/like`, b);
    await call("DELETE", `/v1/packs/${id}/like`, b);
    assert.equal((await call("GET", `/v1/packs/${id}`, a)).body.likes, 1);
    assert.deepEqual((await call("GET", `/v1/likes?ids=${id}`, b)).body, { ids: [] });

    for (let i = 0; i < 3; i++) await call("POST", `/v1/packs/${id}/download`, b);
    assert.equal((await call("GET", `/v1/packs/${id}`, a)).body.downloads, 1);
    assert.equal((await call("PUT", "/v1/packs/33333333-3333-4333-8333-333333333333/like", b)).status, 404);

    // Popular puts the liked pack first.
    assert.equal((await call("GET", "/v1/packs?sort=popular", b)).body[0].id, id);
  });

  test("a second report is taken as the first", async () => {
    const id = (await publish(a, { name: "Reported" })).body.id;
    assert.equal((await call("POST", `/v1/packs/${id}/report`, b, { reason: "spam" })).status, 200);
    assert.equal((await call("POST", `/v1/packs/${id}/report`, b, { reason: "spam again" })).status, 200);
    const [{ n }] = await db`select count(*)::int as n from theme_reports where pack_id = ${id}`;
    assert.equal(n, 1);
  });

  test("pages are twenty long, and fifty packs is the most", async () => {
    const c = await token("44444444-4444-4444-8444-444444444444");
    for (let i = 0; i < 50; i++) assert.equal((await publish(c, { name: `Bulk ${i}` })).status, 201);
    const over = await publish(c, { name: "One too many" });
    assert.equal(over.status, 409);
    assert.equal(over.body.error, "limit_reached");
    const first = (await call("GET", "/v1/packs?sort=mine&page=0", c)).body;
    const third = (await call("GET", "/v1/packs?sort=mine&page=2", c)).body;
    assert.equal(first.length, 20);
    assert.equal(third.length, 10);
    assert.equal(first[0].name, "Bulk 49");
  });

  test("a pack's picture: the author sets it, anyone signed in sees it, it goes with the pack", async () => {
    // A WebP by its header; the server checks the first bytes, not the name.
    const webp = (size: number, seed = 1) => {
      const b = new Uint8Array(size);
      b.set(new TextEncoder().encode("RIFF"), 0);
      b.set(new TextEncoder().encode("WEBP"), 8);
      for (let i = 12; i < size; i++) b[i] = (i * 31 + seed) & 0xff;
      return b;
    };
    const made = (await publish(a, { name: "Pictured" })).body;
    assert.equal(made.snapshot, null);
    const url = `http://store.test/v1/packs/${made.id}/snapshot`;
    const put = (who: string, body: Uint8Array) =>
      fetchStore(new Request(url, { method: "PUT", headers: { authorization: `Bearer ${who}`, "content-type": "image/webp" }, body }));
    const get = (who: string) => fetchStore(new Request(url, { headers: { authorization: `Bearer ${who}` } }));

    assert.equal((await get(b)).status, 404);
    assert.equal((await put(b, webp(4000))).status, 404);
    const first = await put(a, webp(4000));
    assert.equal(first.status, 200);
    const { snapshot } = (await first.json()) as { snapshot: string };
    assert.match(snapshot, /^[0-9a-f]{16}$/);

    const row = (await call("GET", "/v1/packs?sort=mine", a)).body.find((p: { id: string }) => p.id === made.id);
    assert.equal(row.snapshot, snapshot);
    assert.equal((await call("GET", `/v1/packs/${made.id}`, b)).body.snapshot, snapshot);

    const got = await get(b);
    assert.equal(got.status, 200);
    assert.equal(got.headers.get("content-type"), "image/webp");
    assert.equal(got.headers.get("etag"), `"${snapshot}"`);
    assert.deepEqual(new Uint8Array(await got.arrayBuffer()), webp(4000));

    const notImage = await put(a, new TextEncoder().encode("<svg onload=alert(1)></svg>"));
    assert.equal(notImage.status, 400);
    assert.equal(((await notImage.json()) as { error: string }).error, "bad_image");
    assert.equal((await put(a, webp(170_000))).status, 413);

    const again = (await (await put(a, webp(5000, 7))).json()) as { snapshot: string };
    assert.notEqual(again.snapshot, snapshot);
    assert.equal((await get(b)).headers.get("etag"), `"${again.snapshot}"`);

    await call("DELETE", `/v1/packs/${made.id}`, a);
    assert.equal((await get(a)).status, 404);
    const [{ n }] = await db`select count(*)::int as n from theme_snapshots where pack_id = ${made.id}`;
    assert.equal(n, 0);
  });

  test("an oversized body is refused before it is read", async () => {
    const res = await fetchStore(
      new Request("http://store.test/v1/packs", {
        method: "POST",
        headers: { authorization: `Bearer ${a}`, "content-type": "application/json" },
        body: JSON.stringify({ pack: "x".repeat(200_000) }),
      }),
    );
    assert.equal(res.status, 413);
  });

  test("without a database it says it is not set up", async () => {
    const port = 30_000 + Math.floor(Math.random() * 20_000);
    const proc = Bun.spawn(["bun", `${import.meta.dir}/index.ts`], {
      env: { ...Bun.env, DATABASE_URL: "", PORT: String(port) },
      stdout: "ignore",
      stderr: "inherit",
    });
    try {
      let res: Response | undefined;
      for (let i = 0; i < 50 && !res; i++) {
        res = await fetch(`http://127.0.0.1:${port}/v1/packs`).catch(() => undefined);
        if (!res) await Bun.sleep(100);
      }
      assert.equal(res?.status, 503);
      assert.deepEqual(await res?.json(), { error: "not_set_up" });
    } finally {
      proc.kill();
    }
  });

if (!url) {
  console.error("TEST_DATABASE_URL is not set: nothing to test against.");
  process.exit(2);
}
await setUp();
let failed = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log(`  ok    ${name}`);
  } catch (e) {
    failed++;
    console.log(`  FAIL  ${name}`);
    console.log(e);
  }
}
supabase.stop(true);
await db.close();
console.log(`${tests.length - failed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);

// Tryptify Connect's pure parts and its sign-in check, with a stand-in for
// Supabase Auth's public keys. Needs nothing else:
//
//   bun install && bun run test.ts
//
// e2e.ts runs the real room in Cloudflare's runtime (`wrangler dev`).

import assert from "node:assert/strict";
import { SignJWT, exportJWK, generateKeyPair } from "jose";
import { supabaseAuth } from "./src/auth";
import * as P from "./src/protocol";

let passed = 0;
const test = async (name: string, fn: () => unknown) => {
  try { await fn(); passed++; } catch (e) { console.error(`FAIL ${name}`); throw e; }
};

await test("device from the query string", () => {
  assert.deepEqual(P.deviceFrom(new URLSearchParams("device=abcdefgh12&name=Pixel%208&kind=android")), { id: "abcdefgh12", name: "Pixel 8", kind: "android" });
  assert.equal(P.deviceFrom(new URLSearchParams("device=short")), null);
  assert.equal(P.deviceFrom(new URLSearchParams("device=../../etc/passwd")), null);
  assert.deepEqual(P.deviceFrom(new URLSearchParams("device=abcdefgh12&kind=toaster")), { id: "abcdefgh12", name: "A device", kind: "web" });
});

await test("tracks are trimmed, and only safe covers travel", () => {
  const t = P.cleanTrack({ src: "hifi", id: "123", title: "x".repeat(999), cover: "https://resources.tidal.com/images/a/b/320x320.jpg", duration: 200 });
  assert.equal(t?.title.length, 300);
  assert.equal(t?.cover, "https://resources.tidal.com/images/a/b/320x320.jpg");
  assert.equal(P.cleanTrack({ src: "subsonic", id: "1", cover: "https://music.example/rest/getCoverArt.view?id=1&u=me&t=abc&s=def" })?.cover, null);
  assert.equal(P.cleanTrack({ src: "local", id: "1", cover: "blob:https://tryptify.com/x" })?.cover, null);
  assert.equal(P.cleanTrack({ src: "evil", id: "1" }), null);
  assert.equal(P.cleanTrack({ src: "hifi" }), null);
});

await test("state and where it is later", () => {
  const now = P.cleanState({ playing: true, track: { src: "hifi", id: "1", duration: 100 }, position: 10, rate: 2 }, "dev", 1000);
  assert.equal(P.positionAt(now, 6000), 20);   // 5 s at 2× is 10 s on
  assert.equal(P.positionAt(now, 999_999), 100); // never past the end
  assert.equal(P.positionAt({ ...now, playing: false }, 6000), 10);
  assert.equal(P.cleanState({ position: -5, rate: 99 }, "d", 0).position, 0);
  assert.equal(P.cleanState({ position: -5, rate: 99 }, "d", 0).rate, 4);
  assert.equal(P.cleanState({ since: 500 }, "d", 1000).since, 500);
  assert.equal(P.cleanState({}, "d", 1000).since, 1000);
  assert.equal(P.cleanState({ since: 9e15 }, "d", 1000).since, 61_000); // not from the far future
});

await test("commands", () => {
  assert.deepEqual(P.cleanCommand({ action: "seek", value: 42 }), { action: "seek", value: 42 });
  assert.deepEqual(P.cleanCommand({ action: "pause", value: 42 }), { action: "pause", value: null });
  assert.equal(P.cleanCommand({ action: "rm -rf" }), null);
});

await test("queues are capped", () => {
  const q = P.cleanQueue(Array.from({ length: 500 }, (_, i) => ({ src: "cloud", id: String(i) })));
  assert.equal(q.length, P.MAX_QUEUE);
  assert.deepEqual(P.cleanQueue("nope"), []);
});

await test("the token: a header from the app, a subprotocol from a browser", () => {
  assert.deepEqual(P.tokenFrom(new Headers({ authorization: "Bearer abc.def.ghi" })), { token: "abc.def.ghi", protocol: false });
  assert.deepEqual(P.tokenFrom(new Headers({ "sec-websocket-protocol": "tryptify-connect, bearer.abc.def.ghi" })), { token: "abc.def.ghi", protocol: true });
  assert.deepEqual(P.tokenFrom(new Headers()), { token: null, protocol: false });
});

// ── Sign-in, against a stand-in for Supabase's public keys ──
const { publicKey, privateKey } = await generateKeyPair("ES256");
const jwk = { ...(await exportJWK(publicKey)), kid: "k1", alg: "ES256", use: "sig" };
const server = Bun.serve({ port: 0, fetch: (r) => new URL(r.url).pathname === "/auth/v1/.well-known/jwks.json" ? Response.json({ keys: [jwk] }) : new Response("no", { status: 404 }) });
const base = `http://localhost:${server.port}`;
const issuer = `${base}/auth/v1`;
const personOf = supabaseAuth(base, "anon");
const A = "11111111-1111-4111-8111-111111111111";
const sign = (claims: Record<string, unknown>, exp = "5m", key = privateKey) =>
  new SignJWT(claims).setProtectedHeader({ alg: "ES256", kid: "k1" }).setIssuer(issuer).setAudience("authenticated").setIssuedAt().setExpirationTime(exp).sign(key);

await test("a live session is its account", async () => assert.equal(await personOf(await sign({ sub: A })), A));
await test("an expired session is nobody", async () => assert.equal(await personOf(await sign({ sub: A }, "-1m")), null));
await test("another key is nobody", async () => {
  const other = await generateKeyPair("ES256");
  assert.equal(await personOf(await sign({ sub: A }, "5m", other.privateKey)), null);
});
await test("not a uuid is nobody", async () => assert.equal(await personOf(await sign({ sub: "admin" })), null));
await test("garbage is nobody", async () => assert.equal(await personOf("not.a.token"), null));
await test("shared-secret tokens are asked of Supabase Auth, then remembered", async () => {
  let asked = 0;
  const hs = supabaseAuth(base, "anon", (async () => { asked++; return Response.json({ id: A }); }) as typeof fetch);
  const token = await new SignJWT({ sub: A }).setProtectedHeader({ alg: "HS256" }).setIssuer(issuer).setExpirationTime("5m").sign(new TextEncoder().encode("secret"));
  assert.equal(await hs(token), A);
  assert.equal(await hs(token), A);
  assert.equal(asked, 1);
});
await test("shared-secret tokens of another project are not asked about, and a refusal is remembered", async () => {
  let asked = 0;
  const hs = supabaseAuth(base, "anon", (async () => { asked++; return new Response("{}", { status: 401 }); }) as typeof fetch);
  const make = (iss: string) => new SignJWT({ sub: A }).setProtectedHeader({ alg: "HS256" }).setIssuer(iss).setExpirationTime("5m").sign(new TextEncoder().encode("secret"));
  assert.equal(await hs(await make("https://elsewhere.example/auth/v1")), null);
  assert.equal(asked, 0);
  const dead = await make(issuer);
  assert.equal(await hs(dead), null);
  assert.equal(await hs(dead), null);
  assert.equal(asked, 1);
});
await test("Supabase Auth failing is not a sign-out", async () => {
  const hs = supabaseAuth(base, "anon", (async () => new Response("down", { status: 503 })) as typeof fetch);
  const token = await new SignJWT({ sub: A }).setProtectedHeader({ alg: "HS256" }).setIssuer(issuer).setExpirationTime("5m").sign(new TextEncoder().encode("secret"));
  await assert.rejects(hs(token));
});

server.stop();
console.log(`connect: ${passed} tests pass`);

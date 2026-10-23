// The listening-party catalogue client (src/catalog.ts): the tests the Rust
// server's catalog.rs had, and the client against a stand-in TrypT HiFi
// (a fake fetch answering canned JSON). Needs nothing else:
//
//   bun run catalog.test.ts

import assert from "node:assert/strict";
import {
  Catalog,
  CatalogError,
  LENGTH_SLACK_MS,
  MAX_RESULTS,
  TIMEOUT_MS,
  catalogKey,
  coverUrl,
  foundIn,
  pick,
  sameTitle,
  tidalId,
  type Found,
} from "./src/catalog";

let passed = 0;
let failed = 0;
const test = async (name: string, fn: () => unknown) => {
  try {
    await fn();
    passed++;
  } catch (e) {
    failed++;
    console.error(`FAIL ${name}`);
    console.error(e);
  }
};

// --- A stand-in TrypT HiFi ---------------------------------------------------

interface Call { url: URL; headers: Headers; init: RequestInit }

/** A fetch that answers [answer] for each request and remembers what it was asked. */
function fakeFetch(answer: (url: URL, call: Call) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const fetchImpl = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const call = { url: new URL(String(input)), headers: new Headers(init.headers), init };
    calls.push(call);
    return answer(call.url, call);
  }) as typeof fetch;
  return { calls, fetchImpl };
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const BASE = "https://hifi.example";
const KEY = "pass-word-123";

/** One TrypT HiFi search item, shaped as catalog.rs reads it. */
const item = (id: number, title: string, artist: string, secs: number, extra: Record<string, unknown> = {}) => ({
  id, title, duration: secs, allowStreaming: true, explicit: false,
  artists: [{ name: artist }], album: { title: "Album", cover: null }, ...extra,
});

// --- Ported from catalog.rs ---------------------------------------------------

await test("a search answer becomes songs to add", () => {
  const body = { version: "2.9", data: { items: [
    { id: 124215, title: "One More Time", version: "Radio Edit", duration: 235,
      allowStreaming: true, isrc: "GBDUW0000059", explicit: false,
      artists: [{ name: "Daft Punk" }, { name: "Romanthony" }],
      album: { title: "One More Time", cover: "7d3b9810-5634-400c-ad89-50609e0ce800" } },
    { id: 2, title: "Not here", duration: 100, allowStreaming: false },
    { id: 3, title: "No length", duration: 0 },
    { id: 4, title: "Plain", duration: 60, artist: { name: "Solo" },
      album: { cover: "../../etc" } },
  ] } };
  const found = foundIn(body);
  assert.equal(found.length, 2);
  assert.deepEqual(found[0], {
    catalogId: "tidal:124215",
    title: "One More Time (Radio Edit)",
    artist: "Daft Punk, Romanthony",
    album: "One More Time",
    durationMs: 235_000,
    isrc: "GBDUW0000059",
    cover: "https://resources.tidal.com/images/7d3b9810/5634/400c/ad89/50609e0ce800/640x640.jpg",
    explicit: false,
  });
  // One artist alone, and a cover id that is not one is left out.
  assert.equal(found[1].artist, "Solo");
  assert.equal(found[1].cover, null);
});

await test("only a tidal id is played from the catalogue", () => {
  assert.equal(tidalId("tidal:42"), 42);
  assert.equal(tidalId("deezer:42"), null);
  assert.equal(tidalId("tidal:"), null);
  assert.equal(tidalId("tidal:4/../2"), null);
});

const song = (id: number, title: string, artist: string, secs: number, isrc: string | null): Found => ({
  catalogId: `tidal:${id}`, title, artist, album: "", durationMs: secs * 1000, isrc, cover: null, explicit: false,
});

await test("a song known by name is found as the same recording", () => {
  const found = [
    song(1, "Roundabout (Live)", "Yes", 640, null),
    song(2, "Roundabout (2003 Remaster)", "Yes", 516, "USAT20300001"),
    song(3, "Roundabout", "Someone Else", 516, null),
    song(4, "Roundabout", "Yes", 519, null),
  ];
  // The ISRC wins whatever the order.
  assert.equal(pick(found, "Roundabout", "Yes", 0, "usat20300001"), 2);
  // By name: the right artist at the closest length, not the first hit.
  assert.equal(pick(found, "Roundabout", "Yes", 516_000, null), 2);
  assert.equal(pick(found, "Roundabout", "Yes", 519_500, null), 4);
  // Nothing near enough the length: none rather than the wrong one.
  assert.equal(pick(found, "Roundabout", "Yes", 300_000, null), null);
  assert.equal(pick(found, "Heart of the Sunrise", "Yes", 0, null), null);
});

await test("a title is the same song with or without its version", () => {
  assert.ok(sameTitle("Tricky Disco – HardTekk", "tricky disco hardtekk"));
  assert.ok(sameTitle("One More Time (Radio Edit)", "One More Time"));
  assert.ok(!sameTitle("Blinding Lights", "Tricky Disco"));
  assert.ok(!sameTitle("", "x"));
});

await test("the catalogue is a web address", () => {
  assert.notEqual(Catalog.from({ TRYPT_HIFI_URL: "https://hifi.example/" }), null);
  assert.equal(Catalog.from({ TRYPT_HIFI_URL: "  " }), null);
  assert.equal(Catalog.from({ TRYPT_HIFI_URL: "ftp://hifi.example" }), null);
  assert.equal(Catalog.from({}), null);
  assert.equal(new Catalog("  https://hifi.example//  ").base, "https://hifi.example");
  assert.throws(() => new Catalog("ftp://hifi.example"), TypeError);
});

// --- The pure helpers, further ------------------------------------------------

await test("a catalogue id splits into its catalogue and number", () => {
  assert.deepEqual(catalogKey("qobuz:12"), ["qobuz", 12]);
  // Deezer is still told apart, for the caller to look it up by name.
  assert.deepEqual(catalogKey("deezer:42"), ["deezer", 42]);
  assert.deepEqual(catalogKey("tidal:007"), ["tidal", 7]);
  for (const bad of ["tidal:0", ":5", "tidal", "tidal:-1", "tidal:1.5", "tidal:4:2", "tidal: 4", "tidal:99999999999999999999"]) {
    assert.equal(catalogKey(bad), null, bad);
  }
});

await test("the constants are the Rust server's", () => {
  assert.equal(MAX_RESULTS, 25);
  assert.equal(LENGTH_SLACK_MS, 3_000);
  assert.equal(TIMEOUT_MS, 20_000);
});

await test("pick: the slack is inclusive, ties go to the first, and an ISRC on no TIDAL song is none", () => {
  const found = [song(1, "Song", "Band", 200, null), song(2, "Song", "Band", 206, null)];
  assert.equal(pick(found, "Song", "Band", 203_000, null), 1);
  assert.equal(pick(found, "Song", "Band", 209_000, null), 2);
  assert.equal(pick(found, "Song", "Band", 209_001, null), null);
  assert.equal(pick(found, "Song", "", 0, null), 1);
  assert.equal(pick(found, "", "Band", 0, null), null);
  // A blank ISRC is no ISRC.
  assert.equal(pick(found, "Song", "Band", 0, "   "), 1);
  // The same ISRC on a song that is not TIDAL's: none, as the Rust has it.
  assert.equal(pick([{ ...song(9, "x", "y", 1, "AB1"), catalogId: "qobuz:9" }, song(3, "Song", "Band", 200, null)], "Song", "Band", 0, "ab1"), null);
});

await test("titles compare letters only, in any script", () => {
  assert.ok(sameTitle("ΣΟΦΟΣ", "σοφοσ"));
  assert.ok(sameTitle("Ça va (Remix)", "ça va"));
  assert.ok(sameTitle("「夜に駆ける」", "夜に駆ける"));
});

await test("a search item's edges", () => {
  const found = foundIn({ items: [
    { id: 1, title: "  Spaced  ", version: " Live ", duration: 10 },
    { id: 2, title: "Live Song (Live)", version: "Live", duration: 10, isrc: "", explicit: true },
    { id: "3", title: "String id", duration: 10 },
    { id: 4, title: "Half second", duration: 10.5 },
    { id: 5, duration: 10 },
    { id: 6, title: "   ", duration: 10 },
  ] });
  assert.deepEqual(found.map((f) => f.title), ["Spaced (Live)", "Live Song (Live)"]);
  assert.equal(found[1].isrc, null);
  assert.equal(found[1].explicit, true);
  assert.equal(found[0].artist, "");
  assert.equal(found[0].album, "");
  assert.deepEqual(foundIn(null), []);
  assert.deepEqual(foundIn({ data: { items: "no" } }), []);
  assert.equal(coverUrl("7D3B9810-5634-400C-AD89-50609E0CE800"),
    "https://resources.tidal.com/images/7D3B9810/5634/400C/AD89/50609E0CE800/640x640.jpg");
  assert.equal(coverUrl("7d3b9810-5634-400c-ad89-50609e0ce80"), null);
});

await test("a song travels to the website with every field, an absent one as null", () => {
  const [f] = foundIn({ data: { items: [{ id: 7, title: "T", duration: 1 }] } });
  assert.equal(JSON.stringify(f),
    '{"catalogId":"tidal:7","title":"T","artist":"","album":"","durationMs":1000,"isrc":null,"cover":null,"explicit":false}');
});

// --- Against a stand-in TrypT HiFi --------------------------------------------

await test("search asks /search/ for 25 and hands back the streamable songs", async () => {
  const { calls, fetchImpl } = fakeFetch(() => json({ version: "2.9", data: { items: [
    item(10, "Around the World", "Daft Punk", 429, { isrc: "GBDUW9700001" }),
    item(11, "Hidden", "Nobody", 100, { allowStreaming: false }),
  ] } }));
  const catalog = new Catalog(`${BASE}/`, undefined, fetchImpl);
  const found = await catalog.search("daft punk & co");
  assert.equal(calls.length, 1);
  const { url, init } = calls[0];
  assert.equal(url.origin + url.pathname, `${BASE}/search/`);
  assert.equal(url.searchParams.get("s"), "daft punk & co");
  assert.equal(url.searchParams.get("limit"), "25");
  assert.ok(init.signal instanceof AbortSignal, "every ask has a timeout");
  assert.equal(init.redirect, "manual");
  assert.deepEqual(found.map((f) => [f.catalogId, f.title, f.isrc]), [["tidal:10", "Around the World", "GBDUW9700001"]]);
});

await test("search reads a top-level items list and keeps at most 25", async () => {
  const many = Array.from({ length: 40 }, (_, i) => item(i + 1, `Song ${i + 1}`, "Band", 100));
  const { fetchImpl } = fakeFetch(() => json({ items: many }));
  const found = await new Catalog(BASE, undefined, fetchImpl).search("song");
  assert.equal(found.length, MAX_RESULTS);
  assert.equal(found[24].catalogId, "tidal:25");
});

await test("search's failures read as the Rust server's", async () => {
  const quiet = console.error;
  console.error = () => {};
  try {
    const down = fakeFetch(() => { throw new TypeError("connection refused"); });
    await assert.rejects(new Catalog(BASE, undefined, down.fetchImpl).search("x"),
      (e: unknown) => e instanceof CatalogError && e.message === "the catalogue did not answer");
    const refusing = fakeFetch(() => json({ detail: "no" }, 503));
    await assert.rejects(new Catalog(BASE, undefined, refusing.fetchImpl).search("x"),
      { message: "the catalogue did not answer" });
    const garbled = fakeFetch(() => new Response("<html>oops</html>", { status: 200 }));
    await assert.rejects(new Catalog(BASE, undefined, garbled.fetchImpl).search("x"),
      { message: "the catalogue's answer was unreadable" });
  } finally {
    console.error = quiet;
  }
});

await test("X-Access-Key goes only when there is a password, and never in the address", async () => {
  const open = fakeFetch(() => json({ data: { items: [] } }));
  await new Catalog(BASE, undefined, open.fetchImpl).search("x");
  await new Catalog(BASE, "   ", open.fetchImpl).search("x");
  await Catalog.from({ TRYPT_HIFI_URL: BASE, TRYPT_HIFI_KEY: "" }, open.fetchImpl)!.search("x");
  for (const c of open.calls) assert.equal(c.headers.get("x-access-key"), null);

  const locked = fakeFetch((_, call) =>
    call.headers.get("x-access-key") === KEY ? json({ data: { items: [], url: "/stream/1" }, title: "T" }) : json({ error: "locked" }, 401));
  const catalog = Catalog.from({ TRYPT_HIFI_URL: BASE, TRYPT_HIFI_KEY: KEY }, locked.fetchImpl)!;
  await catalog.search("x");
  await catalog.streamUrl(1);
  await catalog.qobuzUrl(1);
  assert.equal(await catalog.isSong(1, "T"), true);
  assert.equal(await catalog.find("T", "A", 0), null);
  assert.equal(locked.calls.length, 5);
  for (const c of locked.calls) {
    assert.equal(c.headers.get("x-access-key"), KEY);
    assert.ok(!c.url.toString().includes(KEY));
  }
  // Without it, the locked server's 401 is what the website hears about.
  const keyless = new Catalog(BASE, undefined, locked.fetchImpl);
  await assert.rejects(keyless.streamUrl(1), { message: 'HTTP 401 Unauthorized: {"error":"locked"}' });
});

await test("the password follows a redirect on the catalogue, not one to another host", async () => {
  const { calls, fetchImpl } = fakeFetch((url) => {
    if (url.pathname === "/search/") return new Response(null, { status: 301, headers: { location: "/search/v2?s=x" } });
    if (url.pathname === "/search/v2") return new Response(null, { status: 302, headers: { location: "https://elsewhere.example/s" } });
    return json({ data: { items: [item(1, "Moved", "Band", 10)] } });
  });
  const found = await new Catalog(BASE, KEY, fetchImpl).search("x");
  assert.equal(found[0].title, "Moved");
  assert.deepEqual(calls.map((c) => [c.url.host, c.headers.get("x-access-key")]),
    [["hifi.example", KEY], ["hifi.example", KEY], ["elsewhere.example", null]]);
  // The same timeout covers every hop.
  assert.equal(calls[0].init.signal, calls[2].init.signal);
});

await test("streamUrl and qobuzUrl ask the download routes at quality 5", async () => {
  const { calls, fetchImpl } = fakeFetch((url) =>
    url.pathname === "/api/tidal/download-music"
      ? json({ success: true, data: { url: "/api/tidal/stream?token=abc" } })
      : json({ success: true, data: { url: "https://cdn.example/q.mp3?sig=1" } }));
  const catalog = new Catalog(`${BASE}/`, undefined, fetchImpl);
  assert.equal(await catalog.streamUrl(124215), `${BASE}/api/tidal/stream?token=abc`);
  assert.equal(await catalog.qobuzUrl(77), "https://cdn.example/q.mp3?sig=1");
  assert.deepEqual(calls.map((c) => [c.url.pathname, c.url.searchParams.get("track_id"), c.url.searchParams.get("quality")]),
    [["/api/tidal/download-music", "124215", "5"], ["/api/download-music", "77", "5"]]);
});

await test("streamUrl says why not, as the Rust server does", async () => {
  const answer = (r: () => Response) => new Catalog(BASE, undefined, fakeFetch(r).fetchImpl);
  await assert.rejects(answer(() => new Response("track not found", { status: 404 })).streamUrl(1),
    (e: unknown) => e instanceof CatalogError && e.message === "HTTP 404 Not Found: track not found");
  await assert.rejects(answer(() => new Response("x".repeat(500), { status: 500 })).streamUrl(1),
    { message: `HTTP 500 Internal Server Error: ${"x".repeat(200)}` });
  await assert.rejects(answer(() => new Response("é".repeat(300), { status: 200 })).streamUrl(1),
    { message: `unreadable: ${"é".repeat(200)}` });
  await assert.rejects(answer(() => json({ data: { url: "ftp://nope" } })).streamUrl(1),
    { message: 'no url in {"data":{"url":"ftp://nope"}}' });
  await assert.rejects(answer(() => json({ data: {} })).qobuzUrl(1), { message: 'no url in {"data":{}}' });
  await assert.rejects(answer(() => { throw new TypeError("connection refused"); }).streamUrl(1),
    { message: "did not answer: connection refused" });
  const cut = new Response(new ReadableStream({ start(c) { c.error(new Error("reset")); } }));
  await assert.rejects(answer(() => cut).streamUrl(1), { message: "answer cut off: reset" });
});

await test("isSong reads the title TIDAL names for the id", async () => {
  const titled = (body: unknown, status = 200) => {
    const f = fakeFetch(() => json(body, status));
    return { catalog: new Catalog(BASE, undefined, f.fetchImpl), calls: f.calls };
  };
  const { catalog, calls } = titled({ data: { id: 5, title: "One More Time" } });
  assert.equal(await catalog.isSong(5, "One More Time (Radio Edit)"), true);
  assert.equal(calls[0].url.pathname, "/info/");
  assert.equal(calls[0].url.searchParams.get("id"), "5");
  assert.equal(await titled({ title: "Blinding Lights" }).catalog.isSong(5, "Tricky Disco"), false);
  assert.equal(await titled({ data: {} }).catalog.isSong(5, "x"), null);
  // The status is not looked at, as in the Rust: a title named is a title named.
  assert.equal(await titled({ title: "Gone" }, 404).catalog.isSong(5, "Gone"), true);
  const garbled = new Catalog(BASE, undefined, fakeFetch(() => new Response("nope")).fetchImpl);
  assert.equal(await garbled.isSong(5, "x"), null);
  const down = new Catalog(BASE, undefined, fakeFetch(() => { throw new Error("down"); }).fetchImpl);
  assert.equal(await down.isSong(5, "x"), null);
});

await test("find searches by title and artist and picks the same recording", async () => {
  const { calls, fetchImpl } = fakeFetch(() => json({ data: { items: [
    item(1, "Roundabout", "Yes", 640, { version: "Live" }),
    item(2, "Roundabout", "Yes", 516, { isrc: "USAT20300001", version: "2003 Remaster" }),
    item(4, "Roundabout", "Yes", 519),
  ] } }));
  const catalog = new Catalog(BASE, undefined, fetchImpl);
  assert.equal(await catalog.find("Roundabout", "Yes", 519_500), 4);
  assert.equal(calls[0].url.searchParams.get("s"), "Roundabout Yes");
  assert.equal(await catalog.find("Roundabout", "Yes", 0, "usat20300001"), 2);
  assert.equal(await catalog.find("Roundabout", "Yes", 300_000, ""), null);
  await catalog.find("Roundabout", "", 0);
  assert.equal(calls[3].url.searchParams.get("s"), "Roundabout");
  const quiet = console.error;
  console.error = () => {};
  try {
    const down = new Catalog(BASE, undefined, fakeFetch(() => json({}, 502)).fetchImpl);
    assert.equal(await down.find("Roundabout", "Yes", 0), null);
  } finally {
    console.error = quiet;
  }
});

await test("the password is never in a log", async () => {
  const logged: string[] = [];
  const quiet = console.error;
  console.error = (...a: unknown[]) => { logged.push(a.map(String).join(" ")); };
  try {
    const catalog = new Catalog(BASE, KEY, fakeFetch(() => new Response("bad", { status: 200 })).fetchImpl);
    await assert.rejects(catalog.search("x"));
    await assert.rejects(new Catalog(BASE, KEY, fakeFetch(() => json({}, 500)).fetchImpl).search("x"));
  } finally {
    console.error = quiet;
  }
  assert.equal(logged.length, 2);
  for (const line of logged) assert.ok(!line.includes(KEY), line);
  assert.ok(!JSON.stringify(new Catalog(BASE, KEY)).includes(KEY));
});

await test("a file on the catalogue's own routes needs the password; a signed link or another host does not", async () => {
  const locked = fakeFetch((_, call) => call.headers.get("x-access-key") === KEY ? new Response("song", { headers: { "content-length": "4" } }) : json({ error: "locked" }, 401));
  const catalog = new Catalog(BASE, KEY, locked.fetchImpl);
  assert.equal(catalog.needsKey(`${BASE}/api/tidal/stream-file?track_id=1&quality=HIGH`), true);
  assert.equal(catalog.needsKey(`${BASE}/api/file?u=x&sig=y`), false);
  assert.equal(catalog.needsKey("https://cdn.example/song.m4a"), false);
  assert.equal(new Catalog(BASE, undefined, locked.fetchImpl).needsKey(`${BASE}/api/tidal/stream-file?track_id=1`), false);
  const r = await catalog.file(`${BASE}/api/tidal/stream-file?track_id=1&quality=HIGH`);
  assert.equal(await r.text(), "song");
  const call = locked.calls[0];
  assert.equal(call.headers.get("x-access-key"), KEY);
  assert.equal(call.url.searchParams.get("whole"), "1");
  assert.ok(!call.url.toString().includes(KEY));
});

console.log(`catalog: ${passed} passed${failed ? `, ${failed} failed` : ""}`);
if (failed) process.exit(1);

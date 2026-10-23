// The real hub in Cloudflare's own runtime, over real sockets: a listening
// party with a phone hosting and a website guest, a shared song fetched by
// range, and a Trypt Mania game from pick to results. Start the server without
// sign-in first (any name gets in), then run this:
//
//   npx wrangler dev --port 8799 --var SUPABASE_URL: --var TRYPT_HIFI_URL:
//   bun run e2e.ts            (or ROOMS=https://… bun run e2e.ts)

import assert from "node:assert/strict";

const BASE = process.env.ROOMS ?? "http://127.0.0.1:8799";
const WS = BASE.replace(/^http/, "ws") + "/ws";

type Msg = Record<string, any>;

class Phone {
  ws: WebSocket;
  inbox: Msg[] = [];
  waiters: { pred: (m: Msg) => boolean; done: (m: Msg) => void }[] = [];
  constructor() {
    this.ws = new WebSocket(WS);
    this.ws.addEventListener("message", (e) => {
      const m = JSON.parse(String(e.data));
      const w = this.waiters.findIndex((x) => x.pred(m));
      if (w >= 0) this.waiters.splice(w, 1)[0].done(m);
      else this.inbox.push(m);
    });
  }
  open() { return new Promise<void>((ok, bad) => { this.ws.addEventListener("open", () => ok()); this.ws.addEventListener("error", bad); }); }
  say(m: Msg) { this.ws.send(JSON.stringify(m)); }
  raw(text: string) { this.ws.send(text); }
  wait(pred: (m: Msg) => boolean, what: string, ms = 5000): Promise<Msg> {
    const i = this.inbox.findIndex(pred);
    if (i >= 0) return Promise.resolve(this.inbox.splice(i, 1)[0]);
    return new Promise((done, fail) => {
      const t = setTimeout(() => fail(new Error(`timed out waiting for ${what}`)), ms);
      this.waiters.push({ pred, done: (m) => { clearTimeout(t); done(m); } });
    });
  }
  t(type: string, ms?: number) { return this.wait((m) => m.t === type, type, ms); }
  close() { this.ws.close(); }
}

let passed = 0;
const step = async (name: string, fn: () => Promise<void>) => {
  try { await fn(); passed++; console.log(`ok  ${name}`); } catch (e) { console.error(`FAIL ${name}`); throw e; }
};

await step("health, info and CORS", async () => {
  assert.deepEqual(await (await fetch(`${BASE}/health`)).json(), { ok: true });
  const info = await fetch(`${BASE}/api/multiplayer/info`);
  assert.equal(info.headers.get("access-control-allow-origin"), "*");
  const body = (await info.json()) as Msg;
  assert.equal(body.success, true);
  assert.ok(body.data.features.includes("party"));
  assert.equal(body.data.service, "tryptmania-multiplayer");
  const pre = await fetch(`${BASE}/api/party/song?item=s1`, { method: "OPTIONS" });
  assert.equal(pre.status, 204);
  assert.match(pre.headers.get("access-control-allow-headers") ?? "", /X-Room-Key/);
  const links = (await (await fetch(`${BASE}/.well-known/assetlinks.json`)).json()) as Msg[];
  assert.equal(links[0].target.package_name, "tf.monotrypt.android");
  const invite = await fetch(`${BASE}/party/k7qd`, { redirect: "manual" });
  assert.equal(invite.headers.get("location"), "https://tryptify.com/party/K7QD");
});

await step("before hello: ping answers, anything else is refused, bad JSON is said so", async () => {
  const p = new Phone(); await p.open();
  p.say({ t: "ping", c: 5 });
  const pong = await p.t("pong");
  assert.equal(pong.c, 5);
  p.say({ t: "rooms" });
  assert.equal((await p.t("error")).message, "say hello first");
  p.raw("{nope");
  assert.match((await p.t("error")).message, /^bad message: /);
  p.close();
});

const host = new Phone(); await host.open();
const guest = new Phone(); await guest.open();
let code = "", hostKey = "", token = "", itemId = "";

await step("a phone starts a party", async () => {
  host.say({ t: "hello", name: "Felix", client: "tryptify-android" });
  const w = await host.t("welcome");
  assert.match(w.id, /^p\d+$/);
  assert.equal(w.key.length, 32);
  assert.equal(w.resumed, false);
  hostKey = w.key;
  host.say({ t: "create", name: "Friday", kind: "party" });
  const party = await host.t("party");
  code = party.party.code;
  assert.match(code, /^[A-HJ-NP-Z2-9]{4}$/);
  assert.equal(party.party.hostId, w.id);
});

await step("a website guest joins by code alone", async () => {
  guest.say({ t: "hello", name: "Web friend", guest: true, party: code.toLowerCase() });
  const w = await guest.t("welcome");
  const joined = await guest.t("joined");
  assert.equal(joined.code, code);
  token = joined.songToken;
  assert.ok(token.length >= 16);
  const seen = await host.wait((m) => m.t === "party" && m.party.members.length === 2, "host sees the guest");
  assert.ok(seen.party.members.some((m: Msg) => m.id === w.id && m.guest === true && m.web === true));
  // A guest may not steer.
  guest.say({ t: "pause" });
  assert.match((await guest.t("error")).message, /^guests can listen/);
});

await step("chat goes round, and a wrong code is refused", async () => {
  guest.say({ t: "chat", text: "hello there" });
  const line = await host.wait((m) => m.t === "line" && m.line.kind === "say", "chat line");
  assert.equal(line.line.text, "hello there");
  const other = new Phone(); await other.open();
  other.say({ t: "hello", name: "Lost", guest: true, party: "ZZZZ" });
  assert.equal((await other.t("error")).message, "no party with that code");
  other.close();
});

const song = new Uint8Array(300_000).map((_, i) => (i * 7) & 255);

await step("the host queues and shares a song; the guest fetches it by range", async () => {
  host.say({ t: "queueAdd", ref: "r1", item: { title: "Avira", artist: "Oxidaksi", album: "Avira", durationMs: 200_000, share: true } });
  const added = await host.t("added");
  assert.equal(added.ref, "r1");
  itemId = added.itemId;
  const put = await fetch(`${BASE}/api/party/song?item=${itemId}&ext=flac`, { method: "PUT", headers: { "x-room-key": hostKey, "content-length": String(song.length) }, body: song });
  assert.equal(put.status, 200, await put.clone().text());
  assert.deepEqual(await put.json(), { success: true, bytes: song.length });
  const ready = await guest.wait((m) => m.t === "party" && (m.party.now?.shared?.ready || m.party.queue.some((i: Msg) => i.shared?.ready)), "shared and ready");
  assert.ok(ready);
  const whole = await fetch(`${BASE}/api/party/song?item=${itemId}&token=${token}`);
  assert.equal(whole.status, 200);
  assert.equal(whole.headers.get("content-type"), "audio/flac");
  assert.equal(whole.headers.get("access-control-allow-origin"), "*");
  assert.deepEqual(new Uint8Array(await whole.arrayBuffer()), song);
  const part = await fetch(`${BASE}/api/party/song?item=${itemId}&token=${token}`, { headers: { range: "bytes=1000-1999" } });
  assert.equal(part.status, 206);
  assert.equal(part.headers.get("content-range"), `bytes 1000-1999/${song.length}`);
  assert.deepEqual(new Uint8Array(await part.arrayBuffer()), song.slice(1000, 2000));
  const nobody = await fetch(`${BASE}/api/party/song?item=${itemId}&token=nottoken`);
  assert.equal(nobody.status, 404);
});

await step("the host pauses and seeks; everyone gets the clock", async () => {
  host.say({ t: "pause" });
  const paused = await guest.wait((m) => m.t === "party" && m.party.playback.paused === true, "paused");
  assert.equal(paused.party.playback.itemId, itemId);
  host.say({ t: "seek", positionMs: 60_000 });
  const sought = await guest.wait((m) => m.t === "party" && m.party.playback.positionMs === 60_000, "seek");
  assert.ok(sought);
});

await step("a phone that drops keeps its seat and gets it back with resume", async () => {
  host.close();
  await guest.wait((m) => m.t === "party" && m.party.members.some((x: Msg) => x.away === true), "host away");
  const back = new Phone(); await back.open();
  back.say({ t: "hello", name: "Felix", client: "tryptify-android", resume: hostKey });
  const w = await back.t("welcome");
  assert.equal(w.resumed, true);
  assert.equal(w.key, hostKey);
  await guest.wait((m) => m.t === "party" && m.party.members.every((x: Msg) => !x.away), "host back");
  back.say({ t: "endParty" });
  assert.equal((await guest.t("left")).reason, "the host ended the party");
  const gone = await fetch(`${BASE}/api/party/song?item=${itemId}&token=${token}`);
  assert.equal(gone.status, 404);
  back.close();
  guest.close();
});

await step("a Trypt Mania game: pick, have, ready, start, frames, results", async () => {
  const a = new Phone(); await a.open();
  const b = new Phone(); await b.open();
  a.say({ t: "hello", name: "Ann" }); const wa = await a.t("welcome");
  b.say({ t: "hello", name: "Ben" }); const wb = await b.t("welcome");
  a.say({ t: "create", name: "Night" });
  const room = await a.t("room");
  const rcode = room.room.code;
  assert.equal(room.room.chart, null);
  assert.equal(room.room.startAt, null);
  b.say({ t: "rooms" });
  const list = await b.t("rooms");
  assert.ok(list.rooms.some((r: Msg) => r.code === rcode && r.host === "Ann"));
  b.say({ t: "join", code: rcode.toLowerCase() });
  await a.wait((m) => m.t === "room" && m.room.players.length === 2, "b joined");
  a.say({ t: "pick", chart: { hash: "h1", title: "Song", artist: "Band", duration: 0.5, bpm: 150, notes: "#TITLE:Song;" } });
  const chart = await b.t("chart");
  assert.equal(chart.chart.notes, "#TITLE:Song;");
  // The host shares the chart's song; the other phone fetches it.
  const bytes = new Uint8Array(1000).fill(9);
  const put = await fetch(`${BASE}/api/multiplayer/song?hash=h1&ext=mp3`, { method: "PUT", headers: { "x-room-key": wa.key, "content-length": "1000" }, body: bytes });
  assert.equal(put.status, 200, await put.clone().text());
  const shared = await b.wait((m) => m.t === "room" && m.room.sharedSong, "sharedSong");
  assert.deepEqual(shared.room.sharedSong, { hash: "h1", bytes: 1000, ext: "mp3" });
  const got = await fetch(`${BASE}/api/multiplayer/song?hash=h1`, { headers: { "x-room-key": wb.key } });
  assert.equal(got.headers.get("x-song-ext"), "mp3");
  assert.equal((await got.arrayBuffer()).byteLength, 1000);
  const notHost = await fetch(`${BASE}/api/multiplayer/song?hash=h1&ext=mp3`, { method: "PUT", headers: { "x-room-key": wb.key, "content-length": "3" }, body: new Uint8Array(3) });
  assert.equal(notHost.status, 403);
  a.say({ t: "have", audio: true }); b.say({ t: "have", audio: true });
  // Starting before everyone with the song is ready is refused by name.
  a.say({ t: "start" });
  assert.match((await a.t("error")).message, /is not ready$/);
  a.say({ t: "ready", ready: true }); b.say({ t: "ready", ready: true });
  await a.wait((m) => m.t === "room" && m.room.players.every((p: Msg) => p.ready), "both ready");
  a.say({ t: "start" });
  const start = await b.t("start");
  assert.equal(start.hash, "h1");
  assert.ok(start.at > Date.now() + 2000);
  a.say({ t: "frame", live: { score: 100, combo: 3, acc: 0.9, hp: 1, pos: 10 } });
  const frames = await b.wait((m) => m.t === "frames" && m.players.some(([id, l]: [string, Msg]) => id === wa.id && l.score === 100), "frames");
  assert.ok(frames);
  a.say({ t: "finish", result: { score: 500, acc: 0.95, maxCombo: 9, grade: "A", failed: false, judgements: {} } });
  b.say({ t: "finish", result: { score: 700, acc: 0.97, maxCombo: 12, grade: "S", failed: false, judgements: {} } });
  const results = await a.t("results");
  assert.deepEqual(results.ranking.map((r: Msg) => r.name), ["Ben", "Ann"]);
  a.close(); b.close();
});

console.log(`e2e: ${passed} passed`);
process.exit(0);

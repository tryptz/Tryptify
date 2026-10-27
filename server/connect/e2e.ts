// Tryptify Connect end to end, in Cloudflare's own runtime: a phone and a
// browser of one account, and a stranger on another. Start the room first:
//
//   npm run dev                 # wrangler dev with dev tokens, on :8787
//   CONNECT=ws://127.0.0.1:8787 bun run e2e.ts

import assert from "node:assert/strict";

const BASE = process.env.CONNECT ?? "ws://127.0.0.1:8787";
const A = "11111111-1111-4111-8111-111111111111", B = "22222222-2222-4222-8222-222222222222";

type Msg = Record<string, any>;
/** A device: its socket, and the messages it has had, waited on by type. */
function device(id: string, name: string, kind: string, account: string, how: "header" | "protocol") {
  const url = `${BASE}/v1/connect?device=${id}&name=${encodeURIComponent(name)}&kind=${kind}`;
  const ws = how === "header"
    ? new WebSocket(url, { headers: { authorization: `Bearer dev.${account}` } } as any)
    : new WebSocket(url, ["tryptify-connect", `bearer.dev.${account}`]);
  const inbox: (Msg | string)[] = [];
  const waiters: [(m: any) => boolean, (m: any) => void][] = [];
  const closed = new Promise<number>((r) => ws.addEventListener("close", (e) => r((e as CloseEvent).code)));
  ws.addEventListener("message", (e) => {
    const m = String(e.data) === "pong" ? "pong" : JSON.parse(String(e.data));
    const w = waiters.findIndex(([f]) => f(m));
    if (w >= 0) waiters.splice(w, 1)[0][1](m); else inbox.push(m);
  });
  const next = (f: (m: any) => boolean, ms = 3000) => new Promise<any>((resolve, reject) => {
    const i = inbox.findIndex(f);
    if (i >= 0) return resolve(inbox.splice(i, 1)[0]);
    const t = setTimeout(() => reject(new Error(`${name}: timed out`)), ms);
    waiters.push([f, (m) => { clearTimeout(t); resolve(m); }]);
  });
  const open = new Promise<void>((r, j) => { ws.addEventListener("open", () => r()); ws.addEventListener("error", () => j(new Error(`${name} could not connect`))); });
  return { ws, open, closed, next, type: (t: string) => next((m) => m && m.type === t), send: (m: Msg) => ws.send(JSON.stringify(m)), inbox };
}
const step = (s: string) => console.log("·", s);

// Signed out: refused.
const nobody = new WebSocket(`${BASE}/v1/connect?device=nobody0001`);
assert.equal(await new Promise((r) => { nobody.onerror = () => r("refused"); nobody.onopen = () => r("open"); }), "refused");
step("no sign-in: refused");

const phone = device("phone00001", "Pixel 8", "android", A, "header");
await phone.open;
const hello = await phone.type("welcome");
assert.equal(hello.you, "phone00001");
step(`phone in: ${hello.devices.map((d: Msg) => d.name).join(", ")}`);

const web = device("webweb0001", "Chrome on Windows", "web", A, "protocol");
await web.open;
assert.equal(web.ws.protocol, "tryptify-connect");
const w0 = await web.type("welcome");
assert.deepEqual(w0.devices.map((d: Msg) => d.name).sort(), ["Chrome on Windows", "Pixel 8"]);
assert.deepEqual((await phone.type("devices")).devices.map((d: Msg) => d.id).sort(), ["phone00001", "webweb0001"]);
step("browser in (subprotocol answered): both see both");

// The keep-alive is answered at the edge.
web.ws.send("ping");
assert.equal(await web.next((m) => m === "pong"), "pong");
step("ping → pong");

// The phone plays: the browser hears it once, not every second.
const track = { src: "hifi", id: "12345", title: "Song", artist: "Artist", album: "Album", duration: 200, quality: "FLAC 16/44.1",
  cover: "https://resources.tidal.com/images/a/b/320x320.jpg" };
phone.send({ type: "state", playing: true, track, position: 30, rate: 1 });
const now = (await web.type("now")).now;
assert.equal(now.device, "phone00001");
assert.equal(now.track.title, "Song");
assert.equal(now.name, "Pixel 8");
const marked = await web.type("devices");
assert.equal(marked.devices.find((d: Msg) => d.id === "phone00001").active, true);
step("phone plays → browser sees “Song” on Pixel 8");

// A stranger's room is another room.
const stranger = device("strange001", "Not yours", "web", B, "protocol");
await stranger.open;
const s0 = await stranger.type("welcome");
assert.equal(s0.now, null);
assert.deepEqual(s0.devices.map((d: Msg) => d.id), ["strange001"]);
step("another account sees none of it");

// Remote control from the browser.
web.send({ type: "command", to: "phone00001", action: "pause" });
assert.equal((await phone.type("command")).action, "pause");
web.send({ type: "command", to: "phone00001", action: "seek", value: 95 });
const seek = await phone.type("command");
assert.deepEqual([seek.action, seek.value, seek.from], ["seek", 95, "webweb0001"]);
step("browser → phone: pause, seek 95");

// "Play here" on the browser: the phone is asked, hands over its place and queue.
web.send({ type: "transfer", to: "webweb0001" });
const release = await phone.type("release");
assert.equal(release.to, "webweb0001");
assert.equal(release.toName, "Chrome on Windows");
phone.send({ type: "handoff", to: "webweb0001", now: { playing: true, track, position: 96.5, rate: 1 },
  queue: [{ src: "hifi", id: "2", title: "Next" }, { src: "nope", id: "x" }] });
const take = await web.type("takeover");
assert.equal(take.from, "phone00001");
assert.equal(take.now.position, 96.5);
assert.deepEqual(take.queue.map((t: Msg) => t.title), ["Next"]);
step("transfer: phone released at 96.5 s, browser took over with the queue");

// The browser plays now; a pause from the old phone does not steal it back.
web.send({ type: "state", playing: true, track, position: 96.5, rate: 1 });
assert.equal((await phone.type("now")).now.device, "webweb0001");
phone.send({ type: "state", playing: false, track, position: 96.5, rate: 1 });
await Bun.sleep(150);
const later = device("later00001", "Laptop", "web", A, "protocol");
await later.open;
const lw = await later.type("welcome");
assert.equal(lw.now.device, "webweb0001");
assert.ok(lw.now.position >= 96.5, "position runs on while playing");
step(`a device joining later sees the browser playing, at ${lw.now.position.toFixed(2)} s`);

// One connection per device: a second phone connection replaces the first.
const phone2 = device("phone00001", "Pixel 8", "android", A, "header");
await phone2.open;
assert.equal(await phone.closed, 4000);
step("same device again: the old connection is closed (4000)");

// Leaving: the others hear it.
later.ws.close(1000);
const left = await web.next((m) => m.type === "devices" && !m.devices.some((d: Msg) => d.id === "later00001"));
step(`laptop left: ${left.devices.map((d: Msg) => d.name).join(", ")}`);

// Junk is ignored, not fatal.
web.ws.send("{not json");
web.send({ type: "command", to: "phone00001", action: "format-disk" });
web.ws.send("ping");
await web.next((m) => m === "pong");
step("junk ignored; the room still answers");

// The device playing leaves: its song stops at the second it reached, not
// "playing" on to the end of the song for everyone else.
web.ws.close(1000);
const stopped = (await phone2.next((m) => m.type === "now" && m.now.device === "webweb0001" && m.now.playing === false)).now;
assert.ok(stopped.position >= 96.5 && stopped.position < 120, "stopped near where it was");
step(`browser left while playing: stopped at ${stopped.position.toFixed(2)} s`);

for (const d of [phone2, stranger]) d.ws.close(1000);
console.log("connect e2e: all pass");
process.exit(0);

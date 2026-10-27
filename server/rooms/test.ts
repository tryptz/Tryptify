// The rooms server's own pure parts: reading what a phone says, file names,
// byte ranges. The rules have their own tests (party.test.ts, room.test.ts,
// catalog.test.ts); e2e.ts runs the real hub in Cloudflare's runtime.
//
//   bun run test.ts

import assert from "node:assert/strict";
import { byteRange, cleanExt, parseClient } from "./src/protocol";

let passed = 0;
const test = (name: string, fn: () => void) => {
  try { fn(); passed++; } catch (e) { console.error(`FAIL ${name}`); throw e; }
};

test("a hello takes its defaults, as serde gave them", () => {
  assert.deepEqual(parseClient('{"t":"hello","name":"Felix"}'), { t: "hello", name: "Felix", client: "", token: "", resume: "", guest: false, party: "" });
  assert.deepEqual(parseClient('{"t":"hello","name":"Web","guest":true,"party":"k7qd","extra":1}'), { t: "hello", name: "Web", client: "", token: "", resume: "", guest: true, party: "k7qd" });
});

test("required fields are required, and types are checked", () => {
  for (const bad of ['{"t":"hello"}', '{"t":"ping"}', '{"t":"ping","c":-1}', '{"t":"ping","c":1.5}', '{"t":"join"}', '{"t":"seek","positionMs":"10"}', '{"t":"nope"}', "[]", "not json", '{"t":"create","kind":"disco"}']) {
    const r = parseClient(bad);
    assert.ok("error" in r && r.error.startsWith("bad message: "), bad);
  }
});

test("a party's messages read as the app sends them", () => {
  assert.deepEqual(parseClient('{"t":"create","name":"Friday","kind":"party"}'), { t: "create", name: "Friday", kind: "party" });
  assert.deepEqual(parseClient('{"t":"create"}'), { t: "create", name: "", kind: "game" });
  assert.deepEqual(parseClient('{"t":"queueAdd","ref":"r1","item":{"title":"Song","durationMs":200000,"isrc":"US1","share":true}}'), {
    t: "queueAdd", ref: "r1",
    item: { title: "Song", artist: "", album: "", durationMs: 200000, isrc: "US1", catalogId: null, audioHash: null, cover: null, share: true },
  });
  assert.deepEqual(parseClient('{"t":"skipVote"}'), { t: "skipVote", itemId: null, vote: true });
  assert.deepEqual(parseClient('{"t":"setRate","rate":1.25}'), { t: "setRate", rate: 1.25, keepPitch: true });
  assert.deepEqual(parseClient('{"t":"queueMove","id":"s2","to":0}'), { t: "queueMove", id: "s2", to: 0 });
});

test("file extensions are kept safe", () => {
  assert.equal(cleanExt(".FLAC"), "flac");
  assert.equal(cleanExt("m4a"), "m4a");
  assert.equal(cleanExt("../x"), "bin");
  assert.equal(cleanExt(""), "bin");
  assert.equal(cleanExt("toolong"), "bin");
});

test("byte ranges, as the Rust server served them", () => {
  assert.deepEqual(byteRange("bytes=0-99", 1000), [0, 99]);
  assert.deepEqual(byteRange("bytes=900-", 1000), [900, 999]);
  assert.deepEqual(byteRange("bytes=-100", 1000), [900, 999]);
  assert.deepEqual(byteRange("bytes=0-5000", 1000), [0, 999]);
  assert.equal(byteRange("bytes=1000-", 1000), null);
  assert.equal(byteRange("bytes=0-1,5-6", 1000), null);
  assert.equal(byteRange("bytes=-0", 1000), null);
  assert.equal(byteRange("items=0-1", 1000), null);
});

console.log(`rooms: ${passed} passed`);

// SPDX-License-Identifier: GPL-3.0-or-later
//
// The room rules, ported from tryptify-party's `src/room.rs` with its unit
// tests, the rule-level half of `tests/flow.rs`, and the wire format held
// to what serde_json writes and reads. The SERDE goldens below were printed
// by the Rust code itself. Run: bun run room.test.ts

import assert from "node:assert/strict";
import {
  type Chart,
  type Finished,
  type Live,
  type Out,
  type Player,
  type RoomView,
  type ServerMsg,
  type SharedSong,
  CHAT_MAX_CHARS,
  GRACE_MS,
  LEAD_MS,
  MAX_NOTES_BYTES,
  MAX_PLAYERS,
  Room,
  chatOut,
  defaultFinished,
  defaultLive,
  encode,
  formatF64,
  listRooms,
  newCode,
  newPlayer,
  normaliseCode,
  parseChart,
  parseFinished,
  parseLive,
  parseRoomClientMsg,
  rustTrim,
} from "./src/room.ts";

let passed = 0;
let failed = 0;

function test(name: string, fn: () => void) {
  try {
    fn();
    passed++;
  } catch (e) {
    failed++;
    console.error(`FAIL ${name}\n`, e);
  }
}

// ------------------------------------------------------------ helpers, as room.rs's tests have them

function chart(over: Partial<Chart> = {}): Chart {
  return {
    hash: "abc",
    title: "Song",
    artist: "Band",
    difficulty: "Hard",
    duration: 120.0,
    bpm: 150.0,
    notes: "#TITLE:Song;",
    ...over,
  };
}

function room(): Room {
  const r = new Room("ABCD", "", newPlayer("h", "Host"));
  ok(r.join(newPlayer("g", "Guest")));
  return r;
}

function ok(r: { ok: true; out: Out } | { ok: false; error: string }): Out {
  if (!r.ok) throw new Error(`expected ok, got: ${r.error}`);
  return r.out;
}

type Handled = ReturnType<Room["handle"]>;
function handled(r: Handled): Extract<Handled, { ok: true }> {
  if (!r.ok) throw new Error(`expected ok, got: ${r.error}`);
  return r;
}

function err(r: { ok: true; out: Out } | { ok: false; error: string }): string {
  if (r.ok) throw new Error("expected an error");
  return r.error;
}

function has(out: Out, pred: (m: ServerMsg) => boolean): boolean {
  return out.some((o) => pred(o.msg));
}

function find<T extends ServerMsg["t"]>(out: Out, t: T): Extract<ServerMsg, { t: T }> | undefined {
  return out.find((o) => o.msg.t === t)?.msg as Extract<ServerMsg, { t: T }> | undefined;
}

function live(over: Partial<Live> = {}): Live {
  return { ...defaultLive(), ...over };
}

function fin(over: Partial<Finished> = {}): Finished {
  return { ...defaultFinished(), ...over };
}

function song(hash: string): SharedSong {
  return { hash, bytes: 1234, ext: "flac" };
}

/** Everyone in with the song and ready, then started at [now]. */
function started(r: Room, now = 0, ids = ["h", "g"]) {
  ok(r.pick("h", chart()));
  for (const id of ids) {
    r.have(id, true);
    ok(r.ready(id, true));
  }
  ok(r.start("h", now));
}

// ------------------------------------------------------------ room.rs's unit tests

test("a room is named after its host and fills to sixteen", () => {
  const r = room();
  assert.equal(r.name, "Host's room");
  for (let i = 0; i < 14; i++) ok(r.join(newPlayer(`p${i}`, "P")));
  assert.equal(r.players.length, MAX_PLAYERS);
  assert.equal(err(r.join(newPlayer("x", "X"))), "the room is full");
  assert.equal(err(r.join(newPlayer("h", "Again"))), "already in the room");
});

test("only the host picks and starts", () => {
  const r = room();
  assert.equal(err(r.pick("g", chart())), "only the host can do that");
  assert.equal(err(r.start("g", 0)), "only the host can do that");
  const out = ok(r.pick("h", chart()));
  // Everyone gets the notes, then the room without them.
  assert.equal(out[0].to, "all");
  assert.equal(out[0].msg.t, "chart");
  assert.equal((out[0].msg as { chart: Chart }).chart.notes, "#TITLE:Song;");
  assert.equal(out[1].to, "all");
  assert.equal(out[1].msg.t, "room");
  const view = (out[1].msg as { room: RoomView }).room;
  assert.equal(view.chart!.notes, undefined);
  assert.equal("notes" in view.chart!, false);
});

test("ready needs the song and start needs everyone ready", () => {
  const r = room();
  assert.equal(err(r.ready("g", true)), "no chart is picked");
  ok(r.pick("h", chart()));
  assert.equal(err(r.ready("g", true)), "you do not have this song");
  r.have("g", true);
  ok(r.ready("g", true));
  // The host picked it from their own library; still, they say so and ready up like anyone.
  assert.equal(err(r.start("h", 1000)), "you do not have this song");
  r.have("h", true);
  assert.equal(err(r.start("h", 1000)), "Host is not ready");
  ok(r.ready("h", true));
  const out = ok(r.start("h", 1000));
  assert.ok(has(out, (m) => m.t === "start" && m.at === 1000 + LEAD_MS && m.hash === "abc"));
  assert.equal(r.state, "playing");
  assert.equal(err(r.start("h", 2000)), "a song is playing");
  assert.equal(err(r.pick("h", chart())), "a song is playing");
});

test("a player without the song watches", () => {
  const r = room();
  ok(r.pick("h", chart()));
  r.have("h", true);
  r.have("g", false);
  ok(r.ready("h", true));
  ok(r.start("h", 0));
  assert.ok(r.player("g")!.spectating);
  // Their frames are ignored, and the results come from the host alone.
  r.frame("g", live({ score: 99 }));
  assert.equal(r.player("g")!.live.score, 0);
  const out = r.finish("h", fin({ score: 500 }), 10);
  assert.ok(has(out, (m) => m.t === "results" && m.ranking.length === 1 && m.ranking[0].id === "h"));
  assert.equal(r.state, "results");
});

test("results rank by score with fails last", () => {
  const r = room();
  ok(r.join(newPlayer("x", "Third")));
  started(r, 0, ["h", "g", "x"]);
  r.frame("g", { score: 10, combo: 3, acc: 0.5, hp: 1.0, pos: 100 });
  const frames = r.frames();
  assert.ok(frames.t === "frames" && frames.players.length === 3);
  const none = r.finish("g", fin({ score: 900, acc: 0.9 }), 1);
  assert.ok(!has(none, (m) => m.t === "results"));
  r.finish("x", fin({ score: 950, failed: true }), 2);
  const out = r.finish("h", fin({ score: 700, acc: 0.95 }), 3);
  const ranking = find(out, "results")!.ranking;
  assert.deepEqual(
    ranking.map((p) => [p.rank, p.id]),
    [
      [1, "g"],
      [2, "h"],
      [3, "x"],
    ],
  );
  // Back in the lobby's hands: nobody ready, and the host may pick again.
  assert.ok(r.players.every((p) => !p.ready));
  ok(r.pick("h", chart()));
  assert.equal(r.state, "lobby");
});

test("leaving passes the host and ends a song left alone", () => {
  const r = room();
  started(r);
  r.finish("g", fin({ score: 1 }), 1);
  const out = r.leave("h", 2);
  assert.equal(r.host, "g");
  assert.ok(has(out, (m) => m.t === "results" && m.ranking.length === 1));
  assert.equal(r.state, "results");
  r.leave("g", 3);
  assert.ok(r.isEmpty());
});

test("a song times out with the last scores", () => {
  const r = room();
  started(r);
  r.frame("g", { score: 42, combo: 7, acc: 0.8, hp: 0.5, pos: 5000 });
  assert.equal(r.timeout(LEAD_MS + 120_000).length, 0);
  const out = r.timeout(LEAD_MS + 120_000 + GRACE_MS);
  const ranking = find(out, "results")!.ranking;
  assert.equal(ranking[0].id, "g");
  assert.equal(ranking[0].result.score, 42);
  assert.equal(ranking[0].result.maxCombo, 7);
});

test("a kick tells the kicked and the room", () => {
  const r = room();
  assert.equal(err(r.kick("g", "h", 0)), "only the host can do that");
  const out = ok(r.kick("h", "g", 0));
  assert.equal(out[0].to, "g");
  assert.equal(out[0].msg.t, "left");
  assert.equal(r.player("g"), undefined);
});

test("joining a song in play hands over the chart and the start", () => {
  const r = room();
  started(r);
  const out = ok(r.join(newPlayer("l", "Late")));
  assert.equal(out[0].to, "l");
  assert.equal(out[0].msg.t, "chart");
  assert.equal(out[1].to, "l");
  assert.ok(out[1].msg.t === "start" && out[1].msg.at === LEAD_MS);
  assert.ok(r.player("l")!.spectating);
});

test("only the host shares and only the picked chart's song", () => {
  const r = room();
  // Nothing picked: no song to share.
  assert.ok(!r.share("h", song("abc"), "/s/a").ok);
  ok(r.pick("h", chart()));
  assert.equal(err(r.share("g", song("abc"), "/s/a")), "only the host can do that");
  assert.equal(err(r.share("h", song("other"), "/s/a")), "that is not the picked chart's song");
  const out = ok(r.share("h", song("abc"), "/s/a"));
  // The room hears the song is there, with its size and type.
  assert.ok(has(out, (m) => m.t === "room" && m.room.sharedSong?.bytes === 1234 && m.room.sharedSong.ext === "flac"));
  // Every player in the room may fetch it; nobody else, and not another chart's.
  assert.equal(r.sharedFor("g", "abc")!.path, "/s/a");
  assert.equal(r.sharedFor("stranger", "abc"), null);
  assert.equal(r.sharedFor("g", "other"), null);
});

test("a new chart lets the last song go and the same chart keeps it", () => {
  const r = room();
  ok(r.pick("h", chart()));
  ok(r.share("h", song("abc"), "/s/a"));
  // Shared again: the first file is let go, the second kept.
  ok(r.share("h", song("abc"), "/s/b"));
  assert.deepEqual(r.dropped, ["/s/a"]);
  r.dropped.length = 0;
  // The same chart picked again keeps its song.
  ok(r.pick("h", chart()));
  assert.notEqual(r.view().sharedSong, null);
  assert.deepEqual(r.dropped, []);
  // Another chart: the song goes, for the hub to delete.
  ok(r.pick("h", chart({ hash: "def" })));
  assert.equal(r.view().sharedSong, null);
  assert.deepEqual(r.takeDropped(), ["/s/b"]);
  assert.deepEqual(r.dropped, []);
});

// ------------------------------------------------------------ the rules tests/flow.rs drives over sockets

function msgOf(json: unknown) {
  const p = parseRoomClientMsg(json);
  if (p.ok !== true) throw new Error(`expected a room message: ${JSON.stringify(p)}`);
  return p.msg;
}

test("flow: two phones play a song", () => {
  let now = 1_800_000_000_000;
  const create = msgOf({ t: "create", name: "Friday" });
  assert.deepEqual(create, { t: "create", name: "Friday", kind: "game" });
  const { room: r, out: made } = Room.create("WXYZ", (create as { name: string }).name, newPlayer("p1", "Host"));
  assert.equal(made.length, 1);
  const madeView = (made[0].msg as { room: RoomView }).room;
  assert.equal(madeView.code.length, 4);
  assert.equal(madeView.hostId, "p1");
  assert.equal(r.name, "Friday");

  // Listed, and joined by its code in any case.
  const list = listRooms([r]);
  assert.ok(list.t === "rooms" && list.rooms[0].code === "WXYZ");
  const join = msgOf({ t: "join", code: "wxyz" }) as { code: string };
  assert.equal(normaliseCode(join.code), r.code);
  const joined = ok(r.join(newPlayer("p2", "Guest")));
  assert.equal(find(joined, "room")!.room.players.length, 2);

  // Only the host picks; the guest is told so.
  const guestPick = msgOf({ t: "pick", chart: { hash: "h", title: "T", artist: "A" } });
  const refused = r.handle("p2", "Guest", guestPick, now);
  assert.ok(!refused.ok && refused.error === "only the host can do that");

  const pick = msgOf({
    t: "pick",
    chart: {
      hash: "h1",
      title: "Song",
      artist: "Band",
      difficulty: "Hard",
      duration: 1.0,
      bpm: 150.0,
      notes: "#TITLE:Song;\n#NOTES:...;",
    },
  });
  const picked = handled(r.handle("p1", "Host", pick, now));
  assert.ok(picked.ok);
  // Everyone gets the notes once, and the room without them.
  assert.equal(find(picked.out, "chart")!.chart.notes, "#TITLE:Song;\n#NOTES:...;");
  const afterPick = JSON.parse(encode(find(picked.out, "room")!));
  assert.equal(afterPick.room.chart.notes, undefined);
  assert.equal(afterPick.room.chart.hash, "h1");

  // Ready needs the song.
  const notYet = r.handle("p2", "Guest", msgOf({ t: "ready", ready: true }), now);
  assert.ok(!notYet.ok && notYet.error === "you do not have this song");
  for (const [id, name] of [
    ["p1", "Host"],
    ["p2", "Guest"],
  ]) {
    assert.ok(r.handle(id, name, msgOf({ t: "have", audio: true }), now).ok);
    assert.ok(r.handle(id, name, msgOf({ t: "ready", ready: true }), now).ok);
  }
  assert.ok(r.view().players.every((p) => p.ready));

  // Start: everyone plays from the same server moment, four seconds on.
  const start = handled(r.handle("p1", "Host", msgOf({ t: "start" }), now));
  assert.ok(start.ok && start.started && start.kicked === null);
  const s = find(start.out, "start")!;
  assert.equal(s.hash, "h1");
  assert.equal(s.at, now + 4_000);

  // Scores round the room while it plays.
  now += 2_500;
  const frame = msgOf({ t: "frame", live: { score: 1234, combo: 12, acc: 0.98, hp: 1.0, pos: 2500 } });
  const framed = handled(r.handle("p2", "Guest", frame, now));
  assert.ok(framed.ok && framed.out.length === 0);
  const frames = r.frames();
  assert.ok(frames.t === "frames" && frames.players.some(([id, l]) => id === "p2" && l.score === 1234));
  // On the wire, a frame is a pair, the way RoomProtocol.kt reads it.
  assert.equal(
    encode(frames),
    '{"t":"frames","players":[["p1",{"score":0,"combo":0,"acc":0.0,"hp":1.0,"pos":0}],' +
      '["p2",{"score":1234,"combo":12,"acc":0.98,"hp":1.0,"pos":2500}]]}',
  );

  // Both finish: ranked results, the room back at its results.
  const f1 = handled(r.handle(
    "p2",
    "Guest",
    msgOf({ t: "finish", result: { score: 1500, acc: 0.99, maxCombo: 40, grade: "S" } }),
    now,
  ));
  assert.ok(f1.ok && !has(f1.out, (m) => m.t === "results"));
  const f2 = handled(r.handle(
    "p1",
    "Host",
    msgOf({ t: "finish", result: { score: 900, acc: 0.9, maxCombo: 20, grade: "A" } }),
    now,
  ));
  assert.ok(f2.ok);
  const ranking = find(f2.out, "results")!.ranking;
  assert.equal(ranking[0].id, "p2");
  assert.equal(ranking[0].rank, 1);
  assert.equal(ranking[0].result.maxCombo, 40);
  assert.equal(ranking[1].id, "p1");
  assert.equal(find(f2.out, "room")!.room.state, "results");

  // Chat reaches everyone with the sender's name.
  const chat = handled(r.handle("p2", "Guest", msgOf({ t: "chat", text: "gg" }), now));
  assert.ok(chat.ok);
  assert.deepEqual(chat.out, [{ to: "all", msg: { t: "chat", id: "p2", name: "Guest", text: "gg" } }]);

  // The host leaves: the guest is host now, alone.
  const left = r.leave("p1", now);
  const alone = find(left, "room")!.room;
  assert.equal(alone.players.length, 1);
  assert.equal(alone.hostId, "p2");
});

test("flow: a dropped phone leaves its room, and an empty room goes with its song", () => {
  const r = new Room("ABCD", "", newPlayer("p1", "Host"));
  assert.equal(r.view().name, "Host's room");
  ok(r.join(newPlayer("p2", "Guest")));
  ok(r.pick("p1", chart()));
  ok(r.share("p1", song("abc"), "/s/a"));
  const out = r.leave("p2", 0);
  assert.equal(find(out, "room")!.room.players.length, 1);
  // The last one out: nothing to tell anyone, and the hub deletes the room's files.
  assert.deepEqual(r.leave("p1", 0), []);
  assert.ok(r.isEmpty());
  assert.deepEqual(r.takeFiles(), ["/s/a"]);
  assert.equal(r.shared, null);
});

test("flow: the host shares the song and a player without it fetches it", () => {
  const r = new Room("ABCD", "test", newPlayer("p1", "Host"));
  ok(r.join(newPlayer("p2", "Guest")));
  const picked = (hash: string) =>
    msgOf({
      t: "pick",
      chart: {
        hash,
        title: "Above Heaven",
        artist: "B-Front",
        difficulty: "Medium",
        duration: 180.0,
        bpm: 150.0,
        notes: "#TITLE:Above Heaven;",
      },
    });
  const p = handled(r.handle("p1", "Host", picked("h1"), 0));
  assert.ok(p.ok);
  assert.equal(JSON.parse(encode(find(p.out, "room")!)).room.sharedSong, null);
  // Only the host shares, and only the picked chart's song.
  assert.equal(err(r.share("p2", { hash: "h1", bytes: 300_000, ext: "flac" }, "/s/x")), "only the host can do that");
  assert.equal(err(r.share("p1", { hash: "other", bytes: 300_000, ext: "flac" }, "/s/x")), "that is not the picked chart's song");
  const shared = ok(r.share("p1", { hash: "h1", bytes: 300_000, ext: "flac" }, "/s/h1"));
  const view = JSON.parse(encode(find(shared, "room")!)).room;
  assert.deepEqual(view.sharedSong, { hash: "h1", bytes: 300_000, ext: "flac" });
  assert.deepEqual(r.sharedFor("p2", "h1"), { song: { hash: "h1", bytes: 300_000, ext: "flac" }, path: "/s/h1" });
  assert.equal(r.sharedFor("p3", "h1"), null);
  // Another chart: the song is gone, from the room and (by the hub) from storage.
  const again = handled(r.handle("p1", "Host", picked("h2"), 0));
  assert.ok(again.ok);
  assert.equal(find(again.out, "room")!.room.sharedSong, null);
  assert.equal(r.sharedFor("p2", "h1"), null);
  assert.deepEqual(r.takeDropped(), ["/s/h1"]);
  assert.deepEqual(r.takeFiles(), []);
});

// ------------------------------------------------------------ rules the Rust tests leave implicit

test("the rest of the refusals, word for word", () => {
  const r = room();
  assert.equal(err(r.kick("h", "h", 0)), "you cannot kick yourself");
  assert.equal(err(r.kick("h", "nobody", 0)), "no such player");
  assert.equal(err(r.start("h", 0)), "no chart is picked");
  assert.equal(err(r.pick("h", chart({ hash: "" }))), "the chart has no hash or title");
  assert.equal(err(r.pick("h", chart({ title: "" }))), "the chart has no hash or title");
  // The limit is in UTF-8 bytes, as Rust's len().
  assert.equal(err(r.pick("h", chart({ notes: "é".repeat(MAX_NOTES_BYTES / 2 + 1) }))), "the chart is too big");
  ok(r.pick("h", chart({ notes: "x".repeat(MAX_NOTES_BYTES) })));
  const party = r.handle("h", "Host", { t: "queueAdd" }, 0);
  assert.ok(!party.ok && party.error === "that is for a party, not a game room");
  const quiet = r.handle("h", "Host", { t: "ping" }, 0);
  assert.ok(quiet.ok && quiet.out.length === 0);
});

test("names are trimmed as Rust trims, and chat is cut to 300 characters", () => {
  assert.equal(new Room("A", "  Friday \u0085", newPlayer("h", "Host")).name, "Friday");
  assert.equal(new Room("A", " \t\n", newPlayer("h", "Host")).name, "Host's room");
  assert.equal(rustTrim("﻿x﻿"), "﻿x﻿");
  assert.deepEqual(chatOut("g", "Guest", "   "), []);
  const long = "😀".repeat(CHAT_MAX_CHARS + 5);
  const cut = chatOut("g", "Guest", long)[0].msg as { text: string };
  assert.equal(Array.from(cut.text).length, CHAT_MAX_CHARS);
  // Cut, not trimmed.
  assert.equal((chatOut("g", "Guest", " hi ")[0].msg as { text: string }).text, " hi ");
});

test("have false takes back ready, and a stranger's have or ready changes nothing", () => {
  const r = room();
  ok(r.pick("h", chart()));
  r.have("g", true);
  ok(r.ready("g", true));
  r.have("g", false);
  assert.equal(r.player("g")!.ready, false);
  assert.equal(r.player("g")!.hasSong, false);
  assert.deepEqual(r.have("nobody", true), []);
  assert.deepEqual(ok(r.ready("nobody", true)), []);
});

test("finishing twice, frames after finishing and moves outside a song are ignored", () => {
  const r = room();
  assert.deepEqual(r.finish("h", fin(), 0), []);
  assert.deepEqual(r.timeout(1e15), []);
  r.frame("h", live({ score: 5 }));
  assert.equal(r.player("h")!.live.score, 0);
  started(r);
  r.finish("h", fin({ score: 10, acc: 0.5 }), 1);
  r.frame("h", live({ score: 99 }));
  assert.equal(r.player("h")!.live.score, 10);
  assert.equal(r.player("h")!.live.acc, 0.5);
});

test("a view is a snapshot: later moves do not change what was sent", () => {
  const r = room();
  started(r);
  const before = r.view();
  r.frame("g", live({ score: 77 }));
  r.finish("g", fin({ score: 80, judgements: { w1: 1 } }), 1);
  assert.equal(before.players[1].live.score, 0);
  assert.equal(before.players[1].finished, null);
  const v = r.view();
  (v.players[1].finished!.judgements as Record<string, number>).w1 = 9;
  assert.deepEqual(r.player("g")!.finished!.judgements, { w1: 1 });
});

test("ties keep the order players joined in, and accuracy breaks a tied score", () => {
  const r = room();
  ok(r.join(newPlayer("x", "X")));
  started(r, 0, ["h", "g", "x"]);
  r.finish("h", fin({ score: 100, acc: 0.5 }), 1);
  r.finish("g", fin({ score: 100, acc: 0.5 }), 1);
  const out = r.finish("x", fin({ score: 100, acc: 0.9 }), 1);
  assert.deepEqual(
    find(out, "results")!.ranking.map((p) => p.id),
    ["x", "h", "g"],
  );
});

test("a timeout waits the song's length and the grace, from the start", () => {
  const r = room();
  ok(r.pick("h", chart({ duration: 1.5 })));
  for (const id of ["h", "g"]) {
    r.have(id, true);
    ok(r.ready(id, true));
  }
  ok(r.start("h", 1000));
  const end = 1000 + LEAD_MS + 1500 + GRACE_MS;
  assert.deepEqual(r.timeout(end - 1), []);
  assert.ok(has(r.timeout(end), (m) => m.t === "results"));
  assert.equal(r.state, "results");
});

test("room codes use the spoken alphabet and skip taken ones", () => {
  let n = 0;
  const seq = [0, 0, 0, 0, 1, 2, 3, 31];
  const code = newCode((c) => c === "AAAA", () => seq[n++]);
  assert.equal(code, "BCD9");
  assert.equal(normaliseCode("  abcd "), "ABCD");
});

test("rooms are listed by name, in code point order", () => {
  const a = new Room("AAAA", "b", newPlayer("1", "One"));
  const b = new Room("BBBB", "\u{1F600}", newPlayer("2", "Two"));
  const c = new Room("CCCC", "Ａ", newPlayer("3", "Three"));
  const list = listRooms([b, c, a]);
  assert.ok(list.t === "rooms");
  assert.deepEqual(
    list.rooms.map((s) => s.code),
    ["AAAA", "CCCC", "BBBB"],
  );
});

// ------------------------------------------------------------ the wire, exactly as serde_json writes it

const SERDE = {
  "VIEW0": "{\"t\":\"room\",\"room\":{\"code\":\"ABCD\",\"name\":\"Friday\",\"hostId\":\"h\",\"state\":\"lobby\",\"chart\":null,\"players\":[{\"id\":\"h\",\"name\":\"Host\",\"user\":null,\"ready\":false,\"hasSong\":null,\"live\":{\"score\":0,\"combo\":0,\"acc\":0.0,\"hp\":1.0,\"pos\":0},\"finished\":null,\"spectating\":false},{\"id\":\"g\",\"name\":\"Guest\",\"user\":\"u-1\",\"ready\":false,\"hasSong\":null,\"live\":{\"score\":0,\"combo\":0,\"acc\":0.0,\"hp\":1.0,\"pos\":0},\"finished\":null,\"spectating\":false}],\"startAt\":null,\"sharedSong\":null}}",
  "PICK": [
    [
      "All",
      "{\"t\":\"chart\",\"chart\":{\"hash\":\"abc\",\"title\":\"Song\",\"artist\":\"Band\",\"difficulty\":\"Hard\",\"duration\":120.0,\"bpm\":150.5,\"notes\":\"#TITLE:Song;\"}}"
    ],
    [
      "All",
      "{\"t\":\"room\",\"room\":{\"code\":\"ABCD\",\"name\":\"Friday\",\"hostId\":\"h\",\"state\":\"lobby\",\"chart\":{\"hash\":\"abc\",\"title\":\"Song\",\"artist\":\"Band\",\"difficulty\":\"Hard\",\"duration\":120.0,\"bpm\":150.5},\"players\":[{\"id\":\"h\",\"name\":\"Host\",\"user\":null,\"ready\":false,\"hasSong\":null,\"live\":{\"score\":0,\"combo\":0,\"acc\":0.0,\"hp\":1.0,\"pos\":0},\"finished\":null,\"spectating\":false},{\"id\":\"g\",\"name\":\"Guest\",\"user\":\"u-1\",\"ready\":false,\"hasSong\":null,\"live\":{\"score\":0,\"combo\":0,\"acc\":0.0,\"hp\":1.0,\"pos\":0},\"finished\":null,\"spectating\":false}],\"startAt\":null,\"sharedSong\":null}}"
    ]
  ],
  "START": [
    [
      "All",
      "{\"t\":\"start\",\"at\":5000,\"hash\":\"abc\"}"
    ],
    [
      "All",
      "{\"t\":\"room\",\"room\":{\"code\":\"ABCD\",\"name\":\"Friday\",\"hostId\":\"h\",\"state\":\"playing\",\"chart\":{\"hash\":\"abc\",\"title\":\"Song\",\"artist\":\"Band\",\"difficulty\":\"Hard\",\"duration\":120.0,\"bpm\":150.5},\"players\":[{\"id\":\"h\",\"name\":\"Host\",\"user\":null,\"ready\":true,\"hasSong\":true,\"live\":{\"score\":0,\"combo\":0,\"acc\":0.0,\"hp\":1.0,\"pos\":0},\"finished\":null,\"spectating\":false},{\"id\":\"g\",\"name\":\"Guest\",\"user\":\"u-1\",\"ready\":true,\"hasSong\":true,\"live\":{\"score\":0,\"combo\":0,\"acc\":0.0,\"hp\":1.0,\"pos\":0},\"finished\":null,\"spectating\":false}],\"startAt\":5000,\"sharedSong\":{\"hash\":\"abc\",\"bytes\":1234,\"ext\":\"flac\"}}}"
    ]
  ],
  "FRAMES": "{\"t\":\"frames\",\"players\":[[\"h\",{\"score\":0,\"combo\":0,\"acc\":0.0,\"hp\":1.0,\"pos\":0}],[\"g\",{\"score\":10,\"combo\":3,\"acc\":0.5,\"hp\":1.0,\"pos\":100}]]}",
  "RESULTS": [
    [
      "All",
      "{\"t\":\"results\",\"ranking\":[{\"rank\":1,\"id\":\"g\",\"name\":\"Guest\",\"result\":{\"score\":900,\"acc\":1.0,\"maxCombo\":4,\"grade\":\"S\",\"failed\":false,\"judgements\":{\"10\":1.0,\"Miss\":0,\"a\":[1,2],\"w1\":3}}},{\"rank\":2,\"id\":\"h\",\"name\":\"Host\",\"result\":{\"score\":700,\"acc\":0.95,\"maxCombo\":0,\"grade\":\"\",\"failed\":false,\"judgements\":{}}}]}"
    ],
    [
      "All",
      "{\"t\":\"room\",\"room\":{\"code\":\"ABCD\",\"name\":\"Friday\",\"hostId\":\"h\",\"state\":\"results\",\"chart\":{\"hash\":\"abc\",\"title\":\"Song\",\"artist\":\"Band\",\"difficulty\":\"Hard\",\"duration\":120.0,\"bpm\":150.5},\"players\":[{\"id\":\"h\",\"name\":\"Host\",\"user\":null,\"ready\":false,\"hasSong\":true,\"live\":{\"score\":700,\"combo\":0,\"acc\":0.95,\"hp\":1.0,\"pos\":0},\"finished\":{\"score\":700,\"acc\":0.95,\"maxCombo\":0,\"grade\":\"\",\"failed\":false,\"judgements\":{}},\"spectating\":false},{\"id\":\"g\",\"name\":\"Guest\",\"user\":\"u-1\",\"ready\":false,\"hasSong\":true,\"live\":{\"score\":900,\"combo\":3,\"acc\":1.0,\"hp\":1.0,\"pos\":100},\"finished\":{\"score\":900,\"acc\":1.0,\"maxCombo\":4,\"grade\":\"S\",\"failed\":false,\"judgements\":{\"10\":1.0,\"Miss\":0,\"a\":[1,2],\"w1\":3}},\"spectating\":false}],\"startAt\":null,\"sharedSong\":{\"hash\":\"abc\",\"bytes\":1234,\"ext\":\"flac\"}}}"
    ]
  ],
  "SUMMARY": "{\"t\":\"rooms\",\"rooms\":[{\"code\":\"ABCD\",\"name\":\"Friday\",\"host\":\"Host\",\"players\":2,\"state\":\"results\",\"chart\":\"Song\"}]}",
  "KICK": [
    [
      "One(\"g\")",
      "{\"t\":\"left\",\"reason\":\"kicked by the host\"}"
    ],
    [
      "All",
      "{\"t\":\"room\",\"room\":{\"code\":\"ABCD\",\"name\":\"Friday\",\"hostId\":\"h\",\"state\":\"results\",\"chart\":{\"hash\":\"abc\",\"title\":\"Song\",\"artist\":\"Band\",\"difficulty\":\"Hard\",\"duration\":120.0,\"bpm\":150.5},\"players\":[{\"id\":\"h\",\"name\":\"Host\",\"user\":null,\"ready\":false,\"hasSong\":true,\"live\":{\"score\":700,\"combo\":0,\"acc\":0.95,\"hp\":1.0,\"pos\":0},\"finished\":{\"score\":700,\"acc\":0.95,\"maxCombo\":0,\"grade\":\"\",\"failed\":false,\"judgements\":{}},\"spectating\":false}],\"startAt\":null,\"sharedSong\":{\"hash\":\"abc\",\"bytes\":1234,\"ext\":\"flac\"}}}"
    ]
  ],
  "ERRMSG": "{\"t\":\"error\",\"message\":\"x\"}",
  "CHAT": "{\"t\":\"chat\",\"id\":\"g\",\"name\":\"Guest\",\"text\":\"gg\"}",
  "SUMMARY2": "{\"code\":\"WXYZ\",\"name\":\"Host's room\",\"host\":\"Host\",\"players\":1,\"state\":\"lobby\",\"chart\":null}"
};
const SERDE_F64 = ["1.0", "0.1", "1000000000000000.0", "1e+16", "1e+17", "1e+21", "0.00001", "1e-6", "1e-7", "123456789.125", "-0.0", "5e-324", "1.7976931348623157e+308"];
const SERDE_PARSE: [string, string | null][] = [
  ["{\"t\":\"create\",\"kind\":{\"party\":null}}", null],
  ["{\"t\":\"create\",\"kind\":{\"party\":1}}", "invalid type: integer `1`, expected unit"],
  ["{\"t\":\"create\",\"kind\":{}}", "invalid value: map, expected map with a single key"],
  ["{\"t\":\"create\",\"kind\":{\"x\":null}}", "unknown variant `x`, expected `game` or `party`"],
  ["{\"t\":\"create\",\"kind\":{\"game\":null,\"party\":null}}", "invalid value: map, expected map with a single key"],
  ["{\"t\":\"create\",\"kind\":[\"game\"]}", "invalid type: sequence, expected string or map"],
  ["[\"create\"]", null],
  ["[\"create\",\"n\",\"party\"]", null],
  ["[\"pick\",[\"h\",\"T\",\"A\"]]", null],
  ["[\"frame\"]", "invalid length 0, expected struct variant ClientMsg::Frame with 1 element"],
  ["[\"frame\",{}]", null],
  ["[\"kick\",1]", "invalid type: integer `1`, expected a string"],
  ["[\"chat\",\"a\\n\\u0001é​\"]", null],
  ["{\"t\":\"chat\",\"text\":5}", "invalid type: integer `5`, expected a string"],
  ["{\"t\":\"kick\",\"id\":\"a\\n\\u0001é​\\\\\"}", null],
  ["{\"t\":\"frame\",\"live\":\"a\\n\\u0001é​\\\\\\t\\r\\u0000\\u007f\"}", "invalid type: string \"a\\n\\u{1}é\\u{200b}\\\\\\t\\r\\0\\u{7f}\", expected struct Live"],
  ["{\"t\":\"pick\",\"chart\":[\"h\",\"T\",\"A\"]}", null],
  ["{\"t\":\"pick\",\"chart\":[\"h\",\"T\"]}", "invalid length 2, expected struct Chart with 7 elements"],
  ["{\"t\":\"pick\",\"chart\":[\"h\",\"T\",\"A\",\"d\",1,2,\"n\"]}", null],
  ["{\"t\":\"pick\",\"chart\":[\"h\",\"T\",\"A\",\"d\",1,2,\"n\",5]}", "invalid length 8, expected 7 elements in sequence"],
  ["{\"t\":\"pick\",\"chart\":[]}", "invalid length 0, expected struct Chart with 7 elements"],
  ["{\"t\":\"frame\",\"live\":[1,2,0.5]}", null],
  ["{\"t\":\"frame\",\"live\":[1,2,0.5,0.25,9,10]}", "invalid length 6, expected 5 elements in sequence"],
  ["{\"t\":\"finish\",\"result\":[1,0.5,3,\"A\",true,{\"x\":1}]}", null],
  ["{\"t\":\"finish\",\"result\":[1,0.5,3,\"A\",true,{\"x\":1},7]}", "invalid length 7, expected 6 elements in sequence"],
  ["[\"have\",true]", null],
  ["[\"have\"]", "invalid length 0, expected struct variant ClientMsg::Have with 1 element"],
  ["[\"have\",true,false]", "invalid length 2, expected 1 element in sequence"],
  ["[\"start\"]", null],
  ["[\"start\",1]", "invalid length 1, expected 0 elements in sequence"],
  ["[]", "missing field `t`"],
  ["{\"t\":\"have\",\"audio\":true,\"t\":\"x\"}", "duplicate field `t`"],
  ["{\"t\":\"have\",\"audio\":true,\"audio\":false}", "duplicate field `audio`"],
  ["{\"t\":\"pick\",\"chart\":{\"hash\":\"h\",\"title\":\"T\",\"artist\":\"A\",\"notes\":null}}", "invalid type: null, expected a string"],
  ["{\"t\":\"frame\",\"live\":{\"acc\":\"1\"}}", "invalid type: string \"1\", expected f64"],
  ["{\"t\":\"frame\",\"live\":{\"acc\":-0.0}}", null],
  ["{\"t\":\"frame\",\"live\":{\"pos\":18446744073709551615}}", null],
  ["{\"t\":\"frame\",\"live\":{\"pos\":18446744073709551616}}", "invalid type: floating point `1.8446744073709552e+19`, expected u64"],
  ["{\"t\":\"frame\",\"live\":{\"score\":-9223372036854775808}}", null],
  ["{\"t\":\"frame\",\"live\":{\"score\":1e3}}", "invalid type: floating point `1000.0`, expected i64"],
  ["{\"t\":\"frame\",\"live\":{\"score\":-0}}", "invalid type: floating point `-0.0`, expected i64"],
  ["{\"t\":\"frame\",\"live\":{\"combo\":-0.0}}", "invalid type: floating point `-0.0`, expected u32"],
  ["{\"t\":\"kick\",\"id\":null}", "invalid type: null, expected a string"],
  ["{\"t\":\"kick\",\"id\":[]}", "invalid type: sequence, expected a string"],
  ["{\"t\":\"kick\",\"id\":{}}", "invalid type: map, expected a string"],
  ["{\"t\":\"kick\",\"id\":1.5}", "invalid type: floating point `1.5`, expected a string"],
  ["{\"t\":\"kick\",\"id\":-2}", "invalid type: integer `-2`, expected a string"],
  ["{\"t\":\"frame\",\"live\":5}", "invalid type: integer `5`, expected struct Live"],
  ["{\"t\":\"frame\",\"live\":\"a\\\"b\"}", "invalid type: string \"a\\\"b\", expected struct Live"],
  ["{\"t\":\"finish\",\"result\":{\"judgements\":null}}", "invalid type: null, expected a map"],
  ["{\"t\":\"finish\",\"result\":{\"judgements\":{\"a\":null}}}", null],
  ["{\"t\":\"Pick\",\"chart\":{}}", "unknown variant `Pick`, expected one of `hello`, `ping`, `rooms`, `create`, `join`, `leave`, `pick`, `have`, `ready`, `start`, `kick`, `frame`, `finish`, `chat`, `queueAdd`, `queueRemove`, `queueMove`, `pause`, `resume`, `seek`, `skip`, `setRate`, `passHost`, `endParty`, `skipVote`"],
  ["{\"t\":\"create\",\"kind\":5}", "invalid type: integer `5`, expected string or map"],
  ["{\"t\":\"create\",\"kind\":null}", "invalid type: null, expected string or map"],
  ["{\"t\":\"create\",\"kind\":\"Game\"}", "unknown variant `Game`, expected `game` or `party`"],
  ["{\"t\":\"rooms\",\"x\":[1,2]}", null],
  ["{\"t\":\"leave\"}", null],
  ["{\"t\":\"pick\",\"chart\":{\"hash\":\"h\",\"title\":\"T\",\"artist\":\"A\"}}", null],
  ["{\"t\":\"pick\",\"chart\":{\"hash\":\"h\",\"title\":\"T\"}}", "missing field `artist`"],
  ["{\"t\":\"pick\",\"chart\":{\"hash\":\"h\",\"title\":\"T\",\"artist\":\"A\",\"difficulty\":null}}", "invalid type: null, expected a string"],
  ["{\"t\":\"pick\",\"chart\":{\"hash\":\"h\",\"title\":\"T\",\"artist\":\"A\",\"duration\":\"x\"}}", "invalid type: string \"x\", expected f64"],
  ["{\"t\":\"pick\",\"chart\":{\"hash\":5,\"title\":\"T\",\"artist\":\"A\"}}", "invalid type: integer `5`, expected a string"],
  ["{\"t\":\"pick\",\"chart\":{\"hash\":\"h\",\"title\":\"T\",\"artist\":\"A\",\"duration\":3}}", null],
  ["{\"t\":\"pick\",\"chart\":\"x\"}", "invalid type: string \"x\", expected struct Chart"],
  ["{\"t\":\"pick\",\"chart\":[1]}", "invalid type: integer `1`, expected a string"],
  ["{\"t\":\"pick\",\"chart\":null}", "invalid type: null, expected struct Chart"],
  ["{\"t\":\"pick\"}", "missing field `chart`"],
  ["{\"t\":\"frame\",\"live\":{}}", null],
  ["{\"t\":\"frame\",\"live\":{\"combo\":-1}}", "invalid value: integer `-1`, expected u32"],
  ["{\"t\":\"frame\",\"live\":{\"combo\":1.5}}", "invalid type: floating point `1.5`, expected u32"],
  ["{\"t\":\"frame\",\"live\":{\"combo\":5.0}}", "invalid type: floating point `5.0`, expected u32"],
  ["{\"t\":\"frame\",\"live\":{\"combo\":4294967296}}", "invalid value: integer `4294967296`, expected u32"],
  ["{\"t\":\"frame\",\"live\":{\"pos\":-3}}", "invalid value: integer `-3`, expected u64"],
  ["{\"t\":\"frame\",\"live\":{\"score\":-3,\"hp\":0}}", null],
  ["{\"t\":\"frame\",\"live\":{\"score\":true}}", "invalid type: boolean `true`, expected i64"],
  ["{\"t\":\"frame\",\"live\":{\"score\":9223372036854775808}}", "invalid value: integer `9223372036854775808`, expected i64"],
  ["{\"t\":\"frame\",\"live\":{\"hp\":null}}", "invalid type: null, expected f64"],
  ["{\"t\":\"finish\",\"result\":{}}", null],
  ["{\"t\":\"finish\",\"result\":{\"failed\":1}}", "invalid type: integer `1`, expected a boolean"],
  ["{\"t\":\"finish\",\"result\":{\"judgements\":[]}}", "invalid type: sequence, expected a map"],
  ["{\"t\":\"finish\",\"result\":{\"grade\":{}}}", "invalid type: map, expected a string"],
  ["{\"t\":\"finish\",\"result\":{\"maxCombo\":\"3\"}}", "invalid type: string \"3\", expected u32"],
  ["{\"t\":\"have\",\"audio\":\"yes\"}", "invalid type: string \"yes\", expected a boolean"],
  ["{\"t\":\"have\"}", "missing field `audio`"],
  ["{\"t\":\"ready\",\"ready\":null}", "invalid type: null, expected a boolean"],
  ["{\"t\":\"kick\",\"id\":3}", "invalid type: integer `3`, expected a string"],
  ["{\"t\":\"join\",\"code\":\"abcd\"}", null],
  ["{\"t\":\"create\"}", null],
  ["{\"t\":\"create\",\"kind\":\"x\"}", "unknown variant `x`, expected `game` or `party`"],
  ["{\"t\":\"create\",\"kind\":\"party\",\"name\":null}", "invalid type: null, expected a string"],
  ["{\"t\":\"nope\"}", "unknown variant `nope`, expected one of `hello`, `ping`, `rooms`, `create`, `join`, `leave`, `pick`, `have`, `ready`, `start`, `kick`, `frame`, `finish`, `chat`, `queueAdd`, `queueRemove`, `queueMove`, `pause`, `resume`, `seek`, `skip`, `setRate`, `passHost`, `endParty`, `skipVote`"],
  ["{\"x\":1}", "missing field `t`"],
  ["{\"t\":5}", "invalid type: integer `5`, expected variant identifier"],
  ["[1]", "invalid type: integer `1`, expected variant identifier"],
  ["\"s\"", "invalid type: string \"s\", expected internally tagged enum ClientMsg"],
  ["{\"t\":\"start\",\"extra\":1}", null],
  ["{\"t\":\"chat\",\"text\":\"hi\"}", null],
  ["{\"t\":\"chat\"}", "missing field `text`"],
  ["{\"t\":\"frame\",\"live\":{\"acc\":1e400}}", "number out of range"],
  ["{\"t\":\"frame\",\"live\":{\"score\":1.5}}", "invalid type: floating point `1.5`, expected i64"],
  ["{\"t\":\"frame\",\"live\":{\"score\":-1.5}}", "invalid type: floating point `-1.5`, expected i64"],
  ["{\"t\":\"pick\",\"chart\":{\"hash\":\"h\",\"hash\":\"i\",\"title\":\"T\",\"artist\":\"A\"}}", "duplicate field `hash`"],
  ["{\"t\":\"finish\",\"result\":{\"grade\":\"\\u0000\"}}", null],
];

test("a room's messages encode to exactly what serde writes", () => {
  // The scenario the Rust printed SERDE from, move for move.
  const r = new Room("ABCD", "  Friday  ", newPlayer("h", "Host"));
  ok(r.join(newPlayer("g", "Guest", "u-1")));
  assert.equal(encode({ t: "room", room: r.view() }), SERDE.VIEW0);
  const pick = ok(r.pick("h", chart({ bpm: 150.5 })));
  assert.deepEqual(
    pick.map((o) => [o.to, encode(o.msg)]),
    SERDE.PICK.map(([to, js]) => [to === "All" ? "all" : to, js]),
  );
  ok(r.share("h", song("abc"), "/s/a"));
  r.have("h", true);
  r.have("g", true);
  ok(r.ready("h", true));
  ok(r.ready("g", true));
  const start = ok(r.start("h", 1000));
  assert.deepEqual(
    start.map((o) => [o.to, encode(o.msg)]),
    SERDE.START.map(([to, js]) => [to === "All" ? "all" : to, js]),
  );
  r.frame("g", { score: 10, combo: 3, acc: 0.5, hp: 1.0, pos: 100 });
  assert.equal(encode(r.frames()), SERDE.FRAMES);
  const parsed = msgOf(
    JSON.parse(
      '{"t":"finish","result":{"score":900,"acc":1,"maxCombo":4,"grade":"S","judgements":{"w1":3,"Miss":0,"10":1.0,"a":[1,2]}}}',
    ),
  );
  assert.equal(parsed.t, "finish");
  r.finish("g", (parsed as { result: Finished }).result, 5);
  const results = r.finish("h", fin({ score: 700, acc: 0.95 }), 6);
  // serde_json keeps `1.0` in a judgement as a float; JSON.parse cannot,
  // so that one value goes out as `1` here.
  const fix = (s: string) => s.replaceAll('"10":1.0', '"10":1');
  assert.deepEqual(
    results.map((o) => [o.to, encode(o.msg)]),
    SERDE.RESULTS.map(([to, js]) => [to === "All" ? "all" : to, fix(js)]),
  );
  assert.equal(encode(listRooms([r])), SERDE.SUMMARY);
  const kick = ok(r.kick("h", "g", 7));
  assert.deepEqual(
    kick.map((o) => [o.to, encode(o.msg)]),
    SERDE.KICK.map(([to, js]) => [to === "All" ? "all" : to.replace(/^One\("(.*)"\)$/, "$1"), js]),
  );
  assert.equal(encode({ t: "error", message: "x" }), SERDE.ERRMSG);
  assert.equal(encode(chatOut("g", "Guest", "gg")[0].msg), SERDE.CHAT);
  assert.equal(encode(new Room("WXYZ", "", newPlayer("h", "Host")).summary()), SERDE.SUMMARY2);
});

test("a RoomView keeps its nulls, drops empty notes, and differs from JSON.stringify only in 1 vs 1.0", () => {
  const r = room();
  const v0 = r.view();
  const text = encode({ t: "room", room: v0 });
  for (const key of ['"chart":null', '"startAt":null', '"sharedSong":null', '"user":null', '"hasSong":null', '"finished":null']) {
    assert.ok(text.includes(key), key);
  }
  ok(r.pick("h", chart()));
  const v1 = r.view();
  assert.ok(!encode({ t: "room", room: v1 }).includes("notes"));
  for (const v of [v0, v1]) {
    const m = { t: "room", room: v };
    assert.deepEqual(JSON.parse(encode(m)), JSON.parse(JSON.stringify(m)));
    assert.equal(encode(m).replace(/(\d)\.0(?=[,}\]])/g, "$1"), JSON.stringify(m));
  }
});

test("floats are written as serde_json writes them", () => {
  const values = [1.0, 0.1, 1e15, 1e16, 1e17, 1e21, 1e-5, 1e-6, 1e-7, 123456789.125, -0.0, 5e-324, 1.7976931348623157e308];
  assert.deepEqual(values.map(formatF64), SERDE_F64);
  assert.equal(formatF64(-2.5), "-2.5");
  assert.equal(formatF64(1.5e20), "1.5e+20");
});

// Inputs serde reads differently from JSON.parse, so no reader of parsed JSON can match them:
// `5.0` and `1e3` are integers once parsed, a u64 past 2^53 is rounded, and a duplicate key keeps the last.
const BEYOND_JSON_PARSE = new Set([
  '{"t":"frame","live":{"combo":5.0}}',
  '{"t":"frame","live":{"score":1e3}}',
  '{"t":"frame","live":{"pos":18446744073709551615}}',
  '{"t":"have","audio":true,"t":"x"}',
  '{"t":"have","audio":true,"audio":false}',
  '{"t":"pick","chart":{"hash":"h","hash":"i","title":"T","artist":"A"}}',
]);

test("incoming messages are accepted and refused as serde does it", () => {
  let checked = 0;
  for (const [input, expected] of SERDE_PARSE) {
    if (BEYOND_JSON_PARSE.has(input)) continue;
    const got = parseRoomClientMsg(JSON.parse(input));
    if (expected === null) assert.notEqual(got.ok, false, `${input} should parse: ${JSON.stringify(got)}`);
    else assert.deepEqual(got, { ok: false, error: `bad message: ${expected}` }, input);
    checked++;
  }
  assert.ok(checked > 80);
});

test("parsed messages carry serde's defaults", () => {
  const cases: [string, unknown][] = [
    ['{"t":"pick","chart":{"hash":"h","title":"T","artist":"A"}}', { t: "pick", chart: { hash: "h", title: "T", artist: "A", difficulty: "", duration: 0, bpm: 0, notes: "" } }],
    ['{"t":"pick","chart":["h","T","A","d",1,2,"n"]}', { t: "pick", chart: { hash: "h", title: "T", artist: "A", difficulty: "d", duration: 1, bpm: 2, notes: "n" } }],
    ['{"t":"frame","live":{}}', { t: "frame", live: { score: 0, combo: 0, acc: 0, hp: 1, pos: 0 } }],
    ['{"t":"frame","live":[1,2,0.5]}', { t: "frame", live: { score: 1, combo: 2, acc: 0.5, hp: 1, pos: 0 } }],
    ['{"t":"finish","result":{}}', { t: "finish", result: { score: 0, acc: 0, maxCombo: 0, grade: "", failed: false, judgements: {} } }],
    ['{"t":"finish","result":[1,0.5,3,"A",true,{"x":1}]}', { t: "finish", result: { score: 1, acc: 0.5, maxCombo: 3, grade: "A", failed: true, judgements: { x: 1 } } }],
    ['{"t":"create"}', { t: "create", name: "", kind: "game" }],
    ['["create","n","party"]', { t: "create", name: "n", kind: "party" }],
    ['{"t":"create","kind":{"party":null}}', { t: "create", name: "", kind: "party" }],
    ['["have",true]', { t: "have", audio: true }],
    ['{"t":"start","extra":1}', { t: "start" }],
    ['{"t":"join","code":"abcd"}', { t: "join", code: "abcd" }],
  ];
  for (const [input, msg] of cases) assert.deepEqual(parseRoomClientMsg(JSON.parse(input)), { ok: true, msg }, input);
  // Not a room's: left to the party's reader, or the hub's own.
  assert.deepEqual(parseRoomClientMsg({ t: "ping", c: 1 }), { ok: "other", t: "ping" });
  assert.deepEqual(parseRoomClientMsg({ t: "queueAdd" }), { ok: "other", t: "queueAdd" });
  // The field readers alone.
  assert.deepEqual(parseLive({ score: 3 }), { ok: true, value: { score: 3, combo: 0, acc: 0, hp: 1, pos: 0 } });
  assert.deepEqual(parseChart({ hash: "h", title: "T" }), { ok: false, error: "bad message: missing field `artist`" });
  assert.deepEqual(parseFinished({ failed: 1 }), { ok: false, error: "bad message: invalid type: integer `1`, expected a boolean" });
  assert.equal(Object.is((parseLive({ acc: -0 }) as { value: Live }).value.acc, -0), true);
});

console.log(`room: ${passed} passed${failed ? `, ${failed} failed` : ""}`);
if (failed) process.exit(1);

// SPDX-License-Identifier: GPL-3.0-or-later
//
// The party's rules alone: no sockets, no Worker. Every test of
// tryptify-party's `cpp/tests/party_test.cpp`, the checks of its Rust
// wrapper (`src/party.rs`), and the rules its socket tests (`tests/party.rs`)
// hold, all against src/party.ts. Needs nothing else:
//
//   bun run party.test.ts

import assert from "node:assert/strict";
import * as P from "./src/party";
import { Party, type ItemIn, type MemberIn, type Out, type Result } from "./src/party";

let passed = 0;
let failed = 0;
function test(name: string, fn: () => void) {
  try {
    fn();
    passed++;
  } catch (e) {
    failed++;
    console.error(`FAIL ${name}`);
    console.error(e);
  }
}

const LIMIT = 128 * 1024 * 1024;

const member = (id: string): MemberIn => ({ id, name: id.toUpperCase(), token: "tok-" + id });
const guest = (id: string): MemberIn => ({ ...member(id), guest: true, web: true });
const song = (title: string, share = false): ItemIn => ({ title, artist: "Band", durationMs: 100_000, share });

function party(): Party {
  const p = new Party("ABCD", "", member("h"), LIMIT);
  p.join(member("a"), 0);
  return p;
}

/** A message as the wire carries it. */
const text = (m: { msg: Record<string, unknown> }) => JSON.stringify(m.msg);
const outOf = (r: Result): Out => {
  if (!r.ok) throw new Error(`refused: ${r.error}`);
  return r.out;
};
const has = (r: Result | Out, needle: string) =>
  (Array.isArray(r) ? r : r.ok ? r.out : []).some((m) => text(m).includes(needle));
const err = (r: Result) => (r.ok ? "" : r.error);
const view = (p: Party) => JSON.stringify(p.viewJson());
/** The party in the last message of a move. */
const lastParty = (out: Out) => {
  const m = out[out.length - 1].msg;
  assert.equal(m.t, "party");
  return m.party as any;
};

// ---- cpp/tests/party_test.cpp ----------------------------------------------

test("a_party_is_named_after_its_host_and_fills_to_sixteen", () => {
  const p = party();
  assert.equal(p.name, "H's party");
  for (let i = 0; i < 14; ++i) assert.ok(p.join(member("m" + i), 0).ok);
  assert.equal(err(p.join(member("x"), 0)), "the party is full");
  assert.equal(err(p.join(member("h"), 0)), "already in the party");
  // A long name is cut by characters, never mid-letter.
  const q = new Party("WXYZ", "  " + "n".repeat(50) + " ", member("h"), LIMIT);
  assert.equal(q.name.length, 40);
  assert.equal(P.trimChars("あいう", 2), "あい");
  // Code points, not UTF-16 units: a letter past the BMP counts once.
  assert.equal(P.trimChars(" 😀😀😀\t", 2), "😀😀");
  assert.equal(P.trimChars("\v\f a b \r\n", 99), "a b");
});

test("joining_hands_over_the_token_the_chat_and_the_party", () => {
  const p = party();
  p.chat("h", "hi", 1);
  const r = p.join(guest("g"), 2);
  const out = outOf(r);
  assert.equal(out[0].to, "g");
  assert.ok(text(out[0]).includes('"songToken":"tok-g"'));
  assert.equal(out[1].to, "g");
  assert.ok(text(out[1]).includes('"t":"history"'));
  assert.ok(text(out[1]).includes('"text":"hi"'));
  // Everyone hears a guest came from the web.
  assert.ok(has(r, '"kind":"join","id":"g","name":"G","text":"web"'));
  assert.equal(out[out.length - 1].to, "");
  assert.ok(text(out[out.length - 1]).startsWith('{"t":"party"'));
});

test("the_first_song_plays_at_once_and_the_rest_wait", () => {
  const p = party();
  const out = outOf(p.add("a", song("One"), "r1", 1000));
  assert.equal(out[0].to, "a");
  assert.equal(text(out[0]), '{"t":"added","ref":"r1","itemId":"s1"}');
  assert.equal(p.playback.itemId, "s1");
  assert.equal(p.playback.atServerMs, 1000);
  p.add("h", song("Two"), "", 2000);
  assert.equal(p.now!.addedByName, "A");
  assert.equal(p.queue.length, 1);
  assert.equal(p.queue[0].title, "Two");
});

test("guests_add_only_songs_from_the_catalogue", () => {
  const p = party();
  p.join(guest("g"), 0);
  const why = "a website guest can add only songs from the catalogue";
  // No catalogue id: nothing anyone could play from it.
  assert.equal(err(p.add("g", song("One"), "", 0)), why);
  // A guest has no file to share.
  assert.equal(err(p.add("g", { ...song("One"), catalogId: "tidal:1", share: true }, "", 0)), why);
  // An empty catalogue id is none.
  assert.equal(err(p.add("g", { ...song("One"), catalogId: "" }, "", 0)), why);
  assert.ok(p.add("g", { ...song("One"), catalogId: "tidal:1" }, "", 0).ok);
  assert.ok(p.now && p.now.addedBy === "g" && p.now.shared === null);
  assert.ok(p.chat("g", "hello", 0).ok);
});

test("a_song_needs_a_title_and_a_length", () => {
  const p = party();
  assert.equal(err(p.add("a", song("  "), "", 0)), "the song has no title");
  assert.equal(err(p.add("a", { ...song("x"), durationMs: 0 }, "", 0)), "the song has no length");
  // Left out, as an older phone may: no length either.
  assert.equal(err(p.add("a", { title: "x" }, "", 0)), "the song has no length");
  // A cover that is not https is dropped, not refused.
  assert.ok(p.add("a", { ...song("x"), cover: "http://x/y.jpg" }, "", 0).ok);
  assert.equal(p.now!.cover, null);
});

test("only_the_host_pauses_seeks_reorders_and_skips", () => {
  const p = party();
  p.add("a", song("One"), "", 0);
  p.add("a", song("Two"), "", 0);
  p.add("a", song("Three"), "", 0);
  for (const e of [err(p.pause("a", 0)), err(p.seek("a", 5, 0)), err(p.moveItem("a", "s3", 0)), err(p.skip("a", null, 0))])
    assert.equal(e, "only the host can do that");
  assert.ok(p.moveItem("h", "s3", 0).ok);
  assert.equal(p.queue[0].id, "s3");
  // Past the end is last; the one playing is not in Up next.
  assert.ok(p.moveItem("h", "s3", 99).ok);
  assert.deepEqual(p.queue.map((i) => i.id), ["s2", "s3"]);
  assert.equal(err(p.moveItem("h", "s1", 0)), "that song is not in Up next");
});

test("pause_resume_and_seek_move_the_one_clock", () => {
  const p = party();
  p.add("a", song("One"), "", 1000);
  assert.equal(p.position(4000), 3000);
  p.pause("h", 4000);
  assert.equal(p.position(9000), 3000);
  assert.ok(p.playback.paused);
  p.resume("h", 10_000);
  assert.equal(p.position(11_000), 4000);
  p.seek("h", 50_000, 12_000);
  assert.equal(p.position(13_000), 51_000);
  // Past the end is the end.
  p.seek("h", 999_999, 14_000);
  assert.equal(p.playback.positionMs, 100_000);
});

test("a_song_ends_on_its_length_and_the_next_plays", () => {
  const p = party();
  p.add("a", song("One"), "", 0);
  p.add("a", song("Two"), "", 0);
  assert.equal(p.tick(100_000).length, 0);
  const out = p.tick(100_000 + P.END_GRACE_MS);
  assert.ok(has(out, '"now":{"id":"s2","title":"Two"'));
  assert.equal(p.playback.positionMs, 0);
  assert.equal(p.playback.atServerMs, 100_000 + P.END_GRACE_MS);
  // A paused song never ends.
  p.pause("h", 100_000 + P.END_GRACE_MS);
  assert.equal(p.tick(10_000_000).length, 0);
  p.resume("h", 10_000_000);
  p.tick(10_000_000 + 100_000 + P.END_GRACE_MS);
  assert.equal(p.now, null);
  assert.equal(p.playback.itemId, null);
});

test("half_the_room_rounded_up_skips", () => {
  const p = party();
  p.join(member("b"), 0);
  p.add("a", song("One"), "", 0);
  p.add("a", song("Two"), "", 0);
  // Three here: two votes skip.
  assert.equal(p.votesNeeded(), 2);
  p.vote("a", "s1", true, 1);
  assert.deepEqual(p.votes(), ["a"]);
  // Taken back and given again.
  p.vote("a", "s1", false, 1);
  assert.deepEqual(p.votes(), []);
  p.vote("a", "s1", true, 1);
  const r = p.vote("b", "s1", true, 2);
  assert.ok(has(r, '"kind":"skip","id":null,"name":"","text":"One"'));
  assert.equal(p.playback.itemId, "s2");
  assert.deepEqual(p.votes(), []);
});

test("two_skips_at_once_skip_one_song", () => {
  const p = party();
  p.add("a", song("One"), "", 0);
  p.add("a", song("Two"), "", 0);
  assert.ok(p.skip("h", "s1", 1).ok);
  assert.equal(err(p.skip("h", "s1", 1)), "that song is already over");
  assert.equal(err(p.vote("a", "s1", true, 1)), "that song is already over");
  assert.equal(p.playback.itemId, "s2");
});

test("a_vote_leaves_with_its_voter_and_the_rest_may_then_skip", () => {
  const p = party();
  p.join(member("b"), 0);
  p.join(member("c"), 0);
  p.add("a", song("One"), "", 0);
  // Four here: two needed.
  p.vote("a", null, true, 1);
  p.leave("a", 2);
  assert.deepEqual(p.votes(), []);
  p.vote("b", null, true, 3);
  assert.notEqual(p.now, null);
  // Down to two (h and b), b's one vote skips.
  const out = p.leave("c", 4);
  assert.ok(has(out, '"kind":"skip"'));
  assert.equal(p.now, null);
});

test("someone_away_keeps_their_seat_but_not_their_say_in_a_skip", () => {
  const p = party();
  p.join(member("b"), 0);
  p.add("a", song("One"), "", 0);
  p.setAway("b", true, 1);
  assert.equal(p.votesNeeded(), 1);
  assert.ok(view(p).includes('"id":"b","name":"B","user":null,"guest":false,"web":false,"away":true'));
  p.vote("b", null, true, 1);
  // b's vote counts only once b is back.
  assert.notEqual(p.now, null);
  p.vote("a", null, true, 2);
  assert.equal(p.now, null);
});

test("the_host_leaving_passes_the_seat_to_an_app_member_and_the_queue_plays_on", () => {
  const p = party();
  p.join(guest("g"), 0);
  p.add("h", song("One"), "", 0);
  p.leave("h", 1);
  assert.equal(p.host, "a");
  assert.notEqual(p.now, null);
  // Only a guest left: no host, until an app member comes.
  p.leave("a", 2);
  assert.equal(p.host, "");
  assert.equal(err(p.pause("g", 3)), "only the host can do that");
  p.join(member("n"), 4);
  assert.equal(p.host, "n");
});

test("taking_a_song_out_is_for_the_host_or_whoever_added_it", () => {
  const p = party();
  p.join(member("b"), 0);
  p.add("a", song("One"), "", 0);
  p.add("a", song("Two"), "", 0);
  assert.equal(err(p.remove("b", "s2", 0)), "only the host or whoever added it can take it out");
  assert.ok(p.remove("a", "s2", 0).ok);
  assert.equal(p.queue.length, 0);
  // The one playing goes as a skip does.
  assert.ok(p.remove("h", "s1", 0).ok);
  assert.equal(p.now, null);
  assert.equal(err(p.remove("h", "s1", 0)), "that song is not in the queue");
});

test("chat_keeps_fifty_lines_and_slows_down_a_flood", () => {
  const p = party();
  for (let i = 0; i < P.CHAT_BURST; ++i) assert.ok(p.chat("a", String(i), 10).ok);
  assert.equal(err(p.chat("a", "more", 10)), "slow down a little");
  assert.ok(p.chat("a", "later", 10 + P.CHAT_WINDOW_MS).ok);
  // Blank lines are said by nobody.
  const blank = p.chat("a", "   ", 100_000);
  assert.ok(blank.ok && blank.out.length === 0);
  for (let i = 0; i < 100; ++i) p.chat("h", "x", 1_000_000 + i * P.CHAT_WINDOW_MS);
  assert.equal(p.chatSize(), P.CHAT_HISTORY);
  const r = outOf(p.chat("a", "y".repeat(1000), 10_000_000));
  assert.ok(text(r[0]).includes('"text":"' + "y".repeat(P.CHAT_MAX_CHARS) + '"'));
  // Quotes and new lines are escaped, so a line is one line of JSON.
  const q = outOf(p.chat("h", 'say "hi"\nnow\\', 20_000_000));
  assert.ok(text(q[0]).includes('"text":"say \\"hi\\"\\nnow\\\\"'));
});

test("whoever_added_a_song_shares_it_within_the_quota", () => {
  const p = party();
  p.join(guest("g"), 0);
  p.add("a", song("One", true), "", 0);
  assert.ok(p.now!.shared !== null && !p.now!.shared.ready);
  assert.deepEqual(p.uploadLimit("h", "s1", 10), { error: "only whoever added a song shares it" });
  assert.deepEqual(p.uploadLimit("a", "s1", 10), { limit: LIMIT });
  assert.deepEqual(p.uploadLimit("a", "s1", LIMIT + 1), { error: "the song is too big to share" });
  // Uploading 62%.
  p.uploading("s1", 100, 62);
  assert.ok(view(p).includes('"shared":{"ready":false,"bytes":100,"received":62,"ext":""}'));
  // Not fetchable until it is all there.
  assert.equal(p.songFor("tok-g", "s1"), null);
  assert.ok(p.shared("a", "s1", 100, "flac", "/s/1").ok);
  assert.equal(p.songFor("tok-g", "s1")!.path, "/s/1");
  assert.equal(p.songFor("stranger", "s1"), null);
  assert.equal(p.sharedBytes(), 100);
  // The quota counts every song the party holds.
  p.quota = 150;
  p.add("a", song("Two"), "", 0);
  assert.deepEqual(p.uploadLimit("a", "s2", 0), { limit: 50 });
  assert.equal(err(p.shared("a", "s2", 60, "mp3", "/s/2")), "the party has no room for more shared songs");
  // Shared again, the old file is let go and its bytes not counted twice.
  assert.deepEqual(p.uploadLimit("a", "s1", 0), { limit: 150 });
  assert.ok(p.shared("a", "s1", 120, "flac", "/s/1b").ok);
  assert.deepEqual(p.dropped, ["/s/1"]);
});

test("a_shared_song_goes_when_it_leaves_the_queue_and_all_go_with_the_party", () => {
  const p = party();
  p.add("a", song("One", true), "", 0);
  p.add("a", song("Two", true), "", 0);
  p.add("a", song("Three", true), "", 0);
  p.shared("a", "s1", 10, "mp3", "/s/1");
  p.shared("a", "s2", 10, "mp3", "/s/2");
  p.shared("a", "s3", 10, "mp3", "/s/3");
  p.tick(100_000 + P.END_GRACE_MS);
  assert.deepEqual(p.dropped, ["/s/1"]);
  p.remove("a", "s3", 1);
  assert.equal(p.dropped.length, 2);
  const files = p.files().sort();
  assert.deepEqual(files, ["/s/1", "/s/2", "/s/3"]);
  // A song gone mid-upload refuses the finished file.
  assert.equal(err(p.shared("a", "s3", 10, "mp3", "/s/3b")), "that song is not in the queue");
});

test("a_broken_upload_shows_the_song_as_not_shared", () => {
  const p = party();
  p.add("a", song("One", true), "", 0);
  p.uploading("s1", 100, 10);
  p.uploadFailed("s1");
  assert.equal(p.now!.shared, null);
});

test("a_kick_tells_the_kicked_and_the_party", () => {
  const p = party();
  assert.equal(err(p.kick("a", "h", 0)), "only the host can do that");
  assert.equal(err(p.kick("h", "h", 0)), "you cannot kick yourself");
  assert.equal(err(p.kick("h", "nobody", 0)), "no such member");
  const out = outOf(p.kick("h", "a", 0));
  assert.equal(out[0].to, "a");
  assert.equal(text(out[0]), '{"t":"left","reason":"kicked by the host"}');
  assert.equal(p.member("a"), null);
});

test("the_host_hands_the_seat_on_and_ends_the_party", () => {
  const p = party();
  p.join(guest("g"), 0);
  assert.equal(err(p.passHost("a", "h", 0)), "only the host can do that");
  assert.equal(err(p.passHost("h", "g", 0)), "a website guest cannot be the host");
  assert.equal(err(p.passHost("h", "h", 0)), "you are the host already");
  assert.equal(err(p.passHost("h", "nobody", 0)), "no such member");
  const r = p.passHost("h", "a", 1);
  assert.ok(r.ok);
  assert.equal(p.host, "a");
  assert.ok(has(r, '"kind":"host","id":"a","name":"A"'));
  assert.equal(err(p.end("h")), "only the host can do that");
  const end = outOf(p.end("a"));
  assert.ok(end.length === 1 && end[0].to === "");
  assert.equal(text(end[0]), '{"t":"left","reason":"the host ended the party"}');
});

test("a_cover_is_handed_only_to_a_member", () => {
  const p = party();
  p.add("a", { ...song("One"), cover: "https://img.example/one.jpg" }, "", 0);
  assert.equal(p.coverFor("tok-h", "s1"), "https://img.example/one.jpg");
  assert.equal(p.coverFor("stranger", "s1"), "");
  assert.equal(p.coverFor("tok-h", "s9"), "");
});

test("the_hosts_speed_runs_the_clock_and_holds_from_song_to_song", () => {
  const p = party();
  p.add("a", song("One"), "", 0);
  p.add("a", song("Two"), "", 0);
  assert.equal(err(p.setRate("a", 1.5, false, 0)), "only the host can do that");
  assert.equal(err(p.setRate("h", 5.0, false, 0)), "the speed must be between 0.25x and 4x");
  assert.equal(err(p.setRate("h", NaN, false, 0)), "the speed must be between 0.25x and 4x");
  // Ten seconds at normal speed, then faster: the moment so far is kept.
  const r = outOf(p.setRate("h", 1.5, false, 10_000));
  assert.ok(text(r[0]).includes('"rate":1.5,"keepPitch":false'));
  assert.equal(p.position(10_000), 10_000);
  assert.equal(p.position(20_000), 25_000);
  // Paused, the speed changes nothing until it plays again.
  p.pause("h", 20_000);
  p.setRate("h", 0.5, true, 30_000);
  assert.equal(p.position(30_000), 25_000);
  p.resume("h", 30_000);
  assert.equal(p.position(34_000), 27_000);
  // The song runs out sooner at speed, and the next keeps the speed.
  p.setRate("h", 2.0, false, 34_000);
  assert.ok(p.tick(90_000).length > 0);
  assert.ok(p.now && p.now.title === "Two");
  assert.ok(p.playback.rate === 2.0 && !p.playback.keepPitch);
  // A whole speed is written as a whole number, as "%.4g" writes it.
  assert.ok(view(p).includes('"rate":2,"keepPitch":false'));
});

test("a_host_away_a_while_hands_the_seat_to_someone_here", () => {
  const p = party();
  p.join(member("b"), 0);
  // Longer than the wait, so it is still playing when the seat moves.
  p.add("h", { ...song("One"), durationMs: P.HOST_AWAY_MS * 3 }, "", 0);
  p.setAway("h", true, 1_000);
  p.setAway("a", true, 1_000);
  // Not yet: the host may only be switching apps.
  assert.equal(p.tick(1_000 + P.HOST_AWAY_MS - 1).length, 0);
  assert.equal(p.host, "h");
  // Then to the first app member who is here, not one away too.
  const out = p.tick(1_000 + P.HOST_AWAY_MS);
  assert.equal(p.host, "b");
  assert.ok(has(out, '"kind":"host"'));
  // The song plays on, and the old host is back as a member.
  assert.ok(p.now && p.now.title === "One");
  p.setAway("h", false, 40_000);
  assert.equal(p.host, "b");
  // With no one else here the seat stays, for when the host is back.
  const q = party();
  q.setAway("h", true, 0);
  q.setAway("a", true, 0);
  q.tick(P.HOST_AWAY_MS * 2);
  assert.equal(q.host, "h");
});

test("a_catalogue_id_and_the_token_check_are_a_members_only", () => {
  const p = party();
  p.add("a", { ...song("One"), catalogId: "tidal:42" }, "", 0);
  p.add("a", song("Two"), "", 0);
  assert.ok(p.knowsToken("tok-h"));
  assert.ok(!p.knowsToken("stranger") && !p.knowsToken(""));
  assert.equal(p.catalogFor("tok-h", "s1"), "tidal:42");
  assert.equal(p.catalogFor("stranger", "s1"), "");
  assert.equal(p.catalogFor("tok-h", "s2"), "");
});

// The C++ test of its C framing ("the_c_way_in_frames_every_answer"), as the
// same answers in TypeScript's shape.
test("the_way_in_answers_every_move", () => {
  const p = new Party("ABCD", "Friday", { id: "h", name: "Host", user: null, token: "tok-h" }, LIMIT);
  assert.equal(p.name, "Friday");
  const added = outOf(p.add("h", { title: "One", artist: "Band", album: "", durationMs: 100_000, catalogId: "tidal:1", share: true }, "r", 5));
  assert.equal(added[0].to, "h");
  assert.ok(text(added[0]).startsWith('{"t":"added"'));
  assert.equal(added[1].to, "");
  assert.ok(text(added[1]).startsWith('{"t":"line"'));
  assert.deepEqual(p.pause("nobody", 6), { ok: false, error: "only the host can do that" });
  assert.deepEqual(p.uploadLimit("h", "s1", 0), { limit: LIMIT });
  assert.equal(p.token("ghost"), null);
  assert.equal(p.songFor("tok-h", "s1"), null);
});

// ---- src/party.rs's own tests ----------------------------------------------

function rsParty(): Party {
  const p = new Party("ABCD", "", { id: "h", name: "Hôst", token: "tok-h" }, 1024 * 1024);
  assert.ok(p.join({ id: "a", name: "A", token: "tok-a" }, 0).ok);
  return p;
}

test("a_move_comes_back_as_messages_to_whom_they_are_for", () => {
  const p = rsParty();
  const out = outOf(p.add("a", { title: "Air for Life", artist: "Above & Beyond", durationMs: 352_000, isrc: "GBX", share: true }, "r1", 7));
  assert.equal(out[0].to, "a");
  assert.equal(out[0].msg.itemId, "s1");
  assert.equal(out[2].to, "");
  const party = lastParty(out);
  assert.equal(party.name, "Hôst's party");
  assert.equal(party.now.isrc, "GBX");
  assert.equal(party.now.shared.ready, false);
  assert.equal(party.playback.atServerMs, 7);
  assert.equal(p.host, "h");
  assert.deepEqual(p.memberIds(), ["h", "a"]);
});

test("a_refusal_comes_back_as_why", () => {
  const p = rsParty();
  assert.equal(err(p.pause("a", 0)), "only the host can do that");
  assert.equal(err(p.skip("h", "s9", 0)), "nothing is playing");
  assert.equal(p.token("ghost"), null);
  assert.equal(p.token("a"), "tok-a");
});

test("what_anyone_types_comes_through_whole", () => {
  const p = rsParty();
  const said = 'a "quote", a tab\t, a new line\n, 日本語, and a NUL\0 gone';
  const out = outOf(p.chat("a", said, 1));
  // Through the wire and back, as the app reads it.
  assert.equal(JSON.parse(text(out[0])).line.text, said.replace("\0", ""));
});

test("a_shared_song_and_its_files_find_their_way_back", () => {
  const p = rsParty();
  p.add("a", { title: "One", durationMs: 1000, share: true }, "", 0);
  assert.deepEqual(p.uploadLimit("a", "s1", 10), { limit: 1024 * 1024 });
  assert.ok("error" in p.uploadLimit("h", "s1", 10));
  assert.ok(p.shared("a", "s1", 10, "flac", "/songs/a b.flac").ok);
  assert.deepEqual(p.songFor("tok-h", "s1"), { song: { ready: true, bytes: 10, received: 10, ext: "flac" }, path: "/songs/a b.flac" });
  assert.equal(p.sharedBytes(), 10);
  p.tick(1000 + 750);
  assert.deepEqual(p.takeDropped(), ["/songs/a b.flac"]);
  assert.deepEqual(p.takeDropped(), []);
  assert.deepEqual(p.files(), []);
});

// ---- the rules tests/party.rs holds through sockets ------------------------

function maya(): Party {
  return new Party("K7QD", "Friday night", { id: "maya", name: "Maya", token: "tok-m" }, LIMIT);
}
const T0 = 1_760_000_000_000;

test("a_party_is_joined_by_code_and_everyone_follows_one_clock", () => {
  const p = maya();
  assert.equal(p.name, "Friday night");
  assert.equal(p.viewJson().hostId, "maya");
  const joined = outOf(p.join({ id: "jonas", name: "Jonas", token: "tok-j" }, T0));
  assert.deepEqual(joined[0].msg, { t: "joined", code: "K7QD", songToken: "tok-j" });
  // Nobody has said anything yet; Jonas's own arrival comes as a line.
  assert.deepEqual(joined[1].msg, { t: "history", lines: [] });
  assert.deepEqual(joined[2].msg, { t: "line", line: { kind: "join", id: "jonas", name: "Jonas", text: "", at: T0 } });
  assert.equal(lastParty(joined).members.length, 2);
  // Jonas adds a song: it plays at once, from the start, on the server's clock.
  const added = outOf(p.add("jonas", { title: "Air for Life", artist: "Above & Beyond", durationMs: 352_000, isrc: "GBX" }, "x1", T0 + 5));
  assert.equal(added[0].msg.ref, "x1");
  const now = lastParty(added);
  assert.equal(now.now.addedBy, "jonas");
  assert.equal(now.now.addedByName, "Jonas");
  assert.equal(now.playback.itemId, added[0].msg.itemId);
  assert.equal(now.playback.paused, false);
  assert.equal(now.playback.atServerMs, T0 + 5);
  // Only the host pauses; everyone sees the pause.
  assert.equal(err(p.pause("jonas", T0 + 6)), "only the host can do that");
  assert.equal(lastParty(outOf(p.pause("maya", T0 + 1005))).playback.paused, true);
  const sought = lastParty(outOf(p.seek("maya", 60_000, T0 + 2000)));
  assert.equal(sought.playback.positionMs, 60_000);
  assert.equal(sought.playback.paused, true);
  // Two here: one vote skips. A second vote for the same song is too late.
  assert.equal(lastParty(outOf(p.vote("jonas", "s1", true, T0 + 3000))).now, null);
  assert.equal(err(p.vote("maya", "s1", true, T0 + 3001)), "nothing is playing");
  // Chat goes round with the server's time on it.
  const said = outOf(p.chat("jonas", "this drop though", T0 + 4000));
  assert.deepEqual(said[0].msg, { t: "line", line: { kind: "say", id: "jonas", name: "Jonas", text: "this drop though", at: T0 + 4000 } });
});

test("a_website_guest_listens_and_chats_but_adds_only_catalogue_songs", () => {
  const p = maya();
  const out = outOf(p.join({ id: "sam", name: "Sam", guest: true, web: true, token: "t".repeat(32) }, T0));
  assert.equal((out[0].msg.songToken as string).length, 32);
  const g = lastParty(out).members[1];
  assert.equal(g.guest, true);
  assert.equal(g.web, true);
  assert.equal(err(p.add("sam", { title: "Tone", artist: "Above & Beyond", durationMs: 352_000, isrc: "GBX" }, "", T0)), "a website guest can add only songs from the catalogue");
  const said = outOf(p.chat("sam", "hi from the web", T0));
  assert.equal((said[0].msg.line as any).name, "Sam");
  // What a catalogue search found goes in the queue as it came.
  const found = outOf(p.add("sam", { title: "One More Time", artist: "Daft Punk", durationMs: 320_000, catalogId: "tidal:1550546" }, "w1", T0));
  assert.equal(found[0].to, "sam");
  assert.deepEqual(found[0].msg, { t: "added", ref: "w1", itemId: "s1" });
  assert.equal(lastParty(found).now.catalogId, "tidal:1550546");
  assert.equal(lastParty(found).now.addedByName, "Sam");
  assert.equal(p.catalogFor("t".repeat(32), "s1"), "tidal:1550546");
});

test("a_shared_song_uploads_with_progress_and_plays", () => {
  const p = maya();
  p.join({ id: "sam", name: "Sam", guest: true, web: true, token: "tok-s" }, T0);
  const added = outOf(p.add("maya", { title: "Above Heaven", artist: "Above & Beyond", durationMs: 352_000, isrc: "GBX", share: true }, "", T0));
  assert.equal(lastParty(added).now.shared.ready, false);
  // Before it is uploaded, nothing to fetch.
  assert.equal(p.songFor("tok-s", "s1"), null);
  // Only whoever added it shares it.
  p.join({ id: "jonas", name: "Jonas", token: "tok-j" }, T0);
  assert.deepEqual(p.uploadLimit("jonas", "s1", 10), { error: "only whoever added a song shares it" });
  assert.equal(err(p.shared("jonas", "s1", 10, "mp3", "/x")), "only whoever added a song shares it");
  const done = lastParty(outOf(p.shared("maya", "s1", 300_000, "mp3", "/songs/s1")));
  assert.equal(done.now.shared.ready, true);
  assert.equal(done.now.shared.bytes, 300_000);
  assert.equal(done.now.shared.ext, "mp3");
  assert.equal(done.sharedBytes, 300_000);
  assert.equal(p.songFor("tok-s", "s1")!.path, "/songs/s1");
  assert.equal(p.songFor("0".repeat(32), "s1"), null);
  // Too big for the server's limit: refused, and the song shows as not shared.
  p.add("maya", { title: "Tone", artist: "Above & Beyond", durationMs: 352_000, isrc: "GBX", share: true }, "", T0);
  assert.deepEqual(p.uploadLimit("maya", "s2", LIMIT * 2), { error: "the song is too big to share" });
  assert.equal(lastParty(p.uploadFailed("s2")).queue[0].shared, null);
  // Skipped, the song's file is let go.
  const skipped = outOf(p.skip("maya", "s1", T0 + 1));
  assert.equal(lastParty(skipped).now.id, "s2");
  assert.deepEqual(p.takeDropped(), ["/songs/s1"]);
  assert.equal(p.songFor("tok-s", "s1"), null);
});

test("a_phone_that_loses_its_connection_keeps_its_seat_and_then_passes_it", () => {
  const p = maya();
  p.join({ id: "jonas", name: "Jonas", token: "tok-j" }, T0);
  // Maya's Wi-Fi drops: she is away, still the host.
  const away = lastParty(p.setAway("maya", true, T0));
  assert.equal(away.members[0].away, true);
  assert.equal(away.hostId, "maya");
  outOf(p.chat("jonas", "where did she go", T0 + 1));
  // Back: the same seat, the chat she missed.
  const back = p.welcomeBack("maya");
  assert.deepEqual(back.map((m) => [m.to, m.msg.t]), [["maya", "joined"], ["maya", "history"], ["maya", "party"]]);
  assert.ok((back[1].msg.lines as any[]).some((l) => l.text === "where did she go"));
  const here = lastParty(p.setAway("maya", false, T0 + 2));
  assert.equal(here.members[0].away, false);
  assert.equal(here.hostId, "maya");
  assert.equal(err(p.pause("maya", T0 + 3)), "nothing is playing");
  // Gone longer than the seat is kept: she leaves, and the seat passes.
  const left = lastParty(p.leave("maya", T0 + 4));
  assert.equal(left.members.length, 1);
  assert.equal(left.hostId, left.members[0].id);
  // Nobody to come back to; welcome_back says nothing to a stranger.
  assert.deepEqual(p.welcomeBack("maya"), []);
  // The last one out: nothing to tell, and the hub lets the party go.
  assert.deepEqual(p.leave("jonas", T0 + 5), []);
  assert.ok(p.empty());
});

test("the_host_hands_the_seat_on_and_ends_the_party_for_everyone", () => {
  const p = maya();
  p.join({ id: "jonas", name: "Jonas", token: "tok-j" }, T0);
  assert.equal(err(p.end("jonas")), "only the host can do that");
  const passed = outOf(p.passHost("maya", "jonas", T0));
  assert.equal(lastParty(passed).hostId, "jonas");
  assert.equal((passed[0].msg.line as any).kind, "host");
  assert.equal((passed[0].msg.line as any).name, "Jonas");
  assert.deepEqual(outOf(p.end("jonas")), [{ to: "", msg: { t: "left", reason: "the host ended the party" } }]);
});

test("a_cover_is_kept_only_over_https", () => {
  const p = maya();
  p.join({ id: "sam", name: "Sam", guest: true, web: true, token: "tok-s" }, T0);
  p.add("maya", { title: "One", durationMs: 60_000, cover: "http://plain.example/one.jpg" }, "", T0);
  assert.equal(p.coverFor("tok-s", "s1"), "");
  assert.equal(p.coverFor("nobody", "s1"), "");
  p.add("maya", { title: "Two", durationMs: 60_000, cover: " https://img.example/two.jpg " }, "", T0);
  assert.equal(p.coverFor("tok-s", "s2"), "https://img.example/two.jpg");
});

test("the_hosts_speed_reaches_everyone_and_only_the_host_sets_it", () => {
  const p = maya();
  p.join({ id: "sam", name: "Sam", guest: true, web: true, token: "tok-s" }, T0);
  assert.equal(err(p.setRate("sam", 2.0, false, T0)), "only the host can do that");
  // Vinyl style: the pitch rides the speed.
  const vinyl = lastParty(outOf(p.setRate("maya", 1.25, false, T0))).playback;
  assert.equal(vinyl.rate, 1.25);
  assert.equal(vinyl.keepPitch, false);
  const kept = lastParty(outOf(p.setRate("maya", 0.8, true, T0))).playback;
  assert.equal(kept.rate, 0.8);
  assert.equal(kept.keepPitch, true);
  assert.equal(err(p.setRate("maya", 9.0, false, T0)), "the speed must be between 0.25x and 4x");
  assert.ok(p.setRate("maya", P.MIN_RATE, true, T0).ok && p.setRate("maya", P.MAX_RATE, true, T0).ok);
});

test("one_seat_let_go_leaves_the_other_member_as_host", () => {
  const p = maya();
  p.join({ id: "sam", name: "Sam", user: "sam", token: "tok-s" }, T0);
  const left = lastParty(p.leave("maya", T0));
  assert.equal(left.members.length, 1);
  assert.equal(left.members[0].name, "Sam");
  assert.equal(left.members[0].user, "sam");
  assert.equal(left.hostId, left.members[0].id);
});

test("a_website_guest_who_closes_the_tab_and_comes_back_is_there_once", () => {
  const p = maya();
  const ana = { id: "ana1", name: "Ana", guest: true, web: true, token: "tok-1" };
  p.join(ana, T0);
  assert.equal(lastParty(p.leave("ana1", T0)).members.length, 1);
  const again = lastParty(outOf(p.join({ ...ana, id: "ana2", token: "tok-2" }, T0)));
  assert.equal(again.members.filter((m: any) => m.name === "Ana").length, 1);
  assert.equal(err(p.join({ ...ana, id: "ana2" }, T0)), "already in the party");
});

// ---- the wire, as party.cpp writes it --------------------------------------

test("a_speed_is_written_as_printf_4g_writes_it", () => {
  assert.equal(P.rateValue(1), 1);
  assert.equal(P.rateValue(1.25), 1.25);
  assert.equal(P.rateValue(0.8), 0.8);
  assert.equal(P.rateValue(1 / 3), 0.3333);
  assert.equal(P.rateValue(2 / 3), 0.6667);
  assert.equal(P.rateValue(3.99999), 4);
  assert.equal(P.rateValue(1.23456), 1.235);
  // An exact tie goes to the even digit, as glibc's printf does (toPrecision would round up).
  assert.equal(P.rateValue(1.0625), 1.062);
  assert.equal(P.rateValue(0.28125), 0.2812);
  assert.equal(P.rateValue(1.1875), 1.188);
  assert.equal(JSON.stringify(P.rateValue(0.25)), "0.25");
  assert.equal(JSON.stringify(P.rateValue(4)), "4");
});

test("the_whole_party_is_the_json_party_cpp_writes", () => {
  const p = new Party("K7QD", "  Friday night  ", { id: "h", name: "Maya", token: "tok-h" }, LIMIT);
  p.join({ id: "j", name: "Jonas", user: "acc-1", token: "tok-j" }, 10);
  p.join({ id: "g", name: "Sam", guest: true, web: true, token: "tok-g" }, 20);
  p.add("j", { title: "Air for Life", artist: "Above & Beyond", album: "", durationMs: 352_000, isrc: "GBX", share: true }, "r1", 1000);
  p.add("h", { title: "  Two  ", artist: "Band", album: "LP", durationMs: 200_000, catalogId: "tidal:42", cover: "https://img.example/two.jpg", audioHash: "  " }, "", 2000);
  p.uploading("s1", 300, 100);
  p.shared("j", "s1", 300, "mp3", "/s/1");
  p.setRate("h", 1.0625, false, 3000);
  p.setAway("g", true, 4000);
  p.vote("g", "s1", true, 4100);
  p.pause("h", 5000);
  // Written by hand from party.cpp's view_json, member_json, item_json and song_json.
  const expected =
    '{"code":"K7QD","name":"Friday night","hostId":"h","members":[' +
    '{"id":"h","name":"Maya","user":null,"guest":false,"web":false,"away":false},' +
    '{"id":"j","name":"Jonas","user":"acc-1","guest":false,"web":false,"away":false},' +
    '{"id":"g","name":"Sam","user":null,"guest":true,"web":true,"away":true}],' +
    '"now":{"id":"s1","title":"Air for Life","artist":"Above & Beyond","album":"","durationMs":352000,' +
    '"addedBy":"j","addedByName":"Jonas","isrc":"GBX","catalogId":null,"audioHash":null,"cover":null,' +
    '"shared":{"ready":true,"bytes":300,"received":300,"ext":"mp3"}},' +
    '"queue":[{"id":"s2","title":"Two","artist":"Band","album":"LP","durationMs":200000,' +
    '"addedBy":"h","addedByName":"Maya","isrc":null,"catalogId":"tidal:42","audioHash":null,' +
    '"cover":"https://img.example/two.jpg","shared":null}],' +
    '"playback":{"itemId":"s1","positionMs":4125,"atServerMs":5000,"paused":true,"rate":1.062,"keepPitch":false},' +
    '"skip":{"votes":["g"],"needed":1},"sharedBytes":300,"sharedLimit":1073741824}';
  assert.equal(view(p), expected);
  // The same object goes out as `party.party`, and item_for hands out one song of it.
  assert.equal(JSON.stringify(p.welcomeBack("j")[2].msg), '{"t":"party","party":' + expected + "}");
  assert.equal(JSON.stringify(p.itemFor("tok-g", "s2")), JSON.stringify((p.viewJson().queue as unknown[])[0]));
  assert.equal(p.itemFor("stranger", "s2"), null);
  // A line and the history, field for field.
  const said = outOf(p.chat("j", ' "hi"\\ ', 6000));
  assert.equal(text(said[0]), '{"t":"line","line":{"kind":"say","id":"j","name":"Jonas","text":"\\"hi\\"\\\\","at":6000}}');
  const history = text(p.welcomeBack("h")[1]);
  assert.ok(history.startsWith('{"t":"history","lines":[{"kind":"join","id":"j","name":"Jonas","text":"","at":10},'));
  assert.ok(history.includes('{"kind":"join","id":"g","name":"Sam","text":"web","at":20}'));
  assert.ok(history.includes('{"kind":"add","id":"j","name":"Jonas","text":"Air for Life","at":1000}'));
});

test("votes_are_listed_in_std_set_order", () => {
  const p = new Party("ABCD", "", member("z"), LIMIT);
  for (const id of ["b", "A", "￿", "😀", "a", "c"]) p.join(member(id), 0);
  p.add("z", song("One"), "", 0);
  // Seven here: four needed, so three votes stay votes.
  for (const id of ["😀", "￿", "b"]) p.vote(id, null, true, 0);
  // Code point (UTF-8 byte) order, not UTF-16's: U+FFFF before U+1F600.
  assert.deepEqual(p.votes(), ["b", "￿", "😀"]);
});

console.log(`party: ${passed} passed`);
if (failed) {
  console.error(`party: ${failed} failed`);
  process.exit(1);
}

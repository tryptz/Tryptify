// The web player's pure parts (site/public/js/wp-sources.js): file names and
// folders into a library, Trypt HiFi's and the account's rows into queue
// tracks, addresses, and that the engine's filters are the ones the player
// plays (the coefficients it hands to IIR filters give the engine's curve).
//   node site/tests/webplayer.mjs
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import { createHash } from "node:crypto";

const here = dirname(fileURLToPath(import.meta.url));
const ctx = { window: {}, Math, Number, Float64Array, Set, Map, URL, URLSearchParams, TextEncoder, Uint8Array, Uint32Array, localStorage: { getItem: () => null, setItem() {}, removeItem() {} } };
vm.runInNewContext(readFileSync(join(here, "../public/js/autoeq-engine.js"), "utf8"), ctx);
vm.runInNewContext(readFileSync(join(here, "../public/js/wp-sources.js"), "utf8"), ctx);
const E = ctx.window.TryptifyAutoEq;
const U = ctx.window.TryptifySources.util;

let failed = 0;
const eq = (label, got, want) => {
  const a = JSON.stringify(got), b = JSON.stringify(want);
  if (a !== b) { failed++; console.log(`FAIL ${label}\n  got  ${a}\n  want ${b}`); }
};

// ── File names ──
eq("01 - Title", U.parseFileName("01 - Title.flac", ["Artist", "Album"]), { n: 1, title: "Title", artist: "Artist", album: "Album" });
eq("disc-track", U.parseFileName("1-03 Third.wav", ["A", "B"]), { n: 3, title: "Third", artist: "A", album: "B" });
eq("12. Title", U.parseFileName("12. Song.mp3", ["Album"]), { n: 12, title: "Song", artist: "", album: "Album" });
eq("no number", U.parseFileName("Song.m4a", []), { n: null, title: "Song", artist: "", album: "" });
eq("audio files", ["a.flac", "b.MP3", "c.jpg", "._d.flac", "e.cue"].map(U.isAudio), [true, true, false, false, false]);

// ── Folders ──
const t = (path, name, n) => ({ parents: path, track: { file: name, n } });
const tree = U.buildTree([t(["B", "X"], "2.flac", 2), t(["B", "X"], "1.flac", 1), t(["A"], "z.flac", null), t([], "root.flac", null)], "Music");
eq("folders sorted", tree.dirs.map((d) => d.name), ["A", "B"]);
eq("tracks by number", tree.byName.get("B").byName.get("X").files.map((f) => f.file), ["1.flac", "2.flac"]);
eq("all files", U.allFiles(tree).map((f) => f.file), ["root.flac", "z.flac", "1.flac", "2.flac"]);
eq("folder path", tree.byName.get("B").byName.get("X").path, ["B", "X"]);

// ── Addresses ──
eq("bare host", U.normalizeBase("hifi.example.com/"), "https://hifi.example.com");
eq("keeps path", U.normalizeBase("https://x.dev/hifi//"), "https://x.dev/hifi");
eq("empty", U.normalizeBase("  "), null);

// ── Trypt HiFi tracks (lib/tidal.tsx normalizeTidalTrack) ──
const album = { id: "7", title: "Al", artist: { name: "Ar" }, image: { small: "https://img/s.jpg" } };
const ht = U.hifiTrack({ id: 5, title: "T", version: "Live", performer: { name: "P" }, track_number: 3, duration: 200, hires: false }, album, "HI_RES_LOSSLESS");
eq("hifi track", [ht.key, ht.title, ht.artist, ht.album, ht.n, ht.quality, ht.cover, ht.ref.id], ["hifi:5", "T (Live)", "P", "Al", 3, "FLAC 16/44.1", "https://img/s.jpg", "5"]);

// ── The account's rows (Supabase favorite_tracks / playlist_tracks) ──
eq("cover id", U.coverUrl("ab-cd-ef"), "https://resources.tidal.com/images/ab/cd/ef/320x320.jpg");
eq("cover url kept", U.coverUrl("https://x/y.jpg"), "https://x/y.jpg");
eq("device cover dropped", U.coverUrl("/storage/x.jpg"), null);
const liked = U.savedTrack({ id: 9, title: "L", artist_name: "A", album_title: "B", album_cover: "aa-bb", duration: 61, audio_quality: "LOSSLESS" }, "HI_RES_LOSSLESS");
eq("liked song", [liked.key, liked.src, liked.quality, liked.ref.hires, liked.duration], ["hifi:9", "hifi", "FLAC 16/44.1", false, 61]);
const listed = U.savedTrack({ track_id: 11, title: "P", position: 0 }, "HI_RES_LOSSLESS");
eq("playlist song asks for the best", [listed.key, listed.ref.hires, listed.quality], ["hifi:11", true, "FLAC 24-bit"]);

// ── Cloud songs ──
const c = U.cloudTrack({ id: "s1", title: "C", artist: "A", suffix: "flac", bitDepth: 24, sampleRate: 96000, duration: 3 });
eq("cloud song", [c.key, c.quality], ["cloud:s1", "FLAC 24/96"]);

// ── Music server (Subsonic): the token is md5(password + salt) ──
for (const str of ["", "sesame" + "c19b2d", "x".repeat(55), "y".repeat(56), "z".repeat(64), "pässwörd ✓".repeat(9)])
  eq(`md5 of ${str.length} chars`, U.md5(str), createHash("md5").update(str).digest("hex"));
const sub = U.subsonicTrack({ id: 4, title: "S", artist: "A", album: "B", track: 2, duration: 9, suffix: "flac", bitDepth: 24, samplingRate: 96000, coverArt: "c" });
eq("server song", [sub.key, sub.src, sub.quality, sub.n, sub.ref.coverArt], ["subsonic:4", "subsonic", "FLAC 24/96", 2, "c"]);
eq("server mp3", U.subsonicTrack({ id: 5, suffix: "mp3", bitRate: 320 }).quality, "MP3 320");

// ── The filters the player builds ──
// coeffs → H(e^jw) by hand, against the engine's own response, at the rates browsers run.
const band = { type: "PEAKING", freq: 3000, gain: 6, q: 2 };
for (const sr of [44100, 48000, 96000]) {
  const [b0, b1, b2, a1, a2] = E.coeffs(band, sr);
  for (const f of [100, 1000, 3000, 9000]) {
    const w = (2 * Math.PI * f) / sr;
    const re = (b0 + b1 * Math.cos(w) + b2 * Math.cos(2 * w)), im = -(b1 * Math.sin(w) + b2 * Math.sin(2 * w));
    const dre = (1 + a1 * Math.cos(w) + a2 * Math.cos(2 * w)), dim = -(a1 * Math.sin(w) + a2 * Math.sin(2 * w));
    const db = 10 * Math.log10((re * re + im * im) / (dre * dre + dim * dim));
    if (Math.abs(db - E.response(f, band, sr)) > 1e-3) { failed++; console.log(`FAIL filter at ${sr} Hz, ${f} Hz: ${db} vs ${E.response(f, band, sr)}`); }
  }
}

console.log(failed ? `${failed} check(s) failed` : "web player: all checks pass");
process.exit(failed ? 1 : 0);

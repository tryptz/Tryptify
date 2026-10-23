// SPDX-License-Identifier: GPL-3.0-or-later
// ffmpeg, for the website: a shared song in a codec no browser plays (ALAC,
// WMA, APE, AIFF, DSD) is turned into AAC once, kept beside the original in
// R2 and deleted with it, as the Rust server did on its disk
// (src/transcode.rs there). A Worker cannot run ffmpeg, so it runs in a
// small Cloudflare Container (ffmpeg/), started only when a web guest asks
// for such a song and asleep a few minutes after.

import { Container } from "@cloudflare/containers";

export class Ffmpeg extends Container {
  defaultPort = 8080;
  sleepAfter = "3m";
  // It needs nothing from the internet: the song comes in the request.
  enableInternet = false;
}

/** Where the website's copy of the shared song `file` is kept. */
export const webKey = (file: string) => `${file}.web.m4a`;

/** The codecs every browser the website runs in plays (the Rust server's list). */
export const BROWSER_PLAYS = new Set(["mp3", "aac", "flac", "opus", "vorbis", "pcm_s16le", "pcm_s24le", "pcm_f32le", "pcm_u8"]);

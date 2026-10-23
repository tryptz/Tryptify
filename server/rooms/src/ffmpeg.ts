// SPDX-License-Identifier: GPL-3.0-or-later
// ffmpeg, for the website: a shared song in a codec no browser plays (ALAC,
// WMA, APE, AIFF, DSD) is turned into AAC once, kept beside the original in
// R2 and deleted with it, as the Rust server did on its disk
// (src/transcode.rs there). A Worker cannot run ffmpeg, so it runs on
// Railway (ffmpeg/): FFMPEG_URL, its key the secret FFMPEG_KEY.

/** Where the website's copy of the shared song `file` is kept. */
export const webKey = (file: string) => `${file}.web.m4a`;

/**
 * Kinds of file whose every codec a browser plays, so they never go to ffmpeg.
 * Not m4a: that is AAC or ALAC, and only ffprobe can tell.
 */
export const PLAYS_AS_IS = new Set(["mp3", "flac", "opus", "ogg", "oga", "wav"]);

# Spotify playback

Spotify tracks play through one of two routes, chosen per track by
`StreamResolver.spotifyPlaybackUri`:

1. **Native PCM, through the DSP** (preferred). An embedded librespot client
   signs in with the app's own Spotify token, decodes the song in-process, and
   ExoPlayer plays that PCM as a WAV — so `monochrome_dsp`, AutoEQ, the mixer,
   the visualizer taps and the USB DAC path all apply, exactly as for any
   other source. Needs Premium and the `streaming` scope.
2. **App Remote shadow** (fallback). When librespot can't sign in — or signs
   in and then can't play a track — the song plays **in the Spotify app**,
   driven by the official
   [App Remote SDK](https://github.com/spotify/android-sdk), while ExoPlayer
   plays a silent stand-in. This audio bypasses the DSP.

## Native PCM route

```
librespot (decode)  →  PcmSink  →  PcmPipe  →  PcmSinkDataSource  →  ExoPlayer  →  AudioProcessor chain (DSP)
   own thread          4 KiB      bounded      spotify-pcm:// as WAV    WavExtractor
                       writes     ring, 2 s
```

| Piece | Role |
|---|---|
| `SpotifyNativeSession` (app) | Signs librespot in on demand with `SpotifyAuthManager`'s access token; backs off 5 min after a failure. |
| `LibrespotPlayerWrapper` | The librespot `Session` + `Player`. `openStream(uri, startMs)` loads/seeks and starts a new pipe generation. Normalisation off, volume max, autoplay/preload off. |
| `PcmSink` | librespot's `SinkOutput`, built by reflection (keep rule in `spotify-wrapper/consumer-rules.pro`). Copies each write into the pipe. |
| `PcmPipe` | Bounded ring with **blocking** writes. ExoPlayer's read rate is the only clock. Generations stop a superseded stream's audio leaking into the next. |
| `PcmSinkDataSource` | Serves `spotify-pcm://track/<id>?durationMs=N`: a 44-byte WAV header from the duration, then the pipe's PCM. |

Contract: 44.1 kHz, 16-bit signed little-endian, stereo, interleaved —
librespot's output format. `PcmSink.start` checks it; any other format makes
the data source refuse rather than play noise under a wrong header.

Behaviour worth knowing before changing it:

- **Nothing mirrors play/pause into librespot.** Pausing is backpressure:
  ExoPlayer stops reading, the pipe fills, librespot's output thread blocks in
  `write`. librespot's own pause would call `SinkOutput.stop()`, and its
  position is never ExoPlayer's anyway (ExoPlayer buffers up to 120 s ahead).
- **Seeking** is ordinary WAV seeking: WavExtractor reopens the data source at
  a byte offset, and `open` → first read → `openStream` seeks librespot to that
  millisecond. librespot's seek flushes the sink.
- **The stream starts on the first PCM read**, not in `open`, so opening a
  source only to read its header (prepare, preload) does not take librespot
  away from the track that is playing.
- **Length**: the header uses Spotify's advertised duration. A short decode is
  padded with silence, and a long one is cut at the header's length. "Ended" is
  reported only after the pipe stays empty for 250 ms, because librespot says
  it's done while its last buffers are still in flight.
- **One stream at a time.** If a second Spotify item starts reading PCM while
  another is still loading (crossfade into a Spotify track whose predecessor
  isn't fully buffered, for example), the first is superseded and ends at
  whatever it had buffered.
- A stall of 10 s with no PCM throws a timeout; ExoPlayer's retry reopens at
  the same offset, which restarts librespot there.
- **A failure is not an end.** When librespot gives up on a track
  (`onPlaybackFailed`, or its panic state) the pipe is marked *failed*, not
  ended: whatever is buffered still plays, then the read throws instead of
  padding the rest of the track with silence. Before this, a track that never
  loaded played as a silent timeline running to its full length.
- **…and it falls back.** That error is final at the load level
  (`ERROR_CODE_IO_FILE_NOT_FOUND`, which ExoPlayer does not retry), so it
  reaches `PlaybackService.onPlayerError` at once. If the Spotify app is
  installed, the service records the failure in `SpotifyNativeSession` and
  replays the track from where it stopped; `StreamResolver` skips native while
  the failure stands, so the replay is the App Remote shadow. Native is tried
  again after 10 minutes, or straight away from Settings → Spotify → *Try
  again*, which also shows why it failed. Without the Spotify app, native
  stays the only route and the ordinary retry-then-skip applies.

Dependencies: `libs/librespot-player-stripped-1.6.5.jar` has no POM, so
`spotify-wrapper/build.gradle.kts` declares librespot's runtime dependencies
itself, at the versions librespot 1.6.5 pins.

Classes replaced in source: the jar has these removed, and
`spotify-wrapper/src/main/java/xyz/gianlu/librespot/` has Tryptify's versions.
Remove them again whenever the jar is regenerated, or the build fails with
duplicate classes.

| Class | Why |
| --- | --- |
| `core.TokenProvider` | 1.6.5 asked the retired keymaster endpoint for tokens; ours uses login5. |
| `core.ApResolver` | Tries access points in port order until one answers; always uses `spclient.wg.spotify.com` (upstream 5981fb5). |
| `dealer.ApiClient` | 1.6.5's `/metadata/4/track` has returned tracks with no audio files since November 2025 (every track: "no alternatives found"). Ours has upstream's extended-metadata fix (52a8c24). |

If extended metadata works but loading then stops at `Audio key error`, the
account is the problem, not the code: librespot needs Premium and valid
reusable credentials.

## Setup (once, in the Spotify Developer Dashboard)

App Remote authenticates the calling *app*, not just the user:

1. Open the app whose client id is `BuildConfig.SPOTIFY_CLIENT_ID`
   (`spotify.clientId` in `local.properties` overrides the default).
2. Under **Android packages**, add `tf.monotrypt.android` (the `applicationId`,
   not the Kotlin package) with the SHA-1 of
   every signing key you install builds with (debug and release differ):
   `keytool -list -v -keystore <keystore> -alias <alias>`.
3. The redirect URI `tryptify://spotify-callback` is already registered for the
   existing sign-in; App Remote reuses it.
4. In Development mode, the Spotify account must be on the app's allowlist
   (User Management), as for playlist import.

On the phone: the Spotify app installed and logged in, on a **Premium**
account (App Remote refuses to start a specific track otherwise).

## App Remote shadow route

| Piece | Role |
|---|---|
| `SpotifyApiClient.searchTracks` | Web API search as the connected user; feeds `SearchViewModel`'s Spotify leg. |
| `toSpotifyUnifiedTrack` | Maps a result to `PlaybackSource.SpotifyRemote(spotifyUri, durationMs)`, `SourceType.SPOTIFY`. |
| `StreamResolver.resolveSpotifyRemote` | Emits a `spotify-shadow://track/<id>?durationMs=N` MediaItem. Unplayable when the Spotify app is missing. |
| `SilentWavDataSource` | Serves that URI as a silent 44.1 kHz/16-bit/stereo WAV of the song's length, generated on the fly, seekable. |
| `SpotifyPlaybackBridge` | Mirrors ExoPlayer ⇄ Spotify (see below). |
| `SpotifyAppRemoteClient` | Coroutine wrapper over App Remote: connect, play, pause, resume, seek, `PlayerState` flow. |

**ExoPlayer remains the single state owner** (Playback Routing playbook). The
silent shadow is what lets the notification, lock screen, scrubber, queue and
end-of-track advance work with no Spotify-specific code. The bridge:

- starts the song in Spotify when a shadow becomes current and the player wants
  to play (seeking Spotify to the player's position when resuming mid-track);
- repeats play/pause and user seeks to Spotify; pauses Spotify when the shadow
  stops being current, ends, or the player stops;
- applies Spotify-side pauses/resumes to the player, disengages and pauses when
  a different song is chosen in Spotify, and corrects drift over 2 s by moving
  the silent play head — changes made on Spotify's behalf are not echoed back;
- turns ExoPlayer's audio-focus handling **off** while a shadow is current, so
  Spotify taking focus doesn't pause the player (which would be mirrored as a
  pause back to Spotify).

## Known limits

- On the shadow route only: Spotify audio bypasses `monochrome_dsp`, AutoEQ,
  the mixer and the USB DAC path, and crossfade is a cut on the Spotify side.
  The native route has neither limit.
- Native sign-in needs the `streaming` scope. Accounts connected before it was
  added keep using the shadow route until they reconnect Spotify.
- Search is tracks only, one page (10). Spotify artists/albums have no pages
  here — their ids are base62 and every catalogue screen takes a numeric id.
- Mirroring reacts to `PlayerState` events, which Spotify sends on change, not
  continuously; drift is corrected at those moments.

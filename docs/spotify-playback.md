# Spotify playback

Spotify tracks found in search play **in the Spotify app**, driven by the
official [App Remote SDK](https://github.com/spotify/android-sdk). Tryptify
never receives, stores or decodes Spotify audio.

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

## How it fits the player

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

- Spotify audio bypasses `monochrome_dsp`, AutoEQ, the mixer and the USB DAC
  path. With exclusive USB output active, Spotify is heard wherever Android
  routes it, not through the DAC.
- Crossfade into or out of a Spotify track is a cut on the Spotify side: the
  ramp only ever applies to the silent shadow.
- Search is tracks only, one page (10). Spotify artists/albums have no pages
  here — their ids are base62 and every catalogue screen takes a numeric id.
- Mirroring reacts to `PlayerState` events, which Spotify sends on change, not
  continuously; drift is corrected at those moments.

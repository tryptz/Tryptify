# Spotify playback

Spotify search, albums and artists stay in Spotify's own catalogue namespace.
Playback requires the embedded librespot client: decoded 44.1 kHz/16-bit stereo
PCM is wrapped as a `spotify-pcm://` WAV stream and handed to the same ExoPlayer
instance as every other source. That puts Spotify through Tryptify's DSP,
AutoEQ, mixer, visualizer tap and USB route.

There is no App Remote or silent-shadow fallback. If native authentication or
decoding fails, the item is reported unplayable instead of bypassing the DSP.

## Setup

1. Connect Spotify in Tryptify with a Premium account. The PKCE request includes
   the `streaming` scope used for native playback.
2. Accounts connected by an older build must disconnect and reconnect once to
   grant that added scope.

## Signal path

| Piece | Role |
|---|---|
| `SpotifyApiClient` | Searches Spotify and opens Spotify album/artist pages. |
| `SpotifyNativePlayback` | Reuses the app's PKCE token, owns librespot, and mirrors play/pause/seek. |
| `LibrespotPlayerWrapper` | Authenticates and decodes the selected Spotify URI into `PcmSink`. |
| `PcmSinkDataSource` | Presents decoded PCM as a finite WAV stream to Media3. |
| `StreamResolver` | Emits `spotify-pcm://` only after native setup succeeds; otherwise marks the item unplayable. |

Spotify's cache remains private implementation storage. Tryptify does not expose
it as a downloaded audio file: Spotify has no supported API for exporting tracks,
and a Spotify id is never sent through the Qobuz, TIDAL or Apple downloader.

## Known limits

- Native playback requires Spotify Premium and a fresh grant containing the
  `streaming` scope.
- The first native play opens a librespot session and can take longer than later
  tracks; its stored credential and read-through cache live in app-private data.
- Native setup failure stops the Spotify item; it never changes to an external
  playback path.

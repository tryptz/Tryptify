# Tryptify rooms

Trypt Mania's multiplayer game rooms and the app's listening parties, on
Cloudflare at `https://rooms.tryptify.com`. A Worker in front (`src/worker.ts`)
and one Durable Object behind it (`src/hub.ts`) that holds every room, every
party and every socket, as the Rust server it replaces held them in one
process. Shared songs live in an R2 bucket while their room or party wants
them.

It is [tryptz/tryptify-party](https://github.com/tryptz/tryptify-party) moved
over as it was, so the app needs nothing new on the wire:

- `src/party.ts`: the listening party's rules, a port of its C++
  (`cpp/party.cpp`), checked against the compiled C++ byte for byte on
  random games. Everyone queues; only the host pauses, seeks, reorders and
  skips; half the room skips by vote; the party keeps the song's one clock.
- `src/room.ts`: Trypt Mania's rooms, a port of its Rust (`src/room.rs`,
  `src/protocol.rs`), its JSON written as serde wrote it (`encode`: `1.0`, not
  `1`). A host picks a chart, everyone with the song readies, all start on
  the same server moment, scores go round four times a second.
- `src/catalog.ts`: the website's Add songs, against a TrypT HiFi server
  (`TRYPT_HIFI_URL`, its password the secret `TRYPT_HIFI_KEY`, sent as
  `X-Access-Key` to its own origin only). A Deezer song plays in a browser
  through TrypT HiFi's `decrypted=1`.
- `src/protocol.ts`: what a phone says, read as serde read it.

```
GET  /health                         liveness
GET  /api/multiplayer/info           what this is; the app's probe
GET  /ws                             one socket per phone or browser tab
PUT  /api/multiplayer/song?hash=…    the host shares the picked chart's song
GET  /api/multiplayer/song?hash=…    a player in the room fetches it
PUT  /api/party/song?item=…&ext=…    party: whoever added a song shares it
GET  /api/party/song?item=…&token=…  party: a member fetches it (Range too)
GET  /api/party/cover|search|stream  party: the website's cover, Add songs, catalogue songs
GET  /party/<code>                   an older app's invite: sent on to tryptify.com
GET  /.well-known/assetlinks.json    lets the app open this host's invite links
```

The guests' page is the website's: `https://tryptify.com/party/<code>`
(`public/party.html` and `js/party.js` in [tryptz/tryptify.com](https://github.com/tryptz/tryptify.com)), which is also the invite link the
app hands out. It calls the party routes from another origin with the song
token the party gave it, never a cookie, so they answer CORS for any origin.

## What changed in the move

- **Shared songs are at most 99 MB**, not 128 MiB: a Worker takes a request
  body of at most 100 MB on this zone's plan. An upload must say its length
  (`Content-Length`), as the phones do.
- **ffmpeg runs on Railway.** As the Rust server did, a shared song in a
  codec no browser plays (ALAC, WMA, APE, AIFF, DSD) reaches the website as
  AAC 256k. A Worker cannot run ffmpeg, so `ffmpeg/` is a small service on
  Railway (`Dockerfile`, `server.py`). The first time a
  browser asks for such a song (`web=1`), the hub sends it there and keeps the
  copy in R2 next to the original (`<file>.web.m4a`), deleted with it. An
  mp3, flac, opus, ogg or wav never goes (`PLAYS_AS_IS`); an m4a does, since
  only ffprobe can tell AAC from ALAC, and comes back 204 when it is AAC.
  Phones always get the original, and so does the website while the service
  is down or not set up.
- **No address secret.** The Railway server lived under `/<secret>/`; this one
  is at the root, since the website talks to it. Sign-in is what keeps rooms
  to accounts: a phone's hello carries its Supabase access token, checked
  with Supabase (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, both public). Website
  guests need only a party's code, as before.
- **Silence, not pings.** A Worker cannot send WebSocket pings; the phones
  and the page ping every two seconds, and a socket silent for 30 seconds is
  let go (a party member then keeps their seat for five minutes).
- A deploy ends every room and party, as a Railway redeploy did.

## Run, test, deploy

```
bun run test                    # protocol, party, room and catalogue rules
npx wrangler dev --port 8799 --var SUPABASE_URL: --var TRYPT_HIFI_URL:
bun run e2e.ts                  # a party and a game over real sockets, in workerd
ALAC=song.m4a bun run e2e.ts    # also: an ALAC share reaches the website as AAC (needs ffmpeg, below)
npx wrangler deploy
```

For the ALAC step, run the converter locally and point `wrangler dev` at it
(it needs `ffmpeg` and `ffprobe` installed):

```
FFMPEG_KEY=localtest PORT=8081 python3 ffmpeg/server.py
npx wrangler dev --port 8799 --var SUPABASE_URL: --var TRYPT_HIFI_URL: \
  --var FFMPEG_URL:http://localhost:8081 --var FFMPEG_KEY:localtest
```

```
npx wrangler secret put TRYPT_HIFI_KEY   # TrypT HiFi's ACCESS_PASSWORD, for Add songs
npx wrangler secret put TRYPT_HIFI_KEY   # TrypT HiFi's ACCESS_PASSWORD, for Add songs
npx wrangler secret put FFMPEG_KEY       # the ffmpeg service's key
```

### The ffmpeg service on Railway

Service `ffmpeg` in the Railway project `tryptmania-rooms`, built from this
repository's `tryptz/tryptmania` branch with **Root Directory**
`server/rooms/ffmpeg` (the Dockerfile there), health check `/health`,
restart on failure, watch pattern `/server/rooms/ffmpeg/**`, and sleep when
idle (it wakes on the next request in a few seconds). Its domain,
`https://ffmpeg-production-1a5b.up.railway.app`, is `FFMPEG_URL` in
`wrangler.toml` and routes to port 8080 (`PORT=8080`). Its variable
`FFMPEG_KEY` was made by Railway (`${{secret(48)}}`); the Worker's secret
`FFMPEG_KEY` must hold the same value. Without the key the service converts
nothing (401), so it is no use to anyone who finds its address.

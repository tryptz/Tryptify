# Tryptify Connect

**Which of a listener's devices are online, what plays on which, and moving
it from one to another**, like Spotify Connect. The web player
(`site/public/js/wp-connect.js`) and the app (`app/…/connect/`) speak it.

**Live since 2026-10-22** at `wss://connect.tryptify.com/v1/connect`
(Worker `tryptify-connect`; checked live: no sign-in, a bad or a dev token is
401, another site's page is 403).

It is a Cloudflare Worker (`src/worker.ts`) with one Durable Object per
account (`src/room.ts`, SQLite storage, the Free plan's kind), at
`wss://connect.tryptify.com/v1/connect`. Sign-in is the app's Supabase
session, checked like the Cloud and the Theme Store (`src/auth.ts`); it holds
no secret.

## Built to cost next to nothing

- **The room sleeps.** It uses the WebSocket Hibernation API: between
  messages it is not running, the connections stay open at Cloudflare's edge,
  and a device's `ping` is answered `pong` there without waking it.
- **Messages only on change.** A device says what it plays when that changes
  (a song, play, pause, a seek, the speed), never the position every second:
  the others work it out from `position`, `rate` and the server time `at`.
- **A ping every 50 s**, only while connected. The web player disconnects
  when its tab is hidden with nothing playing; the app should connect only
  while it is open or playing (it is awake then anyway).
- **Rate limited**: 10 messages a second per connection, bursts of 20; 64 KB
  per message; 20 devices per account; 100 songs in a handed-over queue.

## Connecting

`GET /v1/connect?device=<id>&name=<name>&kind=web|android|desktop`, upgraded
to a WebSocket. `device` is the device's own stable id (8–64 of
`A-Za-z0-9_-`); a second connection with the same id replaces the first (it
is closed with code 4000).

The session goes as `Authorization: Bearer <access token>` (the app), or,
since browsers cannot set headers on a WebSocket, as the subprotocols
`tryptify-connect, bearer.<access token>`; the room then answers with
`tryptify-connect`. Browsers must come from `https://tryptify.com` (or www;
`ALLOWED_ORIGINS` adds more); the app sends no Origin.

## Messages

Every message from the room carries `serverTime` (ms), for clock offsets.

A song travels as a **track**:
`{ src: "hifi"|"cloud"|"subsonic"|"local"|"qobuz"|"deezer"|"apple", id, title, artist, album, duration, quality, cover, coverArt }`.
`id` is a TIDAL id for `hifi`, the Cloud song id for `cloud`, the music
server's id for `subsonic`, the file's own key for `local` (which only
that device can play), and the catalogue's own id for `qobuz`, `deezer` and
`apple`, which the app plays and the web player does not. `cover` must be a plain `https` link: the room drops
blob: links and any link with a sign-in in it (a Subsonic cover's `u`/`t`/`s`/`p`).

**Now playing** is `{ device, name, playing, track, position, rate, at, since }`:
`position` seconds at server time `at`, `since` the server time it started
playing there. Where it is later: `position + (t − at) / 1000 × rate` while
`playing`.

| From a device | Meaning |
|---|---|
| `{type:"state", playing, track, position, rate, since}` | What plays here changed. A paused device's state does not replace another device's playing one. |
| `{type:"command", to, action, value}` | Steer the device `to`: `play`, `pause`, `next`, `prev`, `seek` (`value` seconds). |
| `{type:"transfer", to, force}` | Play it on `to` instead. The device playing is asked to hand over; with `force` (or when it is gone) `to` starts from what the room last heard. |
| `{type:"handoff", to, now, queue}` | The answer to `release`: this device's place to the second and what comes next. |
| `ping` (plain text) | Keep-alive; answered `pong` without waking the room. |

| From the room | Meaning |
|---|---|
| `{type:"welcome", you, devices, now}` | On connecting. |
| `{type:"devices", devices}` | Who is online changed; each `{ id, name, kind, since, active }`. |
| `{type:"now", now}` | Another device's state. **If it plays and started after this device did, this device pauses** (the later start wins, whichever message arrives first). |
| `{type:"command", from, action, value}` | Do it. |
| `{type:"release", to, toName}` | Send `handoff` to `to`, then stop. |
| `{type:"takeover", from, now, queue}` | Start `now.track` at `now.position` (it was `at` server time), then the queue. |
| `{type:"error", message}` | Something asked for could not be done. |

## Running and testing

```
cd server/connect
npm install
bun run test.ts                                  # the protocol and sign-in, no Cloudflare needed
npm run dev                                      # the room in Cloudflare's runtime, on :8787
CONNECT=ws://127.0.0.1:8787 bun run e2e.ts       # a phone and a browser of one account, and a stranger
npx wrangler deploy                              # needs CLOUDFLARE_API_TOKEN (Workers and DNS edit)
```

`npm run dev` sets `DEV_TOKENS=1`, under which `dev.<account uuid>` is
accepted as a sign-in from localhost only, so the tests need no Supabase
account. Never set it on the deployed Worker.

To try the web player against a local room, set `localStorage`
`tryptify-connect-url` to `ws://127.0.0.1:8787` on the player's page.

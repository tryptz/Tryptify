# Listening parties: plan

> **Moved, 2026-10-23.** The server now runs on Cloudflare (`tryptify-servers/rooms`, `rooms.tryptify.com`), ported from `tryptz/tryptify-party` with the same wire format, and the guests' page is tryptify.com's own (`public/party.html` in tryptz/tryptify.com, `https://tryptify.com/party/<code>`). Invites point there. What follows is the plan as it was written.

Agreed with the owner on 2026-10-20 and built the same day. The server and
the website are their own repository, tryptz/tryptify-party (the party's
rules in C++, `cpp/`, at the owner's word; sockets, songs and the website in
Rust), moved out of TryptMania-Parser so neither is built or deployed with
the other; Railway's "rooms" service builds from it, and is live with
parties since 2026-10-20. The app is here (`party/`, `ui/party/`). Not yet
tried on a phone.

## What it is

A party is a room on the Trypt Mania multiplayer rooms server
(tryptz/tryptify-party) where everyone queues songs and
listens to the same moment, in the app (also in the background) or on a
website. It has a live chat, notifications on the phone, and a Share songs
toggle so someone without a song still hears it.

## Decisions

- **Control:** everyone adds songs. Only the host pauses, seeks and reorders.
  Anyone can vote to skip; half the room (rounded up) skips.
- **Website:** guests with a name, through the party link only. Guests listen
  and chat; they cannot add songs. App members are Supabase accounts, as now.
- **Share songs:** files on the phone (local library, songs opened with
  Tryptify) and Tryptify downloads. Shared files are temporary and belong to
  the party: streamed to members, never offered as a download to keep, and
  deleted when the song leaves the queue or the party ends. Streaming tracks
  that are not downloaded are never uploaded: app members play them from
  their own account (matched by ISRC or catalogue id, then title, artist and
  length), and website guests skip them.
- **Restart:** parties live in memory. A server restart or deploy ends them;
  everyone sees "The party ended" and can start a new one.
- **Party screen: direction A, "now playing first"** (owner's pick from the
  demos, https://claude.ai/artifact/TDhHvpbjhwA6xiPJ6wDbYH): top bar with the
  party name, host and code, and Invite; the listeners' avatars with "N
  listening · N on the web" and an "In sync" dot; the big cover, title,
  artist and who added it; the progress bar; Play/Pause for everyone (host)
  and Vote skip (others, with the count); an Up next panel with who added
  each song and Shared or Uploading N%, and Add song; chat as a sheet at the
  bottom with the latest line and the message field. Colours, fonts and
  glass follow the user's own visual settings like every screen; the demo's
  purple is a placeholder. The website (demo D) keeps its own look.
- **Deploy:** parties run on their own Railway service, `parties`, in the
  `tryptmania-rooms` project beside `rooms`, built from the same
  `multiplayer/` folder on `main` (one replica, health check `/health`). Its
  Supabase variables and `ROOMS_SECRET` are references to `rooms`' own, so
  the two share one secret path until the owner gives `parties` its own. The
  owner adds its custom domain. Building parties on a branch keeps it off
  `main` until it is ready; ask the owner before deploying anything that
  `rooms` also builds from.

## Server (`multiplayer/`)

What exists: rooms with codes, host handover on leave, `ping`/`pong` clock
sync, `start{at}` with a lead time, chat (no history), one host-shared song
over HTTP with `X-Room-Key`, 16 players, 128 MB per song, one replica,
everything in memory.

To add:
- A room `kind`: `game` (today) or `party`. Party rooms are not listed by
  `rooms`; they are joined by code or link.
- Queue: items `{id, title, artist, durationMs, addedBy, isrc?, catalogId?,
  audioHash?, shared?}`; `queueAdd`, `queueRemove`, `queueMove` (host),
  auto-advance at the end of a song.
- Playback anchor broadcast on every change: `{itemId, positionMs, atServerMs,
  paused}`. `pause`, `resume`, `seek` (host), `skipVote` (anyone), and
  host-only `skip`.
- Chat history: the last 50 messages with server timestamps, sent on join.
  System lines for joins, leaves, songs added and skips. A rate limit.
- Shared songs: several per party, uploaded by whoever added them; a quota
  per party (say 1 GB) and per song (128 MB); deleted when they leave the
  queue or the party ends. GET with Range (206), `Accept-Ranges`, `ETag`, and
  a short-lived signed token in the query so a browser `<audio>` can play it.
- Rejoin: `hello{resume: key}` within 60 s keeps the seat, host role and
  state.
- Heartbeat: the server pings and drops connections silent for 30 s;
  bounded outbound queues.
- Guests: `hello{guest: true, party: code, name}` allowed only for party
  rooms, read and chat only.
- The website, served by the rooms server under the same secret path (same
  origin, so no CORS): one static page, vanilla JS.

## App

What exists: `RoomClient` (Ktor WebSocket singleton, backoff, chat buffer),
`RoomProtocol` (tagged JSON, `ClockSync`), song share/fetch with progress. The
socket dies with Trypt Mania's screen; Trypt Mania plays through its own
`GlyphAudioTransport`.

To add:
- A `PartyController` singleton that owns the party connection while a party
  is on, and drives the normal app player through a MediaController (the
  `WidgetSession.withSessionController` pattern) and `QueueManager` (the
  `RadioQueueManager` pattern), so the notification, lock screen and
  background play are the player's own.
- Sync: follow the anchor with `ClockSync`; seek only when more than 1 s
  out, seen twice in a row, never in the 4 s after a load or a seek, and aim
  ahead by what a seek has been costing (`PartyDrift`). Seeking at a quarter
  second every second made the sound drop every second: a seek's refill put
  the player behind again.
- A Party screen: queue, now playing, members, chat, Share songs, invite
  link and code. Add to party from song menus.
- Notifications: a "Listening party" channel for joins, chat and songs added,
  each type switchable.
- `docs/ui-invariants.md` "Multiplayer is a room" says leaving the rooms
  screen closes the socket; parties need an explicit exception there.
- Decide whether party plays count in stats, scrobbles and Discord (default:
  yes for what you hear, as normal plays).

## Failures to test

Host leaves (seat passes, queue keeps going); a phone loses Wi-Fi and comes
back within and after 60 s; a late joiner lands mid-song; a slow download
(starts in sync when ready, joining at the current position); a song too big
or over the party quota; an upload cut off; a guest trying to add a song; two
skips at once; a vote to skip from someone who left; the server restarting
mid-song; the app killed in the background; a browser tab asleep and woken
behind; an expired or wrong link; a full party; a song nobody can play.
Server: `cargo test` with fake clients (`tests/flow.rs`). App: a fake rooms
server for `RoomClient` and `PartyController` tests.

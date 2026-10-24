# Social: plan

Proposed on 2026-10-24, not yet agreed with the owner. The tables in
`docs/supabase/social.sql` (`c2c0cce5`) exist; no app code uses them yet, and
this plan builds on them rather than beside them. Demos:
https://claude.ai/artifact/DzPPnA1SvLvPMuqccCpF1p (profile, friends,
a party invite, Settings › Social). The demo's purple is a placeholder:
colours, fonts and glass follow the listener's own visual settings like every
screen (`docs/ui-invariants.md`).

## What it is

Friends, a profile page other people can see, what your friends are playing
right now, and one tap to pull them into a listening party. Everything is off
until the listener turns it on, and each part has its own switch.

It builds on what exists rather than beside it:

| Piece | Already there | New |
|---|---|---|
| Accounts | Supabase sign-in; `social_profiles` (handle, name, picture, bio, visibility) | banner, showcase layout |
| Friends | `social_follows`: two people who follow each other are friends; `social_friends()`, `social_suggestions()` from Discord servers in common | the Friends screen |
| Invites | `social_invites` (party or game, a code, 3 h expiry, friends only) | the notification and join sheet |
| Stats | `ui/stats/` (StatsReport, achievements, Trypt Mania grades); `social_stats.summary` (jsonb, under 16 KB) | the summary builder that fills it |
| Space chart | the genre and library galaxies (`ui/discover/galaxy/`) | "My sky": a personal, monthly version |
| Parties | `party/`, the rooms server (`server/rooms`, `rooms.tryptify.com`), joined by code or `tryptify.com/party/<code>` | Invite from a friend's row |
| Presence | Discord presence (`data/presence/`) | Tryptify's own, for friends |

## Decisions to make (the owner's call)

Each has a recommendation; the owner picks.

1. **Default: off.** Social stays off for everyone until they turn it on in
   Settings › Social or on the Profile screen's prompt. Recommended, because
   what someone listens to is personal.
2. **Friends are mutual follows**, as the schema already has it: you follow
   someone, they follow back, you are friends. Following alone shows only the
   public card. Find people by handle, a QR / link, or Discord servers in
   common. Proposed addition: **block** (the schema has none yet).
3. **Profile visible to:** Everyone / Friends (default) / Only me, the
   schema's `visibility`. Optional later: a public page at
   `tryptify.com/u/<handle>` for "Everyone".
4. **Raw history never leaves the phone.** The phone computes summaries (top
   artists, streak, peak hour, the sky) and uploads only those, once a day and
   when the profile is edited. Friends read the summary, never `play_events`.
5. **Who can invite me to a party:** Friends (default) / Nobody. The schema
   already allows invites only between friends; "Nobody" is a new switch.
6. **Invite expiry:** the schema says 3 hours. For "join me now" 10 minutes
   reads better; the owner picks (one line in the SQL).
7. **Party plays count as normal plays** in stats and the sky (the parties
   plan already says yes for stats, scrobbles and Discord).

## The profile page (demo 1)

Header: banner, avatar, username, a level badge (from achievements), a bio of
up to 160 characters, friend count and minutes this year. Under it a
"Listening now" card with Listen along (opens the party invite flow, or joins
their party when they are in one).

Then **showcase blocks** the listener adds, removes and drags into order. The
layout is saved as a short JSON list (`[{"kind":"sky"},{"kind":"topArtists",
"range":"month"}]`). Blocks to start with, all made from data the app already
has:

- **My sky** (below).
- **Top artists / Top tracks / Top albums**, for a week, month or year.
- **Numbers:** listening streak, peak hour, minutes, average tempo.
- **Sound fingerprint:** median tempo, commonest key, loudness and dynamic
  range of what was played (`AlbumAggregates` already computes these per
  album).
- **Pinned:** up to three achievements or Trypt Mania best grades.
- **Listening clock:** a 7 × 24 heat map of when they listen.
- **On repeat:** the song played most in the last 7 days.
- **Favourite album**, picked by hand.
- **Taste match** (only on a friend's profile): a 0–100 score from shared top
  artists and genres, and the artists both like.

## My sky: everyone's space chart (demo 1)

A star map of one month of listening, drawn with the galaxy's own renderer
so it looks like the rest of the app: each genre is a constellation, each
artist a star, size by plays and brightness by how recent. Tap a star for the
artist and their plays that month.

- Made on the phone from `play_events`, at the start of each month for the
  month before (and live for the current one).
- Uploaded as a small summary only: at most 60 stars, each `{artistId, name,
  genre, plays, lastPlayedDay}` (about 4 KB), so a friend's phone can draw
  it. The current month rides in `social_stats.summary`; past months go in a
  new `social_skies` table.
- Past months stay: swipe back through "October", "September"…
- Share as a picture (the existing share sheet), for people not on Tryptify.

## Friends listening now (demo 2)

A Friends screen, reached from Profile (and, if the owner wants, a tab):
sections **Listening now**, **Online**, **Offline** (with the last song and
when). Each listening row shows the song, how far in, and whether they are in
a party; the button is **Invite** (or **Join** when they are in one).

Presence is one row per user in Supabase, written by the phone:

- on play, pause, track change, and every 60 s while playing;
- `online` means the row was touched in the last 2 minutes;
- friends get changes through Supabase Realtime, so the list moves live
  without polling;
- a **Private session** hides everything for 6 hours and then turns itself
  off, so nobody forgets it on.

Only songs with a catalogue or ISRC id, or local songs as title and artist,
are shown. Subsonic and Tryptify Cloud songs show title and artist only,
never a stream address (the rule in `AGENTS.md` about the listener's server).

## Inviting a friend to a party (demo 3)

1. Tap Invite on a friend. If you are not in a party, one starts with you as
   host (the existing `PartyController`).
2. The app writes a `social_invites` row (`kind = 'party'`, the party code,
   the song as `title`). Sending again replaces the open one (the schema's
   `unique (from_id, to_id, kind)`). Only the code travels, never a server
   address: the friend's app has the rooms server built in. The same row with
   `kind = 'game'` invites to a Trypt Mania multiplayer room.
3. The friend's phone gets it through Realtime and shows a notification on
   the existing "Listening party" channel, with **Join** and **Not now**. In
   the app it is a sheet with the song, who is in, and Join party.
4. Join uses the code the way a link does today. An expired or used invite
   says so plainly.

## Settings › Social (demo 4)

A new section in Settings (and in `SettingsSearchIndex`):

- **Social features** (master switch). Off greys out and stops everything
  below, and deletes the presence row.
- Presence: show when I'm online, show what I'm playing, private session.
- Profile: visible to (Everyone / Friends / Only me), My sky, stats blocks.
- Notifications: friend requests, party invites.
- Who can invite me to a party or a game: Friends / Nobody.
- **Delete my social data**: removes the summary, sky, presence and friend
  list from the server.

These are device-and-account settings stored on the server (so friends'
phones can respect them), not only in DataStore.

## Server (Supabase)

Already in `docs/supabase/social.sql`: `social_profiles`, `social_follows`,
`social_stats`, `social_invites`, `social_discord_guilds`, the helpers
`social_is_friend`, `social_can_view`, `social_counts`, `social_friends`,
`social_suggestions`, `social_discord_common`, and row security for all of
them. Kept as they are.

To add, in a second file `docs/supabase/social_presence.sql` (idempotent, like
its neighbours):

| Table / change | Holds | Who reads |
|---|---|---|
| `social_settings` | `enabled`, `show_online`, `show_playing`, `private_until`, `sky_on`, `stats_on`, `invites_from` (`friends`/`nobody`), notification switches | the owner; read by policies |
| `social_presence` | `(user_id, state playing/paused/online, track jsonb, position_ms, party_code, updated_at)` | friends, only while the owner's switches allow it |
| `social_skies` | `(user_id, month, stars jsonb < 16 KB)` | `social_can_view(user_id)` and `sky_on` |
| `social_profiles` + `banner_url`, `showcase jsonb` | the block layout | as the card |
| `social_blocks` | `(blocker_id, blocked_id)` | the blocker; checked by follow and invite policies |

- `social_presence` and `social_invites` join the Realtime publication, so
  friends' phones see changes without polling.
- The presence policy checks the switches on the server, so turning Social
  off hides you at once even from an old app version.
- The invite insert policy gains `invites_from <> 'nobody'` and "not
  blocked".
- A prune job, like `play_history_prune.sql`, deletes expired invites and
  presence rows a day old.

No change to the rooms server: an invite carries a party code it already
understands.

## App

- `data/social/`: `SocialRepository` (Supabase calls to the tables and
  functions above), `PresencePublisher`
  (listens to the player, throttles to the rules above), `SummaryBuilder`
  (stats and sky from local data, pure Kotlin so it is unit tested),
  `InviteListener` (Realtime, hands to `PartyNotifier`).
- `ui/social/`: `FriendsScreen`, `FriendProfileScreen`, `ShowcaseEditor`,
  `SkyView` (the galaxy stage with one month's stars); the Profile screen
  gains the showcase.
- Strings in all eight languages through `app/l10n/strings.tsv`.
- `docs/ui-invariants.md` gets a "Social" section once the look is agreed.

## Build order

1. **Switches and friends:** `social_settings`, `social_blocks`, Settings ›
   Social, pick a handle, follow / follow back / block, the Friends screen
   without presence.
2. **Presence:** publisher, Realtime, Listening now / Online / Offline,
   private session.
3. **Invites:** `social_invites` through Realtime, notification, join
   sheet.
4. **Profile:** summary builder, showcase blocks, a friend's profile.
5. **My sky:** monthly star map, history of months, share as picture.
6. **Taste match** and the listening clock.

Each step ships on its own and is useful alone.

## Failures to test

Social turned off while friends are watching (they see you go offline at
once); a friend removed mid-party; an invite to someone who blocked you
(silently refused); an invite accepted after it expired; two invites to the
same person; presence after the app is killed (goes stale after 2 minutes,
no ghost "listening"); a private session across a restart; a summary upload
that fails (keeps the last good one); a month with no plays (an empty sky,
not an error); a user with thousands of artists (capped at 60 stars);
accessibility of the sky (each star has a label, and there is a list view).

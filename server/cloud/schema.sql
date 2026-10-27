-- SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
-- Tryptify Cloud's D1 (SQLite) tables. Safe to run on every deploy.

create table if not exists cloud_accounts (
  user_id          text    primary key,
  plan             text    check (plan in ('mini', 'lite', 'plus', 'max', 'pro', 'ultra', 'xtra')),
  paid_until       text,                       -- ISO-8601 instant
  used_bytes       integer not null default 0 check (used_bytes >= 0),
  trial            integer not null default 0, -- 1 while paid_until is a trial's end
  provider         text,
  customer_id      text,
  subscription_id  text,
  version          integer not null default 0, -- bumped on every write; guards concurrent webhooks
  last_event       text,                       -- the billing event whose write this row last took
  updated_at       text
);
create index if not exists cloud_accounts_subscription on cloud_accounts (provider, subscription_id);

-- Storage given rather than sold: an account listed here has no quota and no
-- end date, whatever it has paid for (the owner's own, by hand; see README).
-- Never sold, never in /plans, never touched by a webhook or the cleanup.
create table if not exists cloud_grants (
  user_id     text primary key,
  kind        text not null check (kind in ('unlimited')),
  note        text,
  created_at  text not null
);

-- Each provider event is applied once, however often it is delivered.
create table if not exists cloud_billing_events (
  provider     text not null,
  event_id     text not null,
  user_id      text not null,
  kind         text not null,
  received_at  text not null,
  primary key (provider, event_id)
);

-- ── The library (phase 1) ───────────────────────────────────────────────────

-- One uploaded song. Its file is the R2 object `key`; its cover, if any, is
-- `cover_key`. Tags come from the app, which already reads them on the phone.
create table if not exists cloud_songs (
  id            text    primary key,           -- "so-…"
  user_id       text    not null,
  key           text    not null,
  size          integer not null,
  cover_key     text,
  cover_size    integer not null default 0,
  title         text    not null,
  artist        text    not null,
  album         text    not null,
  album_artist  text    not null,
  album_id      text    not null,              -- "al-…", from album_artist + album
  artist_id     text    not null,              -- "ar-…", from album_artist
  track         integer,
  disc          integer,
  year          integer,
  genre         text,
  duration      integer not null default 0,    -- seconds
  suffix        text    not null,
  content_type  text    not null,
  bit_rate      integer,
  sample_rate   integer,
  bit_depth     integer,
  created_at    text    not null
);
create index if not exists cloud_songs_user_album on cloud_songs (user_id, album_id);
create index if not exists cloud_songs_user_artist on cloud_songs (user_id, artist_id);

-- An upload in progress. Its declared size is reserved against the quota from
-- the start, so abandoned uploads can never hold more storage than the plan
-- pays for; the daily cleanup aborts ones left over.
create table if not exists cloud_uploads (
  id          text    primary key,             -- becomes the song's id
  user_id     text    not null,
  key         text    not null,
  r2_upload   text    not null,                -- R2's multipart uploadId
  size        integer not null,
  meta        text    not null,                -- the song's tags, JSON
  created_at  text    not null
);
create index if not exists cloud_uploads_user on cloud_uploads (user_id);

-- Passwords the app uses for the Subsonic API, one per device. Random and
-- revocable; never the person's Tryptify password.
create table if not exists cloud_subsonic_keys (
  id          text    primary key,
  user_id     text    not null,
  password    text    not null,
  label       text,
  created_at  text    not null
);
create index if not exists cloud_subsonic_keys_user on cloud_subsonic_keys (user_id);

-- ── What a listener does with the library (any Subsonic client) ─────────────

-- Songs they starred (a like in Tryptify stars the song here).
create table if not exists cloud_stars (
  user_id     text not null,
  song_id     text not null,
  starred_at  text not null,
  primary key (user_id, song_id)
);

-- How often and when each song was played: scrobble with submission=true.
-- Drives the Recently played and Most played album lists.
create table if not exists cloud_plays (
  user_id      text    not null,
  song_id      text    not null,
  play_count   integer not null default 0,
  last_played  text    not null,
  primary key (user_id, song_id)
);

-- Playlists, and their songs in order. A position may skip numbers (a song
-- deleted from the library leaves its gap); the order is what counts.
create table if not exists cloud_playlists (
  id          text primary key,                -- "pl-…"
  user_id     text not null,
  name        text not null,
  comment     text,
  created_at  text not null,
  changed_at  text not null
);
create index if not exists cloud_playlists_user on cloud_playlists (user_id);
create table if not exists cloud_playlist_songs (
  playlist_id  text    not null,
  position     integer not null,
  song_id      text    not null,
  primary key (playlist_id, position)
);
create index if not exists cloud_playlist_songs_song on cloud_playlist_songs (song_id);

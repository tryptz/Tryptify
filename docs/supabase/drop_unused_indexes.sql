-- Indexes nothing reads — NOT YET APPLIED
-- =======================================
--
-- Run this in the Supabase SQL editor. It was attempted as migration
-- drop_unused_play_indexes, and the confirmation prompt for a destructive
-- statement was cancelled both times, so all four indexes are still live.
--
-- None of them has been used by any query since it was created (idx_scan = 0;
-- statistics have never been reset). The first three are updated on every play
-- the app uploads. catalog_albums_artist is covered by
-- catalog_albums_artist_title_lower_key (see phase1b_ensure_catalog_track_rpc.sql),
-- which leads with the same column and so also serves the foreign key.
--
-- Check first that nothing has started using them:
--   select indexrelname, idx_scan from pg_stat_user_indexes
--   where indexrelname in ('play_events_user_started_at', 'play_events_user_track',
--                          'play_sessions_user_started', 'catalog_albums_artist');
--
-- To restore any of them:
--   create index play_events_user_started_at on public.play_events (user_id, started_at desc);
--   create index play_events_user_track      on public.play_events (user_id, track_id);
--   create index play_sessions_user_started  on public.play_sessions (user_id, started_at desc);
--   create index catalog_albums_artist       on public.catalog_albums (primary_artist_id);

drop index if exists public.play_events_user_started_at;
drop index if exists public.play_events_user_track;
drop index if exists public.play_sessions_user_started;
drop index if exists public.catalog_albums_artist;

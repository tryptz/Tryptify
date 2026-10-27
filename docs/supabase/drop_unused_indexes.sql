-- Indexes nothing reads — APPLIED 2026-10-24
-- ===========================================
--
-- Run by the owner in the Supabase SQL editor on 2026-10-24 (the migration
-- tool's confirmation prompt never reached them); all three are gone.
--
-- Rechecked 2026-10-24 (16,801 plays): none of the three has been used by any
-- query since it was created (idx_scan = 0; statistics have never been reset).
-- The two play_events ones are updated on every play the app uploads; the
-- app's history reads use play_events_user_played_at_ms and
-- play_events_user_track_uuid instead. play_sessions_user_started was on this
-- list too, but it has now been used, so it stays. The other indexes the
-- advisor calls unused (play_events_device_id, _session, _track_uuid,
-- catalog_track_sources_track, play_sessions_device_id, user_devices_user)
-- each back a foreign key, so they stay as well. catalog_albums_artist is covered by
-- catalog_albums_artist_title_lower_key (see phase1b_ensure_catalog_track_rpc.sql),
-- which leads with the same column and so also serves the foreign key.
--
-- Check first that nothing has started using them:
--   select indexrelname, idx_scan from pg_stat_user_indexes
--   where indexrelname in ('play_events_user_started_at', 'play_events_user_track',
--                          'catalog_albums_artist');
--
-- To restore any of them:
--   create index play_events_user_started_at on public.play_events (user_id, started_at desc);
--   create index play_events_user_track      on public.play_events (user_id, track_id);
--   create index catalog_albums_artist       on public.catalog_albums (primary_artist_id);

drop index if exists public.play_events_user_started_at;
drop index if exists public.play_events_user_track;
drop index if exists public.catalog_albums_artist;

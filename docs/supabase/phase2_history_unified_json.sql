-- Add unified_json to play_history so cross-device Recently Played routes
-- through the original backend (Qobuz cache fetch / local file / collection
-- direct link) instead of falling back to TIDAL with the wrong id.
--
-- Mirrors history_tracks.unifiedJson on the local Room DB
-- (HistoryTrackEntity, MusicDatabase v8). Carries the JSON-serialised
-- UnifiedTrack (PlaybackSource sealed-class polymorphism preserved by
-- kotlinx.serialization). Nullable — old TIDAL rows leave it null.
--
-- Idempotent: safe to re-run.

alter table public.play_history
    add column if not exists unified_json text;

comment on column public.play_history.unified_json is
    'Serialised UnifiedTrack JSON (kotlinx.serialization, polymorphic '
    'PlaybackSource). Mirrors history_tracks.unifiedJson on the Android '
    'client. NULL for legacy/TIDAL-only rows.';

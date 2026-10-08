-- Phase 1b: authenticated write path for the canonical catalog.
-- Clients can't INSERT into catalog_* directly (RLS is SELECT-only).
-- This SECURITY DEFINER RPC validates input and upserts atomically:
--   artist (by case-insensitive name) → album (by title+artist) → track → track_source
-- Returns the resolved catalog_tracks.id (uuid).
--
-- Called over PostgREST as POST /rest/v1/rpc/ensure_catalog_track with the
-- payload wrapped as {"p": {...}}: PostgREST matches the body's top-level keys
-- to parameter names, and the one parameter is p. Until the client wrapped it,
-- every call answered 404 and the catalog stayed empty.
--
-- As live after migration catalog_unique_names_and_race_safe_ensure:
--   - catalog_artists_name_lower_key, unique on lower(name), and
--     catalog_albums_artist_title_lower_key, unique on
--     (primary_artist_id, lower(title)): the lookups below are index probes,
--     and two calls cannot create the same artist or album twice.
--   - creation of a new track is serialised per (source, source_ref) with a
--     transaction-scoped advisory lock, so two calls for the same new track
--     resolve to one.
--
-- Payload shape (jsonb):
--   {
--     "source":        "tidal" | "collection" | "local",     -- required
--     "source_ref":    "string",                              -- required
--     "title":         "string",                              -- required
--     "duration_s":    int,                                   -- optional
--     "artist_name":   "string",                              -- required
--     "artist_picture": "string|null",
--     "album_title":   "string|null",
--     "album_cover":   "string|null",
--     "release_date":  "string|null",
--     "audio_quality": "string|null",
--     "isrc":          "string|null",
--     "explicit":      bool,
--     "track_number":  int
--   }

create unique index if not exists catalog_artists_name_lower_key
    on public.catalog_artists (lower(name));
create unique index if not exists catalog_albums_artist_title_lower_key
    on public.catalog_albums (primary_artist_id, lower(title));

create or replace function public.ensure_catalog_track(p jsonb)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_source         text;
    v_source_ref     text;
    v_title          text;
    v_artist_name    text;
    v_album_title    text;
    v_album_cover    text;
    v_release_date   text;
    v_artist_picture text;
    v_duration_s     int;
    v_audio_quality  text;
    v_isrc           text;
    v_explicit       boolean;
    v_track_number   int;

    v_artist_id uuid;
    v_album_id  uuid;
    v_track_id  uuid;
begin
    -- auth gate: SECURITY DEFINER bypasses RLS, so it is enforced here.
    if (select auth.uid()) is null then
        raise exception 'ensure_catalog_track: not authenticated'
            using errcode = '42501';
    end if;

    v_source         := lower(coalesce(p ->> 'source', ''));
    v_source_ref     := coalesce(p ->> 'source_ref', '');
    v_title          := nullif(btrim(coalesce(p ->> 'title', '')), '');
    v_artist_name    := nullif(btrim(coalesce(p ->> 'artist_name', '')), '');
    v_album_title    := nullif(btrim(coalesce(p ->> 'album_title', '')), '');
    v_album_cover    := nullif(p ->> 'album_cover', '');
    v_release_date   := nullif(p ->> 'release_date', '');
    v_artist_picture := nullif(p ->> 'artist_picture', '');
    v_duration_s     := coalesce((p ->> 'duration_s')::int, 0);
    v_audio_quality  := nullif(p ->> 'audio_quality', '');
    v_isrc           := nullif(p ->> 'isrc', '');
    v_explicit       := coalesce((p ->> 'explicit')::boolean, false);
    v_track_number   := nullif(p ->> 'track_number', '')::int;

    if v_source not in ('tidal', 'collection', 'local') then
        raise exception 'ensure_catalog_track: invalid source %', v_source
            using errcode = '22023';
    end if;
    if length(v_source_ref) = 0 then
        raise exception 'ensure_catalog_track: source_ref required'
            using errcode = '22023';
    end if;
    if v_title is null then
        raise exception 'ensure_catalog_track: title required'
            using errcode = '22023';
    end if;
    if v_artist_name is null then
        v_artist_name := 'Unknown Artist';
    end if;

    -- Fast path: this source key is already mapped.
    select s.track_id into v_track_id
      from public.catalog_track_sources s
     where s.source = v_source and s.source_ref = v_source_ref;
    if v_track_id is not null then
        return v_track_id;
    end if;

    -- Two calls for the same new track (two devices, or a live push and a
    -- backfill) would each insert a track. Serialise creation per source key:
    -- the second waits here, then finds the first one's mapping.
    perform pg_advisory_xact_lock(hashtextextended('ensure_catalog_track:' || v_source || ':' || v_source_ref, 0));
    select s.track_id into v_track_id
      from public.catalog_track_sources s
     where s.source = v_source and s.source_ref = v_source_ref;
    if v_track_id is not null then
        return v_track_id;
    end if;

    -- 1. Artist, by case-insensitive name: one probe of catalog_artists_name_lower_key.
    select id into v_artist_id
      from public.catalog_artists
     where lower(name) = lower(v_artist_name);
    if v_artist_id is null then
        insert into public.catalog_artists (name, picture_url)
            values (v_artist_name, v_artist_picture)
            on conflict ((lower(name))) do nothing
            returning id into v_artist_id;
        if v_artist_id is null then
            -- Another call created it between the probe and the insert.
            select id into v_artist_id
              from public.catalog_artists
             where lower(name) = lower(v_artist_name);
        end if;
    elsif v_artist_picture is not null then
        update public.catalog_artists
           set picture_url = v_artist_picture
         where id = v_artist_id and picture_url is null;
    end if;

    -- 2. Album, by (artist, lower(title)). The artist is never null here, so
    -- a plain = can use catalog_albums_artist_title_lower_key, which
    -- "is not distinct from" could not.
    if v_album_title is not null then
        select id into v_album_id
          from public.catalog_albums
         where primary_artist_id = v_artist_id
           and lower(title) = lower(v_album_title);
        if v_album_id is null then
            insert into public.catalog_albums (title, cover_url, release_date, primary_artist_id)
                values (v_album_title, v_album_cover, v_release_date, v_artist_id)
                on conflict (primary_artist_id, (lower(title))) do nothing
                returning id into v_album_id;
            if v_album_id is null then
                select id into v_album_id
                  from public.catalog_albums
                 where primary_artist_id = v_artist_id
                   and lower(title) = lower(v_album_title);
            end if;
        elsif v_album_cover is not null then
            update public.catalog_albums
               set cover_url = v_album_cover
             where id = v_album_id and cover_url is null;
        end if;
    end if;

    -- 3. Track. Not deduplicated across sources: the same recording reached
    -- from two sources stays two tracks until something maps them together.
    insert into public.catalog_tracks
            (title, duration_s, album_id, primary_artist_id,
             audio_quality, isrc, explicit, track_number)
        values (v_title, v_duration_s, v_album_id, v_artist_id,
                v_audio_quality, v_isrc, v_explicit, v_track_number)
        returning id into v_track_id;

    -- 4. The source mapping. Held under the lock above, so no conflict.
    insert into public.catalog_track_sources (track_id, source, source_ref)
        values (v_track_id, v_source, v_source_ref);

    return v_track_id;
end;
$$;

revoke execute on function public.ensure_catalog_track(jsonb) from public, anon;
grant  execute on function public.ensure_catalog_track(jsonb) to authenticated;

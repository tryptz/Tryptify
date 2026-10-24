-- Social: public profiles, follows, shared stats, invites and Discord servers
-- in common. Apply to the production project as the migration `social`.
--
-- Friends are two accounts that follow each other. What a profile shows past
-- its card (stats, public playlists) depends on its visibility:
--   everyone  any signed-in listener
--   friends   only friends (the default)
--   private   only its owner
-- The card itself (handle, name, picture, bio) is readable by any signed-in
-- listener, so people can be found by their handle.

create table if not exists public.social_profiles (
  user_id      uuid primary key references auth.users(id) on delete cascade,
  handle       text not null unique check (handle ~ '^[a-z0-9_]{3,20}$'),
  display_name text not null default '' check (char_length(display_name) <= 40),
  bio          text not null default '' check (char_length(bio) <= 160),
  avatar_url   text check (avatar_url is null or (avatar_url ~ '^https://' and char_length(avatar_url) <= 500)),
  visibility   text not null default 'friends' check (visibility in ('everyone', 'friends', 'private')),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

create table if not exists public.social_follows (
  follower_id uuid not null references public.social_profiles(user_id) on delete cascade,
  followee_id uuid not null references public.social_profiles(user_id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (follower_id, followee_id),
  check (follower_id <> followee_id)
);
create index if not exists social_follows_followee_idx on public.social_follows (followee_id);

-- A summary the app builds from its own play history; never the history itself.
create table if not exists public.social_stats (
  user_id    uuid primary key references public.social_profiles(user_id) on delete cascade,
  summary    jsonb not null check (pg_column_size(summary) < 16384),
  updated_at timestamptz not null default now()
);

-- One open invite per sender, friend and kind; sending again replaces it.
create table if not exists public.social_invites (
  id         uuid primary key default gen_random_uuid(),
  from_id    uuid not null default auth.uid() references public.social_profiles(user_id) on delete cascade,
  to_id      uuid not null references public.social_profiles(user_id) on delete cascade,
  kind       text not null check (kind in ('party', 'game')),
  code       text not null check (code ~ '^[A-Z0-9]{3,12}$'),
  title      text not null default '' check (char_length(title) <= 80),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '3 hours',
  unique (from_id, to_id, kind)
);
create index if not exists social_invites_to_idx on public.social_invites (to_id);

-- The Discord servers an account is in, saved when it signs in with Discord.
-- Only its owner reads the rows; others see only the servers they share.
create table if not exists public.social_discord_guilds (
  user_id  uuid not null references auth.users(id) on delete cascade,
  guild_id text not null check (guild_id ~ '^[0-9]{1,25}$'),
  name     text not null default '' check (char_length(name) <= 100),
  primary key (user_id, guild_id)
);
create index if not exists social_discord_guilds_guild_idx on public.social_discord_guilds (guild_id);

drop trigger if exists social_profiles_updated_at on public.social_profiles;
create trigger social_profiles_updated_at before update on public.social_profiles
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------- functions

create or replace function public.social_is_friend(a uuid, b uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.social_follows where follower_id = a and followee_id = b)
     and exists (select 1 from public.social_follows where follower_id = b and followee_id = a);
$$;

create or replace function public.social_can_view(owner uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select owner = (select auth.uid())
      or exists (
        select 1 from public.social_profiles p
        where p.user_id = owner
          and (p.visibility = 'everyone'
               or (p.visibility = 'friends' and public.social_is_friend((select auth.uid()), owner)))
      );
$$;

create or replace function public.social_counts(target uuid)
returns table (followers int, following int, friends int)
language sql stable security definer set search_path = '' as $$
  select
    (select count(*)::int from public.social_follows where followee_id = target),
    (select count(*)::int from public.social_follows where follower_id = target),
    (select count(*)::int from public.social_follows f
       where f.follower_id = target
         and exists (select 1 from public.social_follows g
                     where g.follower_id = f.followee_id and g.followee_id = target));
$$;

-- The signed-in listener's friends.
create or replace function public.social_friends()
returns setof public.social_profiles
language sql stable security definer set search_path = '' as $$
  select p.* from public.social_profiles p
  where exists (select 1 from public.social_follows where follower_id = (select auth.uid()) and followee_id = p.user_id)
    and exists (select 1 from public.social_follows where follower_id = p.user_id and followee_id = (select auth.uid()))
  order by lower(p.display_name), p.handle;
$$;

-- The names of the Discord servers the signed-in listener shares with target.
create or replace function public.social_discord_common(target uuid)
returns table (name text)
language sql stable security definer set search_path = '' as $$
  select mine.name from public.social_discord_guilds mine
  join public.social_discord_guilds theirs on theirs.guild_id = mine.guild_id
  where mine.user_id = (select auth.uid()) and theirs.user_id = target and target <> (select auth.uid())
  order by mine.name
  limit 20;
$$;

-- People who share Discord servers with the signed-in listener, not yet followed.
create or replace function public.social_suggestions()
returns table (user_id uuid, handle text, display_name text, avatar_url text, bio text, servers_in_common int)
language sql stable security definer set search_path = '' as $$
  select p.user_id, p.handle, p.display_name, p.avatar_url, p.bio, count(*)::int
  from public.social_discord_guilds mine
  join public.social_discord_guilds theirs on theirs.guild_id = mine.guild_id and theirs.user_id <> mine.user_id
  join public.social_profiles p on p.user_id = theirs.user_id and p.visibility <> 'private'
  where mine.user_id = (select auth.uid())
    and not exists (select 1 from public.social_follows f
                    where f.follower_id = mine.user_id and f.followee_id = p.user_id)
  group by p.user_id, p.handle, p.display_name, p.avatar_url, p.bio
  order by count(*) desc, p.handle
  limit 20;
$$;

revoke execute on function public.social_is_friend(uuid, uuid), public.social_can_view(uuid),
  public.social_counts(uuid), public.social_friends(), public.social_discord_common(uuid),
  public.social_suggestions() from public, anon;
grant execute on function public.social_is_friend(uuid, uuid), public.social_can_view(uuid),
  public.social_counts(uuid), public.social_friends(), public.social_discord_common(uuid),
  public.social_suggestions() to authenticated;

-- ---------------------------------------------------------------- row security

alter table public.social_profiles       enable row level security;
alter table public.social_follows        enable row level security;
alter table public.social_stats          enable row level security;
alter table public.social_invites        enable row level security;
alter table public.social_discord_guilds enable row level security;

create policy social_profiles_select on public.social_profiles for select to authenticated using (true);
create policy social_profiles_insert on public.social_profiles for insert to authenticated with check (user_id = (select auth.uid()));
create policy social_profiles_update on public.social_profiles for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy social_profiles_delete on public.social_profiles for delete to authenticated using (user_id = (select auth.uid()));

create policy social_follows_select on public.social_follows for select to authenticated
  using (follower_id = (select auth.uid()) or followee_id = (select auth.uid()));
create policy social_follows_insert on public.social_follows for insert to authenticated with check (follower_id = (select auth.uid()));
create policy social_follows_delete on public.social_follows for delete to authenticated using (follower_id = (select auth.uid()));

create policy social_stats_select on public.social_stats for select to authenticated using (public.social_can_view(user_id));
create policy social_stats_insert on public.social_stats for insert to authenticated with check (user_id = (select auth.uid()));
create policy social_stats_update on public.social_stats for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- Invites go only to friends.
create policy social_invites_select on public.social_invites for select to authenticated
  using (from_id = (select auth.uid()) or to_id = (select auth.uid()));
create policy social_invites_insert on public.social_invites for insert to authenticated
  with check (from_id = (select auth.uid()) and public.social_is_friend(from_id, to_id));
create policy social_invites_update on public.social_invites for update to authenticated
  using (from_id = (select auth.uid()))
  with check (from_id = (select auth.uid()) and public.social_is_friend(from_id, to_id));
create policy social_invites_delete on public.social_invites for delete to authenticated
  using (from_id = (select auth.uid()) or to_id = (select auth.uid()));

create policy social_discord_guilds_select on public.social_discord_guilds for select to authenticated using (user_id = (select auth.uid()));
create policy social_discord_guilds_insert on public.social_discord_guilds for insert to authenticated with check (user_id = (select auth.uid()));
create policy social_discord_guilds_delete on public.social_discord_guilds for delete to authenticated using (user_id = (select auth.uid()));

-- Public playlists: readable by whoever may see their owner's profile.
create policy user_playlists_select_public on public.user_playlists for select to authenticated
  using (is_public and public.social_can_view(user_id));
create policy playlist_tracks_select_public on public.playlist_tracks for select to authenticated
  using (exists (select 1 from public.user_playlists p
                 where p.id = playlist_tracks.playlist_id and p.is_public and public.social_can_view(p.user_id)));

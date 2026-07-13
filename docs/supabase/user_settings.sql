-- user_settings: one row per user holding a JSON blob of the app's
-- allow-listed preferences (theme, Lyrics FX, EQ/DSP, visualizer, radio
-- weights, …). The client writes it via SupabaseSyncRepository.pushSettings()
-- and restores it on sign-in via pullSettings(). The blob is produced by
-- SettingsSyncCodec (a tagged {"t":type,"v":value} object) so Int/Long/Float/
-- Double round-trip exactly. Sensitive/device-only keys (auth tokens, device
-- ids, per-device GPU/fps tuning, local file paths) are never included — see
-- PreferencesManager.SETTINGS_SYNC_KEYS.
--
-- Run this in the Supabase SQL editor for the project that owns the app.

create table if not exists public.user_settings (
    user_id     uuid primary key
                references auth.users (id) on delete cascade,
    payload     jsonb       not null default '{}'::jsonb,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

-- Keep updated_at fresh on every write (used for last-write-wins).
create or replace function public.set_user_settings_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_user_settings_updated_at on public.user_settings;
create trigger trg_user_settings_updated_at
    before update on public.user_settings
    for each row execute function public.set_user_settings_updated_at();

-- RLS: a user may only see and modify their own settings row.
alter table public.user_settings enable row level security;

drop policy if exists "user_settings_select_own" on public.user_settings;
create policy "user_settings_select_own"
    on public.user_settings for select
    using (auth.uid() = user_id);

drop policy if exists "user_settings_insert_own" on public.user_settings;
create policy "user_settings_insert_own"
    on public.user_settings for insert
    with check (auth.uid() = user_id);

drop policy if exists "user_settings_update_own" on public.user_settings;
create policy "user_settings_update_own"
    on public.user_settings for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "user_settings_delete_own" on public.user_settings;
create policy "user_settings_delete_own"
    on public.user_settings for delete
    using (auth.uid() = user_id);

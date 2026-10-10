-- user_devices: one row per (account, install)
-- ===========================================
--
-- Applied to production as migration user_devices_keyed_by_local_id.
-- Idempotent, so it is safe to re-run.
--
-- DeviceRegistry used to cache the row id in preferences, clear it on sign-out
-- and insert a fresh row on the next sign-in: 774 rows for 327 accounts, one
-- account with 87. local_id is DeviceIdProvider's per-install UUID, which
-- survives sign-out, and DeviceRegistry now upserts on (user_id, local_id).
--
-- Additive: rows from older app versions keep a null local_id, and nulls never
-- collide under a unique constraint, so those versions keep working. Their old
-- rows stay; nothing can tell which install made them.

alter table public.user_devices add column if not exists local_id text;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.user_devices'::regclass and conname = 'user_devices_user_local_key'
    ) then
        alter table public.user_devices
            add constraint user_devices_user_local_key unique (user_id, local_id);
    end if;
end $$;

comment on column public.user_devices.local_id is
    'DeviceIdProvider''s per-install UUID. With user_id, the upsert key.';

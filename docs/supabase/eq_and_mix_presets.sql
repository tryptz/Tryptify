-- EQ and mixer presets
-- ====================
--
-- The two tables behind "my presets follow my account": AutoEQ and parametric
-- EQ profiles, and the mixer's bus-and-plugin states. Written by
-- SupabaseSyncRepository.pushEqPreset / pushMixPreset, read back by
-- pullLibrary, and mapped either way by PresetSyncMapping.kt.
--
-- Both tables existed, fully policied and empty, long before anything wrote to
-- them: the client had push functions with no callers. This file exists so the
-- schema they depend on is on record rather than only in the project.
--
-- Recorded here as it stands after the columns the client actually needs were
-- added. Idempotent, so it is safe to re-run against the live project.

-- ── EQ presets ──────────────────────────────────────────────────────────────
--
-- local_id is EqPresetEntity.id, a stable string minted once when the preset is
-- saved. eq_type and bands_r are not optional extras: one Room table serves
-- both EQ screens and the DAO filters strictly on eq_type, so a row that loses
-- it disappears from one screen and appears in the other, and a preset that
-- loses bands_r loses the calibration for one ear.

alter table public.eq_presets
  add column if not exists bands_r       text,
  add column if not exists eq_type       integer not null default 0,
  add column if not exists created_at_ms bigint  not null default 0,
  add column if not exists updated_at_ms bigint  not null default 0;

-- bands holds an opaque serialized string that only the app parses. As jsonb it
-- promised operators that could never work on it, because a client-side String
-- lands as a jsonb string scalar rather than an array. mix_presets.state_json
-- already settled this shape as text.
alter table public.eq_presets
  alter column bands type text using bands #>> '{}',
  alter column bands set default '[]'::text;

comment on column public.eq_presets.eq_type is
  '0 = AutoEQ, 1 = Parametric.';
comment on column public.eq_presets.updated_at_ms is
  'Device clock at the user''s last edit. Last-write-wins compares this, never
   updated_at, which the set_updated_at() trigger rewrites on every push.';

-- ── Mixer presets ───────────────────────────────────────────────────────────
--
-- local_id is the preset's creation time in epoch milliseconds, deliberately
-- not the Room row id: that is autoGenerate and starts at 1 on every device, so
-- under UNIQUE(user_id, local_id) two devices' "preset 1" would be one row.

alter table public.mix_presets
  add column if not exists created_at_ms bigint not null default 0,
  add column if not exists updated_at_ms bigint not null default 0;

comment on column public.mix_presets.local_id is
  'The preset''s creation time in epoch milliseconds, as text.';
comment on column public.mix_presets.updated_at_ms is
  'Device clock at the user''s last edit. See eq_presets.updated_at_ms.';

-- ── Already in place on both tables ─────────────────────────────────────────
--
--   primary key (id uuid default gen_random_uuid())
--   unique (user_id, local_id)          -- the upsert's conflict target
--   row level security enabled, with select/insert/update/delete "own" policies
--     matching user_id = auth.uid(), plus an ALL catch-all
--   trigger <table>_updated_at before update ... execute set_updated_at()

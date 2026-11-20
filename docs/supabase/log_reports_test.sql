-- Holds log_reports.sql to what it promises: who may write, the day's limit,
-- the 90 days, and what the analysis says of a log. Never run it on the real
-- project: it is for a scratch database on any local Postgres, from the
-- repository's root:
--
--   createdb lr_test && psql -q -v ON_ERROR_STOP=1 -d lr_test -f docs/supabase/log_reports_test.sql; dropdb lr_test
--
-- It ends with "log_reports: all held", or stops at the first thing that did not.

\set ON_ERROR_STOP 1
set client_min_messages = warning;

-- What Supabase gives a project and a bare Postgres does not.
do $$ begin
    if not exists (select from pg_roles where rolname = 'anon') then create role anon nologin; end if;
    if not exists (select from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end $$;
create schema if not exists auth;
create table if not exists auth.users (id uuid primary key);
create or replace function auth.uid() returns uuid language sql stable
    as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
grant usage on schema auth, public to anon, authenticated;
alter default privileges in schema public grant all on tables to anon, authenticated;
insert into auth.users values ('00000000-0000-0000-0000-000000000001'), ('00000000-0000-0000-0000-000000000002')
    on conflict do nothing;

\i docs/supabase/log_reports.sql

-- An account writes its own report: an exception with its frames, slow seconds, a freeze, and what Android,
-- the phone's maker and a library say in every log.
set role authenticated;
set request.jwt.claim.sub = '00000000-0000-0000-0000-000000000001';
insert into public.log_reports (user_id, manufacturer, model, device, android_release, android_sdk, app_version,
                                app_version_code, git_commit, note, context, previous_exits, log)
values ('00000000-0000-0000-0000-000000000001', 'Maker', 'Phone 1', 'p1', '16', 36, '1.9.6', 198, 'abc1234', 'it stutters', '{}', '[]',
$log$(12 earlier lines left out: the log was too long to send whole)
11-18 10:00:00.001  1234  1234 D Player: starting
11-18 10:00:00.002  1234  1234 E HWUI: dequeueBuffer returned unrecognized buffer due to compatible with vulkan.
11-18 10:00:00.003  1234  1234 W VRI[MainActivity]: handleResized abandoned!
11-18 10:00:00.010  1234  1300 E SupabaseSync: pushSettings failed
11-18 10:00:00.011  1234  1300 E SupabaseSync: java.lang.IllegalStateException: no session
11-18 10:00:00.012  1234  1300 E SupabaseSync: 	at io.ktor.client.Foo.bar(Foo.kt:3)
11-18 10:00:00.013  1234  1300 E SupabaseSync: 	at tf.monochrome.android.data.sync.SupabaseSync.pushSettings(SupabaseSync.kt:212)
11-18 10:00:01.011  1234  1300 E SupabaseSync: java.lang.IllegalStateException: no session
11-18 10:00:02.000  1234  1234 E DownloadManager: could not write the file
11-18 10:00:02.100  1234  1234 E VibratorInfo: Invalid frequency profile received from HAL. resonantFrequencyHz=NaN, frequenciesHz=null
11-18 10:00:02.200  1234  1234 E OplusPredictiveBackController:  NoSuchMethodException
11-18 10:00:02.300  1234  1234 W NotificationProvider: Failed to load bitmap: androidx.media3.datasource.HttpDataSource$HttpDataSourceException: java.net.MalformedURLException: unknown protocol
11-18 10:00:03.000  1234  1234 I FrameStall: slow second on lyrics: 64 frames, 30 over 16 ms, worst 88 ms; slow frames average app 6 sync 2 issue 31 swap 4 gpu 22 ms; drew lens 0 (0.0 Mpx) frost 2 (1.1 Mpx) slab 30 (9.0 Mpx) rays 64 curve 3
11-18 10:00:04.000  1234  1234 I FrameStall: slow second on lyrics: 70 frames, 20 over 16 ms, worst 40 ms; slow frames average app 5 sync 2 issue 20 swap 4 gpu 19 ms; drew lens 0 (0.0 Mpx) frost 2 (1.1 Mpx) slab 30 (9.0 Mpx) rays 70 curve 3
11-18 10:00:05.000  1234  1234 I FrameStall: slow second on home: 100 frames, 12 over 16 ms, worst 30 ms; slow frames average app 20 sync 2 issue 3 swap 4 gpu 5 ms; drew lens 0 (0.0 Mpx) frost 0 (0.0 Mpx) slab 3 (1.0 Mpx) rays 0 curve 0
11-18 10:00:06.000  1234  1234 I FrameStall: no frame for 840 ms on equalizer
$log$);

-- It can do nothing else: not read, change or delete, not write as another account, not run the analysis.
do $$ begin
    begin perform count(*) from public.log_reports; raise exception 'an account read the reports';
    exception when insufficient_privilege then null; end;
    begin update public.log_reports set note = 'x'; raise exception 'an account changed a report';
    exception when insufficient_privilege then null; end;
    begin delete from public.log_reports; raise exception 'an account deleted a report';
    exception when insufficient_privilege then null; end;
    begin
        insert into public.log_reports (user_id, manufacturer, model, android_release, android_sdk, app_version, app_version_code, log)
        values ('00000000-0000-0000-0000-000000000002', 'M', 'P', '16', 36, '1', 1, 'x');
        raise exception 'an account wrote as another';
    exception when insufficient_privilege then null; end;
    begin perform public.analyze_log_report('x', '[]', '{}', now()); raise exception 'an account ran the analysis';
    exception when insufficient_privilege then null; end;
end $$;
reset role;
set role anon;
do $$ begin
    insert into public.log_reports (user_id, manufacturer, model, android_release, android_sdk, app_version, app_version_code, log)
    values ('00000000-0000-0000-0000-000000000002', 'M', 'P', '16', 36, '1', 1, 'x');
    raise exception 'a report was taken without a sign-in';
exception when insufficient_privilege then null;
end $$;
reset role;

-- What the analysis made of it.
do $$
declare r public.log_reports;
begin
    select * into strict r from public.log_reports where note = 'it stutters';
    assert r.line_count = 16, 'lines: ' || r.line_count;
    assert r.severity = 'error', 'severity: ' || r.severity;
    assert r.signature = 'exc:java.lang.IllegalStateException@tf.monochrome.android.data.sync.SupabaseSync.pushSettings', r.signature;
    assert r.headline = 'IllegalStateException x2 in SupabaseSync at tf.monochrome.android.data.sync.SupabaseSync.pushSettings(SupabaseSync.kt:212): no session', r.headline;
    -- Slow drawing: the screen with most slow seconds, where the time went, the god rays, the freeze.
    assert r.analysis -> 'findings' @> '[{"kind": "stutter", "key": "stall:lyrics:gpu", "text": "3 slow seconds, most on lyrics (worst frame 88 ms; app 6 ms, gpu 21 ms: the drawing itself, god rays on); 1 freeze, longest 840 ms on equalizer"}]',
        (r.analysis -> 'findings')::text;
    -- A tag of the app's own that logged an error and threw nothing is named. A tag that is not the app's
    -- never is, whatever it says (the first report from a phone had the maker's VibratorInfo as its headline),
    -- nor one an exception already names.
    assert r.analysis -> 'findings' @> '[{"key": "err:DownloadManager", "kind": "error"}]', 'the app''s own error tag is missing';
    assert not exists (select from jsonb_array_elements(r.analysis -> 'findings') f
                       where f ->> 'key' in ('err:HWUI', 'err:VibratorInfo', 'err:OplusPredictiveBackController', 'err:SupabaseSync')),
        (r.analysis -> 'findings')::text;
    -- An exception under someone else's tag is kept, as a note after the app's own: it may still be the app's doing.
    assert r.analysis -> 'findings' -> 1 @> '{"kind": "info", "key": "exc:androidx.media3.datasource.HttpDataSource$HttpDataSourceException@NotificationProvider"}',
        (r.analysis -> 'findings' -> 1)::text;
    assert r.analysis -> 'levels' = '{"D": 1, "E": 9, "I": 4, "W": 2}', (r.analysis -> 'levels')::text;
    assert (r.analysis ->> 'app_errors')::integer = 6, r.analysis ->> 'app_errors';
    -- A phone cannot write its own analysis: the row holds what the table worked out.
    assert r.analyzed_at is not null and r.created_at > now() - interval '1 minute';
end $$;

-- Crashes are read from how the last processes ended, lately only, and a background kill is never the headline.
do $$
declare a jsonb;
begin
    a := public.analyze_log_report(
        '11-18 10:00:00.001  1234  1234 D Player: starting',
        jsonb_build_array(
            jsonb_build_object('at_ms', (extract(epoch from now() - interval '5 minutes') * 1000)::bigint, 'reason', 'LOW_MEMORY', 'description', ''),
            jsonb_build_object('at_ms', (extract(epoch from now() - interval '1 hour') * 1000)::bigint, 'reason', 'CRASH_NATIVE',
                'trace', E'SIGSEGV\n/apex/com.android.runtime/lib64/bionic/libc.so\n/data/app/~~a==/tf.monotrypt.android-b==/lib/arm64/libmonochrome_atmos_jni.so'),
            jsonb_build_object('at_ms', (extract(epoch from now() - interval '9 days') * 1000)::bigint, 'reason', 'ANR', 'description', 'old')),
        '{"previous_crumbs": {"screen": "atmos_renderer"}}', now());
    assert a ->> 'severity' = 'crash' and a ->> 'signature' = 'crash:native:libmonochrome_atmos_jni.so:SIGSEGV', a ->> 'signature';
    assert a ->> 'headline' = 'Native crash in libmonochrome_atmos_jni.so (SIGSEGV) on atmos_renderer', a ->> 'headline';
    assert jsonb_array_length(a -> 'findings') = 2 and a -> 'findings' -> 1 ->> 'key' = 'killed:low_memory', (a -> 'findings')::text;

    a := public.analyze_log_report('', '[]', jsonb_build_object('last_crash', jsonb_build_object(
        'at_ms', (extract(epoch from now() - interval '10 minutes') * 1000)::bigint, 'thread', 'main',
        'stack', E'java.lang.NullPointerException: Attempt to read from null\n\tat android.view.View.draw(View.java:1)\n\tat tf.monochrome.android.ui.player.NowPlaying.draw(NowPlaying.kt:88)\n')), now());
    assert a ->> 'headline' = 'Crashed: java.lang.NullPointerException at tf.monochrome.android.ui.player.NowPlaying.draw(NowPlaying.kt:88): Attempt to read from null', a ->> 'headline';
    assert a ->> 'signature' = 'crash:java:java.lang.NullPointerException@tf.monochrome.android.ui.player.NowPlaying.draw', a ->> 'signature';

    -- Only a background kill: said, and not as a crash.
    a := public.analyze_log_report('', jsonb_build_array(jsonb_build_object(
        'at_ms', (extract(epoch from now()) * 1000)::bigint, 'reason', 'LOW_MEMORY')), '{}', now());
    assert a ->> 'severity' = 'info', a ->> 'severity';

    -- Only what is not the app's: nothing stands out, and no "NaN" from the phone's maker in the headline.
    a := public.analyze_log_report(
        '11-18 10:00:02.100  1234  1234 E VibratorInfo: Invalid frequency profile received from HAL. resonantFrequencyHz=NaN',
        '[]', '{}', now());
    assert a ->> 'severity' = 'ok' and a ->> 'headline' = 'Nothing stands out in this log', a ->> 'headline';
    a := public.analyze_log_report(
        '11-18 10:00:02.300  1234  1234 W NotificationProvider: Failed to load bitmap: java.net.MalformedURLException: unknown protocol: subsonic-cover',
        '[]', '{}', now());
    assert a ->> 'severity' = 'info' and a ->> 'headline' = 'MalformedURLException x1 in NotificationProvider: unknown protocol: subsonic-cover', a::text;

    -- Nothing in it, and shapes a phone should never send: still an answer, never an error.
    a := public.analyze_log_report('not a logcat line', '{"not": "an array"}', '{"last_crash": "text"}', now());
    assert a ->> 'severity' = 'ok' and a ->> 'signature' = 'none' and (a ->> 'lines')::integer = 0, a::text;
end $$;

-- Ten a day an account; the eleventh is refused with the state PostgREST answers 429 for.
set role authenticated;
set request.jwt.claim.sub = '00000000-0000-0000-0000-000000000002';
do $$ begin
    for i in 1..10 loop
        insert into public.log_reports (user_id, manufacturer, model, android_release, android_sdk, app_version, app_version_code, log)
        values ('00000000-0000-0000-0000-000000000002', 'M', 'P', '16', 36, '1', 1, 'x');
    end loop;
    begin
        insert into public.log_reports (user_id, manufacturer, model, android_release, android_sdk, app_version, app_version_code, log)
        values ('00000000-0000-0000-0000-000000000002', 'M', 'P', '16', 36, '1', 1, 'eleventh');
        raise exception 'an eleventh report in a day was taken';
    exception when sqlstate 'PT429' then null;
    end;
end $$;
reset role;

-- A report older than 90 days goes when the next one arrives, and the rules can be run again over a kept one.
alter table public.log_reports disable trigger log_reports_analyze;
update public.log_reports set created_at = now() - interval '91 days' where note = 'it stutters';
alter table public.log_reports enable trigger log_reports_analyze;
set role authenticated;
set request.jwt.claim.sub = '00000000-0000-0000-0000-000000000001';
insert into public.log_reports (user_id, manufacturer, model, android_release, android_sdk, app_version, app_version_code, log)
values ('00000000-0000-0000-0000-000000000001', 'M', 'P', '16', 36, '1', 1,
        '11-18 10:00:00.011  1234  1300 E SupabaseSync: java.net.SocketTimeoutException: timeout');
reset role;
do $$
declare r public.log_reports;
begin
    assert not exists (select from public.log_reports where note = 'it stutters'), 'a report older than 90 days was kept';
    update public.log_reports set headline = 'stale', analyzed_at = null where user_id = '00000000-0000-0000-0000-000000000001';
    select * into strict r from public.log_reports where user_id = '00000000-0000-0000-0000-000000000001';
    assert r.headline = 'SocketTimeoutException x1 in SupabaseSync: timeout' and r.severity = 'error' and r.analyzed_at is not null, r.headline;
end $$;

\echo log_reports: all held

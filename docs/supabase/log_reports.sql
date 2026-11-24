-- Logs a listener sends from Settings › System › Send logs (debug/LogReport.kt).
--
-- One row a report: the phone, the build, the Android version, what the
-- listener wrote, how the last processes ended, and the app's own log, already
-- through LogRedactor. An account may add rows and nothing else: it cannot
-- read, change or delete one, its own included. They are read with the
-- service role only (the Supabase connector; the owner's dashboard).
--
-- Each row is analysed as it is written (analyze_log_report): what went wrong
-- in a sentence (headline), a key that is the same for the same fault on
-- another phone (signature), and the numbers behind them (analysis). The rules
-- live here, not in the app, so they improve without a release. After
-- changing them, run them over what is already there:
--
--   update public.log_reports set analyzed_at = null;
--
-- At most 10 reports an account a day; a report is kept 90 days.

create table if not exists public.log_reports (
    id               bigint generated always as identity primary key,
    user_id          uuid not null default auth.uid() references auth.users (id) on delete cascade,
    created_at       timestamptz not null default now(),
    manufacturer     text not null check (char_length(manufacturer) <= 100),
    model            text not null check (char_length(model) <= 100),
    device           text not null default '' check (char_length(device) <= 100),
    android_release  text not null check (char_length(android_release) <= 40),
    android_sdk      integer not null,
    app_version      text not null check (char_length(app_version) <= 40),
    app_version_code integer not null,
    git_commit       text not null default '' check (char_length(git_commit) <= 64),
    note             text not null default '' check (char_length(note) <= 2000),
    context          jsonb not null default '{}'::jsonb check (pg_column_size(context) <= 200000),
    previous_exits   jsonb not null default '[]'::jsonb check (pg_column_size(previous_exits) <= 400000),
    log              text not null check (char_length(log) <= 2500000),
    line_count       integer,
    severity         text,
    headline         text,
    signature        text,
    analysis         jsonb,
    analyzed_at      timestamptz
);

create index if not exists log_reports_created_at on public.log_reports (created_at desc);
create index if not exists log_reports_user_day on public.log_reports (user_id, created_at desc);
create index if not exists log_reports_signature on public.log_reports (signature);

alter table public.log_reports enable row level security;

revoke all on public.log_reports from anon, authenticated;
grant insert on public.log_reports to authenticated;

drop policy if exists "log_reports: insert own" on public.log_reports;
create policy "log_reports: insert own" on public.log_reports
    for insert to authenticated
    with check ((select auth.uid()) = user_id);

-- What a log says, as JSON: severity, headline, signature, findings and the
-- counts behind them. p_at is when the report was sent: "the app crashed
-- lately" is measured from it, so analysing an old report again says the same.
create or replace function public.analyze_log_report(p_log text, p_exits jsonb, p_context jsonb, p_at timestamptz)
returns jsonb
language plpgsql
stable
set search_path = ''
as $fn$
declare
    -- The tags the app's own code logs with. A sent log is the whole
    -- process's logcat, and Android, the phone's maker and the libraries warn
    -- and err in it on every phone: only these tags' errors are a fault.
    -- app-tags:begin (written by tools/log_reports/app_tags.py, never by hand)
    own constant text[] := array[
        'AlbumArtFinder', 'AmbientComposite', 'AmbientVisualizer', 'ArtworkStoreMigration', 'AtmosPipeline',
        'AtmosProcessor', 'AtmosRoom', 'AtmosSofaStore', 'AtmosTap', 'AtmosTrackProvider',
        'AudioProcessorChain', 'ChartQueue', 'CrashLogger', 'CrtPresetStore', 'DebugScreenRecorder',
        'DebugScreenRecordSvc', 'DeviceRegistry', 'DiscordPresence', 'DownloadManager', 'DownloadQueueWorker',
        'DspEngineManager', 'DynamicRange', 'EqProcessor', 'EqRepository', 'FloatPcmGuard', 'FolderIndexer',
        'FrameStall', 'GalaxyFx', 'GlyphAssets', 'GlyphAttempts', 'GlyphSongs', 'GlyphStyleLibrary',
        'GlyphTvShader', 'GlyphViewModel', 'HeadphoneAutoEqApi', 'HiFiApiClient', 'ImportSync',
        'IndexedFragMp4', 'LastFmAuth', 'LibraryFiles', 'LibraryRestore', 'LibraryWatcher', 'LibusbAudioSink',
        'LibusbUacDriver', 'ListTrackLoader', 'LiveGlassLens', 'LiveLog', 'LogReport', 'LyricsCurvature',
        'LyricsFx', 'LyricsResolver', 'MediaScanner', 'MonochromeDSP', 'MonochromeDSP_JNI', 'MonoPerf',
        'MusicDatabase', 'MusicLinkPresetStore', 'OfflineMeasurements', 'OpenedAudio', 'PartyClient',
        'PartyController', 'PartyService', 'PlaybackService', 'PlaybackState', 'PlayerViewModel', 'PlaySession',
        'PoTokenProvider', 'PoTokenWebView', 'PresetCrashGuard', 'ProjectMAssetInstaller', 'ProjectMBridge',
        'ProjectMEngineRepository', 'ProjectMNativeBridge', 'ProjectMOverlay', 'QueueForwardingPlayer',
        'RadioQueueManager', 'RarNative', 'RoomClient', 'SafeClipboard', 'ScanCoordinator', 'Scrobbling',
        'SearchViewModel', 'SettingsSync', 'ShareLinks', 'ShareShrinker', 'ShizukuPeq', 'SofaHrtfApi',
        'SongPreview', 'SourceDepthCodec', 'SquiglinkApi', 'StepManiaConversion', 'StreamResolver',
        'SupabaseAuth', 'SupabaseSync', 'SystemAudioEq', 'SystemEqService', 'SystemPeqService', 'TagReader',
        'TimedLyrics', 'TrackDownloader', 'TrackShareHelper', 'TryptifyConnect', 'TryptifyGlyph',
        'TryptifyMotion', 'UsbExclusiveCtl', 'UsbJni', 'YoutubeOnDevice'
    ];
    -- app-tags:end
    agg       jsonb;
    findings  jsonb := '[]'::jsonb;
    x         jsonb;
    recent    interval := interval '48 hours';
    lib       text;
    sig       text;
    at_frame  text;
    later     jsonb := '[]'::jsonb;
    top       text;
    kind      text;
    n_gap     integer;
    n_slow    integer;
begin
    with raw as (
        select t.n, t.line
        from regexp_split_to_table(coalesce(p_log, ''), E'\n') with ordinality as t(line, n)
    ), l as materialized (
        select r.n, m[1] as ts, m[3] as lvl, btrim(m[4]) as tag, m[5] as msg
        from raw r
        cross join lateral regexp_match(
            r.line, '^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+\d+\s+([VDIWEFS])\s+([^:]+):\s?(.*)$') as m
        where m is not null
    ), tagged as (
        select l.*, not (l.tag = any (own)) as noisy
        from l
    ), exc as (
        select t.n, t.tag, t.noisy, e[1] as cls, left(coalesce(e[2], ''), 200) as detail
        from tagged t
        cross join lateral regexp_match(
            t.msg, '((?:[a-z_][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_$]*(?:Exception|Error))(?::\s*(.*))?') as e
        where t.lvl in ('W', 'E', 'F') and e is not null and t.msg !~ '^\s*at\s'
    ), exc_g as (
        -- The app's own first: someone else's flood must not push them out of the eight.
        select cls, count(*) as c, min(n) as first_n, bool_or(not noisy) as ours,
               (array_agg(tag order by noisy, n))[1] as tag,
               (array_agg(detail order by noisy, n))[1] as detail
        from exc group by cls order by bool_or(not noisy) desc, count(*) desc, min(n) limit 8
    ), exc_w as (
        select g.cls, g.c, g.tag, g.detail, g.ours,
               (select substring(f.msg from '^\s*at\s+([^(]+\([^)]*\))')
                from l f
                where f.n > g.first_n and f.n <= g.first_n + 60 and f.msg ~ '^\s*at\s+tf\.monochrome\.'
                order by f.n limit 1) as frame
        from exc_g g
    ), errs as (
        select tag, bool_or(noisy) as noisy,
               count(*) filter (where lvl in ('E', 'F')) as e,
               count(*) filter (where lvl = 'W') as w,
               (array_agg(left(msg, 200) order by (lvl in ('E', 'F')) desc, n))[1] as sample
        from tagged where lvl in ('W', 'E', 'F') group by tag
    ), gap as (
        select g[1]::integer as ms, g[2] as screen
        from l cross join lateral regexp_match(l.msg, '^no frame for (\d{1,7}) ms on (.*)$') as g
        where l.tag = 'FrameStall' and g is not null
    ), slow as (
        select s[1] as screen, s[2]::integer as slow, s[3]::integer as worst, s[4]::integer as app, s[5]::integer as gpu,
               coalesce(substring(l.msg from ' rays (\d{1,6}) ')::integer, 0) as rays
        from l cross join lateral regexp_match(
            l.msg,
            '^slow second on (.*): \d{1,5} frames, (\d{1,5}) over \d+ ms, worst (\d{1,7}) ms; slow frames average app (\d{1,7}) .* gpu (\d{1,7}) ms'
        ) as s
        where l.tag = 'FrameStall' and s is not null
    ), slow_s as (
        select screen, count(*) as seconds, max(worst) as worst, round(avg(app)) as app, round(avg(gpu)) as gpu,
               count(*) filter (where rays > 0) as with_rays
        from slow group by screen order by count(*) desc limit 6
    )
    select jsonb_build_object(
        'lines', (select count(*) from l),
        'first', (select min(ts) from l),
        'last', (select max(ts) from l),
        'levels', coalesce((select jsonb_object_agg(lvl, c) from (select lvl, count(*) as c from l group by lvl) z), '{}'::jsonb),
        'fatal_lines', (select count(*) from l where lvl = 'F'),
        'fatal_sample', (select left(msg, 200) from l where lvl = 'F' order by n limit 1),
        'exceptions', coalesce((select jsonb_agg(jsonb_build_object(
            'class', cls, 'count', c, 'tag', tag, 'detail', detail, 'frame', frame,
            'ours', ours or frame is not null) order by (ours or frame is not null) desc, c desc) from exc_w), '[]'::jsonb),
        'error_tags', coalesce((select jsonb_agg(jsonb_build_object(
            'tag', tag, 'errors', e, 'warnings', w, 'sample', sample, 'noisy', noisy) order by e desc, w desc)
            from (select * from errs order by e desc, w desc limit 12) z), '[]'::jsonb),
        'app_errors', (select coalesce(sum(e), 0) from errs where not noisy),
        'stalls', jsonb_build_object(
            'gaps', (select count(*) from gap),
            'longest_gap_ms', (select max(ms) from gap),
            'longest_gap_screen', (select screen from gap order by ms desc limit 1),
            'slow_seconds', (select count(*) from slow),
            'worst_frame_ms', (select max(worst) from slow),
            'screens', coalesce((select jsonb_agg(jsonb_build_object(
                'screen', screen, 'seconds', seconds, 'worst_ms', worst, 'app_ms', app, 'gpu_ms', gpu,
                'with_rays', with_rays) order by seconds desc) from slow_s), '[]'::jsonb)
        )
    ) into agg;

    -- 1. How the app last ended, if lately: a crash or a freeze is not in the
    --    log, which starts with the process that was sent from.
    if jsonb_typeof(p_context -> 'last_crash') = 'object'
        and (p_context -> 'last_crash' ->> 'at_ms') ~ '^\d{10,14}$'
        and to_timestamp((p_context -> 'last_crash' ->> 'at_ms')::double precision / 1000) > p_at - recent then
        x := p_context -> 'last_crash';
        top := substring(x ->> 'stack' from '((?:[a-z_][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_$]*)');
        at_frame := substring(x ->> 'stack' from '\sat\s+(tf\.monochrome\.[^(]+\([^)]*\))');
        if at_frame is null then at_frame := substring(x ->> 'stack' from '\sat\s+([^(]+\([^)]*\))'); end if;
        findings := findings || jsonb_build_object(
            'kind', 'crash',
            'key', 'crash:java:' || coalesce(top, '?') || '@' || coalesce(split_part(at_frame, '(', 1), '?'),
            'text', 'Crashed: ' || coalesce(top, 'an exception') || coalesce(' at ' || at_frame, '')
                || coalesce(': ' || nullif(left(split_part(split_part(x ->> 'stack', E'\n', 1), ': ', 2), 160), ''), ''));
    end if;

    if jsonb_typeof(p_exits) = 'array' then
        for x in
            select e from jsonb_array_elements(p_exits) as e
            where (e ->> 'at_ms') ~ '^\d{10,14}$'
              and to_timestamp((e ->> 'at_ms')::double precision / 1000) > p_at - recent
            order by (e ->> 'at_ms')::double precision desc
        loop
            kind := x ->> 'reason';
            if kind = 'CRASH_NATIVE' then
                -- The app's own libraries are under its install directory; a system one is the fallback.
                lib := coalesce(
                    substring(x ->> 'trace' from '/data/app/[^\s]*/(lib[A-Za-z0-9_+.-]+\.so)'),
                    substring(x ->> 'trace' from '(lib[A-Za-z0-9_+-]+\.so)'));
                sig := substring(x ->> 'trace' from '\m(SIG(?:SEGV|ABRT|BUS|FPE|ILL|TRAP|SYS))\M');
                findings := findings || jsonb_build_object(
                    'kind', 'crash',
                    'key', 'crash:native:' || coalesce(lib, '?') || ':' || coalesce(sig, '?'),
                    'text', 'Native crash' || coalesce(' in ' || lib, '') || coalesce(' (' || sig || ')', '')
                        || coalesce(' on ' || nullif(p_context -> 'previous_crumbs' ->> 'screen', ''), ''));
            elsif kind = 'ANR' then
                findings := findings || jsonb_build_object(
                    'kind', 'crash', 'key', 'crash:anr',
                    'text', 'Froze (not responding)'
                        || coalesce(': ' || nullif(left(x ->> 'description', 160), ''), ''));
            elsif kind in ('LOW_MEMORY', 'EXCESSIVE_RESOURCE_USAGE', 'INITIALIZATION_FAILURE') then
                later := later || jsonb_build_object(
                    'kind', 'info', 'key', 'killed:' || lower(kind),
                    'text', 'The system ended the app: ' || lower(replace(kind, '_', ' '))
                        || coalesce(' (' || nullif(left(x ->> 'description', 120), '') || ')', ''));
            end if;
        end loop;
    end if;

    if (agg ->> 'fatal_lines')::integer > 0 then
        findings := findings || jsonb_build_object(
            'kind', 'crash', 'key', 'crash:fatal-line',
            'text', 'A fatal line in the log: ' || coalesce(agg ->> 'fatal_sample', ''));
    end if;

    -- 2. Exceptions the log names: the app's own (its tag, or its code in the
    --    trace) first and as errors, most frequent first. One from anyone
    --    else's tag is a note: it may be the app's doing (a bad address handed
    --    to a library) or the phone's own business.
    for x in select e from jsonb_array_elements(agg -> 'exceptions') as e limit 5 loop
        top := regexp_replace(x ->> 'class', '^.*\.', '');
        findings := findings || jsonb_build_object(
            'kind', case when (x ->> 'ours')::boolean then 'error' else 'info' end,
            'key', 'exc:' || (x ->> 'class') || '@' || coalesce(split_part(x ->> 'frame', '(', 1), x ->> 'tag'),
            'count', (x ->> 'count')::integer,
            'text', top || ' x' || (x ->> 'count') || ' in ' || (x ->> 'tag')
                || coalesce(' at ' || (x ->> 'frame'), '')
                || coalesce(': ' || nullif(left(x ->> 'detail', 140), ''), ''));
    end loop;

    -- 3. Slow drawing, from FrameStallLog's lines (there only with the FPS counter or the live log on).
    n_gap := (agg -> 'stalls' ->> 'gaps')::integer;
    n_slow := (agg -> 'stalls' ->> 'slow_seconds')::integer;
    if n_slow >= 3 or coalesce((agg -> 'stalls' ->> 'longest_gap_ms')::integer, 0) >= 700 then
        x := agg -> 'stalls' -> 'screens' -> 0;
        findings := findings || jsonb_build_object(
            'kind', 'stutter',
            'key', 'stall:' || coalesce(x ->> 'screen', agg -> 'stalls' ->> 'longest_gap_screen', '?') || ':'
                || case when x is null then 'gap'
                        when (x ->> 'gpu_ms')::numeric >= (x ->> 'app_ms')::numeric then 'gpu' else 'app' end,
            'count', n_slow,
            'text', case when n_slow > 0 then
                    n_slow || case when n_slow = 1 then ' slow second, most on ' else ' slow seconds, most on ' end || (x ->> 'screen') || ' (worst frame '
                    || (agg -> 'stalls' ->> 'worst_frame_ms') || ' ms; app ' || (x ->> 'app_ms') || ' ms, gpu '
                    || (x ->> 'gpu_ms') || ' ms: '
                    || case when (x ->> 'gpu_ms')::numeric >= (x ->> 'app_ms')::numeric
                            then 'the drawing itself' else 'the app''s code' end
                    || case when (x ->> 'with_rays')::integer * 2 > (x ->> 'seconds')::integer
                            then ', god rays on' else '' end || ')'
                else '' end
                || case when n_gap > 0 then
                    case when n_slow > 0 then '; ' else '' end || n_gap
                    || case when n_gap = 1 then ' freeze, longest ' else ' freezes, longest ' end
                    || (agg -> 'stalls' ->> 'longest_gap_ms') || ' ms on ' || (agg -> 'stalls' ->> 'longest_gap_screen')
                else '' end);
    end if;

    -- 4. The app's own tags that logged errors (never anyone else's), where no exception above already names the tag.
    for x in
        select e from jsonb_array_elements(agg -> 'error_tags') as e
        where not (e ->> 'noisy')::boolean and (e ->> 'errors')::integer > 0
          and not exists (select from jsonb_array_elements(agg -> 'exceptions') as ex where ex ->> 'tag' = e ->> 'tag')
        limit 4
    loop
        findings := findings || jsonb_build_object(
            'kind', 'error', 'key', 'err:' || (x ->> 'tag'),
            'count', (x ->> 'errors')::integer,
            'text', (x ->> 'tag') || ': ' || (x ->> 'errors') || ' errors, first "' || left(x ->> 'sample', 140) || '"');
    end loop;

    -- 5. A background app ended for memory is ordinary: said last, and never as a crash.
    findings := findings || later;

    return agg || jsonb_build_object(
        'findings', findings,
        'severity', coalesce(findings -> 0 ->> 'kind', 'ok'),
        'headline', coalesce(findings -> 0 ->> 'text', 'Nothing stands out in this log'),
        'signature', coalesce(findings -> 0 ->> 'key', 'none'));
end
$fn$;

revoke all on function public.analyze_log_report(text, jsonb, jsonb, timestamptz) from public, anon, authenticated;

-- On a new report: the day's limit, the 90 days, then the analysis. On an
-- update (only the service role can make one) the analysis again.
create or replace function public.log_reports_analyze()
returns trigger
language plpgsql
security definer
set search_path = ''
as $fn$
declare
    a jsonb;
begin
    if tg_op = 'INSERT' then
        new.created_at := now();
        if (select count(*) from public.log_reports r
            where r.user_id = new.user_id and r.created_at > now() - interval '24 hours') >= 10 then
            -- PostgREST answers a PT429 with HTTP 429.
            raise sqlstate 'PT429' using message = 'log_reports: daily limit reached';
        end if;
        delete from public.log_reports where created_at < now() - interval '90 days';
    end if;
    begin
        a := public.analyze_log_report(new.log, new.previous_exits, new.context, new.created_at);
        new.line_count := (a ->> 'lines')::integer;
        new.severity := a ->> 'severity';
        new.headline := left(a ->> 'headline', 500);
        new.signature := left(a ->> 'signature', 300);
        new.analysis := a - 'severity' - 'headline' - 'signature';
    exception when others then
        -- A log the rules choke on is still a log: it is kept, and says so.
        new.severity := 'unread';
        new.headline := 'The analysis failed: ' || left(sqlerrm, 300);
        new.signature := 'analysis-failed';
        new.analysis := null;
    end;
    new.analyzed_at := now();
    return new;
end
$fn$;

revoke all on function public.log_reports_analyze() from public, anon, authenticated;

drop trigger if exists log_reports_analyze on public.log_reports;
create trigger log_reports_analyze
    before insert or update on public.log_reports
    for each row execute function public.log_reports_analyze();

-- Running this file again is how the rules above reach the project, so it
-- ends by reading the newest reports again with them (each update runs the
-- analysis). Fifty, not all: a full log takes half a second.
update public.log_reports set analyzed_at = null
where id in (select id from public.log_reports order by id desc limit 50);

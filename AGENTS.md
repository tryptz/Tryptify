# Tryptify — notes for agents

Project notes live here and under `docs/`. `CLAUDE.md` imports this file and
adds the owner's standing rules (branch, secrets, how to report back).

## Read before changing the UI

**[`docs/ui-invariants.md`](docs/ui-invariants.md)** — the current, accepted look,
written as invariants with the failure each one prevents.

Read it before touching glass, the lyrics' glass and god rays, music links,
titles and text on glass, search bars, press feedback, pages and the tab bar,
list rows, themes and Theme Store pictures, the globe, the galaxies, stats
charts, Trypt Mania (its menus, playfield and the CRT effect), or the Discord
presence artwork. Every rule in it is a bug that was already found and fixed;
none of them are obvious from the code, which is exactly why they kept getting
undone. A slab drawn at a tenth opacity looks like a deliberate choice. A
frosted pane behind a list row looks like an improvement. A search bar laid out
inline looks perfectly ordinary.

The build those rules describe is commit `281c07a2` (1.9.4). If something looks
wrong and you are not sure whether it changed, diff against it:

```
git diff 281c07a2 -- app/src/main/java/tf/monochrome/android/ui
```

## Route the work before starting

**[`docs/agent-playbook.md`](docs/agent-playbook.md)** — per-area playbooks, each
with a workflow and completion gates: Glass Surfaces, Music Links, Realtime
Audio, Playback Routing, Radio Ranking, Cloud Sync, Trypt Mania, and the
engineering gates for build upgrades, shrinking and intent security.

Read the section that matches what you are touching before you touch it. The
playbook also carries a re-verified list of known review targets — places where
a shipped skill, a test target or a keep rule is already known to disagree with
the code — so you can tell a pre-existing defect from one you just introduced.

Conventions in this file override it wherever the two differ.

## Build and test

```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

`assembleDebug` needs the git submodules (`third_party/projectm`, `libusb`)
checked out; without them it fails for reasons unrelated to your change.
Fetch them **recursively** — projectM has its own nested submodule
(`third_party/projectm/vendor/projectm-eval`), and a plain `--init` leaves it
empty, so CMake stops with "`vendor/projectm-eval` does not contain a
CMakeLists.txt file":

```
git submodule update --init --recursive --depth 1
```

The MilkDrop preset pack the visualizer ships is not a submodule. It is
committed at `app/src/main/projectm-assets/presets`, and a build task packs it
into one `assets/projectm/presets.zip`. Never put presets under
`src/main/assets/`. Everything there is copied into the APK as-is, next to the
zip. A submodule there used to do exactly that: about 29 MB of loose `.milk`
files that nothing ever opened.

The native DSP has host tests that build with the desktop compiler, no
device or NDK needed — the multichannel mixer, the Oxford stages at Atmos
widths, the WSOLA shifter, the EBU R128 loudness meter (against EBU Tech
3341/3342's reference signals), the dynamic range meter (against signals whose
DR is known by hand), the spectrum waterfall's geometry and the knobs' music
links (`mod_route_test`, held to the same numbers as the Kotlin
`MusicLinkFollowerTest`). The same script runs the USB DAC bypass driver
against stubbed libusb (`usb/tests/`), which needs the `third_party/libusb`
submodule and is skipped without it:

```
app/src/main/cpp/dsp/tests/run_host_tests.sh
```

Two more native suites are CMake projects of their own, built and run on the
desktop the same way: the Atmos renderer's (`atmos/tests/`, sixteen tests; the
script above runs only `channel_placer_test` from it) and Trypt Mania's game
engine (`glyph/tests/`, held to the same rules as its Kotlin twin):

```
cmake -S app/src/main/cpp/atmos/tests -B build/atmos-tests
cmake --build build/atmos-tests && ctest --test-dir build/atmos-tests --output-on-failure
cmake -S app/src/main/cpp/glyph/tests -B build/glyph-tests
cmake --build build/glyph-tests && ctest --test-dir build/glyph-tests --output-on-failure
```

Nothing runs any of these automatically: the one CI workflow
(`.github/workflows/main.yml`) only builds and signs a release.

## Strings

Every user-facing string is a row of `app/l10n/strings.tsv`, one column per
language. The eight `strings.xml` files are generated from it by
`python3 app/l10n/generate_strings.py` and are never edited by hand: the next
run rewrites them from the table, so a string added only to the XML vanishes
and a rewording made only there reverts. `StringResourcesTest` fails when the
XML and the table disagree. Names that are never translated (`app_name`,
`trypt_mania`) are listed in the script's `NON_TRANSLATABLE`. Plurals are rows
whose key ends `#one`, `#other` and so on, and the script refuses a row with
an empty language. Recurring terms (tab names, settings paths) are fixed in
`app/l10n/GLOSSARY.md`; reuse its row, or add one first.

## Trypt Mania's StepMania art

The retro noteskin (`assets/stepmania/skins/retro`) and the default theme's
judgement, grade and full-combo art with its typefaces
(`assets/stepmania/theme`) are cut from a StepMania checkout by
`tools/stepmania_skin/build_retro_skin.py` and `build_theme.py`. Regenerate
them with those scripts rather than editing the PNGs; each writes the licence
StepMania's notice requires next to the art.

## Trypt Mania's built-in servers are build secrets

A release ships with one TryptMania Parser server built in, the rooms server
for multiplayer and listening parties. Its address carries a secret path, so it
comes only from the gitignored `local.properties` or the environment, never
from the repository (`app/build.gradle.kts`):

| `local.properties` key | or environment variable | becomes | what it is |
|---|---|---|---|
| `tryptmania.roomsServer` | `TRYPTMANIA_ROOMS_SERVER` | `BuildConfig.BUILT_IN_ROOMS_SERVER` | the multiplayer rooms server |
| `tryptmania.cpuServer` | `TRYPTMANIA_CPU_SERVER` | `BuildConfig.RETIRED_CPU_HOST_SHA256` (its host's hash only) | the chart server, **never built in** |

**The chart server is never put in the default API list** (the owner's rule).
No build adds it under Settings › Connections, and charting waits until the
listener adds a server there. Builds before 2026-10-23 did add it, so the
build carries only the SHA-256 of its host, and `PreferencesManager.
seedBuiltInServer` takes that entry out once (`retired_cpu_server_removed`);
one the listener adds again stays. The address itself is not in the APK.

The `local.properties` key wins over the variable. A release build for the
owner needs the rooms key. Without it there is no rooms server; with it,
`BuiltInServer` puts it back in that list on every read, and the list shows
only its host.

Never print, log or commit the values, and never write them into a document, a
test, a commit message or a chat reply. Anyone with the APK can dig them out;
the repository must not hand them out as well. To check without showing a
value:

```
cut -d= -f1 local.properties     # the key names only
f=app/build/generated/source/buildConfig/release/tf/monochrome/android/BuildConfig.java
grep -c 'BUILT_IN_ROOMS_SERVER = ""' $f   # 0: the rooms server is built in; 1: left out
grep -c 'BUILT_IN_CPU_SERVER' $f            # always 0: the chart server is never built in
```

After a release build, the first prints `0` when the rooms server is built in
and `1` when it was left out; the second must print `0`. The cloud setup below writes `local.properties` with
`>`, which replaces the file, so add these keys after it, not before. The CI
workflow sets neither variable, so an APK it builds has no built-in servers.

The other settings `local.properties` can override (`spotify.clientId`,
`themeStore.url`, `lastfm.apiKey`) are public and have committed defaults.
They are not secrets, but the rule for `local.properties` stays the same: it is
never committed.

## Plain http:// is for servers at home only

`data/net/CleartextPolicy.kt` is the one rule, and every HTTP stack asks it:
the API client (an OkHttp network interceptor in `NetworkModule`, so
redirects are checked too), the player and song previews (a
`ResolvingDataSource` in front of the HTTP source), and Coil (its own
OkHttp fetcher in `MonochromeApp`). http:// goes only to localhost, private
and link-local ranges, 100.64/10 (Tailscale) and home names (`.local`,
`.lan`, `.home.arpa`, `.internal`). Audio and covers without a sign-in in
their address (internet radio) may still use http:// anywhere. A Subsonic
plain password (`p=`) never goes over http://, home or not. The manifest keeps
`usesCleartextTraffic="true"` because Android's own config cannot say "any
private address"; the policy is what refuses.

Logs go through `debug/LogRedactor.kt` before they are kept, shown or written
to a crash file: tokens, codes, Subsonic `t`/`s`/`p`/`u`, keys, JWTs and the
rooms server's secret path. Sign-in links are PKCE only
(`SupabaseAuthManager.handleDeepLink`); tokens in a link's fragment are
ignored. Android backup and device transfer leave out SharedPreferences and
`monochrome_prefs` (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`),
which hold every sign-in. `CleartextPolicyTest` and `LogRedactorTest` hold
these.

## A listener's Subsonic server is a device secret

Settings › Connections › Music server stores one Subsonic server
(`data/subsonic/`) in the DataStore key `subsonic_server`, deliberately not in
`SETTINGS_SYNC_KEYS`, like the Discord token. It holds a token and salt, never
the password, unless the server refuses tokens (API older than 1.13.0, or error
41/42), and then the password's hex. Keep that sign-in out of everything that
leaves the app or is written down: `PlaybackSource.Subsonic` holds only the
song id (the stream URL is built in `StreamResolver` at play time), and artwork
is a `subsonic-cover://` reference resolved only by the app's own Coil
(`SubsonicCoverMapper`). `SubsonicTest` checks that nothing mapped from a server
contains the token or the password.

## Tryptify Cloud

The paid music storage. The server is `server/cloud` (a Cloudflare Worker with
D1 and R2, PolyForm Noncommercial; see its README). The app side is
`data/cloud/` and Settings › Connections › Tryptify Cloud. Its address is
`BuildConfig.TRYPTIFY_CLOUD_URL`, overridable with `tryptifyCloud.url` in
`local.properties`. Like the Theme Store, it holds no secret: calls carry the
Supabase session.

The cloud is played by saving it as the Subsonic server, with a device
password from `POST /v1/subsonic-keys`, so everything above about that server
applies. `CloudTest` holds the app to the server's error codes and JSON.

## projectM is patched, and its presets are scanned

`app/src/main/cpp/projectm_patches/` holds fixed copies of projectM source
files, swapped into its targets by `projectm_patches.cmake` after
`add_subdirectory`. The submodule itself stays clean upstream 4.1.6, and the
build fails if projectM's version changes under the patches. The first fix is a
null read that crashed the app on the first frame of any preset sampling a
texture the app does not ship.

`KnownCrashPresets.kt` lists the bundled presets that still crash projectM with
those fixes applied. The app never loads them. It is generated by
`tools/preset_crash_scan/`, which runs every preset through the same projectM
under AddressSanitizer on a headless GLES context. Rerun it whenever projectM,
the patches or the preset pack change.

Several tests exist specifically to hold the UI invariants — `LightSchemesTest`,
`CustomSchemeTest`, `GlobeLandClipTest`, `SettingsSearchIndexTest`. They are the
guarantee, not a formality. If one fails, fix the code; do not loosen the
threshold.

## Fresh cloud container setup

A cloud session starts with no Android SDK, so even `compileDebugKotlin` fails
with "SDK location not found". This is everything a release APK needs:

```
export ANDROID_HOME=/root/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools && cd $ANDROID_HOME/cmdline-tools
curl -sSo t.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q t.zip && mv cmdline-tools latest && cd -
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" \
    "build-tools;36.0.0" "platform-tools" "ndk;29.0.14206865" "cmake;3.22.1"
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored
git submodule update --init --recursive --depth 1
```

The platform and build tools are enough for compiling and unit tests. The
NDK, CMake and submodules are needed for anything that assembles an APK. Keep
the versions in step with `compileSdk`, `ndkVersion` and the `cmake` block in
`app/build.gradle.kts`.

Release signing needs the keystore, which is never committed. The owner
uploads `tryptify-release.jks` and its password note into the session; copy
the `.jks` outside the repo and write a `keystore.properties` in the repo root
(gitignored) with `storeFile`, `storePassword`, `keyAlias=tryptify` and
`keyPassword`. Without that file `assembleRelease` does not fail: the release
build type is left with no signing config, so it writes an unsigned
`app-release-unsigned.apk` (the build file says the same), which will not
install. Check that the signing certificate's SHA-256 starts `70:96:05:04`
before handing an APK over. For Trypt Mania's servers, see the section above.

## Baseline profile

`:app` ships `androidx.profileinstaller`, but the only profiles it has to
install are the ones the AndroidX libraries ship — nothing describes this
app's own startup, nav host or list rows yet. `:baselineprofile` is the module
that records that. The only way to know which code is hot is to run it, so it
needs an emulator or a phone:

```
./gradlew :app:generateBaselineProfile
```

By default it starts two Gradle-managed AOSP emulators of its own (Pixel 6 on
API 34, and Pixel 5 on API 30 for the branches older Android versions take) and
merges what both record. `-Pbaselineprofile.device=connected` uses a plugged-in
phone instead.

The result lands in `app/src/release/generated/baselineProfiles/` and is meant
to be **committed**: it is an input to the release build, not an artifact, so a
release does not depend on someone having a phone plugged in. At 1.9.4 nothing
has been committed there yet. `.gitignore`'s text-file rule is anchored to the
repository root (`/*.txt`), so that path is not ignored and needs no negation;
an unanchored `*.txt` would make the profile vanish silently while the build
still succeeds.

Needs the submodules, like anything else that assembles an APK.

Two things to know before trusting it. The Baseline Profile Gradle Plugin
prints a warning that it was tested only against AGP 9.0.0-alpha01 while this
project is on 9.0.0 — the warning is left switched on deliberately rather than
silenced, because nobody has verified generation on this combination yet.
And regenerate the profile when startup or the first screens change shape: a
stale profile is not wrong, only progressively less useful.

## Sending files to the owner

The file upload limit is 30 MiB per file, so anything over 30 MB (the 1.9.4
release APK is about 94 MB) goes as **zip parts**, never as one file and never
left undelivered:

```
zip -q -0 Tryptify-<version>-release.zip app-release.apk   # -0: an APK is already compressed
split -b 29M -a 3 --numeric-suffixes=1 Tryptify-<version>-release.zip Tryptify-<version>-release.zip.
```

That gives `.zip.001`, `.zip.002`, … each under the limit. Prove the round trip
before sending: `cat` the parts back together and `cmp` the result against the
zip, `unzip -t` it, extract it and `cmp` the file against the original, and for
an APK check the signer again with `apksigner`. Send every part in one go, and
tell the owner to keep them in one folder and open the `.001` (ZArchiver or
MiXplorer on Android, 7-Zip on Windows), or `cat *.zip.0* > joined.zip`.

Do not use Info-ZIP's own split (`zip -s`, giving `.z01`, `.z02`, `.zip`). It
failed that round trip here — the rejoined archive was corrupt and the APK in it
was not the one built — and Android extractors handle it poorly anyway.

## Theme Store server

The Theme Store does not live in Supabase. Its API is
[`server/theme-store/index.ts`](server/theme-store/README.md), a single-file
Railway Function (Bun) in project humble-charm with its own Railway Postgres.
Sign-in stays Supabase: the app sends the session's access token, and the
server verifies it against the project's public JWKS, or asks Supabase Auth
when the token was signed with the shared secret. It holds no secret.

Railway does not deploy it from this repository. Change `index.ts`, run its
test (`bun run test.ts` against a local Postgres, see the README), then replace
the function's code with the whole file. `ThemeStoreWireTest` holds the app and
the server to the same theme parts, pack and picture size caps, and error codes.

## Tryptify Cloud server

The paid music storage's API is [`server/cloud/`](server/cloud/README.md): a
Cloudflare Worker with a D1 database, Creem for payments, Supabase sign-in.
PolyForm Noncommercial, unlike the rest of the repository. Its money rules
are in `src/rules.ts` and held by `test.ts` (`bun run test.ts`), including the
worst-case pricing check. Deploy with `npx wrangler deploy` after the tests
pass; secrets live only in the Worker.

## Tryptify Connect server

[`server/connect/`](server/connect/README.md): a listener's devices, what
plays on which, and moving it between them (like Spotify Connect). A
Cloudflare Worker with one Durable Object per account, on the WebSocket
Hibernation API so it sleeps between messages; Supabase sign-in, no secret.
Its README is the protocol the web player and the app both speak; the app's
side is `connect/` (`ConnectController`, which watches the player through
`PlaybackSignals` rather than holding a controller, and is connected only
while signed in and on screen or playing).
`bun run test.ts`, then `npm run dev` and `bun run e2e.ts` in Cloudflare's
own runtime. `DEV_TOKENS` is for `wrangler dev` only, never the deployed
Worker.

## Website

[`site/`](site/README.md) is the public site (app, Tryptify Cloud and its
pricing, Terms, Privacy, Refunds, Contact), static files on Cloudflare. Its
prices and grace periods repeat `server/cloud/src/rules.ts`: change both
together.

## Commits

Author as `tryptz`. No co-author trailers and no tool attribution in commit
messages, PR bodies, or code comments.

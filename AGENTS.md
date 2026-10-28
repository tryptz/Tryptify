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
widths, ReplayGain's stage in front of the mixer, the Oxford effects and every
snapin (`replay_gain_test`), the mixer's exactness (a flat mix and a fader
moved and back come out bit for bit, `engine_exactness_test`), the effects'
delay lines and buffers (`snapin_buffers_test`), the finite guards
(`finite_guard_test`, see below), the WSOLA shifter, the EBU R128 loudness meter (against EBU Tech
3341/3342's reference signals), the dynamic range meter (against signals whose
DR is known by hand), the spectrum waterfall's geometry and the knobs' music
links (`mod_route_test`, held to the same numbers as the Kotlin
`MusicLinkFollowerTest`). The same script runs the USB DAC bypass driver
against stubbed libusb (`usb/tests/`), which needs the `third_party/libusb`
submodule and is skipped without it:

```
app/src/main/cpp/dsp/tests/run_host_tests.sh
```

The phone's compiler is stricter about `-ffast-math` than the desktop's:
clang 21 (NDK r29) takes every float argument as finite and folds a plain bit
test for NaN away, which no host build does, so a guard can pass every host
test and still do nothing on the phone. `dsp/util/finite.h` hides the bits
behind an empty asm; use `dspIsFinite`/`dspAllFinite`/`dspZeroNonFinite` (or
the same barrier) for any finiteness check. `finite_guard_test` is also built
with the NDK and run under qemu for both ARM ABIs when the NDK and
`qemu-user-static` are present (`apt-get install qemu-user-static`);
otherwise that step says it skipped.

Two more native suites are CMake projects of their own, built and run on the
desktop the same way: the Atmos renderer's (`atmos/tests/`, eighteen tests; the
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
an empty language. What's New is no exception: from 1.9.51 on, every entry
(`ui/settings/WhatsNew.kt`) names its title and body rows (`titleRes`,
`bodyRes`), its section a row of `WhatsNew.sectionRes`, and the English
written in the entry must match the table's (`WhatsNewTest`). Recurring terms (tab names, settings paths) are fixed in
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

The rooms server moved to Cloudflare (`rooms/` in the servers repository,
see Servers below) at
`https://rooms.tryptify.com`, which a release build sets as
`tryptmania.roomsServer`. That address has no secret path, since the website's
party page talks to it as well; the old Railway server's address had one, and
it stays a secret like the chart server's. The old entry sat ahead of the new
one in the saved list and would still have been picked, so builds since take
it out once (`BuiltInServer.withoutOldRooms`, `old_rooms_server_removed`),
recognised by the shape older builds gave it: a rooms-only entry under a long
hex path on another host.

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

The other settings `local.properties` can override (`themeStore.url`,
`lastfm.apiKey`) are public and have committed defaults.
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

## A server's password is a device secret

A TrypT HiFi with `ACCESS_PASSWORD` set (trypt-hifi's `middleware.ts`) answers
401 without it. The password is given under the address in Settings ›
Connections › Add API (adding a listed server again with a password changes
it), and kept by `ApiServerAccess` in the DataStore key `api_server_passwords`,
by server URL, deliberately not in `SETTINGS_SYNC_KEYS`: the server list syncs,
its passwords do not. It goes out as `X-Access-Key` on every request and
stream to that server's origin only: the API client's network interceptor
(`NetworkModule`, per redirect hop) and the players' data sources
(`PlaybackService`, `SongPreview`). `ApiServerTest` holds the origin match.

## Tryptify Cloud

The paid music storage. The server is `cloud/` in tryptz/tryptify-servers (a
Cloudflare Worker with D1 and R2, PolyForm Noncommercial; see its README). The app side is
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

## Servers

The servers live in their own private repository,
[tryptz/tryptify-servers](https://github.com/tryptz/tryptify-servers), with
their history. They were `server/` here until 2026-10-27: this repository is
public, and the paid storage's billing code should not be. How each one is
tested and deployed is in that repository's AGENTS.md.

It is a separate checkout. Keep it next to this one, so each finds the other
by a relative path:

```
/home/user/Tryptify            this repository (the app)
/home/user/tryptify-servers    git clone https://github.com/tryptz/tryptify-servers
```

A cloud session starts with only this repository; add that one when the work
needs it:

- changing a server's code, or deploying one (`wrangler deploy`, Railway);
- changing what crosses the wire: the Connect protocol, the party and rooms
  messages, Cloud's JSON or error codes, the Theme Store's parts or size caps;
- rewriting `app/src/test/resources/server-contract/` (only its
  `tools/app-contract.ts` does that);
- checking how a server answers when an app bug may be on that side.

App-only work (UI, playback, DSP, strings) does not need it.

| Server | There | What it is | The app's side |
|---|---|---|---|
| Tryptify Cloud | `cloud/` | the paid music storage: a Cloudflare Worker with D1, R2, PayPal and Creem | `data/cloud/` |
| Tryptify Connect | `connect/` | a listener's devices, and moving playback between them | `connect/` |
| Rooms | `rooms/` | Trypt Mania's multiplayer rooms and the listening parties, at `rooms.tryptify.com` | `glyph/multiplayer/`, `party/` |
| Theme Store | `theme-store/` | a Railway Function with its own Postgres | `data/repository/ThemeStoreRepository.kt` |

The app's tests cannot see that repository, so the two that hold the app to a
server read `app/src/test/resources/server-contract/` instead: `cloud.json`
(every error code Cloud sends, and the request fields it reads) for
`CloudTest`, and `theme-store.json` (the pack parts and size caps) for
`ThemeStoreWireTest`. That repository's `tools/app-contract.ts` writes them;
never edit them by hand. After changing one of those values on a server, run
it against a checkout of this repository and commit the files here (`--check`
says whether they are current). `ConnectProtocolTest` follows Connect's
README, which is the protocol: change both together.

Never copy server source into this repository, for a test or anything else.

## Website

The public site (app, Tryptify Cloud and its pricing, Terms, Privacy,
Refunds, Contact, the web player, the party page), static files on
Cloudflare, lives in its own repository,
[tryptz/tryptify.com](https://github.com/tryptz/tryptify.com), with the tools
that build it (its eight languages, the changelog, the headphone list) and
its README. It was `site/` here until it moved out with its history.

What still ties the two together:

- Its prices and grace periods repeat `cloud/src/rules.ts` in
  tryptz/tryptify-servers: change both together.
- `/changelog` is built from `docs/releases/` here, by that repository's
  `tools/site_changelog/build.py` pointed at a full (not shallow) checkout of
  this one. Rebuild it after a release note changes.
- Its web player speaks the same protocols as the app: Connect
  (`connect/` in the servers repository), the listening parties (`rooms/`),
  TrypT HiFi, and
  the Supabase tables the app syncs (`favorite_tracks.source` and the rest).
  A change to one of those changes the repositories involved.
- Its AutoEQ try-out ports `AutoEqEngine`; `WebAutoEqFixtureTest` holds the
  engine to `app/src/test/resources/web-autoeq/`, a copy of that
  repository's `tests/` fixtures. Rewrite them, copy them over, port the
  change.

## Commits

Author as `tryptz` <216453278+tryptz@users.noreply.github.com> (GitHub's private address, never the
owner's real email). No co-author trailers and no tool attribution in commit
messages, PR bodies, or code comments.

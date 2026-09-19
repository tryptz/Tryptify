# Tryptify — notes for agents

`CLAUDE.md` is gitignored in this repo, so anything that has to survive a fresh
clone lives here or under `docs/`.

## Read before changing the UI

**[`docs/ui-invariants.md`](docs/ui-invariants.md)** — the current, accepted look,
written as invariants with the failure each one prevents.

Read it before touching glass, search bars, press feedback, themes, the globe's
land fill, or the Discord presence artwork. Every rule in it is a bug that was
already found and fixed; none of them are obvious from the code, which is exactly
why they kept getting undone. A slab drawn at a tenth opacity looks like a
deliberate choice. A haze pane under a button looks like an improvement. A search
bar laid out inline looks perfectly ordinary.

The build those rules describe is commit `5ec5b072`. If something looks wrong and
you are not sure whether it changed, diff against it:

```
git diff 5ec5b072 -- app/src/main/java/tf/monochrome/android/ui
```

## Route the work before starting

**[`docs/agent-playbook.md`](docs/agent-playbook.md)** — per-area playbooks, each
with a workflow and completion gates: Glass Surfaces, Realtime Audio, Playback
Routing, Radio Ranking, and the engineering gates for build upgrades, shrinking
and intent security.

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

Several tests exist specifically to hold the invariants above — `LightSchemesTest`,
`CustomSchemeTest`, `GlobeLandClipTest`, `SettingsSearchIndexTest`. They are the
guarantee, not a formality. If one fails, fix the code; do not loosen the
threshold.

## Baseline profile

`:app` ships `androidx.profileinstaller`, and until now the only profiles it had
to install were the ones the AndroidX libraries ship — nothing described this
app's own startup, nav host or list rows. `:baselineprofile` is the module that
records that. It needs a **connected device or emulator**, because the only way
to know which code is hot is to run it:

```
./gradlew :app:generateBaselineProfile
```

The result lands in `app/src/release/generated/baselineProfiles/` and is
**committed** — it is an input to the release build, not an artifact, so a
release does not depend on someone having a phone plugged in. `.gitignore` has
a blanket `*.txt`, so there is an explicit negation for that path; if you move
the output, move the negation with it or the profile will vanish silently and
the build will still succeed.

Needs the submodules, like anything else that assembles an APK.

Two things to know before trusting it. The Baseline Profile Gradle Plugin
prints a warning that it was tested only against AGP 9.0.0-alpha01 while this
project is on 9.0.0 — the warning is left switched on deliberately rather than
silenced, because nobody has verified generation on this combination yet.
And regenerate the profile when startup or the first screens change shape: a
stale profile is not wrong, only progressively less useful.

## Commits

Author as `tryptz`. No co-author trailers and no tool attribution in commit
messages, PR bodies, or code comments.

## Releases

`.github/workflows/release.yml` is the only workflow that ships anything. It
builds the release variant — minified, never a debug APK — and, when it can sign
it, publishes a GitHub Release with the APK, its SHA-256, and the notes for that
version out of `CHANGELOG.md`.

To cut one:

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts` and add the
   matching `## [x.y.z]` section to `CHANGELOG.md`.
2. Tag the commit with the bare version (`1.8.8`, matching the existing tags; a
   `v` prefix is accepted) and push the tag.

The workflow refuses to publish an APK that disagrees with the tag, that came out
unminified, that is marked debuggable, or that is signed with the committed debug
keystore. A tag suffix (`1.9.0-rc1`) publishes as a pre-release.

Signing comes from four repository secrets — `KEYSTORE_BASE64`,
`KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `KEYSTORE_KEY_PASSWORD` — which
the workflow writes into `keystore.properties` and deletes afterwards.

**Those secrets are not set on this repository.** Until they are, the workflow
does not fail — it builds, shrinks and verifies the release APK, uploads it as a
workflow artifact, prints the four secrets to add, and publishes nothing. That is
deliberate: an earlier version of this workflow hard-failed on the missing
keystore, so tagging 1.8.7 hung a red check off main, and it was deleted for it.

A `workflow_dispatch` run with no tag is the same thing on purpose, and it is the
**R8 gate**: `isMinifyEnabled = true` has been set for the release build type far
longer than anything has built that variant, so a dry run is how the shrinker and
the keep rules in `app/proguard-rules.pro` get exercised. Reach for it before
believing a release build works, and read the mapping artifact it uploads.

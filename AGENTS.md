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

## Commits

Author as `tryptz`. No co-author trailers and no tool attribution in commit
messages, PR bodies, or code comments.

## Releases

`.github/workflows/release.yml` is the only workflow that ships anything. It
builds the release variant — signed, minified, never a debug APK — and publishes
a GitHub Release with the APK, its SHA-256, and the notes for that version out of
`CHANGELOG.md`.

To cut one:

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts` and add the
   matching `## [x.y.z]` section to `CHANGELOG.md`.
2. Tag the commit with the bare version (`1.8.8`, matching the existing tags; a
   `v` prefix is accepted) and push the tag.

The workflow refuses to publish an APK that disagrees with the tag, that came out
unsigned, that is marked debuggable, or that is signed with the committed debug
keystore. A `workflow_dispatch` run with no tag is a dry run: it builds, signs and
verifies, then uploads the APK as a workflow artifact without publishing.

Signing comes from four repository secrets — `KEYSTORE_BASE64`,
`KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `KEYSTORE_KEY_PASSWORD` — which
the workflow writes into `keystore.properties` and deletes afterwards. A tag
suffix (`1.9.0-rc1`) publishes as a pre-release.

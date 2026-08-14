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

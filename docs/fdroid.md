# Getting Tryptify onto F-Droid

F-Droid builds every app itself, from source, on its own server, and signs it
with its own key. Their quick start guide is
<https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/>.
This page is what that means for Tryptify: what is already done in this
repository, how the FFmpeg decoder gets built from source, the metadata to
send them, and what is still open.

## Done in this repository

- **No Google libraries.** Cast (`play-services-cast-framework`,
  `media3-cast`) and Google Sign-In (`credentials-play-services-auth`,
  `googleid`, `credentials`) were declared but never used, and are gone.
  The `com.google.android.gms.car.application` tag in the manifest stays:
  it is Android Auto's meta-data, not a library.
- **Store text** in `fastlane/metadata/android/en-US/`: title, short and full
  description, screenshots and `changelogs/<versionCode>.txt`. F-Droid reads
  this folder from the tagged commit. There is no `images/icon.png`: the
  launcher icon is an adaptive vector, and F-Droid takes the icon from the
  APK when the file is missing.
- **FFmpeg from source**, as an opt-in switch (below). Builds without the
  switch are unchanged.

## FFmpeg from source

The FFmpeg renderer (`NextRenderersFactory`, in `PlaybackService` and
`CrossfadeController`) comes from nextlib's `media3ext` on Maven Central,
which carries prebuilt `.so` files. F-Droid does not take prebuilt native
code, and the app cannot drop FFmpeg: ALAC (handed to FFmpeg on purpose),
E-AC-3/Atmos, AC-3, DTS and TrueHD all play through it.

nextlib is open source (Apache-2.0) and its tree builds FFmpeg itself:
`media3ext`'s `preBuild` runs `ffmpeg/setup.sh`, which downloads FFmpeg 6.0,
libvpx 1.13.0 and mbedtls 3.4.1 as source and compiles them with the NDK for
each ABI. So the from-source build is nextlib's own build at the tag the app
uses, `v0.8.4`, giving the same decoders as the Maven AAR.

The app takes that AAR with one property, either `-Pnextlib.aar=<path>` or
`nextlib.aar=<path>` in `local.properties` (`app/build.gradle.kts`, next to
the dependency). With it set, the Maven artifact is not used at all. The AAR's
only dependency is `media3-exoplayer`, which the app already has, at the same
version (1.5.1).

By hand:

```
git clone --branch v0.8.4 https://github.com/anilbeesetti/nextlib
cd nextlib
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :media3ext:assembleRelease      # needs NDK 25.2.9519653, CMake 3.22.1, pkg-config
cd ../Tryptify
./gradlew :app:assembleRelease -Pnextlib.aar=$PWD/../nextlib/media3ext/build/outputs/aar/media3ext-release.aar
```

Building FFmpeg for four ABIs takes a while. Not yet run end to end:
nobody has built nextlib from source, built the app with `nextlib.aar`, and
played an ALAC or E-AC-3 file through the result. Do that once before
sending the merge request. Next Player
(`dev.anilbeesetti.nextplayer`, by nextlib's author) is already on F-Droid
with the same library, which is a useful precedent to point reviewers at.

## Metadata for fdroiddata

Two files go in a fork of <https://gitlab.com/fdroid/fdroiddata>, on a
branch named `tf.monotrypt.android`, and the merge request is titled
`New App: tf.monotrypt.android`. Run `fdroid rewritemeta`, `fdroid lint` and
`fdroid build` on them first (or let the fork's CI do it). Both are drafts
that nobody has run through `fdroid build` yet.

`srclibs/nextlib.yml`:

```yaml
RepoType: git
Repo: https://github.com/anilbeesetti/nextlib.git
```

`metadata/tf.monotrypt.android.yml`:

```yaml
Categories:
  - Multimedia
License: GPL-3.0-only
AuthorName: tryptz
WebSite: https://tryptify.com
SourceCode: https://github.com/tryptz/Tryptify
IssueTracker: https://github.com/tryptz/Tryptify/issues
Changelog: https://github.com/tryptz/Tryptify/blob/HEAD/CHANGELOG.md

AutoName: Tryptify

RepoType: git
Repo: https://github.com/tryptz/Tryptify.git

Builds:
  - versionName: 1.9.51
    versionCode: 196
    commit: v1.9.51
    subdir: app
    submodules: true
    sudo:
      - apt-get update
      - apt-get install -y pkg-config
    gradle:
      - yes
    srclibs:
      - nextlib@v0.8.4
    rm:
      - models
    prebuild:
      - echo "sdk.dir=$$SDK$$" > $$nextlib$$/local.properties
    build:
      - pushd $$nextlib$$ && ./gradlew :media3ext:assembleRelease && popd
    gradleprops:
      - nextlib.aar=$$nextlib$$/media3ext/build/outputs/aar/media3ext-release.aar
    ndk:
      - r25c
      - r29

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.9.51
CurrentVersionCode: 196
```

Notes on it:

- `License`: the `LICENSE` file is GPL-3.0's text and does not say "or
  later". Change it to `GPL-3.0-or-later` if that is what is meant.
- `rm: models` deletes the Git LFS ONNX model. The app build never reads it,
  and F-Droid's clone would only have the LFS pointer anyway.
- `ndk`: nextlib pins NDK r25c (25.2.9519653) and the app r29
  (29.0.14206865). fdroidserver can install several; check `fdroid lint`
  accepts the list.
- `AntiFeatures` is left for the reviewers to fill in (see below).
- Auto-update works because `versionCode` and `versionName` are plain
  literals in `app/build.gradle.kts`. Each release then needs a `v<version>`
  tag on its commit, and a `changelogs/<versionCode>.txt`.

## Still open

- **Tags.** F-Droid finds releases by tag (`v1.9.51`). The tag must sit on a
  commit that has the changes above, so tag after this work is merged.
- **Anti-features reviewers will likely add**: NonFreeNet for the services
  whose servers are not open source (Supabase sign-in and sync, Tryptify
  Cloud, Connect, the Theme Store, the rooms server, Last.fm). All of them
  are optional, but the label is about the app talking to them at all.
- **Things reviewers may question**: Discord Rich Presence signs in with the
  listener's own account token, which Discord's terms forbid; the TIDAL,
  Qobuz and Deezer sources through HiFi API servers; and the MilkDrop
  presets, which have no licence and are assumed to be public domain
  (`app/src/main/projectm-assets/presets/LICENSE.md`).
- **Reproducible builds** are optional. Without them F-Droid's APK is signed
  with F-Droid's key, so it cannot update over a GitHub release and the other
  way round: a listener moving between the two reinstalls.

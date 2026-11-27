# Third-party notices

Contour is licensed under the Apache License, Version 2.0 (see `LICENSE`). This file lists everything
Contour uses that someone else made: the libraries inside the app, code and knowledge it builds on, and the
tools used to build it. The app shows this file, `NOTICE` and `LICENSE` on its About & licences screen (at the
foot of the Library, and in the service menu).

Summary:
- Every library inside the app is licensed under the Apache License 2.0.
- The only code from other projects is a port of the WalkPlay protocol and coefficient maths from
  devicePEQ, licensed under 0BSD, and the TRN Black Pearl write sequence from FreeDSP-Studio, licensed under
  the MIT License (both in section 2).
- The app bundles one font made by others, Geom (SIL Open Font License 1.1, section 3); no other fonts,
  images, sounds or EQ settings made by others.

## 1. Libraries inside the app (all Apache License 2.0)

The app ships these libraries, shrunk by R8. The Apache License 2.0 text is in `LICENSE`.

Kotlin and JetBrains, (c) JetBrains s.r.o. and contributors:
- org.jetbrains.kotlin:kotlin-stdlib 2.4.20
- org.jetbrains.kotlinx:kotlinx-coroutines-core, kotlinx-coroutines-android 1.9.0
- org.jetbrains.kotlinx:kotlinx-serialization-core, kotlinx-serialization-json 1.11.0
- org.jetbrains:annotations 23.0.0

Android Jetpack (AndroidX), (c) The Android Open Source Project:
- Compose BOM 2026.09.00: androidx.compose.ui (ui, ui-geometry, ui-graphics, ui-text, ui-unit, ui-util) 1.12.1;
  androidx.compose.foundation (foundation, foundation-layout) 1.12.1; androidx.compose.animation (animation,
  animation-core) 1.12.1; androidx.compose.runtime (runtime, runtime-annotation, runtime-retain,
  runtime-saveable) 1.12.1; androidx.compose.material3:material3 1.4.0;
  androidx.compose.material:material-ripple 1.12.1
- androidx.compose.material:material-icons-core, material-icons-extended 1.7.8 (Material Icons by Google,
  the icons in the profile icon picker and the + and chevron icons)
- androidx.activity (activity, activity-ktx, activity-compose) 1.13.0
- androidx.lifecycle (common, common-java8, runtime, runtime-ktx, runtime-compose, livedata-core, process,
  viewmodel, viewmodel-ktx, viewmodel-savedstate) 2.11.0
- androidx.savedstate (savedstate, savedstate-ktx, savedstate-compose) 1.4.0
- androidx.navigationevent (navigationevent, navigationevent-compose) 1.0.0
- androidx.core (core, core-ktx) 1.19.0, androidx.core:core-viewtree 1.0.0
- androidx.arch.core (core-common, core-runtime) 2.2.0
- androidx.annotation:annotation 1.10.0, androidx.annotation:annotation-experimental 1.4.1
- androidx.collection (collection, collection-ktx) 1.5.0
- androidx.autofill:autofill 1.0.0
- androidx.concurrent:concurrent-futures 1.1.0
- androidx.customview:customview-poolingcontainer 1.0.0
- androidx.emoji2:emoji2 1.4.0
- androidx.graphics:graphics-path 1.0.1
- androidx.interpolator:interpolator 1.0.0
- androidx.profileinstaller:profileinstaller 1.4.0
- androidx.startup:startup-runtime 1.1.1
- androidx.tracing:tracing 1.2.0
- androidx.versionedparcelable:versionedparcelable 1.1.1
- androidx.window (window, window-core) 1.5.0

Other:
- com.google.guava:listenablefuture 1.0 (an empty placeholder artifact), (c) Google
- org.jspecify:jspecify 1.0.0, (c) the JSpecify authors

## 2. Code and knowledge Contour builds on

devicePEQ, https://github.com/jeromeof/devicePEQ, commit 0617f382e76629792a5933e6933e4b396a756a93.
The WalkPlay HID PEQ protocol and the biquad coefficient maths in
`android/core/src/main/kotlin/io/github/chronosauros/contour/core/WalkPlay.kt` and `research/protocol/walkplay.py`
are ports of devicePEQ's `walkplayHidHandler.js`. `research/protocol/devicepeq-ref/` keeps verbatim reference
copies of four devicePEQ files. devicePEQ is licensed under 0BSD:

    Copyright 2024 Jerome O'Flaherty (jerome.oflaherty@icloud.com)

    Permission to use, copy, modify, and/or distribute this software for any
    purpose with or without fee is hereby granted.

    THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
    REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
    AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
    INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
    LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
    OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
    PERFORMANCE OF THIS SOFTWARE.

The protocol was then checked on a real CrinEar Protocol Micro (captures in `research/protocol/`). It was not
taken from any vendor tool or SDK.

FreeDSP-Studio, https://github.com/EffectiveEquivalent/FreeDSP-Studio, commit 1b42f63. What was taken: the
write sequence and pacing of the TRN Black Pearl (WalkPlay scheme 16) from `write_bank` and its helpers in
`tools/app/src/dsp2.rs` - the band frame with length byte 0x21, 25 ms after each band, the coefficient commit,
the preamp, the two flash commands and their pauses. It is used in
`android/core/src/main/kotlin/io/github/chronosauros/contour/core/WalkPlay.kt` (`trnWriteSequence`) and was then
checked on a real TRN Black Pearl (`research/black-pearl-pc-2026-10-19/FINDINGS.md`). FreeDSP-Studio is licensed
under the MIT License:

    MIT License

    Copyright (c) 2026 EffectiveEquivalent

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

Nothing was taken from BlackPearlControl-Android (GPL).

Formulas and formats, no code copied:
- Audio EQ Cookbook by Robert Bristow-Johnson: the biquad formulas for the response graph.
- The Equalizer APO / AutoEQ parametric text format: Contour reads and writes it with its own parser.
- squig.link: Contour shows the preamp the same way, as a reference for users who know it.

## 3. Fonts, icons and images

- Fonts: Geom 500, (c) 2022 The Geom Project Authors (https://github.com/ThanosPoulakidas/Geom), SIL Open
  Font License 1.1 (`OFL-Geom.txt`, shipped next to this file): the "d" and "b" around the Soundblooms face on the
  Google Play beta invitation (`android/app/src/main/res/font/geom_500.ttf`, the sfnt of the website's
  `docs/shared/fonts/geom-500.woff`). Everything else: on Pixel phones the app asks the system for its Google Sans font by name; elsewhere it
  uses the system sans-serif. Google Sans is not part of this project and is not distributed with it.
- Icons: Material Icons, from the AndroidX libraries in section 1 (Apache License 2.0).
- Example profiles created on first launch: general settings written for this project, part of it under the
  Apache License 2.0.
- The launcher icon and all other graphics are drawn in code or as vector resources in this repo, under the
  project's Apache License 2.0.

## 4. Build and test tools (not inside the app)

- Gradle wrapper 9.7.1 (`android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar`,
  kept in this repo), (c) the original authors, Apache License 2.0
- Android Gradle Plugin 9.4.1 with D8 and R8, (c) Google, Apache License 2.0
- Kotlin Gradle plugin, Compose compiler plugin and serialization plugin 2.4.20, (c) JetBrains, Apache License 2.0
- kotlin-test 2.4.20 (Apache License 2.0), JUnit Jupiter 5.10.1 and JUnit Platform 1.10.1 (Eclipse Public
  License 2.0), opentest4j 1.3.0 and apiguardian-api 1.1.2 (Apache License 2.0): used by the parity test only,
  downloaded by Gradle, not distributed
- JDK 21 and the Android SDK: needed to build, not distributed

## 5. The website (`docs/`, not inside the app)

The project website at https://contoureq.app/ ships these files made by others:
- cube-motion 0.1.0, https://github.com/Danilaa1/cube-motion, (c) 2026 Daniel Belyi, MIT License: the page
  motion (rise, leave, morph, reveal), merged into one script as `docs/shared/cube-motion.js`, licence text in
  `docs/shared/cube-motion.LICENSE.txt`.
- Google Sans Flex (latin subset), (c) Google, SIL Open Font License 1.1: the web font in `docs/shared/fonts/`
  and `docs/install/fonts/`, licence text in `OFL.txt` next to it.

The live EQ panel (`docs/shared/contour-dsp.js`, `contour-tune.js`) is this project's own code, a port of the
app's response maths, under the Apache License 2.0.

## 6. Trademarks

Google Play and the Google Play logo are trademarks of Google LLC. CrinEar, Protocol Micro, WalkPlay, Moondrop, Google, Pixel, Google Sans, Android and all other product names belong to
their owners. Contour is an unofficial project and is not affiliated with or endorsed by any of them. Names are
used only to say which hardware and formats Contour works with.

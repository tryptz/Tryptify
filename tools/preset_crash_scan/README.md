# Preset crash scan

Loads every bundled MilkDrop preset into projectM, renders a few frames with
synthetic audio, and lists the presets that kill the process.
`app/src/main/java/tf/monochrome/android/visualizer/KnownCrashPresets.kt` is
generated from its output, and the app never loads a preset on that list.

## Why it exists

projectM survives a preset that fails to *load*. Any exception it throws while
loading is caught, and the playlist moves on. It does not survive a null read,
a memory error or an exception during *rendering*. Any of those takes the app
down with it, from the visualizer's render thread, with nothing to catch.

The first run found presets that crashed on their first frame. Each one sampled
a texture the app does not ship, and projectM 4.1.6 dereferenced the resulting
empty descriptor. That bug is fixed in
`app/src/main/cpp/projectm_patches/`. This scan builds with those patches, so
the list holds only what still crashes after them.

## What it runs

- projectM 4.1.6 from `third_party/projectm`, built the way the app builds it:
  - the GLES renderer and the vendored projectM-eval;
  - the patched sources from `projectm_patches/`, swapped in by the same
    `projectm_patches.cmake` the APK uses;
  - AddressSanitizer on all of it.
- A headless OpenGL ES 3.0 context on Mesa's EGL (llvmpipe).
- The app's defaults: 32×24 mesh, 60 fps, aspect correction on, and no
  textures.
- Each preset loaded with a hard cut, then 16 frames rendered with a kick, a
  sweep and noise as the audio.

A preset is flagged if:
- the process dies from a signal;
- ASan reports a memory error;
- an exception escapes;
- or one preset takes more than 30 s to load and render its frames (a hang).

## Running it

Needs `cmake`, `ninja`, `g++` with ASan, and Mesa's EGL/GLES (`libegl-dev
libgles-dev` on Ubuntu). It also needs the submodules
(`git submodule update --init --recursive`).

```
cmake -S tools/preset_crash_scan -B /tmp/pcs -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo
ninja -C /tmp/pcs harness
python3 tools/preset_crash_scan/scan.py /tmp/pcs --out /tmp/pcs-out
```

The full pack (9,795 presets) takes about 45 minutes on four cores. The last
thing printed is the Kotlin set to paste into `KnownCrashPresets.kt`.

## What it cannot find

It cannot find a preset that only crashes one phone's GPU driver: llvmpipe is
not an Adreno or a Mali. On the device, `PresetCrashGuard` covers that:
- The bridge names each preset in a sentinel file before loading it.
- A clean shutdown deletes the file.
- If a process dies with the file still there, the next process flags that
  preset on that device.
- On Android 11 and later, it only does so when the system's exit record says
  the death was a native crash or an ANR (app not responding).

Settings › Visual Studio › Crash-flagged presets clears those device flags.

Rerun the scan when projectM, its patches or the preset pack change.

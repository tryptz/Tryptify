# libarchive (RAR readers only)

A subset of [libarchive](https://github.com/libarchive/libarchive) 3.8.4,
copied unchanged from its release tarball: the read core and the RAR and RAR5
format readers, with what they link against. Trypt Mania uses it to import
StepMania themes, noteskins, announcers and characters shipped as `.rar`
(`app/src/main/cpp/archive/`). ZIP and 7z are read in Kotlin.

libarchive's RAR readers are its own BSD-licensed implementations, not
derived from unRAR, so they can ship in a GPL app; unRAR's licence and the
decoders built on it (junrar) cannot. Licences: `COPYING` (BSD-2-Clause; the
BLAKE2 files are CC0).

`config/android.h` and `config/linux_host.h` are libarchive's own
`contrib/android/config` headers, and `include/android_lf.h` its
`contrib/android/include` one, which `archive.h` includes on Android;
`config/config.h` picks one of the two configurations.

To update, copy the same files from a newer release and run the host test
(`app/src/main/cpp/archive/tests/`). The file list is in
`app/src/main/cpp/CMakeLists.txt`.

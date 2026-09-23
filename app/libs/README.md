# Vendored libraries

## `spotify-app-remote-release-0.8.0.aar`

Spotify App Remote SDK for Android, copied unmodified from
[`spotify/android-sdk`](https://github.com/spotify/android-sdk/tree/master/app-remote-lib)
(`app-remote-lib/spotify-app-remote-release-0.8.0.aar`). Licensed Apache-2.0 —
see `SPOTIFY-ANDROID-SDK-LICENSE` next to this file.

It is vendored because Spotify does not publish it to Maven Central or Google's
Maven. Its manifest already declares the `<queries>` entries for the Spotify
app packages, so nothing extra is needed in `AndroidManifest.xml`.

To upgrade: replace the file, bump the file name in `app/build.gradle.kts`, and
read the SDK's `CHANGELOG.md` for connection or auth changes.

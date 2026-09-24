import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Wrapper around the Spotify app: the vendored App Remote SDK talks to the
// installed com.spotify.music package (pulled from this phone), while this
// module owns the silent-shadow playback trick — ExoPlayer plays a generated
// silent WAV of the song's length and SpotifyPlaybackBridge mirrors all
// transport state onto the Spotify app, so the queue, notification, scrubber
// and end-of-track advance stay in Tryptify's hands.

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "tf.monochrome.android.data.spotify"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Same defaults as the app's BuildConfig — the client id and redirect
        // are registered in the Spotify Developer Dashboard against this
        // package name + signing SHA-1.
        buildConfigField("String", "SPOTIFY_CLIENT_ID",
            "\"${System.getenv("SPOTIFY_CLIENT_ID") ?: "c9e571c8b81948feb6573014a3efdd2c"}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"tryptify://spotify-callback\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Full runtime dependency (not compileOnly): the bridge/shadow classes
    // dereference SDK types (PlayerState etc.) at runtime, so the SDK classes
    // must ship inside the APK. AGP only forbids local .aar deps when the
    // consuming module itself produces an .aar; this module is an APK-bound
    // library, so a plain files() dep is allowed.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))

    // Native Spotify audio: librespot is the open Spotify client. The player
    // module streams decrypted audio into a sink we feed to ExoPlayer, so
    // Spotify tracks play inside Tryptify through the normal DSP chain —
    // no Spotify app on the device, no silent-shadow mirroring.
    //
    // The Maven artifact is a fat jar that bundles (shades) libs the app
    // already carries — night-config, error-prone annotations, gson,
    // protobuf, okhttp, okio, slf4j/log4j, kotlin-stdlib, plus jcraft/disruptor
    // — and Maven excludes can't strip shaded classes out of the jar itself.
    // libs/librespot-player-stripped-1.6.5.jar is that artifact with every
    // bundled package removed except librespot's own (xyz.gianlu.librespot,
    // com.spotify protobuf messages, zeroconf); the real dependencies come in
    // via the artifact's normal POM. Regenerate with: unzip, prune, jar cf.
    api(files("libs/librespot-player-stripped-1.6.5.jar"))
    // The jar also has xyz/gianlu/librespot/core/TokenProvider*.class removed:
    // 1.6.5's TokenProvider fetches tokens from Spotify's retired keymaster
    // endpoint (403), which failed every login. The replacement, using login5,
    // is src/main/java/xyz/gianlu/librespot/core/TokenProvider.java. When
    // regenerating the jar, delete those three classes again.
    //
    // Likewise xyz/gianlu/librespot/core/ApResolver.class: 1.6.5's picks one
    // access point at random and Session tries only that one, so a :80 entry
    // on a network that refuses Spotify's protocol on port 80 failed every
    // sign-in. The replacement, src/main/java/xyz/gianlu/librespot/core/
    // ApResolver.java, returns the first reachable one, preferring 4070/443.
    //
    // And xyz/gianlu/librespot/dealer/ApiClient*.class (ApiClient, ApiClient$1,
    // ApiClient$StatusCodeException): 1.6.5 reads track metadata from
    // /metadata/4/track, which since November 2025 returns tracks with no
    // playable files, so every track failed with "no alternatives found".
    // src/main/java/xyz/gianlu/librespot/dealer/ApiClient.java is 1.6.5's
    // class with upstream's extended-metadata fix (52a8c24) ported in.

    // A files() jar carries no POM, so "the real dependencies" above are
    // declared here by hand, at the versions librespot 1.6.5's own POMs pin
    // (librespot-java parent + librespot-lib). Without them the first class
    // librespot loads — every one logs through slf4j, the wire format is
    // protobuf, and Vorbis decoding is jorbis — is a NoClassDefFoundError.
    //
    // Left out on purpose, because only code this app never runs uses them:
    // log4j and night-config (librespot's CLI Main and its TOML
    // FileConfiguration) and zeroconf (ZeroconfServer). consumer-rules.pro
    // tells R8 they are missing on purpose.
    api("com.google.protobuf:protobuf-java:3.25.2")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.jcraft:jorbis:0.0.17")
    implementation("com.badlogicgames.jlayer:jlayer:1.0.2-gdx")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("commons-net:commons-net:3.11.1")
    implementation(libs.gson)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(libs.hilt.android)
    implementation(libs.media3.exoplayer)

    testImplementation(libs.junit)
}

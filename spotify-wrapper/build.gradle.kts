import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Spotify transport module. Native librespot PCM is the only playback route
// compiled into the app.

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "tf.monochrome.android.data.spotify"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    // bundled package removed except librespot's own (xyz.gianlu.librespot and
    // com.spotify protobuf messages). A local files() dependency has no POM,
    // so the upstream 1.6.5 dependencies are declared explicitly below.
    // Regenerate with: unzip, prune, jar cf.
    api(files("libs/librespot-player-stripped-1.6.5.jar"))

    // The stripped jar keeps librespot and Spotify's generated protobufs but
    // deliberately removes third-party packages that the app already uses or
    // can supply once. A files() dependency has no POM, so declare those
    // runtime pieces explicitly.
    implementation("com.google.protobuf:protobuf-java:3.25.2")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("commons-net:commons-net:3.11.1")
    implementation("org.jcraft:jorbis:0.0.17")
    implementation("com.badlogicgames.jlayer:jlayer:1.0.2-gdx")
    implementation("xyz.gianlu.zeroconf:zeroconf:1.3.2")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("com.electronwill.night-config:toml:3.6.7")
    implementation("org.apache.logging.log4j:log4j-api:2.24.3")
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")
    runtimeOnly("com.lmax:disruptor:3.4.4")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(libs.hilt.android)
    implementation(libs.media3.exoplayer)

    testImplementation(libs.junit)
}

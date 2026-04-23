import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use(localProperties::load)
}



val hasCompleteReleaseSigning = requiredSigningKeys.all { key ->
    !keystoreProperties.getProperty(key).isNullOrBlank()
}

// Release signing is opt-in — if keystore.properties is absent, `assembleRelease`
// falls back to unsigned output and every other task (assembleDebug, lint, tests,
// installDebug) still works because debug builds use the committed
// app/monotrypt-debug.keystore. The `--info` log keeps the signal available when
// someone does want to know but no longer shouts from every debug CI run.
if (!hasCompleteReleaseSigning) {
    logger.info(
        "Release signing disabled: missing or incomplete keystore.properties at {}",
        keystorePropertiesFile.path,
    )
}

android {
    namespace = "tf.monochrome.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "tf.monotrypt.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    signingConfigs {
        // Project-local debug keystore — committed so every machine and every
        // `installDebug` signs with the same key. Without this, a fresh checkout
        // or a CI build would use ~/.android/debug.keystore, whose key varies
        // across machines, and the device would reject the install as a
        // signature-mismatch update (forcing an uninstall/reinstall).
        getByName("debug") {
            storeFile = file("monotrypt-debug.keystore")
            storePassword = "monotrypt"
            keyAlias = "monotrypt-debug"
            keyPassword = "monotrypt"
        }
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Always the committed project-local debug keystore. Same signature
            // on every machine and every CI run — so `./gradlew installDebug`
            // over a CI-produced APK (or vice versa) upgrades in place.
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)

    // Navigation
    implementation(libs.navigation.compose)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.cast)
    // Prebuilt FFmpeg audio decoder wired as FfmpegAudioRenderer. Drops in
    // support for DSD/DSF, APE, TAK, WavPack, Musepack, TrueHD/MLP, DTS, AC-3,
    // and a long tail of codecs the platform MediaCodec can't handle.
    implementation(libs.nextlib.media3ext)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime)

    // Kotlin
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)

    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.compose.auth)
    implementation(libs.supabase.compose.auth.ui)
    implementation(libs.supabase.postgrest)

    // Glance Widgets
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Palette
    implementation(libs.palette)

    // Cast
    implementation(libs.cast.framework)

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.browser)

    // Media (Android Auto)
    implementation(libs.media)

    // Security (Keystore for collection encryption keys)
    implementation(libs.security.crypto)

    // Google Sign-In (Credential Manager)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Haze (Glassmorphism blur)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // BlurView v3 (real backdrop blur, Compose-safe on API 31+)
    implementation(libs.blurview)
}

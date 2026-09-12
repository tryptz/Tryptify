plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "tf.monochrome.android.baselineprofile"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        // Macrobenchmark needs 28; the app itself still ships to 26, which is
        // fine — the profile generated here applies to every device that can
        // use one, and devices below 28 simply do not install it.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The module under test. The plugin drives this app, records the classes
    // and methods it touches, and writes the profile back into :app.
    targetProjectPath = ":app"
}

baselineProfile {
    // A real device or emulator, attached over adb. There is no way to record
    // a profile without running the app: the whole point is a trace of what it
    // actually executes.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

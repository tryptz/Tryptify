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

    // An emulator this build can start for itself, so recording a profile does
    // not mean running the app flat out on a phone for several minutes. That is
    // real work — five launches, three pages, a scroll and a search each — and
    // on a handset it is minutes of sustained load with the screen on.
    //
    // `aosp`, not `google`: the generator wants root, and the Play images are
    // not rootable. Macrobenchmark 1.4 can do without root on API 33 and above,
    // but an AOSP image costs nothing here and keeps the option open.
    //
    // API 34 rather than the app's target 36: profiles are portable across API
    // levels — they name classes and methods, not platform behaviour — and 34
    // is the image most likely to already be on a machine.
    testOptions.managedDevices.allDevices {
        create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
        // A second, older device, for the branches the first one never takes.
        //
        // A profile only contains code the app was *seen* executing, and this
        // app gates a great deal on the platform version: nineteen checks
        // against TIRAMISU alone — the whole liquid-glass path, which needs
        // RuntimeShader from API 33 — plus five against S and two against R.
        // Recorded only on API 34, every one of those resolves to the modern
        // branch and the fallbacks a phone on API 26-32 actually runs are
        // absent from the profile, which is precisely the hardware that can
        // least afford to JIT them.
        //
        // API 30 is below R, S and TIRAMISU, so one pass here covers all three
        // fallbacks. It does not go below Q (29), where fourteen more checks
        // sit; API 28 would catch those too, at the cost of an older and
        // flakier image — and 28 is the floor anyway, since Macrobenchmark
        // itself needs it (see minSdk above).
        //
        // Pixel 5 rather than Pixel 6: the 6 shipped on API 31, and pairing a
        // device profile with an image older than the hardware is asking for
        // an emulator combination nobody tests.
        create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel5Api30") {
            device = "Pixel 5"
            apiLevel = 30
            systemImageSource = "aosp"
        }
    }
}

baselineProfile {
    // Recording needs the app actually running: the profile is a trace of what
    // it executed, and there is no way to derive that statically.
    //
    // Where it runs is a choice. By default the emulator above, on whatever
    // machine is running Gradle. A phone is available with
    //
    //     ./gradlew :app:generateReleaseBaselineProfile -Pbaselineprofile.device=connected
    //
    // for when there is no emulator to be had — but it is the slower and hotter
    // of the two, and nothing about the profile is better for having come from
    // real hardware.
    if (providers.gradleProperty("baselineprofile.device").orNull == "connected") {
        useConnectedDevices = true
    } else {
        // Both devices, and the plugin merges what they record into the one
        // profile that ships. A baseline profile is not per-device — it names
        // classes and methods, and ART compiles them at install time on
        // whatever hardware installs the app — so this is about the *union of
        // code paths observed*, not about producing a file per phone.
        managedDevices += listOf("pixel6Api34", "pixel5Api30")
        useConnectedDevices = false
    }
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

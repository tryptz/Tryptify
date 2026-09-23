# Monochrome Android ProGuard Rules

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class tf.monochrome.android.**$$serializer { *; }
-keepclassmembers class tf.monochrome.android.** {
    *** Companion;
}
-keepclasseswithmembers class tf.monochrome.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep Appwrite
-keep class io.appwrite.** { *; }

# Keep Media3
-keep class androidx.media3.** { *; }

# Keep projectM JNI bridge
-keep class tf.monochrome.android.visualizer.ProjectMNativeBridge { *; }

# Spotify App Remote SDK. The .aar ships consumer rules for its protocol Item
# types; these additionally pin the Gson-backed mapper and the protocol types it
# reflects over, since a stripped field there fails at runtime as a silently
# empty PlayerState rather than as a build error.
-keep class com.spotify.protocol.mappers.gson.** { *; }
-keep class com.spotify.protocol.types.** { *; }
-dontwarn com.spotify.**

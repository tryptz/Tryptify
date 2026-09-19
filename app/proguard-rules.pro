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

# JAudioTagger instantiates ID3 frame bodies by name: AbstractID3v2Frame does
# Class.forName("org.jaudiotagger.tag.id3.framebody.FrameBody" + id).newInstance().
# R8 cannot see that, so it renames them — FrameBodyAPIC came out as `fj.f` on the
# release build this rule was written against, which is a ClassNotFoundException
# on the first tag write of a downloaded MP3 rather than a build failure. The rule
# is a package because the identifier is assembled at runtime, so there is no
# subset of frames to name.
#
# No -dontwarn is needed alongside it, which is worth recording because it looks
# like it should be. JAudioTagger's desktop image handling — ArtworkFactory,
# StandardArtwork, StandardImageHandler — references java.awt.* and
# javax.imageio.*, neither of them in android.jar. R8 never has to resolve those
# references because nothing reaches the classes holding them: TrackDownloader
# builds the FLAC picture block itself and uses AndroidArtwork for ID3, so all
# three appear in usage.txt as removed. A -dontwarn here would only hide the
# build error that calling ArtworkFactory ought to produce.
-keep class org.jaudiotagger.tag.id3.framebody.** { *; }

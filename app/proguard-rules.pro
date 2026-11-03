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

# jaudiotagger makes each ID3 frame's body by name, Class.forName(
# "org.jaudiotagger.tag.id3.framebody.FrameBody" + frameId), then calls its
# (ByteBuffer, int) constructor. Renamed or stripped, a frame reads as
# unsupported and its text is lost: BPM, key and ReplayGain from an MP3, and a
# WAV's ID3 tags, would come back empty in release builds only.
-keep class org.jaudiotagger.tag.id3.framebody.** { <init>(...); }

# LiveGlassLens reads Haze's internal "source is recording" flag by name, so a
# lens inside its own source skips it instead of looping (HazeContentDrawing).
-keepclassmembers class dev.chrisbanes.haze.HazeArea {
    boolean getContentDrawing$haze_release();
}

# YouTube on the phone (data/youtube), with NewPipeExtractor. These follow
# the rules NewPipe's own app ships for it.
#
# Rhino runs YouTube's player script to unscramble stream links, and wires
# its JavaScript objects to Java by reflection throughout (its own classes
# included, by name), so all of it stays. The parts it never uses on Android
# (javax.script, jdk.dynalink, its tools, its JSON-to-Java bridge over
# java.beans) are absent here and only warned about.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
# protobuf-lite finds a message's fields by their names (the extractor's
# YouTube request parameters are such messages); renamed or stripped, it
# builds them empty.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
# One class of "3 days ago" patterns per language. PatternMap names them
# directly today; NewPipe keeps them whole in case a release loads them by
# name again.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# NewPipe's PO-token WebView (data/youtube/potoken): its page calls these
# methods back by name through addJavascriptInterface. AGP's default rules
# keep them too; this does not depend on that.
-keepclassmembers class tf.monochrome.android.data.youtube.potoken.PoTokenWebView {
    @android.webkit.JavascriptInterface <methods>;
}

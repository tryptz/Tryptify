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
# The same library makes two more kinds of object by class, not by name in
# code: its WMA (ASF) chunk readers, each through Class.newInstance() in
# ChunkContainerReader, and a tag's data fields when a frame is copied
# (ID3Tags.copyObject, through the class's own copy constructor). R8 saw no
# caller for those constructors and took them out: a release build logged
# "java.lang.Class<cj.h> cannot be instantiated" 33 times at its first tag
# read (a phone's log of 2026-11-18, under jaudiotagger's logger
# "org.jaudiotabgger.audio"), and no WMA file's tags could be read there.
# The scan reads tags with the app's own reader now (cpp/tags, TagLib), which
# has no reflection at all; jaudiotagger is what a file TagLib refuses falls
# back to, what Track info shows, and what a download's tags are written with.
-keep class org.jaudiotagger.audio.asf.io.** { <init>(); }
-keep class org.jaudiotagger.tag.datatype.** { <init>(...); }

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

# The Shizuku system-wide equalizer (audio/eq/shizuku). Shizuku starts the
# service in its own process and makes it by name, with the constructor that
# takes nothing; the AIDL classes are both ends of the wire; and Shizuku's
# own classes find each other by name. The service's hidden Android classes
# are reached by reflection and are not in the SDK the app is built against.
-keep class tf.monochrome.android.audio.eq.shizuku.SystemPeqUserService { *; }
-keep class tf.monochrome.android.audio.eq.shizuku.SystemPeqUserService$* { *; }
-keep class tf.monochrome.android.audio.eq.shizuku.ISystemPeqService { *; }
-keep class tf.monochrome.android.audio.eq.shizuku.ISystemPeqService$** { *; }
# The helper that outlives Shizuku is started by name too, by a shell
# command, and entered at its main.
-keep class tf.monochrome.android.audio.eq.shizuku.SystemPeqDaemon { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn android.app.ActivityThread
-dontwarn android.media.audiopolicy.**

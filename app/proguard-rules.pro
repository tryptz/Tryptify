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

# ONNX Runtime's native library looks its Java classes up by name from C++
# (FindClass "ai/onnxruntime/OrtSession$Result", OnnxTensor, TensorInfo, ...)
# and builds results through their constructors. Renamed, FindClass returns
# null and the first OrtSession.run aborts the process — release builds only,
# the moment a chart is separated with an imported model.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

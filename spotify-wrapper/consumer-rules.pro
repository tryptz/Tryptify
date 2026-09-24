# librespot instantiates its audio output by class name
# (PlayerConfiguration.setOutputClass) and calls the public no-arg
# constructor reflectively. Renamed or stripped, native Spotify playback
# fails at the first track with ClassNotFoundException.
-keep class tf.monochrome.android.data.spotify.PcmSink { public <init>(); }

# librespot's other reflective entry points, found by scanning its bytecode
# for getConstructor/newInstance/TextFormat. Without these R8 sees no callers
# and strips them — verified on a release build, where VorbisDecoder shipped
# as an empty class and jorbis was removed entirely, so every Spotify track
# would fail to decode in release while debug played fine.
#
# Decoders$1 builds the codec with
# getConstructor(SeekableInputStream, float, int).
-keep class * extends xyz.gianlu.librespot.player.decoders.Decoder {
    public <init>(xyz.gianlu.librespot.player.decoders.SeekableInputStream, float, int);
}
# JsonMercuryRequest builds its response type with getConstructor(JsonObject).
-keep class * extends xyz.gianlu.librespot.json.JsonWrapper {
    public <init>(com.google.gson.JsonObject);
}
# librespot uses full protobuf-java, not lite. Its GeneratedMessageV3 field
# accessor tables look up getFoo/setFoo/hasFoo by name (TextFormat in the
# Connect state handler goes through them), so renamed accessors fail at
# runtime. Scoped to generated message types, not all of protobuf.
-keep class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keep class * extends com.google.protobuf.GeneratedMessageV3$Builder { *; }
-keep class * implements com.google.protobuf.ProtocolMessageEnum { *; }

# Dependencies of librespot code paths this app never reaches (its CLI Main,
# the TOML FileConfiguration, Zeroconf discovery); deliberately not shipped.
# See spotify-wrapper/build.gradle.kts.
-dontwarn org.apache.logging.log4j.**
-dontwarn com.electronwill.nightconfig.**
-dontwarn xyz.gianlu.zeroconf.**

# librespot instantiates its audio output by class name
# (PlayerConfiguration.setOutputClass) and calls the public no-arg
# constructor reflectively. Renamed or stripped, native Spotify playback
# fails at the first track with ClassNotFoundException.
-keep class tf.monochrome.android.data.spotify.PcmSink { public <init>(); }

# Dependencies of librespot code paths this app never reaches (its CLI Main,
# the TOML FileConfiguration, Zeroconf discovery); deliberately not shipped.
# See spotify-wrapper/build.gradle.kts.
-dontwarn org.apache.logging.log4j.**
-dontwarn com.electronwill.nightconfig.**
-dontwarn xyz.gianlu.zeroconf.**

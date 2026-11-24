package tf.monochrome.android.audio.eq.shizuku;

// The Shizuku system-wide equalizer's service, as the app talks to it. It
// runs in Shizuku's process (SystemPeqUserService), not the app's. Every
// method has its number written out: the two ends are not always the same
// build, and a number must never move.
interface ISystemPeqService {
    // Takes other apps' media audio and plays it through the curve given:
    // each ear's bands as SystemPeqDsp.packBands packs them, and the preamp
    // in dB. appUid is the app's own, left out of what is taken. False when
    // it could not start; status() then says why.
    boolean start(int appUid, in float[] left, in float[] right, float preampDb) = 0;
    // The curve to play from now on, while started.
    void setCurve(in float[] left, in float[] right, float preampDb) = 1;
    // Gives other apps' audio back to Android as it was.
    void stop() = 2;
    // "running", "stopped", or "error: " and why.
    String status() = 3;
    // The app's token: when the app dies, the service stops and leaves.
    void attach(IBinder clientToken) = 4;
    // Shizuku's own number for "leave".
    void destroy() = 16777114;
}

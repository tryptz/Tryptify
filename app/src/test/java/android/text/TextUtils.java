package android.text;

/**
 * The two TextUtils methods Media3's format and MIME handling call, working,
 * for JVM unit tests (the stub throws). Test sources only; see android.util.Pair.
 */
public class TextUtils {
    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().equals(b.toString());
    }
}

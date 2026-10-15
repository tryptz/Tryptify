package android.util;

import java.util.Objects;

/**
 * A working android.util.Pair for JVM unit tests, which otherwise get a stub
 * that throws. Media3's MP4 extractors return one from their box parsers, so
 * without it they cannot run here (see ExtractorHarness). Test sources only;
 * the app uses the platform's own.
 */
public class Pair<F, S> {
    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A a, B b) {
        return new Pair<>(a, b);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Pair)) return false;
        Pair<?, ?> p = (Pair<?, ?>) o;
        return Objects.equals(p.first, first) && Objects.equals(p.second, second);
    }

    @Override
    public int hashCode() {
        return (first == null ? 0 : first.hashCode()) ^ (second == null ? 0 : second.hashCode());
    }
}

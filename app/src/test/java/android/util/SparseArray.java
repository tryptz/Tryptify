package android.util;

import java.util.ArrayList;
import java.util.TreeMap;

/**
 * A working android.util.SparseArray for JVM unit tests (the stub throws), with
 * the platform's ordering: valueAt(i) is the i-th value by ascending key. Only
 * what Media3's MP4 extractors use. Test sources only; see Pair.
 */
public class SparseArray<E> {
    private final TreeMap<Integer, E> map = new TreeMap<>();

    public SparseArray() {}

    public SparseArray(int initialCapacity) {}

    public E get(int key) {
        return map.get(key);
    }

    public E get(int key, E valueIfKeyNotFound) {
        E value = map.get(key);
        return value == null ? valueIfKeyNotFound : value;
    }

    public void put(int key, E value) {
        map.put(key, value);
    }

    public void remove(int key) {
        map.remove(key);
    }

    public int size() {
        return map.size();
    }

    public int keyAt(int index) {
        return new ArrayList<>(map.keySet()).get(index);
    }

    public E valueAt(int index) {
        return new ArrayList<>(map.values()).get(index);
    }

    public void clear() {
        map.clear();
    }
}

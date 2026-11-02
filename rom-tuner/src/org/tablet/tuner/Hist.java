package org.tablet.tuner;

/** Fixed size history of readings, newest last (no Android types: tested on a JVM). */
final class Hist {
    final float[] v;
    int n;

    Hist(int cap) { v = new float[cap]; }

    void add(float x) {
        if (n < v.length) v[n++] = x;
        else { System.arraycopy(v, 1, v, 0, v.length - 1); v[v.length - 1] = x; }
    }

    float last() { return n == 0 ? 0 : v[n - 1]; }

    float max() { float m = 0; for (int i = 0; i < n; i++) m = Math.max(m, v[i]); return m; }
}

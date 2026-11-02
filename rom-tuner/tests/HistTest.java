package org.tablet.tuner;

import java.util.Objects;

/** Run by tests/test_apps.sh: javac -d <dir> src/org/tablet/tuner/Hist.java tests/HistTest.java; java -cp <dir> org.tablet.tuner.HistTest */
public class HistTest {
    static int fail;
    static void eq(String what, Object got, Object want) {
        if (Objects.equals(got, want)) System.out.println("ok   " + what);
        else { System.out.println("FAIL " + what + ": got [" + got + "] want [" + want + "]"); fail = 1; }
    }

    public static void main(String[] a) {
        Hist h = new Hist(3);
        eq("empty last", h.last(), 0f);
        h.add(1); h.add(2);
        eq("count below cap", h.n, 2);
        eq("last", h.last(), 2f);
        h.add(3); h.add(4);
        eq("count stays at cap", h.n, 3);
        eq("oldest dropped", h.v[0], 2f);
        eq("newest last", h.v[2], 4f);
        eq("max", h.max(), 4f);
        System.exit(fail);
    }
}

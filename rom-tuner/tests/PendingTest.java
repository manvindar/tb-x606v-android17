package org.tablet.tuner;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Run by tests/test_apps.sh: javac -d <dir> src/org/tablet/tuner/Pending.java src/org/tablet/tuner/PicMath.java tests/PendingTest.java; java -cp <dir> org.tablet.tuner.PendingTest */
public class PendingTest {
    static int fail;
    static void eq(String what, Object got, Object want) {
        if (Objects.equals(got, want)) System.out.println("ok   " + what);
        else { System.out.println("FAIL " + what + ": got [" + got + "] want [" + want + "]"); fail = 1; }
    }
    static Map<String, String> m(String... kv) { Map<String, String> r = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) r.put(kv[i], kv[i + 1]); return r; }

    public static void main(String[] a) {
        Pending p = new Pending();
        p.put("pq_contrast", "6", 1000);
        eq("stale read keeps the new value", p.overlay(m("pq_contrast", "4", "pq_sat", "4"), 3500).get("pq_contrast"), "6");
        eq("other keys untouched", p.overlay(m("pq_sat", "4"), 3500).get("pq_sat"), "4");
        eq("kept just inside the window", p.overlay(m("pq_contrast", "4"), 1000 + Pending.TTL_MS).get("pq_contrast"), "6");
        eq("dropped after the window", p.overlay(m("pq_contrast", "4"), 1000 + Pending.TTL_MS + 1).get("pq_contrast"), "4");
        eq("stays dropped", p.overlay(m("pq_contrast", "5"), 1500).get("pq_contrast"), "5");
        Pending q = new Pending(); q.put("k", "1", 0); q.put("k", "2", 5000);
        eq("a newer change replaces and restarts the window", q.overlay(m("k", "0"), 5000 + Pending.TTL_MS).get("k"), "2");

        eq("contrast neutral", PicMath.contrast(4), 1.0f);
        eq("contrast up", PicMath.contrast(6) > 1f, true);
        eq("saturation down", PicMath.saturation(2) < 1f, true);
        eq("factory mode is neutral", PicMath.effective("0", "9"), 4);
        eq("custom mode uses the index", PicMath.effective("2", "7"), 7);
        eq("bad index is neutral", PicMath.effective("2", "x"), 4);
        eq("relative change", PicMath.rel(PicMath.contrast(4), PicMath.contrast(6)), 1.2f);
        System.exit(fail);
    }
}

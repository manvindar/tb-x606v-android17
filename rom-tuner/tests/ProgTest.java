package org.tablet.tuner;

import java.util.*;

/** Run by tests/test_apps.sh: javac -d <dir> src/org/tablet/tuner/Prog.java tests/ProgTest.java; java -cp <dir> org.tablet.tuner.ProgTest */
public class ProgTest {
    static int fail;
    static void eq(String what, Object got, Object want) {
        if (Objects.equals(got, want)) System.out.println("ok   " + what);
        else { System.out.println("FAIL " + what + ": got [" + got + "] want [" + want + "]"); fail = 1; }
    }
    static Map<String, String> m(String... kv) {
        Map<String, String> r = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put(kv[i], kv[i + 1]);
        return r;
    }

    public static void main(String[] a) {
        eq("old daemon: no keys", Prog.parse(m("compile_status", "running", "status", "x")), null);
        eq("idle: no keys", Prog.parse(m("compile_status", "idle")), null);
        eq("empty done key", Prog.parse(m("compile_done", "")), null);

        Prog p = Prog.parse(m("compile_status", "running", "compile_current", "com.a.b", "compile_current_state", "compiling",
            "compile_done", "12", "compile_total", "340", "compile_failed", "1", "compile_started", "1000"));
        eq("current", p.current, "com.a.b");
        eq("compiling", p.compiling, true);
        eq("percent", p.percent(), 3);
        eq("counts, bulk is an estimate", p.counts(), "12 of about 340");
        eq("elapsed 3:05", p.elapsed(1185000L), "3:05");
        eq("detail", p.detail(1185000L), "12 of about 340 · 3:05 · 1 failed");

        p = Prog.parse(m("compile_status", "running", "compile_current", "com.a.b", "compile_current_state", "finished", "compile_done", "400", "compile_total", "340", "compile_started", "1000"));
        eq("finished app", p.compiling, false);
        eq("percent capped at 100", p.percent(), 100);
        eq("no failures: not in the detail", p.detail(1060000L), "400 of about 340 · 1:00");

        p = Prog.parse(m("compile_status", "idle", "compile_current", "com.q.one", "compile_current_state", "compiling", "compile_done", "1", "compile_total", "3", "compile_failed", "0", "compile_started", "500"));
        eq("queue: exact total", p.counts(), "1 of 3");
        eq("queue percent", p.percent(), 33);

        p = Prog.parse(m("compile_status", "running", "compile_done", "0", "compile_total", "0", "compile_started", ""));
        eq("total unknown", p.counts(), "0 done");
        eq("total unknown: no bar", p.percent(), 0);
        eq("no start time: no elapsed", p.detail(5000L), "0 done");
        eq("current may be empty", p.current, "");

        p = Prog.parse(m("compile_done", "x", "compile_total", "-", "compile_started", "7x"));
        eq("garbage numbers are 0", p.done + "/" + p.total + "/" + p.started, "0/0/0");

        eq("elapsed in the future clamps to 0", Prog.parse(m("compile_done", "1", "compile_started", "9999")).elapsed(1000L), "0:00");
        eq("mm:ss over an hour", Prog.mmss(4503), "75:03");

        eq("summary when idle", Prog.summary(m("compile_status", "done", "compile_summary", "5 done, 2 failed in 5:05")), "5 done, 2 failed in 5:05");
        eq("no summary while a run shows progress", Prog.summary(m("compile_summary", "x", "compile_done", "1")), "");
        eq("no summary key", Prog.summary(m("compile_status", "done")), "");
        System.exit(fail);
    }
}

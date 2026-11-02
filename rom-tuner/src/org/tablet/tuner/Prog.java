package org.tablet.tuner;

import java.util.Map;

/** Compile progress from the state copy (compile_current, compile_done, ...). Null from parse() when the daemon publishes none (old daemon, or idle). No Android classes: tests/ProgTest.java runs it on a plain JVM. */
final class Prog {
    final String current;       // package being compiled, or the last one that finished (see compiling); may be empty
    final boolean compiling;    // false: current is the last finished app (the -a step names an app only after it is done)
    final int done, total, failed;   // total 0 = unknown
    final long started;         // epoch seconds, 0 = unknown
    final boolean bulk;         // a system run (the total is an estimate) as against the per app queue (exact)

    private Prog(String c, boolean k, int d, int t, int f, long s, boolean b) { current = c; compiling = k; done = d; total = t; failed = f; started = s; bulk = b; }

    static Prog parse(Map<String, String> s) {
        String d = s.get("compile_done");
        if (d == null || d.isEmpty()) return null;
        String cur = s.get("compile_current");
        return new Prog(cur == null ? "" : cur, "compiling".equals(s.get("compile_current_state")),
            (int) num(d), (int) num(s.get("compile_total")), (int) num(s.get("compile_failed")), num(s.get("compile_started")),
            "running".equals(s.get("compile_status")));
    }

    /** "Last run: ..." text, only when no run is going on and the daemon left a summary. */
    static String summary(Map<String, String> s) {
        String v = s.get("compile_summary");
        return v == null || v.isEmpty() || s.get("compile_done") != null ? "" : v;
    }

    int percent() { return total <= 0 ? 0 : Math.min(100, (int) ((long) done * 100 / total)); }

    String counts() { return total <= 0 ? done + " done" : done + (bulk ? " of about " : " of ") + total; }

    String elapsed(long nowMs) { return started <= 0 ? "" : mmss(Math.max(0, nowMs / 1000 - started)); }

    /** "12 of about 340 · 3:05 · 1 failed", parts left out when unknown. */
    String detail(long nowMs) {
        StringBuilder b = new StringBuilder(counts());
        String e = elapsed(nowMs);
        if (!e.isEmpty()) b.append(" · ").append(e);
        if (failed > 0) b.append(" · ").append(failed).append(" failed");
        return b.toString();
    }

    static String mmss(long sec) { return String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60); }

    private static long num(String v) {
        try { return v == null || v.isEmpty() ? 0 : Long.parseLong(v.trim()); } catch (NumberFormatException e) { return 0; }
    }
}

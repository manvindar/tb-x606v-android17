package org.tablet.tuner;

/** Settings search: pure functions (no Android types) so they can be unit tested on a JVM. */
final class Search {
    private Search() { }

    /** Every word of the query must appear in one of the fields (case insensitive, any order). */
    static boolean matches(String query, String... fields) {
        String hay = String.join(" ", fields).toLowerCase(java.util.Locale.ROOT);
        for (String w : query.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+")) if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }

    /** Lower is better: 0 the title starts with the query, 1 the title contains every word, 2 only the other fields do. */
    static int rank(String query, String title) {
        String q = query.toLowerCase(java.util.Locale.ROOT).trim(), t = title.toLowerCase(java.util.Locale.ROOT);
        return t.startsWith(q) ? 0 : matches(q, title) ? 1 : 2;
    }
}

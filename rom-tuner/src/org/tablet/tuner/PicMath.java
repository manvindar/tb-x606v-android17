package org.tablet.tuner;

/** Picture preview maths (no Android types: tested on a JVM). Indexes are the MediaTek ones: 0..9, 4 is neutral. */
final class PicMath {
    private PicMath() { }

    /** Contrast factor of an index: 1.0 at 4, 0.6 at 0, 1.5 at 9 (an estimate, not measured). */
    static float contrast(int idx) { return 1f + (idx - 4) * 0.1f; }

    /** Saturation factor of an index: 1.0 at 4, 0.4 at 0, 1.75 at 9 (an estimate). */
    static float saturation(int idx) { return 1f + (idx - 4) * 0.15f; }

    /** Index in effect: the stored one in Custom mode (2), neutral in Factory mode. */
    static int effective(String mode, String idx) {
        if (!"2".equals(mode)) return 4;
        try { return Math.max(0, Math.min(9, Integer.parseInt(idx))); } catch (NumberFormatException e) { return 4; }
    }

    /** Change to draw on top of what the display already does when going from index a to index b, as a ratio of factors. */
    static float rel(float from, float to) { return from <= 0 ? 1f : to / from; }
}

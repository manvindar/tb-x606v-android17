package org.tablet.tuner;

/** Pure profile decisions (no Android types) so they can be unit tested on a JVM. */
final class Profiles {
    private Profiles() { }

    /**
     * Offer "Save changes to ..." when the settings came from a saved profile (base, kept by the daemon across edits) and no longer match it
     * (active is cleared by an edit). An old daemon publishes no base, so base is empty and there is no offer.
     */
    static boolean canUpdate(String base, String active, String savedCsv) {
        if (base == null || base.isEmpty() || base.equals(active)) return false;
        for (String p : savedCsv == null ? new String[0] : savedCsv.split(",")) if (p.equals(base)) return true;
        return false;
    }

    /** Names are stored with underscores; shown with spaces. */
    static String shown(String name) { return name.replace('_', ' '); }
}

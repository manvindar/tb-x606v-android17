package org.tablet.tuner;

import java.util.Objects;

/** Run by tests/test_apps.sh: javac -d <dir> src/org/tablet/tuner/Profiles.java tests/ProfilesTest.java; java -cp <dir> org.tablet.tuner.ProfilesTest */
public class ProfilesTest {
    static int fail;
    static void eq(String what, Object got, Object want) {
        if (Objects.equals(got, want)) System.out.println("ok   " + what);
        else { System.out.println("FAIL " + what + ": got [" + got + "] want [" + want + "]"); fail = 1; }
    }

    public static void main(String[] a) {
        eq("edited profile offers the update", Profiles.canUpdate("fast", "", "fast,quiet"), true);
        eq("unmodified profile: no row", Profiles.canUpdate("fast", "fast", "fast,quiet"), false);
        eq("old daemon, no base: no row", Profiles.canUpdate("", "", "fast,quiet"), false);
        eq("null base: no row", Profiles.canUpdate(null, "", "fast"), false);
        eq("base not in the list (deleted): no row", Profiles.canUpdate("gone", "", "fast,quiet"), false);
        eq("empty list: no row", Profiles.canUpdate("fast", "", ""), false);
        eq("null list: no row", Profiles.canUpdate("fast", "", null), false);
        eq("exact match only, not a prefix", Profiles.canUpdate("fa", "", "fast"), false);
        eq("another profile active: still differs", Profiles.canUpdate("fast", "quiet", "fast,quiet"), true);
        eq("null active counts as edited", Profiles.canUpdate("fast", null, "fast"), true);
        eq("shown", Profiles.shown("my_fast_one"), "my fast one");
        System.exit(fail);
    }
}

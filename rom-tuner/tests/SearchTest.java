package org.tablet.tuner;

import java.util.Objects;

/** Run by tests/test_apps.sh: javac -d <dir> src/org/tablet/tuner/Search.java tests/SearchTest.java; java -cp <dir> org.tablet.tuner.SearchTest */
public class SearchTest {
    static int fail;
    static void eq(String what, Object got, Object want) {
        if (Objects.equals(got, want)) System.out.println("ok   " + what);
        else { System.out.println("FAIL " + what + ": got [" + got + "] want [" + want + "]"); fail = 1; }
    }

    public static void main(String[] a) {
        eq("title word", Search.matches("gpu", "GPU clock", "Locks the GPU", "Performance details"), true);
        eq("case", Search.matches("GPU", "gpu clock"), true);
        eq("sub text", Search.matches("jank", "Touch boost", "Less scroll jank"), true);
        eq("page name", Search.matches("picture", "Contrast", "", "Picture"), true);
        eq("any order", Search.matches("clock gpu", "GPU clock"), true);
        eq("all words needed", Search.matches("gpu contrast", "GPU clock", "Locks the GPU"), false);
        eq("no match", Search.matches("zzz", "GPU clock", "Locks"), false);
        eq("blank query matches", Search.matches("  ", "GPU clock"), true);
        eq("rank prefix", Search.rank("gpu", "GPU clock"), 0);
        eq("rank contains", Search.rank("clock", "GPU clock"), 1);
        eq("rank other fields only", Search.rank("jank", "Touch boost"), 2);
        System.exit(fail);
    }
}

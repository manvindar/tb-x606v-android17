package org.tablet.tuner;

import java.util.Objects;

/** Run by tests/test_apps.sh: javac -d <dir> src/org/tablet/tuner/Layouts.java tests/LayoutTest.java; java -cp <dir> org.tablet.tuner.LayoutTest */
public class LayoutTest {
    static int fail;
    static void eq(String what, Object got, Object want) {
        if (Objects.equals(got, want)) System.out.println("ok   " + what);
        else { System.out.println("FAIL " + what + ": got [" + got + "] want [" + want + "]"); fail = 1; }
    }

    public static void main(String[] a) {
        // the tablet at the 192 dpi override: 1200x1920 px is 1000 x 1600 dp, landscape 1600 x 1000
        eq("tablet portrait gets the rail layout", Layouts.wide(1000, 1600), true);
        eq("tablet portrait at 800 dp wide", Layouts.wide(800, 1280), true);
        eq("tablet landscape is two panes", Layouts.wide(1600, 1000), true);
        eq("phone portrait", Layouts.wide(411, 891), false);
        eq("phone landscape 891 dp is two panes", Layouts.wide(891, 411), true);
        eq("small tablet landscape 800 dp", Layouts.wide(800, 480), true);
        eq("threshold 760", Layouts.wide(760, 600), true);
        eq("759 is not wide", Layouts.wide(759, 600), false);
        eq("a 600 dp tablet in portrait is one column", Layouts.wide(600, 960), false);
        eq("square window is wide", Layouts.wide(900, 900), true);
        eq("phone in portrait stays one column", Layouts.wide(411, 891), false);

        eq("left pane is a rail", Layouts.leftWidth(1600), 88);
        eq("left pane is a rail at 840", Layouts.leftWidth(840), 88);
        eq("content at 1600", Layouts.contentWidth(1600), 1463);
        eq("content at 840", Layouts.contentWidth(840), 703);

        eq("settings columns 1600", Layouts.settingColumns(Layouts.contentWidth(1600)), 2);
        eq("settings columns 1280", Layouts.settingColumns(Layouts.contentWidth(1280)), 2);
        eq("settings columns 1000", Layouts.settingColumns(Layouts.contentWidth(1000)), 2);
        eq("settings columns 840", Layouts.settingColumns(Layouts.contentWidth(840)), 1);
        eq("apps columns 1600", Layouts.appColumns(Layouts.contentWidth(1600)), 3);
        eq("apps columns 1280", Layouts.appColumns(Layouts.contentWidth(1280)), 3);
        eq("apps columns 840", Layouts.appColumns(Layouts.contentWidth(840)), 2);

        eq("header pinned at 1000 dp high", Layouts.pinHeader(1000), true);
        eq("header not pinned at 600 dp high", Layouts.pinHeader(600), false);
        System.exit(fail);
    }
}

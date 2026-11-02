package org.tablet.tuner;

/** Pure layout decisions (no Android types) so they can be unit tested on a JVM. All sizes are dp. */
final class Layouts {
    static final int WIDE_MIN_W = 760;       // narrower than this: the single column of a phone (the tablet in portrait is 800 dp wide)
    static final int PIN_MIN_H = 720;        // shorter than this: the left pane scrolls as one piece
    static final int PANE_PAD = 24;          // padding on each side of the right pane
    // a large screen in portrait (1000 dp wide) gets the rail layout too; phones are narrower than WIDE_MIN_W and keep the single column.
    static final boolean WIDE_IN_PORTRAIT = true;

    private Layouts() { }

    /** Two panes: wide enough, and landscape unless WIDE_IN_PORTRAIT. */
    static boolean wide(int wDp, int hDp) { return wDp >= WIDE_MIN_W && (WIDE_IN_PORTRAIT || wDp > hDp); }

    /** Left pane: a navigation rail of icons. */
    static int leftWidth(int wDp) { return 88; }

    /** Usable width of the content in the right pane (left pane, 1 dp divider and the side padding taken off). */
    static int contentWidth(int wDp) { return wDp - leftWidth(wDp) - 1 - 2 * PANE_PAD; }

    /** Columns of setting cards: two once each card is at least about 340 dp wide. */
    static int settingColumns(int contentDp) { return contentDp >= 720 ? 2 : 1; }

    /** Columns of app rows: three from 1000 dp, two from 640 dp. */
    static int appColumns(int contentDp) { return contentDp >= 1000 ? 3 : contentDp >= 640 ? 2 : 1; }

    /** Dial, preset name and live card stay put above the section list only when the screen is tall enough. */
    static boolean pinHeader(int hDp) { return hDp >= PIN_MIN_H; }
}

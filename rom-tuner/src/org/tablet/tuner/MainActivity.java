package org.tablet.tuner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/** Home: a dial for the performance preset plus a short list of areas. Each area is a page with plain-language rows. */
public class MainActivity extends Activity {
    private static final String HOME = "home";
    private Map<String, String> st;
    private LinearLayout col;
    private boolean night;
    private float dp;
    private String page = HOME;                         // narrow layout: the page on screen
    private String section = "overview";                    // two pane layout: the section in the right pane
    private boolean wide;                               // layout mode of the tree built last
    private int cols = 1, appsCols = 1;                 // columns for setting cards and for app rows
    private ScrollView mainSv, leftSv, rightSv;
    private LinearLayout rightCol;
    private final java.util.HashMap<String, View> entryRows = new java.util.HashMap<>();
    private String builtKey = "";
    // search: the query survives a rebuild; the index (page, title, sub, group) is built once from the pages themselves
    private String query = "";
    private boolean collecting;
    private String collPage, collGroup, focusTitle;
    private final ArrayList<String[]> index = new ArrayList<>();
    private View rootView;
    private LinearLayout homeBody, resultsBox;
    private View barView;
    private int swapTok;
    private TextView status;
    private TextView liveCpu, liveGpu, liveMem, liveHeat, compileView, ocView;
    private final android.os.Handler ui = new android.os.Handler();
    private final Runnable tick = new Runnable() { @Override public void run() { sample(); refreshLive(); refreshOverview(); refreshGraphs(); refreshCompile(); refreshProgress(); refreshOc(); refreshApps(); ui.postDelayed(this, 1000); } };
    private final Pending pend = new Pending();
    /** The published state with the values changed in the last seconds laid over it (the daemon publishes late). */
    private Map<String, String> loadState() { return pend.overlay(Cfg.load(this), System.currentTimeMillis()); }
    private final Runnable reload = () -> { st = loadState(); setStatus("Applied"); build(); };

    // preset id, name, one-line description
    private static final String[][] PRESETS = {
        {"battery", "Battery", "Low power mode and capped clocks. Quiet and cool, slower."},
        {"balanced", "Balanced", "Everyday default: a moderate clock floor, GPU at full speed."},
        {"smooth", "Smooth", "Stronger app boosts and more free memory. The best everyday feel."},
        {"performance", "Performance", "Maximum app boosts and a bigger write buffer. Lowest scroll jank in tests."},
        {"extreme", "Extreme", "Top clocks pinned, thermal limit off. Hot, short on battery, no UI gain over Performance."}};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTitle("ROM Tuner");
        if (getActionBar() != null) getActionBar().hide();
        String p = getIntent().getStringExtra("page");
        if (p != null) { page = p; section = p; }
        if (b != null) {
            page = b.getString("page", page); section = b.getString("section", section);
            appsQuery = b.getString("query", ""); appsFilter = b.getInt("filter", 0); query = b.getString("q", "");
        }
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putString("page", page); b.putString("section", section); b.putString("query", appsQuery); b.putInt("filter", appsFilter); b.putString("q", query);
    }

    /** Rotation, window resize and a display size change land here (see configChanges in the manifest): rebuild the tree, keep the state. */
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        dp = getResources().getDisplayMetrics().density;
        if (!wide && Layouts.wide(c.screenWidthDp, c.screenHeightDp) && !HOME.equals(page)) section = page;
        if (st != null) build();
    }

    @Override protected void onResume() { super.onResume(); st = loadState(); index.clear(); build(); ui.post(tick); }

    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }

    @Override public void onBackPressed() { if (!wide && !HOME.equals(page)) go(parentPage()); else super.onBackPressed(); }

    private String parentPage() { return HOME; }

    /** The page or section the user is looking at. */
    private String cur() { return wide ? section : page; }

    // ---------- state ----------
    private String get(String k, String def) { String v = st.get(k); return v == null || v.isEmpty() ? def : v; }

    /** Changes a value that is sent elsewhere (a direct request): shown at once and kept over older published state. */
    private void put(String k, String v) { st.put(k, v); pend.put(k, v, System.currentTimeMillis()); Cfg.request(this, k, v); }

    private void set(String k, String v) {
        st.put(k, v); pend.put(k, v, System.currentTimeMillis());
        Cfg.request(this, k, v);
        setStatus("Applying…");
        ui.removeCallbacks(reload);
        ui.postDelayed(reload, 2500);
    }

    private void setStatus(String s) { if (status != null) status.setText(s); }

    private void go(String p) {
        if (wide) { if (!HOME.equals(p)) selectSection(p); return; }
        page = p; build(); if (mainSv != null) mainSv.scrollTo(0, 0);
    }

    // ---------- look ----------
    private int px(float v) { return Math.round(v * dp); }
    private int sys(int night300, int day600) { return getColor(night ? night300 : day600); }
    private int accent() { return sys(android.R.color.system_accent1_300, android.R.color.system_accent1_600); }
    private int onAccent() { return sys(android.R.color.system_accent1_900, android.R.color.system_accent1_0); }
    private int surface() { return sys(android.R.color.system_surface_dark, android.R.color.system_surface_light); }   // follows the pure black overlay
    private int card() { return sys(android.R.color.system_surface_container_high_dark, android.R.color.system_surface_container_high_light); }
    private int ink() { return sys(android.R.color.system_neutral1_50, android.R.color.system_neutral1_900); }
    private int dim() { return sys(android.R.color.system_neutral2_300, android.R.color.system_neutral2_600); }
    private int line() { return sys(android.R.color.system_neutral2_700, android.R.color.system_neutral2_200); }

    private TextView text(String s, float sp, boolean medium, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int secContainer() { return sys(android.R.color.system_accent2_700, android.R.color.system_accent2_100); }
    private int onSecContainer() { return sys(android.R.color.system_accent2_100, android.R.color.system_accent2_900); }
    private int ripple() { return (ink() & 0xFFFFFF) | 0x1F000000; }

    /** Corner radii of each tile (px), so a tile can be painted again (selected, flashed) with the same shape. */
    private final java.util.WeakHashMap<View, float[]> radii = new java.util.WeakHashMap<>();

    private void paintTile(View v, int fill) {
        float[] r = radii.get(v);
        if (r == null) return;
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadii(r);
        GradientDrawable m = new GradientDrawable(); m.setColor(0xFF000000); m.setCornerRadii(r);
        v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple()), g, m));
    }

    /** A segmented list: every child is its own tile, large corners on the outside of the group and small ones between tiles, 2 dp apart. */
    private void restyle(LinearLayout c) {
        int n = c.getChildCount();
        float big = px(28), small = px(6);
        for (int i = 0; i < n; i++) {
            View v = c.getChildAt(i);
            float t = i == 0 ? big : small, b = i == n - 1 ? big : small;
            radii.put(v, new float[]{t, t, t, t, b, b, b, b});
            paintTile(v, v.isSelected() ? secContainer() : card());
            if (v.getLayoutParams() instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) v.getLayoutParams()).topMargin = i == 0 ? 0 : px(2);
        }
    }

    /** A segmented list that rows go into. */
    private LinearLayout newCard() {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(View parent, View child) { restyle(c); }
            @Override public void onChildViewRemoved(View parent, View child) { restyle(c); }
        });
        return c;
    }

    /** One rounded surface for content that is not a list of rows (a chart, the dial). */
    private LinearLayout plainCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(card()); g.setCornerRadius(px(28));
        c.setBackground(g);
        c.setPadding(0, px(4), 0, px(4));
        return c;
    }

    private LinearLayout group(String title) {
        if (collecting) collGroup = title;
        LinearLayout host = cols > 1 ? shorterColumn() : col;
        if (title != null) {
            TextView t = text(title, 14, true, accent());
            t.setPadding(px(20), px(24), 0, px(8));
            host.addView(t);
        } else {
            View gap = new View(this); host.addView(gap, new LinearLayout.LayoutParams(-1, px(14)));
        }
        LinearLayout c = newCard();
        host.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private static final Object PAIR = new Object();
    /**
     * Two columns of cards for a wide right pane. A pair of columns lasts until something else is added to col (a hint, a warning),
     * which spans both; the next group then starts a new pair. Each card goes to the column with fewer rows so far.
     * ponytail: the row count ignores wrapped text, so heights can differ by a few rows.
     */
    private LinearLayout shorterColumn() {
        View last = col.getChildCount() > 0 ? col.getChildAt(col.getChildCount() - 1) : null;
        LinearLayout pair;
        if (last != null && last.getTag() == PAIR) pair = (LinearLayout) last;
        else {
            pair = new LinearLayout(this);
            pair.setTag(PAIR); pair.setBaselineAligned(false);
            for (int i = 0; i < 2; i++) {
                LinearLayout k = new LinearLayout(this); k.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(i == 0 ? 0 : px(8), 0, i == 0 ? px(8) : 0, 0);
                pair.addView(k, lp);
            }
            col.addView(pair, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout a = (LinearLayout) pair.getChildAt(0), b = (LinearLayout) pair.getChildAt(1);
        return rowsIn(a) <= rowsIn(b) ? a : b;
    }
    private static int rowsIn(LinearLayout column) {
        int n = 0;
        for (int i = 0; i < column.getChildCount(); i++) { View v = column.getChildAt(i); n += v instanceof LinearLayout ? ((LinearLayout) v).getChildCount() : 1; }
        return n;
    }

    private void divider(LinearLayout c) {
        View v = new View(this); v.setBackgroundColor(surface());   // a gap in the page colour: the rows read as separate tiles
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(2));
        c.addView(v, lp);
    }

    private LinearLayout row(LinearLayout c, String title, String sub, View control) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(60));
        r.setTag(title);
        if (collecting) index.add(new String[]{collPage, title, sub == null ? "" : sub, collGroup == null ? "" : collGroup});
        r.setPadding(px(20), px(12), px(16), px(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 16, false, ink()));
        if (sub != null) { TextView s = text(sub, 14, false, dim()); s.setPadding(0, px(2), px(12), 0); texts.addView(s); }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (control != null) r.addView(control);
        c.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private void rowSwitch(LinearLayout c, String title, String sub, final String key, String def) {
        Switch s = new Switch(this);
        s.setChecked("1".equals(get(key, def)));
        s.setOnCheckedChangeListener((v, on) -> set(key, on ? "1" : "0"));
        row(c, title, sub, s).setOnClickListener(v -> s.toggle());
    }

    /** A row that shows the current value; tapping opens a list. */
    private void rowChoice(LinearLayout c, final String title, String sub, final String key, final String[] labels, final String[] vals, String def) { rowChoice(c, title, sub, key, labels, vals, def, false); }

    private void rowChoice(LinearLayout c, final String title, String sub, final String key, final String[] labels, final String[] vals, String def, final boolean pic) {
        String cur = pic ? pv(key, def) : get(key, def); int pos = -1;
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) pos = i;
        TextView v = text((pos >= 0 ? labels[pos] : cur) + "  ›", 15, true, accent());
        final int sel = pos;
        row(c, title, sub, v).setOnClickListener(x ->
            new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(labels, sel, (d, i) -> { d.dismiss(); if (i != sel) { if (pic) picEdit.put(key, vals[i]); else set(key, vals[i]); } build(); })
                .setNegativeButton("Cancel", null).show());
    }

    private void rowSlider(LinearLayout c, String title, String sub, final String key, final int max, final int def, final String[] fmt) { rowSlider(c, title, sub, key, max, def, fmt, false); }

    private void rowSlider(LinearLayout c, String title, String sub, final String key, final int max, final int def, final String[] fmt, final boolean pic) {
        int cur = def;
        try { cur = Math.round(Float.parseFloat(pic ? pv(key, String.valueOf(def)) : get(key, String.valueOf(def)))); } catch (Exception ignored) { }
        final TextView val = text("", 15, true, accent());
        SeekBar sb = new SeekBar(this); sb.setMax(max); sb.setProgress(cur);
        final int cc = cur;
        val.setText(label(fmt, cc));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { val.setText(label(fmt, p)); if (pic && u) { picEdit.put(key, String.valueOf(p)); updatePreview(); } }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { if (pic) { picEdit.put(key, String.valueOf(s.getProgress())); build(); } else set(key, String.valueOf(s.getProgress())); }
        });
        sliderTile(c, row(c, title, sub, val), sb);
    }

    /** The row and its bar make one tile. */
    private void sliderTile(LinearLayout c, View r, SeekBar sb) {
        c.removeView(r);
        LinearLayout w = new LinearLayout(this); w.setOrientation(LinearLayout.VERTICAL);
        w.addView(r, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(px(14), 0, px(14), px(10));
        w.addView(sb, lp);
        c.addView(w, new LinearLayout.LayoutParams(-1, -2));
    }
    private String label(String[] fmt, int p) { return fmt != null && p < fmt.length ? fmt[p] : String.valueOf(p); }

    private void rowGo(LinearLayout c, String title, String sub, final String target) {
        row(c, title, sub, text("›", 22, false, dim())).setOnClickListener(v -> go(target));
    }

    private void rowButton(LinearLayout c, String title, String sub, final Intent i) {
        row(c, title, sub, text("›", 22, false, dim())).setOnClickListener(v -> {
            try { startActivity(i); } catch (Exception e) { Toast.makeText(this, "Not available on this build", Toast.LENGTH_SHORT).show(); }
        });
    }

    private void rowAction(LinearLayout c, String title, String sub, final Runnable run) { row(c, title, sub, null).setOnClickListener(v -> run.run()); }

    private Intent comp(String pkg, String cls) { return new Intent().setComponent(new ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }

    private String[] list(String key, String fallback) { String v = get(key, ""); return (v.isEmpty() ? fallback : v).split(","); }

    private String[] gpuValues() {
        String[] o = list("gpu_opps", "680000,500000,400000");
        String[] r = new String[o.length + 1]; r[0] = "0";
        System.arraycopy(o, 0, r, 1, o.length);
        return r;
    }
    private String[] gpuLabels() {
        String[] v = gpuValues(), r = new String[v.length]; r[0] = "Automatic";
        for (int i = 1; i < v.length; i++) r[i] = (Integer.parseInt(v[i]) / 1000) + " MHz";
        return r;
    }
    private static String fontLabel(String f) {
        switch (f) {
            case "lato": return "Lato";
            case "rubik": return "Rubik";
            case "notoserifsource": return "Noto Serif";
            case "googlesansflex": return "Google Sans Flex";
            case "googlesans": return "Google Sans";
            default: return f.substring(0, 1).toUpperCase(Locale.US) + f.substring(1);
        }
    }

    private static String pick(String v, String[] vals, String[] names, String def) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return names[i];
        return def;
    }

    // ---------- frame ----------
    private void resetRefs() {
        liveCpu = liveGpu = liveMem = liveHeat = null;
        resetPageRefs();
    }
    /** Views owned by a page; the live card (left pane in wide mode) is not one of them. */
    private void resetPageRefs() {
        compileView = ocView = null;
        ovV = new TextView[4]; ovG = new LiveGraph[3];
        preview = null;
        graphBox = stateBox = null; graphs.clear(); gv = new TextView[6]; sv2 = new TextView[3];
        appsBox = null; appsInfo = null; progBox = null;
    }

    private String viewKey() { return (wide ? "w:" : "n:") + cur(); }

    /** Rebuilds the whole tree. The scroll positions survive when the same page is rebuilt (a setting changed, the device turned). */
    private void build() {
        int[] y = builtKey.equals(viewKey()) || builtKey.isEmpty() ? new int[]{sy(mainSv), sy(leftSv), sy(rightSv)} : new int[3];
        resetRefs();
        barView = null; mainSv = leftSv = rightSv = null; rightCol = null; entryRows.clear(); homeBody = resultsBox = null; searchBox = null;
        Configuration cf = getResources().getConfiguration();
        wide = Layouts.wide(cf.screenWidthDp, cf.screenHeightDp);
        if (wide) buildWide(cf.screenWidthDp, cf.screenHeightDp); else buildNarrow();
        builtKey = viewKey();
        restoreScroll(mainSv, y[0]); restoreScroll(leftSv, y[1]); restoreScroll(rightSv, y[2]);
        runFocus();
    }
    private static int sy(ScrollView v) { return v == null ? 0 : v.getScrollY(); }
    private static void restoreScroll(final ScrollView v, final int y) { if (v != null && y > 0) v.post(() -> v.scrollTo(0, y)); }

    /** One centred column, as on a phone. */
    private void buildNarrow() {
        cols = 1; appsCols = 1;
        ScrollView sv = new ScrollView(this);
        mainSv = sv;
        sv.setBackgroundColor(surface());
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(16), px(20), px(16), px(HOME.equals(page) && barWanted() ? 128 : 48));
        outer.addView(col, new LinearLayout.LayoutParams(Math.min(px(680), getResources().getDisplayMetrics().widthPixels), -2));
        sv.addView(outer);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(6), px(4), px(6), px(4));
        if (!HOME.equals(page)) {
            TextView back = text("‹", 30, false, ink());
            back.setGravity(Gravity.CENTER); back.setIncludeFontPadding(false);
            GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(card()); back.setBackground(bg);
            back.setContentDescription("Back");
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(px(48), px(48)); bl.setMargins(0, 0, px(14), 0);
            back.setOnClickListener(v -> go(parentPage()));
            head.addView(back, bl);
        }
        head.addView(text(HOME.equals(page) ? "ROM Tuner" : pageTitle(page), HOME.equals(page) ? 34 : 30, true, ink()), new LinearLayout.LayoutParams(0, -2, 1f));
        status = text(st.isEmpty() ? "Waiting for the tuner service…" : "", 13, false, dim());
        status.setMaxWidth(px(260)); status.setGravity(Gravity.END);
        head.addView(status);
        col.addView(head);

        if (HOME.equals(page)) {
            col.addView(searchBar(), searchLp());
            homeBody = new LinearLayout(this); homeBody.setOrientation(LinearLayout.VERTICAL);
            resultsBox = new LinearLayout(this); resultsBox.setOrientation(LinearLayout.VERTICAL);
            col.addView(homeBody); col.addView(resultsBox);
            LinearLayout host = col; col = homeBody; pageHome(); col = host;
            onQuery();
        } else buildPage(page);
        if (HOME.equals(page) && barWanted()) {
            FrameLayout fr = new FrameLayout(this);
            fr.addView(sv, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2); bl.gravity = Gravity.BOTTOM;
            fr.addView(saveBar(false), bl);
            rootView = fr; setContentView(fr);
        } else { rootView = sv; setContentView(sv); }
    }

    private void buildPage(String p) {
        switch (p) {
            case "overview": pageOverview(); break;
            case "live": pageLive(); break;
            case "perf": pagePerf(); break;
            case "oc": pageOc(); break;
            case "profiles": pageProfiles(); break;
            case "memory": pageMemory(); break;
            case "picture": pagePicture(); break;
            case "look": pageLook(); break;
            case "display": pageDisplay(); break;
            case "connect": pageConnect(); break;
            case "system": pageSystem(); break;
            case "apps": pageApps(); break;
            default: pageHome();
        }
    }

    private String pageTitle(String p) {
        switch (p) {
            case "overview": return "Overview";
            case "live": return "Live";
            case "perf": return "Performance details";
            case "oc": return "Overclock";
            case "profiles": return "Profiles";
            case "memory": return "Memory and storage";
            case "picture": return "Picture";
            case "look": return "Look";
            case "display": return "Display and touch";
            case "connect": return "Connections";
            case "apps": return "App speed";
            default: return "System";
        }
    }

    // ---------- two panes: the preset dial, live card and section list on the left, the chosen section on the right ----------
    private TextView titleView;

    /** Two panes: a rail of icons on the left, the section on the right under a header with its name, the status and the search box. */
    private void buildWide(int wDp, int hDp) {
        if (!entryIds().contains(section)) section = "overview";
        int contentDp = Layouts.contentWidth(wDp);
        int settingCols = Layouts.settingColumns(contentDp);
        appsCols = Layouts.appColumns(contentDp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(surface());

        // left: the rail
        LinearLayout rail = new LinearLayout(this); rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(0, px(14), 0, px(14));
        for (String[] e : entries()) if (!e[0].equals("profiles")) rail.addView(railItem(e[0]), new LinearLayout.LayoutParams(-1, -2));
        leftSv = new ScrollView(this); leftSv.setVerticalScrollBarEnabled(false); leftSv.addView(rail);
        root.addView(leftSv, new LinearLayout.LayoutParams(px(Layouts.leftWidth(wDp)), -1));
        View rule = new View(this); rule.setBackgroundColor(line());
        root.addView(rule, new LinearLayout.LayoutParams(1, -1));

        // right: header, then the selected section
        cols = settingCols;
        rightSv = new ScrollView(this);
        rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setPadding(px(Layouts.PANE_PAD), px(8), px(Layouts.PANE_PAD), px(barWanted() ? 128 : 48));
        rightSv.addView(rightCol, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(Layouts.PANE_PAD), px(14), px(Layouts.PANE_PAD), px(4));
        titleView = text("", 30, true, ink());
        head.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        status = text(st.isEmpty() ? "Waiting for the tuner service…" : "", 13, false, dim());
        status.setMaxWidth(px(180)); status.setGravity(Gravity.END);
        head.addView(status);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(360), -2); sl.setMargins(px(16), 0, 0, 0);
        head.addView(searchBar(), sl);
        LinearLayout rightBox = new LinearLayout(this); rightBox.setOrientation(LinearLayout.VERTICAL);
        rightBox.addView(head, new LinearLayout.LayoutParams(-1, -2));
        rightBox.addView(rightSv, new LinearLayout.LayoutParams(-1, 0, 1f));
        final FrameLayout rightPane = new FrameLayout(this);
        rightPane.addView(rightBox, new FrameLayout.LayoutParams(-1, -1));
        root.addView(rightPane, new LinearLayout.LayoutParams(0, -1, 1f));
        fillRight();
        if (barWanted()) {
            barView = saveBar(true);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2); bl.gravity = Gravity.BOTTOM; bl.setMargins(px(12), 0, px(12), px(10));
            rightPane.addView(barView, bl);
            barView.setVisibility("overview".equals(section) ? View.VISIBLE : View.GONE);
        }
        rootView = root;
        if (!query.isEmpty()) onQuery();
        setContentView(root);
    }

    private static final String[][] RAIL = {{"overview", "Overview", "home"}, {"live", "Live", "live"}, {"perf", "Speed", "perf"}, {"oc", "Overclock", "oc"}, {"apps", "Apps", "apps"},
        {"picture", "Picture", "picture"}, {"look", "Look", "look"}, {"display", "Display", "display"}, {"memory", "Memory", "memory"}, {"connect", "Network", "connect"}, {"system", "System", "system"}};

    /** One stop of the rail: an icon on a pill (filled when selected) and a short name under it. The pill is what entryRows keeps. */
    private View railItem(final String id) {
        String label = id, glyph = id;
        for (String[] r : RAIL) if (r[0].equals(id)) { label = r[1]; glyph = r[2]; }
        LinearLayout it = new LinearLayout(this); it.setOrientation(LinearLayout.VERTICAL); it.setGravity(Gravity.CENTER_HORIZONTAL);
        it.setPadding(0, px(6), 0, px(6)); it.setMinimumHeight(px(64));
        FrameLayout pill = new FrameLayout(this);
        pill.addView(new GlyphView(this, glyph, ink(), 0), new FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER));
        it.addView(pill, new LinearLayout.LayoutParams(px(56), px(32)));
        TextView t = text(label, 11, true, dim()); t.setGravity(Gravity.CENTER); t.setSingleLine(true); t.setPadding(0, px(3), 0, 0);
        it.addView(t, new LinearLayout.LayoutParams(-1, -2));
        it.setContentDescription(label);
        it.setOnClickListener(v -> selectSection(id));
        entryRows.put(id, pill);
        markEntry(pill, id.equals(section));
        return it;
    }

    /** id, title, summary of the section list, in order. */
    private String[][] entries() {
        return new String[][]{
            {"overview", "Overview", overviewSummary()}, {"live", "Live", liveSummary()}, {"perf", "Performance details", perfSummary()}, {"oc", "Overclock", ocSummary()}, {"profiles", "Profiles", profileSummary()},
            {"apps", "App speed", "Compile state of every app, compile or reset one"},
            {"picture", "Picture", pictureSummary()}, {"look", "Look", lookSummary()}, {"display", "Display and touch", displaySummary()},
            {"memory", "Memory and storage", memorySummary()}, {"connect", "Connections", connectSummary()},
            {"system", "System", get("status", "Shortcuts, logging, restart")}};
    }
    private java.util.List<String> entryIds() { ArrayList<String> r = new ArrayList<>(); for (String[] e : entries()) r.add(e[0]); return r; }

    private void sectionEntries() {
        LinearLayout c = null;
        for (final String[] e : entries()) {
            if (e[0].equals("overview")) c = group(null); else if (e[0].equals("live")) c = group("Speed"); else if (e[0].equals("picture")) c = group("Screen"); else if (e[0].equals("memory")) c = group("Device");
            final String id = e[0];
            View r = row(c, e[1], e[2], null);
            TextView sub = (TextView) ((LinearLayout) ((LinearLayout) r).getChildAt(0)).getChildAt(1);   // one line each keeps the list short
            sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.setOnClickListener(v -> selectSection(id));
            entryRows.put(id, r);
            markEntry(r, id.equals(section));
        }
    }
    private void markEntry(View r, boolean on) {
        r.setSelected(on);
        if (radii.containsKey(r)) { paintTile(r, on ? secContainer() : card()); return; }
        GradientDrawable pg = new GradientDrawable(); pg.setColor(on ? secContainer() : 0); pg.setCornerRadius(px(16)); r.setBackground(pg);   // a rail pill
    }

    /** Swaps the right pane with a short crossfade (none when animations are off). The selection is remembered in section. */
    private void selectSection(String id) {
        if (rightSv == null || id.equals(section)) return;
        section = id; builtKey = viewKey();
        for (java.util.Map.Entry<String, View> e : entryRows.entrySet()) markEntry(e.getValue(), e.getKey().equals(id));
        final int tok = ++swapTok;
        final ScrollView sv = rightSv;
        sv.animate().cancel();
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { sv.setAlpha(1f); fillRight(); sv.scrollTo(0, 0); return; }
        sv.animate().alpha(0f).setDuration(75).withEndAction(() -> {
            if (tok != swapTok || sv != rightSv) return;
            fillRight(); sv.scrollTo(0, 0);
            sv.animate().alpha(1f).setDuration(75).start();
        }).start();
    }

    private void fillRight() {
        if (barView != null) barView.setVisibility("overview".equals(section) ? View.VISIBLE : View.GONE);
        resetPageRefs();
        rightCol.removeAllViews();
        col = rightCol;
        if (titleView != null) titleView.setText(pageTitle(section));
        buildPage(section);
    }

    // ---------- search ----------
    private EditText searchBox;

    private LinearLayout.LayoutParams searchLp() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, px(10), 0, px(6)); return lp; }

    private View searchBar() {
        LinearLayout w = new LinearLayout(this);
        w.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(line()); g.setCornerRadius(px(28));
        w.setBackground(g);
        w.setPadding(px(20), 0, px(6), 0);
        final EditText e = new EditText(this);
        e.setBackground(null); e.setHint("Search settings"); e.setHintTextColor(dim()); e.setTextColor(ink()); e.setTextSize(16);
        e.setSingleLine(true); e.setImeOptions(EditorInfo.IME_ACTION_SEARCH); e.setMinHeight(px(52));
        e.setText(query); e.setContentDescription("Search settings");
        final TextView x = text("✕", 18, false, dim());
        x.setPadding(px(14), px(10), px(14), px(10)); x.setMinWidth(px(48)); x.setMinHeight(px(48)); x.setGravity(Gravity.CENTER); x.setContentDescription("Clear search"); x.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        x.setOnClickListener(v -> e.setText(""));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String n = s.toString().trim();
                x.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
                if (n.equals(query)) return;
                query = n;
                if (!n.isEmpty()) buildIndex();
                onQuery();
            }
        });
        e.setOnEditorActionListener((v, a, ev) -> { hideKeyboard(); return true; });
        w.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
        w.addView(x);
        searchBox = e;
        return w;
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager m = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (m != null && rootView != null) m.hideSoftInputFromWindow(rootView.getWindowToken(), 0);
    }

    /**
     * Collects every row of every page (except the app list, which is built from the package list) by building the pages into a scratch
     * column, so the index never drifts from the screens. The page refs the scratch build sets are put back afterwards.
     */
    private void buildIndex() {
        if (!index.isEmpty()) return;
        LinearLayout sCol = col; int sCols = cols;
        TextView a = liveCpu, b = liveGpu, c = liveMem, d = liveHeat, e = compileView, f = ocView;
        LinearLayout pb = progBox; TextView pt = progTitle, px = progText; ProgressBar pg = progBar;
        collecting = true; col = new LinearLayout(this); cols = 1;
        try {
            for (String[] en : entries()) index.add(new String[]{en[0], en[1], en[2], ""});
            for (String[] en : entries()) { if (en[0].equals("apps") || en[0].equals("live") || en[0].equals("overview")) continue; collPage = en[0]; collGroup = null; col.removeAllViews(); buildPage(en[0]); }
        } finally {
            collecting = false; col = sCol; cols = sCols;
            liveCpu = a; liveGpu = b; liveMem = c; liveHeat = d; compileView = e; ocView = f;
            progBox = pb; progTitle = pt; progText = px; progBar = pg;
        }
    }

    /** The query changed: show the results where the settings are (the home body in one pane, the right pane in two), or put the page back. */
    private void onQuery() {
        boolean q = !query.isEmpty();
        if (wide) {
            if (rightCol == null) return;
            for (java.util.Map.Entry<String, View> en : entryRows.entrySet()) markEntry(en.getValue(), !q && en.getKey().equals(section));
            if (!q) { fillRight(); return; }
            rightSv.animate().cancel(); rightSv.setAlpha(1f); resetPageRefs(); rightCol.removeAllViews();
            if (titleView != null) titleView.setText("Search");
            fillResults(rightCol);
        } else if (homeBody != null) {
            homeBody.setVisibility(q ? View.GONE : View.VISIBLE);
            resultsBox.setVisibility(q ? View.VISIBLE : View.GONE);
            resultsBox.removeAllViews();
            if (q) fillResults(resultsBox);
        }
    }

    private void fillResults(LinearLayout host) {
        ArrayList<String[]> hits = new ArrayList<>();
        for (String[] it : index) if (it[1].equals(pageTitle(it[0])) ? Search.matches(query, it[1]) : Search.matches(query, it[1], it[2], pageTitle(it[0]), it[3])) hits.add(it);   // a page matches on its name only: its summary is just the current values
        Collections.sort(hits, (x, y) -> Search.rank(query, x[1]) - Search.rank(query, y[1]));
        if (hits.isEmpty()) {
            TextView t = text("No settings match \u201c" + query + "\u201d.", 15, false, dim());
            t.setPadding(px(10), px(16), px(10), 0); host.addView(t);
            return;
        }
        LinearLayout c = newCard();
        host.addView(c, new LinearLayout.LayoutParams(-1, -2));
        int n = 0;
        for (final String[] it : hits) {
            if (++n > 40) break;
            boolean pageItem = it[1].equals(pageTitle(it[0]));
            String sub = pageItem ? it[2] : pageTitle(it[0]) + (it[3].isEmpty() ? "" : " \u203a " + it[3]);
            row(c, it[1], sub, text("\u203a", 22, false, dim())).setOnClickListener(v -> openResult(it[0], it[1]));
        }
    }

    /** Opens the page of a result and scrolls to its row, which flashes once. */
    private void openResult(String p, String title) {
        hideKeyboard();
        query = ""; focusTitle = title.equals(pageTitle(p)) ? null : title;
        if (searchBox != null) searchBox.setText("");
        if (!wide) { go(p); return; }
        section = p; builtKey = viewKey();
        for (java.util.Map.Entry<String, View> en : entryRows.entrySet()) markEntry(en.getValue(), en.getKey().equals(p));
        fillRight(); rightSv.scrollTo(0, 0); runFocus();
    }

    private void runFocus() {
        final String t = focusTitle; focusTitle = null;
        final ScrollView sv = wide ? rightSv : mainSv;
        if (t == null || sv == null) return;
        sv.post(() -> {
            final View v = findTag(sv, t);
            if (v == null) return;
            int y = 0; View c = v;
            while (c != null && c != sv) { y += c.getTop(); c = c.getParent() instanceof View ? (View) c.getParent() : null; }
            sv.scrollTo(0, Math.max(0, y - px(140)));
            paintTile(v, secContainer());
            ui.postDelayed(() -> paintTile(v, card()), 1600);
        });
    }
    private static View findTag(View v, String t) {
        if (t.equals(v.getTag())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { View r = findTag(((ViewGroup) v).getChildAt(i), t); if (r != null) return r; }
        return null;
    }

    // ---------- live readout (read straight from sysfs; the tuner's ROM runs permissive, elsewhere the cells show a dash) ----------
    private static String rd(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) { String l = r.readLine(); return l == null ? "" : l.trim(); }
        catch (Exception e) { return ""; }
    }
    private static String grep(String path, String key) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String l; while ((l = r.readLine()) != null) if (l.contains(key)) return l;
        } catch (Exception ignored) { }
        return "";
    }
    private static long num(String s) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s); return m.find() ? Long.parseLong(m.group(1)) : -1; }
    private static String ghz(long khz) { return khz < 0 ? "–" : String.format(Locale.US, "%.2f", khz / 1e6); }

    // the latest readings and the last minute of them (one sample a second, whether or not a live view is on screen)
    private long curFast = -1, curSlow = -1, curGpu = -1, curDdr = -1, curAvail = -1, curTotal = -1, curTemp = -1;
    private static final int WINDOW = 90;
    private final Hist hFast = new Hist(WINDOW), hSlow = new Hist(WINDOW), hGpu = new Hist(WINDOW), hTemp = new Hist(WINDOW), hMem = new Hist(WINDOW);
    private float cpuMaxGhz = -1; private float[] cpuRules = new float[0];

    private void sample() {
        String c = "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq";
        curFast = num(rd(String.format(c, 0))); curSlow = num(rd(String.format(c, 4)));
        curGpu = num(grep("/proc/gpufreq/gpufreq_var_dump", "g_cur_opp_freq"));
        curDdr = num(grep("/sys/devices/platform/10012000.dvfsrc/helio-dvfsrc/dvfsrc_dump", "DDR"));
        curAvail = num(grep("/proc/meminfo", "MemAvailable")); curTotal = num(grep("/proc/meminfo", "MemTotal"));
        long t = -1;
        for (int i = 0; i < 12 && t < 0; i++) if ("mtktscpu".equals(rd("/sys/class/thermal/thermal_zone" + i + "/type"))) t = num(rd("/sys/class/thermal/thermal_zone" + i + "/temp"));
        curTemp = t;
        hFast.add(curFast < 0 ? 0 : curFast / 1e6f); hSlow.add(curSlow < 0 ? 0 : curSlow / 1e6f);
        hGpu.add(curGpu < 0 ? 0 : curGpu / 1000f); hTemp.add(curTemp < 0 ? 0 : curTemp / 1000f); hMem.add(curAvail < 0 ? 0 : curAvail / 1048576f);
    }

    private void refreshLive() {
        if (liveCpu == null) return;
        liveCpu.setText(ghz(curFast) + " · " + ghz(curSlow));
        liveGpu.setText(curGpu < 0 ? "–" : (curGpu / 1000) + " MHz");
        liveMem.setText((curDdr < 0 ? "–" : (curDdr / 1000) + " MHz") + (curAvail < 0 || curTotal < 0 ? "" : "\n" + String.format(Locale.US, "%.1f of %.1f GB free", curAvail / 1048576f, curTotal / 1048576f)));
        liveHeat.setText(curTemp < 0 ? "–" : String.format(Locale.US, "%.0f °C", curTemp / 1000f));
    }


    // ---------- Overview: the last 90 seconds of the chip, the five modes, and what the chosen mode changes ----------
    private LiveGraph[] ovG = new LiveGraph[3];
    private TextView[] ovV = new TextView[4];

    private int priC() { return sys(android.R.color.system_accent1_700, android.R.color.system_accent1_100); }
    private int onPriC() { return sys(android.R.color.system_accent1_100, android.R.color.system_accent1_900); }

    private String overviewSummary() {
        String cur = get("preset", "balanced");
        for (String[] pr : PRESETS) if (pr[0].equals(cur)) return pr[1] + ", " + (curTemp < 0 ? "–" : Math.round(curTemp / 1000f) + " °C");
        return "Custom, " + (curTemp < 0 ? "–" : Math.round(curTemp / 1000f) + " °C");
    }

    private void pageOverview() {
        cpuScale();
        int fastC = accent(), slowC = tone(android.R.color.system_accent2_300, android.R.color.system_accent2_600),
            gpuC = tone(android.R.color.system_accent3_300, android.R.color.system_accent3_600), warm = 0xFFFF8A4C;
        int rule = (dim() & 0xFFFFFF) | 0x40000000;

        // scope: four readings over one chart (CPU on a GHz scale, GPU on its own scale, temperature as a filled area)
        LinearLayout sc = plainCard();
        sc.setPadding(px(20), px(18), px(20), px(14));
        LinearLayout vals = new LinearLayout(this);
        String[] names = {"Fast cores", "Slow cores", "GPU", "Chip"};
        int[] cs = {fastC, slowC, gpuC, warm};
        for (int i = 0; i < 4; i++) {
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
            LinearLayout lab = new LinearLayout(this); lab.setGravity(Gravity.CENTER_VERTICAL);
            View dash = new View(this); GradientDrawable dg = new GradientDrawable(); dg.setColor(cs[i]); dg.setCornerRadius(px(2)); dash.setBackground(dg);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(16), px(4)); dl.setMargins(0, 0, px(6), 0);
            lab.addView(dash, dl); lab.addView(text(names[i], 12.5f, false, dim()));
            ovV[i] = text("–", 26, true, ink());
            cell.addView(lab); cell.addView(ovV[i]);
            vals.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        sc.addView(vals);
        FrameLayout fl = new FrameLayout(this);
        for (int i = 0; i < 3; i++) { ovG[i] = new LiveGraph(this); ovG[i].setHeightDp(wide ? 120 : 190); }
        ovG[2].setup(new Hist[]{hTemp}, new int[]{warm}, 20, 90, new float[0], 85, rule, 0xAAFF5A4C, true);
        ovG[0].setup(new Hist[]{hFast, hSlow}, new int[]{fastC, slowC}, 0, cpuMaxGhz, cpuRules, -1, rule, 0, false);
        ovG[1].setup(new Hist[]{hGpu}, new int[]{gpuC}, 300, 900, new float[0], -1, rule, 0, false);
        fl.addView(ovG[2], new FrameLayout.LayoutParams(-1, -2)); fl.addView(ovG[0], new FrameLayout.LayoutParams(-1, -2)); fl.addView(ovG[1], new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2); fp.setMargins(0, px(10), 0, 0);
        sc.addView(fl, fp);
        LinearLayout axis = new LinearLayout(this);
        axis.addView(text("90 s ago", 12, false, dim()), new LinearLayout.LayoutParams(0, -2, 1f));
        axis.addView(text("now", 12, false, dim()));
        sc.addView(axis);
        sc.setOnClickListener(v -> go("live"));
        sc.setContentDescription("Live clocks. Opens the Live page.");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.setMargins(0, px(12), 0, px(4));
        col.addView(sc, sp);
        refreshOverview();

        // modes
        String cur = get("preset", "balanced");
        int idx = -1;
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i][0].equals(cur)) idx = i;
        TextView q = text("How should it run?", wide ? 26 : 28, true, ink());
        q.setPadding(px(6), px(22), 0, px(2)); col.addView(q);
        TextView qs = text(idx >= 0 ? "Pick a mode. The list under it shows what it changes." : "Your own settings are active. Pick a mode to start over.", 14, false, dim());
        qs.setPadding(px(6), 0, 0, px(10)); col.addView(qs);
        modeCarousel(idx);

        // what the mode changes, against Balanced
        changesPanel(idx);
        if (!wide) fineTuneGrid();
    }

    private static final int[][] METERS = {{1, 5, 1}, {3, 3, 2}, {4, 3, 3}, {5, 2, 4}, {5, 1, 5}};   // speed, battery, heat out of 5

    private void modeCarousel(final int idx) {
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        String[] meterNames = {"Speed", "Battery", "Heat"};
        final int shownIdx = idx < 0 ? PRESETS.length : idx;   // the Custom card is the sixth
        final int perRow = wide && Layouts.contentWidth(getResources().getConfiguration().screenWidthDp) >= 1000 ? 6 : 3;
        for (int i = 0; i <= PRESETS.length; i++) {
            final int n = i; boolean sel = i == shownIdx, custom = i == PRESETS.length;
            LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(px(wide ? 16 : 20), px(wide ? 14 : 18), px(wide ? 16 : 20), px(wide ? 14 : 18));
            GradientDrawable g = new GradientDrawable(); g.setColor(sel ? priC() : card()); g.setCornerRadius(px(28));
            c.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple()), g, null));
            int fg = sel ? onPriC() : ink(), fg2 = sel ? onPriC() : dim();
            LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(new GlyphView(this, custom ? "custom" : "m" + i, sel ? onAccent() : fg2, sel ? accent() : line()), new LinearLayout.LayoutParams(px(wide ? 38 : 44), px(wide ? 38 : 44)));
            View sp = new View(this); top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
            if (sel) {
                TextView chip = text("✓ In use", 12, true, onAccent());
                GradientDrawable cg = new GradientDrawable(); cg.setColor(accent()); cg.setCornerRadius(px(14)); chip.setBackground(cg); chip.setPadding(px(10), px(3), px(10), px(3));
                top.addView(chip);
            }
            c.addView(top, new LinearLayout.LayoutParams(-1, -2));
            String ap = get("profile_active", ""), pb = get("profile_base", "");
            TextView nm = text(custom ? "Custom" : PRESETS[i][1], wide ? (perRow == 6 ? 17 : 19) : 22, true, fg); nm.setPadding(0, px(wide ? 8 : 12), 0, 0); c.addView(nm);
            String desc = custom ? (!ap.isEmpty() ? "Profile \u201c" + Profiles.shown(ap) + "\u201d is in use." : !pb.isEmpty() ? "Based on \u201c" + Profiles.shown(pb) + "\u201d, changed." : "Your own settings and your saved profiles.") : PRESETS[i][2];
            TextView ds = text(desc, wide ? 12.5f : 13, false, fg2); ds.setMinLines(wide ? (perRow == 6 ? 5 : 3) : 4); ds.setPadding(0, px(4), 0, px(wide ? 6 : 10)); c.addView(ds);
            if (custom) {
                String[] ps = get("profiles", "").split(","); int cnt = 0; for (String q : ps) if (!q.isEmpty()) cnt++;
                LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.setMargins(0, px(6), 0, 0);
                c.addView(text(cnt == 0 ? "No saved profiles yet" : cnt + (cnt == 1 ? " saved profile" : " saved profiles"), 13, true, fg), ml);
                c.addView(text("Tap to open the list", 12.5f, false, fg2), ml);
            } else for (int m = 0; m < 3; m++) {
                LinearLayout mr = new LinearLayout(this); mr.setGravity(Gravity.CENTER_VERTICAL);
                mr.addView(text(meterNames[m], 12.5f, false, fg2), new LinearLayout.LayoutParams(0, -2, 1f));
                for (int k = 0; k < 5; k++) {
                    View seg = new View(this); GradientDrawable sg = new GradientDrawable(); sg.setCornerRadius(px(3));
                    sg.setColor(k < METERS[i][m] ? (m == 2 ? 0xFFFF8A4C : (sel ? onPriC() : accent())) : (sel ? (onPriC() & 0xFFFFFF) | 0x44000000 : line()));
                    seg.setBackground(sg);
                    LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(wide ? (perRow == 6 ? 12 : 18) : 16), px(6)); sl.setMargins(px(3), 0, 0, 0);
                    mr.addView(seg, sl);
                }
                LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.setMargins(0, px(6), 0, 0);
                c.addView(mr, ml);
            }
            c.setOnClickListener(v -> { if (custom) showProfilesSheet(); else if (n != idx) { set("preset", PRESETS[n][0]); build(); } });
            c.setContentDescription((custom ? "Custom. " + desc : PRESETS[i][1] + ". " + PRESETS[i][2]) + (sel ? " In use." : ""));
            if (wide) {   // all six visible without scrolling: in one row when the pane is wide, else three across
                LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -1, 1f); wl.setMargins(i % perRow == 0 ? 0 : px(5), 0, i % perRow == perRow - 1 ? 0 : px(5), 0);
                if (i % perRow == 0) { row = new LinearLayout(this); row.setBaselineAligned(false); LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.setMargins(0, i == 0 ? 0 : px(10), 0, 0); col.addView(row, rl); }
                row.addView(c, wl);
                continue;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(220), -2); lp.setMargins(0, 0, px(10), 0);
            row.addView(c, lp);
        }
        if (wide) return;
        hs.addView(row);
        col.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        if (shownIdx > 1) hs.post(() -> hs.scrollTo(Math.max(0, (shownIdx - 1) * px(230)), 0));
    }

    /** The saved profiles in a sheet from the bottom: tap to apply, Delete, update the one in use, save the current settings. */
    private void showProfilesSheet() {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(sys(android.R.color.system_neutral1_800, android.R.color.system_neutral1_50)); bg.setCornerRadii(new float[]{px(28), px(28), px(28), px(28), 0, 0, 0, 0});
        box.setBackground(bg); box.setPadding(px(8), px(20), px(8), px(24));
        TextView title = text("Profiles", 22, true, ink()); title.setPadding(px(20), 0, 0, px(8)); box.addView(title);
        LinearLayout list = newCard(); box.addView(list, new LinearLayout.LayoutParams(-1, -2));
        String active = get("profile_active", "");
        row(list, "Save current settings\u2026", "Keeps performance, memory and picture settings under a name", text("＋", 22, true, accent())).setOnClickListener(v -> { d.dismiss(); askSaveProfile(); });
        if (canUpdateProfile()) {
            final String base = get("profile_base", "");
            row(list, "Update \u201c" + Profiles.shown(base) + "\u201d", "Overwrites the saved profile with the current settings", text("Update", 15, true, accent()))
                .setOnClickListener(v -> { d.dismiss(); set("update_profile", base); st.put("profile_active", base); build(); });
        }
        for (final String n : get("profiles", "").split(",")) {
            if (n.isEmpty()) continue;
            final String shown = Profiles.shown(n);
            TextView del = text("Delete", 14, true, accent());
            del.setPadding(px(12), px(8), px(4), px(8)); del.setMinHeight(px(48)); del.setMinWidth(px(48)); del.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Delete the profile \"" + shown + "\"?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (dd, w) -> { d.dismiss(); set("delete_profile", n); }).show());
            row(list, n.equals(active) ? shown + "  \u2713" : shown, n.equals(active) ? "In use" : "Tap to apply", del).setOnClickListener(v -> { d.dismiss(); set("apply_profile", n); });
        }
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) { w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); w.setGravity(Gravity.BOTTOM); w.setLayout(Math.min(px(680), getResources().getDisplayMetrics().widthPixels), -2); }
        d.show();
    }

    /** The bar under the overview: what is in use, and one button to keep it as a profile. */
    /** The bar is only there when there is something to save (a profile in use that matches has nothing). */
    private boolean barWanted() { return get("profile_active", "").isEmpty(); }

    private View saveBar(boolean floating) {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(sys(android.R.color.system_neutral1_800, android.R.color.system_neutral1_50)); g.setCornerRadii(new float[]{px(28), px(28), px(28), px(28), floating ? px(28) : 0, floating ? px(28) : 0, floating ? px(28) : 0, floating ? px(28) : 0});
        bar.setBackground(g); bar.setElevation(px(8));
        bar.setPadding(px(24), px(14), px(20), px(14));
        String ap = get("profile_active", ""), pb = get("profile_base", ""), cur = get("preset", "balanced"), t, sub;
        if (!ap.isEmpty()) { t = "Profile \u201c" + Profiles.shown(ap) + "\u201d in use"; sub = "No changes"; }
        else if (!pb.isEmpty() && canUpdateProfile()) { t = "Profile \u201c" + Profiles.shown(pb) + "\u201d in use"; sub = "Changed since you applied it"; }
        else { t = "Custom settings"; sub = "Not saved as a profile"; for (String[] pr : PRESETS) if (pr[0].equals(cur)) { t = pr[1] + " mode"; sub = "Keep these settings as a profile"; } }
        LinearLayout texts = new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(t, 17, true, ink())); texts.addView(text(sub, 13.5f, false, dim()));
        bar.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView b = text("Save as profile", 16, true, onAccent()); b.setGravity(Gravity.CENTER); b.setMinHeight(px(52)); b.setPadding(px(26), 0, px(26), 0);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(accent()); bg.setCornerRadius(px(26)); b.setBackground(bg);
        b.setOnClickListener(v -> {
            if (canUpdateProfile()) {
                final String base = get("profile_base", "");
                new AlertDialog.Builder(this).setItems(new String[]{"Update \u201c" + Profiles.shown(base) + "\u201d", "Save as a new profile\u2026"}, (d, i) -> {
                    if (i == 0) { set("update_profile", base); st.put("profile_active", base); build(); } else askSaveProfile();
                }).show();
            } else askSaveProfile();
        });
        bar.addView(b);
        return bar;
    }

    private void fineTuneGrid() {
        TextView h = text("Fine tune", 14, true, accent()); h.setPadding(px(20), px(24), 0, px(8)); col.addView(h);
        String[][] t = {{"live", "Live", liveSummary()}, {"perf", "Performance", perfSummary()}, {"oc", "Overclock", ocSummary()}, {"apps", "App speed", "Compile state of every app"},
            {"picture", "Picture", pictureSummary()}, {"look", "Look", lookSummary()}, {"display", "Display and touch", displaySummary()},
            {"memory", "Memory and storage", memorySummary()}, {"connect", "Connections", connectSummary()}, {"system", "System", get("status", "Shortcuts, logging, restart")}};
        for (int i = 0; i < t.length; i += 2) {
            LinearLayout r = new LinearLayout(this); r.setBaselineAligned(false);
            for (int k = 0; k < 2; k++) {
                final String[] e = t[i + k];
                LinearLayout tile = new LinearLayout(this); tile.setGravity(Gravity.CENTER_VERTICAL);
                tile.setPadding(px(18), px(14), px(14), px(14)); tile.setMinimumHeight(px(84));
                GradientDrawable g = new GradientDrawable(); g.setColor(card()); g.setCornerRadius(px(24));
                tile.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple()), g, null));
                tile.addView(new GlyphView(this, e[0].equals("live") ? "live" : e[0], onSecContainer(), secContainer()), new LinearLayout.LayoutParams(px(46), px(46)));
                LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(px(14), 0, 0, 0);
                tx.addView(text(e[1], 16, true, ink()));
                TextView sm = text(e[2], 13, false, dim()); sm.setSingleLine(true); sm.setEllipsize(android.text.TextUtils.TruncateAt.END); tx.addView(sm);
                tile.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
                tile.setOnClickListener(v -> go(e[0]));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f); lp.setMargins(k == 0 ? 0 : px(4), 0, k == 0 ? px(4) : 0, px(8));
                r.addView(tile, lp);
            }
            col.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    // key, Balanced value; the daemon's Balanced preset is the baseline the other modes are compared with
    private static final String[][] BASE = {{"gpu_freq", "680000"}, {"oc", "0"}, {"governor", "schedplus"}, {"thermal", "stock"}, {"fg_boost", "auto"}, {"ta_boost", "0"},
        {"cap_big", "-1"}, {"cap_little", "-1"}, {"dirty", "1"}, {"swappiness", "100"}, {"extra_free", "49152"}};

    private static String keyLabel(String k) {
        switch (k) {
            case "gpu_freq": return "GPU clock"; case "oc": return "Max clocks"; case "governor": return "CPU governor"; case "thermal": return "Thermal limit";
            case "fg_boost": return "App boost"; case "ta_boost": return "Top app boost"; case "cap_big": return "Fast cores, top speed"; case "cap_little": return "Slow cores, top speed";
            case "dirty": return "Write buffer"; case "swappiness": return "Swappiness"; default: return "Free memory reserve";
        }
    }
    private static String keyValue(String k, String v) {
        try {
            switch (k) {
                case "gpu_freq": return "0".equals(v) ? "Auto" : (Integer.parseInt(v) / 1000) + " MHz";
                case "oc": return "1".equals(v) ? "On" : "Off";
                case "thermal": return "off".equals(v) ? "Off" : "Stock";
                case "cap_big": case "cap_little": return "-1".equals(v) ? "No limit" : String.format(Locale.US, "%.2f GHz", Integer.parseInt(v) / 1e6);
                case "dirty": return v + " %";
                case "extra_free": return (Integer.parseInt(v) / 1024) + " MB";
                default: return v.isEmpty() ? v : v.substring(0, 1).toUpperCase(Locale.US) + v.substring(1);
            }
        } catch (NumberFormatException e) { return v; }
    }

    private void changesPanel(int idx) {
        LinearLayout p = plainCard();
        p.setPadding(px(22), px(18), px(22), px(10));
        ArrayList<String[]> diff = new ArrayList<>();
        for (String[] b : BASE) { String c = get(b[0], b[1]); if (!c.equals(b[1])) diff.add(new String[]{keyLabel(b[0]), keyValue(b[0], b[1]), keyValue(b[0], c)}); }
        boolean base = idx == 1 && diff.isEmpty();
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(base ? "Balanced is the baseline" : (idx >= 0 ? PRESETS[idx][1] : "Your settings") + " changes", 18, true, ink()));
        if (!base) { TextView sm = text("compared with Balanced", 13, false, dim()); sm.setPadding(px(12), 0, 0, 0); head.addView(sm); }
        p.addView(head);
        if (diff.isEmpty()) {
            TextView t = text(base ? "Everything is at the everyday defaults. Pick another mode and this list shows what it changes." : "Nothing differs from Balanced.", 14, false, dim());
            t.setPadding(0, px(8), 0, px(10)); p.addView(t);
        }
        for (String[] d : diff) {
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setMinimumHeight(px(48));
            r.addView(text(d[0], 15, false, dim()), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView o = text(d[1], 14, false, dim()); o.setPaintFlags(o.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            r.addView(o);
            TextView ar = text("  →  ", 14, false, dim()); r.addView(ar);
            TextView nw = text(d[2], 15, true, accent()); nw.setMinWidth(px(110)); r.addView(nw);
            p.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, px(12), 0, px(4));
        col.addView(p, lp);
    }

    private void refreshOverview() {
        if (ovV[0] == null) return;
        ovV[0].setText(ghz(curFast)); ovV[1].setText(ghz(curSlow));
        ovV[2].setText(curGpu < 0 ? "–" : String.valueOf(curGpu / 1000));
        ovV[3].setText(curTemp < 0 ? "–" : Math.round(curTemp / 1000f) + "°");
        if (ovG[1] != null) ovG[1].setRange(300, Math.max(900f, hGpu.max() + 50), new float[0]);
        for (LiveGraph g : ovG) if (g != null) g.invalidate();
    }

    private String liveSummary() {
        return curFast < 0 ? "Clocks, temperature and memory over the last minute"
            : "CPU " + ghz(curFast) + " · " + ghz(curSlow) + " GHz, GPU " + (curGpu < 0 ? "–" : (curGpu / 1000) + " MHz") + ", " + (curTemp < 0 ? "–" : Math.round(curTemp / 1000f) + " °C");
    }

    // ---------- Live page: a Graphs tab (the last minute of every clock) and a State tab (what is set and what the hardware is doing) ----------
    private int liveTab;                                   // 0 graphs, 1 state
    private LinearLayout graphBox, stateBox;
    private final ArrayList<LiveGraph> graphs = new ArrayList<>();
    private TextView[] gv = new TextView[6];               // value labels of the graph headers: fast, slow, gpu, temp, memory, spare
    private TextView[] sv2 = new TextView[3];              // live values of the state rows: gpu, battery, memory

    private int tone(int a1, int a2) { return getColor(night ? a1 : a2); }

    private static String readOr(String path, String def) { String v = rd(path); return v.isEmpty() ? def : v; }

    /** Max clock and the table of clock steps, read once; the steps become the faint rules of the CPU graph. */
    private void cpuScale() {
        if (cpuMaxGhz > 0) return;
        long m = Math.max(num(rd("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")), num(rd("/sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_max_freq")));
        cpuMaxGhz = m > 0 ? m / 1e6f : 2.3f;
        ArrayList<Float> r = new ArrayList<>();
        for (String f : rd("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies").split("\\s+")) { long k = num(f); if (k > 0) r.add(k / 1e6f); }
        if (r.isEmpty() || r.size() > 12) { r.clear(); for (int i = 1; i <= 3; i++) r.add(cpuMaxGhz * i / 4f); }
        cpuRules = new float[r.size()]; for (int i = 0; i < cpuRules.length; i++) cpuRules[i] = r.get(i);
    }

    private LinearLayout chartCard(String title, LiveGraph g, TextView[] values, int[] colors) {
        LinearLayout c = plainCard();
        c.setPadding(px(20), px(14), px(20), px(10));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(title, 15, true, ink()), new LinearLayout.LayoutParams(0, -2, 1f));
        for (int i = 0; i < values.length; i++) {
            values[i].setTextColor(colors[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(px(14), 0, 0, 0);
            head.addView(values[i], lp);
        }
        c.addView(head);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2); gp.setMargins(0, px(6), 0, 0);
        c.addView(g, gp);
        graphs.add(g);
        return c;
    }
    private TextView big() { return text("–", 20, true, ink()); }

    private void pageLive() {
        cpuScale();
        int fastC = accent(), slowC = tone(android.R.color.system_accent2_300, android.R.color.system_accent2_600),
            gpuC = tone(android.R.color.system_accent3_300, android.R.color.system_accent3_600), warm = 0xFFFF8A4C;
        int rule = (dim() & 0xFFFFFF) | 0x40000000;

        // tab switch
        LinearLayout tabs = new LinearLayout(this);
        GradientDrawable tg = new GradientDrawable(); tg.setColor(card()); tg.setCornerRadius(px(28)); tabs.setBackground(tg);
        tabs.setPadding(px(4), px(4), px(4), px(4));
        final TextView t0 = text("Graphs", 15, true, ink()), t1 = text("State", 15, true, ink());
        for (TextView t : new TextView[]{t0, t1}) { t.setGravity(Gravity.CENTER); t.setPadding(0, px(10), 0, px(10)); t.setMinHeight(px(48)); tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1f)); }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.setMargins(0, px(10), 0, px(8));
        col.addView(tabs, tp);

        graphBox = new LinearLayout(this); graphBox.setOrientation(LinearLayout.VERTICAL);
        stateBox = new LinearLayout(this); stateBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(graphBox); col.addView(stateBox);

        // graphs
        for (int i = 0; i < gv.length; i++) gv[i] = big();
        LiveGraph gc = new LiveGraph(this); gc.setup(new Hist[]{hFast, hSlow}, new int[]{fastC, slowC}, 0, cpuMaxGhz, cpuRules, -1, rule, 0, false);
        View cpu = chartCard("CPU clock, GHz", gc, new TextView[]{gv[0], gv[1]}, new int[]{fastC, slowC});
        LiveGraph gg = new LiveGraph(this); gg.setup(new Hist[]{hGpu}, new int[]{gpuC}, 0, 800, gpuRules(), -1, rule, 0, true);
        View gpu = chartCard("GPU clock, MHz", gg, new TextView[]{gv[2]}, new int[]{gpuC});
        LiveGraph gt = new LiveGraph(this); gt.setup(new Hist[]{hTemp}, new int[]{warm}, 20, 90, new float[]{40, 60}, 85, rule, 0xAAFF5A4C, true);
        View heat = chartCard("Chip temperature, °C (dashed: 85 limit)", gt, new TextView[]{gv[3]}, new int[]{warm});
        LiveGraph gm = new LiveGraph(this); gm.setup(new Hist[]{hMem}, new int[]{fastC}, 0, Math.max(1f, curTotal / 1048576f), new float[0], -1, rule, 0, true);
        View mem = chartCard("Free memory, GB", gm, new TextView[]{gv[4]}, new int[]{fastC});
        View[] cards = {cpu, gpu, heat, mem};
        if (cols > 1) {
            for (int i = 0; i < cards.length; i += 2) {
                LinearLayout pair = new LinearLayout(this); pair.setBaselineAligned(false);
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f); l.setMargins(0, 0, px(8), 0);
                LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, -2, 1f); r.setMargins(px(8), 0, 0, 0);
                pair.addView(cards[i], l); pair.addView(cards[i + 1], r);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.setMargins(0, 0, 0, px(14));
                graphBox.addView(pair, pp);
            }
        } else for (View v : cards) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, px(14)); graphBox.addView(v, lp); }
        TextView note = text("The last 90 seconds, one reading a second. The lines step because the chip only runs at fixed clock steps (faint rules).", 13, false, dim());
        note.setPadding(px(10), 0, px(10), 0); graphBox.addView(note);

        // state
        LinearLayout saved = col; col = stateBox;
        String presetName = "custom";
        for (String[] pr : PRESETS) if (pr[0].equals(get("preset", "balanced"))) presetName = pr[1];
        String prof = get("profile_active", "");
        LinearLayout c = group("Set now");
        row(c, "Preset", "The dial on the home screen", text(presetName, 15, true, accent()));
        row(c, "Profile", "A saved set of settings", text(prof.isEmpty() ? "none" : prof.replace('_', ' '), 15, true, accent()));
        row(c, "Overclock", null, text(ocSummary(), 15, true, accent()));
        row(c, "Thermal limit", "Chip temperature the kernel throttles at", text("off".equals(get("thermal", "stock")) ? "off" : "stock", 15, true, accent()));
        c = group("Running now");
        sv2[0] = text("–", 15, true, accent()); row(c, "GPU clock", "0 means the clock is not readable", sv2[0]);
        sv2[1] = text("–", 15, true, accent()); row(c, "Battery", null, sv2[1]);
        sv2[2] = text("–", 15, true, accent()); row(c, "Memory", "Memory clock and free RAM", sv2[2]);
        col = saved;

        final Runnable show = () -> {
            graphBox.setVisibility(liveTab == 0 ? View.VISIBLE : View.GONE);
            stateBox.setVisibility(liveTab == 1 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < 2; i++) {
                TextView t = i == 0 ? t0 : t1; boolean on = i == liveTab;
                GradientDrawable g = new GradientDrawable(); g.setColor(on ? secContainer() : 0); g.setCornerRadius(px(24)); t.setBackground(g);
                t.setTextColor(on ? onSecContainer() : dim());
            }
        };
        t0.setOnClickListener(v -> { liveTab = 0; show.run(); });
        t1.setOnClickListener(v -> { liveTab = 1; show.run(); });
        show.run();
        refreshGraphs();
    }

    private float[] gpuRules() {
        String[] o = list("gpu_opps", "680000,500000,400000");
        float[] r = new float[o.length];
        for (int i = 0; i < o.length; i++) { try { r[i] = Integer.parseInt(o[i].trim()) / 1000f; } catch (Exception e) { r[i] = 0; } }
        return r;
    }

    private void refreshGraphs() {
        if (graphBox == null) return;
        if (gv[0] != null) {
            gv[0].setText(ghz(curFast)); gv[1].setText(ghz(curSlow));
            gv[2].setText(curGpu < 0 ? "–" : (curGpu / 1000) + " MHz");
            gv[3].setText(curTemp < 0 ? "–" : String.format(Locale.US, "%.0f °C", curTemp / 1000f));
            gv[4].setText(curAvail < 0 ? "–" : String.format(Locale.US, "%.1f", curAvail / 1048576f));
        }
        if (graphs.size() == 4) {
            String[] names = {"CPU clock", "GPU clock", "Chip temperature", "Free memory"};
            String[] now = {gv[0].getText() + " and " + gv[1].getText() + " gigahertz", gv[2].getText().toString(), gv[3].getText().toString(), gv[4].getText() + " gigabytes"};
            for (int i = 0; i < 4; i++) graphs.get(i).setContentDescription(names[i] + " over the last 90 seconds, now " + now[i]);
        }
        if (graphs.size() == 4) graphs.get(1).setRange(0, Math.max(800f, hGpu.max()), gpuRules());
        for (LiveGraph g : graphs) g.invalidate();
        if (sv2[0] != null) {
            sv2[0].setText(curGpu < 0 ? "–" : (curGpu / 1000) + " MHz");
            String b = rd("/sys/class/power_supply/battery/capacity"), st2 = rd("/sys/class/power_supply/battery/status");
            sv2[1].setText(b.isEmpty() ? "–" : b + " %" + (st2.isEmpty() ? "" : ", " + st2.toLowerCase(Locale.US)));
            sv2[2].setText((curDdr < 0 ? "–" : (curDdr / 1000) + " MHz") + (curAvail < 0 || curTotal < 0 ? "" : ", " + String.format(Locale.US, "%.1f of %.1f GB free", curAvail / 1048576f, curTotal / 1048576f)));
        }
    }

    private static String compileLabel(String s) {
        switch (s) { case "running": return "Running"; case "done": return "Done"; case "missing": return "Not installed"; default: return "Not run"; }
    }
    /** The compile run ends on its own, so the System page re-reads the status key while it is open. */
    private void refreshCompile() {
        if (compileView == null) return;
        Map<String, String> s = liveState();
        if (s != null) compileView.setText(compileLabel(s.getOrDefault("compile_status", "idle")));
    }
    /** The state file as the app reads it, or null while it looks half written (the daemon ends every complete copy with status=). */
    private Map<String, String> liveState() {
        Map<String, String> s = Cfg.load(this);
        return !s.isEmpty() && !s.containsKey("status") ? null : s;
    }

    // ---------- compile progress block (System page "App speed" and the per app page) ----------
    private LinearLayout progBox;
    private TextView progTitle, progText;
    private ProgressBar progBar;

    /** Hidden until the daemon publishes progress keys; an old daemon never does, and the pages look as before. */
    private void progressBlock(LinearLayout card) {
        progBox = new LinearLayout(this);
        progBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(card()); g.setCornerRadius(px(28));
        progBox.setBackground(g);
        progBox.setPadding(px(20), px(14), px(20), px(14));
        progTitle = text("", 16, true, ink());
        progBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progBar.setMax(100);
        progBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
        progBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(line()));
        progBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent()));
        progText = text("", 13, false, dim());
        progBox.addView(progTitle);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, px(8)); bp.setMargins(0, px(10), 0, px(8));
        progBox.addView(progBar, bp);
        progBox.addView(progText);
        progBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, px(10), 0, px(4));
        LinearLayout host = (LinearLayout) card.getParent();   // col, or one of the two columns of a wide pane
        host.addView(progBox, host.indexOfChild(card), lp);
    }

    private String appName(String pkg) {
        ArrayList<App> all = appsAll;
        if (all != null) for (App a : all) if (a.pkg.equals(pkg)) return a.label + " (" + pkg + ")";
        return pkg;
    }

    private void refreshProgress() {
        if (progBox == null) return;
        Map<String, String> s = liveState();
        if (s == null) return;
        Prog p = Prog.parse(s);
        String sum = Prog.summary(s);
        if (p == null && sum.isEmpty()) { progBox.setVisibility(View.GONE); return; }
        progBox.setVisibility(View.VISIBLE);
        if (p == null) {   // no run going on, the daemon left the summary of the last one
            progTitle.setText("Last compile run");
            progBar.setVisibility(View.GONE);
            progText.setText(sum);
            return;
        }
        progBar.setVisibility(View.VISIBLE);
        progBar.setIndeterminate(p.total <= 0);
        progBar.setProgress(p.percent());
        String t = p.current.isEmpty() ? "Compiling apps…" : p.compiling ? "Compiling " + appName(p.current) : "Compiling apps…";
        progTitle.setText(t);
        // the -a step names an app only after it is done, so there it is the last one finished, not the current one
        progText.setText((!p.compiling && !p.current.isEmpty() ? "Last finished: " + appName(p.current) + "\n" : "") + p.detail(System.currentTimeMillis()));
    }
    private void compile(String key) {
        Map<String, String> s = Cfg.load(this);
        if ("running".equals(s.get("compile_status"))) { Toast.makeText(this, "Already running", Toast.LENGTH_SHORT).show(); return; }
        if (!get0(s, "compile_queue").isEmpty() || get0(s, "compile_pkg_status").endsWith(":running")) { Toast.makeText(this, "An app is compiling. Wait for it to finish.", Toast.LENGTH_SHORT).show(); return; }
        Cfg.request(this, key, "1");
        setStatus("Starting…");
    }

    private TextView liveCell(LinearLayout c, String label, float weight) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(px(14), px(12), px(8), px(12));
        cell.addView(text(label, 12.5f, false, dim()));
        TextView v = text("–", 17, true, ink());
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cell.addView(v);
        c.addView(cell, new LinearLayout.LayoutParams(0, -2, weight));
        return v;
    }

    /** Four readouts in one row, or two rows of two in the narrow left pane of the wide layout. */
    private void liveCard(boolean grid) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(grid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(card()); g.setCornerRadius(px(28));
        c.setBackground(g);
        LinearLayout top = c, bottom = c;
        if (grid) {
            top = new LinearLayout(this); bottom = new LinearLayout(this);
            c.setPadding(0, px(4), 0, px(4));
            c.addView(top); c.addView(bottom);
        }
        liveCpu = liveCell(top, "CPU, GHz (fast · slow cores)", grid ? 1f : 1.25f);
        liveGpu = liveCell(top, "GPU", grid ? 1f : 0.8f);
        liveMem = liveCell(bottom, "Memory clock and free RAM", grid ? 1f : 1.3f);
        liveHeat = liveCell(bottom, "Chip temperature", grid ? 1f : 0.8f);
        c.setOnClickListener(v -> go("live"));
        col.addView(c, new LinearLayout.LayoutParams(-1, -2));
        refreshLive();
    }

    // ---------- home ----------
    // ---------- home ----------
    private void pageHome() { pageOverview(); }

    private String memorySummary() {
        return "Read-ahead " + pick(get("readahead", "512"), new String[]{"128", "256", "512", "1024", "2048"}, new String[]{"128 KB", "256 KB", "512 KB", "1 MB", "2 MB"}, get("readahead", "512") + " KB") + ", swappiness " + get("swappiness", "100");
    }
    private String connectSummary() {
        return "Private DNS " + pick(get("dns", "auto"), new String[]{"off", "auto", "google", "cloudflare", "adguard", "quad9"}, new String[]{"off", "automatic", "Google", "Cloudflare", "AdGuard", "Quad9"}, "automatic");
    }
    private String profileSummary() {
        int n = 0; for (String p : get("profiles", "").split(",")) if (!p.isEmpty()) n++;
        String a = get("profile_active", "");
        return (n == 0 ? "None saved" : n + " saved") + (a.isEmpty() ? "" : ", in use: " + a.replace('_', ' '));
    }

    /** Wide layout only: the saved profiles get a section of their own (the narrow home lists them under Overclock). */
    private void pageProfiles() {
        TextView hint = text("Save the current performance, memory and picture settings under a name, then apply them again with one tap.", 13.5f, false, dim());
        hint.setPadding(px(10), px(6), px(10), 0); col.addView(hint);
        profileRows(group(null));
    }

    /** The settings came from a saved profile and were edited since (an old daemon sends no profile_base: no row). */
    private boolean canUpdateProfile() { return Profiles.canUpdate(get("profile_base", ""), get("profile_active", ""), get("profiles", "")); }

    /** Overwrites the profile the settings came from, after a confirmation. */
    private void updateProfileRow(LinearLayout c) {
        final String n = get("profile_base", ""), shown = Profiles.shown(n);
        row(c, "Save changes to \"" + shown + "\"", "Overwrites the saved profile with the current settings", text("Save", 15, true, accent()))
            .setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Overwrite the profile \"" + shown + "\" with the current settings?")
                .setNegativeButton("Cancel", null).setPositiveButton("Overwrite", (d, w) -> {
                    set("update_profile", n); st.put("profile_active", n);
                    Toast.makeText(this, "Saved changes to " + shown, Toast.LENGTH_SHORT).show();
                    build();
                }).show());
    }

    /** Saved profiles: tap to apply, Delete to remove, a row that overwrites the profile in use after edits, and a row that saves the current performance, memory and picture settings. */
    private void profileRows(LinearLayout c) {
        String active = get("profile_active", "");
        if (canUpdateProfile()) updateProfileRow(c);
        for (final String n : get("profiles", "").split(",")) {
            if (n.isEmpty()) continue;
            final String shown = n.replace('_', ' ');
            TextView del = text("Delete", 14, true, accent());
            del.setPadding(px(12), px(8), px(4), px(8)); del.setMinHeight(px(48)); del.setMinWidth(px(48)); del.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Delete the profile \"" + shown + "\"?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> set("delete_profile", n)).show());
            row(c, n.equals(active) ? shown + "  ✓" : shown, n.equals(active) ? "In use" : "Tap to apply", del)
                .setOnClickListener(v -> { set("apply_profile", n); });
        }
        row(c, "Save current settings as a profile", "Keeps performance, memory and picture settings under a name", text("Save", 15, true, accent()))
            .setOnClickListener(v -> askSaveProfile());
    }

    private void askSaveProfile() {
        final EditText name = new EditText(this);
        name.setHint("Profile name"); name.setSingleLine(true); name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        name.setPadding(px(24), px(16), px(24), px(8));
        new AlertDialog.Builder(this).setTitle("Save profile").setView(name).setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (d, w) -> {
                String n = name.getText().toString().trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
                if (n.isEmpty()) { Toast.makeText(this, "Use letters or numbers in the name", Toast.LENGTH_SHORT).show(); return; }
                set("save_profile", n);
            }).show();
    }

    private String perfSummary() {
        String g = get("gpu_freq", "680000");
        return ("1".equals(get("oc", "0")) ? "Max clocks, " : "") + "GPU " + ("0".equals(g) ? "automatic" : (Integer.parseInt(g) / 1000) + " MHz")
            + ", thermal " + ("off".equals(get("thermal", "stock")) ? "limit off" : "stock");
    }
    private String pictureSummary() {
        if (!"2".equals(get("pq_mode", "0"))) return "Factory picture";
        return "Custom: contrast " + get("pq_contrast", "4") + ", saturation " + get("pq_sat", "4") + ", gamma " + get("pq_gamma", "7");
    }
    private String lookSummary() {
        String n = pick(get("night", "yes"), new String[]{"auto", "no", "yes"}, new String[]{"Automatic", "Light", "Dark"}, "Dark");
        return n + ("1".equals(get("black_theme", "1")) ? ", pure black" : "") + ", " + ("home_wallpaper".equals(get("palette_source", "preset")) ? "wallpaper colours" : "custom accent");
    }
    private String displaySummary() {
        return get("anim_scale", "1.0") + "x animations, " + ("gestural".equals(get("nav_mode", "gestural")) ? "gestures" : "three buttons");
    }

    // ---------- pages ----------
    private void pagePerf() {
        if (!wide) liveCard(false);
        TextView hint = text("Changing a setting here turns the preset into Custom. The dial on the " + (wide ? "left" : "home screen") + " sets all of these together.", 13.5f, false, dim());
        hint.setPadding(px(10), px(6), px(10), 0); col.addView(hint);
        LinearLayout cb = group("Apps");
        rowSwitch(cb, "Boost while CarPlay is open", "Runs the Performance preset while DiPlay or Pillion is on screen, then goes back. Only from Battery, Balanced or Smooth; custom settings and profiles are left alone.", "carplay_boost", "0");
        LinearLayout c = group("Clock speed");
        rowChoice(c, "GPU clock", "Locks the PowerVR GPU to one speed. Ignored in Battery.", "gpu_freq", gpuLabels(), gpuValues(), "680000");
        rowChoice(c, "Fast cores, top speed", "Limits the four fast cores", "cap_big",
            new String[]{"No limit", "2.0 GHz", "1.75 GHz", "1.5 GHz", "1.2 GHz", "0.9 GHz"}, new String[]{"-1", "2009000", "1750000", "1484000", "1218000", "900000"}, "-1");
        rowChoice(c, "Efficiency cores, top speed", "Limits the four slower cores", "cap_little",
            new String[]{"No limit", "1.5 GHz", "1.2 GHz", "1.05 GHz", "0.75 GHz"}, new String[]{"-1", "1491000", "1226000", "1050000", "745000"}, "-1");
        c = group("App boost");
        rowChoice(c, "Visible app", "CPU boost for the app on screen. Automatic follows the preset.", "fg_boost",
            new String[]{"Automatic", "0", "10", "20", "30", "40", "60", "80", "100"}, new String[]{"auto", "0", "10", "20", "30", "40", "60", "80", "100"}, "auto");
        rowChoice(c, "Touched app", "Extra boost for the app you are touching", "ta_boost",
            new String[]{"0", "10", "20", "30", "50", "70"}, new String[]{"0", "10", "20", "30", "50", "70"}, "0");
        rowSwitch(c, "Rotate heavy work between fast cores", "Spreads heat", "big_rotation", "0");
        c = group("Power and heat");
        rowChoice(c, "Power mode", "MediaTek power mode and clock floor. The presets choose it for you.", "perf_profile",
            new String[]{"Battery (low power)", "Balanced", "Performance (sports)"}, new String[]{"battery", "balanced", "performance"}, "balanced");
        rowSwitch(c, "Maximum clocks", "Pins both CPU clusters at their top stock speed. Runs hot and drains the battery. More options under Overclock on the home screen.", "oc", "0");
        rowChoice(c, "CPU governor", "Used when Maximum clocks is off", "governor", list("govs", "schedplus,ondemand,interactive,performance,powersave"), list("govs", "schedplus,ondemand,interactive,performance,powersave"), "schedplus");
        rowChoice(c, "Thermal limit", "Off removes MediaTek's heat limit. Not recommended while charging.", "thermal", new String[]{"Stock", "Off"}, new String[]{"stock", "off"}, "stock");
        c = group("Touch and clocks");
        rowChoice(c, "Touch boost length", "How long the clocks stay raised after a touch. Stock is 100 ms.", "touch_duration_ms",
            new String[]{"100 ms (stock)", "200 ms", "400 ms", "800 ms"}, new String[]{"100", "200", "400", "800"}, "400");
        rowChoice(c, "Touch boost clock", "Clock step used during a touch boost. Top clock is the highest, Stock step is the vendor value.", "touch_opp",
            new String[]{"Top clock", "Middle step", "Stock step"}, new String[]{"0", "1", "2"}, "0");
        rowChoice(c, "Touch boost strength", "How hard a touch pushes the clocks up, 0 to 100. Stock is 80.", "touch_boost",
            new String[]{"Off", "20", "40", "60", "80 (stock)", "100"}, new String[]{"0", "20", "40", "60", "80", "100"}, "100");
        rowChoice(c, "Clock drop delay", "How long a clock is held before it is lowered. Longer keeps short bursts smooth. Stock is 4 ms. Needs the schedplus governor.", "down_throttle",
            new String[]{"4 ms (stock)", "10 ms", "20 ms", "40 ms"}, new String[]{"4", "10", "20", "40"}, "20");
        rowChoice(c, "Clock rise delay", "Minimum time between clock increases. Stock is none. Needs the schedplus governor.", "up_throttle",
            new String[]{"None (stock)", "2 ms", "5 ms"}, new String[]{"0", "2", "5"}, "0");
        rowChoice(c, "Clock headroom", "How far above the measured load the scheduler sets the clock. Stock is 1.25x. More is smoother and uses more power.", "cap_margin",
            new String[]{"1.0x", "1.25x (stock)", "1.5x", "1.75x"}, new String[]{"1024", "1280", "1536", "1792"}, "1536");
        c = group("Idle and statistics");
        rowSwitch(c, "Deep idle (MCDI)", "Turning it off cuts wake up delay and costs idle battery.", "mcdi", "1");
        rowSwitch(c, "Scheduler statistics", "Kernel counters used by profilers. Off saves a little CPU time.", "schedstats", "0");
    }


    // ---------- overclock ----------
    private String ocSummary() {
        boolean on = "1".equals(get("oc_master", "0")) && (!"0".equals(get("oc_gpu", "0")) || !"0".equals(get("oc_cpu_uv", "0")));
        String st_ = get("oc_status", "off");
        if (st_.startsWith("reverted") || st_.endsWith("revert")) return "Went back to stock, tap for the reason";
        if (!on) return "1".equals(get("oc", "0")) ? "Off, top clocks pinned" : "Off, everything at stock";
        return ("0".equals(get("oc_gpu", "0")) ? "" : "GPU " + (Integer.parseInt(get("oc_gpu", "0")) / 1000) + " MHz") + ("0".equals(get("oc_gpu", "0")) || "0".equals(get("oc_cpu_uv", "0")) ? "" : ", ")
            + ("0".equals(get("oc_cpu_uv", "0")) ? "" : "CPU voltage trim") + ", " + ocStatusWord(st_);
    }
    private static String ocStatusWord(String s) {
        switch (s) { case "testing": return "testing"; case "active": return "stable"; case "waiting": return "waiting"; default: return "off"; }
    }
    private static String ocStatusText(String s) {
        switch (s) {
            case "waiting": return "Waiting. It is applied 30 seconds after boot.";
            case "testing": return "On. Kept for 3 minutes of stable running before it counts as safe.";
            case "active": return "On and stable.";
            case "reverted_after_crash": return "Went back to stock: the tablet restarted while overclocked. Turn it on again with a lower setting to retry.";
            case "thermal_revert": return "Went back to stock: a temperature sensor passed 85 °C.";
            case "gpu_failed": return "Went back to stock: the GPU did not take the clock.";
            case "cpu_failed": return "Went back to stock: the kernel did not take the CPU voltage trim.";
            case "unsupported": return "Not available: this kernel's GPU table is not the expected one.";
            default: return "Off. Everything runs at stock.";
        }
    }
    private String ocLine(Map<String, String> s) {
        long g = num(grep("/proc/gpufreq/gpufreq_var_dump", "real freq"));
        return ocStatusText(get0(s, "oc_status").isEmpty() ? "off" : get0(s, "oc_status")) + "\nGPU clock now " + (g < 0 ? "–" : (g / 1000) + " MHz");
    }
    private void refreshOc() { if (ocView != null) ocView.setText(ocLine(Cfg.load(this))); }

    private void confirmOc(final Runnable go) {
        new AlertDialog.Builder(this).setTitle("Overclock").setMessage("This can crash or freeze the tablet, shortens battery life and the life of the chip, and a hard freeze can corrupt data. Back up what matters first.\n\nIf the tablet restarts while an overclock is on, it goes back to stock by itself.")
            .setNegativeButton("Cancel", (d, w) -> build()).setPositiveButton("I understand", (d, w) -> go.run()).setOnCancelListener(d -> build()).show();
    }

    private void pageOc() {
        TextView warn = text("Warning: overclocking can crash or freeze the tablet, uses more battery, wears the chip faster, and a hard freeze can corrupt data. Nothing is applied until 30 seconds after boot. Above 85 °C, or after a restart while overclocked, everything goes back to stock.", 13.5f, false, 0xFFFF8A4C);
        warn.setPadding(px(10), px(6), px(10), px(2)); col.addView(warn);
        if (!wide) liveCard(false);
        LinearLayout c = group("Status");
        ocView = text(ocLine(st), 14, false, ink());
        ocView.setPadding(px(20), px(12), px(20), px(12)); c.addView(ocView);
        c = group("Switch");
        rowSwitchLike(c, "Allow overclock", "Master switch for the voltage trim and the GPU clock below. Off puts them back to stock.", "1".equals(get("oc_master", "0")), on -> {
            if (on) confirmOc(() -> { set("oc_master", "1"); build(); }); else { set("oc_master", "0"); build(); } });
        rowAction(c, "Max stable", "Turns it on with the safest useful settings: top CPU clocks pinned, GPU 730 MHz at the stock 0.80 V, no voltage change.", () -> confirmOc(() -> { set("oc_profile", "maxstable"); build(); }));
        c = group("CPU");
        rowSwitch(c, "Maximum clocks", "Pins both CPU clusters at their top stock speed, 2.30 GHz fast cores and 1.80 GHz efficiency cores. The kernel has no higher step, so this is not an overclock.", "oc", "0");
        rowChoice(c, "CPU voltage trim", "Lowers the CPU voltage at every step, 6 mV per notch. Less heat at the same speed. Too low freezes the tablet.", "oc_cpu_uv",
            new String[]{"Stock", "-6 mV", "-12 mV", "-25 mV"}, new String[]{"0", "1", "2", "4"}, "0");
        c = group("GPU");
        rowChoice(c, "GPU overclock", "Raises the GPU above its 680 MHz top step at the same 0.80 V. Replaces the GPU clock setting while it is on.", "oc_gpu",
            new String[]{"Stock (680 MHz)", "730 MHz (Max stable)", "760 MHz", "800 MHz"}, new String[]{"0", "730000", "760000", "800000"}, "0");
        c = group(null);
        rowAction(c, "Back to stock", "Turns off everything on this page right away, including Maximum clocks.", () -> { set("oc_stock", "1"); build(); });
    }

    private void pageMemory() {
        LinearLayout c = group("Storage");
        rowChoice(c, "Read-ahead", "How much storage is read in advance", "readahead",
            new String[]{"128 KB", "256 KB", "512 KB", "1 MB", "2 MB"}, new String[]{"128", "256", "512", "1024", "2048"}, "512");
        rowChoice(c, "Write buffer", "Share of memory that can hold unsaved writes. The vendor's 1% makes large writes slow.", "dirty",
            new String[]{"1%", "5%", "10%", "20%", "30%"}, new String[]{"1", "5", "10", "20", "30"}, "1");
        rowChoice(c, "I/O scheduler", "Storage request ordering", "io_sched", list("io_scheds", "noop,deadline,cfq"), list("io_scheds", "noop,deadline,cfq"), "deadline");
        rowChoice(c, "File cache pressure", "Lower keeps file caches longer", "vfs_cache", new String[]{"25", "50", "100", "150"}, new String[]{"25", "50", "100", "150"}, "50");
        c = group("Memory");
        rowChoice(c, "Compressed memory (swappiness)", "Higher keeps more apps in memory by compressing them", "swappiness",
            new String[]{"20", "40", "60", "80", "100"}, new String[]{"20", "40", "60", "80", "100"}, "100");
        rowChoice(c, "Free memory reserve", "Memory kept free so new apps start without waiting", "extra_free",
            new String[]{"None", "24 MB", "48 MB", "96 MB"}, new String[]{"0", "24576", "49152", "98304"}, "49152");
        rowChoice(c, "Swap read-ahead", "Pages read back from compressed memory at once", "page_cluster",
            new String[]{"1 (stock)", "2", "4", "8"}, new String[]{"0", "1", "2", "3"}, "0");
        rowChoice(c, "Memory watermark", "How early the kernel frees memory in the background. Higher keeps more free and caches less. Stock is 10.", "watermark",
            new String[]{"10 (stock)", "20", "30"}, new String[]{"10", "20", "30"}, "10");
        rowChoice(c, "Background apps kept", "How many background apps stay in memory, set through device config.", "bg_procs",
            new String[]{"System default", "8", "16", "32", "64"}, new String[]{"default", "8", "16", "32", "64"}, "default");
        rowChoice(c, "Cached app limit", "The same kind of limit, set through the Settings database. Use this or Background apps kept, not both. Android may ignore one of them.", "cached_apps",
            new String[]{"System default", "16", "24", "32", "48"}, new String[]{"default", "16", "24", "32", "48"}, "default");
    }

    // Picture edits wait here until Apply, so the engine restarts once; the preview shows them at once.
    private final java.util.HashMap<String, String> picEdit = new java.util.HashMap<>();
    private PicPreview preview;

    private String pv(String k, String def) { return picEdit.containsKey(k) ? picEdit.get(k) : get(k, def); }

    private android.graphics.Bitmap photo;
    private boolean photoAsked;

    /** The wallpaper copy the daemon puts in files/preview.jpg (asked for once); until it is there the preview draws its own scene. */
    private void loadPhoto() {
        java.io.File f = new java.io.File(getFilesDir(), "preview.jpg");
        if (photo == null && f.exists()) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = Math.max(1, Math.min(o.outWidth, o.outHeight) / 700); o.inJustDecodeBounds = false;
            photo = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        }
        if (photo != null) { preview.setPhoto(photo); return; }
        if (!photoAsked) {
            photoAsked = true; Cfg.request(this, "preview_photo", "1");
            ui.postDelayed(() -> { if (preview != null && "picture".equals(cur())) build(); }, 3000);
        }
    }

    private void updatePreview() {
        if (preview == null) return;
        float cNow = PicMath.contrast(PicMath.effective(get("pq_mode", "0"), get("pq_contrast", "4"))), cNew = PicMath.contrast(PicMath.effective(pv("pq_mode", "0"), pv("pq_contrast", "4")));
        float sNow = PicMath.saturation(PicMath.effective(get("pq_mode", "0"), get("pq_sat", "4"))), sNew = PicMath.saturation(PicMath.effective(pv("pq_mode", "0"), pv("pq_sat", "4")));
        preview.set(PicMath.rel(cNow, cNew), PicMath.rel(sNow, sNew), ink(), dim());
    }

    private void applyPicture() {
        for (java.util.Map.Entry<String, String> e : picEdit.entrySet()) put(e.getKey(), e.getValue());
        picEdit.clear();
        setStatus("Applying…"); ui.removeCallbacks(reload); ui.postDelayed(reload, 6000);
        build();
    }

    private void pagePicture() {
        TextView hint = text("MediaTek's picture engine (MiraVision). Changes wait until you press Apply, then it restarts once, which takes a few seconds and can flicker the screen. This tablet has no dynamic contrast, HDR or ultra resolution hardware, so those switches are gone. Contrast, saturation, gamma and sharpness only act in Custom mode. The numbers are MediaTek indexes: judge them by eye on a grey ramp.", 13.5f, false, dim());
        hint.setPadding(px(10), px(6), px(10), 0); col.addView(hint);

        LinearLayout pc = plainCard(); pc.setPadding(px(14), px(14), px(14), px(10));
        preview = new PicPreview(this); updatePreview(); loadPhoto();
        pc.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        TextView cap = text("Your wallpaper. An estimate for contrast and saturation. Left: your screen now. Right: with the changes below. Gamma, sharpness and brightness are not shown.", 12.5f, false, dim());
        cap.setPadding(px(6), px(8), px(6), 0); pc.addView(cap);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.setMargins(0, px(10), 0, px(4));
        col.addView(pc, pp);
        if (!picEdit.isEmpty()) {
            LinearLayout ab = new LinearLayout(this); ab.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable abg = new GradientDrawable(); abg.setColor(priC()); abg.setCornerRadius(px(28)); ab.setBackground(abg);
            ab.setPadding(px(22), px(12), px(14), px(12));
            ab.addView(text(picEdit.size() + (picEdit.size() == 1 ? " change" : " changes") + " not applied", 16, true, onPriC()), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView dis = text("Discard", 15, true, onPriC()); dis.setPadding(px(16), px(10), px(16), px(10)); dis.setMinHeight(px(48)); dis.setGravity(Gravity.CENTER);
            dis.setOnClickListener(v -> { picEdit.clear(); build(); });
            TextView ap = text("Apply", 15, true, onAccent()); ap.setPadding(px(24), px(10), px(24), px(10)); ap.setMinHeight(px(48)); ap.setGravity(Gravity.CENTER);
            GradientDrawable apg = new GradientDrawable(); apg.setColor(accent()); apg.setCornerRadius(px(24)); ap.setBackground(apg);
            ap.setOnClickListener(v -> applyPicture());
            ab.addView(dis); ab.addView(ap);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2); alp.setMargins(0, px(6), 0, px(4));
            col.addView(ab, alp);
        }
        LinearLayout c = group("Engine");
        row(c, "Picture engine", "Service, mode and the values it logged when it last set the display", text(pqLive(), 14, true, accent()));
        c = group("Quick looks");
        String[][] looks = {
            {"Factory", "The factory picture tables.", "pq_mode=0"},
            {"Clean", "Custom, neutral contrast and colour, no sharpening. Best for compressed video.", "pq_mode=2 pq_contrast=4 pq_sat=4 pq_gamma=7 pq_shp=0"},
            {"Punchy", "Custom, more contrast and colour, a little sharpening. Can look harsh.", "pq_mode=2 pq_contrast=6 pq_sat=6 pq_gamma=7 pq_shp=3"}};
        for (final String[] l : looks) {
            row(c, l[0], l[1], text("Apply", 15, true, accent())).setOnClickListener(v -> {
                picEdit.clear();
                for (String kv : l[2].split(" ")) { String[] p = kv.split("="); put(p[0], p[1]); }
                setStatus("Applying…"); ui.removeCallbacks(reload); ui.postDelayed(reload, 6000);
            });
        }
        c = group("Fine control");
        rowChoice(c, "Picture mode", "Custom uses the sliders below", "pq_mode", new String[]{"Factory", "Custom"}, new String[]{"0", "2"}, "0", true);
        String[] ten = {"0", "1", "2", "3", "4 (neutral)", "5", "6", "7", "8", "9"};
        rowSlider(c, "Contrast", "Screen contrast, 4 is neutral", "pq_contrast", 9, 4, ten, true);
        rowSlider(c, "Saturation", "Screen colour strength, 4 is neutral", "pq_sat", 9, 4, ten, true);
        rowSlider(c, "Gamma", "Mid-tone curve, 0 to 14, 7 is neutral", "pq_gamma", 14, 7, new String[]{"0", "1", "2", "3", "4", "5", "6", "7 (neutral)", "8", "9", "10", "11", "12", "13", "14"}, true);
        rowSlider(c, "Sharpness", "0 to 11, 2 is the factory value", "pq_shp", 11, 2, null, true);
        rowSlider(c, "Brightness", "Picture brightness, 0 to 9, 4 is the factory value (the engine maps the steps unevenly)", "pq_bri", 9, 4, new String[]{"0", "1", "2", "3", "4 (neutral)", "5", "6", "7", "8", "9"}, true);
        rowChoice(c, "Video colour index", "MediaTek video colour index. Effect not verified on this tablet", "pq_color", new String[]{"0", "1", "2", "3"}, new String[]{"0", "1", "2", "3"}, "0", true);
    }

    /** The daemon's pq_live line: "running,mode2,gsat4,cont6,bri4" or "...,nolog" when the log is off. */
    private String pqLive() {
        String v = get("pq_live", "");
        if (v.isEmpty()) return "unknown";
        String[] p = v.split(",");
        String s = ("running".equals(p[0]) ? "running" : "NOT running") + ", " + (p.length > 1 && "mode2".equals(p[1]) ? "custom" : "factory");
        return p.length > 4 ? s + ", set " + p[2] + " " + p[3] + " " + p[4] : s;
    }

    private void pageLook() {
        LinearLayout c = group("Theme");
        rowChoice(c, "Dark mode", null, "night", new String[]{"Automatic", "Light", "Dark"}, new String[]{"auto", "no", "yes"}, "yes");
        rowSwitch(c, "Pure black backgrounds", "True black behind everything", "black_theme", "1");
        c = group("Accent colour");
        boolean fromWall = "home_wallpaper".equals(get("palette_source", "preset"));
        String curAcc = get("accent", "3E91FF");
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        String[][] colors = {{"3E91FF", "Blue"}, {"7C5CFF", "Violet"}, {"00B8A9", "Teal"}, {"34C759", "Green"}, {"FF9500", "Orange"}, {"FF3B30", "Red"}, {"FF4F9A", "Pink"}};
        for (final String[] cc : colors) {
            View chip = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF000000 | Integer.parseInt(cc[0], 16));
            if (!fromWall && cc[0].equalsIgnoreCase(curAcc)) g.setStroke(px(3), ink());
            chip.setBackground(g);
            chip.setContentDescription(cc[1]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(38), px(38));
            lp.setMargins(px(5), px(6), px(5), px(6));
            chip.setOnClickListener(v -> { put("palette_source", "preset"); set("accent", cc[0]); build(); });
            chips.addView(chip, lp);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(chips);
        hs.setPadding(px(14), px(8), px(14), px(8));
        c.addView(hs);
        rowSwitchLike(c, "Use wallpaper colours", "Picks the accent from your home wallpaper", fromWall, on -> {
            if (on) set("palette_source", "home_wallpaper"); else set("palette_source", "preset"); build(); });
        rowChoice(c, "Palette style", null, "theme_style",
            new String[]{"Tonal spot", "Vibrant", "Expressive", "Spritz", "Rainbow", "Fruit salad", "Monochrome"},
            new String[]{"TONAL_SPOT", "VIBRANT", "EXPRESSIVE", "SPRITZ", "RAINBOW", "FRUIT_SALAD", "MONOCHROMATIC"}, "TONAL_SPOT");
        c = group("Fonts and icons");
        String[] fl = get("fonts", "").split(",");
        ArrayList<String> fLabels = new ArrayList<>(), fVals = new ArrayList<>();
        fLabels.add("System default"); fVals.add("default");
        for (String f : fl) if (!f.isEmpty()) { fVals.add(f); fLabels.add(fontLabel(f)); }
        if (fVals.size() > 1) rowChoice(c, "Font", null, "font", fLabels.toArray(new String[0]), fVals.toArray(new String[0]), "default");
        if ("1".equals(get("has_shapes", "0")))
            rowChoice(c, "Icon shape", null, "icon_shape",
                new String[]{"Default (circle)", "Squircle", "Rounded square", "Teardrop", "Pebble", "Vessel", "Tapered", "Square"},
                new String[]{"default", "squircle", "roundedrect", "teardrop", "pebble", "vessel", "taperedrect", "square"}, "default");
        if ("1".equals(get("has_packs", "0")))
            rowChoice(c, "System icon pack", null, "icon_pack",
                new String[]{"Default", "Rounded", "Filled", "Kai", "Sam", "Victor", "Circular"},
                new String[]{"default", "rounded", "filled", "kai", "sam", "victor", "circular"}, "default");
        rowButton(c, "Wallpaper and style", "Wallpapers and lock screen", new Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private interface OnBool { void on(boolean v); }
    private void rowSwitchLike(LinearLayout c, String title, String sub, boolean checked, final OnBool cb) {
        Switch s = new Switch(this); s.setChecked(checked);
        s.setOnCheckedChangeListener((v, on) -> cb.on(on));
        row(c, title, sub, s).setOnClickListener(v -> s.toggle());
    }

    private void pageDisplay() {
        LinearLayout c = group("Motion");
        rowChoice(c, "Navigation", null, "nav_mode", new String[]{"Swipe gestures", "Three buttons"}, new String[]{"gestural", "threebutton"}, "gestural");
        int a = 10; try { a = Math.round(Float.parseFloat(get("anim_scale", "1.0")) * 10); } catch (Exception ignored) { }
        final TextView av = text(String.format(Locale.US, "%.1fx", a / 10f), 15, true, accent());
        SeekBar sb = new SeekBar(this); sb.setMax(20); sb.setProgress(a);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { av.setText(String.format(Locale.US, "%.1fx", p / 10f)); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { set("anim_scale", String.format(Locale.US, "%.1f", s.getProgress() / 10f)); }
        });
        sliderTile(c, row(c, "Animation speed", "Lower is faster. 0.5x makes everything feel quicker.", av), sb);
        rowSwitch(c, "Turn off window blur", "Cheaper to draw, which makes launcher and shade smoother", "window_blur", "0");
        c = group("Screen");
        rowChoice(c, "Display size", "Smaller numbers show more content. In portrait, above 250 the app uses the single column layout.", "density",
            new String[]{"Tiny (160)", "Smaller (176)", "Compact (192)", "Small (213)", "Medium (225)", "Stock (240)", "Large (260)", "Larger (280)", "Largest (300)", "Huge (320)"},
            new String[]{"160", "176", "192", "213", "225", "reset", "260", "280", "300", "320"}, "192");
        rowChoice(c, "Font size", null, "font_scale", new String[]{"Small", "Default", "Large", "Largest"}, new String[]{"0.85", "1.0", "1.15", "1.3"}, "1.0");
        rowChoice(c, "Text weight", "Heavier strokes read clearer on this screen", "font_weight", new String[]{"Normal", "Medium", "Bold", "Extra bold"}, new String[]{"0", "100", "200", "300"}, "0");
        rowSwitch(c, "High contrast text", "Draws text with an outline so it stands out from the background", "text_contrast", "0");
        rowChoice(c, "Screen timeout", null, "screen_timeout",
            new String[]{"15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes"},
            new String[]{"15000", "30000", "60000", "120000", "300000", "600000", "1800000"}, "60000");
        rowChoice(c, "Stay awake while charging", null, "stay_awake", new String[]{"Never", "On AC and USB", "On AC, USB and wireless"}, new String[]{"0", "3", "7"}, "0");
        rowSwitch(c, "Auto-rotate", null, "auto_rotate", "1");
        rowSwitch(c, "Show taps", "Draws a dot where you touch", "show_taps", "0");
        c = group("Start-up");
        rowChoice(c, "Boot animation", null, "bootanim", new String[]{"Gemini (Pixel 10)", "ArrowOS", "Galaxy", "Aurora ring", "Stock LineageOS"}, new String[]{"gemini", "arrow", "galaxy", "aurora", "stock"}, "gemini");
    }

    private void pageConnect() {
        LinearLayout c = group("Wi-Fi");
        rowChoice(c, "Country code", "Sets the allowed Wi-Fi channels and power. Pick the country you are in. Applied again after every boot.", "wifi_country",
            new String[]{"Automatic", "Canada", "United States", "United Kingdom", "Germany", "France", "India", "Australia", "Japan", "Mexico", "Brazil"},
            new String[]{"auto", "CA", "US", "GB", "DE", "FR", "IN", "AU", "JP", "MX", "BR"}, "CA");
        c = group("Network");
        rowChoice(c, "Private DNS", null, "dns",
            new String[]{"Off", "Automatic", "Google", "Cloudflare", "AdGuard (blocks ads)", "Quad9"}, new String[]{"off", "auto", "google", "cloudflare", "adguard", "quad9"}, "auto");
        rowChoice(c, "TCP congestion control", "How fast downloads ramp up", "tcp_cc", list("tcps", "bic,cubic,reno"), list("tcps", "bic,cubic,reno"), "bic");
        c = group("Bluetooth and audio");
        rowSwitch(c, "Bluetooth MediaTek workaround", "Lets this controller connect. Restarts Bluetooth.", "bt_work", "0");
        rowSwitch(c, "Allow Treble audio effects", "Left on, app audio can cut out after a few milliseconds.", "phh_effects", "0");
        c = group("Developer");
        rowSwitch(c, "ADB over network", "Port 5555 on Wi-Fi. Anyone on the network can connect.", "adb_tcp", "0");
        rowSwitch(c, "Serial console shell", "Android shows a performance warning while it runs.", "serial_console", "0");
    }

    private String widevine() {
        try {
            android.media.MediaDrm d = new android.media.MediaDrm(new java.util.UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL));
            String r = d.getPropertyString("securityLevel") + ", HDCP " + d.getPropertyString("maxHdcpLevel");
            android.util.Log.i("RomTuner", "widevine " + r + " vendor=" + d.getPropertyString("vendor") + " version=" + d.getPropertyString("version")
                + " systemId=" + d.getPropertyString("systemId") + " hdcp=" + d.getPropertyString("hdcpLevel"));
            d.close();
            return r;
        } catch (Throwable t) { android.util.Log.i("RomTuner", "widevine failed " + t); return "unavailable"; }
    }

    // ---------- per app compile ----------
    private static class App { String pkg, label; boolean system, code; }
    private static final HashSet<String> NATIVE = new HashSet<>(Arrays.asList("speed", "speed-profile", "everything", "everything-profile", "space", "space-profile"));
    private volatile ArrayList<App> appsAll;               // from PackageManager, built off the UI thread
    private boolean appsLoading;
    private long appsLoadedAt, appsAsked;
    private int appsAsks;
    private Map<String, String> appStates;                 // package -> compiler filter; null while the daemon's file is missing or incomplete
    private Map<String, String> appsSt = new java.util.HashMap<>();
    private String appsStamp = "", appsSig = "", appsQuery = "";
    private int appsFilter, appsLimit = 60;                // filter: 0 all, 1 not compiled, 2 compiled
    private LinearLayout appsBox;
    private TextView appsInfo;

    private static String get0(Map<String, String> m, String k) { String v = m.get(k); return v == null ? "" : v; }

    private EditText searchBox() {
        EditText q = new EditText(this);
        q.setHint("Search by name or package"); q.setSingleLine(true); q.setTextSize(15);
        q.setTextColor(ink()); q.setHintTextColor(dim());
        q.setImeOptions(EditorInfo.IME_ACTION_DONE);
        q.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        GradientDrawable qg = new GradientDrawable(); qg.setColor(card()); qg.setCornerRadius(px(28));
        q.setBackground(qg); q.setPadding(px(20), px(14), px(20), px(14));
        q.setText(appsQuery);
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) { appsQuery = e.toString(); appsLimit = 60; fillApps(); }
        });
        return q;
    }

    private LinearLayout filterChips(boolean tall) {
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        if (!tall) chips.setPadding(0, px(12), 0, px(2));
        String[] names = {"All", "Not compiled", "Compiled"};
        for (int i = 0; i < names.length; i++) {
            final int fi = i;
            boolean on = i == appsFilter;
            TextView t = text(names[i], 14, true, on ? onAccent() : ink());
            t.setGravity(Gravity.CENTER); t.setPadding(px(18), px(10), px(18), px(10));
            if (tall) t.setMinHeight(px(48));
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(20)); g.setColor(on ? accent() : card());
            t.setBackground(g);
            t.setOnClickListener(v -> { appsFilter = fi; appsLimit = 60; build(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, 0, px(8), 0);
            chips.addView(t, lp);
        }
        return chips;
    }

    private void pageApps() {
        int savedCols = cols; cols = 1;   // the progress block, search and filter span the pane; only the app rows use columns
        TextView hint = text("Tap an app to compile it for faster launches or to put it back to the system default. Apps are compiled one at a time.", 13.5f, false, dim());
        hint.setPadding(px(10), px(6), px(10), 0); col.addView(hint);
        LinearLayout c = group(null);
        progressBlock(c);
        rowAction(c, "Compile all not compiled", "Compiles every app that is not at speed yet, apps at speed profile included. Runs in the background and takes 10 to 15 minutes.", () -> compile("compile_now"));

        appsInfo = text("", 13.5f, false, dim());
        appsInfo.setPadding(px(10), px(18), px(10), px(8));
        appsInfo.setOnClickListener(v -> { appsAsks = 0; askApps(); });
        col.addView(appsInfo);

        EditText q = searchBox();
        if (wide) {   // search and filter share one row on top of the list
            LinearLayout bar = new LinearLayout(this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(0, -2, 1f); qp.setMargins(0, 0, px(16), 0);
            bar.addView(q, qp);
            bar.addView(filterChips(true));
            col.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        } else {
            col.addView(q, new LinearLayout.LayoutParams(-1, -2));
            col.addView(filterChips(false));
        }

        if (appsCols > 1) {
            appsBox = new LinearLayout(this);
            appsBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, px(14), 0, 0);
            col.addView(appsBox, lp);
        } else appsBox = group(null);
        if (!appsLoading && System.currentTimeMillis() - appsLoadedAt > 30000) loadAppList();
        appsSig = "";
        refreshApps();
        cols = savedCols;
    }

    /** Reads the installed apps; labels take a moment, so this runs on its own thread. */
    private void loadAppList() {
        appsLoading = true;
        new Thread(() -> {
            ArrayList<App> out = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (!ai.enabled) continue;
                    App a = new App();
                    a.pkg = ai.packageName;
                    try { a.label = String.valueOf(ai.loadLabel(pm)); } catch (Exception e) { a.label = a.pkg; }
                    a.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    a.code = (ai.flags & ApplicationInfo.FLAG_HAS_CODE) != 0;
                    out.add(a);
                }
            } catch (Throwable ignored) { }
            ui.post(() -> {
                appsLoading = false;
                if (!out.isEmpty() || appsAll == null) { appsAll = out; appsLoadedAt = System.currentTimeMillis(); }
                if (!isDestroyed() && "apps".equals(cur())) fillApps();
            });
        }).start();
    }

    private void askApps() { Cfg.request(this, "refresh_apps", "1"); appsAsked = System.currentTimeMillis(); appsAsks++; }

    /** Runs every second while the page is open: re-reads the state copy and the list file and redraws only when something changed. */
    private void refreshApps() {
        if (appsInfo == null) return;
        Map<String, String> s = liveState();
        if (s == null) return;
        appsSt = s;
        String stamp = get0(s, "apps_state_time"), rf = get0(s, "apps_refresh");
        if (appStates == null || (!stamp.isEmpty() && !stamp.equals(appsStamp))) {
            Map<String, String> m = Cfg.loadApps(this);
            if (m != null) { appStates = m; appsStamp = get0(m, "apps_state_time"); appsAsks = 0; }
        }
        long now = System.currentTimeMillis();
        String info, job = get0(s, "compile_pkg_status"), queue = get0(s, "compile_queue");
        int waiting = queue.isEmpty() ? 0 : queue.split(",").length;
        if (appStates == null) {
            if ("running".equals(rf)) info = "Loading compile states…";
            else if ("failed".equals(rf)) info = "Could not read the compile states. Tap here to try again.";
            else if (appsAsks >= 3) info = "The tuner service did not answer. Install the newest tablet_tunerd.sh on the tablet, then tap here.";
            else { info = "Loading compile states…"; if (now - appsAsked > 8000) askApps(); }
        } else if ("running".equals(s.get("compile_status"))) {
            info = "A system compile is running. Wait for it to finish before compiling one app.";
        } else if (job.endsWith(":running")) {
            info = "Compiling " + job.substring(0, job.length() - 8) + (waiting > 0 ? ", " + waiting + " waiting" : "") + ".";
        } else if ("running".equals(rf)) {
            info = "Reading the new compile states…";
        } else {
            long t = 0; try { t = Long.parseLong(appsStamp) * 1000; } catch (Exception ignored) { }
            info = "Compile states read at " + (t > 0 ? java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(t)) : "an unknown time") + ". Tap here to read them again.";
            if (t > 0 && now - t > 300000 && now - appsAsked > 60000) askApps();
        }
        appsInfo.setText(info);
        String sig = appsStamp + "|" + job + "|" + queue + "|" + s.get("compile_status") + "|" + (appStates == null)
            + "|" + get0(s, "compile_current") + "|" + get0(s, "compile_current_state") + "|" + get0(s, "compile_done") + "|" + get0(s, "compile_summary");
        if (!sig.equals(appsSig)) { appsSig = sig; fillApps(); }
    }

    private static String stateLabel(String f) {
        if (f == null || f.isEmpty()) return "Not compiled";
        switch (f) {
            case "unknown": case "error": case "run-from-apk": case "run-from-apk-fallback": case "run-from-vdex-fallback": return "Not compiled";
            case "verify": return "Verified only";
            default: return f.substring(0, 1).toUpperCase(Locale.US) + f.substring(1).replace('-', ' ');
        }
    }
    /** 0 not compiled, 1 compiled to native code, 2 nothing to compile. */
    private int appGroup(App a) {
        if (!a.code || noDex(a)) return 2;
        return appStates != null && NATIVE.contains(appStates.get(a.pkg)) ? 1 : 0;
    }
    /** ART reports "unknown" for packages without a dex file (resource overlays such as android.auto_generated_rro_vendor__ declare no hasCode=false but have no code). */
    private boolean noDex(App a) { return appStates != null && (!appStates.containsKey(a.pkg) || "unknown".equals(appStates.get(a.pkg))); }
    private String baseState(App a) { return !a.code || noDex(a) ? "No code" : appStates == null ? "Loading" : stateLabel(appStates.get(a.pkg)); }
    private String stateText(App a) {
        String job = get0(appsSt, "compile_pkg_status"), jp = "", js = "";
        int i = job.lastIndexOf(':');
        if (i > 0) { jp = job.substring(0, i); js = job.substring(i + 1); }
        if (a.pkg.equals(jp) && "running".equals(js)) return "Compiling…";
        // a bulk run names the app it is on (compile_current); old daemons publish nothing here
        if (a.pkg.equals(get0(appsSt, "compile_current")) && "compiling".equals(get0(appsSt, "compile_current_state"))
            && ("running".equals(get0(appsSt, "compile_status")) || "running".equals(js))) return "Compiling…";
        if (("," + get0(appsSt, "compile_queue")).contains("," + a.pkg + ",")) return "Queued";
        if (a.pkg.equals(jp) && "failed".equals(js)) return "Failed";
        if (a.pkg.equals(jp) && "done".equals(js)) return "Done, " + baseState(a).toLowerCase(Locale.US);
        return baseState(a);
    }

    /** A card for a message or the Show more row: appsBox itself in one column, else a full width card below the grid. */
    private LinearLayout appsWholeCard() {
        if (appsCols <= 1) return appsBox;
        LinearLayout c = newCard();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, px(appsBox.getChildCount() > 0 ? 14 : 0), 0, 0);
        appsBox.addView(c, lp);
        return c;
    }

    private void fillApps() {
        if (appsBox == null) return;
        appsBox.removeAllViews();
        if (appsAll == null) { row(appsWholeCard(), "Loading apps…", null, null); return; }
        if (appStates == null && appsFilter != 0) { row(appsWholeCard(), "Waiting for the compile states", "Pick All to see the apps meanwhile.", null); return; }
        String q = appsQuery.trim().toLowerCase(Locale.ROOT);
        final ArrayList<App> m = new ArrayList<>();
        for (App a : appsAll) {
            int g = appGroup(a);
            if (appsFilter == 1 && g != 0 || appsFilter == 2 && g != 1) continue;
            if (!q.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(q) && !a.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            m.add(a);
        }
        Collections.sort(m, (x, y) -> { int gx = appGroup(x), gy = appGroup(y); return gx != gy ? gx - gy : x.label.compareToIgnoreCase(y.label); });
        if (m.isEmpty()) { row(appsWholeCard(), "No apps match", null, null); return; }
        int shown = Math.min(m.size(), appsLimit);
        // in columns the apps run left to right, then down, so a longer list only grows at the bottom
        LinearLayout[] cards = new LinearLayout[Math.max(1, Math.min(appsCols, shown))];
        if (appsCols <= 1) cards[0] = appsBox;
        else {
            LinearLayout grid = new LinearLayout(this);
            grid.setBaselineAligned(false);
            for (int i = 0; i < appsCols; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(i == 0 ? 0 : px(8), 0, i == appsCols - 1 ? 0 : px(8), 0);
                if (i < cards.length) { cards[i] = newCard(); grid.addView(cards[i], lp); }
                else grid.addView(new View(this), lp);
            }
            appsBox.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        }
        for (int i = 0; i < shown; i++) {
            final App a = m.get(i);
            String st1 = stateText(a);
            TextView sv = text(st1, 14, true, appGroup(a) == 1 || st1.startsWith("Compiling") || st1.equals("Queued") ? accent() : ink());
            sv.setGravity(Gravity.END);
            row(cards[i % cards.length], a.label, a.pkg + (a.system ? "\nSystem app" : ""), sv).setOnClickListener(v -> appDialog(a));
        }
        if (m.size() > appsLimit)
            row(appsWholeCard(), "Show more", appsLimit + " of " + m.size() + " shown", text("›", 22, false, dim())).setOnClickListener(v -> { appsLimit += 60; fillApps(); });
    }

    private int attrColor(int attr) {
        android.content.res.TypedArray t = obtainStyledAttributes(new int[]{attr});
        try { return t.getColor(0, ink()); } finally { t.recycle(); }
    }

    private void appDialog(final App a) {
        final int pri = attrColor(android.R.attr.textColorPrimary), sec = attrColor(android.R.attr.textColorSecondary);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(px(24), px(8), px(24), 0);
        v.addView(text(a.pkg, 13, false, sec));
        TextView cur = text("Current state: " + stateText(a), 15, true, pri);
        cur.setPadding(0, px(6), 0, px(10));
        v.addView(cur);
        final AlertDialog[] dlg = new AlertDialog[1];
        String[][] acts = {
            {"Compile (speed)", "Compiles all of the app's code to native code. Fastest launches, uses more storage.", "compile_pkg"},
            {"Compile (speed profile)", "Compiles only the code this app has used before. Quicker to finish, smaller gain.", "compile_pkg_profile"},
            {"Reset", "Removes the compiled code and goes back to the system default.", "reset_pkg"}};
        for (final String[] x : acts) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, px(10), 0, px(10));
            r.addView(text(x[0], 16, true, accent()));
            TextView d = text(x[1], 13, false, sec); d.setPadding(0, px(2), 0, 0);
            r.addView(d);
            r.setOnClickListener(b -> { dlg[0].dismiss(); appAction(x[2], a); });
            v.addView(r);
        }
        dlg[0] = new AlertDialog.Builder(this).setTitle(a.label).setView(v).setNegativeButton("Cancel", null).show();
    }

    private void appAction(String key, App a) {
        Map<String, String> s = Cfg.load(this);
        if ("running".equals(s.get("compile_status"))) { Toast.makeText(this, "A system compile is running. Wait for it to finish.", Toast.LENGTH_SHORT).show(); return; }
        Cfg.request(this, key, a.pkg);
        setStatus("Sent");
    }

    private void pageSystem() {
        LinearLayout c = group("Shortcuts");
        rowButton(c, "Status bar", "Clock, battery, network speed, brightness slider", comp("org.lineageos.lineageparts", "org.lineageos.lineageparts.statusbar.StatusBarSettings"));
        rowButton(c, "Buttons", "Navigation and hardware key actions", comp("org.lineageos.lineageparts", "org.lineageos.lineageparts.input.ButtonSettings"));
        rowButton(c, "Display rotation", "Allowed rotation angles", comp("org.lineageos.lineageparts", "org.lineageos.lineageparts.hardware.DisplayRotation"));
        rowButton(c, "Charging control", "Limit the charge level", comp("org.lineageos.lineageparts", "org.lineageos.lineageparts.health.ChargingControlSettings"));
        rowButton(c, "Dolby Atmos", "Sound modes", comp("com.dolby.daxappui", "com.dolby.daxappui.MainActivity"));
        rowButton(c, "Developer options", null, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        c = group("Streaming");
        row(c, "Widevine", "Decides the streaming quality: L1 allows HD, L3 is limited to about 480p", text(widevine(), 15, true, accent()));
        c = group("Diagnostics");
        rowSwitch(c, "System logging", "Off for speed. Turn on to debug.", "logging", "0");
        c = group("App speed");
        progressBlock(c);
        compileView = text(compileLabel(get("compile_status", "idle")), 15, true, accent());
        row(c, "Compile apps now", "Compiles installed apps for faster launches. Takes 10 to 15 minutes, runs in the background. SystemUI and the launcher restart when it finishes the first apps.", compileView)
            .setOnClickListener(v -> compile("compile_now"));
        rowAction(c, "Recompile all apps", "Redoes apps that are already compiled too. Use Restart system interface afterwards.", () -> compile("compile_all"));
        rowGo(c, "Per app compile", "See the compile state of every app and compile or reset one app", "apps");
        c = group("Restart");
        rowAction(c, "Restart system interface", "Re-applies theme changes without rebooting", () -> { Cfg.request(this, "restart_ui", "1"); setStatus("Restarting the interface…"); });
        rowAction(c, "Reboot the tablet", "Needed for a few changes to fully apply", () ->
            new AlertDialog.Builder(this).setMessage("Reboot the tablet now?").setNegativeButton("Cancel", null)
                .setPositiveButton("Reboot", (d, w) -> Cfg.request(this, "reboot", "1")).show());
    }
}

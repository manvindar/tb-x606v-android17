package org.tablet.tuner;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * The Fridge hub picture, used by the screen saver and by the activity behind the Hub mode tile. It shows a large clock next to the Google Home favourites widget, so the ecobee and the LG WashTower are on screen while the tablet charges.
 * The widget is the one Google Home provides to any launcher; ecobee and LG are only reachable through Google Home, so this is the way to show their state.
 * It needs the bind permission once: adb shell appwidget grantbind --package org.tablet.tuner
 */
final class HubScreen {
    private static final int HOST_ID = 7701;
    private static final ComponentName HOME_WIDGET = new ComponentName("com.google.android.apps.chromecast.app",
        "com.google.android.apps.chromecast.app.appwidget.favorites.FavoritesWidgetReceiver");

    private final Context ctx;
    private AppWidgetHost host;
    private LinearLayout content;
    private final Handler drift = new Handler(Looper.getMainLooper());
    private int step;

    HubScreen(Context c) { ctx = c; }

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()); }

    View build() {
        host = new AppWidgetHost(ctx, HOST_ID);

        FrameLayout root = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF04060C, 0xFF0B1A33, 0xFF1A1030});
        root.setBackground(bg);

        boolean wide = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        content = new LinearLayout(ctx);
        content.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding((int) dp(48), (int) dp(32), (int) dp(48), (int) dp(32));

        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL | (wide ? Gravity.START : Gravity.CENTER_HORIZONTAL));
        TextClock time = new TextClock(ctx);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, wide ? 150 : 120);
        time.setTextColor(Color.WHITE);
        time.setLetterSpacing(-0.04f);
        time.setIncludeFontPadding(false);
        TextClock date = new TextClock(ctx);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        date.setTextColor(0xB3FFFFFF);
        left.addView(time);
        left.addView(date);
        content.addView(left, new LinearLayout.LayoutParams(wide ? 0 : -2, -2, wide ? 1f : 0f));

        View right = widgetView();
        int w = (int) dp(wide ? 460 : 520), h = (int) dp(wide ? 420 : 380);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        if (!wide) lp.topMargin = (int) dp(24);
        content.addView(right, lp);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View widgetView() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        SharedPreferences sp = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE);
        int id = sp.getInt("widget_id", -1);
        AppWidgetProviderInfo info = id < 0 ? null : mgr.getAppWidgetInfo(id);
        if (info == null) {
            id = host.allocateAppWidgetId();
            if (!mgr.bindAppWidgetIdIfAllowed(id, HOME_WIDGET)) {
                host.deleteAppWidgetId(id);
                return note("Allow the widget once:\nadb shell appwidget grantbind --package org.tablet.tuner");
            }
            sp.edit().putInt("widget_id", id).apply();
            info = mgr.getAppWidgetInfo(id);
        }
        if (info == null) return note("Google Home widget not found");
        AppWidgetHostView hv = host.createView(ctx, id, info);
        Bundle o = new Bundle();
        o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 380);
        o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 460);
        o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 340);
        o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 420);
        hv.updateAppWidgetOptions(o);
        hv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline out) { out.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(28)); }
        });
        hv.setClipToOutline(true);
        return hv;
    }

    private View note(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xCCFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    void start() {
        try { host.startListening(); } catch (Exception ignored) { }
        drift.post(new Runnable() {
            @Override public void run() {      // move the picture a few pixels each minute so nothing stays lit in one place
                int d = (int) dp(10);
                content.setTranslationX(((step % 3) - 1) * d);
                content.setTranslationY((((step / 3) % 3) - 1) * d);
                step++;
                drift.postDelayed(this, 60_000);
            }
        });
    }

    void stop() {
        drift.removeCallbacksAndMessages(null);
        try { host.stopListening(); } catch (Exception ignored) { }
    }
}

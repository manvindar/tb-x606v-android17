package org.tablet.tuner;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * Step plot of the last minute. Clock speeds on this chip move in steps between fixed operating points, so the lines are drawn as steps
 * and the faint rules mark those points. The newest reading is at the right edge.
 */
final class LiveGraph extends View {
    private Hist[] series = new Hist[0];
    private int[] colors = new int[0];
    private float min, max = 1, guard = -1;
    private float[] rules = new float[0];
    private int ruleColor, guardColor;
    private boolean fillFirst;
    private float heightDp = 104;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    LiveGraph(Context c) { super(c); }

    void setup(Hist[] s, int[] colors, float min, float max, float[] rules, float guard, int ruleColor, int guardColor, boolean fillFirst) {
        series = s; this.colors = colors; this.min = min; this.max = Math.max(max, min + 0.001f);
        this.rules = rules; this.guard = guard; this.ruleColor = ruleColor; this.guardColor = guardColor; this.fillFirst = fillFirst;
        invalidate();
    }
    void setRange(float min, float max, float[] rules) { this.min = min; this.max = Math.max(max, min + 0.001f); this.rules = rules; }

    void setHeightDp(float h) { heightDp = h; requestLayout(); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(resolveSize(0, w), (int) dp(heightDp)); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), top = dp(8), bot = getHeight() - dp(8), span = bot - top;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(ruleColor); p.setPathEffect(null);
        for (float r : rules) { float y = yOf(r, top, span); c.drawLine(0, y, w, y, p); }
        if (guard >= 0) {
            p.setColor(guardColor); p.setStrokeWidth(dp(1.5f)); p.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(5)}, 0));
            float y = yOf(guard, top, span); c.drawLine(0, y, w, y, p); p.setPathEffect(null);
        }
        for (int k = 0; k < series.length; k++) {
            Hist h = series[k];
            if (h.n < 2) continue;
            float dx = w / (h.v.length - 1), x0 = w - (h.n - 1) * dx;
            path.reset();
            path.moveTo(x0, yOf(h.v[0], top, span));
            for (int i = 1; i < h.n; i++) { float x = x0 + i * dx; path.lineTo(x, yOf(h.v[i - 1], top, span)); path.lineTo(x, yOf(h.v[i], top, span)); }
            if (k == 0 && fillFirst) {
                Path f = new Path(path); f.lineTo(w, bot); f.lineTo(x0, bot); f.close();
                p.setStyle(Paint.Style.FILL); p.setColor((colors[k] & 0xFFFFFF) | 0x30000000); c.drawPath(f, p);
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2.5f)); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(colors[k]);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL); c.drawCircle(w - dp(3), yOf(h.last(), top, span), dp(4), p);
        }
    }

    private float yOf(float v, float top, float span) {
        float f = (v - min) / (max - min);
        return top + span * (1 - Math.max(0f, Math.min(1f, f)));
    }
}

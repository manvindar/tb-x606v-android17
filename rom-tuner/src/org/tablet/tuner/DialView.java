package org.tablet.tuner;

import android.content.Context;
import android.graphics.*;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Half-circle selector with detents: the current preset sits on the arc, drag or tap to pick another. */
final class DialView extends View {
    interface OnPick { void pick(int index); }

    private String[] labels = new String[0];
    private int selected = -1;
    private OnPick onPick;
    private int accent, track, ink, dim, onAccent, warm;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float dragPos = -1;

    DialView(Context c) { super(c); }

    void setColors(int accent, int track, int ink, int dim, int onAccent, int warm) {
        this.accent = accent; this.track = track; this.ink = ink; this.dim = dim; this.onAccent = onAccent; this.warm = warm; invalidate();
    }
    void setItems(String[] l) { labels = l; invalidate(); }
    void setSelected(int i) { selected = i; dragPos = -1; invalidate(); }
    void setOnPick(OnPick o) { onPick = o; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private float cx() { return getWidth() / 2f; }
    private float r() { return Math.min(getWidth() / 2f - dp(36), getHeight() - dp(70)); }
    private float cy() { return r() + dp(40); }
    // detent i of n sits at angle 180..0 degrees (left to right)
    private PointF at(float pos) {
        int n = Math.max(2, labels.length);
        double a = Math.PI * (1 - pos / (n - 1));
        return new PointF((float) (cx() + r() * Math.cos(a)), (float) (cy() - r() * Math.sin(a)));
    }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (Math.min(width / 2f - dp(36), dp(250)) + dp(84)));
    }

    @Override protected void onDraw(Canvas c) {
        int n = labels.length; if (n < 2) return;
        float R = r(), CX = cx(), CY = cy();
        RectF box = new RectF(CX - R, CY - R, CX + R, CY + R);
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(10)); p.setColor(track);
        c.drawArc(box, 180, 180, false, p);
        float pos = dragPos >= 0 ? dragPos : Math.max(0, selected);
        if (selected >= 0 || dragPos >= 0) {
            p.setColor(pos >= n - 1.5f ? warm : accent);
            c.drawArc(box, 180, 180 * pos / (n - 1), false, p);
        }
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            PointF d = at(i);
            p.setStyle(Paint.Style.FILL); p.setColor(i <= pos ? onAccent : dim);
            c.drawCircle(d.x, d.y, dp(3), p);
            p.setColor(i == selected ? ink : dim);
            p.setTextSize(dp(i == selected ? 14 : 12.5f));
            p.setTypeface(Typeface.create("sans-serif-medium", i == selected ? Typeface.BOLD : Typeface.NORMAL));
            // the name sits just outside the arc, next to its dot
            double a = Math.PI * (1 - (double) i / (n - 1));
            float off = dp(i == selected ? 26 : 22);
            float tx = d.x + (float) Math.cos(a) * off, ty = d.y - (float) Math.sin(a) * off + dp(5);
            p.setTextAlign(i == 0 ? Paint.Align.CENTER : i == n - 1 ? Paint.Align.CENTER : Paint.Align.CENTER);
            c.drawText(labels[i], tx, i == 0 || i == n - 1 ? CY + dp(26) : ty, p);
        }
        if (selected >= 0 || dragPos >= 0) {
            PointF t = at(pos);
            p.setStyle(Paint.Style.FILL); p.setColor(pos >= n - 1.5f ? warm : accent);
            c.drawCircle(t.x, t.y, dp(17), p);
            p.setColor(onAccent); c.drawCircle(t.x, t.y, dp(6), p);
        }
    }

    private int nearest(float x, float y) {
        int n = labels.length, best = 0; float bd = 1e9f;
        for (int i = 0; i < n; i++) { PointF d = at(i); float dd = (d.x - x) * (d.x - x) + (d.y - y) * (d.y - y); if (dd < bd) { bd = dd; best = i; } }
        return best;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int n = labels.length; if (n < 2) return false;
        float x = e.getX(), y = Math.min(e.getY(), cy());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: getParent().requestDisallowInterceptTouchEvent(true);
            case MotionEvent.ACTION_MOVE: {
                double a = Math.atan2(cy() - y, x - cx()); if (a < 0) a = a > -Math.PI / 2 ? 0 : Math.PI;
                float pos = (float) ((1 - a / Math.PI) * (n - 1));
                int near = Math.round(pos);
                if ((int) Math.round(dragPos) != near) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                dragPos = pos; invalidate(); return true;
            }
            case MotionEvent.ACTION_UP: {
                int pick = Math.round(Math.max(0, Math.min(n - 1, dragPos < 0 ? nearest(x, y) : dragPos)));
                dragPos = -1;
                if (pick != selected) { selected = pick; if (onPick != null) onPick.pick(pick); }
                invalidate(); return true;
            }
            case MotionEvent.ACTION_CANCEL: dragPos = -1; invalidate(); return true;
        }
        return true;
    }
}

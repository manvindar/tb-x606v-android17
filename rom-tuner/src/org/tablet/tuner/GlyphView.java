package org.tablet.tuner;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** A round icon drawn with the canvas (no font or image files): a tonal circle with a simple symbol, chosen by id. */
final class GlyphView extends View {
    private final String id;
    private final int fg, bg;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    GlyphView(Context c, String id, int fg, int bg) { super(c); this.id = id; this.fg = fg; this.bg = bg; }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, R = Math.min(cx, cy), u = R * 0.46f;
        p.setStyle(Paint.Style.FILL); p.setColor(bg); c.drawCircle(cx, cy, R, p);
        p.setColor(fg); p.setStrokeWidth(R * 0.11f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        RectF box = new RectF(cx - u, cy - u, cx + u, cy + u);
        switch (id) {
            case "m0": case "m1": case "m2": case "m3": {   // a pie that fills up with the mode
                int n = id.charAt(1) - '0' + 1;
                p.setStyle(Paint.Style.STROKE); c.drawCircle(cx, cy, u, p);
                p.setStyle(Paint.Style.FILL); c.drawArc(box, -90, 90f * n, true, p); break;
            }
            case "m4": {   // spark
                Path s = new Path();
                for (int i = 0; i < 16; i++) { double a = Math.PI * i / 8 - Math.PI / 2, r = i % 2 == 0 ? u * 1.15f : u * 0.55f; float x = cx + (float) (Math.cos(a) * r), y = cy + (float) (Math.sin(a) * r); if (i == 0) s.moveTo(x, y); else s.lineTo(x, y); }
                s.close(); p.setStyle(Paint.Style.FILL); c.drawPath(s, p); break;
            }
            case "custom": case "look": {   // sliders / palette dots
                p.setStyle(Paint.Style.STROKE);
                if (id.equals("custom")) {
                    for (int i = -1; i <= 1; i++) { float y = cy + i * u * 0.75f; c.drawLine(cx - u, y, cx + u, y, p); p.setStyle(Paint.Style.FILL); c.drawCircle(cx + (i == 0 ? -u * 0.3f : u * 0.35f * i), y, R * 0.17f, p); p.setStyle(Paint.Style.STROKE); }
                } else { p.setStyle(Paint.Style.FILL); c.drawCircle(cx - u * 0.55f, cy + u * 0.35f, R * 0.2f, p); c.drawCircle(cx + u * 0.55f, cy + u * 0.35f, R * 0.2f, p); c.drawCircle(cx, cy - u * 0.5f, R * 0.2f, p); }
                break;
            }
            case "live": {   // pulse line
                p.setStyle(Paint.Style.STROKE);
                Path w = new Path(); w.moveTo(cx - u, cy); w.lineTo(cx - u * 0.45f, cy); w.lineTo(cx - u * 0.2f, cy - u * 0.8f); w.lineTo(cx + u * 0.2f, cy + u * 0.8f); w.lineTo(cx + u * 0.45f, cy); w.lineTo(cx + u, cy); c.drawPath(w, p); break;
            }
            case "perf": {   // gauge
                p.setStyle(Paint.Style.STROKE); c.drawArc(box, 150, 240, false, p); c.drawLine(cx, cy + u * 0.1f, cx + u * 0.6f, cy - u * 0.5f, p); break;
            }
            case "oc": {   // bolt
                Path b = new Path(); b.moveTo(cx + u * 0.25f, cy - u); b.lineTo(cx - u * 0.55f, cy + u * 0.15f); b.lineTo(cx - u * 0.05f, cy + u * 0.15f); b.lineTo(cx - u * 0.25f, cy + u); b.lineTo(cx + u * 0.55f, cy - u * 0.15f); b.lineTo(cx + u * 0.05f, cy - u * 0.15f); b.close();
                p.setStyle(Paint.Style.FILL); c.drawPath(b, p); break;
            }
            case "home": {   // house
                p.setStyle(Paint.Style.STROKE);
                Path h = new Path(); h.moveTo(cx - u, cy); h.lineTo(cx, cy - u * 0.9f); h.lineTo(cx + u, cy); h.moveTo(cx - u * 0.7f, cy - u * 0.2f); h.lineTo(cx - u * 0.7f, cy + u * 0.85f); h.lineTo(cx + u * 0.7f, cy + u * 0.85f); h.lineTo(cx + u * 0.7f, cy - u * 0.2f); c.drawPath(h, p); break;
            }
            case "apps": {   // four squares
                p.setStyle(Paint.Style.FILL); float g = u * 0.12f;
                c.drawRoundRect(cx - u, cy - u, cx - g, cy - g, 4, 4, p); c.drawRoundRect(cx + g, cy - u, cx + u, cy - g, 4, 4, p);
                c.drawRoundRect(cx - u, cy + g, cx - g, cy + u, 4, 4, p); c.drawRoundRect(cx + g, cy + g, cx + u, cy + u, 4, 4, p); break;
            }
            case "picture": {   // half filled circle
                p.setStyle(Paint.Style.STROKE); c.drawCircle(cx, cy, u, p); p.setStyle(Paint.Style.FILL); c.drawArc(box, 90, 180, true, p); break;
            }
            case "display": {   // screen
                p.setStyle(Paint.Style.STROKE); c.drawRoundRect(cx - u, cy - u * 0.7f, cx + u, cy + u * 0.7f, u * 0.2f, u * 0.2f, p); break;
            }
            case "memory": {   // stacked bars
                p.setStyle(Paint.Style.STROKE); for (int i = -1; i <= 1; i++) c.drawLine(cx - u, cy + i * u * 0.7f, cx + u, cy + i * u * 0.7f, p); break;
            }
            case "connect": {   // wifi arcs
                p.setStyle(Paint.Style.STROKE);
                for (int i = 1; i <= 3; i++) { float r = u * 0.45f * i; c.drawArc(new RectF(cx - r, cy + u * 0.5f - r, cx + r, cy + u * 0.5f + r), 225, 90, false, p); }
                p.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy + u * 0.5f, R * 0.1f, p); break;
            }
            default: {   // system: ring with a dot
                p.setStyle(Paint.Style.STROKE); c.drawCircle(cx, cy, u * 0.85f, p); p.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, u * 0.3f, p);
            }
        }
    }
}

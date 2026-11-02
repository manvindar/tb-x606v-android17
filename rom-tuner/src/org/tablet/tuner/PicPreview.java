package org.tablet.tuner;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * A drawn scene for judging contrast and saturation. The left half is drawn as is (the display already applies the settings in effect);
 * the right half is drawn through the extra change the pending settings would add. An estimate: the real engine does the real work.
 */
final class PicPreview extends View {
    private float contrastRel = 1f, satRel = 1f;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int dim = 0xFF999999, ink = 0xFFFFFFFF;
    private Bitmap photo;

    PicPreview(Context c) { super(c); }

    /** A real picture (the wallpaper) to judge by; the drawn scene is used while there is none. */
    void setPhoto(Bitmap b) { photo = b; invalidate(); }

    void set(float contrastRel, float satRel, int ink, int dim) { this.contrastRel = contrastRel; this.satRel = satRel; this.ink = ink; this.dim = dim; invalidate(); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(resolveSize(0, w), (int) dp(210)); }

    private void scene(Canvas c, float w, float h) {
        if (photo != null) {   // centre crop to the area
            float pa = (float) photo.getWidth() / photo.getHeight(), ra = w / h;
            int sw = pa > ra ? Math.round(photo.getHeight() * ra) : photo.getWidth(), sh = pa > ra ? photo.getHeight() : Math.round(photo.getWidth() / ra);
            int sx = (photo.getWidth() - sw) / 2, sy = (photo.getHeight() - sh) / 2;
            c.drawBitmap(photo, new Rect(sx, sy, sx + sw, sy + sh), new RectF(0, 0, w, h), null);
            return;
        }
        p.setStyle(Paint.Style.FILL); p.setShader(new LinearGradient(0, 0, 0, h * 0.62f, 0xFF3F7BD0, 0xFFBFD9F2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.62f, p); p.setShader(null);
        p.setColor(0xFFFFD54F); c.drawCircle(w * 0.18f, h * 0.2f, h * 0.09f, p);                       // sun
        p.setColor(0xFF2E7D32); c.drawRect(0, h * 0.62f, w, h, p);                                       // field
        Path hill = new Path(); hill.moveTo(0, h * 0.62f); hill.quadTo(w * 0.25f, h * 0.4f, w * 0.55f, h * 0.62f); hill.close(); p.setColor(0xFF1B5E20); c.drawPath(hill, p);
        p.setColor(0xFFD9A98A); c.drawCircle(w * 0.72f, h * 0.38f, h * 0.13f, p);                        // skin
        p.setColor(0xFFB23A48); c.drawRoundRect(w * 0.64f, h * 0.52f, w * 0.8f, h * 0.9f, 12, 12, p);    // shirt
        p.setColor(0xFF0C0C0C); c.drawRect(w * 0.02f, h * 0.78f, w * 0.1f, h * 0.96f, p);                // deep shadow
        p.setColor(0xFFF2F2F2); c.drawRect(w * 0.12f, h * 0.78f, w * 0.2f, h * 0.96f, p);                // highlight
        int[] sw = {0xFFE53935, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1, 0xFF3949AB, 0xFFD81B60};
        for (int i = 0; i < sw.length; i++) { p.setColor(sw[i]); c.drawRect(w * (0.24f + i * 0.055f), h * 0.84f, w * (0.24f + (i + 1) * 0.055f), h * 0.96f, p); }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), half = w / 2f;
        Path clip = new Path(); clip.addRoundRect(new RectF(0, 0, w, h), dp(20), dp(20), Path.Direction.CW);
        c.save(); c.clipPath(clip);
        c.save(); c.clipRect(0, 0, half, h); scene(c, half, h); c.restore();   // the same picture on both sides
        ColorMatrix m = new ColorMatrix(); m.setSaturation(satRel);
        ColorMatrix k = new ColorMatrix(new float[]{contrastRel, 0, 0, 0, 127.5f * (1 - contrastRel), 0, contrastRel, 0, 0, 127.5f * (1 - contrastRel), 0, 0, contrastRel, 0, 127.5f * (1 - contrastRel), 0, 0, 0, 1, 0});
        m.postConcat(k);
        Paint f = new Paint(); f.setColorFilter(new ColorMatrixColorFilter(m));
        c.save(); c.translate(half, 0); c.clipRect(0, 0, half, h);
        c.saveLayer(0, 0, half, h, f); scene(c, half, h); c.restore();
        c.restore();
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); c.drawRect(half - dp(1.5f), 0, half + dp(1.5f), h, p);
        c.restore();
        p.setTextSize(dp(12)); p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setColor(0xCC000000); c.drawRoundRect(dp(10), dp(10), dp(66), dp(32), dp(11), dp(11), p); c.drawRoundRect(half + dp(10), dp(10), half + dp(120), dp(32), dp(11), dp(11), p);
        p.setColor(0xFFFFFFFF); c.drawText("Now", dp(22), dp(26), p); c.drawText("With changes", half + dp(20), dp(26), p);
    }
}

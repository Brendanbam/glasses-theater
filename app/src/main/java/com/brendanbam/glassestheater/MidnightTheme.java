package com.brendanbam.glassestheater;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * Shared warm walnut, champagne-gold, and amber lighting palette + custom vector drawables
 * for the Midnight Library interface.
 */
final class MidnightTheme {

    static final int BG_VOID = Color.BLACK;
    static final int IVORY = 0xFFFFF7E8;
    static final int GOLD_BRIGHT = 0xFFFFE6B5;
    static final int GOLD = 0xFFE5BA73;
    static final int GOLD_MUTED = 0xFFC5A87E;
    static final int GOLD_DIM = 0xFF8C7250;
    static final int AMBER_GLOW = 0xFFF4B254;
    static final int WALNUT_DEEP = 0xFF140C08;
    static final int WALNUT_CARD = 0xFF1E120C;
    static final int WALNUT_PANEL = 0xEB180F0A;
    static final int WALNUT_BORDER = 0x88CFA366;
    static final int WALNUT_BORDER_SUBTLE = 0x4DD4AA6A;

    private static final int[][] FALLBACK_PALETTES = {
            {0xFF1B2B3E, 0xFF0E1724}, // Midnight navy cloth
            {0xFF3E1B20, 0xFF210D10}, // Oxblood burgundy cloth
            {0xFF1C352A, 0xFF0E1E17}, // Deep forest cloth
            {0xFF2F213D, 0xFF181022}, // Dark plum cloth
            {0xFF3B2A1A, 0xFF20150C}, // Burnished leather
            {0xFF1E3238, 0xFF0F1B1F}  // Deep slate teal
    };

    private MidnightTheme() { }

    static Typeface serifRegular() { return Typeface.SERIF; }
    static Typeface serifBold() { return Typeface.create(Typeface.SERIF, Typeface.BOLD); }
    static Typeface serifBoldItalic() { return Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC); }

    static GradientDrawable warmPill(int fill, int stroke, float strokePx, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0f) d.setStroke(Math.max(1, Math.round(strokePx)), stroke);
        return d;
    }

    /** Crisp vector open-book icon matching the Midnight Library header & Resume Reading button. */
    static final class OpenBookView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        OpenBookView(Context c, int color) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        void setTint(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float sw = Math.max(1.6f, Math.min(w, h) * 0.075f);
            paint.setStrokeWidth(sw);
            float pad = sw * 1.1f;
            float cx = w * 0.5f;
            float topY = pad + h * 0.08f;
            float botY = h - pad - h * 0.06f;

            // Left page
            path.reset();
            path.moveTo(cx, topY + h * 0.10f);
            path.quadTo(cx - w * 0.22f, topY - h * 0.04f, pad + w * 0.04f, topY + h * 0.05f);
            path.lineTo(pad, botY - h * 0.06f);
            path.quadTo(cx - w * 0.22f, botY - h * 0.14f, cx, botY);
            canvas.drawPath(path, paint);

            // Right page
            path.reset();
            path.moveTo(cx, topY + h * 0.10f);
            path.quadTo(cx + w * 0.22f, topY - h * 0.04f, w - pad - w * 0.04f, topY + h * 0.05f);
            path.lineTo(w - pad, botY - h * 0.06f);
            path.quadTo(cx + w * 0.22f, botY - h * 0.14f, cx, botY);
            canvas.drawPath(path, paint);

            // Center spine crease
            canvas.drawLine(cx, topY + h * 0.11f, cx, botY, paint);
        }
    }

    /** Crisp vector clapperboard icon matching the Cinema / Open Video card. */
    static final class ClapperIconView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        ClapperIconView(Context c, int color) {
            super(c);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFF1A100B);
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }

        void setTint(int color) {
            fill.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = Math.min(w, h) * 0.11f;

            // Main slate body
            rect.set(w * 0.08f, h * 0.34f, w * 0.92f, h * 0.90f);
            canvas.drawRoundRect(rect, r, r, fill);

            // Angled clapper bar on top
            canvas.save();
            canvas.rotate(-10f, w * 0.12f, h * 0.32f);
            rect.set(w * 0.08f, h * 0.12f, w * 0.90f, h * 0.31f);
            canvas.drawRoundRect(rect, r * 0.7f, r * 0.7f, fill);

            // Diagonal dark stripes on clapper top
            stroke.setStrokeWidth(Math.max(2f, w * 0.075f));
            for (int i = 0; i < 3; i++) {
                float sx = w * (0.24f + i * 0.22f);
                canvas.drawLine(sx, h * 0.12f, sx - w * 0.07f, h * 0.31f, stroke);
            }
            canvas.restore();

            // Subtle dark play triangle cutout inside slate
            Path play = new Path();
            float cx = w * 0.50f, cy = h * 0.62f, tr = Math.min(w, h) * 0.13f;
            play.moveTo(cx - tr * 0.65f, cy - tr);
            play.lineTo(cx + tr, cy);
            play.lineTo(cx - tr * 0.65f, cy + tr);
            play.close();
            Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
            cut.setColor(0xFF1B100A);
            canvas.drawPath(play, cut);
        }
    }

    /**
     * Collector cloth-bound hardcover fallback drawable for manga that have no local or downloaded cover yet.
     */
    static final class BookFallbackDrawable extends Drawable {
        private final String title;
        private final int topColor, botColor;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        BookFallbackDrawable(String title) {
            this.title = title == null ? "" : title;
            int idx = Math.abs(this.title.hashCode()) % FALLBACK_PALETTES.length;
            this.topColor = FALLBACK_PALETTES[idx][0];
            this.botColor = FALLBACK_PALETTES[idx][1];
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setColor(0x88E5BA73);
            textPaint.setColor(IVORY);
            textPaint.setTypeface(serifBoldItalic());
            textPaint.setShadowLayer(4f, 0f, 1.5f, 0xCC000000);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;

            bgPaint.setShader(new LinearGradient(0, 0, w * 0.4f, h, topColor, botColor, Shader.TileMode.CLAMP));
            canvas.drawRect(b, bgPaint);

            // Subtle left book spine crease shadow
            spinePaint.setShader(new LinearGradient(0, 0, w * 0.14f, 0, 0x80000000, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(b.left, b.top, b.left + w * 0.14f, b.bottom, spinePaint);

            // Embossed antique gold inner frame
            float inset = Math.max(4f, w * 0.08f);
            framePaint.setStrokeWidth(Math.max(1.2f, w * 0.012f));
            canvas.drawRect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, framePaint);

            // Ornamental gold divider line near bottom
            float divY = b.bottom - h * 0.20f;
            canvas.drawLine(b.left + inset * 2.1f, divY, b.right - inset * 2.1f, divY, framePaint);

            // Centered serif title
            int textW = Math.max(20, Math.round(w - inset * 3.0f));
            textPaint.setTextSize(Math.max(11f, w * 0.125f));
            StaticLayout sl = StaticLayout.Builder.obtain(title, 0, title.length(), textPaint, textW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(4)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build();
            canvas.save();
            float ty = b.top + (h * 0.72f - sl.getHeight()) * 0.5f;
            canvas.translate(b.left + (w - textW) * 0.5f, Math.max(b.top + inset * 1.8f, ty));
            sl.draw(canvas);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}

package com.brendanbam.glassestheater;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Custom Midnight Library UI components:
 * - MangaBookCardView: upright real manga book resting on the walnut shelf with warm-gold lift focus
 * - MidnightFeaturedPanel: right-hand showcase with live reading progress & glowing Resume Reading pill
 * - MidnightCinemaCard: bottom-right Cinema / Open Video shortcut with bundled golden filmstrip art
 * - MidnightStageLayout: proportional 1376:768 floating bookshelf layout aligned to the walnut bays
 */
final class MidnightViews {

    private static final DecelerateInterpolator SMOOTH = new DecelerateInterpolator(1.6f);

    private MidnightViews() { }

    static void drawWarmGlow(Canvas canvas, RectF rect, float radius, int color, float maxSpread, float baseAlpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        int steps = 5;
        for (int i = steps; i >= 1; i--) {
            float f = i / (float) steps;
            float spread = maxSpread * f;
            int a = Math.min(255, Math.max(0, Math.round(255f * baseAlpha * (1f - f * 0.72f) * 0.42f)));
            p.setColor((color & 0x00FFFFFF) | (a << 24));
            p.setStrokeWidth(Math.max(2f, spread * 0.9f));
            RectF r = new RectF(rect.left - spread * 0.45f, rect.top - spread * 0.45f,
                    rect.right + spread * 0.45f, rect.bottom + spread * 0.45f);
            canvas.drawRoundRect(r, radius + spread * 0.35f, radius + spread * 0.35f, p);
        }
    }

    /** Upright manga book card standing on a walnut shelf plank. */
    static final class MangaBookCardView extends FrameLayout {
        private final ImageView coverView;
        private final View titleScrim;
        private final TextView titleLabel;
        private final View progressDot;
        private final Paint spinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF boundsRect = new RectF();
        private boolean selected;
        private boolean focusedOnShelf;
        private boolean hasRealBitmap;
        private float cornerPx = 8f;

        MangaBookCardView(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);

            coverView = new ImageView(c);
            coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            coverView.setClipToOutline(true);
            addView(coverView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            titleScrim = new View(c);
            GradientDrawable scrim = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x99120A06, 0xE60D0704}
            );
            titleScrim.setBackground(scrim);
            LayoutParams slp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            addView(titleScrim, slp);

            titleLabel = new TextView(c);
            titleLabel.setTextColor(MidnightTheme.IVORY);
            titleLabel.setTypeface(MidnightTheme.serifBold());
            titleLabel.setGravity(Gravity.CENTER_HORIZONTAL);
            titleLabel.setMaxLines(2);
            titleLabel.setEllipsize(TextUtils.TruncateAt.END);
            titleLabel.setShadowLayer(4f, 0f, 1.5f, 0xEE000000);
            LayoutParams tlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            addView(titleLabel, tlp);

            progressDot = new View(c);
            addView(progressDot, new LayoutParams(10, 10, Gravity.TOP | Gravity.END));

            borderPaint.setStyle(Paint.Style.STROKE);
            shadowPaint.setColor(0x99000000);
        }

        void bind(MihonStore.Series series, Bitmap cover, boolean hasProgress, float stageScale) {
            cornerPx = Math.max(4f, 6f * stageScale);
            coverView.setBackground(MidnightTheme.warmPill(MidnightTheme.WALNUT_CARD, 0, 0, cornerPx));
            titleLabel.setText(series.title);
            titleLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(9.5f, 13.5f * stageScale));
            int hPad = Math.max(3, Math.round(6f * stageScale));
            int bPad = Math.max(3, Math.round(5f * stageScale));
            titleLabel.setPadding(hPad, 0, hPad, bPad);

            LayoutParams slp = (LayoutParams) titleScrim.getLayoutParams();
            slp.height = Math.max(20, Math.round(48f * stageScale));
            titleScrim.setLayoutParams(slp);

            hasRealBitmap = (cover != null);
            if (cover != null) {
                coverView.setImageDrawable(null);
                coverView.setImageBitmap(cover);
                titleScrim.setVisibility(VISIBLE);
                titleLabel.setVisibility(VISIBLE);
            } else {
                coverView.setImageBitmap(null);
                coverView.setImageDrawable(new MidnightTheme.BookFallbackDrawable(series.title));
                titleScrim.setVisibility(GONE);
                titleLabel.setVisibility(GONE);
            }

            int dot = Math.max(6, Math.round(9f * stageScale));
            int margin = Math.max(4, Math.round(7f * stageScale));
            LayoutParams dlp = (LayoutParams) progressDot.getLayoutParams();
            dlp.width = dot;
            dlp.height = dot;
            dlp.topMargin = margin;
            dlp.rightMargin = margin;
            progressDot.setLayoutParams(dlp);
            progressDot.setBackground(MidnightTheme.warmPill(MidnightTheme.GOLD_BRIGHT, 0xFF28180C, Math.max(1f, 1.4f * stageScale), dot / 2f));
            progressDot.setVisibility(hasProgress ? VISIBLE : GONE);
            invalidate();
        }

        void setCoverBitmap(Bitmap cover) {
            if (cover == null) return;
            hasRealBitmap = true;
            coverView.setImageDrawable(null);
            coverView.setImageBitmap(cover);
            titleScrim.setVisibility(VISIBLE);
            titleLabel.setVisibility(VISIBLE);
            invalidate();
        }

        void setBookSelected(boolean isSelected, boolean shelfHasFocus, boolean animate) {
            if (this.selected == isSelected && this.focusedOnShelf == shelfHasFocus) return;
            this.selected = isSelected;
            this.focusedOnShelf = shelfHasFocus;
            boolean activeLift = isSelected && shelfHasFocus;
            float targetScale = activeLift ? 1.08f : (isSelected ? 1.035f : 1.0f);
            float liftPx = activeLift ? -Math.max(5f, getHeight() * 0.048f) : (isSelected ? -Math.max(2f, getHeight() * 0.02f) : 0f);
            setZ(activeLift ? 24f : (isSelected ? 12f : 0f));
            animate().cancel();
            if (animate) {
                animate().scaleX(targetScale).scaleY(targetScale).translationY(liftPx)
                        .setDuration(145).setInterpolator(SMOOTH).start();
            } else {
                setScaleX(targetScale);
                setScaleY(targetScale);
                setTranslationY(liftPx);
            }
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            boundsRect.set(0, 0, w, h);

            // Soft shelf contact shadow under the book
            shadowPaint.setColor(selected ? 0x77000000 : 0x5A000000);
            canvas.drawRoundRect(w * 0.08f, h - 1f, w * 0.92f, h + Math.max(3f, h * 0.018f), 8f, 8f, shadowPaint);

            // Warm outer glow behind selected book (drawn before children so it radiates cleanly behind the cover)
            if (selected) {
                float spread = Math.max(6f, w * (focusedOnShelf ? 0.11f : 0.06f));
                drawWarmGlow(canvas, boundsRect, cornerPx,
                        focusedOnShelf ? MidnightTheme.GOLD_BRIGHT : MidnightTheme.AMBER_GLOW,
                        spread, focusedOnShelf ? 0.92f : 0.50f);
            }

            super.dispatchDraw(canvas);

            // Subtle physical 3D book spine crease along left edge
            spinePaint.setShader(new LinearGradient(0, 0, w * 0.09f, 0,
                    0x66000000, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0, 0, w * 0.09f, h, cornerPx, cornerPx, spinePaint);

            // Subtle warm overhead spotlight glint along top edge
            Paint topGlint = new Paint(Paint.ANTI_ALIAS_FLAG);
            topGlint.setShader(new LinearGradient(0, 0, 0, h * 0.08f,
                    0x38FFE3AC, 0x00FFE3AC, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0, 0, w, h * 0.08f, cornerPx, cornerPx, topGlint);

            // Crisp border outline: bright warm-white/gold when selected, delicate dark rim when idle
            if (selected) {
                borderPaint.setStrokeWidth(Math.max(2f, w * (focusedOnShelf ? 0.026f : 0.016f)));
                borderPaint.setColor(focusedOnShelf ? 0xFFFFF4DC : MidnightTheme.GOLD);
                canvas.drawRoundRect(boundsRect, cornerPx, cornerPx, borderPaint);
            } else {
                borderPaint.setStrokeWidth(Math.max(1f, w * 0.008f));
                borderPaint.setColor(0x38FFE2B0);
                canvas.drawRoundRect(boundsRect, cornerPx, cornerPx, borderPaint);
            }
        }
    }

    /** Right-hand Featured Manga Panel matching "The Last Horizon" showcase in the reference image. */
    static final class MidnightFeaturedPanel extends FrameLayout {
        private final ImageView bgArt;
        private final View scrimView;
        private final LinearLayout contentCol;
        private final TextView titleView;
        private final LinearLayout progressRow;
        private final MidnightTheme.OpenBookView progressIcon;
        private final TextView progressText;
        private final TextView metaLine;
        private final LinearLayout resumeButton;
        private final MidnightTheme.OpenBookView resumeIcon;
        private final TextView resumeLabel;
        private final TextView chaptersHint;
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cardRect = new RectF();
        private float cornerPx = 16f;
        private boolean buttonFocused;

        MidnightFeaturedPanel(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);

            FrameLayout clipBox = new FrameLayout(c);
            clipBox.setClipToOutline(true);
            addView(clipBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            bgArt = new ImageView(c);
            bgArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            clipBox.addView(bgArt, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            scrimView = new View(c);
            clipBox.addView(scrimView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            contentCol = new LinearLayout(c);
            contentCol.setOrientation(LinearLayout.VERTICAL);
            contentCol.setGravity(Gravity.CENTER_VERTICAL);
            clipBox.addView(contentCol, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            titleView = new TextView(c);
            titleView.setTextColor(MidnightTheme.IVORY);
            titleView.setTypeface(MidnightTheme.serifBoldItalic());
            titleView.setMaxLines(2);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setShadowLayer(8f, 0f, 2f, 0xEE000000);
            contentCol.addView(titleView);

            progressRow = new LinearLayout(c);
            progressRow.setOrientation(LinearLayout.HORIZONTAL);
            progressRow.setGravity(Gravity.CENTER_VERTICAL);
            progressIcon = new MidnightTheme.OpenBookView(c, MidnightTheme.GOLD_BRIGHT);
            progressRow.addView(progressIcon);
            progressText = new TextView(c);
            progressText.setTextColor(0xFFF3E5CC);
            progressText.setMaxLines(1);
            progressText.setEllipsize(TextUtils.TruncateAt.END);
            progressText.setShadowLayer(6f, 0f, 1.5f, 0xEE000000);
            progressRow.addView(progressText);
            contentCol.addView(progressRow);

            metaLine = new TextView(c);
            metaLine.setTextColor(MidnightTheme.GOLD_MUTED);
            metaLine.setMaxLines(1);
            metaLine.setEllipsize(TextUtils.TruncateAt.END);
            metaLine.setShadowLayer(5f, 0f, 1f, 0xEE000000);
            contentCol.addView(metaLine);

            // Spacer pushing Resume Reading gracefully to lower third like the reference
            View spacer = new View(c);
            contentCol.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

            resumeButton = new LinearLayout(c);
            resumeButton.setOrientation(LinearLayout.HORIZONTAL);
            resumeButton.setGravity(Gravity.CENTER);
            resumeIcon = new MidnightTheme.OpenBookView(c, MidnightTheme.IVORY);
            resumeButton.addView(resumeIcon);
            resumeLabel = new TextView(c);
            resumeLabel.setTextColor(MidnightTheme.IVORY);
            resumeLabel.setTypeface(Typeface.DEFAULT_BOLD);
            resumeLabel.setAllCaps(true);
            resumeLabel.setLetterSpacing(0.06f);
            resumeLabel.setMaxLines(1);
            resumeLabel.setEllipsize(TextUtils.TruncateAt.END);
            resumeButton.addView(resumeLabel);
            contentCol.addView(resumeButton, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            chaptersHint = new TextView(c);
            chaptersHint.setTextColor(MidnightTheme.GOLD_MUTED);
            chaptersHint.setGravity(Gravity.CENTER);
            chaptersHint.setMaxLines(1);
            contentCol.addView(chaptersHint, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            borderPaint.setStyle(Paint.Style.STROKE);
        }

        void applyScale(float s) {
            cornerPx = Math.max(8f, 16f * s);
            View clipBox = getChildAt(0);
            clipBox.setBackground(MidnightTheme.warmPill(0xFF140D0A, 0, 0, cornerPx));

            // Atmospheric dark espresso scrim preserving cover art on the right
            GradientDrawable scrim = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xE80C0806, 0xB8100A07, 0x58120B07}
            );
            scrimView.setBackground(scrim);

            int padH = Math.max(10, Math.round(22f * s));
            int padV = Math.max(10, Math.round(20f * s));
            contentCol.setPadding(padH, padV, padH, Math.max(8, Math.round(14f * s)));

            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(15f, 34f * s));
            titleView.setLineSpacing(0f, 0.96f);

            LinearLayout.LayoutParams prLp = (LinearLayout.LayoutParams) progressRow.getLayoutParams();
            prLp.topMargin = Math.max(6, Math.round(14f * s));
            progressRow.setLayoutParams(prLp);

            int piSize = Math.max(12, Math.round(20f * s));
            LinearLayout.LayoutParams piLp = new LinearLayout.LayoutParams(piSize, Math.round(piSize * 0.82f));
            piLp.rightMargin = Math.max(5, Math.round(9f * s));
            progressIcon.setLayoutParams(piLp);
            progressText.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(11f, 17f * s));

            LinearLayout.LayoutParams mlLp = (LinearLayout.LayoutParams) metaLine.getLayoutParams();
            mlLp.topMargin = Math.max(3, Math.round(6f * s));
            metaLine.setLayoutParams(mlLp);
            metaLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(9.5f, 13f * s));

            int riSize = Math.max(12, Math.round(20f * s));
            LinearLayout.LayoutParams riLp = new LinearLayout.LayoutParams(riSize, Math.round(riSize * 0.82f));
            riLp.rightMargin = Math.max(5, Math.round(9f * s));
            resumeIcon.setLayoutParams(riLp);
            resumeLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10f, 15.5f * s));

            int btnPadV = Math.max(7, Math.round(13f * s));
            int btnPadH = Math.max(8, Math.round(14f * s));
            resumeButton.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);

            LinearLayout.LayoutParams chLp = (LinearLayout.LayoutParams) chaptersHint.getLayoutParams();
            chLp.topMargin = Math.max(4, Math.round(8f * s));
            chaptersHint.setLayoutParams(chLp);
            chaptersHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(8.5f, 11.5f * s));

            updateButtonAppearance(s);
        }

        private float lastScale = 1f;

        void bind(String title, String progressLabel, String metaInfo, String actionText, String secondaryHint, Bitmap art, float s) {
            this.lastScale = s;
            titleView.setText(title);
            progressText.setText(progressLabel);
            progressRow.setVisibility(TextUtils.isEmpty(progressLabel) ? GONE : VISIBLE);
            metaLine.setText(metaInfo);
            metaLine.setVisibility(TextUtils.isEmpty(metaInfo) ? GONE : VISIBLE);
            resumeLabel.setText(actionText + "  ▶");
            chaptersHint.setText(secondaryHint);
            chaptersHint.setVisibility(TextUtils.isEmpty(secondaryHint) ? GONE : VISIBLE);

            if (art != null) {
                bgArt.setImageDrawable(null);
                bgArt.setImageBitmap(art);
                bgArt.setAlpha(0.78f);
            } else {
                bgArt.setImageBitmap(null);
                bgArt.setImageDrawable(new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFF211611, 0xFF150D09, 0xFF0E0907}
                ));
                bgArt.setAlpha(1f);
            }
            updateButtonAppearance(s);
        }

        void setActionClickListener(OnClickListener l) {
            resumeButton.setOnClickListener(l);
            setOnClickListener(l);
        }

        void setChaptersClickListener(OnClickListener l) {
            chaptersHint.setOnClickListener(l);
        }

        void setButtonFocused(boolean focused) {
            this.buttonFocused = focused;
            updateButtonAppearance(lastScale);
            resumeButton.animate().cancel();
            resumeButton.animate()
                    .scaleX(focused ? 1.04f : 1.0f)
                    .scaleY(focused ? 1.04f : 1.0f)
                    .setDuration(130)
                    .setInterpolator(SMOOTH)
                    .start();
            invalidate();
        }

        private void updateButtonAppearance(float s) {
            float btnRadius = Math.max(8f, 13f * s);
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    buttonFocused
                            ? new int[]{0xEE5C3D1E, 0xEE3B2410}
                            : new int[]{0xD83A2616, 0xD824160C}
            );
            bg.setCornerRadius(btnRadius);
            bg.setStroke(
                    Math.max(1, Math.round((buttonFocused ? 2.6f : 1.5f) * s)),
                    buttonFocused ? 0xFFFFF4DC : 0xFFE5BA73
            );
            resumeButton.setBackground(bg);
            resumeLabel.setTextColor(buttonFocused ? Color.WHITE : MidnightTheme.IVORY);
            resumeIcon.setTint(buttonFocused ? Color.WHITE : MidnightTheme.GOLD_BRIGHT);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            cardRect.set(0, 0, getWidth(), getHeight());
            if (buttonFocused) {
                drawWarmGlow(canvas, cardRect, cornerPx, MidnightTheme.AMBER_GLOW, Math.max(8f, getWidth() * 0.06f), 0.72f);
            }
            super.dispatchDraw(canvas);
            borderPaint.setStrokeWidth(Math.max(1.2f, (buttonFocused ? 2.4f : 1.3f) * lastScale));
            borderPaint.setColor(buttonFocused ? 0xFFFFEBC4 : MidnightTheme.WALNUT_BORDER);
            canvas.drawRoundRect(cardRect, cornerPx, cornerPx, borderPaint);
        }
    }

    /** Bottom-right Cinema / Open Video shortcut card with golden 35mm filmstrip artwork. */
    static final class MidnightCinemaCard extends FrameLayout {
        private final ImageView bgFilm;
        private final LinearLayout row;
        private final MidnightTheme.ClapperIconView icon;
        private final TextView title;
        private final TextView subtitle;
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cardRect = new RectF();
        private float cornerPx = 14f;
        private float lastScale = 1f;
        private boolean cardFocused;

        MidnightCinemaCard(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);

            FrameLayout clipBox = new FrameLayout(c);
            clipBox.setClipToOutline(true);
            addView(clipBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            bgFilm = new ImageView(c);
            bgFilm.setImageResource(R.drawable.midnight_cinema_bg);
            bgFilm.setScaleType(ImageView.ScaleType.CENTER_CROP);
            clipBox.addView(bgFilm, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            View scrim = new View(c);
            scrim.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xCC110B07, 0x80130C08, 0x1A130C08}
            ));
            clipBox.addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            clipBox.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            icon = new MidnightTheme.ClapperIconView(c, MidnightTheme.IVORY);
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(c);
            textCol.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(c);
            title.setText("CINEMA");
            title.setTextColor(MidnightTheme.IVORY);
            title.setTypeface(MidnightTheme.serifBold());
            title.setLetterSpacing(0.08f);
            title.setShadowLayer(6f, 0f, 1.5f, 0xEE000000);
            textCol.addView(title);

            subtitle = new TextView(c);
            subtitle.setText("Open Video");
            subtitle.setTextColor(0xFFE7D3B3);
            subtitle.setShadowLayer(5f, 0f, 1f, 0xEE000000);
            textCol.addView(subtitle);

            row.addView(textCol);
            borderPaint.setStyle(Paint.Style.STROKE);
        }

        void applyScale(float s) {
            this.lastScale = s;
            cornerPx = Math.max(8f, 14f * s);
            getChildAt(0).setBackground(MidnightTheme.warmPill(0xFF140D09, 0, 0, cornerPx));
            int padH = Math.max(10, Math.round(20f * s));
            row.setPadding(padH, 0, padH, 0);

            int iconSize = Math.max(20, Math.round(36f * s));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSize, iconSize);
            ilp.rightMargin = Math.max(8, Math.round(14f * s));
            icon.setLayoutParams(ilp);

            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(12f, 22f * s));
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10f, 14.5f * s));
        }

        void setCardFocused(boolean focused) {
            if (this.cardFocused == focused) return;
            this.cardFocused = focused;
            animate().cancel();
            animate().scaleX(focused ? 1.035f : 1.0f).scaleY(focused ? 1.035f : 1.0f)
                    .setDuration(140).setInterpolator(SMOOTH).start();
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            cardRect.set(0, 0, getWidth(), getHeight());
            if (cardFocused) {
                drawWarmGlow(canvas, cardRect, cornerPx, MidnightTheme.GOLD_BRIGHT, Math.max(8f, getWidth() * 0.06f), 0.88f);
            }
            super.dispatchDraw(canvas);
            borderPaint.setStrokeWidth(Math.max(1.2f, (cardFocused ? 2.6f : 1.3f) * lastScale));
            borderPaint.setColor(cardFocused ? 0xFFFFF4DC : MidnightTheme.WALNUT_BORDER);
            canvas.drawRoundRect(cardRect, cornerPx, cornerPx, borderPaint);
        }
    }

    /**
     * Proportional 1376:768 floating bookshelf stage.
     * Aligns the real upright manga books, featured panel, cinema card, header, and controller hints
     * pixel-accurately onto the offline dark walnut bookshelf cabinet across every screen size (50–100%).
     */
    static final class MidnightStageLayout extends ViewGroup {
        static final float CABINET_RATIO = 1376f / 768f;
        static final int TOP_SLOTS = 5;
        static final int BOTTOM_SLOTS = 4;
        static final int PAGE_SLOTS = TOP_SLOTS + BOTTOM_SLOTS;

        final ImageView cabinetImage;
        final LinearLayout headerContainer;
        final TextView brandTitle;
        final TextView brandSubtitle;
        final MidnightTheme.OpenBookView headerBookIcon;
        final View headerLineLeft, headerLineRight;
        final TextView categoryPill;
        final TextView settingsPill;
        final TextView pageIndicator;
        final MangaBookCardView[] bookSlots = new MangaBookCardView[PAGE_SLOTS];
        final MidnightFeaturedPanel featuredPanel;
        final MidnightCinemaCard cinemaCard;
        final LinearLayout emptyOverlay;
        final TextView emptyTitle;
        final TextView emptyBody;
        final TextView emptyActionBtn;
        final LinearLayout bottomBar;
        final TextView resizeToast;

        private float currentScale = 1f;
        private boolean hasAppliedScale = false;
        private int stageW, stageH, stageX, stageY;

        MidnightStageLayout(Context c) {
            super(c);
            setBackgroundColor(MidnightTheme.BG_VOID);
            setClipChildren(false);
            setClipToPadding(false);

            // 1. Offline walnut cabinet background (pure #000000 around edges)
            cabinetImage = new ImageView(c);
            cabinetImage.setImageResource(R.drawable.midnight_shelf_cabinet);
            cabinetImage.setScaleType(ImageView.ScaleType.FIT_XY);
            addView(cabinetImage);

            // 2. Centered Header: — 📖 GLASSES THEATER — / MANGA LIBRARY
            headerContainer = new LinearLayout(c);
            headerContainer.setOrientation(LinearLayout.VERTICAL);
            headerContainer.setGravity(Gravity.CENTER);

            LinearLayout titleRow = new LinearLayout(c);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            headerLineLeft = new View(c);
            headerLineLeft.setBackgroundColor(0x88D9B170);
            titleRow.addView(headerLineLeft);

            headerBookIcon = new MidnightTheme.OpenBookView(c, MidnightTheme.GOLD_BRIGHT);
            titleRow.addView(headerBookIcon);

            brandTitle = new TextView(c);
            brandTitle.setText("GLASSES THEATER");
            brandTitle.setTextColor(MidnightTheme.GOLD_BRIGHT);
            brandTitle.setTypeface(MidnightTheme.serifRegular());
            brandTitle.setLetterSpacing(0.14f);
            brandTitle.setShadowLayer(10f, 0f, 0f, 0x88F4B254);
            titleRow.addView(brandTitle);

            headerLineRight = new View(c);
            headerLineRight.setBackgroundColor(0x88D9B170);
            titleRow.addView(headerLineRight);
            headerContainer.addView(titleRow, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            brandSubtitle = new TextView(c);
            brandSubtitle.setText("MANGA LIBRARY");
            brandSubtitle.setTextColor(MidnightTheme.GOLD_MUTED);
            brandSubtitle.setLetterSpacing(0.28f);
            headerContainer.addView(brandSubtitle, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            addView(headerContainer);

            // 3. Subtle top utility pills (Category filter left, Library & Settings right)
            categoryPill = createUtilityPill(c, "All");
            addView(categoryPill);
            settingsPill = createUtilityPill(c, "⚙  Library & Settings");
            addView(settingsPill);

            // 4. Subtle shelf pagination badge (shown only when > 9 books)
            pageIndicator = new TextView(c);
            pageIndicator.setTextColor(MidnightTheme.GOLD_BRIGHT);
            pageIndicator.setGravity(Gravity.CENTER);
            pageIndicator.setVisibility(GONE);
            addView(pageIndicator);

            // 5. 9 real upright manga book slots (5 top shelf, 4 bottom shelf)
            for (int i = 0; i < PAGE_SLOTS; i++) {
                bookSlots[i] = new MangaBookCardView(c);
                bookSlots[i].setVisibility(INVISIBLE);
                addView(bookSlots[i]);
            }

            // 6. Right alcove: Featured Panel + Cinema Card
            featuredPanel = new MidnightFeaturedPanel(c);
            addView(featuredPanel);

            cinemaCard = new MidnightCinemaCard(c);
            addView(cinemaCard);

            // 7. Graceful Empty / Scanning / Error card across shelves when no books are loaded yet
            emptyOverlay = new LinearLayout(c);
            emptyOverlay.setOrientation(LinearLayout.VERTICAL);
            emptyOverlay.setGravity(Gravity.CENTER);
            emptyOverlay.setVisibility(GONE);

            emptyTitle = new TextView(c);
            emptyTitle.setTextColor(MidnightTheme.GOLD_BRIGHT);
            emptyTitle.setTypeface(MidnightTheme.serifBold());
            emptyTitle.setGravity(Gravity.CENTER);
            emptyOverlay.addView(emptyTitle);

            emptyBody = new TextView(c);
            emptyBody.setTextColor(0xFFE5D4B8);
            emptyBody.setGravity(Gravity.CENTER);
            emptyOverlay.addView(emptyBody);

            emptyActionBtn = new TextView(c);
            emptyActionBtn.setTextColor(MidnightTheme.IVORY);
            emptyActionBtn.setTypeface(Typeface.DEFAULT_BOLD);
            emptyActionBtn.setGravity(Gravity.CENTER);
            emptyOverlay.addView(emptyActionBtn);
            addView(emptyOverlay);

            // 8. Bottom Controller Hints: (A) Select | (B) Back | [L1] / [R1] Resize
            bottomBar = new LinearLayout(c);
            bottomBar.setOrientation(LinearLayout.HORIZONTAL);
            bottomBar.setGravity(Gravity.CENTER);
            addView(bottomBar);

            // 9. Transient resize badge when L1/R1 adjusts screen size
            resizeToast = new TextView(c);
            resizeToast.setTextColor(MidnightTheme.GOLD_BRIGHT);
            resizeToast.setTypeface(Typeface.DEFAULT_BOLD);
            resizeToast.setGravity(Gravity.CENTER);
            resizeToast.setVisibility(GONE);
            addView(resizeToast);
        }

        private TextView createUtilityPill(Context c, String label) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextColor(MidnightTheme.GOLD_MUTED);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            return tv;
        }

        void styleUtilityPill(TextView pill, boolean focused) {
            float s = currentScale;
            pill.setTextColor(focused ? Color.WHITE : MidnightTheme.GOLD_MUTED);
            pill.setBackground(MidnightTheme.warmPill(
                    focused ? 0xEE4A3119 : 0x991A110B,
                    focused ? 0xFFFFF2D4 : 0x55CFA366,
                    (focused ? 2.2f : 1.0f) * s,
                    Math.max(10f, 18f * s)
            ));
            pill.animate().cancel();
            pill.animate().scaleX(focused ? 1.06f : 1.0f).scaleY(focused ? 1.06f : 1.0f)
                    .setDuration(120).start();
        }

        void rebuildBottomHints(Context c, float s, OnClickListener onSelect, OnClickListener onBack, OnClickListener onResize) {
            bottomBar.removeAllViews();
            bottomBar.addView(hintItem(c, "A", "Select", true, s, onSelect));
            bottomBar.addView(hintDivider(c, s));
            bottomBar.addView(hintItem(c, "B", "Back", true, s, onBack));
            bottomBar.addView(hintDivider(c, s));
            bottomBar.addView(resizeHintItem(c, s, onResize));
        }

        private View hintItem(Context c, String key, String label, boolean circle, float s, OnClickListener click) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOnClickListener(click);

            TextView badge = new TextView(c);
            badge.setText(key);
            badge.setTextColor(MidnightTheme.GOLD_BRIGHT);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setGravity(Gravity.CENTER);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10f, 15f * s));
            int size = Math.max(18, Math.round(28f * s));
            badge.setBackground(MidnightTheme.warmPill(0xCC18100A, MidnightTheme.GOLD, Math.max(1.2f, 1.8f * s), circle ? size / 2f : Math.max(5f, 7f * s)));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(size, size);
            blp.rightMargin = Math.max(5, Math.round(9f * s));
            row.addView(badge, blp);

            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextColor(MidnightTheme.IVORY);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10.5f, 16.5f * s));
            row.addView(tv);
            return row;
        }

        private View resizeHintItem(Context c, float s, OnClickListener click) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOnClickListener(click);

            int bw = Math.max(24, Math.round(36f * s));
            int bh = Math.max(18, Math.round(26f * s));
            float rad = Math.max(5f, 7f * s);
            float stroke = Math.max(1.2f, 1.6f * s);

            TextView l1 = new TextView(c);
            l1.setText("L1");
            l1.setTextColor(MidnightTheme.GOLD_BRIGHT);
            l1.setTypeface(Typeface.DEFAULT_BOLD);
            l1.setGravity(Gravity.CENTER);
            l1.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(9.5f, 13.5f * s));
            l1.setBackground(MidnightTheme.warmPill(0xCC18100A, MidnightTheme.GOLD, stroke, rad));
            row.addView(l1, new LinearLayout.LayoutParams(bw, bh));

            TextView slash = new TextView(c);
            slash.setText(" / ");
            slash.setTextColor(MidnightTheme.GOLD_MUTED);
            slash.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10f, 15f * s));
            row.addView(slash);

            TextView r1 = new TextView(c);
            r1.setText("R1");
            r1.setTextColor(MidnightTheme.GOLD_BRIGHT);
            r1.setTypeface(Typeface.DEFAULT_BOLD);
            r1.setGravity(Gravity.CENTER);
            r1.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(9.5f, 13.5f * s));
            r1.setBackground(MidnightTheme.warmPill(0xCC18100A, MidnightTheme.GOLD, stroke, rad));
            LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(bw, bh);
            r1lp.rightMargin = Math.max(6, Math.round(10f * s));
            row.addView(r1, r1lp);

            TextView lbl = new TextView(c);
            lbl.setText("Resize");
            lbl.setTextColor(MidnightTheme.IVORY);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10.5f, 16.5f * s));
            row.addView(lbl);
            return row;
        }

        private View hintDivider(Context c, float s) {
            View v = new View(c);
            v.setBackgroundColor(0x55D4AE72);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, Math.round(1.2f * s)), Math.max(12, Math.round(20f * s)));
            int m = Math.max(10, Math.round(22f * s));
            lp.leftMargin = m;
            lp.rightMargin = m;
            v.setLayoutParams(lp);
            return v;
        }

        float scale() { return currentScale; }

        private Runnable onScaleApplied;
        void setOnScaleApplied(Runnable r) { this.onScaleApplied = r; }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int availW = MeasureSpec.getSize(widthMeasureSpec);
            int availH = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(availW, availH);
            if (availW <= 0 || availH <= 0) return;

            // Fit exact 1376:768 cabinet stage centered inside ScreenFrame
            if (availW / (float) availH > CABINET_RATIO) {
                stageH = availH;
                stageW = Math.round(stageH * CABINET_RATIO);
            } else {
                stageW = availW;
                stageH = Math.round(stageW / CABINET_RATIO);
            }
            stageX = (availW - stageW) / 2;
            stageY = (availH - stageH) / 2;

            float newScale = stageH / 768f;
            boolean scaleChanged = !hasAppliedScale || Math.abs(newScale - currentScale) > 0.015f;
            currentScale = newScale;
            if (scaleChanged) {
                hasAppliedScale = true;
                applyTypographyScale(newScale);
                if (onScaleApplied != null) post(onScaleApplied);
            }

            // Measure cabinet image to exact stage size
            cabinetImage.measure(
                    MeasureSpec.makeMeasureSpec(stageW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(stageH, MeasureSpec.EXACTLY)
            );

            // Header centered in top pure-black margin (y = 0.00 .. 0.132)
            headerContainer.measure(
                    MeasureSpec.makeMeasureSpec(Math.round(stageW * 0.56f), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(Math.round(stageH * 0.132f), MeasureSpec.AT_MOST)
            );

            int pillMaxW = Math.round(stageW * 0.20f);
            int pillH = Math.max(22, Math.round(36f * newScale));
            categoryPill.measure(MeasureSpec.makeMeasureSpec(pillMaxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(pillH, MeasureSpec.EXACTLY));
            settingsPill.measure(MeasureSpec.makeMeasureSpec(Math.round(stageW * 0.22f), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(pillH, MeasureSpec.EXACTLY));
            pageIndicator.measure(MeasureSpec.makeMeasureSpec(Math.round(stageW * 0.18f), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(Math.max(18, Math.round(24f * newScale)), MeasureSpec.EXACTLY));

            // Top shelf books: 5 upright slots in x=[0.106 .. 0.696], y=[0.222 .. 0.516]
            int topBayW = Math.round(stageW * 0.590f);
            int topGap = Math.max(4, Math.round(stageW * 0.014f));
            int topBookW = (topBayW - topGap * (TOP_SLOTS - 1)) / TOP_SLOTS;
            int topBookH = Math.round(stageH * 0.292f);
            for (int i = 0; i < TOP_SLOTS; i++) {
                bookSlots[i].measure(
                        MeasureSpec.makeMeasureSpec(topBookW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(topBookH, MeasureSpec.EXACTLY)
                );
            }

            // Bottom shelf books: 4 upright slots in x=[0.195 .. 0.575], y=[0.567 .. 0.799]
            int botBayW = Math.round(stageW * 0.380f);
            int botGap = Math.max(4, Math.round(stageW * 0.012f));
            int botBookW = (botBayW - botGap * (BOTTOM_SLOTS - 1)) / BOTTOM_SLOTS;
            int botBookH = Math.round(stageH * 0.232f);
            for (int i = 0; i < BOTTOM_SLOTS; i++) {
                bookSlots[TOP_SLOTS + i].measure(
                        MeasureSpec.makeMeasureSpec(botBookW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(botBookH, MeasureSpec.EXACTLY)
                );
            }

            // Right alcove: Featured Panel + Cinema Card
            int rightW = Math.round(stageW * 0.224f);
            int featH = Math.round(stageH * 0.440f);
            int cinH = Math.round(stageH * 0.138f);
            featuredPanel.measure(
                    MeasureSpec.makeMeasureSpec(rightW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(featH, MeasureSpec.EXACTLY)
            );
            cinemaCard.measure(
                    MeasureSpec.makeMeasureSpec(rightW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cinH, MeasureSpec.EXACTLY)
            );

            // Empty/loading overlay inside left cabinet bays
            emptyOverlay.measure(
                    MeasureSpec.makeMeasureSpec(Math.round(stageW * 0.50f), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(stageH * 0.34f), MeasureSpec.AT_MOST)
            );

            // Bottom controller hint bar
            bottomBar.measure(
                    MeasureSpec.makeMeasureSpec(stageW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(Math.round(stageH * 0.115f), MeasureSpec.AT_MOST)
            );

            resizeToast.measure(
                    MeasureSpec.makeMeasureSpec(Math.round(stageW * 0.26f), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(Math.max(24, Math.round(38f * newScale)), MeasureSpec.EXACTLY)
            );
        }

        private void applyTypographyScale(float s) {
            int lineW = Math.max(18, Math.round(52f * s));
            int lineH = Math.max(1, Math.round(1.4f * s));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(lineW, lineH);
            llp.rightMargin = Math.max(8, Math.round(16f * s));
            headerLineLeft.setLayoutParams(llp);

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(lineW, lineH);
            rlp.leftMargin = Math.max(8, Math.round(16f * s));
            headerLineRight.setLayoutParams(rlp);

            int bookW = Math.max(18, Math.round(36f * s));
            int bookH = Math.max(14, Math.round(28f * s));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(bookW, bookH);
            blp.rightMargin = Math.max(6, Math.round(12f * s));
            headerBookIcon.setLayoutParams(blp);

            brandTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(13f, 27f * s));
            brandSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(8.5f, 13.5f * s));
            brandSubtitle.setPadding(0, Math.max(1, Math.round(2f * s)), 0, 0);

            int pPadH = Math.max(8, Math.round(15f * s));
            categoryPill.setPadding(pPadH, 0, pPadH, 0);
            categoryPill.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(9.5f, 13.5f * s));
            settingsPill.setPadding(pPadH, 0, pPadH, 0);
            settingsPill.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(9.5f, 13.5f * s));

            pageIndicator.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(8.5f, 11.5f * s));
            pageIndicator.setPadding(Math.max(8, Math.round(12f * s)), 0, Math.max(8, Math.round(12f * s)), 0);
            pageIndicator.setBackground(MidnightTheme.warmPill(0xCC1C110B, 0x88CFA366, Math.max(1f, s), Math.max(8f, 12f * s)));

            featuredPanel.applyScale(s);
            cinemaCard.applyScale(s);

            int ep = Math.max(12, Math.round(22f * s));
            emptyOverlay.setPadding(ep, ep, ep, ep);
            emptyOverlay.setBackground(MidnightTheme.warmPill(0xDC160E09, MidnightTheme.WALNUT_BORDER, Math.max(1.2f, 1.5f * s), Math.max(10f, 16f * s)));
            emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(13f, 22f * s));
            emptyBody.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10f, 14f * s));
            emptyBody.setPadding(0, Math.max(5, Math.round(8f * s)), 0, Math.max(8, Math.round(14f * s)));
            emptyActionBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10.5f, 15f * s));
            emptyActionBtn.setPadding(Math.max(14, Math.round(22f * s)), Math.max(6, Math.round(10f * s)),
                    Math.max(14, Math.round(22f * s)), Math.max(6, Math.round(10f * s)));
            emptyActionBtn.setBackground(MidnightTheme.warmPill(0xEE4A311A, MidnightTheme.GOLD_BRIGHT, Math.max(1.4f, 1.8f * s), Math.max(10f, 14f * s)));

            resizeToast.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(11f, 15f * s));
            resizeToast.setPadding(Math.max(12, Math.round(18f * s)), 0, Math.max(12, Math.round(18f * s)), 0);
            resizeToast.setBackground(MidnightTheme.warmPill(0xEA1B110B, MidnightTheme.GOLD_BRIGHT, Math.max(1.2f, 1.5f * s), Math.max(12f, 18f * s)));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // 1. Cabinet shell
            cabinetImage.layout(stageX, stageY, stageX + stageW, stageY + stageH);

            // 2. Centered Header above cabinet
            int hw = headerContainer.getMeasuredWidth(), hh = headerContainer.getMeasuredHeight();
            int hx = stageX + (stageW - hw) / 2;
            int hy = stageY + Math.max(2, (Math.round(stageH * 0.132f) - hh) / 2);
            headerContainer.layout(hx, hy, hx + hw, hy + hh);

            // 3. Top utility pills nestled cleanly in outer upper corners above cabinet
            int pillTop = stageY + Math.round(stageH * 0.036f);
            int catX = stageX + Math.round(stageW * 0.035f);
            categoryPill.layout(catX, pillTop, catX + categoryPill.getMeasuredWidth(), pillTop + categoryPill.getMeasuredHeight());
            int setX = stageX + Math.round(stageW * 0.965f) - settingsPill.getMeasuredWidth();
            settingsPill.layout(setX, pillTop, setX + settingsPill.getMeasuredWidth(), pillTop + settingsPill.getMeasuredHeight());

            // 4. Shelf page indicator inlaid neatly on the middle walnut shelf plank face
            int piw = pageIndicator.getMeasuredWidth(), pih = pageIndicator.getMeasuredHeight();
            int pix = stageX + Math.round(stageW * 0.400f) - piw / 2;
            int piy = stageY + Math.round(stageH * 0.538f) - pih / 2;
            pageIndicator.layout(pix, piy, pix + piw, piy + pih);

            // 5. Top Shelf (5 upright books resting flush on middle walnut plank y=0.517)
            int topStartX = stageX + Math.round(stageW * 0.106f);
            int topGap = Math.max(4, Math.round(stageW * 0.014f));
            int topShelfFloorY = stageY + Math.round(stageH * 0.517f);
            for (int i = 0; i < TOP_SLOTS; i++) {
                int bw = bookSlots[i].getMeasuredWidth();
                int bh = bookSlots[i].getMeasuredHeight();
                int bx = topStartX + i * (bw + topGap);
                bookSlots[i].layout(bx, topShelfFloorY - bh, bx + bw, topShelfFloorY);
            }

            // 6. Bottom Shelf (4 upright books nestled between brass globe & lantern, resting flush on y=0.799)
            int botStartX = stageX + Math.round(stageW * 0.196f);
            int botGap = Math.max(4, Math.round(stageW * 0.012f));
            int botShelfFloorY = stageY + Math.round(stageH * 0.799f);
            for (int i = 0; i < BOTTOM_SLOTS; i++) {
                MangaBookCardView book = bookSlots[TOP_SLOTS + i];
                int bw = book.getMeasuredWidth();
                int bh = book.getMeasuredHeight();
                int bx = botStartX + i * (bw + botGap);
                book.layout(bx, botShelfFloorY - bh, bx + bw, botShelfFloorY);
            }

            // 7. Right Alcove: Featured Panel + Cinema Card
            int rightX = stageX + Math.round(stageW * 0.736f);
            int featY = stageY + Math.round(stageH * 0.196f);
            featuredPanel.layout(rightX, featY, rightX + featuredPanel.getMeasuredWidth(), featY + featuredPanel.getMeasuredHeight());

            int cinBottomY = stageY + Math.round(stageH * 0.798f);
            int cinTopY = cinBottomY - cinemaCard.getMeasuredHeight();
            cinemaCard.layout(rightX, cinTopY, rightX + cinemaCard.getMeasuredWidth(), cinBottomY);

            // 8. Empty / Loading overlay centered across the left bays
            int ew = emptyOverlay.getMeasuredWidth(), eh = emptyOverlay.getMeasuredHeight();
            int ex = stageX + Math.round(stageW * 0.39f) - ew / 2;
            int ey = stageY + Math.round(stageH * 0.48f) - eh / 2;
            emptyOverlay.layout(ex, ey, ex + ew, ey + eh);

            // 9. Bottom Controller Hint Bar centered in bottom pure-black margin
            int bbw = bottomBar.getMeasuredWidth(), bbh = bottomBar.getMeasuredHeight();
            int bbx = stageX + (stageW - bbw) / 2;
            int bby = stageY + Math.round(stageH * 0.885f) + Math.max(0, (Math.round(stageH * 0.105f) - bbh) / 2);
            bottomBar.layout(bbx, bby, bbx + bbw, bby + bbh);

            // 10. Transient Resize Badge centered near top of shelves
            int rtw = resizeToast.getMeasuredWidth(), rth = resizeToast.getMeasuredHeight();
            int rtx = stageX + (stageW - rtw) / 2;
            int rty = stageY + Math.round(stageH * 0.145f);
            resizeToast.layout(rtx, rty, rtx + rtw, rty + rth);
        }
    }
}

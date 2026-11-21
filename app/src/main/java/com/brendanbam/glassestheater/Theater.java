package com.brendanbam.glassestheater;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared look, settings and controller mapping for Glasses Theater. */
final class Theater {

    static final int BG = Color.BLACK;            // pure black reads as see-through in XR glasses
    static final int CARD = 0xFF141821;
    static final int CARD_2 = 0xFF1F2430;
    static final int LINE = 0xFF2E3545;
    static final int ACCENT = 0xFF00E5FF;
    static final int PURPLE = 0xFF8B6CFF;
    static final int MUTED = 0xFF9BA3B4;

    private Theater() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("theater", Context.MODE_PRIVATE);
    }

    /* ---------- Virtual screen size ---------- */

    /** How much of the glasses view the picture fills, 50–100%. Smaller looks further away. */
    static int screenSize(Context c) { return prefs(c).getInt("screen_size", 85); }

    static int setScreenSize(Context c, int pct) {
        int v = Math.max(50, Math.min(100, pct));
        prefs(c).edit().putInt("screen_size", v).apply();
        return v;
    }

    /** Manga reading direction: true = right to left (tap/press left for the next page). */
    static boolean rightToLeft(Context c) { return prefs(c).getBoolean("rtl", false); }

    static void setRightToLeft(Context c, boolean rtl) { prefs(c).edit().putBoolean("rtl", rtl).apply(); }

    /**
     * Holds one child at [pct]% of its own size, centered, with black all around it.
     * Used for the cinema screen and the manga page so both can be shrunk in the glasses.
     */
    static final class ScreenFrame extends FrameLayout {
        private float pct;

        ScreenFrame(Context c, int pct) {
            super(c);
            this.pct = pct / 100f;
            setBackgroundColor(BG);
        }

        void setPct(int p) {
            pct = p / 100f;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int h = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(w, h);
            int cw = Math.round(w * pct), ch = Math.round(h * pct);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                if (child.getTag() == OVERLAY) {
                    child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                } else {
                    child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
                }
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
                int x = (w - cw) / 2, y = (h - ch) / 2;
                child.layout(x, y, x + cw, y + ch);
            }
        }
    }

    /** Tag for full-size overlay children of a ScreenFrame (status text etc.). */
    static final Object OVERLAY = new Object();

    /* ---------- Controller ---------- */

    static boolean isConfirm(int k) {
        return k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_SPACE;
    }

    static boolean isBack(int k) {
        return k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE;
    }

    static boolean isSizeDown(int k) { return k == KeyEvent.KEYCODE_BUTTON_L1; }

    static boolean isSizeUp(int k) { return k == KeyEvent.KEYCODE_BUTTON_R1; }

    /* ---------- Look ---------- */

    static void immersive(Activity a) {
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        a.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable round(int fill, int stroke, int strokeDp, Context c, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    /** Card-style background that lights up with a cyan ring when focused by the controller. */
    static StateListDrawable focusable(Context c, int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(0xFF003746, ACCENT, 3, c, radiusDp));
        s.addState(new int[]{android.R.attr.state_pressed}, round(0xFF003746, ACCENT, 3, c, radiusDp));
        s.addState(new int[]{}, round(CARD_2, LINE, 1, c, radiusDp));
        return s;
    }

    static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14));
        b.setBackground(focusable(c, 14));
        b.setStateListAnimator(null);
        b.setFocusable(true);
        return b;
    }

    /* ---------- Sorting ---------- */

    private static final Pattern CHAPTER_NUM = Pattern.compile("(?i)(?:chapter|chap|ch|episode|ep)\\.?\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern ANY_NUM = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /** Chapter number in a Mihon chapter folder name like "Group_Ch. 12.5 - Title", or -1. */
    static double chapterNumber(String name) {
        Matcher m = CHAPTER_NUM.matcher(name);
        if (m.find()) return Double.parseDouble(m.group(1));
        Matcher a = ANY_NUM.matcher(name);
        double last = -1;
        while (a.find()) {
            try { last = Double.parseDouble(a.group(1)); } catch (NumberFormatException ignored) { }
        }
        return last;
    }

    /** "page 2" before "page 10". */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) return cmp;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}

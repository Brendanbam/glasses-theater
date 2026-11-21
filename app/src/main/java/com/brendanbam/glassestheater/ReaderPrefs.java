package com.brendanbam.glassestheater;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Reader settings, modelled on Mihon's reader: reading mode (default + per series), scale type,
 * crop borders, split wide pages, tap zones, colors, volume keys, long-strip padding and more.
 * Each option is a small list of choices so it can be cycled with one button on a controller.
 */
final class ReaderPrefs {

    static final int MODE_DEFAULT = 0, MODE_LTR = 1, MODE_RTL = 2, MODE_VERTICAL = 3, MODE_STRIP = 4;
    static final String[] MODE_NAMES = {"Default", "Left to right", "Right to left (manga)", "Vertical pages", "Long strip (webtoon)"};

    static final int SCALE_FIT = 0, SCALE_WIDTH = 1, SCALE_HEIGHT = 2, SCALE_ORIGINAL = 3, SCALE_SMART = 4;
    static final String[] SCALE_NAMES = {"Fit screen", "Fit width", "Fit height", "Original size", "Smart fit"};

    static final int TAP_RIGHT_LEFT = 0, TAP_KINDLE = 1, TAP_EDGES = 2, TAP_OFF = 3;
    static final String[] TAP_NAMES = {"Right and left", "Kindle (tap most of the page for next)", "Edges", "Off"};

    static final String[] BG_NAMES = {"Black", "Gray", "White"};
    static final int[] BG_COLORS = {0xFF000000, 0xFF202225, 0xFFFFFFFF};

    static final int[] BRIGHTNESS = {100, 80, 60, 45, 30, 20};
    static final int[] PADDING = {0, 10, 15, 20, 25, 30};
    static final String[] VOLUME_NAMES = {"Off", "On", "On (inverted)"};
    static final String[] SPEED_NAMES = {"Slow", "Medium", "Fast"};

    private final SharedPreferences p;

    ReaderPrefs(Context c) { p = Theater.prefs(c); }

    private int get(String k, int def) { return p.getInt("rd_" + k, def); }
    private void set(String k, int v) { p.edit().putInt("rd_" + k, v).apply(); }
    private boolean flag(String k, boolean def) { return p.getBoolean("rd_" + k, def); }
    private void flag(String k, boolean v) { p.edit().putBoolean("rd_" + k, v).apply(); }

    /* Reading mode */
    int defaultMode() {
        int m = get("default_mode", -1);
        if (m < 0) m = p.getBoolean("rtl", false) ? MODE_RTL : MODE_LTR; // carry over the older left/right switch
        return m;
    }
    void setDefaultMode(int m) { set("default_mode", m); }
    int seriesMode(String series) { return p.getInt("rd_mode:" + series, MODE_DEFAULT); }
    void setSeriesMode(String series, int m) { p.edit().putInt("rd_mode:" + series, m).apply(); }
    int effectiveMode(String series) {
        int m = seriesMode(series);
        return m == MODE_DEFAULT ? defaultMode() : m;
    }

    /* Display */
    int scale() { return get("scale", SCALE_SMART); }
    void setScale(int v) { set("scale", v); }
    boolean cropBorders() { return flag("crop", true); }
    void setCropBorders(boolean v) { flag("crop", v); }
    boolean splitWide() { return flag("split", true); }
    void setSplitWide(boolean v) { flag("split", v); }
    boolean doubleTapZoom() { return flag("dtz", true); }
    void setDoubleTapZoom(boolean v) { flag("dtz", v); }
    boolean animate() { return flag("anim", true); }
    void setAnimate(boolean v) { flag("anim", v); }
    boolean pageNumber() { return flag("pagenum", true); }
    void setPageNumber(boolean v) { flag("pagenum", v); }
    int background() { return get("bg", 0); }
    void setBackground(int v) { set("bg", v); }
    int brightness() { return get("bright", 100); }
    void setBrightness(int v) { set("bright", v); }
    boolean grayscale() { return flag("gray", false); }
    void setGrayscale(boolean v) { flag("gray", v); }
    boolean invertColors() { return flag("invert", false); }
    void setInvertColors(boolean v) { flag("invert", v); }

    /* Navigation */
    int tapZones() { return get("tap", TAP_RIGHT_LEFT); }
    void setTapZones(int v) { set("tap", v); }
    boolean invertTaps() { return flag("tapinv", false); }
    void setInvertTaps(boolean v) { flag("tapinv", v); }
    int volumeKeys() { return get("vol", 0); }
    void setVolumeKeys(int v) { set("vol", v); }

    /* Long strip */
    int stripPadding() { return get("pad", 0); }
    void setStripPadding(int v) { set("pad", v); }
    int scrollSpeed() { return get("speed", 1); }
    void setScrollSpeed(int v) { set("speed", v); }

    static int next(int[] list, int current) {
        for (int i = 0; i < list.length; i++) if (list[i] == current) return list[(i + 1) % list.length];
        return list[0];
    }
}

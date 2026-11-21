package com.brendanbam.glassestheater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * The manga reader, modelled on Mihon's.
 *
 * Reading modes: left to right, right to left (manga), vertical pages, and long strip (webtoon),
 * set per series or as a default. Paged modes zoom (pinch, double-tap, L2/R2), crop borders and
 * split two-page spreads; long strip scrolls continuously with optional auto-scroll and side
 * padding. A transition screen sits between chapters, chapters are marked read, and the menu has a
 * page slider, chapter buttons and every setting. Everything works with touch, the dock's touchpad
 * cursor, or a controller:
 *
 *   A next • X previous • D-pad turns pages / pans zoomed pages / scrolls strips
 *   L2 / R2 zoom (paged) or auto-scroll (strip) • L1 / R1 screen size • Y or Start menu
 *   Select settings • B closes the menu, then leaves
 */
public class ReaderActivity extends Activity {

    /** One screen of a paged chapter: a page, or half of a split spread. */
    private static final class Item {
        final int page;     // index into pages
        final int half;     // 0 = whole page, 1 = left half, 2 = right half
        Item(int page, int half) { this.page = page; this.half = half; }
    }

    private final ExecutorService io = TheaterApp.pool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(96 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private ReaderPrefs rp;
    private Uri tree;
    private String seriesId, title;
    private List<MihonStore.Entry> chapters = new ArrayList<>();
    private List<Uri> pages = new ArrayList<>();
    private final List<float[]> sizes = new ArrayList<>(); // per page {w, h}, or {0,0} if unknown
    private final List<Item> items = new ArrayList<>();
    private int chapter;
    /** Paged position: 0..items-1 = page, -1 = "previous chapter" screen, items.size() = "next chapter" screen. */
    private int pos;
    private int stripPage;
    private int mode;
    private int loadToken;

    // Views
    private FrameLayout root;
    private Theater.ScreenFrame frame;
    private ZoomImageView pageView;
    private TextView transition;
    private ListView strip;
    private View dim;
    private TextView pageNumber, toast;
    private LinearLayout topBar, bottomBar;
    private TextView menuTitle, sliderLabel;
    private SeekBar slider;
    private ScrollView settingsPanel;
    private LinearLayout settingsList;
    private Button modeButton, autoButton;
    private boolean autoScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theater.immersive(this);
        rp = new ReaderPrefs(this);
        tree = MihonStore.tree(this);
        seriesId = getIntent().getStringExtra("series");
        title = getIntent().getStringExtra("title");
        chapter = getIntent().getIntExtra("chapter", 0);
        int startPage = getIntent().getIntExtra("page", 0);
        mode = rp.effectiveMode(seriesId);
        buildViews();
        applyLook();

        showToast("Loading…", 0);
        io.execute(() -> {
            List<MihonStore.Entry> found = MihonStore.chapters(getContentResolver(), tree, seriesId);
            ui.post(() -> {
                chapters = found;
                if (chapters.isEmpty()) { showToast("No chapters found", 0); return; }
                chapter = Math.max(0, Math.min(chapter, chapters.size() - 1));
                openChapter(chapter, startPage, false);
                showToast(ReaderPrefs.MODE_NAMES[mode], 1500);
            });
        });
    }

    /* =========================== Views =========================== */

    private void buildViews() {
        root = new FrameLayout(this);
        frame = new Theater.ScreenFrame(this, Theater.screenSize(this));

        pageView = new ZoomImageView(this);
        pageView.setListener(new ZoomImageView.Listener() {
            @Override public void onTap(float x, float y) { tapPaged(x / pageView.getWidth(), y / pageView.getHeight()); }
            @Override public void onSwipe(float vx, float vy) { swipe(vx, vy); }
            @Override public void onLongPress() { pageActions(); }
        });
        frame.addView(pageView);

        transition = Theater.text(this, 20, Color.WHITE, true);
        transition.setGravity(Gravity.CENTER);
        transition.setLineSpacing(0, 1.3f);
        transition.setPadding(Theater.dp(this, 32), 0, Theater.dp(this, 32), 0);
        transition.setVisibility(View.GONE);
        transition.setOnClickListener(v -> next());
        frame.addView(transition);

        strip = new ListView(this);
        strip.setDivider(null);
        strip.setVerticalScrollBarEnabled(false);
        strip.setSelector(new android.graphics.drawable.ColorDrawable(0));
        strip.setVisibility(View.GONE);
        strip.setAdapter(stripAdapter);
        strip.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) { stripScrolled(first, visible); }
        });
        // Taps on the strip: top third scrolls up, bottom third down, middle opens the menu
        final float[] down = new float[3];
        strip.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { down[0] = e.getX(); down[1] = e.getY(); down[2] = e.getEventTime(); stopAutoScroll(); }
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && e.getEventTime() - down[2] < 300
                    && Math.abs(e.getX() - down[0]) < Theater.dp(this, 12) && Math.abs(e.getY() - down[1]) < Theater.dp(this, 12)) {
                tapStrip(e.getY() / v.getHeight());
            }
            return false;
        });
        frame.addView(strip);

        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setClickable(false);
        frame.addView(dim);

        // Full-size overlay: page number, messages, menu, settings
        FrameLayout overlay = new FrameLayout(this);
        overlay.setTag(Theater.OVERLAY);
        frame.addView(overlay);

        pageNumber = Theater.text(this, 13, 0xCCFFFFFF, true);
        pageNumber.setShadowLayer(4, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = Theater.dp(this, 10);
        overlay.addView(pageNumber, plp);

        toast = Theater.text(this, 16, Color.WHITE, true);
        toast.setGravity(Gravity.CENTER);
        toast.setPadding(Theater.dp(this, 18), Theater.dp(this, 10), Theater.dp(this, 18), Theater.dp(this, 10));
        toast.setBackground(Theater.round(0xE61A202C, 0, 0, this, 12));
        toast.setVisibility(View.GONE);
        overlay.addView(toast, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        buildMenu(overlay);
        buildSettings(overlay);

        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private void buildMenu(FrameLayout overlay) {
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Theater.dp(this, 16), Theater.dp(this, 12), Theater.dp(this, 16), Theater.dp(this, 12));
        topBar.setBackgroundColor(0xF0141821);
        Button close = Theater.button(this, "✕");
        close.setOnClickListener(v -> finish());
        topBar.addView(close);
        menuTitle = Theater.text(this, 16, Color.WHITE, true);
        menuTitle.setSingleLine(true);
        menuTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        menuTitle.setPadding(Theater.dp(this, 14), 0, Theater.dp(this, 8), 0);
        topBar.addView(menuTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        modeButton = Theater.button(this, "");
        modeButton.setTextSize(14);
        modeButton.setOnClickListener(v -> {
            int next = (rp.seriesMode(seriesId) + 1) % ReaderPrefs.MODE_NAMES.length;
            rp.setSeriesMode(seriesId, next);
            changeMode();
        });
        topBar.addView(modeButton);
        Button settings = Theater.button(this, "⚙ Settings");
        settings.setTextSize(14);
        settings.setOnClickListener(v -> showSettings(true));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = Theater.dp(this, 8);
        topBar.addView(settings, slp);
        topBar.setVisibility(View.GONE);
        overlay.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(Theater.dp(this, 16), Theater.dp(this, 10), Theater.dp(this, 16), Theater.dp(this, 14));
        bottomBar.setBackgroundColor(0xF0141821);
        Button prevCh = Theater.button(this, "⏮ Chapter");
        prevCh.setTextSize(14);
        prevCh.setOnClickListener(v -> { if (chapter > 0) openChapter(chapter - 1, 0, false); });
        bottomBar.addView(prevCh);
        slider = new SeekBar(this);
        slider.setFocusable(true);
        slider.setPadding(Theater.dp(this, 20), Theater.dp(this, 12), Theater.dp(this, 20), Theater.dp(this, 12));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) jumpTo(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        bottomBar.addView(slider, new LinearLayout.LayoutParams(0, -2, 1f));
        sliderLabel = Theater.text(this, 14, Color.WHITE, true);
        sliderLabel.setPadding(0, 0, Theater.dp(this, 10), 0);
        bottomBar.addView(sliderLabel);
        autoButton = Theater.button(this, "▶ Auto-scroll");
        autoButton.setTextSize(14);
        autoButton.setOnClickListener(v -> { if (autoScroll) stopAutoScroll(); else startAutoScroll(); hideMenu(); });
        bottomBar.addView(autoButton);
        Button nextCh = Theater.button(this, "Chapter ⏭");
        nextCh.setTextSize(14);
        nextCh.setOnClickListener(v -> { if (chapter + 1 < chapters.size()) openChapter(chapter + 1, 0, false); });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.leftMargin = Theater.dp(this, 8);
        bottomBar.addView(nextCh, nlp);
        bottomBar.setVisibility(View.GONE);
        overlay.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void buildSettings(FrameLayout overlay) {
        settingsPanel = new ScrollView(this);
        settingsPanel.setBackground(Theater.round(0xF50E1117, Theater.LINE, 1, this, 16));
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        settingsList.setPadding(Theater.dp(this, 16), Theater.dp(this, 16), Theater.dp(this, 16), Theater.dp(this, 16));
        settingsPanel.addView(settingsList);
        settingsPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Theater.dp(this, 440), -1, Gravity.END);
        lp.setMargins(0, Theater.dp(this, 16), Theater.dp(this, 16), Theater.dp(this, 16));
        overlay.addView(settingsPanel, lp);
    }

    /** Rebuilds the settings list (each row cycles its option with A / tap). */
    private void fillSettings() {
        settingsList.removeAllViews();
        TextView head = Theater.text(this, 20, Theater.ACCENT, true);
        head.setText("READER SETTINGS");
        head.setPadding(0, 0, 0, Theater.dp(this, 10));
        settingsList.addView(head);

        section("Reading");
        row("This series: " + ReaderPrefs.MODE_NAMES[rp.seriesMode(seriesId)], () -> {
            rp.setSeriesMode(seriesId, (rp.seriesMode(seriesId) + 1) % ReaderPrefs.MODE_NAMES.length); changeMode(); });
        row("Default for all series: " + ReaderPrefs.MODE_NAMES[rp.defaultMode()], () -> {
            int m = rp.defaultMode() + 1; if (m >= ReaderPrefs.MODE_NAMES.length) m = 1;
            rp.setDefaultMode(m); changeMode(); });
        row("Tap zones: " + ReaderPrefs.TAP_NAMES[rp.tapZones()], () -> rp.setTapZones((rp.tapZones() + 1) % ReaderPrefs.TAP_NAMES.length));
        row("Invert tap zones: " + onOff(rp.invertTaps()), () -> rp.setInvertTaps(!rp.invertTaps()));
        row("Volume keys turn pages: " + ReaderPrefs.VOLUME_NAMES[rp.volumeKeys()], () -> rp.setVolumeKeys((rp.volumeKeys() + 1) % 3));
        row("Page turn animation: " + onOff(rp.animate()), () -> rp.setAnimate(!rp.animate()));

        section("Pages");
        row("Scale: " + ReaderPrefs.SCALE_NAMES[rp.scale()], () -> { rp.setScale((rp.scale() + 1) % ReaderPrefs.SCALE_NAMES.length); refreshPage(); });
        row("Crop borders: " + onOff(rp.cropBorders()), () -> { rp.setCropBorders(!rp.cropBorders()); reloadKeepingPlace(); });
        row("Split wide pages (spreads): " + onOff(rp.splitWide()), () -> { rp.setSplitWide(!rp.splitWide()); reloadKeepingPlace(); });
        row("Double-tap to zoom: " + onOff(rp.doubleTapZoom()), () -> { rp.setDoubleTapZoom(!rp.doubleTapZoom()); refreshPage(); });
        row("Show page number: " + onOff(rp.pageNumber()), () -> { rp.setPageNumber(!rp.pageNumber()); updatePageNumber(); });

        section("Long strip");
        row("Side padding: " + rp.stripPadding() + "%", () -> { rp.setStripPadding(ReaderPrefs.next(ReaderPrefs.PADDING, rp.stripPadding())); applyLook(); });
        row("Auto-scroll speed: " + ReaderPrefs.SPEED_NAMES[rp.scrollSpeed()], () -> rp.setScrollSpeed((rp.scrollSpeed() + 1) % 3));

        section("Look");
        row("Screen size: " + Theater.screenSize(this) + "%", () -> {
            int s = Theater.screenSize(this) - 10; Theater.setScreenSize(this, s < 50 ? 100 : s); applyLook(); });
        row("Brightness: " + rp.brightness() + "%", () -> { rp.setBrightness(ReaderPrefs.next(ReaderPrefs.BRIGHTNESS, rp.brightness())); applyLook(); });
        row("Background: " + ReaderPrefs.BG_NAMES[rp.background()], () -> { rp.setBackground((rp.background() + 1) % 3); applyLook(); });
        row("Grayscale: " + onOff(rp.grayscale()), () -> { rp.setGrayscale(!rp.grayscale()); applyLook(); });
        row("Invert colors: " + onOff(rp.invertColors()), () -> { rp.setInvertColors(!rp.invertColors()); applyLook(); });
    }

    private static String onOff(boolean b) { return b ? "On" : "Off"; }

    private void section(String name) {
        TextView t = Theater.text(this, 13, Theater.MUTED, true);
        t.setText(name.toUpperCase(java.util.Locale.US));
        t.setPadding(0, Theater.dp(this, 12), 0, Theater.dp(this, 6));
        settingsList.addView(t);
    }

    private void row(String label, Runnable action) {
        Button b = Theater.button(this, label);
        b.setTextSize(15);
        b.setOnClickListener(v -> {
            action.run();
            int index = settingsList.indexOfChild(v);
            fillSettings();
            View again = settingsList.getChildAt(index);
            if (again != null) again.requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Theater.dp(this, 6);
        settingsList.addView(b, lp);
    }

    /* =========================== Look =========================== */

    private void applyLook() {
        int bg = ReaderPrefs.BG_COLORS[rp.background()];
        root.setBackgroundColor(Color.BLACK);
        frame.setBackgroundColor(Color.BLACK);
        pageView.setBackgroundColor(bg);
        strip.setBackgroundColor(bg);
        transition.setBackgroundColor(bg);
        transition.setTextColor(rp.background() == 2 ? Color.BLACK : Color.WHITE);
        frame.setPct(Theater.screenSize(this));
        dim.setAlpha(1f - rp.brightness() / 100f);
        pageView.setColorFilter(colorFilter());
        int pad = Math.round(getResources().getDisplayMetrics().widthPixels * rp.stripPadding() / 100f * Theater.screenSize(this) / 100f);
        strip.setPadding(pad, 0, pad, 0);
        strip.setClipToPadding(false);
        stripAdapter.notifyDataSetChanged();
    }

    private ColorMatrixColorFilter colorFilter() {
        if (!rp.grayscale() && !rp.invertColors()) return null;
        ColorMatrix m = new ColorMatrix();
        if (rp.grayscale()) m.setSaturation(0);
        if (rp.invertColors()) {
            ColorMatrix inv = new ColorMatrix(new float[]{-1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
            m.postConcat(inv);
        }
        return new ColorMatrixColorFilter(m);
    }

    private boolean rtl() { return mode == ReaderPrefs.MODE_RTL; }
    private boolean isStrip() { return mode == ReaderPrefs.MODE_STRIP; }

    /* =========================== Loading =========================== */

    /** Opens a chapter at an original page index (-1 = its last page). */
    private void openChapter(int index, int startPage, boolean announce) {
        stopAutoScroll();
        chapter = index;
        int token = ++loadToken;
        cache.evictAll();
        MihonStore.Entry entry = chapters.get(index);
        showToast("Opening " + chapterName(index) + "…", 0);
        final boolean split = rp.splitWide() && !isStrip();
        io.execute(() -> {
            List<Uri> found = MihonStore.pages(this, tree, entry);
            List<float[]> dims = new ArrayList<>();
            for (Uri u : found) dims.add(bounds(u));
            ui.post(() -> {
                if (token != loadToken) return;
                pages = found;
                sizes.clear();
                sizes.addAll(dims);
                if (pages.isEmpty()) {
                    showToast(chapterName(index) + " has no pages" + (MihonStore.lastError != null ? "  •  " + MihonStore.lastError : ""), 0);
                    return;
                }
                items.clear();
                for (int i = 0; i < pages.size(); i++) {
                    float[] d = sizes.get(i);
                    if (split && d[0] > d[1] * 1.15f) {
                        // Spreads: right half first in right-to-left reading
                        items.add(new Item(i, rtl() ? 2 : 1));
                        items.add(new Item(i, rtl() ? 1 : 2));
                    } else items.add(new Item(i, 0));
                }
                hideToast();
                int page = startPage < 0 ? pages.size() - 1 : Math.min(startPage, pages.size() - 1);
                if (isStrip()) showStrip(page);
                else showPaged(itemFor(page, startPage < 0), 0);
                if (announce) showToast(chapterName(index), 1200);
            });
        });
    }

    private int itemFor(int page, boolean lastHalf) {
        int found = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).page == page) { found = i; if (!lastHalf) break; }
        return found;
    }

    private float[] bounds(Uri u) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); } catch (Exception ignored) { }
        return new float[]{Math.max(0, o.outWidth), Math.max(0, o.outHeight)};
    }

    /** Re-reads the chapter (after split/crop changes) and returns to the same page. */
    private void reloadKeepingPlace() {
        if (chapters.isEmpty()) return;
        openChapter(chapter, currentPage(), false);
    }

    private int currentPage() {
        if (isStrip()) return stripPage;
        if (pos >= 0 && pos < items.size()) return items.get(pos).page;
        return pos < 0 ? 0 : Math.max(0, pages.size() - 1);
    }

    private void changeMode() {
        int page = currentPage();
        mode = rp.effectiveMode(seriesId);
        showToast(ReaderPrefs.MODE_NAMES[mode], 1200);
        updateMenu();
        if (!chapters.isEmpty()) openChapter(chapter, page, false);
    }

    /** Decodes, splits and crops one screen's bitmap (background thread). */
    private Bitmap render(Uri uri, int half, int maxW, int maxH) {
        String key = uri + "#" + half + "#" + rp.cropBorders() + "#" + maxW;
        Bitmap hit = cache.get(key);
        if (hit != null) return hit;
        MihonStore.lastError = null;
        Bitmap b = MihonStore.decode(getContentResolver(), uri, half == 0 ? maxW : maxW * 2, maxH);
        if (b == null) return null;
        if (half != 0) {
            int w = b.getWidth() / 2;
            b = Bitmap.createBitmap(b, half == 1 ? 0 : b.getWidth() - w, 0, w, b.getHeight());
        }
        if (rp.cropBorders()) b = PageTools.cropBorders(b);
        cache.put(key, b);
        return b;
    }

    /* =========================== Paged =========================== */

    private void showPaged(int target, int direction) {
        strip.setVisibility(View.GONE);
        pos = target;
        if (pos < 0 || pos >= items.size()) { showTransition(); return; }
        transition.setVisibility(View.GONE);
        pageView.setVisibility(View.VISIBLE);
        Item it = items.get(pos);
        saveProgress(it.page);
        int w = pageView.getWidth() > 0 ? pageView.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int h = pageView.getHeight() > 0 ? pageView.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int maxW = rp.scale() == ReaderPrefs.SCALE_WIDTH || rp.scale() == ReaderPrefs.SCALE_SMART ? w * 2 : w;
        int maxH = rp.scale() == ReaderPrefs.SCALE_HEIGHT || rp.scale() == ReaderPrefs.SCALE_SMART ? h * 2 : h;
        Uri uri = pages.get(it.page);
        int token = loadToken;
        int at = pos;
        io.execute(() -> {
            Bitmap b = render(uri, it.half, maxW, maxH);
            ui.post(() -> {
                if (token != loadToken || pos != at) return;
                if (b == null) {
                    pageView.setImageBitmap(null);
                    showToast("Couldn't open page " + (it.page + 1) + "  •  " + uri.getLastPathSegment()
                            + (MihonStore.lastError != null ? "\n" + MihonStore.lastError : "") + "\nA for the next page", 0);
                    return;
                }
                if ("sticky".equals(toast.getTag())) hideToast();
                pageView.configure(rp.scale(), rtl(), rp.doubleTapZoom());
                pageView.setImageBitmap(b);
                animateIn(direction);
                updatePageNumber();
                preload(at + 1, maxW, maxH);
                preload(at + 2, maxW, maxH);
                preload(at - 1, maxW, maxH);
            });
        });
        if (it.page == pages.size() - 1) MihonStore.markRead(this, seriesId, chapters.get(chapter).docId);
        updatePageNumber();
        updateMenu();
    }

    private void preload(int index, int maxW, int maxH) {
        if (index < 0 || index >= items.size()) return;
        Item it = items.get(index);
        Uri uri = pages.get(it.page);
        io.execute(() -> render(uri, it.half, maxW, maxH));
    }

    /** Slides the new page in from the reading direction. */
    private void animateIn(int direction) {
        if (!rp.animate() || direction == 0) return;
        boolean vertical = mode == ReaderPrefs.MODE_VERTICAL;
        float dist = (vertical ? pageView.getHeight() : pageView.getWidth()) * 0.12f;
        float from = direction * (rtl() ? -dist : dist);
        pageView.setAlpha(0.4f);
        if (vertical) pageView.setTranslationY(from); else pageView.setTranslationX(from);
        pageView.animate().translationX(0).translationY(0).alpha(1f).setDuration(140).start();
    }

    private void showTransition() {
        pageView.setVisibility(View.GONE);
        transition.setVisibility(View.VISIBLE);
        StringBuilder t = new StringBuilder();
        if (pos < 0) {
            t.append("Start of ").append(chapterName(chapter)).append("\n\n");
            if (chapter > 0) t.append("Previous: ").append(chapterName(chapter - 1)).append("\n\nGo back once more to open it");
            else t.append("This is the first downloaded chapter");
        } else {
            MihonStore.markRead(this, seriesId, chapters.get(chapter).docId);
            t.append("Finished: ").append(chapterName(chapter)).append("\n\n");
            if (chapter + 1 < chapters.size()) t.append("Next: ").append(chapterName(chapter + 1)).append("\n\nPress A or tap to continue");
            else t.append("You're caught up. That's the last downloaded chapter.\n\nDownload more in Mihon, then come back.");
        }
        transition.setText(t.toString());
        pageNumber.setVisibility(View.GONE);
        updateMenu();
    }

    private void next() {
        if (isStrip()) { scrollStrip(0.8f); return; }
        if (pos >= items.size()) {
            if (chapter + 1 < chapters.size()) openChapter(chapter + 1, 0, true);
            return;
        }
        if (pos < 0) { showPaged(0, 1); return; }
        showPaged(pos + 1, 1);
    }

    private void previous() {
        if (isStrip()) { scrollStrip(-0.8f); return; }
        if (pos < 0) {
            if (chapter > 0) openChapter(chapter - 1, -1, true);
            return;
        }
        if (pos >= items.size()) { showPaged(items.size() - 1, -1); return; }
        showPaged(pos - 1, -1);
    }

    private void jumpTo(int index) {
        if (isStrip()) { strip.setSelection(Math.max(0, Math.min(index, pages.size() - 1))); return; }
        showPaged(Math.max(0, Math.min(index, items.size() - 1)), 0);
    }

    private void refreshPage() {
        if (isStrip()) { stripAdapter.notifyDataSetChanged(); return; }
        cache.evictAll();
        if (pos >= 0 && pos < items.size()) showPaged(pos, 0);
    }

    /** Tap zones (Mihon-style presets; "forward" follows the reading direction). */
    private void tapPaged(float x, float y) {
        if (menuShown()) { hideMenu(); return; }
        int zone; // -1 left action, 1 right action, 0 menu
        switch (rp.tapZones()) {
            case ReaderPrefs.TAP_KINDLE: zone = y < 0.2f ? 0 : (x < 0.3f ? -1 : 1); break;
            case ReaderPrefs.TAP_EDGES: zone = x < 0.15f ? -1 : (x > 0.85f ? 1 : 0); break;
            case ReaderPrefs.TAP_OFF: zone = 0; break;
            default: zone = x < 0.33f ? -1 : (x > 0.66f ? 1 : 0);
        }
        if (rp.invertTaps()) zone = -zone;
        if (zone == 0) { showMenu(); return; }
        boolean forward = rtl() ? zone < 0 : zone > 0;
        if (forward) next(); else previous();
    }

    private void swipe(float vx, float vy) {
        if (mode == ReaderPrefs.MODE_VERTICAL) {
            if (vy < 0) next(); else if (vy > 0) previous();
            return;
        }
        if (vx == 0) return;
        boolean towardLeft = vx < 0; // finger moved left
        if (rtl() ? !towardLeft : towardLeft) next(); else previous();
    }

    /* =========================== Long strip =========================== */

    private void showStrip(int page) {
        pageView.setVisibility(View.GONE);
        transition.setVisibility(View.GONE);
        strip.setVisibility(View.VISIBLE);
        stripAdapter.notifyDataSetChanged();
        strip.setSelection(Math.max(0, page));
        stripPage = page;
        saveProgress(page);
        updatePageNumber();
        updateMenu();
    }

    private void stripScrolled(int first, int visible) {
        if (!isStrip() || pages.isEmpty()) return;
        int page = Math.min(first, pages.size() - 1);
        if (page != stripPage) {
            stripPage = page;
            saveProgress(page);
            updatePageNumber();
            if (menuShown()) updateMenu();
        }
        if (first + visible > pages.size()) MihonStore.markRead(this, seriesId, chapters.get(chapter).docId);
    }

    private void scrollStrip(float fraction) {
        strip.smoothScrollBy((int) (strip.getHeight() * fraction), 220);
    }

    private void tapStrip(float y) {
        if (menuShown()) { hideMenu(); return; }
        if (rp.tapZones() == ReaderPrefs.TAP_OFF || (y > 0.3f && y < 0.7f)) { showMenu(); return; }
        boolean down = y >= 0.7f;
        if (rp.invertTaps()) down = !down;
        scrollStrip(down ? 0.75f : -0.75f);
    }

    private final Runnable autoStep = new Runnable() {
        @Override public void run() {
            if (!autoScroll) return;
            float d = getResources().getDisplayMetrics().density;
            int px = Math.max(1, Math.round((rp.scrollSpeed() == 0 ? 0.6f : rp.scrollSpeed() == 1 ? 1.2f : 2.2f) * d));
            if (!strip.canScrollList(1)) { stopAutoScroll(); showToast("End of chapter", 1500); return; }
            strip.scrollListBy(px);
            ui.postDelayed(this, 16);
        }
    };

    private void startAutoScroll() {
        if (!isStrip()) { showToast("Auto-scroll works in Long strip mode", 1800); return; }
        autoScroll = true;
        autoButton.setText("⏸ Auto-scroll");
        ui.post(autoStep);
        showToast("Auto-scroll " + ReaderPrefs.SPEED_NAMES[rp.scrollSpeed()].toLowerCase(java.util.Locale.US) + "  •  any button stops", 1500);
    }

    private void stopAutoScroll() {
        if (!autoScroll) return;
        autoScroll = false;
        ui.removeCallbacks(autoStep);
        autoButton.setText("▶ Auto-scroll");
    }

    private final BaseAdapter stripAdapter = new BaseAdapter() {
        @Override public int getCount() { return isStrip() && !pages.isEmpty() ? pages.size() + 1 : 0; }
        @Override public Object getItem(int p) { return p; }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return p < pages.size() ? 0 : 1; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            // Before the first layout the list has no width yet: use the screen's, or pages load tiny and blurry
            int full = parent.getWidth() > 0 ? parent.getWidth()
                    : Math.round(getResources().getDisplayMetrics().widthPixels * Theater.screenSize(ReaderActivity.this) / 100f);
            int width = Math.max(200, full - parent.getPaddingLeft() - parent.getPaddingRight());
            if (p >= pages.size()) {
                TextView t = convert instanceof TextView ? (TextView) convert : Theater.text(ReaderActivity.this, 18, Color.WHITE, true);
                t.setGravity(Gravity.CENTER);
                t.setPadding(Theater.dp(ReaderActivity.this, 24), Theater.dp(ReaderActivity.this, 60), Theater.dp(ReaderActivity.this, 24), Theater.dp(ReaderActivity.this, 120));
                t.setTextColor(rp.background() == 2 ? Color.BLACK : Color.WHITE);
                t.setText(chapter + 1 < chapters.size()
                        ? "Finished: " + chapterName(chapter) + "\n\nNext: " + chapterName(chapter + 1) + "\n\nPress A or tap to continue"
                        : "You're caught up. That's the last downloaded chapter.");
                t.setOnClickListener(v -> { if (chapter + 1 < chapters.size()) openChapter(chapter + 1, 0, true); });
                return t;
            }
            ImageView img = convert instanceof ImageView ? (ImageView) convert : new ImageView(ReaderActivity.this);
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setColorFilter(colorFilter());
            float[] d = sizes.size() > p ? sizes.get(p) : new float[]{0, 0};
            // Placeholder height from the page's real proportions, so the strip doesn't jump while loading
            int placeholder = d[0] > 0 ? Math.round(width * d[1] / d[0]) : width * 3 / 2;
            img.setMinimumHeight(placeholder);
            img.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            Uri uri = pages.get(p);
            String tag = uri.toString();
            img.setTag(tag);
            Bitmap cached = cache.get(uri + "#0#" + rp.cropBorders() + "#" + width);
            img.setImageBitmap(cached);
            if (cached == null) {
                int token = loadToken;
                io.execute(() -> {
                    Bitmap b = render(uri, 0, width, Integer.MAX_VALUE / 4);
                    ui.post(() -> {
                        if (token != loadToken || !tag.equals(img.getTag())) return;
                        img.setMinimumHeight(0);
                        img.setImageBitmap(b);
                    });
                });
            } else img.setMinimumHeight(0);
            return img;
        }
    };

    /* =========================== Menu =========================== */

    private boolean menuShown() { return topBar.getVisibility() == View.VISIBLE || settingsPanel.getVisibility() == View.VISIBLE; }

    private void showMenu() {
        stopAutoScroll();
        updateMenu();
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        pageNumber.setVisibility(View.GONE);
        slider.requestFocus();
    }

    private void hideMenu() {
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        showSettings(false);
        updatePageNumber();
        root.requestFocus();
    }

    private void showSettings(boolean show) {
        if (show) {
            fillSettings();
            settingsPanel.setVisibility(View.VISIBLE);
            View first = settingsList.getChildAt(2);
            if (first != null) first.requestFocus();
        } else settingsPanel.setVisibility(View.GONE);
    }

    private void updateMenu() {
        if (chapters.isEmpty()) return;
        menuTitle.setText(title + "  •  " + chapterName(chapter));
        modeButton.setText(ReaderPrefs.MODE_NAMES[mode].replace(" (manga)", "").replace(" (webtoon)", ""));
        autoButton.setVisibility(isStrip() ? View.VISIBLE : View.GONE);
        int count = isStrip() ? pages.size() : items.size();
        slider.setMax(Math.max(0, count - 1));
        slider.setLayoutDirection(rtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        int at = isStrip() ? stripPage : Math.max(0, Math.min(pos, items.size() - 1));
        slider.setProgress(at);
        sliderLabel.setText((currentPage() + 1) + " / " + pages.size());
    }

    private void updatePageNumber() {
        boolean show = rp.pageNumber() && !menuShown() && !pages.isEmpty()
                && (isStrip() || (pos >= 0 && pos < items.size()));
        pageNumber.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) pageNumber.setText((currentPage() + 1) + " / " + pages.size());
    }

    /* =========================== Page actions =========================== */

    private void pageActions() {
        if (pages.isEmpty()) return;
        Uri uri = pages.get(currentPage());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Page " + (currentPage() + 1))
                .setItems(new String[]{"Save page to Pictures", "Set as series cover"}, (d, which) -> {
                    if (which == 0) savePage(uri); else setCover(uri);
                })
                .show();
    }

    private void savePage(Uri uri) {
        io.execute(() -> {
            boolean ok = false;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, (title + " " + chapterName(chapter) + " p" + (currentPage() + 1)).replaceAll("[\\\\/:*?\"<>|]", "_") + ".jpg");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Glasses Theater");
                Uri out = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                Bitmap b = MihonStore.decode(getContentResolver(), uri, 4096, 8192);
                if (out != null && b != null) {
                    try (OutputStream o = getContentResolver().openOutputStream(out)) { ok = b.compress(Bitmap.CompressFormat.JPEG, 95, o); }
                }
            } catch (Exception ignored) { }
            boolean saved = ok;
            ui.post(() -> Toast.makeText(this, saved ? "Saved to Pictures/Glasses Theater" : "Couldn't save the page", Toast.LENGTH_SHORT).show());
        });
    }

    private void setCover(Uri uri) {
        io.execute(() -> {
            boolean ok = false;
            try {
                Bitmap b = MihonStore.decode(getContentResolver(), uri, 900, 1400);
                if (b != null) try (OutputStream o = new java.io.FileOutputStream(Meta.coverFile(this, title))) {
                    ok = b.compress(Bitmap.CompressFormat.JPEG, 92, o);
                }
            } catch (Exception ignored) { }
            boolean set = ok;
            ui.post(() -> Toast.makeText(this, set ? "Cover updated" : "Couldn't set the cover", Toast.LENGTH_SHORT).show());
        });
    }

    /* =========================== Controller =========================== */

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        // Menu / settings open: normal focus navigation, B or Y closes
        if (menuShown()) {
            if (Theater.isBack(k) || k == KeyEvent.KEYCODE_BUTTON_Y || k == KeyEvent.KEYCODE_BUTTON_START) {
                if (!down) {
                    if (settingsPanel.getVisibility() == View.VISIBLE && topBar.getVisibility() == View.VISIBLE) showSettings(false);
                    else hideMenu();
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        if (autoScroll && down && k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN) { stopAutoScroll(); return true; }

        // Volume keys
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && rp.volumeKeys() != 0) {
            if (down) {
                boolean fwd = (k == KeyEvent.KEYCODE_VOLUME_DOWN) == (rp.volumeKeys() == 1);
                if (fwd) next(); else previous();
            }
            return true;
        }

        if (Theater.isBack(k)) { if (!down) finish(); return true; }
        if (!down) return handled(k) || super.dispatchKeyEvent(e);

        boolean zoomed = !isStrip() && pageView.getVisibility() == View.VISIBLE && pageView.isZoomed();
        boolean pannable = !isStrip() && pageView.getVisibility() == View.VISIBLE;
        float stepX = pageView.getWidth() * 0.7f, stepY = pageView.getHeight() * 0.6f;
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
                showMenu();
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                showMenu();
                showSettings(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isStrip()) { scrollStrip(0.6f); return true; }
                if (pannable && pageView.panBy(-stepX, 0)) return true;
                if (rtl()) previous(); else next();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (isStrip()) { scrollStrip(-0.6f); return true; }
                if (pannable && pageView.panBy(stepX, 0)) return true;
                if (rtl()) next(); else previous();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (isStrip()) { scrollStrip(0.6f); return true; }
                if (pannable && pageView.panBy(0, -stepY)) return true;
                next();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (isStrip()) { scrollStrip(-0.6f); return true; }
                if (pannable && pageView.panBy(0, stepY)) return true;
                previous();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_PAGE_UP:
                previous();
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                next();
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (isStrip()) startAutoScroll(); else if (pannable) pageView.zoomStep(true);
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
                if (isStrip()) stopAutoScroll(); else if (zoomed) pageView.zoomStep(false); else if (pannable) pageView.reset();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1: {
                int pct = Theater.setScreenSize(this, Theater.screenSize(this) + (k == KeyEvent.KEYCODE_BUTTON_R1 ? 5 : -5));
                frame.setPct(pct);
                applyLook();
                showToast("Screen size " + pct + "%", 1200);
                return true;
            }
            default:
                if (Theater.isConfirm(k)) { next(); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean handled(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_L2: case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                return true;
            default:
                return false;
        }
    }

    /* =========================== Helpers =========================== */

    private void saveProgress(int page) {
        if (chapters.isEmpty()) return;
        MihonStore.saveProgress(this, seriesId, chapters.get(chapter).docId, page);
    }

    private String chapterName(int index) {
        if (index < 0 || index >= chapters.size()) return "";
        return chapters.get(index).name.replace(".cbz", "").replace(".zip", "").replace("_", " ");
    }

    private final Runnable hideToastRunnable = this::hideToast;

    /** Centered message; durationMs 0 keeps it until replaced. */
    private void showToast(String msg, int durationMs) {
        toast.setText(msg);
        toast.setVisibility(View.VISIBLE);
        toast.setTag(durationMs == 0 ? "sticky" : null);
        ui.removeCallbacks(hideToastRunnable);
        if (durationMs > 0) ui.postDelayed(hideToastRunnable, durationMs);
    }

    private void hideToast() {
        toast.setVisibility(View.GONE);
        toast.setTag(null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Theater.immersive(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoScroll();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        cache.evictAll();
        super.onDestroy();
    }
}

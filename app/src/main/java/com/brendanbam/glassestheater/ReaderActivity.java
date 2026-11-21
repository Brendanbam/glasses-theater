package com.brendanbam.glassestheater;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads a downloaded chapter in the glasses: the page floats on black (see-through), at the screen
 * size you pick. Long webtoon strips scroll; normal pages fit the screen.
 *
 * Controller: A / D-pad right = next page (left in right-to-left mode), D-pad left = previous,
 * D-pad up / down = scroll long pages, L1 / R1 = smaller / bigger, Y = flip reading direction,
 * B = back. Touch (or the dock's touchpad cursor): tap the right or left third to turn pages.
 * The last page of a chapter continues into the next one.
 */
public class ReaderActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Bitmap> cache = new HashMap<>();

    private Uri tree;
    private String seriesId, title;
    private List<MihonStore.Entry> chapters = new ArrayList<>();
    private List<Uri> pages = new ArrayList<>();
    private int chapter, page;
    private int loadToken;

    private Theater.ScreenFrame frame;
    private ScrollView scroll;
    private ImageView image;
    private TextView info;
    private boolean tall;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theater.immersive(this);
        tree = MihonStore.tree(this);
        seriesId = getIntent().getStringExtra("series");
        title = getIntent().getStringExtra("title");
        chapter = getIntent().getIntExtra("chapter", 0);
        page = getIntent().getIntExtra("page", 0);

        frame = new Theater.ScreenFrame(this, Theater.screenSize(this));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scroll.addView(image, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(scroll);

        FrameLayout overlay = new FrameLayout(this);
        overlay.setTag(Theater.OVERLAY);
        info = Theater.text(this, 15, 0xFFFFFFFF, true);
        info.setPadding(Theater.dp(this, 14), Theater.dp(this, 6), Theater.dp(this, 14), Theater.dp(this, 6));
        info.setBackground(Theater.round(0xCC141821, 0, 0, this, 10));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ilp.bottomMargin = Theater.dp(this, 18);
        overlay.addView(info, ilp);
        frame.addView(overlay);
        setContentView(frame);

        // Taps: left / right third turn pages, middle shows where you are
        final float[] down = new float[2];
        scroll.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { down[0] = e.getX(); down[1] = e.getY(); }
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                    && Math.abs(e.getX() - down[0]) < Theater.dp(this, 12) && Math.abs(e.getY() - down[1]) < Theater.dp(this, 12)) {
                float x = e.getX() / v.getWidth();
                boolean rtl = Theater.rightToLeft(this);
                if (x > 0.66f) { if (rtl) previous(); else next(); }
                else if (x < 0.33f) { if (rtl) next(); else previous(); }
                else showInfo();
            }
            return false;
        });

        info.setText("Loading…");
        io.execute(() -> {
            List<MihonStore.Entry> found = MihonStore.chapters(getContentResolver(), tree, seriesId);
            ui.post(() -> {
                chapters = found;
                if (chapters.isEmpty()) { info.setText("No chapters found"); return; }
                chapter = Math.max(0, Math.min(chapter, chapters.size() - 1));
                openChapter(chapter, page);
            });
        });
    }

    /* ---------- Loading ---------- */

    private void openChapter(int index, int startPage) {
        chapter = index;
        int token = ++loadToken;
        recycleCache();
        info.setText("Opening " + chapterName() + "…");
        info.setVisibility(View.VISIBLE);
        MihonStore.Entry entry = chapters.get(index);
        io.execute(() -> {
            List<Uri> found = MihonStore.pages(this, tree, entry);
            ui.post(() -> {
                if (token != loadToken) return;
                pages = found;
                if (pages.isEmpty()) { info.setText(chapterName() + " has no pages"); return; }
                // startPage < 0 means "last page" (coming back from the next chapter)
                showPage(startPage < 0 ? pages.size() - 1 : Math.min(startPage, pages.size() - 1), startPage < 0);
            });
        });
    }

    private void showPage(int index, boolean fromEnd) {
        page = index;
        MihonStore.saveProgress(this, seriesId, chapters.get(chapter).docId, page);
        Bitmap b = cache.get(index);
        if (b != null) display(b, fromEnd);
        else {
            int token = loadToken;
            io.execute(() -> {
                Bitmap bmp = decode(index);
                ui.post(() -> {
                    if (token != loadToken || page != index) return;
                    if (bmp != null) { cache.put(index, bmp); display(bmp, fromEnd); }
                    else info.setText("Couldn't open page " + (index + 1));
                });
            });
        }
        preload(index + 1);
        showInfo();
    }

    private Bitmap decode(int index) {
        if (index < 0 || index >= pages.size()) return null;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return MihonStore.decode(getContentResolver(), pages.get(index), w, h);
    }

    private void preload(int index) {
        if (index >= pages.size() || cache.containsKey(index)) return;
        int token = loadToken;
        io.execute(() -> {
            Bitmap bmp = decode(index);
            ui.post(() -> {
                if (token == loadToken && bmp != null) cache.put(index, bmp);
                trimCache();
            });
        });
    }

    /** Keeps only the pages next to the current one in memory. */
    private void trimCache() {
        List<Integer> drop = new ArrayList<>();
        for (Integer k : cache.keySet()) if (k < page - 1 || k > page + 2) drop.add(k);
        for (Integer k : drop) cache.remove(k);
    }

    private void recycleCache() { cache.clear(); }

    private void display(Bitmap b, boolean fromEnd) {
        tall = b.getHeight() > b.getWidth() * 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) image.getLayoutParams();
        lp.height = tall ? FrameLayout.LayoutParams.WRAP_CONTENT : FrameLayout.LayoutParams.MATCH_PARENT;
        image.setLayoutParams(lp);
        image.setImageBitmap(b);
        scroll.post(() -> scroll.scrollTo(0, fromEnd && tall ? image.getHeight() : 0));
        trimCache();
    }

    /* ---------- Navigation ---------- */

    private void next() {
        if (pages.isEmpty()) return;
        if (tall && scroll.canScrollVertically(1)) { scroll.smoothScrollBy(0, (int) (scroll.getHeight() * 0.8f)); return; }
        if (page + 1 < pages.size()) showPage(page + 1, false);
        else if (chapter + 1 < chapters.size()) openChapter(chapter + 1, 0);
        else { info.setText("That's the last downloaded chapter"); info.setVisibility(View.VISIBLE); }
    }

    private void previous() {
        if (pages.isEmpty()) return;
        if (tall && scroll.canScrollVertically(-1)) { scroll.smoothScrollBy(0, -(int) (scroll.getHeight() * 0.8f)); return; }
        if (page > 0) showPage(page - 1, true);
        else if (chapter > 0) openChapter(chapter - 1, -1);
    }

    private String chapterName() {
        return chapters.isEmpty() ? "" : chapters.get(chapter).name.replace(".cbz", "").replace("_", " ");
    }

    private final Runnable hideInfo = () -> info.setVisibility(View.GONE);

    private void showInfo() {
        info.setText(chapterName() + "  •  " + (page + 1) + " / " + pages.size()
                + (Theater.rightToLeft(this) ? "  •  right to left" : ""));
        info.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 2500);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean rtl = Theater.rightToLeft(this);
        if (Theater.isBack(k)) {
            if (!down) finish();
            return true;
        }
        if (!down) {
            // Swallow the key-up of everything handled below; let others (volume, etc.) through
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_BUTTON_R2: case KeyEvent.KEYCODE_BUTTON_L2:
                case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_Y:
                case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_R1:
                case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_PAGE_UP:
                    return true;
                default:
                    return Theater.isConfirm(k) || super.dispatchKeyEvent(e);
            }
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (rtl) previous(); else next();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (rtl) next(); else previous();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (tall) scroll.smoothScrollBy(0, (int) (scroll.getHeight() * 0.5f)); else next();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (tall) scroll.smoothScrollBy(0, -(int) (scroll.getHeight() * 0.5f)); else previous();
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                next();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_PAGE_UP:
                previous();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                Theater.setRightToLeft(this, !rtl);
                showInfo();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1: {
                int pct = Theater.setScreenSize(this, Theater.screenSize(this) + (k == KeyEvent.KEYCODE_BUTTON_R1 ? 5 : -5));
                frame.setPct(pct);
                info.setText("Screen size " + pct + "%");
                info.setVisibility(View.VISIBLE);
                ui.removeCallbacks(hideInfo);
                ui.postDelayed(hideInfo, 1500);
                return true;
            }
            default:
                if (Theater.isConfirm(k)) { next(); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Theater.immersive(this);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        cache.clear();
        super.onDestroy();
    }
}

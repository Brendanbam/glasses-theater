package com.brendanbam.glassestheater;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your manga as a grid of covers, organized into categories.
 *
 * Pick any parent folder once (Mihon's folder, a "Manga" folder, Downloads…). Everything inside is
 * searched for series and filed under the folder each one sits in. The last search is saved, so the
 * library opens instantly and then re-checks in the background.
 */
public class LibraryActivity extends Activity {

    private static final int PICK_FOLDER = 7;

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final String ALL = "\u0000all", CONTINUE = "\u0000continue";
    private final List<MihonStore.Series> all = new ArrayList<>();
    private final List<MihonStore.Series> series = new ArrayList<>();
    private String filter = ALL;
    private LinearLayout chips;
    private boolean scanning;
    private GridView grid;
    private TextView status;
    private Theater.ScreenFrame frame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theater.immersive(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Theater.dp(this, 24), Theater.dp(this, 20), Theater.dp(this, 24), 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Theater.text(this, 26, Theater.ACCENT, true);
        title.setText("MANGA");
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button rescan = Theater.button(this, "Rescan");
        rescan.setOnClickListener(v -> scan());
        top.addView(rescan);
        Button mihon = Theater.button(this, "Open Mihon");
        mihon.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(MihonStore.MIHON_PACKAGE);
            if (i != null) startActivity(i);
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.leftMargin = Theater.dp(this, 10);
        top.addView(mihon, mlp);
        Button folder = Theater.button(this, "Change folder");
        folder.setOnClickListener(v -> pickFolder());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.leftMargin = Theater.dp(this, 10);
        top.addView(folder, flp);
        col.addView(top);

        // Category filter: All, Continue reading, then each folder
        chips = new LinearLayout(this);
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Theater.dp(this, 14);
        col.addView(chipScroll, clp);

        status = Theater.text(this, 15, Theater.MUTED, false);
        status.setPadding(0, Theater.dp(this, 8), 0, Theater.dp(this, 8));
        col.addView(status);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Theater.dp(this, 150));
        grid.setHorizontalSpacing(Theater.dp(this, 14));
        grid.setVerticalSpacing(Theater.dp(this, 16));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(Theater.round(0x00000000, Theater.ACCENT, 3, this, 14));
        grid.setDrawSelectorOnTop(true);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, Theater.dp(this, 24));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> openSeries(series.get(pos)));
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        frame = new Theater.ScreenFrame(this, Theater.screenSize(this));
        frame.addView(col);
        setContentView(frame);

        if (MihonStore.tree(this) == null) {
            status.setText("Pick the folder your manga is in. It can be a parent folder (Mihon's folder, a Manga folder, Downloads…); everything inside it is searched.");
            pickFolder();
        }
    }

    private boolean firstResume = true;

    @Override
    protected void onResume() {
        super.onResume();
        frame.setPct(Theater.screenSize(this));
        Uri tree = MihonStore.tree(this);
        if (tree == null) return;
        if (firstResume) {
            firstResume = false;
            List<MihonStore.Series> saved = MihonStore.savedScan(this, tree);
            if (!saved.isEmpty()) show(saved);
            scan();
        } else {
            applyFilter(); // reading progress may have changed the order
        }
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, PICK_FOLDER); } catch (Exception e) { status.setText("This device has no folder picker."); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        MihonStore.setTree(this, tree);
        covers.evictAll();
        all.clear();
        filter = ALL;
        firstResume = false;
        scan();
    }

    /** Searches the picked folder in the background; the grid stays usable meanwhile. */
    private void scan() {
        Uri tree = MihonStore.tree(this);
        if (tree == null || scanning) return;
        scanning = true;
        if (all.isEmpty()) status.setText("Searching your folders for manga…");
        io.execute(() -> {
            List<MihonStore.Series> found = MihonStore.scan(getContentResolver(), tree, (folders, count) ->
                    ui.post(() -> { if (all.isEmpty()) status.setText("Searching… " + folders + " folders checked, " + count + " series found"); }));
            MihonStore.saveScan(this, tree, found);
            ui.post(() -> {
                scanning = false;
                show(found);
            });
        });
    }

    private void show(List<MihonStore.Series> found) {
        all.clear();
        all.addAll(found);
        buildChips();
        applyFilter();
    }

    private static String categoryName(MihonStore.Series s) {
        return s.source == null || s.source.isEmpty() ? "Library" : s.source;
    }

    /** One button per category (with counts), plus All and Continue reading. */
    private void buildChips() {
        chips.removeAllViews();
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int reading = 0;
        for (MihonStore.Series s : all) {
            counts.merge(categoryName(s), 1, Integer::sum);
            if (MihonStore.progress(this, s.docId) != null) reading++;
        }
        Map<String, String> items = new LinkedHashMap<>();
        items.put(ALL, "All  " + all.size());
        if (reading > 0) items.put(CONTINUE, "Continue reading  " + reading);
        if (counts.size() > 1) for (Map.Entry<String, Integer> e : counts.entrySet()) items.put(e.getKey(), e.getKey() + "  " + e.getValue());
        if (!items.containsKey(filter)) filter = ALL;
        for (Map.Entry<String, String> e : items.entrySet()) {
            Button b = Theater.button(this, e.getValue());
            b.setTextSize(15);
            b.setPadding(Theater.dp(this, 16), Theater.dp(this, 8), Theater.dp(this, 16), Theater.dp(this, 8));
            boolean on = e.getKey().equals(filter);
            if (on) b.setTextColor(Theater.ACCENT);
            final String key = e.getKey();
            b.setOnClickListener(v -> { filter = key; buildChips(); applyFilter(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Theater.dp(this, 8);
            chips.addView(b, lp);
        }
    }

    /** Shows the chosen category: recently read first, then A–Z. */
    private void applyFilter() {
        List<MihonStore.Series> shown = new ArrayList<>();
        for (MihonStore.Series s : all) {
            if (filter.equals(ALL)
                    || (filter.equals(CONTINUE) && MihonStore.progress(this, s.docId) != null)
                    || filter.equals(categoryName(s))) shown.add(s);
        }
        Collections.sort(shown, (a, b) -> {
            long ra = MihonStore.lastRead(this, a.docId), rb = MihonStore.lastRead(this, b.docId);
            if (ra != rb) return Long.compare(rb, ra);
            return a.title.compareToIgnoreCase(b.title);
        });
        boolean hadFocus = grid.hasFocus();
        series.clear();
        series.addAll(shown);
        adapter.notifyDataSetChanged();
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (MihonStore.Series s : all) distinct.add(categoryName(s).toLowerCase(java.util.Locale.US));
        int categories = distinct.size() > 1 ? distinct.size() : 0;
        status.setText(all.isEmpty()
                ? (scanning ? "Searching your folders for manga…" : "No manga found in this folder. Use Change folder to pick another one.")
                : shown.size() + " series" + (categories > 0 ? " in " + categoryLabel() : "")
                + (scanning ? "  •  checking for new downloads…" : "") + "  •  A open  •  L1 / R1 size  •  B back");
        if (!shown.isEmpty() && (hadFocus || !chips.hasFocus())) grid.requestFocus();
    }

    private String categoryLabel() {
        return filter.equals(ALL) ? "all categories" : filter.equals(CONTINUE) ? "Continue reading" : filter;
    }

    private void openSeries(MihonStore.Series s) {
        Intent i = new Intent(this, ChaptersActivity.class);
        i.putExtra("series", s.docId);
        i.putExtra("title", s.title);
        startActivity(i);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (Theater.isSizeDown(k) || Theater.isSizeUp(k)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) frame.setPct(Theater.setScreenSize(this, Theater.screenSize(this) + (Theater.isSizeUp(k) ? 5 : -5)));
            return true;
        }
        if (k == KeyEvent.KEYCODE_BUTTON_B) {
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return series.size(); }
        @Override public Object getItem(int p) { return series.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout card = (LinearLayout) convert;
            if (card == null) {
                card = new LinearLayout(LibraryActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                ImageView img = new ImageView(LibraryActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Theater.round(Theater.CARD, 0, 0, LibraryActivity.this, 12));
                img.setClipToOutline(true);
                card.addView(img, new LinearLayout.LayoutParams(-1, Theater.dp(LibraryActivity.this, 210)));
                TextView t = Theater.text(LibraryActivity.this, 14, 0xFFFFFFFF, true);
                t.setMaxLines(2);
                t.setPadding(Theater.dp(LibraryActivity.this, 4), Theater.dp(LibraryActivity.this, 6), Theater.dp(LibraryActivity.this, 4), 0);
                card.addView(t);
                TextView sub = Theater.text(LibraryActivity.this, 12, Theater.MUTED, false);
                sub.setSingleLine(true);
                sub.setPadding(Theater.dp(LibraryActivity.this, 4), 0, Theater.dp(LibraryActivity.this, 4), 0);
                card.addView(sub);
            }
            MihonStore.Series s = series.get(pos);
            ImageView img = (ImageView) card.getChildAt(0);
            ((TextView) card.getChildAt(1)).setText(s.title);
            ((TextView) card.getChildAt(2)).setText(MihonStore.progress(LibraryActivity.this, s.docId) != null ? "Continue • " + categoryName(s) : categoryName(s));
            img.setTag(s.docId);
            Bitmap cached = covers.get(s.docId);
            img.setImageBitmap(cached);
            if (cached == null) {
                final ImageView target = img;
                final Uri tree = MihonStore.tree(LibraryActivity.this);
                io.execute(() -> {
                    Bitmap b = MihonStore.cover(LibraryActivity.this, tree, s.docId, Theater.dp(LibraryActivity.this, 160));
                    if (b == null) return;
                    covers.put(s.docId, b);
                    ui.post(() -> { if (s.docId.equals(target.getTag())) target.setImageBitmap(b); });
                });
            }
            return card;
        }
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Theater.immersive(this);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}

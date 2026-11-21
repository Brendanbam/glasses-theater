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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Manga downloaded in Mihon, as a grid of covers. Recently read series come first. */
public class LibraryActivity extends Activity {

    private static final int PICK_FOLDER = 7;

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final List<MihonStore.Series> series = new ArrayList<>();
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
        Button mihon = Theater.button(this, "Open Mihon");
        mihon.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(MihonStore.MIHON_PACKAGE);
            if (i != null) startActivity(i);
        });
        top.addView(mihon);
        Button folder = Theater.button(this, "Change folder");
        folder.setOnClickListener(v -> pickFolder());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.leftMargin = Theater.dp(this, 10);
        top.addView(folder, flp);
        col.addView(top);

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
            status.setText("Pick your Mihon folder once (the one that holds \"downloads\"). In Mihon: Settings → Data and storage → Storage location shows where it is.");
            pickFolder();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        frame.setPct(Theater.screenSize(this));
        if (MihonStore.tree(this) != null) load();
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
        load();
    }

    private void load() {
        Uri tree = MihonStore.tree(this);
        if (series.isEmpty()) status.setText("Loading your downloads…");
        io.execute(() -> {
            List<MihonStore.Series> found = MihonStore.library(getContentResolver(), tree);
            // Recently read first, then A–Z
            Collections.sort(found, (a, b) -> {
                long ra = MihonStore.lastRead(this, a.docId), rb = MihonStore.lastRead(this, b.docId);
                if (ra != rb) return Long.compare(rb, ra);
                return a.title.compareToIgnoreCase(b.title);
            });
            ui.post(() -> {
                series.clear();
                series.addAll(found);
                adapter.notifyDataSetChanged();
                status.setText(found.isEmpty()
                        ? "No downloaded manga found. Download chapters in Mihon, or use Change folder to pick the Mihon folder."
                        : found.size() + " series  •  A open  •  L1 / R1 screen size  •  B back");
                if (!found.isEmpty()) grid.requestFocus();
            });
        });
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
            ((TextView) card.getChildAt(2)).setText(MihonStore.progress(LibraryActivity.this, s.docId) != null ? "Continue • " + s.source : s.source);
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

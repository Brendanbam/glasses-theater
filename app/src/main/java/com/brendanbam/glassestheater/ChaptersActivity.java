package com.brendanbam.glassestheater;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Bitmap;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Downloaded chapters of one series, with a Continue button for where you left off. */
public class ChaptersActivity extends Activity {

    private final List<MihonStore.Entry> chapters = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String seriesId, title;
    private ListView list;
    private Button resume;
    private TextView status, facts, about;
    private ImageView cover;
    private Theater.ScreenFrame frame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theater.immersive(this);
        seriesId = getIntent().getStringExtra("series");
        title = getIntent().getStringExtra("title");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Theater.dp(this, 24), Theater.dp(this, 20), Theater.dp(this, 24), Theater.dp(this, 12));
        // Cover and details (from AniList when available)
        LinearLayout header = new LinearLayout(this);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Theater.round(Theater.CARD, 0, 0, this, 12));
        cover.setClipToOutline(true);
        cover.setVisibility(View.GONE);
        LinearLayout.LayoutParams cvp = new LinearLayout.LayoutParams(Theater.dp(this, 110), Theater.dp(this, 160));
        cvp.rightMargin = Theater.dp(this, 18);
        header.addView(cover, cvp);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        TextView head = Theater.text(this, 24, 0xFFFFFFFF, true);
        head.setText(title);
        details.addView(head);
        facts = Theater.text(this, 14, Theater.ACCENT, false);
        facts.setPadding(0, Theater.dp(this, 4), 0, 0);
        details.addView(facts);
        about = Theater.text(this, 14, 0xFFD0D6E0, false);
        about.setMaxLines(5);
        about.setEllipsize(android.text.TextUtils.TruncateAt.END);
        about.setPadding(0, Theater.dp(this, 6), 0, 0);
        details.addView(about);
        header.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(header);
        status = Theater.text(this, 14, Theater.MUTED, false);
        status.setPadding(0, Theater.dp(this, 4), 0, Theater.dp(this, 12));
        status.setText("Loading chapters…");
        col.addView(status);

        resume = Theater.button(this, "▶  Continue");
        resume.setVisibility(View.GONE);
        col.addView(resume, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(Theater.dp(this, 8));
        list.setSelector(Theater.round(0x00000000, Theater.ACCENT, 3, this, 12));
        list.setDrawSelectorOnTop(true);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> read(pos, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = Theater.dp(this, 12);
        col.addView(list, lp);

        frame = new Theater.ScreenFrame(this, Theater.screenSize(this));
        frame.addView(col);
        setContentView(frame);
        loadDetails();
    }

    private void loadDetails() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Meta.Info info = Meta.get(this, title);
            if (info == null && Meta.enabled(this)) info = Meta.fetch(this, title);
            java.io.File art = Meta.coverFile(this, title);
            Bitmap b = art.exists() ? MihonStore.decode(getContentResolver(), Uri.fromFile(art), Theater.dp(this, 220), Theater.dp(this, 320)) : null;
            if (b == null) b = MihonStore.cover(this, MihonStore.tree(this), seriesId, Theater.dp(this, 220));
            final Meta.Info i = info;
            final Bitmap bmp = b;
            ui.post(() -> {
                if (bmp != null) { cover.setImageBitmap(bmp); cover.setVisibility(View.VISIBLE); }
                if (i == null || !i.found) return;
                StringBuilder f = new StringBuilder();
                if (i.english != null && !i.english.equalsIgnoreCase(title)) f.append(i.english).append("\n");
                if (i.authors != null) f.append(i.authors).append("\n");
                if (i.genres != null) f.append(i.genres).append("\n");
                StringBuilder line = new StringBuilder();
                if (i.status != null) line.append(i.status);
                if (i.chapters > 0) line.append(line.length() > 0 ? "  •  " : "").append(i.chapters).append(" chapters");
                if (i.score > 0) line.append(line.length() > 0 ? "  •  " : "").append("★ ").append(i.score).append("%");
                f.append(line);
                facts.setText(f.toString().trim());
                if (i.description != null) about.setText(i.description);
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        frame.setPct(Theater.screenSize(this));
        Uri tree = MihonStore.tree(this);
        Executors.newSingleThreadExecutor().execute(() -> {
            List<MihonStore.Entry> found = MihonStore.chapters(getContentResolver(), tree, seriesId);
            ui.post(() -> {
                chapters.clear();
                chapters.addAll(found);
                adapter.notifyDataSetChanged();
                status.setText(found.size() + " downloaded chapters  •  A read  •  B back");
                showResume();
            });
        });
    }

    private void showResume() {
        String[] p = MihonStore.progress(this, seriesId);
        int idx = -1;
        if (p != null) for (int i = 0; i < chapters.size(); i++) if (chapters.get(i).docId.equals(p[0])) idx = i;
        if (idx < 0) {
            resume.setVisibility(chapters.isEmpty() ? View.GONE : View.VISIBLE);
            resume.setText("▶  Start reading");
            resume.setOnClickListener(v -> read(0, 0));
        } else {
            final int chapter = idx;
            final int page = Integer.parseInt(p[1]);
            resume.setVisibility(View.VISIBLE);
            resume.setText("▶  Continue: " + chapters.get(idx).name.replace(".cbz", "") + "  (page " + (page + 1) + ")");
            resume.setOnClickListener(v -> read(chapter, page));
            list.setSelection(Math.max(0, idx - 1));
        }
        resume.requestFocus();
    }

    private void read(int chapter, int page) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra("series", seriesId);
        i.putExtra("title", title);
        i.putExtra("chapter", chapter);
        i.putExtra("page", page);
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
        @Override public int getCount() { return chapters.size(); }
        @Override public Object getItem(int p) { return chapters.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            TextView t = (TextView) convert;
            if (t == null) {
                t = Theater.text(ChaptersActivity.this, 16, 0xFFFFFFFF, false);
                t.setPadding(Theater.dp(ChaptersActivity.this, 18), Theater.dp(ChaptersActivity.this, 14), Theater.dp(ChaptersActivity.this, 18), Theater.dp(ChaptersActivity.this, 14));
                t.setBackground(Theater.round(Theater.CARD_2, Theater.LINE, 1, ChaptersActivity.this, 12));
            }
            MihonStore.Entry c = chapters.get(pos);
            String[] p = MihonStore.progress(ChaptersActivity.this, seriesId);
            boolean current = p != null && p[0].equals(c.docId);
            t.setText((current ? "● " : "") + c.name.replace(".cbz", "").replace("_", "  "));
            t.setTextColor(current ? Theater.ACCENT : 0xFFFFFFFF);
            return t;
        }
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Theater.immersive(this);
    }
}

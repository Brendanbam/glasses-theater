package com.brendanbam.glassestheater;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Glasses Theater home: Manga (Mihon downloads), Cinema, and the shared settings. */
public class HomeActivity extends Activity {

    private static final int PICK_VIDEO = 3;

    private Theater.ScreenFrame frame;
    private Button sizeButton, directionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theater.immersive(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Theater.dp(this, 28);
        col.setPadding(pad, pad, pad, pad);

        TextView title = Theater.text(this, 30, Theater.ACCENT, true);
        title.setText("GLASSES THEATER");
        col.addView(title);
        TextView sub = Theater.text(this, 15, Theater.MUTED, false);
        sub.setText("Manga and movies that float in your XR glasses. L1 / R1 change the screen size anywhere.");
        sub.setPadding(0, Theater.dp(this, 4), 0, Theater.dp(this, 20));
        col.addView(sub);

        Button manga = add(col, "📖   Manga  —  Mihon downloads and your manga folders");
        manga.setOnClickListener(v -> startActivity(new Intent(this, LibraryActivity.class)));

        Button cinema = add(col, "🎬   Cinema  —  open a video file");
        cinema.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");
            try { startActivityForResult(i, PICK_VIDEO); } catch (Exception ignored) { }
        });

        sizeButton = add(col, "");
        sizeButton.setOnClickListener(v -> {
            int next = Theater.screenSize(this) - 10;
            Theater.setScreenSize(this, next < 50 ? 100 : next);
            refresh();
        });

        directionButton = add(col, "");
        directionButton.setOnClickListener(v -> {
            Theater.setRightToLeft(this, !Theater.rightToLeft(this));
            refresh();
        });

        TextView tip = Theater.text(this, 14, Theater.MUTED, false);
        tip.setText("Watching from Nuvio: in Nuvio's player settings choose an external player and pick Glasses Theater. "
                + "Streams then open on the floating screen, and Nuvio remembers where you stopped.\n\n"
                + "Reading: pick any folder that holds your manga, such as Mihon's folder or a parent folder with "
                + "several. Every folder inside is searched, and series are sorted into categories by the folder "
                + "they're in. Chapters can be image folders or .cbz files.");
        tip.setPadding(0, Theater.dp(this, 20), 0, 0);
        col.addView(tip);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        frame = new Theater.ScreenFrame(this, Theater.screenSize(this));
        frame.addView(scroll);
        setContentView(frame);
        refresh();
        manga.requestFocus();
    }

    private Button add(LinearLayout col, String label) {
        Button b = Theater.button(this, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Theater.dp(this, 12);
        col.addView(b, lp);
        return b;
    }

    private void refresh() {
        sizeButton.setText("🔍   Screen size: " + Theater.screenSize(this) + "%   (A to change)");
        directionButton.setText("↔   Manga reading direction: " + (Theater.rightToLeft(this) ? "right to left" : "left to right"));
        frame.setPct(Theater.screenSize(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Intent i = new Intent(this, CinemaActivity.class);
            i.setDataAndType(data.getData(), "video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (Theater.isSizeDown(k) || Theater.isSizeUp(k)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                Theater.setScreenSize(this, Theater.screenSize(this) + (Theater.isSizeUp(k) ? 5 : -5));
                refresh();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Theater.immersive(this);
    }
}

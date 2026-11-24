package com.brendanbam.glassestheater;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Cozy dark-walnut Library & Settings overlay floating cleanly inside Theater.ScreenFrame.
 * Gives controller & touch access to every existing feature:
 * Categories, Change Folder, Rescan, Open Mihon, Open Chapters, Screen Size,
 * Default Reading Mode, AniList Cover Download, and Nuvio/Folder tips.
 */
final class MidnightSettingsOverlay extends FrameLayout {

    interface Listener {
        void onPickCategory(String key);
        void onPickFolder();
        void onRescan();
        void onOpenMihon();
        void onOpenSelectedChapters();
        void onCycleScreenSize();
        void onCycleReadingMode();
        void onToggleAniList();
        void onClose();
    }

    static final class CategoryItem {
        final String key, label;
        final boolean selected;
        CategoryItem(String key, String label, boolean selected) {
            this.key = key;
            this.label = label;
            this.selected = selected;
        }
    }

    private final LinearLayout panel;
    private final LinearLayout listCol;
    private final List<RowItem> rows = new ArrayList<>();
    private final ScrollView scrollView;
    private int focusedRow = 0;

    private static final class RowItem {
        final LinearLayout view;
        final TextView titleView;
        final TextView valueView;
        final Runnable action;
        final boolean accentValue;
        RowItem(LinearLayout view, TextView titleView, TextView valueView, Runnable action, boolean accentValue) {
            this.view = view;
            this.titleView = titleView;
            this.valueView = valueView;
            this.action = action;
            this.accentValue = accentValue;
        }
    }

    MidnightSettingsOverlay(Context c, Listener listener) {
        super(c);
        setBackgroundColor(0xD4060403);
        setClickable(true);
        setOnClickListener(v -> listener.onClose());

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Theater.dp(c, 22);
        panel.setPadding(pad, Theater.dp(c, 18), pad, Theater.dp(c, 16));
        panel.setBackground(MidnightTheme.warmPill(0xF6170F0A, MidnightTheme.GOLD, Theater.dp(c, 1.5f), Theater.dp(c, 18)));
        panel.setClickable(true);

        // Header
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(c);
        heading.setText("LIBRARY & SETTINGS");
        heading.setTextColor(MidnightTheme.GOLD_BRIGHT);
        heading.setTypeface(MidnightTheme.serifBold());
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        heading.setLetterSpacing(0.10f);
        header.addView(heading, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView closeHint = new TextView(c);
        closeHint.setText("B  Close");
        closeHint.setTextColor(MidnightTheme.GOLD_MUTED);
        closeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        closeHint.setPadding(Theater.dp(c, 12), Theater.dp(c, 5), Theater.dp(c, 12), Theater.dp(c, 5));
        closeHint.setBackground(MidnightTheme.warmPill(0x9924170F, MidnightTheme.WALNUT_BORDER_SUBTLE, Theater.dp(c, 1), Theater.dp(c, 10)));
        closeHint.setOnClickListener(v -> listener.onClose());
        header.addView(closeHint);
        panel.addView(header);

        View div = new View(c);
        div.setBackgroundColor(0x38D4AA6A);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Theater.dp(c, 1));
        dlp.topMargin = Theater.dp(c, 12);
        dlp.bottomMargin = Theater.dp(c, 10);
        panel.addView(div, dlp);

        scrollView = new ScrollView(c);
        scrollView.setVerticalScrollBarEnabled(false);
        listCol = new LinearLayout(c);
        listCol.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listCol, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        panel.addView(scrollView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LayoutParams plp = new LayoutParams(Theater.dp(c, 540), LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        int m = Theater.dp(c, 24);
        plp.setMargins(m, m, m, m);
        addView(panel, plp);
        setVisibility(GONE);
    }

    void populate(
            Context c,
            List<CategoryItem> categories,
            String selectedSeriesTitle,
            int screenSizePct,
            String readingModeLabel,
            boolean aniListOn,
            boolean hasMihon,
            Listener listener
    ) {
        listCol.removeAllViews();
        rows.clear();

        if (!categories.isEmpty()) {
            addSectionTitle(c, "SHELF CATEGORIES");
            for (CategoryItem cat : categories) {
                addRow(c, cat.label, cat.selected ? "ACTIVE" : "VIEW", cat.selected, () -> {
                    listener.onPickCategory(cat.key);
                    listener.onClose();
                });
            }
        }

        addSectionTitle(c, "MANGA LIBRARY");
        if (!TextUtils.isEmpty(selectedSeriesTitle)) {
            addRow(c, "Chapters: " + selectedSeriesTitle, "OPEN", true, () -> {
                listener.onClose();
                listener.onOpenSelectedChapters();
            });
        }
        addRow(c, "Change Manga Folder", "PICK", false, () -> {
            listener.onClose();
            listener.onPickFolder();
        });
        addRow(c, "Rescan Manga Folders", "SCAN", false, () -> {
            listener.onClose();
            listener.onRescan();
        });
        if (hasMihon) {
            addRow(c, "Open Mihon", "LAUNCH", false, () -> {
                listener.onClose();
                listener.onOpenMihon();
            });
        }

        addSectionTitle(c, "THEATER & READER SETTINGS");
        addRow(c, "Virtual Screen Size (L1 / R1)", screenSizePct + "%", true, listener::onCycleScreenSize);
        addRow(c, "Default Reading Mode", readingModeLabel, true, listener::onCycleReadingMode);
        addRow(c, "Download Covers & Info (AniList)", aniListOn ? "ON" : "OFF", aniListOn, listener::onToggleAniList);

        TextView tip = new TextView(c);
        tip.setText("Nuvio: choose Glasses Theater as external player to watch on the floating screen and sync playback position.\n"
                + "Manga: supports Mihon downloads, custom nested folders, image chapters, and .cbz archives.");
        tip.setTextColor(MidnightTheme.GOLD_DIM);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tip.setPadding(Theater.dp(c, 4), Theater.dp(c, 12), Theater.dp(c, 4), Theater.dp(c, 4));
        listCol.addView(tip);

        focusedRow = Math.max(0, Math.min(focusedRow, Math.max(0, rows.size() - 1)));
        updateFocusVisuals();
    }

    private void addSectionTitle(Context c, String label) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(MidnightTheme.GOLD_DIM);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setLetterSpacing(0.14f);
        tv.setPadding(Theater.dp(c, 4), Theater.dp(c, 10), 0, Theater.dp(c, 6));
        listCol.addView(tv);
    }

    private void addRow(Context c, String title, String value, boolean accent, Runnable action) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Theater.dp(c, 14), Theater.dp(c, 11), Theater.dp(c, 14), Theater.dp(c, 11));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Theater.dp(c, 7);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(MidnightTheme.IVORY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(c);
        v.setText(value);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setPadding(Theater.dp(c, 10), Theater.dp(c, 4), Theater.dp(c, 10), Theater.dp(c, 4));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = Theater.dp(c, 10);
        row.addView(v, vlp);

        final int idx = rows.size();
        row.setOnClickListener(view -> {
            focusedRow = idx;
            updateFocusVisuals();
            action.run();
        });
        listCol.addView(row, rlp);
        rows.add(new RowItem(row, t, v, action, accent));
    }

    void resetFocus() {
        focusedRow = 0;
        updateFocusVisuals();
        scrollView.post(() -> scrollView.scrollTo(0, 0));
    }

    void move(int delta) {
        if (rows.isEmpty()) return;
        focusedRow = (focusedRow + delta + rows.size()) % rows.size();
        updateFocusVisuals();
        View v = rows.get(focusedRow).view;
        scrollView.post(() -> {
            int top = v.getTop();
            int bot = v.getBottom();
            int sy = scrollView.getScrollY();
            int sh = scrollView.getHeight();
            if (top < sy) scrollView.smoothScrollTo(0, Math.max(0, top - 24));
            else if (bot > sy + sh) scrollView.smoothScrollTo(0, bot - sh + 24);
        });
    }

    void activate() {
        if (focusedRow >= 0 && focusedRow < rows.size()) {
            rows.get(focusedRow).action.run();
        }
    }

    private void updateFocusVisuals() {
        Context c = getContext();
        for (int i = 0; i < rows.size(); i++) {
            RowItem r = rows.get(i);
            boolean sel = (i == focusedRow);
            r.view.setBackground(MidnightTheme.warmPill(
                    sel ? 0xEE462E18 : 0xB821150E,
                    sel ? MidnightTheme.GOLD_BRIGHT : MidnightTheme.WALNUT_BORDER_SUBTLE,
                    Theater.dp(c, sel ? 2f : 1f),
                    Theater.dp(c, 12)
            ));
            r.titleView.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            r.titleView.setTextColor(sel ? Color.WHITE : MidnightTheme.IVORY);
            r.valueView.setTextColor(sel ? 0xFF1A1008 : (r.accentValue ? MidnightTheme.GOLD_BRIGHT : MidnightTheme.GOLD_MUTED));
            r.valueView.setBackground(MidnightTheme.warmPill(
                    sel ? MidnightTheme.GOLD_BRIGHT : (r.accentValue ? 0x38E5BA73 : 0x20FFFFFF),
                    sel ? Color.WHITE : (r.accentValue ? MidnightTheme.GOLD : 0x33FFFFFF),
                    Theater.dp(c, 1),
                    Theater.dp(c, 8)
            ));
        }
    }
}

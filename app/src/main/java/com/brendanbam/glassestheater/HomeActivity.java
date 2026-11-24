package com.brendanbam.glassestheater;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

/**
 * Midnight Library — Glasses Theater's main floating bookshelf experience for XR glasses & tablet.
 *
 * Preserves all existing Mihon/folder discovery, AniList cover/info downloads, categories,
 * saved reading progress, direct Resume Reading, Chapters list, Cinema / Open Video, Nuvio
 * external player support, crash reporting, and L1/R1 virtual screen resizing.
 */
public class HomeActivity extends Activity {

    private static final int PICK_VIDEO = 3;
    private static final int PICK_FOLDER = 7;
    private static final String FILTER_ALL = "\u0000all";
    private static final String FILTER_CONTINUE = "\u0000continue";

    private enum FocusZone { SHELF, FEATURED, CINEMA, TOP_CATEGORY, TOP_SETTINGS }

    private final ExecutorService io = TheaterApp.pool(3);
    private final ExecutorService metaQueue = TheaterApp.pool(1);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final Map<String, List<MihonStore.Entry>> chapterCache = new HashMap<>();

    private final List<MihonStore.Series> allSeries = new ArrayList<>();
    private final List<MihonStore.Series> filteredSeries = new ArrayList<>();
    private final List<String> categoryKeys = new ArrayList<>();
    private final Map<String, String> categoryLabels = new LinkedHashMap<>();

    private Theater.ScreenFrame frame;
    private MidnightViews.MidnightStageLayout stage;
    private MidnightSettingsOverlay settingsOverlay;

    private String activeFilter = FILTER_ALL;
    private FocusZone focusZone = FocusZone.SHELF;
    private int selectedIndex = 0; // global index into filteredSeries
    private int shelfPage = 0;
    private boolean scanning = false;
    private boolean metaRunning = false;
    private boolean firstResume = true;
    private String scanStatusMessage = null;

    // Analog joystick / D-pad cooldown state
    private long lastNavMs = 0L;
    private boolean stickNeutral = true;
    private final Runnable hideResizeToast = () -> {
        if (stage != null) stage.resizeToast.setVisibility(View.GONE);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theater.immersive(this);

        stage = new MidnightViews.MidnightStageLayout(this);
        stage.setOnScaleApplied(() -> {
            stage.rebuildBottomHints(
                    this,
                    stage.scale(),
                    v -> triggerConfirm(),
                    v -> triggerBack(),
                    v -> adjustScreenSize(-5)
            );
            refreshVisibleShelves(false);
            refreshFeaturedPanel();
            refreshTopUtilityVisuals();
        });

        // Book slot click handlers (touch & touchpad cursor)
        for (int i = 0; i < MidnightViews.MidnightStageLayout.PAGE_SLOTS; i++) {
            final int slot = i;
            stage.bookSlots[i].setOnClickListener(v -> {
                int idx = shelfPage * MidnightViews.MidnightStageLayout.PAGE_SLOTS + slot;
                if (idx < 0 || idx >= filteredSeries.size()) return;
                if (focusZone == FocusZone.SHELF && selectedIndex == idx) {
                    openChapters(filteredSeries.get(idx));
                } else {
                    selectedIndex = idx;
                    focusZone = FocusZone.SHELF;
                    refreshVisibleShelves(true);
                    refreshFeaturedPanel();
                    refreshFocusVisuals();
                }
            });
        }

        // Featured Panel actions
        stage.featuredPanel.setActionClickListener(v -> {
            focusZone = FocusZone.FEATURED;
            refreshFocusVisuals();
            triggerFeaturedAction();
        });
        stage.featuredPanel.setChaptersClickListener(v -> {
            MihonStore.Series sel = selectedSeries();
            if (sel != null) openChapters(sel);
        });

        // Cinema Card action
        stage.cinemaCard.setOnClickListener(v -> {
            focusZone = FocusZone.CINEMA;
            refreshFocusVisuals();
            openVideoPicker();
        });

        // Top utility pills & page badge
        stage.categoryPill.setOnClickListener(v -> cycleCategory());
        stage.settingsPill.setOnClickListener(v -> openSettingsOverlay());
        stage.pageIndicator.setOnClickListener(v -> {
            int pages = totalPages();
            if (pages > 1) {
                int nextPage = (shelfPage + 1) % pages;
                selectGlobalIndex(nextPage * MidnightViews.MidnightStageLayout.PAGE_SLOTS, true);
            }
        });
        stage.emptyActionBtn.setOnClickListener(v -> pickFolder());

        settingsOverlay = new MidnightSettingsOverlay(this, settingsListener);

        frame = new Theater.ScreenFrame(this, Theater.screenSize(this));
        frame.addView(stage);
        frame.addView(settingsOverlay);
        setContentView(frame);

        // Keep decorView focusable in touch mode so the very first D-pad press never gets swallowed
        View decor = getWindow().getDecorView();
        decor.setFocusable(true);
        decor.setFocusableInTouchMode(true);
        decor.requestFocus();

        updateEmptyState();
        refreshFocusVisuals();
        showCrashReport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Theater.immersive(this);
        frame.setPct(Theater.screenSize(this));
        Uri tree = MihonStore.tree(this);
        if (tree == null) {
            allSeries.clear();
            filteredSeries.clear();
            rebuildCategories();
            refreshVisibleShelves(false);
            refreshFeaturedPanel();
            updateEmptyState();
            return;
        }
        try {
            if (firstResume) {
                firstResume = false;
                List<MihonStore.Series> saved = MihonStore.savedScan(this, tree);
                if (!saved.isEmpty()) showDiscovered(saved);
                scanLibrary();
            } else {
                // Reading progress may have changed after returning from ReaderActivity or ChaptersActivity
                rebuildCategories();
                applyFilterAndRefresh(false);
            }
        } catch (Throwable t) {
            TheaterApp.record(t, "opening the Midnight Library");
            scanStatusMessage = "Couldn't load library (" + t.getClass().getSimpleName() + "). Try Rescan or Change Folder.";
            updateEmptyState();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        metaQueue.shutdownNow();
        super.onDestroy();
    }

    /* ---------------- Library Discovery & Metadata ---------------- */

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, PICK_FOLDER);
        } catch (Exception e) {
            scanStatusMessage = "This device has no folder picker available.";
            updateEmptyState();
        }
    }

    private void openVideoPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*");
        try {
            startActivityForResult(i, PICK_VIDEO);
        } catch (Exception ignored) { }
    }

    private void openMihon() {
        Intent i = getPackageManager().getLaunchIntentForPackage(MihonStore.MIHON_PACKAGE);
        if (i != null) startActivity(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_VIDEO) {
            Intent i = new Intent(this, CinemaActivity.class);
            i.setDataAndType(data.getData(), "video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } else if (requestCode == PICK_FOLDER) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            MihonStore.setTree(this, tree);
            covers.evictAll();
            chapterCache.clear();
            allSeries.clear();
            filteredSeries.clear();
            activeFilter = FILTER_ALL;
            selectedIndex = 0;
            shelfPage = 0;
            firstResume = false;
            scanLibrary();
        }
    }

    private void scanLibrary() {
        Uri tree = MihonStore.tree(this);
        if (tree == null || scanning) return;
        scanning = true;
        scanStatusMessage = "Searching your folders for manga…";
        updateEmptyState();
        io.execute(() -> {
            try {
                List<MihonStore.Series> found = MihonStore.scan(getContentResolver(), tree, (folders, count) ->
                        ui.post(() -> {
                            scanStatusMessage = "Searching… " + folders + " folders checked, " + count + " series found";
                            if (allSeries.isEmpty()) updateEmptyState();
                        }));
                MihonStore.saveScan(this, tree, found);
                ui.post(() -> {
                    scanning = false;
                    scanStatusMessage = null;
                    chapterCache.clear();
                    showDiscovered(found);
                });
            } catch (Throwable t) {
                TheaterApp.record(t, "library search");
                ui.post(() -> {
                    scanning = false;
                    scanStatusMessage = "Folder search failed (" + t.getClass().getSimpleName() + "). Try Change Folder or Rescan.";
                    updateEmptyState();
                });
            }
        });
    }

    private void showDiscovered(List<MihonStore.Series> found) {
        try {
            allSeries.clear();
            allSeries.addAll(found);
            rebuildCategories();
            applyFilterAndRefresh(false);
            fetchAniListMetadata();
        } catch (Throwable t) {
            TheaterApp.record(t, "displaying the Midnight Library");
            scanStatusMessage = "Couldn't display library (" + t.getClass().getSimpleName() + ").";
            updateEmptyState();
        }
    }

    private void fetchAniListMetadata() {
        if (metaRunning || !Meta.enabled(this)) return;
        List<MihonStore.Series> todo = new ArrayList<>();
        for (MihonStore.Series s : allSeries) {
            if (Meta.needsFetch(this, s.title)) todo.add(s);
        }
        if (todo.isEmpty()) return;
        metaRunning = true;
        metaQueue.execute(() -> {
            try {
                for (MihonStore.Series s : todo) {
                    if (Thread.currentThread().isInterrupted()) break;
                    Meta.Info info = Meta.fetch(this, s.title);
                    ui.post(() -> {
                        covers.remove(s.docId);
                        refreshCoverForSeries(s);
                        MihonStore.Series current = selectedSeries();
                        if (current != null && current.docId.equals(s.docId)) {
                            refreshFeaturedPanel();
                        }
                    });
                    try { Thread.sleep(info == null ? 3000 : 800); } catch (InterruptedException e) { break; }
                }
            } catch (Throwable t) {
                TheaterApp.record(t, "cover download");
            } finally {
                ui.post(() -> metaRunning = false);
            }
        });
    }

    private static String categoryName(MihonStore.Series s) {
        return (s.source == null || s.source.isEmpty()) ? "Library" : s.source;
    }

    private void rebuildCategories() {
        categoryKeys.clear();
        categoryLabels.clear();
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int reading = 0;
        for (MihonStore.Series s : allSeries) {
            counts.merge(categoryName(s), 1, Integer::sum);
            if (MihonStore.progress(this, s.docId) != null) reading++;
        }
        categoryLabels.put(FILTER_ALL, "All  •  " + allSeries.size());
        if (reading > 0) categoryLabels.put(FILTER_CONTINUE, "Continue  •  " + reading);
        if (counts.size() > 1) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                categoryLabels.put(e.getKey(), e.getKey() + "  •  " + e.getValue());
            }
        }
        categoryKeys.addAll(categoryLabels.keySet());
        if (!categoryLabels.containsKey(activeFilter)) activeFilter = FILTER_ALL;
        refreshTopUtilityVisuals();
    }

    private void cycleCategory() {
        if (categoryKeys.size() <= 1) return;
        int idx = categoryKeys.indexOf(activeFilter);
        activeFilter = categoryKeys.get((idx + 1) % categoryKeys.size());
        selectedIndex = 0;
        shelfPage = 0;
        applyFilterAndRefresh(true);
    }

    private void applyFilterAndRefresh(boolean animateFocus) {
        String previousDocId = selectedSeries() != null ? selectedSeries().docId : null;
        List<MihonStore.Series> shown = new ArrayList<>();
        for (MihonStore.Series s : allSeries) {
            if (activeFilter.equals(FILTER_ALL)
                    || (activeFilter.equals(FILTER_CONTINUE) && MihonStore.progress(this, s.docId) != null)
                    || activeFilter.equals(categoryName(s))) {
                shown.add(s);
            }
        }
        Collections.sort(shown, (a, b) -> {
            long ra = MihonStore.lastRead(this, a.docId);
            long rb = MihonStore.lastRead(this, b.docId);
            if (ra != rb) return Long.compare(rb, ra);
            return a.title.compareToIgnoreCase(b.title);
        });
        filteredSeries.clear();
        filteredSeries.addAll(shown);

        if (filteredSeries.isEmpty()) {
            selectedIndex = 0;
            shelfPage = 0;
            if (focusZone == FocusZone.SHELF) focusZone = FocusZone.FEATURED;
        } else {
            int restored = -1;
            if (previousDocId != null) {
                for (int i = 0; i < filteredSeries.size(); i++) {
                    if (filteredSeries.get(i).docId.equals(previousDocId)) { restored = i; break; }
                }
            }
            selectedIndex = restored >= 0 ? restored : Math.max(0, Math.min(selectedIndex, filteredSeries.size() - 1));
            shelfPage = selectedIndex / MidnightViews.MidnightStageLayout.PAGE_SLOTS;
            if (focusZone == FocusZone.FEATURED && allSeries.size() > 0 && previousDocId == null) {
                focusZone = FocusZone.SHELF;
            }
        }
        refreshTopUtilityVisuals();
        refreshVisibleShelves(animateFocus);
        refreshFeaturedPanel();
        updateEmptyState();
        refreshFocusVisuals();
    }

    private MihonStore.Series selectedSeries() {
        if (filteredSeries.isEmpty()) return null;
        int idx = Math.max(0, Math.min(selectedIndex, filteredSeries.size() - 1));
        return filteredSeries.get(idx);
    }

    private int totalPages() {
        if (filteredSeries.isEmpty()) return 1;
        return (filteredSeries.size() + MidnightViews.MidnightStageLayout.PAGE_SLOTS - 1)
                / MidnightViews.MidnightStageLayout.PAGE_SLOTS;
    }

    /* ---------------- Rendering Shelves & Featured Panel ---------------- */

    private void refreshVisibleShelves(boolean animate) {
        int start = shelfPage * MidnightViews.MidnightStageLayout.PAGE_SLOTS;
        int pages = totalPages();
        if (pages > 1) {
            stage.pageIndicator.setVisibility(View.VISIBLE);
            stage.pageIndicator.setText("‹  Shelf " + (shelfPage + 1) + " / " + pages + "  ›");
        } else {
            stage.pageIndicator.setVisibility(View.GONE);
        }

        boolean shelfFocused = (focusZone == FocusZone.SHELF);
        for (int slot = 0; slot < MidnightViews.MidnightStageLayout.PAGE_SLOTS; slot++) {
            int idx = start + slot;
            MidnightViews.MangaBookCardView card = stage.bookSlots[slot];
            if (idx < filteredSeries.size()) {
                MihonStore.Series s = filteredSeries.get(idx);
                card.setVisibility(View.VISIBLE);
                card.setTag(s.docId);
                Bitmap cached = covers.get(s.docId);
                boolean hasProg = MihonStore.progress(this, s.docId) != null;
                card.bind(s, cached, hasProg, stage.scale());
                card.setBookSelected(idx == selectedIndex, shelfFocused, animate);
                if (cached == null) loadCoverAsync(s, card);
            } else {
                card.setTag(null);
                card.setBookSelected(false, false, false);
                card.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void refreshCoverForSeries(MihonStore.Series s) {
        int start = shelfPage * MidnightViews.MidnightStageLayout.PAGE_SLOTS;
        for (int slot = 0; slot < MidnightViews.MidnightStageLayout.PAGE_SLOTS; slot++) {
            int idx = start + slot;
            if (idx < filteredSeries.size() && filteredSeries.get(idx).docId.equals(s.docId)) {
                loadCoverAsync(s, stage.bookSlots[slot]);
            }
        }
    }

    private void loadCoverAsync(MihonStore.Series s, MidnightViews.MangaBookCardView targetCard) {
        Uri tree = MihonStore.tree(this);
        int maxW = Math.max(180, Theater.dp(this, 200));
        io.execute(() -> {
            File art = Meta.coverFile(this, s.title);
            Bitmap b = art.exists()
                    ? MihonStore.decode(getContentResolver(), Uri.fromFile(art), maxW, maxW * 2)
                    : null;
            if (b == null && tree != null) {
                b = MihonStore.cover(this, tree, s.docId, maxW);
            }
            if (b == null) return;
            covers.put(s.docId, b);
            final Bitmap loaded = b;
            ui.post(() -> {
                if (s.docId.equals(targetCard.getTag())) {
                    targetCard.setCoverBitmap(loaded);
                }
                MihonStore.Series cur = selectedSeries();
                if (cur != null && cur.docId.equals(s.docId)) {
                    refreshFeaturedPanel();
                }
            });
        });
    }

    private void refreshFeaturedPanel() {
        MihonStore.Series sel = selectedSeries();
        float s = stage.scale();
        if (sel == null) {
            boolean hasFolder = MihonStore.tree(this) != null;
            stage.featuredPanel.bind(
                    hasFolder ? "Empty Shelf" : "Midnight Library",
                    hasFolder ? "No manga found in folder" : "Select your Mihon or manga folder",
                    "Supports image folders & .cbz chapters",
                    hasFolder ? "CHANGE FOLDER" : "CHOOSE FOLDER",
                    "",
                    null,
                    s
            );
            return;
        }

        Bitmap cover = covers.get(sel.docId);
        String[] prog = MihonStore.progress(this, sel.docId);
        Meta.Info info = Meta.get(this, sel.title);
        String metaSummary = buildMetaSummary(sel, info);

        List<MihonStore.Entry> cachedChapters = chapterCache.get(sel.docId);
        if (cachedChapters != null) {
            bindFeaturedWithChapters(sel, cover, prog, cachedChapters, metaSummary, s);
        } else {
            String initialProgress = prog != null ? "Page " + (parsePage(prog[1]) + 1) : categoryName(sel);
            String action = prog != null ? "RESUME READING" : "OPEN CHAPTERS";
            String secondary = prog != null ? "X  All Chapters" : "A  Select  •  X  Chapters";
            stage.featuredPanel.bind(sel.title, initialProgress, metaSummary, action, secondary, cover, s);
            loadChaptersForFeaturedAsync(sel);
        }
    }

    private void bindFeaturedWithChapters(
            MihonStore.Series sel,
            Bitmap cover,
            String[] prog,
            List<MihonStore.Entry> chapters,
            String metaSummary,
            float s
    ) {
        Set<String> readSet = MihonStore.readChapters(this, sel.docId);
        String progressLine;
        String actionText;
        String secondary;

        if (prog != null && !chapters.isEmpty()) {
            int idx = findChapterIndex(chapters, prog[0]);
            if (idx >= 0 && readSet.contains(chapters.get(idx).docId)) {
                int nextUnread = findNextUnread(chapters, idx, readSet);
                if (nextUnread >= 0) {
                    progressLine = "Next: " + cleanChapterName(chapters.get(nextUnread).name);
                    actionText = "RESUME READING";
                } else {
                    progressLine = cleanChapterName(chapters.get(idx).name) + "  •  Finished";
                    actionText = "OPEN CHAPTERS";
                }
            } else if (idx >= 0) {
                int pageNum = parsePage(prog[1]) + 1;
                progressLine = cleanChapterName(chapters.get(idx).name) + "  •  Page " + pageNum;
                actionText = "RESUME READING";
            } else {
                progressLine = chapters.size() + " Chapters";
                actionText = "START READING";
            }
            secondary = chapters.size() + " chapters  •  Tap or press X for list";
        } else {
            progressLine = chapters.isEmpty() ? categoryName(sel) : chapters.size() + (chapters.size() == 1 ? " Chapter" : " Chapters");
            actionText = chapters.isEmpty() ? "OPEN CHAPTERS" : "START READING";
            secondary = chapters.isEmpty() ? "" : "Press A on book or X for chapters";
        }
        stage.featuredPanel.bind(sel.title, progressLine, metaSummary, actionText, secondary, cover, s);
    }

    private void loadChaptersForFeaturedAsync(MihonStore.Series sel) {
        Uri tree = MihonStore.tree(this);
        if (tree == null) return;
        io.execute(() -> {
            List<MihonStore.Entry> found = MihonStore.chapters(getContentResolver(), tree, sel.docId);
            ui.post(() -> {
                chapterCache.put(sel.docId, found);
                MihonStore.Series cur = selectedSeries();
                if (cur != null && cur.docId.equals(sel.docId)) {
                    refreshFeaturedPanel();
                }
            });
        });
    }

    private String buildMetaSummary(MihonStore.Series sel, Meta.Info info) {
        if (info != null && info.found) {
            StringBuilder sb = new StringBuilder();
            if (info.status != null && !info.status.isEmpty()) sb.append(info.status);
            if (info.score > 0) {
                if (sb.length() > 0) sb.append("  •  ");
                sb.append("★ ").append(info.score).append("%");
            }
            if (info.genres != null && !info.genres.isEmpty()) {
                String firstGenre = info.genres.split(",")[0].trim();
                if (!firstGenre.isEmpty()) {
                    if (sb.length() > 0) sb.append("  •  ");
                    sb.append(firstGenre);
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        return categoryName(sel);
    }

    private static String cleanChapterName(String raw) {
        if (raw == null) return "";
        String clean = raw.replace(".cbz", "").replace(".zip", "").replace('_', ' ').trim();
        double num = Theater.chapterNumber(clean);
        if (clean.length() > 26 && num >= 0) {
            String numStr = (num == Math.floor(num))
                    ? String.valueOf((long) num)
                    : String.format(Locale.US, "%.1f", num);
            return "Chapter " + numStr;
        }
        return clean;
    }

    private static int parsePage(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static int findChapterIndex(List<MihonStore.Entry> chapters, String docId) {
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).docId.equals(docId)) return i;
        }
        return -1;
    }

    private static int findNextUnread(List<MihonStore.Entry> chapters, int afterIdx, Set<String> readSet) {
        for (int i = afterIdx + 1; i < chapters.size(); i++) {
            if (!readSet.contains(chapters.get(i).docId)) return i;
        }
        return -1;
    }

    private void updateEmptyState() {
        boolean isEmpty = filteredSeries.isEmpty();
        stage.emptyOverlay.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (!isEmpty) return;

        Uri tree = MihonStore.tree(this);
        if (tree == null) {
            stage.emptyTitle.setText("Welcome to Midnight Library");
            stage.emptyBody.setText("Choose your Mihon folder or any manga folder to fill your floating walnut shelves.");
            stage.emptyActionBtn.setText("Choose Manga Folder");
            stage.emptyActionBtn.setVisibility(View.VISIBLE);
        } else if (scanning) {
            stage.emptyTitle.setText("Stocking the Shelves…");
            stage.emptyBody.setText(scanStatusMessage != null ? scanStatusMessage : "Searching your folders for manga…");
            stage.emptyActionBtn.setVisibility(View.GONE);
        } else {
            stage.emptyTitle.setText(allSeries.isEmpty() ? "No Manga Found Here" : "No Series in This Category");
            stage.emptyBody.setText(scanStatusMessage != null
                    ? scanStatusMessage
                    : (allSeries.isEmpty()
                    ? "Pick a parent folder containing Mihon downloads, image chapter folders, or .cbz archives."
                    : "Switch categories at the top-left or open Library & Settings."));
            stage.emptyActionBtn.setText("Change Manga Folder");
            stage.emptyActionBtn.setVisibility(View.VISIBLE);
        }
    }

    private void refreshTopUtilityVisuals() {
        String catLabel = categoryLabels.get(activeFilter);
        if (catLabel == null) catLabel = "All  •  " + allSeries.size();
        stage.categoryPill.setText(catLabel + (categoryKeys.size() > 1 ? "  ▾" : ""));
        stage.categoryPill.setVisibility(allSeries.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        stage.styleUtilityPill(stage.categoryPill, focusZone == FocusZone.TOP_CATEGORY);
        stage.styleUtilityPill(stage.settingsPill, focusZone == FocusZone.TOP_SETTINGS);
    }

    private void refreshFocusVisuals() {
        boolean shelfFocused = (focusZone == FocusZone.SHELF);
        int start = shelfPage * MidnightViews.MidnightStageLayout.PAGE_SLOTS;
        for (int slot = 0; slot < MidnightViews.MidnightStageLayout.PAGE_SLOTS; slot++) {
            int idx = start + slot;
            if (idx < filteredSeries.size()) {
                stage.bookSlots[slot].setBookSelected(idx == selectedIndex, shelfFocused, true);
            }
        }
        stage.featuredPanel.setButtonFocused(focusZone == FocusZone.FEATURED);
        stage.cinemaCard.setCardFocused(focusZone == FocusZone.CINEMA);
        refreshTopUtilityVisuals();
    }

    /* ---------------- Actions: Chapters, Direct Resume, Settings ---------------- */

    private void openChapters(MihonStore.Series s) {
        Intent i = new Intent(this, ChaptersActivity.class);
        i.putExtra("series", s.docId);
        i.putExtra("title", s.title);
        startActivity(i);
    }

    private void triggerFeaturedAction() {
        MihonStore.Series sel = selectedSeries();
        if (sel == null) {
            pickFolder();
            return;
        }
        String[] prog = MihonStore.progress(this, sel.docId);
        List<MihonStore.Entry> chapters = chapterCache.get(sel.docId);
        if (chapters != null) {
            launchResumeOrChapters(sel, prog, chapters);
        } else {
            Uri tree = MihonStore.tree(this);
            if (tree == null) { openChapters(sel); return; }
            io.execute(() -> {
                List<MihonStore.Entry> found = MihonStore.chapters(getContentResolver(), tree, sel.docId);
                ui.post(() -> {
                    chapterCache.put(sel.docId, found);
                    launchResumeOrChapters(sel, prog, found);
                });
            });
        }
    }

    private void launchResumeOrChapters(MihonStore.Series sel, String[] prog, List<MihonStore.Entry> chapters) {
        if (chapters.isEmpty()) {
            openChapters(sel);
            return;
        }
        if (prog != null) {
            Set<String> readSet = MihonStore.readChapters(this, sel.docId);
            int idx = findChapterIndex(chapters, prog[0]);
            if (idx >= 0 && readSet.contains(chapters.get(idx).docId)) {
                int nextUnread = findNextUnread(chapters, idx, readSet);
                if (nextUnread >= 0) {
                    launchReader(sel, nextUnread, 0);
                    return;
                }
                openChapters(sel);
                return;
            }
            if (idx >= 0) {
                launchReader(sel, idx, parsePage(prog[1]));
                return;
            }
        }
        // Unread series: start reading chapter 1 directly from Start Reading button
        launchReader(sel, 0, 0);
    }

    private void launchReader(MihonStore.Series sel, int chapterIndex, int page) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra("series", sel.docId);
        i.putExtra("title", sel.title);
        i.putExtra("chapter", chapterIndex);
        i.putExtra("page", page);
        startActivity(i);
    }

    private void openSettingsOverlay() {
        populateSettingsOverlay();
        settingsOverlay.resetFocus();
        settingsOverlay.setVisibility(View.VISIBLE);
    }

    private void closeSettingsOverlay() {
        settingsOverlay.setVisibility(View.GONE);
        refreshFocusVisuals();
    }

    private void populateSettingsOverlay() {
        List<MidnightSettingsOverlay.CategoryItem> cats = new ArrayList<>();
        for (String key : categoryKeys) {
            String lbl = categoryLabels.get(key);
            if (lbl != null) cats.add(new MidnightSettingsOverlay.CategoryItem(key, lbl, key.equals(activeFilter)));
        }
        boolean hasMihon = getPackageManager().getLaunchIntentForPackage(MihonStore.MIHON_PACKAGE) != null;
        MihonStore.Series sel = selectedSeries();
        settingsOverlay.populate(
                this,
                cats,
                sel != null ? sel.title : null,
                Theater.screenSize(this),
                ReaderPrefs.MODE_NAMES[new ReaderPrefs(this).defaultMode()],
                Meta.enabled(this),
                hasMihon,
                settingsListener
        );
    }

    private final MidnightSettingsOverlay.Listener settingsListener = new MidnightSettingsOverlay.Listener() {
        @Override public void onPickCategory(String key) {
            activeFilter = key;
            selectedIndex = 0;
            shelfPage = 0;
            applyFilterAndRefresh(true);
        }
        @Override public void onPickFolder() { pickFolder(); }
        @Override public void onRescan() { scanLibrary(); }
        @Override public void onOpenMihon() { openMihon(); }
        @Override public void onOpenSelectedChapters() {
            MihonStore.Series sel = selectedSeries();
            if (sel != null) openChapters(sel);
        }
        @Override public void onCycleScreenSize() {
            int next = Theater.screenSize(HomeActivity.this) - 10;
            int updated = Theater.setScreenSize(HomeActivity.this, next < 50 ? 100 : next);
            frame.setPct(updated);
            showResizeBadge(updated);
            populateSettingsOverlay();
        }
        @Override public void onCycleReadingMode() {
            ReaderPrefs rp = new ReaderPrefs(HomeActivity.this);
            int next = rp.defaultMode() + 1;
            rp.setDefaultMode(next >= ReaderPrefs.MODE_NAMES.length ? 1 : next);
            populateSettingsOverlay();
        }
        @Override public void onToggleAniList() {
            boolean next = !Meta.enabled(HomeActivity.this);
            Meta.setEnabled(HomeActivity.this, next);
            populateSettingsOverlay();
            if (next) fetchAniListMetadata();
        }
        @Override public void onClose() { closeSettingsOverlay(); }
    };

    private void adjustScreenSize(int delta) {
        int updated = Theater.setScreenSize(this, Theater.screenSize(this) + delta);
        frame.setPct(updated);
        showResizeBadge(updated);
        if (settingsOverlay.getVisibility() == View.VISIBLE) populateSettingsOverlay();
    }

    private void showResizeBadge(int pct) {
        stage.resizeToast.setText("Screen Size  •  " + pct + "%");
        stage.resizeToast.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideResizeToast);
        ui.postDelayed(hideResizeToast, 1400L);
    }

    /* ---------------- Deterministic Controller & Joystick Navigation ---------------- */

    private void selectGlobalIndex(int newIdx, boolean animate) {
        if (filteredSeries.isEmpty()) return;
        selectedIndex = Math.max(0, Math.min(newIdx, filteredSeries.size() - 1));
        int targetPage = selectedIndex / MidnightViews.MidnightStageLayout.PAGE_SLOTS;
        focusZone = FocusZone.SHELF;
        if (targetPage != shelfPage) {
            shelfPage = targetPage;
            refreshVisibleShelves(false);
        } else {
            refreshFocusVisuals();
        }
        refreshFeaturedPanel();
    }

    private void handleDirection(int dir) {
        if (settingsOverlay.getVisibility() == View.VISIBLE) {
            if (dir == KeyEvent.KEYCODE_DPAD_UP) settingsOverlay.move(-1);
            else if (dir == KeyEvent.KEYCODE_DPAD_DOWN) settingsOverlay.move(1);
            return;
        }

        int pageStart = shelfPage * MidnightViews.MidnightStageLayout.PAGE_SLOTS;
        int pageCount = Math.min(MidnightViews.MidnightStageLayout.PAGE_SLOTS, Math.max(0, filteredSeries.size() - pageStart));
        int topCount = Math.min(MidnightViews.MidnightStageLayout.TOP_SLOTS, pageCount);
        int botCount = Math.max(0, pageCount - MidnightViews.MidnightStageLayout.TOP_SLOTS);
        int localSlot = Math.max(0, Math.min(selectedIndex - pageStart, Math.max(0, pageCount - 1)));
        boolean onTopRow = localSlot < MidnightViews.MidnightStageLayout.TOP_SLOTS;

        switch (focusZone) {
            case SHELF:
                if (filteredSeries.isEmpty()) {
                    focusZone = FocusZone.FEATURED;
                    refreshFocusVisuals();
                    return;
                }
                if (dir == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (onTopRow && localSlot > 0) {
                        selectGlobalIndex(selectedIndex - 1, true);
                    } else if (!onTopRow && localSlot > MidnightViews.MidnightStageLayout.TOP_SLOTS) {
                        selectGlobalIndex(selectedIndex - 1, true);
                    } else if (shelfPage > 0) {
                        // Flip cleanly to previous shelf page
                        int prevStart = (shelfPage - 1) * MidnightViews.MidnightStageLayout.PAGE_SLOTS;
                        int targetSlot = onTopRow ? MidnightViews.MidnightStageLayout.TOP_SLOTS - 1 : MidnightViews.MidnightStageLayout.PAGE_SLOTS - 1;
                        selectGlobalIndex(prevStart + targetSlot, true);
                    }
                } else if (dir == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (onTopRow) {
                        if (localSlot + 1 < topCount) {
                            selectGlobalIndex(selectedIndex + 1, true);
                        } else {
                            focusZone = FocusZone.FEATURED;
                            refreshFocusVisuals();
                        }
                    } else {
                        int botPos = localSlot - MidnightViews.MidnightStageLayout.TOP_SLOTS;
                        if (botPos + 1 < botCount) {
                            selectGlobalIndex(selectedIndex + 1, true);
                        } else {
                            focusZone = FocusZone.CINEMA;
                            refreshFocusVisuals();
                        }
                    }
                } else if (dir == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (onTopRow) {
                        if (botCount > 0) {
                            int mappedBot = Math.min(botCount - 1, Math.max(0, localSlot - 1));
                            selectGlobalIndex(pageStart + MidnightViews.MidnightStageLayout.TOP_SLOTS + mappedBot, true);
                        } else if (shelfPage + 1 < totalPages()) {
                            selectGlobalIndex((shelfPage + 1) * MidnightViews.MidnightStageLayout.PAGE_SLOTS, true);
                        }
                    } else if (shelfPage + 1 < totalPages()) {
                        int nextStart = (shelfPage + 1) * MidnightViews.MidnightStageLayout.PAGE_SLOTS;
                        int nextTop = Math.min(MidnightViews.MidnightStageLayout.TOP_SLOTS, filteredSeries.size() - nextStart);
                        int mappedTop = Math.min(nextTop - 1, Math.max(0, (localSlot - MidnightViews.MidnightStageLayout.TOP_SLOTS) + 1));
                        selectGlobalIndex(nextStart + mappedTop, true);
                    }
                } else if (dir == KeyEvent.KEYCODE_DPAD_UP) {
                    if (!onTopRow) {
                        int botPos = localSlot - MidnightViews.MidnightStageLayout.TOP_SLOTS;
                        int mappedTop = Math.min(topCount - 1, botPos + 1);
                        selectGlobalIndex(pageStart + mappedTop, true);
                    } else if (shelfPage > 0) {
                        int prevStart = (shelfPage - 1) * MidnightViews.MidnightStageLayout.PAGE_SLOTS;
                        int mappedBot = Math.min(MidnightViews.MidnightStageLayout.BOTTOM_SLOTS - 1, Math.max(0, localSlot - 1));
                        selectGlobalIndex(prevStart + MidnightViews.MidnightStageLayout.TOP_SLOTS + mappedBot, true);
                    } else {
                        focusZone = (localSlot >= 3 || allSeries.isEmpty()) ? FocusZone.TOP_SETTINGS : FocusZone.TOP_CATEGORY;
                        refreshFocusVisuals();
                    }
                }
                break;

            case FEATURED:
                if (dir == KeyEvent.KEYCODE_DPAD_LEFT && !filteredSeries.isEmpty()) {
                    focusZone = FocusZone.SHELF;
                    refreshFocusVisuals();
                } else if (dir == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusZone = FocusZone.CINEMA;
                    refreshFocusVisuals();
                } else if (dir == KeyEvent.KEYCODE_DPAD_UP) {
                    focusZone = FocusZone.TOP_SETTINGS;
                    refreshFocusVisuals();
                }
                break;

            case CINEMA:
                if (dir == KeyEvent.KEYCODE_DPAD_UP) {
                    focusZone = FocusZone.FEATURED;
                    refreshFocusVisuals();
                } else if (dir == KeyEvent.KEYCODE_DPAD_LEFT && !filteredSeries.isEmpty()) {
                    focusZone = FocusZone.SHELF;
                    refreshFocusVisuals();
                }
                break;

            case TOP_CATEGORY:
                if (dir == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    focusZone = FocusZone.TOP_SETTINGS;
                    refreshFocusVisuals();
                } else if (dir == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusZone = filteredSeries.isEmpty() ? FocusZone.FEATURED : FocusZone.SHELF;
                    refreshFocusVisuals();
                }
                break;

            case TOP_SETTINGS:
                if (dir == KeyEvent.KEYCODE_DPAD_LEFT && !allSeries.isEmpty()) {
                    focusZone = FocusZone.TOP_CATEGORY;
                    refreshFocusVisuals();
                } else if (dir == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusZone = FocusZone.FEATURED;
                    refreshFocusVisuals();
                }
                break;
        }
    }

    private void triggerConfirm() {
        if (settingsOverlay.getVisibility() == View.VISIBLE) {
            settingsOverlay.activate();
            return;
        }
        switch (focusZone) {
            case SHELF:
                MihonStore.Series sel = selectedSeries();
                if (sel != null) openChapters(sel);
                else pickFolder();
                break;
            case FEATURED:
                triggerFeaturedAction();
                break;
            case CINEMA:
                openVideoPicker();
                break;
            case TOP_CATEGORY:
                cycleCategory();
                break;
            case TOP_SETTINGS:
                openSettingsOverlay();
                break;
        }
    }

    private void triggerBack() {
        if (settingsOverlay.getVisibility() == View.VISIBLE) {
            closeSettingsOverlay();
            return;
        }
        if (focusZone != FocusZone.SHELF && !filteredSeries.isEmpty()) {
            focusZone = FocusZone.SHELF;
            refreshFocusVisuals();
            return;
        }
        if (!activeFilter.equals(FILTER_ALL)) {
            activeFilter = FILTER_ALL;
            selectedIndex = 0;
            shelfPage = 0;
            applyFilterAndRefresh(true);
            return;
        }
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (Theater.isSizeDown(k) || Theater.isSizeUp(k)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) adjustScreenSize(Theater.isSizeUp(k) ? 5 : -5);
            return true;
        }

        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                long now = SystemClock.uptimeMillis();
                long delay = e.getRepeatCount() > 0 ? 220L : 110L;
                if (now - lastNavMs >= delay) {
                    lastNavMs = now;
                    handleDirection(k);
                }
            }
            return true;
        }

        if (Theater.isConfirm(k)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) triggerConfirm();
            return true;
        }

        if (Theater.isBack(k)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) triggerBack();
            return true;
        }

        if (k == KeyEvent.KEYCODE_BUTTON_X) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && settingsOverlay.getVisibility() != View.VISIBLE) {
                MihonStore.Series sel = selectedSeries();
                if (sel != null) openChapters(sel);
            }
            return true;
        }

        if (k == KeyEvent.KEYCODE_BUTTON_Y || k == KeyEvent.KEYCODE_BUTTON_START || k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (settingsOverlay.getVisibility() == View.VISIBLE) closeSettingsOverlay();
                else openSettingsOverlay();
            }
            return true;
        }

        if (k == KeyEvent.KEYCODE_BUTTON_L2 || k == KeyEvent.KEYCODE_BUTTON_R2) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && settingsOverlay.getVisibility() != View.VISIBLE) {
                int pages = totalPages();
                if (pages > 1) {
                    int delta = (k == KeyEvent.KEYCODE_BUTTON_R2) ? 1 : -1;
                    int nextPage = (shelfPage + delta + pages) % pages;
                    selectGlobalIndex(nextPage * MidnightViews.MidnightStageLayout.PAGE_SLOTS, true);
                }
            }
            return true;
        }

        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        int src = e.getSource();
        boolean isPad = (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
        if (isPad && e.getAction() == MotionEvent.ACTION_MOVE) {
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float sx = e.getAxisValue(MotionEvent.AXIS_X);
            float sy = e.getAxisValue(MotionEvent.AXIS_Y);
            float x = Math.abs(hx) > Math.abs(sx) ? hx : sx;
            float y = Math.abs(hy) > Math.abs(sy) ? hy : sy;
            long now = SystemClock.uptimeMillis();
            if (Math.abs(x) < 0.25f && Math.abs(y) < 0.25f) {
                stickNeutral = true;
            } else if ((stickNeutral && now - lastNavMs >= 130L) || now - lastNavMs >= 260L) {
                int dir = 0;
                if (Math.abs(x) >= Math.abs(y)) {
                    if (x <= -0.55f) dir = KeyEvent.KEYCODE_DPAD_LEFT;
                    else if (x >= 0.55f) dir = KeyEvent.KEYCODE_DPAD_RIGHT;
                } else {
                    if (y <= -0.55f) dir = KeyEvent.KEYCODE_DPAD_UP;
                    else if (y >= 0.55f) dir = KeyEvent.KEYCODE_DPAD_DOWN;
                }
                if (dir != 0) {
                    stickNeutral = false;
                    lastNavMs = now;
                    handleDirection(dir);
                    return true;
                }
            }
        }
        return super.dispatchGenericMotionEvent(e);
    }

    /** Preserved crash reporter from original HomeActivity. */
    private void showCrashReport() {
        String report = TheaterApp.report(this);
        if (report == null || report.trim().isEmpty()) return;
        TextView body = Theater.text(this, 12, 0xFFD0D6E0, false);
        body.setText(report);
        body.setTextIsSelectable(true);
        body.setPadding(Theater.dp(this, 20), Theater.dp(this, 12), Theater.dp(this, 20), 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Something went wrong last time")
                .setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Glasses Theater report", report));
                    TheaterApp.clearReport(this);
                    android.widget.Toast.makeText(this, "Copied.", android.widget.Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Dismiss", (d, w) -> TheaterApp.clearReport(this))
                .show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Theater.immersive(this);
    }
}

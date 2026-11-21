package com.brendanbam.glassestheater;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the manga Mihon has downloaded.
 *
 * Mihon keeps downloads at  <Mihon folder>/downloads/<Source>/<Series>/<Chapter>  where each
 * chapter is a folder of page images or a .cbz archive. You pick the Mihon folder once (Android's
 * folder picker); Glasses Theater only reads it.
 */
final class MihonStore {

    static final String MIHON_PACKAGE = "app.mihon";

    static final class Entry {
        final String docId, name, mime;
        Entry(String docId, String name, String mime) { this.docId = docId; this.name = name; this.mime = mime; }
        boolean isDir() { return Document.MIME_TYPE_DIR.equals(mime); }
        boolean isArchive() {
            String n = name.toLowerCase(Locale.US);
            return n.endsWith(".cbz") || n.endsWith(".zip");
        }
    }

    static final class Series {
        final String docId, title, source;
        Series(String docId, String title, String source) { this.docId = docId; this.title = title; this.source = source; }
    }

    private MihonStore() { }

    /* ---------- Folder choice ---------- */

    static Uri tree(Context c) {
        String s = Theater.prefs(c).getString("mihon_tree", null);
        return s == null ? null : Uri.parse(s);
    }

    static void setTree(Context c, Uri tree) {
        Theater.prefs(c).edit().putString("mihon_tree", tree.toString()).apply();
    }

    /* ---------- Browsing ---------- */

    static List<Entry> list(ContentResolver cr, Uri tree, String parentDocId) {
        List<Entry> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
        String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(1);
                if (name == null || name.startsWith(".")) continue;
                out.add(new Entry(c.getString(0), name, c.getString(2)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The downloads folder: a "downloads" folder inside the picked one, or the picked folder itself. */
    static String downloadsDocId(ContentResolver cr, Uri tree) {
        String root = DocumentsContract.getTreeDocumentId(tree);
        for (Entry e : list(cr, tree, root)) {
            if (e.isDir() && e.name.equalsIgnoreCase("downloads")) return e.docId;
        }
        return root;
    }

    /** Every downloaded series across all sources, A–Z. */
    static List<Series> library(ContentResolver cr, Uri tree) {
        List<Series> out = new ArrayList<>();
        String downloads = downloadsDocId(cr, tree);
        for (Entry source : list(cr, tree, downloads)) {
            if (!source.isDir()) continue;
            for (Entry series : list(cr, tree, source.docId)) {
                if (series.isDir()) out.add(new Series(series.docId, series.name, source.name));
            }
        }
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    /** Chapters of a series in reading order (by chapter number, then name). */
    static List<Entry> chapters(ContentResolver cr, Uri tree, String seriesDocId) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list(cr, tree, seriesDocId)) {
            if (e.isDir() || e.isArchive()) out.add(e);
        }
        Collections.sort(out, (a, b) -> {
            double na = Theater.chapterNumber(a.name), nb = Theater.chapterNumber(b.name);
            if (na != nb) return Double.compare(na, nb);
            return Theater.natural(a.name, b.name);
        });
        return out;
    }

    static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".gif") || n.endsWith(".avif") || n.endsWith(".heif") || n.endsWith(".heic");
    }

    /** Page images of a chapter, in order. Archives are unpacked into the app's cache first. */
    static List<Uri> pages(Context ctx, Uri tree, Entry chapter) {
        ContentResolver cr = ctx.getContentResolver();
        List<Uri> out = new ArrayList<>();
        if (chapter.isDir()) {
            List<Entry> imgs = new ArrayList<>();
            for (Entry e : list(cr, tree, chapter.docId)) if (!e.isDir() && isImage(e.name)) imgs.add(e);
            Collections.sort(imgs, (a, b) -> Theater.natural(a.name, b.name));
            for (Entry e : imgs) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, e.docId));
            return out;
        }
        File dir = new File(ctx.getCacheDir(), "cbz/" + Integer.toHexString(chapter.docId.hashCode()));
        File[] cached = dir.listFiles();
        if (cached == null || cached.length == 0) {
            cleanCache(ctx);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, chapter.docId);
            try (InputStream in = cr.openInputStream(uri); ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry z;
                int i = 0;
                byte[] buf = new byte[64 * 1024];
                while ((z = zip.getNextEntry()) != null) {
                    if (z.isDirectory() || !isImage(z.getName())) continue;
                    String base = new File(z.getName()).getName();
                    // Keep the original name for ordering; prefix guards against duplicate names in subfolders
                    File f = new File(dir, String.format(Locale.US, "%s__%04d", base, i++));
                    try (OutputStream o = new FileOutputStream(f)) {
                        int n;
                        while ((n = zip.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
            } catch (Exception ignored) { }
            cached = dir.listFiles();
        }
        if (cached == null) return out;
        List<File> files = new ArrayList<>();
        Collections.addAll(files, cached);
        Collections.sort(files, (a, b) -> Theater.natural(a.getName(), b.getName()));
        for (File f : files) out.add(Uri.fromFile(f));
        return out;
    }

    /** Keeps the unpacked-archive cache to the last few chapters. */
    private static void cleanCache(Context ctx) {
        File root = new File(ctx.getCacheDir(), "cbz");
        File[] dirs = root.listFiles();
        if (dirs == null || dirs.length < 4) return;
        java.util.Arrays.sort(dirs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < dirs.length - 3; i++) deleteTree(dirs[i]);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /* ---------- Images ---------- */

    /** Decodes a page no bigger than needed for the screen (and never big enough to crash drawing). */
    static Bitmap decode(ContentResolver cr, Uri uri, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            boolean tall = o.outHeight > o.outWidth * 2;
            int sample = 1;
            // Normal pages fit the screen; long webtoon strips fit the width
            while ((tall ? o.outWidth / (sample * 2) >= maxW : (o.outWidth / (sample * 2) >= maxW || o.outHeight / (sample * 2) >= maxH))) sample *= 2;
            while ((long) (o.outWidth / sample) * (o.outHeight / sample) * 4 > 90L * 1024 * 1024 || o.outHeight / sample > 16000) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            try (InputStream in = cr.openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, d); }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** First page of the first chapter, small, for the library grid. */
    static Bitmap cover(Context ctx, Uri tree, String seriesDocId, int maxW) {
        ContentResolver cr = ctx.getContentResolver();
        List<Entry> ch = chapters(cr, tree, seriesDocId);
        if (ch.isEmpty()) return null;
        Entry first = ch.get(0);
        if (first.isDir()) {
            List<Entry> imgs = new ArrayList<>();
            for (Entry e : list(cr, tree, first.docId)) if (!e.isDir() && isImage(e.name)) imgs.add(e);
            if (imgs.isEmpty()) return null;
            Collections.sort(imgs, (a, b) -> Theater.natural(a.name, b.name));
            return decode(cr, DocumentsContract.buildDocumentUriUsingTree(tree, imgs.get(0).docId), maxW, maxW * 2);
        }
        // Archive: first image inside it
        try (InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, first.docId));
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry z;
            while ((z = zip.getNextEntry()) != null) {
                if (z.isDirectory() || !isImage(z.getName())) continue;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = 4;
                return BitmapFactory.decodeStream(zip, null, d);
            }
        } catch (Exception | OutOfMemoryError ignored) { }
        return null;
    }

    /* ---------- Progress ---------- */

    static void saveProgress(Context c, String seriesDocId, String chapterDocId, int page) {
        Theater.prefs(c).edit()
                .putString("prog:" + seriesDocId, chapterDocId + "\n" + page)
                .putLong("read:" + seriesDocId, System.currentTimeMillis())
                .apply();
    }

    /** {chapterDocId, page} or null. */
    static String[] progress(Context c, String seriesDocId) {
        String s = Theater.prefs(c).getString("prog:" + seriesDocId, null);
        if (s == null) return null;
        String[] p = s.split("\n", 2);
        return p.length == 2 ? p : null;
    }

    static long lastRead(Context c, String seriesDocId) {
        return Theater.prefs(c).getLong("read:" + seriesDocId, 0);
    }
}

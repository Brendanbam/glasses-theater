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

    /** A series found on disk. [category] is the folder it sits in (Mihon: the source; your own folders: their name). */
    static final class Series {
        final String docId, title, source;
        Series(String docId, String title, String source) { this.docId = docId; this.title = title; this.source = source; }
    }

    interface Progress { void folders(int scanned, int found); }

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

    /**
     * Searches everything under the picked folder for manga, however it's nested.
     *
     * A folder is a series when it holds chapter archives (.cbz / .zip) or chapter folders full of
     * images. A folder of loose images with no subfolders is a one-shot. Each series is filed under
     * the folder that contains it ("downloads" and the picked folder itself are skipped as names).
     */
    static List<Series> scan(ContentResolver cr, Uri tree, Progress progress) {
        List<Series> out = new ArrayList<>();
        String root = DocumentsContract.getTreeDocumentId(tree);
        int[] scanned = {0};
        walk(cr, tree, root, rootName(tree), "", 0, out, scanned, progress);
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    private static String rootName(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int cut = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
        return cut >= 0 ? id.substring(cut + 1) : id;
    }

    private static boolean skipFolder(String name) {
        String n = name.toLowerCase(Locale.US);
        // Mihon's unfinished downloads and system/cache folders
        return n.endsWith("_tmp") || n.startsWith(".") || n.equals("android") || n.equals("cache") || n.equals("autobackup");
    }

    private static void walk(ContentResolver cr, Uri tree, String docId, String name, String category, int depth,
                             List<Series> out, int[] scanned, Progress progress) {
        List<Entry> kids = list(cr, tree, docId);
        scanned[0]++;
        if (progress != null && scanned[0] % 10 == 0) progress.folders(scanned[0], out.size());

        boolean hasArchive = false, hasImages = false;
        List<Entry> dirs = new ArrayList<>();
        for (Entry e : kids) {
            if (e.isDir()) { if (!skipFolder(e.name)) dirs.add(e); }
            else if (e.isArchive()) hasArchive = true;
            else if (isImage(e.name)) hasImages = true;
        }
        if (hasArchive) { out.add(new Series(docId, name, category)); return; }

        // Chapter folders: subfolders that hold page images directly
        List<Entry> deeper = new ArrayList<>();
        int chapterDirs = 0;
        for (Entry d : dirs) {
            // A chapter folder holds page images and nothing else that looks like more manga
            // (a series folder with a cover.jpg also has chapter folders or archives inside, so it isn't one)
            boolean images = false, nested = false;
            for (Entry e : list(cr, tree, d.docId)) {
                if (e.isDir() || e.isArchive()) { nested = true; break; }
                if (isImage(e.name)) images = true;
            }
            if (images && !nested) chapterDirs++; else deeper.add(d);
        }
        if (chapterDirs > 0) { out.add(new Series(docId, name, category)); return; }
        if (hasImages && dirs.isEmpty()) { out.add(new Series(docId, name, category)); return; }
        if (depth >= 7) return;

        // Folders below here are filed under this folder's name ("downloads" and the picked folder don't count)
        String childCategory = (depth == 0 || name.equalsIgnoreCase("downloads")) ? category : name;
        for (Entry d : deeper) walk(cr, tree, d.docId, d.name, childCategory, depth + 1, out, scanned, progress);
    }

    /* ---------- Saved scan, so the library opens instantly ---------- */

    static void saveScan(Context c, Uri tree, List<Series> list) {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Series s : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", s.docId); o.put("t", s.title); o.put("c", s.source);
                a.put(o);
            }
            Theater.prefs(c).edit().putString("scan:" + tree, a.toString()).apply();
        } catch (Exception ignored) { }
    }

    static List<Series> savedScan(Context c, Uri tree) {
        List<Series> out = new ArrayList<>();
        String raw = Theater.prefs(c).getString("scan:" + tree, null);
        if (raw == null) return out;
        try {
            org.json.JSONArray a = new org.json.JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                out.add(new Series(o.getString("id"), o.getString("t"), o.optString("c", "")));
            }
        } catch (Exception ignored) { }
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
        // One-shot: the series folder itself holds the pages
        if (out.isEmpty()) {
            for (Entry e : list(cr, tree, seriesDocId)) {
                if (!e.isDir() && isImage(e.name)) { out.add(new Entry(seriesDocId, "One-shot", Document.MIME_TYPE_DIR)); break; }
            }
        }
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
        File dir = new File(ctx.getCacheDir(), "cbz2/" + Integer.toHexString(chapter.docId.hashCode()));
        File[] cached = dir.listFiles();
        if (cached == null || cached.length == 0) {
            deleteTree(new File(ctx.getCacheDir(), "cbz")); // pages unpacked by v1.0.1–1.0.2
            cleanCache(ctx);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            unpack(cr, DocumentsContract.buildDocumentUriUsingTree(tree, chapter.docId), dir, ctx.getCacheDir());
            cached = dir.listFiles();
        }
        if (cached == null) return out;
        List<File> files = new ArrayList<>();
        Collections.addAll(files, cached);
        Collections.sort(files, (a, b) -> Theater.natural(a.getName(), b.getName()));
        for (File f : files) out.add(Uri.fromFile(f));
        return out;
    }

    /**
     * Unpacks a chapter archive's page images into [dir], numbered in reading order.
     * The archive is copied first and read with ZipFile: streaming reads fail on some .cbz files
     * (stored entries with data descriptors), which left pages broken.
     */
    private static void unpack(ContentResolver cr, Uri archive, File dir, File cacheRoot) {
        File copy = new File(cacheRoot, "chapter-archive.tmp");
        byte[] buf = new byte[128 * 1024];
        try {
            try (InputStream in = cr.openInputStream(archive); OutputStream o = new FileOutputStream(copy)) {
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(copy)) {
                List<ZipEntry> imgs = new ArrayList<>();
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry z = en.nextElement();
                    if (!z.isDirectory() && isImage(z.getName()) && !new File(z.getName()).getName().startsWith(".")) imgs.add(z);
                }
                Collections.sort(imgs, (a, b) -> Theater.natural(a.getName(), b.getName()));
                for (int i = 0; i < imgs.size(); i++) {
                    ZipEntry z = imgs.get(i);
                    File f = new File(dir, String.format(Locale.US, "%04d_%s", i, new File(z.getName()).getName()));
                    try (InputStream in = zf.getInputStream(z); OutputStream o = new FileOutputStream(f)) {
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception e) {
            lastError = "couldn't unpack the .cbz (" + e.getClass().getSimpleName() + ")";
        } finally {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    /** Why the last page or archive failed, for the reader's error message. */
    static volatile String lastError = null;

    /** Keeps the unpacked-archive cache to the last few chapters. */
    private static void cleanCache(Context ctx) {
        File root = new File(ctx.getCacheDir(), "cbz2");
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
        // Android's newer decoder handles more formats (HEIF/AVIF, odd JPEGs, animated WebP/GIF first frame)
        try {
            android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(cr, uri);
            Bitmap b = android.graphics.ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                decoder.setTargetSampleSize(sampleFor(w, h, maxW, maxH));
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            if (b != null) return b;
        } catch (Exception | OutOfMemoryError e) {
            lastError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
        Bitmap b = decodeLegacy(cr, uri, maxW, maxH);
        if (b != null) lastError = null;
        return b;
    }

    private static int sampleFor(int w, int h, int maxW, int maxH) {
        boolean tall = h > w * 2;
        int sample = 1;
        while (tall ? w / (sample * 2) >= maxW : (w / (sample * 2) >= maxW || h / (sample * 2) >= maxH)) sample *= 2;
        while ((long) (w / sample) * (h / sample) * 4 > 90L * 1024 * 1024 || h / sample > 16000) sample *= 2;
        return sample;
    }

    private static Bitmap decodeLegacy(ContentResolver cr, Uri uri, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                if (lastError == null) lastError = "the image format isn't supported";
                return null;
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sampleFor(o.outWidth, o.outHeight, maxW, maxH);
            try (InputStream in = cr.openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, d); }
        } catch (Exception | OutOfMemoryError e) {
            if (lastError == null) lastError = e.getClass().getSimpleName();
            return null;
        }
    }

    /** First page of the first chapter, small, for the library grid. */
    static Bitmap cover(Context ctx, Uri tree, String seriesDocId, int maxW) {
        ContentResolver cr = ctx.getContentResolver();
        for (Entry e : list(cr, tree, seriesDocId)) {
            String n = e.name.toLowerCase(Locale.US);
            if (!e.isDir() && isImage(n) && (n.startsWith("cover") || n.startsWith("folder") || n.startsWith("poster"))) {
                return decode(cr, DocumentsContract.buildDocumentUriUsingTree(tree, e.docId), maxW, maxW * 2);
            }
        }
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

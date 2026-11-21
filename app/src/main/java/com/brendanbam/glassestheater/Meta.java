package com.brendanbam.glassestheater;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Cover art and series info from AniList (free public manga database, no account needed).
 *
 * Each series is looked up once by its folder name, cleaned of things like "[Group]", "(Digital)"
 * or "v01". The best-matching result's cover and details are saved on the tablet. Series that
 * aren't found are tried again after a week.
 */
final class Meta {

    static final class Info {
        String title, english, description, status, authors, genres;
        int score, chapters;
        boolean found;
    }

    private Meta() { }

    static boolean enabled(Context c) { return Theater.prefs(c).getBoolean("meta_enabled", true); }

    static void setEnabled(Context c, boolean on) { Theater.prefs(c).edit().putBoolean("meta_enabled", on).apply(); }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "meta");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static String key(String title) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(clean(title).toLowerCase(Locale.US).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format(Locale.US, "%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(title.hashCode());
        }
    }

    static File coverFile(Context c, String title) { return new File(dir(c), key(title) + ".jpg"); }

    private static File infoFile(Context c, String title) { return new File(dir(c), key(title) + ".json"); }

    /** "[Group] Some Title (Digital) v01" → "Some Title". */
    static String clean(String folder) {
        String t = folder.replace('_', ' ');
        t = t.replaceAll("\\[[^\\]]*]|\\([^)]*\\)|\\{[^}]*}", " ");
        t = t.replaceAll("(?i)\\b(v|vol\\.?|volume)\\s*\\d+.*$", " ");
        t = t.replaceAll("(?i)\\b(complete|digital|official|colored|webtoon|manga)\\b", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Saved info, or null if this series hasn't been looked up yet. */
    static Info get(Context c, String title) {
        File f = infoFile(c, title);
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(read(new java.io.FileInputStream(f)));
            Info i = new Info();
            i.found = o.optBoolean("found");
            i.title = o.optString("title", null);
            i.english = o.optString("english", null);
            i.description = o.optString("description", null);
            i.status = o.optString("status", null);
            i.authors = o.optString("authors", null);
            i.genres = o.optString("genres", null);
            i.score = o.optInt("score");
            i.chapters = o.optInt("chapters");
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the series has never been looked up, or wasn't found more than a week ago. */
    static boolean needsFetch(Context c, String title) {
        File f = infoFile(c, title);
        if (!f.exists()) return true;
        Info i = get(c, title);
        return i == null || (!i.found && System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000);
    }

    private static final String QUERY =
            "query($q:String){Page(perPage:6){media(search:$q,type:MANGA){"
                    + "title{romaji english native} synonyms description(asHtml:false) status chapters averageScore genres "
                    + "coverImage{extraLarge large} staff(perPage:3,sort:RELEVANCE){edges{role node{name{full}}}}}}}";

    /** Looks the series up on AniList and saves its info and cover. Runs on a background thread. */
    static Info fetch(Context c, String folderTitle) {
        String q = clean(folderTitle);
        try {
            JSONObject body = new JSONObject().put("query", QUERY).put("variables", new JSONObject().put("q", q));
            HttpURLConnection con = (HttpURLConnection) new URL("https://graphql.anilist.co").openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Accept", "application/json");
            try (OutputStream o = con.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            if (con.getResponseCode() == 429) return null; // rate limited: try again later
            if (con.getResponseCode() != 200) return null;
            JSONArray media = new JSONObject(read(con.getInputStream())).getJSONObject("data").getJSONObject("Page").getJSONArray("media");

            JSONObject best = pick(media, q);
            JSONObject saved = new JSONObject().put("found", best != null).put("query", q);
            if (best != null) {
                JSONObject t = best.getJSONObject("title");
                saved.put("title", t.optString("romaji", folderTitle));
                if (!t.isNull("english")) saved.put("english", t.optString("english"));
                if (!best.isNull("description")) saved.put("description", stripHtml(best.optString("description")));
                if (!best.isNull("status")) saved.put("status", pretty(best.optString("status")));
                saved.put("score", best.optInt("averageScore"));
                saved.put("chapters", best.optInt("chapters"));
                JSONArray g = best.optJSONArray("genres");
                if (g != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < g.length(); i++) sb.append(i > 0 ? ", " : "").append(g.getString(i));
                    saved.put("genres", sb.toString());
                }
                JSONArray staff = best.optJSONObject("staff") != null ? best.getJSONObject("staff").optJSONArray("edges") : null;
                if (staff != null && staff.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < staff.length(); i++) {
                        String name = staff.getJSONObject(i).getJSONObject("node").getJSONObject("name").optString("full");
                        if (sb.indexOf(name) < 0) sb.append(sb.length() > 0 ? ", " : "").append(name);
                    }
                    saved.put("authors", sb.toString());
                }
                JSONObject cover = best.optJSONObject("coverImage");
                String url = cover == null ? null : cover.optString("extraLarge", cover.optString("large", null));
                if (url != null && !url.isEmpty()) download(url, coverFile(c, folderTitle));
            }
            try (OutputStream o = new FileOutputStream(infoFile(c, folderTitle))) {
                o.write(saved.toString().getBytes(StandardCharsets.UTF_8));
            }
            return get(c, folderTitle);
        } catch (Exception e) {
            return null;
        }
    }

    /** The result whose title (any language or synonym) best matches the folder name. */
    private static JSONObject pick(JSONArray media, String q) throws Exception {
        if (media.length() == 0) return null;
        String want = norm(q);
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < media.length(); i++) {
            JSONObject m = media.getJSONObject(i);
            JSONObject t = m.getJSONObject("title");
            int score = 0;
            String[] names = {t.optString("romaji"), t.optString("english"), t.optString("native")};
            for (String n : names) score = Math.max(score, similarity(want, norm(n)));
            JSONArray syn = m.optJSONArray("synonyms");
            if (syn != null) for (int s = 0; s < syn.length(); s++) score = Math.max(score, similarity(want, norm(syn.optString(s))));
            if (score > bestScore) { bestScore = score; best = m; }
        }
        // AniList orders by relevance; only reject when nothing resembles the title at all
        return bestScore >= 40 ? best : media.getJSONObject(0);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** 100 = same words, 0 = nothing in common. */
    private static int similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        java.util.Set<String> wa = new java.util.HashSet<>(java.util.Arrays.asList(a.split(" ")));
        java.util.Set<String> wb = new java.util.HashSet<>(java.util.Arrays.asList(b.split(" ")));
        int common = 0;
        for (String w : wa) if (wb.contains(w)) common++;
        return 100 * common / Math.max(wa.size(), wb.size());
    }

    private static String pretty(String status) {
        switch (status) {
            case "FINISHED": return "Completed";
            case "RELEASING": return "Ongoing";
            case "HIATUS": return "On hiatus";
            case "CANCELLED": return "Cancelled";
            case "NOT_YET_RELEASED": return "Upcoming";
            default: return status;
        }
    }

    private static String stripHtml(String s) {
        return s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").replace("&quot;", "\"")
                .replace("&amp;", "&").replace("&#039;", "'").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(20_000);
        if (con.getResponseCode() != 200) return;
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = con.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(to);
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream i = in; ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        }
    }
}

package com.brendanbam.glassestheater;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Records crashes so they can be fixed: if the app ever closes on its own, Home shows what went
 * wrong next time with a Copy button. Background work runs on threads that log their own errors
 * instead of closing the app.
 */
public class TheaterApp extends Application {

    private static File logFile;

    @Override
    public void onCreate() {
        super.onCreate();
        logFile = new File(getFilesDir(), "crash.txt");
        try { BuildInfo.version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            record(e, "crash on " + t.getName());
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    static synchronized void record(Throwable e, String where) {
        if (logFile == null) return;
        try (PrintWriter w = new PrintWriter(new FileWriter(logFile, true))) {
            w.println("Glasses Theater " + BuildInfo.version + " • Android " + Build.VERSION.RELEASE + " • " + Build.MODEL);
            w.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " • " + where);
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();
            w.println(trace.length() > 6000 ? trace.substring(0, 6000) : trace);
            w.println();
        } catch (Exception ignored) { }
    }

    /** The recorded report, or null when nothing went wrong. */
    static String report(Context c) {
        File f = new File(c.getFilesDir(), "crash.txt");
        if (!f.exists()) return null;
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception e) { return null; }
    }

    static void clearReport(Context c) {
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), "crash.txt").delete();
    }

    /** Worker threads whose errors are logged rather than closing the app. */
    static ExecutorService pool(int threads) {
        ThreadFactory f = r -> {
            Thread t = new Thread(r, "theater-worker");
            t.setUncaughtExceptionHandler((th, e) -> record(e, "background task (app kept running)"));
            return t;
        };
        return threads == 1 ? Executors.newSingleThreadExecutor(f) : Executors.newFixedThreadPool(threads, f);
    }

    static final class BuildInfo {
        static String version = "?";
    }
}

package com.drakosanctis.auriga;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * AurigaApplication: global crash logger.
 *
 * Writes any uncaught exception to the app's external-files crash directory
 * (visible via Samsung My Files → Android → data → com.drakosanctis.auriga.*
 * → files → crashes) and stores a "last crash marker" in SharedPreferences so
 * the next launch can surface it with a Toast pointing at the file.
 *
 * No network calls, no third-party SDKs, no PII beyond device model + OS
 * version + stack trace. Entirely self-contained so users can debug instant-
 * crash builds on OEM-locked devices where adb is awkward (e.g. Samsung Knox
 * path on the Galaxy A07).
 */
public class AurigaApplication extends Application {

    public static final String PREFS_NAME = "auriga_crash_prefs";
    public static final String KEY_LAST_CRASH_PATH = "last_crash_path";
    public static final String KEY_LAST_CRASH_TIME = "last_crash_time";

    private static final String TAG = "AurigaCrash";
    private static final String CRASH_DIR_NAME = "crashes";

    private static volatile AurigaApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        installUncaughtExceptionHandler();
        surfaceLastCrashIfAny();
    }

    /**
     * Logs a non-fatal throwable to the same crash directory the uncaught
     * handler uses, without killing the process. Returned file path (or null
     * if logging itself failed) is also recorded in SharedPreferences so a
     * later launch can surface it via Toast. Safe to call from any thread.
     */
    public static String logNonFatal(Throwable t, String contextLabel) {
        AurigaApplication app = instance;
        if (app == null || t == null) return null;
        try {
            Thread named = new Thread(contextLabel == null ? "non-fatal" : contextLabel);
            File f = app.writeCrashFile(named, t);
            app.rememberCrashPath(f);
            Log.w(TAG, "Non-fatal recorded at " + f.getAbsolutePath(), t);
            return f.getAbsolutePath();
        } catch (Throwable loggerFailure) {
            Log.e(TAG, "logNonFatal failed", loggerFailure);
            return null;
        }
    }

    private void installUncaughtExceptionHandler() {
        final Thread.UncaughtExceptionHandler existing =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File crashFile = writeCrashFile(thread, throwable);
                rememberCrashPath(crashFile);
                Log.e(TAG, "Wrote crash report to " + crashFile.getAbsolutePath(), throwable);
            } catch (Throwable loggerFailure) {
                // Never let our crash handler hide the original crash.
                Log.e(TAG, "Crash handler itself failed", loggerFailure);
            } finally {
                // Hand back to the system/default handler so the process
                // actually terminates (otherwise the user sees a frozen app).
                if (existing != null) existing.uncaughtException(thread, throwable);
            }
        });
    }

    private File writeCrashFile(Thread thread, Throwable t) throws Exception {
        File baseDir = getExternalFilesDir(null);
        if (baseDir == null) {
            // Fall back to internal if external storage unavailable.
            baseDir = getFilesDir();
        }
        File crashDir = new File(baseDir, CRASH_DIR_NAME);
        if (!crashDir.exists() && !crashDir.mkdirs()) {
            Log.w(TAG, "Could not create crashes dir at " + crashDir.getAbsolutePath());
        }

        // Millisecond granularity so cascading initStep failures on the
        // main thread (e.g. lut fails → calibrationManager fails → engine
        // fails, all within the same second) don't collide and overwrite
        // the root-cause report.
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File out = new File(crashDir, "crash-" + stamp + ".txt");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("=== Auriga crash report ===");
        pw.println("Time:          " + new Date());
        pw.println("Thread:        " + thread.getName());
        pw.println("App version:   " + appVersionString());
        pw.println("Product:       " + safeBuildConfig("AURIGA_PRODUCT"));
        pw.println("Android:       " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        pw.println("Device:        " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        pw.println("ABIs:          " + Arrays.toString(Build.SUPPORTED_ABIS));
        pw.println();
        pw.println("=== Stack trace ===");
        t.printStackTrace(pw);
        pw.flush();

        try (FileWriter fw = new FileWriter(out)) {
            fw.write(sw.toString());
        }
        return out;
    }

    private void rememberCrashPath(File f) {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // commit() (sync) instead of apply() (async) because this is called
        // from the uncaught-exception handler, which hands off to the
        // system default handler immediately after -- that kills the
        // process via SIGKILL and any pending async write would be lost.
        // The crash *file* is already written synchronously above; we need
        // the marker to survive the kill too so the next launch can Toast
        // the user with the path.
        sp.edit()
          .putString(KEY_LAST_CRASH_PATH, f.getAbsolutePath())
          .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
          .commit();
    }

    private void surfaceLastCrashIfAny() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String path = sp.getString(KEY_LAST_CRASH_PATH, null);
        if (path == null) return;

        File f = new File(path);
        if (!f.exists()) {
            // Clean up stale marker.
            sp.edit().remove(KEY_LAST_CRASH_PATH).remove(KEY_LAST_CRASH_TIME).apply();
            return;
        }

        // Android 11+ scoped storage hides /Android/data from Samsung's
        // My Files, so a Toast pointing at the file path is useless for
        // visually-impaired / non-technical users. Instead:
        //   1) auto-copy the full crash text to the clipboard, so the
        //      user can paste it anywhere (chat, email, WhatsApp),
        //   2) launch CrashReportActivity which renders the trace on
        //      screen with COPY / SHARE / DISMISS buttons.
        // Both run best-effort -- neither must throw on teardown paths.
        copyCrashToClipboard(f);
        launchCrashReport(path);

        // Clear the marker so the viewer only fires once per crash. The
        // file itself stays on disk until the user deletes it.
        sp.edit().remove(KEY_LAST_CRASH_PATH).remove(KEY_LAST_CRASH_TIME).apply();
    }

    private void copyCrashToClipboard(java.io.File f) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            ClipboardManager cm = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Auriga crash", sb.toString()));
                Log.i(TAG, "Crash report copied to clipboard (" + sb.length() + " chars)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Clipboard copy of crash report failed", t);
        }
    }

    private void launchCrashReport(String path) {
        try {
            Intent i = new Intent(this, CrashReportActivity.class);
            i.putExtra(CrashReportActivity.EXTRA_CRASH_PATH, path);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "Could not launch CrashReportActivity", t);
            // Fall back to a Toast so something at least visibly surfaces.
            Toast.makeText(
                    this,
                    "Auriga recovered from a crash. Report saved to:\n" + path,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private String appVersionString() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /** BuildConfig access guarded for test/tool invocations without classloader. */
    private String safeBuildConfig(String field) {
        try {
            return (String) BuildConfig.class.getField(field).get(null);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}

package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * CrashReportActivity: displays the most recent crash report on screen so the
 * user can read/copy/share it without adb or a USB cable.
 *
 * Deliberately constructs every View in code (no XML layout, no theme
 * attributes, no drawables, no custom styles) so it cannot itself fail to
 * inflate on OEM-quirky devices where the regular MainActivity layout might.
 * This is the "escape hatch" that must work even when everything else is
 * broken on launch.
 *
 * Android 11+ scoped storage hides the app's external files dir from
 * Samsung My Files; this activity replaces that workflow entirely by
 *   1) rendering the crash text full-screen,
 *   2) auto-copying it to the clipboard,
 *   3) offering a Share button that opens the OS share sheet to email /
 *      WhatsApp / Drive / etc.
 */
public class CrashReportActivity extends Activity {

    public static final String EXTRA_CRASH_PATH = "auriga.crash.path";

    private static final String TAG = "AurigaCrashView";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String path = getIntent().getStringExtra(EXTRA_CRASH_PATH);
        final String body = loadBody(path);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        TextView header = new TextView(this);
        header.setText("AURIGA CRASH REPORT");
        header.setTextColor(Color.parseColor("#00F0FF"));
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        header.setPadding(0, 0, 0, dp(4));
        root.addView(header, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("The app crashed on the previous launch. Full stack trace "
                + "is below AND has been copied to your clipboard — paste it "
                + "into the chat so the fix can be targeted.\n"
                + (path == null ? "" : "File: " + path));
        sub.setTextColor(Color.WHITE);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        sub.setPadding(0, 0, 0, dp(12));
        root.addView(sub, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        TextView report = new TextView(this);
        report.setText(body);
        report.setTextColor(Color.parseColor("#E0E0E0"));
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        report.setTextIsSelectable(true);
        report.setPadding(dp(8), dp(8), dp(8), dp(8));
        report.setBackgroundColor(Color.parseColor("#101018"));
        scroll.addView(report);
        LinearLayout.LayoutParams scrollLP = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLP.bottomMargin = dp(12);
        root.addView(scroll, scrollLP);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        root.addView(buttonRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        Button copy = new Button(this);
        copy.setText("COPY");
        copy.setOnClickListener(v -> copyToClipboard(body));
        LinearLayout.LayoutParams copyLP = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        copyLP.rightMargin = dp(4);
        buttonRow.addView(copy, copyLP);

        Button share = new Button(this);
        share.setText("SHARE");
        share.setOnClickListener(v -> share(body, path));
        LinearLayout.LayoutParams shareLP = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        shareLP.leftMargin = dp(4);
        shareLP.rightMargin = dp(4);
        buttonRow.addView(share, shareLP);

        Button dismiss = new Button(this);
        dismiss.setText("DISMISS");
        dismiss.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams dismissLP = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        dismissLP.leftMargin = dp(4);
        buttonRow.addView(dismiss, dismissLP);

        setContentView(root);

        // Auto-copy on open so the user already has the trace in clipboard
        // even if they just tap DISMISS without reading.
        copyToClipboard(body);
    }

    private String loadBody(String path) {
        if (path == null) return "(no crash file path recorded)";
        File f = new File(path);
        if (!f.exists()) return "(crash file missing: " + path + ")";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read crash file", e);
            return "(failed to read " + path + ": " + e.getMessage() + ")";
        }
        return sb.toString();
    }

    private void copyToClipboard(String body) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Auriga crash", body));
                Toast.makeText(this, "Crash report copied to clipboard",
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Clipboard copy failed", t);
        }
    }

    private void share(String body, String path) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Auriga crash report");
            send.putExtra(Intent.EXTRA_TEXT, body);
            // If we have the file, also attach it via FileProvider for
            // recipients that prefer an attachment.
            if (path != null) {
                File f = new File(path);
                if (f.exists()) {
                    try {
                        Uri uri = FileProvider.getUriForFile(
                                this,
                                getPackageName() + ".crashprovider",
                                f);
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable t) {
                        Log.w(TAG, "FileProvider attach failed", t);
                    }
                }
            }
            startActivity(Intent.createChooser(send, "Share crash report"));
        } catch (Throwable t) {
            Log.e(TAG, "Share failed", t);
            Toast.makeText(this, "Share failed: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}

package com.drakosanctis.auriga;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

/**
 * LocatorWebActivity hosts the same Object Locator experience that the
 * web preview at <code>web_deploy/locator.html</code> serves. The HTML +
 * JS payload is bundled into the APK as assets at build time (see the
 * <code>copyWebDeployToAssets</code> task in <code>app/build.gradle</code>),
 * so the in-app HUD looks and behaves identically to the live website,
 * even offline once TF.js + COCO-SSD have been cached on first launch.
 *
 * The activity wires up three pieces of glue WebView needs to drive a
 * real-time camera + voice readout page hosted inside a native shell:
 *   1. Camera permission: WebChromeClient.onPermissionRequest is bridged
 *      through the standard runtime permission flow.
 *   2. Audio readout: SpeechSynthesis works out-of-the-box in modern
 *      Android System WebView; we just enable JS, DOM storage and media
 *      autoplay so the page can wire it up the same way the web build
 *      does.
 *   3. Native hamburger drawer: the activity hosts a {@link DrawerLayout}
 *      that surfaces the same rich Auriga drawer the legacy native HUD
 *      used (Calibration Walk, Send Feedback, Help, Support, Contribute,
 *      Object Targets, About, etc.). The website's own PWA drawer is
 *      hidden inside the WebView via injected CSS so the user never sees
 *      two competing menus.
 */
public class LocatorWebActivity extends Activity {

    private static final int CAMERA_PERMISSION_REQUEST = 1701;
    private static final String LOCATOR_URL = "file:///android_asset/web/locator.html";

    /**
     * Persisted mute states. Stored in {@link MainActivity#PREFS_NAME}
     * so the user's choice survives across launches and across the
     * native HUD ↔ web HUD boundary.
     */
    private static final String PREF_LOCATOR_VOICE = "locator_web_voice_enabled";
    private static final String PREF_LOCATOR_HAPTIC = "locator_web_haptic_enabled";

    /**
     * Injected on every page the WebView finishes loading. Hides the
     * website's PWA hamburger + drawer + scrim so the only menu the
     * user can pull up is the native one rendered by this activity.
     * Kept as a single &lt;style&gt; tag with a stable id so re-injection
     * on each load is idempotent.
     */
    private static final String HIDE_WEB_NAV_CSS_JS =
            "(function(){"
          + "  var id='auriga-native-shell-hide-web-nav';"
          + "  if (document.getElementById(id)) return;"
          + "  var s=document.createElement('style');"
          + "  s.id=id;"
          + "  s.textContent="
          + "    '#navToggle,.nav-toggle,.nav-drawer,#navLinks,'"
          + "  + '.nav-scrim,#navScrim{display:none !important;}'"
          + "  + 'body.nav-open{overflow:auto !important;}';"
          + "  (document.head||document.documentElement).appendChild(s);"
          + "})();";

    private WebView webView;
    private TextView fallback;
    private DrawerLayout drawerLayout;
    private TextView voiceSub;
    private TextView hapticSub;
    private boolean voiceEnabled = true;
    private boolean hapticEnabled = true;
    private PermissionRequest pendingPermissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep the screen awake while the HUD is on, like the native HUD did.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_locator_web);

        webView = findViewById(R.id.locator_webview);
        fallback = findViewById(R.id.locator_fallback);
        drawerLayout = findViewById(R.id.drawer_layout);

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        voiceEnabled = prefs.getBoolean(PREF_LOCATOR_VOICE, true);
        hapticEnabled = prefs.getBoolean(PREF_LOCATOR_HAPTIC, true);

        wireDrawer();
        configureWebView();
        // Pre-request the camera so the WebView can grant the page's
        // getUserMedia call without an extra round-trip.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
        webView.loadUrl(LOCATOR_URL);
    }

    /**
     * Wire the native hamburger drawer that wraps the WebView. Each row
     * either dismisses the drawer (if it points back to the HUD) or
     * launches the matching standalone activity. All launches go through
     * {@link #safeStart(Class, String)} so a missing or crashing target
     * surfaces as a Toast instead of crashing the host activity.
     */
    private void wireDrawer() {
        Button menuBtn = findViewById(R.id.menu_toggle);
        if (menuBtn != null && drawerLayout != null) {
            menuBtn.setOnClickListener(v -> {
                if (drawerLayout.isDrawerOpen(Gravity.START)) {
                    drawerLayout.closeDrawer(Gravity.START);
                } else {
                    drawerLayout.openDrawer(Gravity.START);
                }
            });
        }

        // ── NAVIGATE ──────────────────────────────────────────────
        View navHome = findViewById(R.id.nav_home);
        if (navHome != null) {
            navHome.setOnClickListener(v -> closeDrawer());
        }

        View navReader = findViewById(R.id.nav_reader);
        if (navReader != null) {
            navReader.setOnClickListener(v -> {
                closeDrawer();
                safeStart(ReaderActivity.class, "Reader");
            });
        }

        View navTargets = findViewById(R.id.nav_targets);
        if (navTargets != null) {
            navTargets.setOnClickListener(v -> {
                closeDrawer();
                safeStart(TargetsActivity.class, "Object Targets");
            });
        }

        View navAbout = findViewById(R.id.nav_about);
        if (navAbout != null) {
            navAbout.setOnClickListener(v -> {
                closeDrawer();
                safeStart(AboutActivity.class, "About");
            });
        }

        // ── SETUP ─────────────────────────────────────────────────
        View navCalibrate = findViewById(R.id.nav_calibrate);
        if (navCalibrate != null) {
            navCalibrate.setOnClickListener(v -> {
                closeDrawer();
                safeStart(CalibrationWalkActivity.class, "Calibration walk");
            });
        }

        View navFeedback = findViewById(R.id.nav_feedback);
        TextView feedbackHint = findViewById(R.id.nav_feedback_hint);
        if (navFeedback != null) {
            navFeedback.setOnClickListener(v -> {
                closeDrawer();
                safeStart(FeedbackActivity.class, "Feedback");
            });
        }
        refreshFeedbackGate(navFeedback, feedbackHint);

        // Voice + haptic mute toggles. They update SharedPreferences and
        // immediately push the new state into the WebView so the change
        // takes effect mid-session, without closing the drawer (the user
        // is likely toggling these because the HUD just got disruptive).
        View navVoice = findViewById(R.id.nav_voice_locator);
        voiceSub = findViewById(R.id.nav_voice_locator_sub);
        if (navVoice != null) {
            navVoice.setOnClickListener(v -> {
                voiceEnabled = !voiceEnabled;
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(PREF_LOCATOR_VOICE, voiceEnabled).apply();
                refreshMuteLabels();
                applyMuteStateToWebView();
                Toast.makeText(this,
                        voiceEnabled ? "Voice ON" : "Voice MUTED",
                        Toast.LENGTH_SHORT).show();
            });
        }

        View navHaptic = findViewById(R.id.nav_haptic_locator);
        hapticSub = findViewById(R.id.nav_haptic_locator_sub);
        if (navHaptic != null) {
            navHaptic.setOnClickListener(v -> {
                hapticEnabled = !hapticEnabled;
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(PREF_LOCATOR_HAPTIC, hapticEnabled).apply();
                refreshMuteLabels();
                applyMuteStateToWebView();
                Toast.makeText(this,
                        hapticEnabled ? "Haptic ON" : "Haptic MUTED",
                        Toast.LENGTH_SHORT).show();
            });
        }
        refreshMuteLabels();

        // ── SUPPORT ───────────────────────────────────────────────
        View navHelp = findViewById(R.id.nav_help);
        if (navHelp != null) {
            navHelp.setOnClickListener(v -> {
                closeDrawer();
                safeStart(HelpActivity.class, "Help");
            });
        }

        View navSupport = findViewById(R.id.nav_support);
        if (navSupport != null) {
            navSupport.setOnClickListener(v -> {
                closeDrawer();
                safeStart(SupportActivity.class, "Support");
            });
        }

        // ── CONTRIBUTE ────────────────────────────────────────────
        View navContributeCalibration = findViewById(R.id.nav_contribute_calibration);
        if (navContributeCalibration != null) {
            navContributeCalibration.setOnClickListener(v -> {
                closeDrawer();
                safeStart(ContributeActivity.class, "Contribute");
            });
        }

        View navContributeSdk = findViewById(R.id.nav_contribute_sdk);
        if (navContributeSdk != null) {
            navContributeSdk.setOnClickListener(v -> {
                closeDrawer();
                safeStart(ContributeActivity.class, "Contribute");
            });
        }
    }

    private void closeDrawer() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START);
        }
    }

    /**
     * Mirror MainActivity's gating: if the user hasn't completed the
     * 10-point calibration walk yet, dim the Feedback row and surface
     * the amber hint. The row stays tappable so a tap still reaches
     * FeedbackActivity, which itself enforces the gate properly.
     */
    private void refreshFeedbackGate(View feedbackRow, TextView hint) {
        try {
            SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
            boolean walkDone = prefs.getBoolean(
                    CalibrationWalkActivity.PREF_WALK_DONE, false);
            if (hint != null) hint.setVisibility(walkDone ? View.GONE : View.VISIBLE);
            if (feedbackRow != null) feedbackRow.setAlpha(walkDone ? 1f : 0.55f);
        } catch (Throwable t) {
            // Non-fatal — the row stays tappable either way.
        }
    }

    private void safeStart(Class<?> target, String label) {
        try {
            startActivity(new Intent(this, target));
        } catch (Throwable t) {
            Toast.makeText(this,
                    label + " unavailable: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Update the VOICE / HAPTIC sub-labels to reflect the current mute state. */
    private void refreshMuteLabels() {
        if (voiceSub != null) {
            voiceSub.setText(voiceEnabled ? "ON · tap to mute" : "MUTED · tap to enable");
        }
        if (hapticSub != null) {
            hapticSub.setText(hapticEnabled ? "ON · tap to mute" : "MUTED · tap to enable");
        }
    }

    /**
     * Build the JS snippet that monkey-patches {@code SpeechSynthesis.speak}
     * and {@code navigator.vibrate} inside the WebView. The patch is
     * idempotent: it stashes the original implementations on first run
     * and only swaps the wrapper functions on subsequent runs, so calling
     * it on every page load (and on every toggle) is safe.
     *
     * When voice is muted the wrapper also calls {@code speechSynthesis.cancel()}
     * so any utterance already in flight stops immediately — matching what
     * the user expects when they tap MUTE because the HUD just got noisy.
     */
    private String buildMutePatchJs() {
        return "(function(){"
             + "  if (!window.__aurigaShellPatched) {"
             + "    window.__aurigaShellPatched = true;"
             + "    try {"
             + "      window.__aurigaOrigSpeak = window.speechSynthesis.speak.bind(window.speechSynthesis);"
             + "    } catch(e) { window.__aurigaOrigSpeak = function(){}; }"
             + "    window.__aurigaOrigVibrate = (navigator.vibrate)"
             + "      ? navigator.vibrate.bind(navigator)"
             + "      : function(){return false;};"
             + "    window.speechSynthesis.speak = function(u) {"
             + "      if (window.__aurigaVoiceEnabled) return window.__aurigaOrigSpeak(u);"
             + "      try { window.speechSynthesis.cancel(); } catch(e){}"
             + "      return undefined;"
             + "    };"
             + "    navigator.vibrate = function(p) {"
             + "      if (window.__aurigaHapticEnabled) return window.__aurigaOrigVibrate(p);"
             + "      return false;"
             + "    };"
             + "  }"
             + "  window.__aurigaVoiceEnabled = " + (voiceEnabled ? "true" : "false") + ";"
             + "  window.__aurigaHapticEnabled = " + (hapticEnabled ? "true" : "false") + ";"
             + "  if (!window.__aurigaVoiceEnabled) {"
             + "    try { window.speechSynthesis.cancel(); } catch(e){}"
             + "  }"
             + "})();";
    }

    /**
     * Push the current mute state into the live WebView. Safe to call
     * before the page has finished loading — {@link #buildMutePatchJs()}
     * is idempotent and {@code onPageFinished} re-applies it anyway.
     */
    private void applyMuteStateToWebView() {
        if (webView == null) return;
        try {
            webView.evaluateJavascript(buildMutePatchJs(), null);
        } catch (Throwable t) {
            // Non-fatal — onPageFinished will re-apply on the next load.
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // The page is loaded over file:// but pulls TF.js + COCO-SSD over
        // https. COMPATIBILITY_MODE permits that mix so the model loads.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Cache the heavy ML CDN payload so the HUD survives later
        // network drops the same way the PWA does in a real browser.
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                fallback.setVisibility(View.GONE);
                // Suppress the website's PWA drawer so the user only
                // ever sees the native one this activity hosts. Re-run
                // on every page load so cross-page navigation inside
                // the WebView (locator → targets → locator) stays clean.
                view.evaluateJavascript(HIDE_WEB_NAV_CSS_JS, null);
                // Re-apply the voice + haptic mute state, since each
                // navigation hands us a fresh document with the original
                // SpeechSynthesis + navigator.vibrate implementations.
                view.evaluateJavascript(buildMutePatchJs(), null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> handlePermissionRequest(req));
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                // Surface JS errors via logcat so the in-app HUD can be
                // debugged with the same console output the web build has.
                android.util.Log.d("LocatorWeb",
                        "[" + cm.messageLevel() + "] " + cm.message()
                                + " @ " + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });
    }

    private void handlePermissionRequest(PermissionRequest req) {
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    req.grant(new String[]{r});
                } else {
                    pendingPermissionRequest = req;
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.CAMERA},
                            CAMERA_PERMISSION_REQUEST);
                }
                return;
            }
        }
        // Anything else (mic, midi, etc.) we don't need — refuse it.
        req.deny();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingPermissionRequest != null) {
            if (granted) {
                pendingPermissionRequest.grant(
                        new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
        }
        if (!granted) {
            fallback.setText("Camera permission is required for the Object Locator.");
            fallback.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        // Re-evaluate the feedback gate in case the user just finished
        // the calibration walk in another activity.
        refreshFeedbackGate(findViewById(R.id.nav_feedback),
                findViewById(R.id.nav_feedback_hint));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /**
     * Close the drawer first, then let the WebView handle in-page
     * navigation, then fall back to default activity finish.
     */
    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START);
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Keep the legacy KEYCODE_BACK path so hardware back keys on
        // older devices behave the same as the gesture / system back.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (drawerLayout != null && drawerLayout.isDrawerOpen(Gravity.START)) {
                drawerLayout.closeDrawer(Gravity.START);
                return true;
            }
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}

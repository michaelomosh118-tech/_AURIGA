package com.drakosanctis.auriga;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * LocatorWebActivity hosts the same Object Locator experience that the
 * web preview at <code>web_deploy/locator.html</code> serves. The HTML +
 * JS payload is bundled into the APK as assets at build time (see the
 * <code>copyWebDeployToAssets</code> task in <code>app/build.gradle</code>),
 * so the in-app HUD looks and behaves identically to the live website,
 * even offline once TF.js + COCO-SSD have been cached on first launch.
 *
 * The activity wires up the two pieces of glue WebView needs to drive a
 * real-time camera + voice readout page:
 *   1. Camera permission: WebChromeClient.onPermissionRequest is bridged
 *      through the standard runtime permission flow.
 *   2. Audio readout: SpeechSynthesis works out-of-the-box in modern
 *      Android System WebView; we just enable JS, DOM storage and media
 *      autoplay so the page can wire it up the same way the web build
 *      does.
 */
public class LocatorWebActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 1701;
    private static final String LOCATOR_URL = "file:///android_asset/web/locator.html";

    private WebView webView;
    private TextView fallback;
    private PermissionRequest pendingPermissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep the screen awake while the HUD is on, like the native HUD did.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_locator_web);

        webView = findViewById(R.id.locator_webview);
        fallback = findViewById(R.id.locator_fallback);

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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK
                && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}

package com.drakosanctis.auriga;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CameraStreamActivity — turns the phone into a wireless MJPEG camera server.
 *
 * <p>Starts a plain Java {@link ServerSocket} on port {@value PORT} that serves
 * {@code multipart/x-mixed-replace} MJPEG frames from the back camera. Any browser
 * or the Auriga PWA ({@code camera-connect.html} → Wi-Fi tab → MJPEG) can connect
 * using the URL shown on screen: {@code http://<phone-wifi-ip>:8080/stream}.
 *
 * <p>Works for NAVI and ARAEL (sentinel) flavors — both share this activity.
 * No external library dependencies; pure Java {@code ServerSocket} + CameraX.
 */
public class CameraStreamActivity extends ComponentActivity {

    private static final String TAG      = "CameraStream";
    private static final int    PORT     = 8080;
    private static final int    CAM_REQ  = 2001;
    private static final String BOUNDARY = "auriga_frame";

    private final Handler                     mainHandler  = new Handler(Looper.getMainLooper());
    private final AtomicBoolean               streaming    = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<OutputStream> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger               clientCount  = new AtomicInteger(0);
    private final AtomicInteger               frameCount   = new AtomicInteger(0);

    private ExecutorService      cameraExec;
    private ExecutorService      serverExec;
    private ServerSocket         serverSocket;
    private ProcessCameraProvider cameraProvider;

    private volatile byte[] latestFrame;

    private TextView urlLabel;
    private TextView statusLabel;
    private Button   streamBtn;

    // ───────────────────────────────────────────────────────────────[...]
    // Lifecycle
    // ───────────────────────────────────────────────────────────────[...]

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cameraExec = Executors.newSingleThreadExecutor();
        serverExec = Executors.newCachedThreadPool();

        setContentView(buildUi());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStream();
        cameraExec.shutdownNow();
        serverExec.shutdownNow();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] ps, @NonNull int[] gs) {
        if (req == CAM_REQ && gs.length > 0 && gs[0] == PackageManager.PERMISSION_GRANTED) {
            startStream();
        } else {
            Toast.makeText(this, "Camera permission required to stream", Toast.LENGTH_SHORT).show();
        }
    }

    // ────────────────────────────────────────────────────────────────[...]
    // UI — built programmatically (no XML dep, same pattern as CrashReportActivity)
    // ────────────────────────────────────────────────────────────────[...]

    private ScrollView buildUi() {
        int C_BG     = Color.parseColor("#030D0D");
        int C_CARD   = Color.parseColor("#0A1E1E");
        int C_CARD2  = Color.parseColor("#07181A");
        int C_CYAN   = Color.parseColor("#00F0FF");
        int C_DIM    = Color.parseColor("#336666");
        int C_STEP   = Color.parseColor("#4A9090");
        int C_IDLE   = Color.parseColor("#336666");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setGravity(Gravity.TOP);
        root.setPadding(dp(20), dp(48), dp(20), dp(32));

        // ── Title ─────────────────────────────────────────────────────────[...]
        TextView title = new TextView(this);
        title.setText("⊞  STREAM TO LAPTOP");
        title.setTextColor(C_CYAN);
        title.setTextSize(16f);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Auriga MJPEG Camera Server  ·  Port " + PORT);
        sub.setTextColor(C_DIM);
        sub.setTextSize(11f);
        sub.setLetterSpacing(0.06f);
        sub.setPadding(0, 0, 0, dp(24));
        root.addView(sub);

        // ── URL card ───────────────────────────────────────────────────────
        LinearLayout urlCard = card(C_CARD, dp(16), dp(20));
        root.addView(urlCard);

        addLabel(urlCard, "STREAM URL — paste into Camera Connect  →  Wi-Fi  →  MJPEG", C_DIM, 10f, dp(8));

        urlLabel = new TextView(this);
        urlLabel.setText("http://" + getWifiIp() + ":" + PORT + "/stream");
        urlLabel.setTextColor(C_CYAN);
        urlLabel.setTextSize(17f);
        urlLabel.setTypeface(Typeface.MONOSPACE);
        urlLabel.setPadding(0, 0, 0, dp(12));
        urlCard.addView(urlLabel);

        Button copyBtn = makeBtn("  COPY URL  ", C_CARD, C_CYAN);
        copyBtn.setOnClickListener(v -> copyUrl());
        urlCard.addView(copyBtn);

        // ── How-to card ────────────────────────────────────────────────────
        LinearLayout howCard = card(C_CARD2, dp(16), dp(24));
        root.addView(howCard);

        String[] steps = {
            "1.  Phone and laptop must be on the same Wi-Fi network.",
            "2.  Tap START STREAM on this screen.",
            "3.  On the laptop open the Auriga PWA → Camera Connect.",
            "4.  Go to the Wi-Fi tab, set Format to MJPEG.",
            "5.  Paste the URL above into the MJPEG URL field.",
            "6.  Tap Connect — the HUD will show this phone's live camera."
        };
        for (String step : steps) addLabel(howCard, step, C_STEP, 12f, dp(5));

        // ── Status ──────────────────────────────────────────────────────────[...]
        statusLabel = new TextView(this);
        statusLabel.setText("● IDLE  —  tap START STREAM to begin");
        statusLabel.setTextColor(C_IDLE);
        statusLabel.setTextSize(12f);
        statusLabel.setLetterSpacing(0.05f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.bottomMargin = dp(14);
        statusLabel.setLayoutParams(sLp);
        root.addView(statusLabel);

        // ── Stream button ──────────────────────────────────────────────────
        streamBtn = makeBtn("START STREAM", Color.parseColor("#003030"), C_CYAN);
        streamBtn.setTextSize(14f);
        streamBtn.setOnClickListener(v -> { if (!streaming.get()) startStream(); else stopStream(); });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.bottomMargin = dp(12);
        streamBtn.setLayoutParams(btnLp);
        root.addView(streamBtn);

        // ── Back button ────────────────────────────────────────────────────
        Button back = makeBtn("← BACK", C_CARD2, C_DIM);
        back.setOnClickListener(v -> finish());
        back.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        root.addView(back);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        return sv;
    }

    // ────────────────────────────────────────────────────────────────[...]
    // Stream lifecycle
    // ────────────────────────────────────────────────────────────────[...]

    private void startStream() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAM_REQ);
            return;
        }
        streaming.set(true);
        streamBtn.setText("STOP STREAM");
        setStatus("⬤  STARTING…", "#FFAA00");

        serverExec.execute(this::runHttpServer);

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                Log.e(TAG, "Provider error", e);
                mainHandler.post(() -> setStatus("✕  Camera error: " + e.getMessage(), "#FF4444"));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopStream() {
        streaming.set(false);
        if (cameraProvider != null) {
            try { cameraProvider.unbindAll(); } catch (Throwable ignored) {}
        }
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        clients.clear();
        clientCount.set(0);
        frameCount.set(0);
        latestFrame = null;
        if (streamBtn != null) streamBtn.setText("START STREAM");
        setStatus("● IDLE  —  tap START STREAM to begin", "#336666");
    }

    // ────────────────────────────────────────────────────────────────[...]
    // CameraX → JPEG pipeline
    // ────────────────────────────────────────────────────────────────[...]

    private void bindCamera() {
        CameraSelector sel = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        analysis.setAnalyzer(cameraExec, proxy -> {
            if (!streaming.get()) { proxy.close(); return; }
            byte[] jpeg = toJpeg(proxy);
            proxy.close();
            if (jpeg == null) return;
            latestFrame = jpeg;
            broadcastFrame(jpeg);
            int fc = frameCount.incrementAndGet();
            if (fc % 60 == 0) {
                final int cc = clientCount.get();
                mainHandler.post(() -> setStatus(
                    "⬤  LIVE  ·  " + cc + " client" + (cc == 1 ? "" : "s") +
                    "  ·  frame " + fc, "#00F0B0"));
            }
        });

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, sel, analysis);
            mainHandler.post(() -> setStatus("⬤  STREAMING  ·  waiting for laptop…", "#00F0B0"));
        } catch (Exception e) {
            Log.e(TAG, "bindCamera error", e);
            mainHandler.post(() -> setStatus("✕  Bind error: " + e.getMessage(), "#FF4444"));
        }
    }

    private byte[] toJpeg(ImageProxy proxy) {
        try {
            if (proxy.getFormat() == android.graphics.ImageFormat.JPEG) {
                ByteBuffer buf = proxy.getPlanes()[0].getBuffer();
                byte[] b = new byte[buf.remaining()];
                buf.get(b);
                return b;
            }
            Bitmap bmp = proxy.toBitmap();
            if (bmp == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, baos);
            bmp.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "toJpeg error", e);
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────────[...]
    // HTTP MJPEG server
    // ────────────────────────────────────────────────────────────────[...]

    private void runHttpServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            Log.i(TAG, "MJPEG server on port " + PORT);
            while (streaming.get()) {
                try {
                    Socket client = serverSocket.accept();
                    serverExec.execute(() -> handleClient(client));
                } catch (Exception e) {
                    if (streaming.get()) Log.e(TAG, "accept error", e);
                }
            }
        } catch (Exception e) {
            if (streaming.get()) {
                Log.e(TAG, "ServerSocket error", e);
                mainHandler.post(() -> setStatus("✕  Server error: " + e.getMessage(), "#FF4444"));
            }
        }
    }

    private void handleClient(Socket client) {
        OutputStream out = null;
        try {
            out = client.getOutputStream();
            // Drain the HTTP request (we respond to everything with the stream)
            byte[] reqBuf = new byte[2048];
            client.getInputStream().read(reqBuf);

            // MJPEG HTTP response
            out.write(("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n").getBytes());
            out.flush();

            clients.add(out);
            final int cc = clientCount.incrementAndGet();
            mainHandler.post(() -> setStatus(
                "⬤  STREAMING  ·  " + cc + " client" + (cc == 1 ? "" : "s") + " connected",
                "#00F0B0"));

            // Send last frame immediately so the client sees something right away
            byte[] first = latestFrame;
            if (first != null) writeFrame(out, first);

            // Hold the thread open — broadcastFrame() pushes subsequent frames
            while (streaming.get() && !client.isClosed()) Thread.sleep(400);

        } catch (Exception e) {
            Log.d(TAG, "Client disconnected: " + e.getMessage());
        } finally {
            if (out != null) clients.remove(out);
            try { client.close(); } catch (Exception ignored) {}
            final int cc = clientCount.decrementAndGet();
            mainHandler.post(() -> setStatus(
                streaming.get()
                    ? "⬤  STREAMING  ·  " + cc + " client" + (cc == 1 ? "" : "s") + " connected"
                    : "● IDLE  —  tap START STREAM to begin",
                streaming.get() ? "#00F0B0" : "#336666"));
        }
    }

    private void broadcastFrame(byte[] jpeg) {
        for (OutputStream out : clients) {
            try {
                writeFrame(out, jpeg);
            } catch (Exception e) {
                clients.remove(out);
            }
        }
    }

    private void writeFrame(OutputStream out, byte[] jpeg) throws Exception {
        String header = "--" + BOUNDARY + "\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: " + jpeg.length + "\r\n\r\n";
        out.write(header.getBytes());
        out.write(jpeg);
        out.write("\r\n".getBytes());
        out.flush();
    }

    // ────────────────────────────────────────────────────────────────[...]
    // Helpers
    // ────────────────────────────────────────────────────────────────[...]

    private String getWifiIp() {
        try {
            WifiManager wm = (WifiManager)
                    getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                return String.format(Locale.US, "%d.%d.%d.%d",
                        ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        } catch (Exception ignored) {}
        return "?.?.?.?";
    }

    private void copyUrl() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("stream-url",
                    urlLabel.getText().toString()));
            Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void setStatus(String text, String hex) {
        if (statusLabel != null) {
            statusLabel.setText(text);
            statusLabel.setTextColor(Color.parseColor(hex));
        }
    }

    // ────────────────────────────────────────────────────────────────[...]
    // UI factory helpers
    // ────────────────────────────────────────────────────────────────[...]

    private LinearLayout card(int bg, int hPad, int bottomMargin) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(bg);
        c.setPadding(hPad, hPad, hPad, hPad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMargin;
        c.setLayoutParams(lp);
        return c;
    }

    private void addLabel(LinearLayout parent, String text, int color, float sp, int bottomPad) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setPadding(0, 0, 0, bottomPad);
        parent.addView(t);
    }

    private Button makeBtn(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setBackgroundColor(bg);
        b.setTextColor(fg);
        b.setLetterSpacing(0.09f);
        b.setAllCaps(true);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

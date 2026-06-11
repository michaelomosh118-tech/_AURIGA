package com.drakosanctis.auriga;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * ModelStatusActivity — AI model download management screen.
 *
 * Shows the current download state of each Qwen model with live
 * progress feedback. Users tap Download / Cancel / Delete directly.
 * When a model becomes READY, AurigaVoiceEngine hot-reloads MindEngine
 * automatically via its own DownloadListener — this screen is pure UI.
 */
public class ModelStatusActivity extends Activity
        implements ModelDownloadManager.DownloadListener {

    private final Handler main = new Handler(Looper.getMainLooper());

    // Qwen Small (0.5B) card views
    private TextView    smallStatus;
    private ProgressBar smallBar;
    private Button      smallBtn;

    // Qwen Large (1.5B) card views
    private TextView    largeStatus;
    private ProgressBar largeBar;
    private Button      largeBtn;

    // Footer: active model label
    private TextView activeLabel;

    // ── Lifecycle ─────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_status);

        // Back / close
        View backBtn = findViewById(R.id.btn_back);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        // Qwen Small views
        smallStatus = findViewById(R.id.small_status);
        smallBar    = findViewById(R.id.small_progress);
        smallBtn    = findViewById(R.id.btn_small);

        // Qwen Large views
        largeStatus = findViewById(R.id.large_status);
        largeBar    = findViewById(R.id.large_progress);
        largeBtn    = findViewById(R.id.btn_large);

        // Footer
        activeLabel = findViewById(R.id.active_model_label);

        // Wire buttons
        if (smallBtn != null) smallBtn.setOnClickListener(v -> handleSmallBtn());
        if (largeBtn != null) largeBtn.setOnClickListener(v -> handleLargeBtn());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // getOrCreateMgr() guarantees a non-null manager so the listener is
        // always registered and live progress callbacks always arrive.
        getOrCreateMgr().registerListener(this);
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ModelDownloadManager mgr = AurigaApplication.modelDownloadManager;
        if (mgr != null) mgr.unregisterListener(this);
    }

    // ── DownloadListener callbacks (main thread via notifyXxx in mgr) ─

    @Override
    public void onProgress(ModelDownloadManager.ModelId model, int percentDone) {
        main.post(() -> {
            if (model == ModelDownloadManager.ModelId.QWEN_SMALL) {
                if (smallBar    != null) smallBar.setProgress(percentDone);
                if (smallStatus != null) {
                    smallStatus.setText("DOWNLOADING  " + percentDone + "%");
                    smallStatus.setTextColor(getColor(R.color.model_downloading));
                }
            } else {
                if (largeBar    != null) largeBar.setProgress(percentDone);
                if (largeStatus != null) {
                    largeStatus.setText("DOWNLOADING  " + percentDone + "%");
                    largeStatus.setTextColor(getColor(R.color.model_downloading));
                }
            }
        });
    }

    @Override
    public void onStateChanged(ModelDownloadManager.ModelId model,
                               ModelDownloadManager.ModelState newState) {
        main.post(this::refreshAll);
    }

    // ── Button actions ────────────────────────────────────────────────

    private void handleSmallBtn() {
        ModelDownloadManager mgr = getOrCreateMgr();
        switch (mgr.getState(ModelDownloadManager.ModelId.QWEN_SMALL)) {
            case READY:
                mgr.cancelDownload(ModelDownloadManager.ModelId.QWEN_SMALL);
                mgr.deleteModel(ModelDownloadManager.ModelId.QWEN_SMALL);
                toast("Qwen 0.5B deleted");
                break;
            case DOWNLOADING:
                mgr.cancelDownload(ModelDownloadManager.ModelId.QWEN_SMALL);
                toast("Download cancelled");
                break;
            default:
                if (!ModelDownloadManager.isOnline(this)) {
                    toast("No internet connection — please connect and try again");
                    return;
                }
                mgr.ensureQwenSmallDownloaded();
                toast("Downloading Qwen 0.5B  (~519 MB)…");
                break;
        }
        refreshAll();
    }

    private void handleLargeBtn() {
        ModelDownloadManager mgr = getOrCreateMgr();
        switch (mgr.getState(ModelDownloadManager.ModelId.QWEN_LARGE)) {
            case READY:
                mgr.cancelDownload(ModelDownloadManager.ModelId.QWEN_LARGE);
                mgr.deleteModel(ModelDownloadManager.ModelId.QWEN_LARGE);
                toast("Qwen 1.5B deleted");
                break;
            case DOWNLOADING:
                mgr.cancelDownload(ModelDownloadManager.ModelId.QWEN_LARGE);
                toast("Download cancelled");
                break;
            default:
                if (!ModelDownloadManager.isOnline(this)) {
                    toast("No internet connection — please connect and try again");
                    return;
                }
                mgr.ensureQwenLargeDownloaded();
                toast("Downloading Qwen 1.5B  (~800 MB)…");
                break;
        }
        refreshAll();
    }

    /**
     * Returns the global {@link ModelDownloadManager}, creating and registering
     * it if it is null. This handles the case where the app started offline and
     * {@link com.drakosanctis.auriga.AurigaApplication} deferred manager creation.
     */
    private ModelDownloadManager getOrCreateMgr() {
        if (AurigaApplication.modelDownloadManager == null) {
            AurigaApplication.modelDownloadManager = new ModelDownloadManager(this);
        }
        return AurigaApplication.modelDownloadManager;
    }

    // ── UI refresh ────────────────────────────────────────────────────

    private void refreshAll() {
        ModelDownloadManager mgr = AurigaApplication.modelDownloadManager;
        if (mgr == null) {
            applyCardState(smallStatus, smallBar, smallBtn,
                    ModelDownloadManager.ModelState.NOT_DOWNLOADED, 0);
            applyCardState(largeStatus, largeBar, largeBtn,
                    ModelDownloadManager.ModelState.NOT_DOWNLOADED, 0);
            setActiveLabel(false, false);
            return;
        }

        ModelDownloadManager.ModelState ss = mgr.getState(ModelDownloadManager.ModelId.QWEN_SMALL);
        ModelDownloadManager.ModelState ls = mgr.getState(ModelDownloadManager.ModelId.QWEN_LARGE);

        applyCardState(smallStatus, smallBar, smallBtn, ss,
                mgr.getProgressPercent(ModelDownloadManager.ModelId.QWEN_SMALL));
        applyCardState(largeStatus, largeBar, largeBtn, ls,
                mgr.getProgressPercent(ModelDownloadManager.ModelId.QWEN_LARGE));

        setActiveLabel(ss == ModelDownloadManager.ModelState.READY,
                       ls == ModelDownloadManager.ModelState.READY);
    }

    private void applyCardState(TextView statusTv, ProgressBar bar, Button btn,
                                ModelDownloadManager.ModelState state, int pct) {
        if (statusTv == null || bar == null || btn == null) return;
        switch (state) {
            case READY:
                statusTv.setText("READY  ✓");
                statusTv.setTextColor(getColor(R.color.model_ready));
                bar.setVisibility(View.GONE);
                btn.setText("DELETE");
                break;
            case DOWNLOADING:
                statusTv.setText("DOWNLOADING  " + pct + "%");
                statusTv.setTextColor(getColor(R.color.model_downloading));
                bar.setVisibility(View.VISIBLE);
                bar.setProgress(pct);
                btn.setText("CANCEL");
                break;
            default:
                statusTv.setText("NOT DOWNLOADED");
                statusTv.setTextColor(getColor(R.color.model_missing));
                bar.setVisibility(View.GONE);
                btn.setText("DOWNLOAD");
                break;
        }
    }

    private void setActiveLabel(boolean smallReady, boolean largeReady) {
        if (activeLabel == null) return;
        if (largeReady)       activeLabel.setText(getString(R.string.drawer_ai_active_large));
        else if (smallReady)  activeLabel.setText(getString(R.string.drawer_ai_active_small));
        else                  activeLabel.setText(getString(R.string.drawer_ai_active_none));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}

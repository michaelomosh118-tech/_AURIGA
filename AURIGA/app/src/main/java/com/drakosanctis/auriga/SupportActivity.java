package com.drakosanctis.auriga;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;

/**
 * SupportActivity — Drawer "Support Centre" destination.
 *
 * Surfaces the most common FAQs, contact addresses and the
 * DrakoSanctis accessibility commitment in the Auriga HUD
 * design language (dark glass, cyan glows).
 */
public class SupportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_support);

        Button back = findViewById(R.id.support_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}

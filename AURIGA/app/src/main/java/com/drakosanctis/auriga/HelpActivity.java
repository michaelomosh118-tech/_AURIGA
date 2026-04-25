package com.drakosanctis.auriga;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;

/**
 * HelpActivity: short user guide reachable from the hamburger drawer.
 * Pure markup; the only Java responsibility is wiring the back button.
 */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        Button back = findViewById(R.id.help_back);
        if (back != null) back.setOnClickListener(v -> finish());
    }
}

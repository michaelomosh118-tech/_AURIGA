package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

/**
 * AboutActivity: static product blurb reachable from the hamburger drawer.
 *
 * <p>Builds the version stamp at runtime from {@link PackageInfo} so the
 * displayed string always matches the installed APK (no risk of hard-coded
 * strings drifting from {@code build.gradle}).
 */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Button back = findViewById(R.id.about_back);
        if (back != null) back.setOnClickListener(v -> finish());

        TextView stamp = findViewById(R.id.about_build_stamp);
        if (stamp != null) {
            String label = "AurigaNavi · v?";
            try {
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                label = "AurigaNavi · v" + info.versionName
                        + " (build " + info.versionCode + ")";
            } catch (Throwable ignored) {
                // Defensive: PackageManager.NameNotFoundException is theoretical
                // for our own package, but we never want About to crash.
            }
            stamp.setText(label + "\n© DrakoSanctis · See Beyond Sight");
        }
    }
}

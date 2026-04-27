package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * AboutActivity: static product blurb reachable from the hamburger drawer.
 *
 * <p>Builds the version stamp at runtime from {@link PackageInfo} so the
 * displayed string always matches the installed APK (no risk of hard-coded
 * strings drifting from {@code build.gradle}).
 *
 * <p>Wires two contact buttons (project Gmail + maintainer's personal
 * Gmail) using mailto: composers. Addresses are assembled at runtime
 * from a username + provider so they don't sit in the layout XML as
 * straight scrape-bait.
 */
public class AboutActivity extends Activity {

    private static final String PROVIDER     = "gmail.com";
    private static final String PROJECT_USER = "drakosanctis";
    private static final String PERSONAL_USER = "oluochmichael975";

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

        // ── Contact tiles ─────────────────────────────────────────
        final String project  = PROJECT_USER  + "@" + PROVIDER;
        final String personal = PERSONAL_USER + "@" + PROVIDER;

        TextView addrProject  = findViewById(R.id.about_addr_project);
        TextView addrPersonal = findViewById(R.id.about_addr_personal);
        if (addrProject  != null) addrProject.setText(project);
        if (addrPersonal != null) addrPersonal.setText(personal);

        Button mailProject  = findViewById(R.id.about_mail_project);
        Button mailPersonal = findViewById(R.id.about_mail_personal);
        if (mailProject != null) {
            mailProject.setOnClickListener(v -> openMail(project,
                    "[AURIGA] Hello",
                    "Hi DrakoSanctis team,\n\n"));
        }
        if (mailPersonal != null) {
            mailPersonal.setOnClickListener(v -> openMail(personal,
                    "[AURIGA] Hello, Michael",
                    "Hi Michael,\n\n"));
        }
    }

    private void openMail(String address, String subject, String prefilledBody) {
        try {
            Uri uri = Uri.parse("mailto:" + address
                    + "?subject=" + Uri.encode(subject)
                    + "&body=" + Uri.encode(prefilledBody));
            Intent intent = new Intent(Intent.ACTION_SENDTO, uri);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this,
                    "No email app available. Address: " + address,
                    Toast.LENGTH_LONG).show();
        }
    }
}

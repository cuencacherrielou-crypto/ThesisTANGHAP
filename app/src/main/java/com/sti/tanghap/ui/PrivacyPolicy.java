package com.sti.tanghap.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.sti.tanghap.R;

/** Terms summary dialog with a link to the full privacy policy hosted on GitHub Pages. */
final class PrivacyPolicy {
    private PrivacyPolicy() {}

    static void showDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Terms and Privacy Policy")
                .setMessage("TANGHAP respects your privacy. All sensor data is stored securely and used only for providing trend analytics. We do not provide medical diagnosis. By using this app, you agree to our data processing practices.")
                .setPositiveButton("I Understand", null)
                .setNeutralButton("Read full policy", (d, w) -> open(activity))
                .show();
    }

    static void open(Activity activity) {
        Uri uri = Uri.parse(activity.getString(R.string.privacy_policy_url));
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException noBrowser) {
            Toast.makeText(activity, "No web browser is installed.", Toast.LENGTH_SHORT).show();
        }
    }
}

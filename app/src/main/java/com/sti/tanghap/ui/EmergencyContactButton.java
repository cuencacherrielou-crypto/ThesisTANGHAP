package com.sti.tanghap.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.sti.tanghap.data.ProfilePrefs;

/**
 * Shows the saved emergency contact on a button that opens the phone dialer.
 * ACTION_DIAL is used instead of ACTION_CALL so the user confirms the call and
 * no CALL_PHONE permission is needed.
 */
public final class EmergencyContactButton {

    private EmergencyContactButton() {}

    /** Shows the button when {@code show} is true and a contact is saved; hides it otherwise. */
    public static void bind(Activity activity, MaterialButton button, boolean show) {
        if (button == null) return;
        if (!show) {
            button.setVisibility(View.GONE);
            return;
        }

        button.setVisibility(View.VISIBLE);
        if (!ProfilePrefs.hasEmergencyContact(activity)) {
            button.setText("Add an Emergency Contact");
            button.setOnClickListener(v ->
                    activity.startActivity(new Intent(activity, EditProfileActivity.class)));
            return;
        }

        String name = ProfilePrefs.emergencyName(activity);
        String relation = ProfilePrefs.emergencyRelation(activity);
        String label = name.isEmpty() ? "Emergency Contact" : name;
        if (!relation.isEmpty()) label += " (" + relation + ")";
        button.setText("Call " + label);
        button.setOnClickListener(v -> dial(activity, ProfilePrefs.emergencyPhone(activity)));
    }

    public static void dial(Activity activity, String phone) {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
        try {
            // startActivity directly: resolveActivity() returns null on Android 11+
            // unless the app declares a <queries> entry.
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No phone app available to place the call.", Toast.LENGTH_SHORT).show();
        }
    }
}

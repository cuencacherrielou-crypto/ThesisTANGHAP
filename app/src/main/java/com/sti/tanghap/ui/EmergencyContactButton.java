package com.sti.tanghap.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.sti.tanghap.data.ProfilePrefs;

import java.util.ArrayList;
import java.util.List;

/**
 * Emergency call button shown on worsening findings. Tapping it offers the saved emergency
 * contact, the national emergency hotline (911 in the Philippines) and editing the contact,
 * so there is always a way to call for help even before a contact is saved.
 * ACTION_DIAL is used instead of ACTION_CALL so the user confirms the call and no
 * CALL_PHONE permission is needed.
 */
public final class EmergencyContactButton {

    /** Philippine national emergency hotline (E911). */
    public static final String EMERGENCY_HOTLINE = "911";

    private EmergencyContactButton() {}

    /** Shows the button when {@code show} is true; hides it otherwise. */
    public static void bind(Activity activity, MaterialButton button, boolean show) {
        if (button == null) return;
        if (!show) {
            button.setVisibility(View.GONE);
            return;
        }
        button.setVisibility(View.VISIBLE);
        if (ProfilePrefs.hasEmergencyContact(activity)) {
            button.setText("Call " + contactLabel(activity) + " or 911");
        } else {
            button.setText("Emergency Call (911)");
        }
        button.setOnClickListener(v -> showOptions(activity));
    }

    private static String contactLabel(Activity activity) {
        String name = ProfilePrefs.emergencyName(activity);
        String relation = ProfilePrefs.emergencyRelation(activity);
        String label = name.isEmpty() ? "Emergency Contact" : name;
        if (!relation.isEmpty()) label += " (" + relation + ")";
        return label;
    }

    /** The choices behind the button: saved contact, 911, and adding or editing the contact. */
    public static void showOptions(Activity activity) {
        if (activity.isFinishing()) return;
        boolean hasContact = ProfilePrefs.hasEmergencyContact(activity);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (hasContact) {
            String phone = ProfilePrefs.emergencyPhone(activity);
            labels.add("Call " + contactLabel(activity) + "  •  " + phone);
            actions.add(() -> dial(activity, phone));
        }
        labels.add("Call " + EMERGENCY_HOTLINE + "  •  National Emergency Hotline");
        actions.add(() -> dial(activity, EMERGENCY_HOTLINE));
        labels.add(hasContact ? "Edit emergency contact" : "Add an emergency contact");
        actions.add(() -> activity.startActivity(new Intent(activity, EditProfileActivity.class)));

        new AlertDialog.Builder(activity)
                .setTitle("Emergency call")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Opens the phone dialer with the number filled in; the user presses call. */
    public static void dial(Activity activity, String phone) {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null));
        try {
            // startActivity directly: resolveActivity() returns null on Android 11+
            // unless the app declares a <queries> entry.
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No phone app available. Call " + phone + " from another phone.",
                    Toast.LENGTH_LONG).show();
        }
    }
}

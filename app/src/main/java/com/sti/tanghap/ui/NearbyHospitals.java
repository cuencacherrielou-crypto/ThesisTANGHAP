package com.sti.tanghap.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** Opens a "hospitals near me" map search without crashing when Google Maps is missing. */
final class NearbyHospitals {
    private NearbyHospitals() {}

    static void open(Activity activity) {
        Uri uri = Uri.parse("geo:0,0?q=hospitals+near+me");
        Intent mapsIntent = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps");
        try {
            activity.startActivity(mapsIntent);
        } catch (ActivityNotFoundException noGoogleMaps) {
            try {
                // Any other installed map app
                activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException noMapApp) {
                Toast.makeText(activity, "No map app is installed.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}

package com.sti.tanghap.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * Opens a "hospitals near me" search around the phone's current location: Google Maps first,
 * then any other map app, then the browser, so it works on every phone.
 */
final class NearbyHospitals {
    private NearbyHospitals() {}

    static void open(Activity activity) {
        // geo:0,0 with a query searches around the user's current location
        Uri uri = Uri.parse("geo:0,0?q=" + Uri.encode("hospital"));
        Intent mapsIntent = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps");
        try {
            activity.startActivity(mapsIntent);
            return;
        } catch (ActivityNotFoundException noGoogleMaps) {
            // fall through
        }
        try {
            // Any other installed map app
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return;
        } catch (ActivityNotFoundException noMapApp) {
            // fall through
        }
        try {
            // No map app: Google Maps in the browser asks for the location itself
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode("hospital near me"))));
        } catch (ActivityNotFoundException noBrowser) {
            Toast.makeText(activity, "No map app or browser is installed. In an emergency, call 911.",
                    Toast.LENGTH_LONG).show();
        }
    }
}

package com.sti.tanghap.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppSettings;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.sync.SyncScheduler;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQ_NOTIFICATIONS = 4102;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = AppSettings.prefs(this);

        TextView tv = findViewById(R.id.tvProfile);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            tv.setText("Logged in as: " + user.getEmail());
        }
        showVersion();

        setupDarkModeSwitch();
        setupNotificationSwitch(R.id.switchAlertNotifications, AppSettings.KEY_ALERT_NOTIFICATIONS);
        setupNotificationSwitch(R.id.switchDoseReminders, AppSettings.KEY_DOSE_REMINDERS);

        findViewById(R.id.btnHealthInfo).setOnClickListener(v -> {
            startActivity(new Intent(this, HealthInfoActivity.class));
        });

        findViewById(R.id.btnEditProfile).setOnClickListener(v -> {
            startActivity(new Intent(this, EditProfileActivity.class));
        });

        findViewById(R.id.btnChangePassword).setOnClickListener(v -> {
            startActivity(new Intent(this, ChangePasswordActivity.class));
        });

        findViewById(R.id.btnExportReport).setOnClickListener(v -> {
            Intent intent = new Intent(this, DoctorExportActivity.class);
            intent.putExtra(DoctorExportActivity.EXTRA_EXPORT_ALL, true);
            startActivity(intent);
        });

        findViewById(R.id.btnForgetInhaler).setOnClickListener(v -> confirmForgetInhaler());

        findViewById(R.id.btnAbout).setOnClickListener(v -> {
            startActivity(new Intent(this, AboutActivity.class));
        });

        findViewById(R.id.btnRunSync).setOnClickListener(v -> {
            SyncScheduler.enqueueOneTimeSync(this);
            Toast.makeText(this, R.string.sync_started, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnLogout).setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            HealthPrefs.clearUserData(this);
            com.facebook.login.LoginManager.getInstance().logOut();
            Intent login = new Intent(this, LoginActivity.class);
            login.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(login);
            finishAffinity();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePairedInhaler();
    }

    private void setupDarkModeSwitch() {
        SwitchMaterial switchDarkMode = findViewById(R.id.switchDarkMode);
        boolean isDarkMode = prefs.getBoolean(AppSettings.KEY_DARK_MODE, false);
        switchDarkMode.setChecked(isDarkMode);

        switchDarkMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(AppSettings.KEY_DARK_MODE, isChecked).apply();
            AppCompatDelegate.setDefaultNightMode(isChecked ?
                    AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
    }

    private void setupNotificationSwitch(int switchId, String key) {
        SwitchMaterial sw = findViewById(switchId);
        sw.setChecked(prefs.getBoolean(key, true));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
            if (isChecked) ensureSystemNotificationsAllowed();
        });
    }

    /** The in-app switch alone is not enough when Android itself blocks TANGHAP notifications. */
    private void ensureSystemNotificationsAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, R.string.notifications_blocked, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.notifications_blocked, Toast.LENGTH_LONG).show();
        }
    }

    private void updatePairedInhaler() {
        String address = prefs.getString(LiveSessionActivity.PREF_LAST_DEVICE, null);
        boolean paired = address != null && !address.isEmpty();
        TextView tvPaired = findViewById(R.id.tvPairedInhaler);
        tvPaired.setText(paired ? address : getString(R.string.forget_inhaler_none));
        findViewById(R.id.btnForgetInhaler).setEnabled(paired);
    }

    private void confirmForgetInhaler() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forget_inhaler)
                .setMessage(R.string.forget_inhaler_confirm)
                .setPositiveButton(R.string.action_forget, (d, w) -> {
                    prefs.edit().remove(LiveSessionActivity.PREF_LAST_DEVICE).apply();
                    Toast.makeText(this, R.string.inhaler_forgotten, Toast.LENGTH_SHORT).show();
                    updatePairedInhaler();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tvVersion)).setText(getString(R.string.version_format, version));
        } catch (PackageManager.NameNotFoundException ignored) {
            // Own package always exists; leave the line empty otherwise
        }
    }
}

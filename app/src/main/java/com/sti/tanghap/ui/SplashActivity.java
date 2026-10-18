package com.sti.tanghap.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import com.sti.tanghap.R;
import com.sti.tanghap.data.remote.FirebaseAuthRepo;

public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Give the system a moment to breathe and show the splash screen
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            FirebaseAuthRepo authRepo = new FirebaseAuthRepo();
            if (authRepo.isLoggedIn()) {
                startActivity(new Intent(SplashActivity.this, DashboardActivity.class));
            } else {
                startActivity(new Intent(SplashActivity.this, LoginActivity.class));
            }
            finish();
        }, 1500); // 1.5 seconds delay
    }
}
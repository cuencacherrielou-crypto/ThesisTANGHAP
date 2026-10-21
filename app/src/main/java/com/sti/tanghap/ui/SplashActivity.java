package com.sti.tanghap.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.sti.tanghap.R;

public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Give the system a moment to breathe and show the splash screen
        new Handler(Looper.getMainLooper()).postDelayed(this::route, 1500); // 1.5 seconds delay
    }

    private void route() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            open(LoginActivity.class);
            return;
        }

        // Signed-in users who never finished onboarding go back to it
        FirebaseFirestore.getInstance().collection("users").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    boolean needsOnboarding = doc.exists()
                            && Boolean.FALSE.equals(doc.getBoolean("onboardingCompleted"));
                    open(needsOnboarding ? OnboardingActivity.class : DashboardActivity.class);
                })
                // Offline / error: don't block the user from their data
                .addOnFailureListener(e -> open(DashboardActivity.class));
    }

    private void open(Class<?> target) {
        if (isFinishing() || isDestroyed()) return;
        startActivity(new Intent(this, target));
        finish();
    }
}

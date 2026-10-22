package com.sti.tanghap.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.sti.tanghap.R;

public class ChangePasswordActivity extends AppCompatActivity {

    private TextInputEditText etCurrent, etNew, etConfirm;
    private View progress;
    private FirebaseUser user;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);

        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }

        etCurrent = findViewById(R.id.etCurrentPassword);
        etNew = findViewById(R.id.etNewPassword);
        etConfirm = findViewById(R.id.etConfirmPassword);
        progress = findViewById(R.id.progress);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnUpdatePassword).setOnClickListener(v -> updatePassword());
    }

    /** True when the account can sign in with an email and password (not Google only). */
    private boolean hasPasswordSignIn() {
        for (com.google.firebase.auth.UserInfo info : user.getProviderData()) {
            if (EmailAuthProvider.PROVIDER_ID.equals(info.getProviderId())) return true;
        }
        return false;
    }

    private void updatePassword() {
        if (!hasPasswordSignIn() || user.getEmail() == null) {
            Toast.makeText(this, "This account signs in with Google, so it has no TANGHAP password. "
                    + "Manage your password in your Google account.", Toast.LENGTH_LONG).show();
            return;
        }
        String currentPass = text(etCurrent);
        String newPass = text(etNew);
        String confirmPass = text(etConfirm);

        if (currentPass.isEmpty() || newPass.isEmpty() || confirmPass.isEmpty()) {
            Toast.makeText(this, "All fields are required.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!newPass.equals(confirmPass)) {
            Toast.makeText(this, "Passwords do not match.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPass.length() < 6) {
            Toast.makeText(this, "Password must be at least 6 characters.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);

        // Re-authenticate user before changing password (required by Firebase)
        AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), currentPass);

        user.reauthenticate(credential).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                user.updatePassword(newPass).addOnCompleteListener(updateTask -> {
                    setLoading(false);
                    if (updateTask.isSuccessful()) {
                        Toast.makeText(this, "Password updated successfully!", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        String reason = updateTask.getException() != null ? updateTask.getException().getMessage() : "unknown error";
                        Toast.makeText(this, "Update failed: " + reason, Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                setLoading(false);
                Toast.makeText(this, "Current password incorrect.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnUpdatePassword).setEnabled(!loading);
    }
}
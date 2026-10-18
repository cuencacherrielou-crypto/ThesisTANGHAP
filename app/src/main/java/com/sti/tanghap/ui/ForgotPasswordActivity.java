package com.sti.tanghap.ui;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textfield.TextInputEditText;
import com.sti.tanghap.R;
import com.google.firebase.auth.FirebaseAuth;
public class ForgotPasswordActivity extends AppCompatActivity {
    private TextInputEditText emailEt;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot);
        emailEt = findViewById(R.id.etEmail);
        findViewById(R.id.btnSend).setOnClickListener(v -> send());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }
    private void send() {
        String email = emailEt.getText() == null ? "" : emailEt.getText().toString().trim();
        if (email.isEmpty()) {
            Toast.makeText(this, "Enter email.", Toast.LENGTH_SHORT).show();
            return;
        }
        setLoading(true);
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                .addOnSuccessListener(v -> {
                    setLoading(false);
                    Toast.makeText(this, "Password reset email sent.", Toast.LENGTH_LONG).show();
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }
    private void setLoading(boolean loading) {
        findViewById(R.id.progress).setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnSend).setEnabled(!loading);
    }
}
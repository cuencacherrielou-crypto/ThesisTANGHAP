package com.sti.tanghap.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.FirebaseFirestore;
import com.sti.tanghap.R;
import com.sti.tanghap.domain.AgeCalculator;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class RegisterActivity extends AppCompatActivity {
    private static final String TAG = "RegisterActivity";
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private GoogleSignInClient googleSignInClient;
    private ActivityResultLauncher<Intent> googleSignInLauncher;

    private TextInputEditText nameEt, ageEt, bdayEt, userEt, emailEt, passEt, confirmPassEt;
    private CheckBox cbTerms;
    private TextView tvTermsLink;
    private MaterialCardView termsContainer;
    private View progress;

    private static final long SIGN_IN_TIMEOUT_MS = 30_000;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = () -> {
        if (progress != null && progress.getVisibility() == View.VISIBLE) {
            setLoading(false);
            Toast.makeText(this, "Registration request timed out. Please try again.", Toast.LENGTH_LONG).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        nameEt = findViewById(R.id.etFullName);
        ageEt = findViewById(R.id.etAge);
        bdayEt = findViewById(R.id.etBirthday);
        userEt = findViewById(R.id.etUsername);
        emailEt = findViewById(R.id.etEmail);
        passEt = findViewById(R.id.etPassword);
        confirmPassEt = findViewById(R.id.etConfirmPassword);
        cbTerms = findViewById(R.id.cbTerms);
        tvTermsLink = findViewById(R.id.tvTermsLink);
        termsContainer = findViewById(R.id.termsContainer);
        progress = findViewById(R.id.progress);

        setupGoogleSignIn();
        setupTermsLink();

        findViewById(R.id.toolbar).setOnClickListener(v -> finish());
        bdayEt.setOnClickListener(v -> showDatePicker());

        findViewById(R.id.btnRegister).setOnClickListener(v -> register());
        findViewById(R.id.btnGoogleRegister).setOnClickListener(v -> signInWithGoogle());
        findViewById(R.id.btnBackLogin).setOnClickListener(v -> finish());

        cbTerms.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) termsContainer.setStrokeWidth(0);
        });
    }

    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        googleSignInLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    try {
                        GoogleSignInAccount account = task.getResult(ApiException.class);
                        if (account != null && account.getIdToken() != null) {
                            firebaseAuthWithSocial(GoogleAuthProvider.getCredential(account.getIdToken(), null));
                        } else {
                            setLoading(false);
                            Toast.makeText(this, "Failed to retrieve Google account.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (ApiException e) {
                        setLoading(false);
                        Log.e(TAG, "Google Sign-In ApiException: code=" + e.getStatusCode(), e);
                        Toast.makeText(this, "Google Registration Error (code " + e.getStatusCode() + ")", Toast.LENGTH_LONG).show();
                    }
                } else {
                    setLoading(false);
                }
            }
        );
    }

    private void setupTermsLink() {
        String text = getString(R.string.terms_agreement_text);
        SpannableString ss = new SpannableString(text);
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                PrivacyPolicy.showDialog(RegisterActivity.this);
            }
            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(ContextCompat.getColor(RegisterActivity.this, R.color.accent_teal));
                ds.setUnderlineText(true);
            }
        };
        ss.setSpan(clickableSpan, 15, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvTermsLink.setText(ss);
    }

    private void showDatePicker() {
        MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select Birthday")
                .setTheme(R.style.Theme_Tanghap_DatePickerDialog)
                .build();

        datePicker.addOnPositiveButtonClickListener(selection -> {
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            calendar.setTimeInMillis(selection);
            SimpleDateFormat format = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC")); // the picker returns UTC midnight
            bdayEt.setText(format.format(calendar.getTime()));

            // Same age rule as the rest of the app (birthday not reached yet this year = one less)
            int age = AgeCalculator.fromBirthday(bdayEt.getText() != null ? bdayEt.getText().toString() : null,
                    Calendar.getInstance());
            if (age == AgeCalculator.UNKNOWN) {
                bdayEt.setText("");
                ageEt.setText("");
                Toast.makeText(this, "Please choose a birthday that is not in the future.", Toast.LENGTH_SHORT).show();
            } else {
                ageEt.setText(String.valueOf(age));
            }
        });

        datePicker.show(getSupportFragmentManager(), "DATE_PICKER");
    }

    private boolean checkTerms() {
        if (!cbTerms.isChecked()) {
            termsContainer.setStrokeWidth(3);
            Toast.makeText(this, "Please agree to the terms to proceed.", Toast.LENGTH_SHORT).show();
            return false;
        }
        termsContainer.setStrokeWidth(0);
        return true;
    }

    private void register() {
        if (!checkTerms()) return;

        String fullName = safe(nameEt);
        String age = safe(ageEt);
        String birthday = safe(bdayEt);
        String username = safe(userEt);
        String email = safe(emailEt);
        String pass = safe(passEt);
        String confirmPass = safe(confirmPassEt);

        if (fullName.isEmpty() || email.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Please fill in all required fields.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!pass.equals(confirmPass)) {
            Toast.makeText(this, "Passwords do not match.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        auth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(authResult -> saveUserData(authResult.getUser().getUid(), fullName, age, birthday, username, email))
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Toast.makeText(this, "Registration failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void signInWithGoogle() {
        if (!checkTerms()) return;

        GoogleApiAvailability availability = GoogleApiAvailability.getInstance();
        int resultCode = availability.isGooglePlayServicesAvailable(this);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (availability.isUserResolvableError(resultCode)) {
                availability.getErrorDialog(this, resultCode, 9000).show();
            } else {
                Toast.makeText(this, "Google Play Services unavailable.", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        setLoading(true);
        googleSignInClient.signOut().addOnCompleteListener(this, task -> {
            Intent signInIntent = googleSignInClient.getSignInIntent();
            googleSignInLauncher.launch(signInIntent);
        });
    }

    private void firebaseAuthWithSocial(AuthCredential credential) {
        auth.signInWithCredential(credential).addOnCompleteListener(this, task -> {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            if (task.isSuccessful() && auth.getCurrentUser() != null) {
                checkAndCreateUserProfile(auth.getCurrentUser());
            } else {
                setLoading(false);
                String msg = task.getException() != null ? task.getException().getMessage() : "Authentication Failed.";
                Toast.makeText(this, "Auth Error: " + msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void checkAndCreateUserProfile(FirebaseUser user) {
        db.collection("users").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        String displayName = user.getDisplayName() != null ? user.getDisplayName() : "User";
                        String email = user.getEmail() != null ? user.getEmail() : "";
                        String username = !email.isEmpty() ? email.split("@")[0] : "user_" + user.getUid().substring(0, 5);
                        saveUserData(user.getUid(), displayName, "N/A", "N/A", username, email);
                    } else {
                        Boolean completed = doc.getBoolean("onboardingCompleted");
                        setLoading(false);
                        Intent intent = new Intent(RegisterActivity.this, Boolean.TRUE.equals(completed) ? DashboardActivity.class : OnboardingActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    }
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Toast.makeText(this, "Error checking profile: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void saveUserData(String uid, String name, String age, String bday, String username, String email) {
        Map<String, Object> user = new HashMap<>();
        user.put("fullName", name);
        user.put("age", age);
        user.put("birthday", bday);
        user.put("username", username);
        user.put("email", email);
        user.put("onboardingCompleted", false);

        db.collection("users").document(uid).set(user)
                .addOnSuccessListener(aVoid -> {
                    setLoading(false);
                    Intent intent = new Intent(RegisterActivity.this, OnboardingActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Toast.makeText(this, "Error saving profile.", Toast.LENGTH_SHORT).show();
                });
    }

    private void setLoading(boolean loading) {
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        // Stop the spinner with a message if the sign-in request never answers
        timeoutHandler.removeCallbacks(timeoutRunnable);
        if (loading) timeoutHandler.postDelayed(timeoutRunnable, SIGN_IN_TIMEOUT_MS);
        findViewById(R.id.btnRegister).setEnabled(!loading);
        findViewById(R.id.btnGoogleRegister).setEnabled(!loading);
    }

    private String safe(TextInputEditText et) {
        return et == null || et.getText() == null ? "" : et.getText().toString().trim();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timeoutHandler.removeCallbacks(timeoutRunnable);
    }
}

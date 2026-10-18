package com.sti.tanghap.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.FirebaseFirestore;
import com.sti.tanghap.R;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity";
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private CallbackManager callbackManager;
    private GoogleSignInClient googleSignInClient;
    private ActivityResultLauncher<Intent> googleSignInLauncher;
    
    private TextInputEditText emailEt, passEt;
    private TextInputLayout tilEmail, tilPassword;
    private CheckBox cbTerms;
    private TextView tvTermsLink;
    private MaterialCardView termsContainer;
    private View progress;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = () -> {
        if (progress != null && progress.getVisibility() == View.VISIBLE) {
            setLoading(false);
            Toast.makeText(this, "Sign-in request timed out. Please try again.", Toast.LENGTH_LONG).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        
        emailEt = findViewById(R.id.etEmail);
        passEt = findViewById(R.id.etPassword);
        tilEmail = findViewById(R.id.tilEmail);
        tilPassword = findViewById(R.id.tilPassword);
        cbTerms = findViewById(R.id.cbTerms);
        tvTermsLink = findViewById(R.id.tvTermsLink);
        termsContainer = findViewById(R.id.termsContainer);
        progress = findViewById(R.id.progress);

        setupGoogleSignIn();
        setupTermsLink();
        setupFacebookSignIn();

        findViewById(R.id.btnLogin).setOnClickListener(v -> login());
        findViewById(R.id.btnGoRegister).setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        findViewById(R.id.btnForgot).setOnClickListener(v ->
                startActivity(new Intent(this, ForgotPasswordActivity.class)));
        
        findViewById(R.id.btnGoogle).setOnClickListener(v -> signInWithGoogle());
        findViewById(R.id.btnFacebook).setOnClickListener(v -> signInWithFacebook());

        cbTerms.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                termsContainer.setStrokeWidth(0);
            }
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
                        Toast.makeText(this, "Google Sign-In Error (code " + e.getStatusCode() + ")", Toast.LENGTH_LONG).show();
                    }
                } else {
                    setLoading(false);
                }
            }
        );
    }

    private void setupTermsLink() {
        String text = "I agree to the terms and privacy policy";
        SpannableString ss = new SpannableString(text);
        
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                showTermsDialog();
            }

            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(ContextCompat.getColor(LoginActivity.this, R.color.accent_teal));
                ds.setUnderlineText(true);
            }
        };

        ss.setSpan(clickableSpan, 15, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        
        tvTermsLink.setText(ss);
        tvTermsLink.setMovementMethod(LinkMovementMethod.getInstance());
        tvTermsLink.setHighlightColor(Color.TRANSPARENT);
    }

    private void showTermsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Terms and Privacy Policy")
                .setMessage("TANGHAP respects your privacy. All sensor data is stored securely and used only for providing trend analytics. We do not provide medical diagnosis. By using this app, you agree to our data processing practices.")
                .setPositiveButton("I Understand", null)
                .show();
    }

    private void setupFacebookSignIn() {
        callbackManager = CallbackManager.Factory.create();
        LoginManager.getInstance().registerCallback(callbackManager, new FacebookCallback<LoginResult>() {
            @Override
            public void onSuccess(LoginResult loginResult) { handleFacebookToken(loginResult.getAccessToken()); }
            @Override
            public void onCancel() { setLoading(false); }
            @Override
            public void onError(@NonNull FacebookException error) {
                setLoading(false);
                Toast.makeText(LoginActivity.this, "Facebook Error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
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

    private void signInWithFacebook() {
        if (!checkTerms()) return;
        setLoading(true);
        LoginManager.getInstance().logInWithReadPermissions(this, Arrays.asList("email", "public_profile"));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (callbackManager != null) callbackManager.onActivityResult(requestCode, resultCode, data);
    }

    private void firebaseAuthWithSocial(AuthCredential credential) {
        auth.signInWithCredential(credential).addOnCompleteListener(this, task -> {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            if (task.isSuccessful()) {
                FirebaseUser user = auth.getCurrentUser();
                if (user != null) {
                    checkAndCreateUserProfile(user);
                } else {
                    setLoading(false);
                    Toast.makeText(this, "User profile not found.", Toast.LENGTH_SHORT).show();
                }
            } else {
                setLoading(false);
                Log.e(TAG, "Firebase Social Auth Failed", task.getException());
                String msg = task.getException() != null ? task.getException().getMessage() : "Authentication Failed.";
                Toast.makeText(this, "Auth Error: " + msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void checkAndCreateUserProfile(FirebaseUser user) {
        db.collection("users").document(user.getUid()).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (!documentSnapshot.exists()) {
                        Map<String, Object> userData = new HashMap<>();
                        String displayName = user.getDisplayName() != null ? user.getDisplayName() : "User";
                        String email = user.getEmail() != null ? user.getEmail() : "";
                        String username = !email.isEmpty() ? email.split("@")[0] : "user_" + user.getUid().substring(0, 5);

                        userData.put("fullName", displayName);
                        userData.put("email", email);
                        userData.put("username", username);
                        userData.put("age", "N/A");
                        userData.put("birthday", "N/A");
                        userData.put("onboardingCompleted", false);

                        db.collection("users").document(user.getUid()).set(userData)
                                .addOnCompleteListener(t -> finishLoginFlow(false));
                    } else {
                        Boolean completed = documentSnapshot.getBoolean("onboardingCompleted");
                        finishLoginFlow(Boolean.TRUE.equals(completed));
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Firestore Profile Error", e);
                    finishLoginFlow(false);
                });
    }

    private void finishLoginFlow(boolean onboardingCompleted) {
        setLoading(false);
        Intent intent;
        if (onboardingCompleted) {
            intent = new Intent(LoginActivity.this, DashboardActivity.class);
        } else {
            intent = new Intent(LoginActivity.this, OnboardingActivity.class);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void handleFacebookToken(AccessToken token) {
        setLoading(true);
        firebaseAuthWithSocial(FacebookAuthProvider.getCredential(token.getToken()));
    }

    private void login() {
        if (!checkTerms()) return;

        String email = safe(emailEt);
        String pass = safe(passEt);

        tilEmail.setError(null);
        tilPassword.setError(null);

        if (email.isEmpty()) {
            tilEmail.setError("Email is required");
            return;
        }
        if (pass.isEmpty()) {
            tilPassword.setError("Password is required");
            return;
        }

        setLoading(true);
        auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> {
                    FirebaseUser user = auth.getCurrentUser();
                    if (user != null) checkAndCreateUserProfile(user);
                    else setLoading(false);
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    if (e instanceof FirebaseAuthInvalidUserException) {
                        tilEmail.setError("Incorrect email address");
                    } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
                        tilPassword.setError("Incorrect password");
                    } else {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setLoading(boolean loading) {
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnLogin).setEnabled(!loading);
        findViewById(R.id.btnGoogle).setEnabled(!loading);
        findViewById(R.id.btnFacebook).setEnabled(!loading);
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

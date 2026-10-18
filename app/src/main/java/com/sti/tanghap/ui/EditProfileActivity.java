package com.sti.tanghap.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.sti.tanghap.R;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EditProfileActivity extends AppCompatActivity {

    private TextInputEditText etFullName, etUsername, etAge, etBirthday;
    private TextInputEditText etInhalerType, etInhalerUsage;
    private MaterialCheckBox cbAsthma, cbCOPD, cbBronchitis, cbPneumonia, cbAllergies, cbSmoke;
    private View progress;
    private FirebaseFirestore db;
    private FirebaseUser user;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_profile);

        db = FirebaseFirestore.getInstance();
        user = FirebaseAuth.getInstance().getCurrentUser();

        etFullName = findViewById(R.id.etFullName);
        etUsername = findViewById(R.id.etUsername);
        etAge = findViewById(R.id.etAge);
        etBirthday = findViewById(R.id.etBirthday);

        // Health Profile Views
        cbAsthma = findViewById(R.id.cbAsthma);
        cbCOPD = findViewById(R.id.cbCOPD);
        cbBronchitis = findViewById(R.id.cbBronchitis);
        cbPneumonia = findViewById(R.id.cbPneumonia);
        cbAllergies = findViewById(R.id.cbAllergies);
        cbSmoke = findViewById(R.id.cbSmoke);

        etInhalerType = findViewById(R.id.etInhalerType);
        etInhalerUsage = findViewById(R.id.etInhalerUsage);

        progress = findViewById(R.id.progress);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveProfile());

        loadUserData();
    }

    @SuppressWarnings("unchecked")
    private void loadUserData() {
        if (user == null) return;

        setLoading(true);
        db.collection("users").document(user.getUid()).get()
                .addOnSuccessListener(documentSnapshot -> {
                    setLoading(false);
                    if (documentSnapshot.exists()) {
                        etFullName.setText(documentSnapshot.getString("fullName"));
                        etUsername.setText(documentSnapshot.getString("username"));
                        etAge.setText(String.valueOf(documentSnapshot.get("age")));
                        etBirthday.setText(documentSnapshot.getString("birthday"));

                        Map<String, Object> healthMap = (Map<String, Object>) documentSnapshot.get("healthProfile");
                        if (healthMap != null) {
                            List<String> conditions = (List<String>) healthMap.get("diagnosedConditions");
                            if (conditions != null) {
                                cbAsthma.setChecked(conditions.contains("Asthma"));
                                cbCOPD.setChecked(conditions.contains("COPD"));
                                cbBronchitis.setChecked(conditions.contains("Bronchitis"));
                                cbPneumonia.setChecked(conditions.contains("Pneumonia"));
                                cbAllergies.setChecked(conditions.contains("Allergies") || conditions.contains("Mild Congestion"));
                            }
                            etInhalerType.setText((String) healthMap.get("currentInhalerType"));
                            etInhalerUsage.setText((String) healthMap.get("inhalerUsageFreqPerDay"));
                            Boolean smoke = (Boolean) healthMap.get("isSmokerOrVaper");
                            if (smoke != null) cbSmoke.setChecked(smoke);
                        } else {
                            SharedPreferences pref = getSharedPreferences("TanghapUserPreferences", MODE_PRIVATE);
                            cbAsthma.setChecked(pref.getBoolean("has_asthma", false));
                            cbCOPD.setChecked(pref.getBoolean("has_copd", false));
                            cbBronchitis.setChecked(pref.getBoolean("has_bronchitis", false));
                            cbAllergies.setChecked(pref.getBoolean("has_congestion", false));
                            etInhalerUsage.setText(pref.getString("inhaler_usage", ""));
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Toast.makeText(this, "Failed to load profile.", Toast.LENGTH_SHORT).show();
                });
    }

    private void saveProfile() {
        String name = safe(etFullName);
        String username = safe(etUsername);
        String age = safe(etAge);
        String birthday = safe(etBirthday);

        if (name.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, "Name and Username are required.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean hasAsthma = cbAsthma.isChecked();
        boolean hasCOPD = cbCOPD.isChecked();
        boolean hasBronchitis = cbBronchitis.isChecked();
        boolean hasPneumonia = cbPneumonia.isChecked();
        boolean hasAllergies = cbAllergies.isChecked();
        boolean isSmoke = cbSmoke.isChecked();
        String inhalerType = safe(etInhalerType);
        String inhalerUsage = safe(etInhalerUsage);

        setLoading(true);

        // 1. Immediately sync to SharedPreferences for AssessmentEngine in LiveSessionActivity
        SharedPreferences pref = getSharedPreferences("TanghapUserPreferences", MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.putBoolean("has_asthma", hasAsthma);
        editor.putBoolean("has_copd", hasCOPD);
        editor.putBoolean("has_bronchitis", hasBronchitis);
        editor.putBoolean("has_congestion", hasAllergies);
        editor.putString("inhaler_usage", inhalerUsage);
        editor.apply();

        // 2. Update Firestore document
        List<String> conditions = new ArrayList<>();
        if (hasAsthma) conditions.add("Asthma");
        if (hasCOPD) conditions.add("COPD");
        if (hasBronchitis) conditions.add("Bronchitis");
        if (hasPneumonia) conditions.add("Pneumonia");
        if (hasAllergies) conditions.add("Allergies");

        Map<String, Object> healthMap = new HashMap<>();
        healthMap.put("diagnosedConditions", conditions);
        healthMap.put("currentInhalerType", inhalerType);
        healthMap.put("inhalerUsageFreqPerDay", inhalerUsage);
        healthMap.put("isSmokerOrVaper", isSmoke);

        Map<String, Object> updates = new HashMap<>();
        updates.put("fullName", name);
        updates.put("username", username);
        updates.put("age", age);
        updates.put("birthday", birthday);
        updates.put("healthProfile", healthMap);

        db.collection("users").document(user.getUid()).update(updates)
                .addOnSuccessListener(aVoid -> {
                    setLoading(false);
                    Toast.makeText(this, "Personal Health Profile updated successfully!", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Toast.makeText(this, "Update failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnSave).setEnabled(!loading);
    }

    private String safe(TextInputEditText et) {
        return et == null || et.getText() == null ? "" : et.getText().toString().trim();
    }
}

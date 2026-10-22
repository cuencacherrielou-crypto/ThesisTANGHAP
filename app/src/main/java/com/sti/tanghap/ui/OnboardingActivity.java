package com.sti.tanghap.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.sti.tanghap.R;
import com.sti.tanghap.data.HealthPrefs;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private MaterialButton btnNext;
    private View progress;
    private View[] stepIndicators;

    private final Map<String, Object> onboardingData = new HashMap<>();
    private FirebaseAuth auth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        viewPager = findViewById(R.id.viewPager);
        btnNext = findViewById(R.id.btnNext);
        progress = findViewById(R.id.saveProgress);

        stepIndicators = new View[]{
                findViewById(R.id.step1),
                findViewById(R.id.step2),
                findViewById(R.id.step3),
                findViewById(R.id.step4)
        };

        OnboardingAdapter adapter = new OnboardingAdapter();
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(3);
        viewPager.setUserInputEnabled(true);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateIndicators(position);
                btnNext.setText(position == 3 ? R.string.finish : R.string.next);
            }
        });

        btnNext.setOnClickListener(v -> {
            int current = viewPager.getCurrentItem();
            if (current < 3) {
                viewPager.setCurrentItem(current + 1);
            } else {
                saveData();
            }
        });
    }

    private void updateIndicators(int position) {
        for (int i = 0; i < stepIndicators.length; i++) {
            stepIndicators[i].setBackgroundColor(ContextCompat.getColor(this,
                    i <= position ? R.color.accent_teal : R.color.text_low_emphasis));
        }
    }

    private void saveData() {
        if (auth.getCurrentUser() == null) return;

        setLoading(true);
        collectData();
        saveToPreferences();

        db.collection("users").document(auth.getCurrentUser().getUid())
                .update("healthProfile", onboardingData, "onboardingCompleted", true)
                .addOnSuccessListener(aVoid -> {
                    setLoading(false);
                    startActivity(new Intent(OnboardingActivity.this, DashboardActivity.class));
                    finish();
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Toast.makeText(this, "Failed to save data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void saveToPreferences() {
        HealthPrefs.saveFromHealthMap(this, onboardingData);
    }

    private void collectData() {
        for (int i = 0; i < 4; i++) {
            RecyclerView.ViewHolder holder = ((RecyclerView) viewPager.getChildAt(0)).findViewHolderForAdapterPosition(i);
            if (holder instanceof StepViewHolder) {
                ((StepViewHolder) holder).collectData(onboardingData);
            }
        }
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnNext.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        btnNext.setEnabled(!loading);
    }

    class OnboardingAdapter extends RecyclerView.Adapter<StepViewHolder> {
        @NonNull
        @Override
        public StepViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layoutId = 0;
            switch (viewType) {
                case 0: layoutId = R.layout.item_onboarding_step1; break;
                case 1: layoutId = R.layout.item_onboarding_step2; break;
                case 2: layoutId = R.layout.item_onboarding_step3; break;
                case 3: layoutId = R.layout.item_onboarding_step4; break;
            }
            View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
            return new StepViewHolder(view, viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull StepViewHolder holder, int position) {
            holder.bind();
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @Override
        public int getItemCount() {
            return 4;
        }
    }

    class StepViewHolder extends RecyclerView.ViewHolder {
        int stepType;

        StepViewHolder(@NonNull View itemView, int stepType) {
            super(itemView);
            this.stepType = stepType;
        }

        void bind() {
            if (stepType == 1) {
                TextInputEditText etDate = itemView.findViewById(R.id.etDiagnosisDate);
                if (etDate != null) {
                    etDate.setOnClickListener(v -> showDatePicker(etDate));
                }
            }
        }

        private void showDatePicker(TextInputEditText et) {
            MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText("Select Date")
                    .setTheme(R.style.Theme_Tanghap_DatePickerDialog)
                    .build();

            datePicker.addOnPositiveButtonClickListener(selection -> {
                Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                calendar.setTimeInMillis(selection);
                SimpleDateFormat format = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
                et.setText(format.format(calendar.getTime()));
            });

            datePicker.show(getSupportFragmentManager(), "DATE_PICKER");
        }

        void collectData(Map<String, Object> data) {
            switch (stepType) {
                case 0:
                    // Unanswered questions are left out instead of saved as a default answer
                    if (isChecked(R.id.rbMale)) data.put("biologicalSex", "Male");
                    else if (isChecked(R.id.rbFemale)) data.put("biologicalSex", "Female");
                    data.put("heightCm", getText(R.id.etHeight));
                    data.put("weightKg", getText(R.id.etWeight));
                    putYesNo(data, "isSmokerOrVaper", R.id.rbSmokeYes, R.id.rbSmokeNo);
                    putYesNo(data, "pollutionExposure", R.id.rbPollutionYes, R.id.rbPollutionNo);
                    break;
                case 1:
                    List<String> conditions = new ArrayList<>();
                    if (isChecked(R.id.cbAsthma)) conditions.add("Asthma");
                    if (isChecked(R.id.cbCOPD)) conditions.add("COPD");
                    if (isChecked(R.id.cbBronchitis)) conditions.add("Bronchitis");
                    if (isChecked(R.id.cbPneumonia)) conditions.add("Pneumonia");
                    if (isChecked(R.id.cbAllergies)) conditions.add("Allergies");
                    if (isChecked(R.id.cbNone)) conditions.add("None");
                    String others = getText(R.id.etOtherCondition);
                    if (!others.isEmpty()) conditions.add("Other: " + others);
                    data.put("diagnosedConditions", conditions);
                    data.put("diagnosisDate", getText(R.id.etDiagnosisDate));
                    putYesNo(data, "previouslyHospitalized", R.id.rbHospYes, R.id.rbHospNo);
                    putYesNo(data, "familyRespiratoryHistory", R.id.rbFamilyYes, R.id.rbFamilyNo);
                    break;
                case 2:
                    RadioGroup rgCough = itemView.findViewById(R.id.rgCoughFreq);
                    int coughId = rgCough.getCheckedRadioButtonId();
                    String freq = null;
                    if (coughId == R.id.rbCoughDaily) freq = "Daily";
                    else if (coughId == R.id.rbCoughWeekly) freq = "Weekly";
                    else if (coughId == R.id.rbCoughMonthly) freq = "Monthly";
                    else if (coughId == R.id.rbCoughRarely) freq = "Rarely";
                    if (freq != null) data.put("coughFrequency", freq);

                    List<String> coughTypes = new ArrayList<>();
                    if (isChecked(R.id.cbDryCough)) coughTypes.add("Dry");
                    if (isChecked(R.id.cbWetCough)) coughTypes.add("Wet");
                    if (isChecked(R.id.cbPersistentCough)) coughTypes.add("Persistent");
                    if (isChecked(R.id.cbWorseningCough)) coughTypes.add("Worsening");
                    data.put("coughTypes", coughTypes);
                    putYesNo(data, "hasShortnessOfBreath", R.id.rbShortnessYes, R.id.rbShortnessNo);
                    putYesNo(data, "hasWheezing", R.id.rbWheezingYes, R.id.rbWheezingNo);

                    List<String> triggers = new ArrayList<>();
                    if (isChecked(R.id.cbTriggerDust)) triggers.add("Dust");
                    if (isChecked(R.id.cbTriggerCold)) triggers.add("Cold weather");
                    if (isChecked(R.id.cbTriggerSmoke)) triggers.add("Smoke");
                    if (isChecked(R.id.cbTriggerExercise)) triggers.add("Exercise");
                    if (isChecked(R.id.cbTriggerStress)) triggers.add("Stress");
                    if (isChecked(R.id.cbTriggerOdors)) triggers.add("Strong odors");
                    if (isChecked(R.id.cbTriggerPet)) triggers.add("Pet fur");
                    if (isChecked(R.id.cbTriggerPollen)) triggers.add("Pollen");
                    data.put("respiratoryTriggers", triggers);
                    break;
                case 3:
                    data.put("currentInhalerType", getText(R.id.etInhalerType));
                    data.put("inhalerUsageFreqPerDay", getText(R.id.etInhalerUsageFreq));
                    putYesNo(data, "forgetsInhalerUsage", R.id.rbForgetYes, R.id.rbForgetNo);
                    putYesNo(data, "feelsInhalerRelief", R.id.rbReliefYes, R.id.rbReliefNo);
                    break;
            }
        }

        private void putYesNo(Map<String, Object> data, String key, int yesId, int noId) {
            if (isChecked(yesId)) data.put(key, true);
            else if (isChecked(noId)) data.put(key, false);
        }

        private String getText(int id) {
            TextInputEditText et = itemView.findViewById(id);
            return et != null && et.getText() != null ? et.getText().toString().trim() : "";
        }

        private boolean isChecked(int id) {
            View view = itemView.findViewById(id);
            if (view instanceof CheckBox) return ((CheckBox) view).isChecked();
            if (view instanceof android.widget.RadioButton) return ((android.widget.RadioButton) view).isChecked();
            return false;
        }
    }
}
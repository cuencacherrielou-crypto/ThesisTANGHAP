package com.sti.tanghap.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.sti.tanghap.R;
import com.sti.tanghap.domain.HealthSummary;
import java.util.List;

/** Read-only view of the health information the user entered during onboarding and in Edit Profile. */
public class HealthInfoActivity extends AppCompatActivity {

    private LinearLayout sectionContainer;
    private TextView tvEmpty, tvNote;
    private View progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_health_info);

        sectionContainer = findViewById(R.id.sectionContainer);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvNote = findViewById(R.id.tvNote);
        progress = findViewById(R.id.progress);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnEdit).setOnClickListener(v ->
                startActivity(new Intent(this, EditProfileActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload so changes saved in Edit Profile show up right away
        loadHealthInfo();
    }

    private void loadHealthInfo() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            show(null);
            return;
        }

        progress.setVisibility(View.VISIBLE);
        FirebaseFirestore.getInstance().collection("users").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    progress.setVisibility(View.GONE);
                    show(HealthSummary.build(doc.exists() ? doc.getData() : null));
                })
                .addOnFailureListener(e -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Failed to load health information.", Toast.LENGTH_SHORT).show();
                    show(null);
                });
    }

    private void show(List<HealthSummary.Section> sections) {
        sectionContainer.removeAllViews();
        boolean empty = sections == null || sections.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvNote.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (HealthSummary.Section section : sections) {
            View sectionView = inflater.inflate(R.layout.item_health_info_section, sectionContainer, false);
            ((TextView) sectionView.findViewById(R.id.tvSectionTitle)).setText(section.title);
            ViewGroup rows = sectionView.findViewById(R.id.rowContainer);
            for (HealthSummary.Item item : section.items) {
                View row = inflater.inflate(R.layout.item_health_info_row, rows, false);
                ((TextView) row.findViewById(R.id.tvLabel)).setText(item.label);
                ((TextView) row.findViewById(R.id.tvValue)).setText(item.value);
                rows.addView(row);
            }
            sectionContainer.addView(sectionView);
        }
    }
}

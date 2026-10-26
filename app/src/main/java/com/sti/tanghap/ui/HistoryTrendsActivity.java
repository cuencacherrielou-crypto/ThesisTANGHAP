package com.sti.tanghap.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.ui.adapter.SessionAdapter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import java.text.SimpleDateFormat;
import com.sti.tanghap.domain.DailySummary;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryTrendsActivity extends AppCompatActivity {
    private static final String TAG = "HistoryTrendsActivity";
    private SessionAdapter adapter;
    private BarChart chartCough;
    private TextView tvEmpty, tvDailyCoverage;
    private android.widget.LinearLayout dailyContainer;
    private static final int SUMMARY_DAYS = 14;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_trends);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        chartCough = findViewById(R.id.chartCoughHistory);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvDailyCoverage = findViewById(R.id.tvDailyCoverage);
        dailyContainer = findViewById(R.id.dailyContainer);
        ExtendedFloatingActionButton btnExportAll = findViewById(R.id.btnExportAll);
        
        setupCharts();

        RecyclerView rv = findViewById(R.id.rvSessions);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SessionAdapter(new ArrayList<>(), session -> {
            if (session != null) {
                Intent intent = new Intent(this, SessionResultActivity.class);
                intent.putExtra("sessionId", session.id);
                startActivity(intent);
            }
        });
        rv.setAdapter(adapter);

        btnExportAll.setOnClickListener(v -> executor.execute(() -> {
            String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null ?
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
            List<SessionEntity> all = AppDatabase.getInstance(this).sessionDao().getAllForUser(uid);
            runOnUiThread(() -> {
                if (!all.isEmpty()) {
                    Intent intent = new Intent(this, DoctorExportActivity.class);
                    intent.putExtra(DoctorExportActivity.EXTRA_EXPORT_ALL, true);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show();
                }
            });
        }));

    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload so new sessions and cough reviews show up when returning to this screen
        loadData();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // Breathing-rate and inhalation-effort trends are hidden for now; coughs are charted per session
    private void setupCharts() {
        styleBarChart(chartCough);
        showGhostData();
    }

    private void styleBarChart(BarChart chart) {
        if (chart == null) return;
        chart.setDrawGridBackground(false);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(ContextCompat.getColor(this, R.color.text_medium_emphasis));
        
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setGridColor(ContextCompat.getColor(this, R.color.divider));
        leftAxis.setTextColor(ContextCompat.getColor(this, R.color.text_medium_emphasis));
        leftAxis.setAxisMinimum(0f);
    }

    private void showGhostData() {
        // Ghost Cough
        List<BarEntry> ghostBar = new ArrayList<>();
        for (int i = 0; i < 5; i++) ghostBar.add(new BarEntry(i, 0));
        BarDataSet barSet = new BarDataSet(ghostBar, "");
        barSet.setColor(Color.TRANSPARENT);
        if (chartCough != null) {
            chartCough.setData(new BarData(barSet));
            chartCough.invalidate();
        }
    }

    private void loadData() {
        executor.execute(() -> {
            try {
                String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null ?
                        com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
                List<SessionEntity> sessions = AppDatabase.getInstance(this).sessionDao().getAllForUser(uid);
                List<DailySummary.Day> days = DailySummary.build(sessions, Calendar.getInstance(), SUMMARY_DAYS);
                runOnUiThread(() -> {
                    showDailySummary(days);
                    if (sessions != null && !sessions.isEmpty()) {
                        tvEmpty.setVisibility(View.GONE);
                        adapter.setSessions(sessions);
                        updateCharts(sessions);
                    } else {
                        tvEmpty.setVisibility(View.VISIBLE);
                        showGhostData();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading sessions", e);
            }
        });
    }

    /** One row per day for the last two weeks, so days without monitoring are visible too. */
    private void showDailySummary(List<DailySummary.Day> days) {
        dailyContainer.removeAllViews();
        int tracked = 0;
        int age = HealthPrefs.loadProfile(this).ageYears;
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());
        for (DailySummary.Day d : days) {
            if (d.hasData()) tracked++;
            TextView row = new TextView(this);
            row.setTextSize(12);
            row.setPadding(0, 8, 0, 8);
            String date = dayFmt.format(new Date(d.dayStartMillis));
            if (!d.hasData()) {
                row.setText(date + "  •  No monitoring recorded");
                row.setTextColor(ContextCompat.getColor(this, R.color.text_low_emphasis));
            } else {
                StringBuilder sb = new StringBuilder(date).append('\n')
                        .append(d.sessions).append(d.sessions == 1 ? " session, " : " sessions, ")
                        .append(Math.round(d.monitoredMillis / 60000f)).append(" min monitored")
                        .append('\n').append(d.averageBpm() > 0
                                ? "Breathing: " + BreathingAnalyzer.shortLabel(BreathingAnalyzer.classify(d.averageBpm(), age).pattern)
                                : "Breathing not measured")
                        .append(" • ").append(d.coughs).append(d.coughs == 1 ? " cough" : " coughs")
                        .append(" • ").append(d.inhalerPresses).append(d.inhalerPresses == 1 ? " inhaler press" : " inhaler presses");
                if (d.wheezes > 0) sb.append(" • ").append(d.wheezes).append(d.wheezes == 1 ? " wheeze" : " wheezes");
                for (String c : d.possibleConditions) sb.append('\n').append(c).append(" (not a diagnosis)");
                row.setText(sb);
                row.setTextColor(ContextCompat.getColor(this, d.possibleConditions.isEmpty()
                        ? R.color.text_high_emphasis : R.color.warning));
            }
            dailyContainer.addView(row);
        }
        tvDailyCoverage.setText(tracked + " of " + days.size() + " days have monitoring data.");
    }

    private void updateCharts(List<SessionEntity> sessions) {
        if (sessions == null || sessions.isEmpty()) return;

        List<BarEntry> coughEntries = new ArrayList<>();

        // The list is newest first; charts read left (oldest) to right (newest).
        final long[] sessionTimes = new long[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            SessionEntity s = sessions.get(sessions.size() - 1 - i);
            sessionTimes[i] = s.startTimeMillis;
            coughEntries.add(new BarEntry(i, s.coughCount));
        }

        // X axis shows the date and time of each session
        ValueFormatter dateLabels = new ValueFormatter() {
            private final SimpleDateFormat fmt = new SimpleDateFormat("MM/dd h:mma", Locale.getDefault());

            @Override
            public String getFormattedValue(float value) {
                int i = Math.round(value);
                return i >= 0 && i < sessionTimes.length ? fmt.format(new Date(sessionTimes[i])) : "";
            }
        };
        if (chartCough != null) {
            chartCough.getXAxis().setValueFormatter(dateLabels);
            chartCough.getXAxis().setGranularity(1f);
            chartCough.getXAxis().setLabelCount(Math.min(4, sessionTimes.length));
        }

        // Cough
        BarDataSet cSet = new BarDataSet(coughEntries, "Cough");
        cSet.setColor(ContextCompat.getColor(this, R.color.error));
        cSet.setDrawValues(false);
        if (chartCough != null) {
            chartCough.setData(new BarData(cSet));
            chartCough.animateY(1000);
        }
    }
}

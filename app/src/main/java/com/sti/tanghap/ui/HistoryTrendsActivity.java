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
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.ui.adapter.SessionAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryTrendsActivity extends AppCompatActivity {
    private static final String TAG = "HistoryTrendsActivity";
    private SessionAdapter adapter;
    private LineChart chartPressure, chartBpmTrend;
    private BarChart chartCough;
    private TextView tvEmpty;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_trends);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        chartBpmTrend = findViewById(R.id.chartBpmTrend);
        chartPressure = findViewById(R.id.chartTrend);
        chartCough = findViewById(R.id.chartCoughHistory);
        tvEmpty = findViewById(R.id.tvEmpty);
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
            if (!all.isEmpty()) {
                Intent intent = new Intent(this, DoctorExportActivity.class);
                intent.putExtra("sessionId", all.get(0).id);
                startActivity(intent);
            } else {
                runOnUiThread(() -> Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show());
            }
        }));

        loadData();
    }

    private void setupCharts() {
        styleLineChart(chartBpmTrend);
        styleLineChart(chartPressure);
        styleBarChart(chartCough);
        showGhostData();
    }

    private void styleLineChart(LineChart chart) {
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
        // Ghost BPM
        List<Entry> ghostBpm = new ArrayList<>();
        for (int i = 0; i < 5; i++) ghostBpm.add(new Entry(i, 0));
        LineDataSet bpmSet = new LineDataSet(ghostBpm, "");
        bpmSet.setColor(Color.TRANSPARENT);
        bpmSet.setDrawCircles(false);
        if (chartBpmTrend != null) {
            chartBpmTrend.setData(new LineData(bpmSet));
            chartBpmTrend.invalidate();
        }

        // Ghost Pressure
        List<Entry> ghostLine = new ArrayList<>();
        for (int i = 0; i < 5; i++) ghostLine.add(new Entry(i, 0));
        LineDataSet lineSet = new LineDataSet(ghostLine, "");
        lineSet.setColor(Color.TRANSPARENT);
        lineSet.setDrawCircles(false);
        if (chartPressure != null) {
            chartPressure.setData(new LineData(lineSet));
            chartPressure.invalidate();
        }

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
                runOnUiThread(() -> {
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

    private void updateCharts(List<SessionEntity> sessions) {
        if (sessions == null || sessions.isEmpty()) return;

        List<Entry> bpmEntries = new ArrayList<>();
        List<Entry> pressureEntries = new ArrayList<>();
        List<BarEntry> coughEntries = new ArrayList<>();

        for (int i = 0; i < sessions.size(); i++) {
            SessionEntity s = sessions.get(i);
            bpmEntries.add(new Entry(i, s.averageBpm));
            pressureEntries.add(new Entry(i, s.peakPressureKpa));
            coughEntries.add(new BarEntry(i, s.coughCount));
        }

        // BPM Trend
        LineDataSet bSet = new LineDataSet(bpmEntries, "Respiration Rate");
        bSet.setColor(ContextCompat.getColor(this, R.color.accent_teal));
        bSet.setLineWidth(3f);
        bSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        bSet.setDrawValues(false);
        bSet.setDrawCircles(true);
        bSet.setCircleColor(ContextCompat.getColor(this, R.color.accent_teal));
        if (chartBpmTrend != null) {
            chartBpmTrend.setData(new LineData(bSet));
            chartBpmTrend.animateY(1000);
        }

        // Pressure
        LineDataSet pSet = new LineDataSet(pressureEntries, "Pressure");
        pSet.setColor(ContextCompat.getColor(this, R.color.secondary));
        pSet.setLineWidth(3f);
        pSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        pSet.setDrawValues(false);
        pSet.setDrawCircles(false);
        if (chartPressure != null) {
            chartPressure.setData(new LineData(pSet));
            chartPressure.animateY(1000);
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

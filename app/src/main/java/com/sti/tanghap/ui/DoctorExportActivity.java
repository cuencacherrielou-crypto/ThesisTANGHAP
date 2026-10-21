package com.sti.tanghap.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.SessionDao;
import com.sti.tanghap.data.SessionEntity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DoctorExportActivity extends AppCompatActivity {
    public static final String EXTRA_EXPORT_ALL = "exportAll";

    // A4 size: 595 x 842 points
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 50;
    private static final int FOOTER_Y = 780;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat sdf = new SimpleDateFormat("MMMM dd, yyyy HH:mm", Locale.getDefault());
    private String sessionId;
    private boolean exportAll;

    // Page state while drawing
    private PdfDocument document;
    private PdfDocument.Page page;
    private Canvas canvas;
    private int pageNumber;
    private int y;
    private final Paint paint = new Paint();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doctor_export);

        sessionId = getIntent().getStringExtra("sessionId");
        exportAll = getIntent().getBooleanExtra(EXTRA_EXPORT_ALL, false);
        if (sessionId == null && !exportAll) {
            Toast.makeText(this, "No session data to export", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        findViewById(R.id.btnConfirmExport).setOnClickListener(v -> {
            v.setEnabled(false);
            generatePdf();
        });
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void generatePdf() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        executor.execute(() -> {
            List<SessionEntity> sessions = new ArrayList<>();
            if (uid != null) {
                SessionDao dao = AppDatabase.getInstance(this).sessionDao();
                if (exportAll) {
                    sessions.addAll(dao.getAllForUser(uid));
                    Collections.reverse(sessions); // oldest first
                } else {
                    SessionEntity s = dao.getByIdForUser(sessionId, uid);
                    if (s != null) sessions.add(s);
                }
            }

            if (sessions.isEmpty()) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: Session not found", Toast.LENGTH_SHORT).show();
                    findViewById(R.id.btnConfirmExport).setEnabled(true);
                });
                return;
            }

            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Storage is not available.", Toast.LENGTH_SHORT).show();
                    findViewById(R.id.btnConfirmExport).setEnabled(true);
                });
                return;
            }
            File file = new File(dir, "TANGHAP_Report_" + System.currentTimeMillis() + ".pdf");

            document = new PdfDocument();
            try {
                pageNumber = 0;
                startNewPage();
                drawHeader(sessions);
                if (exportAll) {
                    drawSessionTable(sessions);
                } else {
                    drawSingleSession(sessions.get(0));
                }
                finishPage();

                try (FileOutputStream out = new FileOutputStream(file)) {
                    document.writeTo(out);
                }
                runOnUiThread(() -> shareReport(file));
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to save PDF", Toast.LENGTH_SHORT).show();
                    findViewById(R.id.btnConfirmExport).setEnabled(true);
                });
            } finally {
                document.close();
            }
        });
    }

    private void shareReport(File file) {
        Toast.makeText(this, "Report saved: " + file.getName(), Toast.LENGTH_LONG).show();
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, "Share report with your doctor"));
        } catch (ActivityNotFoundException ignored) {
            // Nothing can receive a PDF; the file is still saved
        }
        finish();
    }

    // =========================================================
    // DRAWING
    // =========================================================

    private void startNewPage() {
        pageNumber++;
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
        page = document.startPage(pageInfo);
        canvas = page.getCanvas();
        y = 60;
    }

    private void finishPage() {
        // Footer / Disclaimer
        paint.setTextSize(10);
        paint.setFakeBoldText(false);
        paint.setColor(Color.GRAY);
        canvas.drawText("* This report is an automated trend indicator from the TANGHAP smart inhaler system.", MARGIN, FOOTER_Y, paint);
        canvas.drawText("  Consult with a medical professional for clinical diagnosis.", MARGIN, FOOTER_Y + 15, paint);
        canvas.drawText("Page " + pageNumber, PAGE_WIDTH - MARGIN - 40, FOOTER_Y + 30, paint);
        document.finishPage(page);
    }

    /** Moves to a new page when the next block would run into the footer. */
    private void ensureSpace(int needed) {
        if (y + needed > FOOTER_Y - 20) {
            finishPage();
            startNewPage();
        }
    }

    private void drawHeader(List<SessionEntity> sessions) {
        paint.setColor(Color.BLACK);
        paint.setTextSize(24);
        paint.setFakeBoldText(true);
        canvas.drawText("TANGHAP Lung Health Report", MARGIN, y, paint);

        y += 40;
        paint.setTextSize(14);
        paint.setFakeBoldText(false);
        canvas.drawText("Generated on: " + sdf.format(new Date()), MARGIN, y, paint);
        if (exportAll) {
            y += 20;
            canvas.drawText("Sessions included: " + sessions.size(), MARGIN, y, paint);
        }

        // Divider
        y += 20;
        paint.setStrokeWidth(2);
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, paint);
        y += 40;
    }

    private void drawSingleSession(SessionEntity session) {
        paint.setColor(Color.BLACK);
        paint.setTextSize(16);
        paint.setFakeBoldText(true);
        canvas.drawText("SESSION SUMMARY", MARGIN, y, paint);

        paint.setTextSize(14);
        paint.setFakeBoldText(false);
        y += 30;
        canvas.drawText("Session ID: " + session.id, MARGIN, y, paint);
        y += 25;
        canvas.drawText("Session Date: " + sdf.format(new Date(session.startTimeMillis)), MARGIN, y, paint);

        y += 35;
        paint.setFakeBoldText(true);
        canvas.drawText("Metrics:", MARGIN, y, paint);
        paint.setFakeBoldText(false);
        y += 25;
        canvas.drawText("- Average Breathing Rate: " + (session.averageBpm > 0 ? session.averageBpm + " BPM" : "not measured"), 70, y, paint);
        y += 25;
        canvas.drawText("- Breathing Pattern: " + nonNull(session.detectedPattern), 70, y, paint);
        y += 25;
        canvas.drawText("- Peak Inhalation Pressure: " + String.format(Locale.getDefault(), "%.1f kPa", session.peakPressureKpa), 70, y, paint);
        y += 25;
        canvas.drawText("- Average Inhalation Duration: " + String.format(Locale.getDefault(), "%.2f seconds", session.inhaleDurationMillis / 1000f), 70, y, paint);
        y += 25;
        canvas.drawText("- Cough Events: " + session.coughCount + (session.coughFinding != null ? " (" + session.coughFinding + ")" : ""), 70, y, paint);
        y += 25;
        canvas.drawText("- Actuations: " + session.actuationCount, 70, y, paint);

        if (session.aiPreliminaryInsight != null) {
            y += 35;
            drawWrappedSection("Preliminary Assessment (non-diagnostic):", session.aiPreliminaryInsight);
        }
        if (session.aiObservations != null) {
            y += 15;
            drawWrappedSection("Observations:", session.aiObservations);
        }
        if (session.otcRecommendation != null) {
            y += 15;
            drawWrappedSection("Indication:", session.otcRecommendation);
        }
        if (session.baselineComparison != null) {
            y += 15;
            drawWrappedSection("Baseline Comparison:", session.baselineComparison);
        }
    }

    private void drawWrappedSection(String title, String body) {
        ensureSpace(45);
        paint.setColor(Color.BLACK);
        paint.setTextSize(14);
        paint.setFakeBoldText(true);
        canvas.drawText(title, MARGIN, y, paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(12);
        y += 18;
        for (String paragraph : body.split("\n")) {
            drawWrapped(paragraph, 70, PAGE_WIDTH - MARGIN - 70);
        }
        y += 10;
    }

    /**
     * Draws text word-wrapped to maxWidth starting at the current y and leaves y on the next free line.
     * Continues on a new page instead of stopping when the text reaches the footer.
     */
    private void drawWrapped(String text, int x, int maxWidth) {
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(candidate) > maxWidth && line.length() > 0) {
                drawWrappedLine(line.toString(), x);
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            drawWrappedLine(line.toString(), x);
        }
    }

    private void drawWrappedLine(String line, int x) {
        ensureSpace(18);
        canvas.drawText(line, x, y, paint);
        y += 18;
    }

    private void drawSessionTable(List<SessionEntity> sessions) {
        drawTableHeader();
        paint.setTextSize(11);
        paint.setFakeBoldText(false);
        SimpleDateFormat rowFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
        for (SessionEntity s : sessions) {
            if (y + 20 > FOOTER_Y - 20) {
                finishPage();
                startNewPage();
                drawTableHeader();
                paint.setTextSize(11);
                paint.setFakeBoldText(false);
            }
            y += 20;
            paint.setColor(Color.BLACK);
            canvas.drawText(rowFormat.format(new Date(s.startTimeMillis)), MARGIN, y, paint);
            canvas.drawText(s.averageBpm > 0 ? String.valueOf(s.averageBpm) : "--", 200, y, paint);
            canvas.drawText(String.format(Locale.getDefault(), "%.1f", s.peakPressureKpa), 260, y, paint);
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", s.inhaleDurationMillis / 1000f), 330, y, paint);
            canvas.drawText(String.valueOf(s.coughCount), 410, y, paint);
            canvas.drawText(String.valueOf(s.actuationCount), 470, y, paint);
        }
    }

    private void drawTableHeader() {
        paint.setColor(Color.BLACK);
        paint.setTextSize(11);
        paint.setFakeBoldText(true);
        canvas.drawText("Date", MARGIN, y, paint);
        canvas.drawText("BPM", 200, y, paint);
        canvas.drawText("Peak kPa", 260, y, paint);
        canvas.drawText("Inhale s", 330, y, paint);
        canvas.drawText("Coughs", 410, y, paint);
        canvas.drawText("Actuations", 470, y, paint);
        y += 6;
        paint.setStrokeWidth(1);
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, paint);
    }

    private static String nonNull(String s) {
        return s != null ? s : "--";
    }
}

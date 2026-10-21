package com.sti.tanghap.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.SessionEntity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DoctorExportActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String sessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doctor_export);

        sessionId = getIntent().getStringExtra("sessionId");
        if (sessionId == null) {
            Toast.makeText(this, "No session data to export", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        findViewById(R.id.btnConfirmExport).setOnClickListener(v -> generatePdf());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
    }

    private void generatePdf() {
        executor.execute(() -> {
            SessionEntity session = AppDatabase.getInstance(this).sessionDao().getAll()
                    .stream().filter(s -> s.id.equals(sessionId)).findFirst().orElse(null);

            if (session == null) {
                runOnUiThread(() -> Toast.makeText(this, "Error: Session not found", Toast.LENGTH_SHORT).show());
                return;
            }

            PdfDocument document = new PdfDocument();
            // A4 size: 595 x 842 points
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            Paint paint = new Paint();

            // Header
            int y = 60;
            paint.setColor(Color.BLACK);
            paint.setTextSize(24);
            paint.setFakeBoldText(true);
            canvas.drawText("TANGHAP Lung Health Report", 50, y, paint);

            y += 40;
            paint.setTextSize(14);
            paint.setFakeBoldText(false);
            SimpleDateFormat sdf = new SimpleDateFormat("MMMM dd, yyyy HH:mm", Locale.getDefault());
            canvas.drawText("Generated on: " + sdf.format(new Date()), 50, y, paint);

            // Divider
            y += 20;
            paint.setStrokeWidth(2);
            canvas.drawLine(50, y, 545, y, paint);

            // Session Content
            y += 50;
            paint.setTextSize(16);
            paint.setFakeBoldText(true);
            canvas.drawText("SESSION SUMMARY", 50, y, paint);
            
            y += 40;
            paint.setTextSize(14);
            paint.setFakeBoldText(false);
            canvas.drawText("Session ID: " + session.id, 50, y, paint);
            
            y += 25;
            canvas.drawText("Session Date: " + sdf.format(new Date(session.startTimeMillis)), 50, y, paint);
            
            y += 40;
            paint.setFakeBoldText(true);
            canvas.drawText("Metrics:", 50, y, paint);
            
            paint.setFakeBoldText(false);
            y += 25;
            canvas.drawText("- Peak Inhalation Pressure: " + String.format(Locale.getDefault(), "%.1f kPa", session.peakPressureKpa), 70, y, paint);
            y += 25;
            canvas.drawText("- Inhalation Duration: " + String.format(Locale.getDefault(), "%.2f seconds", session.inhaleDurationMillis / 1000f), 70, y, paint);
            y += 25;
            canvas.drawText("- Cough Events: " + session.coughCount, 70, y, paint);
            y += 25;
            canvas.drawText("- Actuations: " + session.actuationCount, 70, y, paint);
            y += 25;
            canvas.drawText("- Average Breathing Rate: " + (session.averageBpm > 0 ? session.averageBpm + " breaths/min" : "not measured"), 70, y, paint);
            y += 25;
            canvas.drawText("- Breathing Pattern: " + (session.detectedPattern != null ? session.detectedPattern : "not available"), 70, y, paint);

            y += 40;
            paint.setFakeBoldText(true);
            canvas.drawText("Preliminary AI-Assisted Assessment:", 50, y, paint);
            paint.setFakeBoldText(false);
            y = drawWrapped(canvas, paint, "Findings: " + (session.aiPreliminaryInsight != null ? session.aiPreliminaryInsight : "none"), 70, y + 25, 475, 740);
            y = drawWrapped(canvas, paint, "Observations: " + (session.aiObservations != null ? session.aiObservations : "none"), 70, y + 5, 475, 740);
            if (session.otcRecommendation != null) {
                y = drawWrapped(canvas, paint, "Indication: " + session.otcRecommendation, 70, y + 5, 475, 740);
            }

            // Footer / Disclaimer
            y = 780;
            paint.setTextSize(10);
            paint.setColor(Color.GRAY);
            canvas.drawText("* This report is an automated trend indicator from the TANGHAP smart inhaler system.", 50, y, paint);
            y += 15;
            canvas.drawText("  Consult with a medical professional for clinical diagnosis.", 50, y, paint);

            document.finishPage(page);

            // Save to Public Documents or External Files
            String fileName = "TANGHAP_Report_" + System.currentTimeMillis() + ".pdf";
            File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName);

            try {
                document.writeTo(new FileOutputStream(file));
                runOnUiThread(() -> {
                    Toast.makeText(this, "Report saved to Documents", Toast.LENGTH_LONG).show();
                    // Optional: You could trigger a Share intent here
                    finish();
                });
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Failed to save PDF", Toast.LENGTH_SHORT).show());
            } finally {
                document.close();
            }
        });
    }

    /** Draws text wrapped to maxWidth and returns the y of the next line; stops at maxY. */
    private int drawWrapped(Canvas canvas, Paint paint, String text, int x, int y, int maxWidth, int maxY) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(candidate) > maxWidth && line.length() > 0) {
                if (y > maxY) return y;
                canvas.drawText(line.toString(), x, y, paint);
                y += 20;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0 && y <= maxY) {
            canvas.drawText(line.toString(), x, y, paint);
            y += 20;
        }
        return y;
    }
}

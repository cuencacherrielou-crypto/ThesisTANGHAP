package com.sti.tanghap.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.R;
import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.SavedAssessment;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.DailySummary;
import com.sti.tanghap.domain.SessionTimeline;
import com.sti.tanghap.data.SessionDao;
import com.sti.tanghap.data.SessionEntity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
    private static final String TAG = "DoctorExportActivity";
    /** Reports are saved to Downloads/TANGHAP so the user can find them in the Files app. */
    static final String FOLDER = "TANGHAP";
    private static final int REQ_STORAGE = 5101;
    private static final int MAX_SUMMARY_DAYS = 90;

    // A4 size: 595 x 842 points
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 50;
    private static final int FOOTER_Y = 780;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat sdf = new SimpleDateFormat("MMMM dd, yyyy hh:mm a", Locale.getDefault());
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

        findViewById(R.id.btnConfirmExport).setOnClickListener(v -> startExport());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void startExport() {
        // Android 8-9 need storage permission to save into Downloads; Android 10+ do not
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        findViewById(R.id.btnConfirmExport).setEnabled(false);
        generatePdf();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startExport();
        } else {
            Toast.makeText(this, "Storage permission is needed to save the report to Downloads.",
                    Toast.LENGTH_LONG).show();
        }
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

            String fileName = "TANGHAP_Report_"
                    + new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date()) + ".pdf";

            document = new PdfDocument();
            try {
                pageNumber = 0;
                startNewPage();
                drawHeader(sessions);
                if (exportAll) {
                    drawDailySummary(sessions);
                    drawSessionTable(sessions);
                } else {
                    drawSingleSession(sessions.get(0));
                }
                finishPage();

                Uri uri = saveToDownloads(fileName);
                runOnUiThread(() -> showSaved(uri, fileName));
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Saving the PDF report failed", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Could not save the report: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    findViewById(R.id.btnConfirmExport).setEnabled(true);
                });
            } finally {
                document.close();
            }
        });
    }

    /**
     * Writes the PDF into the phone's public Downloads/TANGHAP folder, where the Files app and
     * any PDF viewer can find it, and checks the saved file is not empty before reporting success.
     */
    private Uri saveToDownloads(String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("the Downloads folder is not available");
            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IOException("cannot open the file for writing");
                    document.writeTo(out);
                }
                long size;
                try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
                    size = pfd != null ? pfd.getStatSize() : 0;
                }
                if (size <= 0) throw new IOException("the saved file is empty");
                // Done writing: make the file visible to other apps
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                return uri;
            } catch (IOException | RuntimeException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create Downloads/" + FOLDER);
        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            document.writeTo(out);
        }
        if (file.length() <= 0) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw new IOException("the saved file is empty");
        }
        // Lets the Files app and computers connected by USB see the new file right away
        MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()},
                new String[]{"application/pdf"}, null);
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    /** Confirms where the report was saved and offers to open or share it. */
    private void showSaved(Uri uri, String fileName) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("Report saved")
                .setMessage("Saved to Downloads > " + FOLDER + " on this phone:\n" + fileName
                        + "\n\nYou can find it in the Files app under Downloads.")
                .setPositiveButton("Open", (d, w) -> {
                    Intent view = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(view);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "No PDF viewer is installed. The report is still saved in Downloads.",
                                Toast.LENGTH_LONG).show();
                    }
                    finish();
                })
                .setNeutralButton("Share", (d, w) -> {
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
                })
                .setNegativeButton("Done", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    /** One row per day from the first session to today (up to 90 days), including days without monitoring. */
    private void drawDailySummary(List<SessionEntity> sessions) {
        long first = Long.MAX_VALUE;
        for (SessionEntity s : sessions) first = Math.min(first, s.startTimeMillis);
        long spanDays = (System.currentTimeMillis() - first) / 86_400_000L + 2;
        int days = (int) Math.max(1, Math.min(MAX_SUMMARY_DAYS, spanDays));
        List<DailySummary.Day> summary = DailySummary.build(sessions, java.util.Calendar.getInstance(), days);

        paint.setColor(Color.BLACK);
        paint.setTextSize(14);
        paint.setFakeBoldText(true);
        canvas.drawText("DAILY SUMMARY" + (spanDays > MAX_SUMMARY_DAYS ? " (last " + MAX_SUMMARY_DAYS + " days)" : ""), MARGIN, y, paint);
        y += 20;
        drawDailyHeader();
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEE MMM dd, yyyy", Locale.getDefault());
        for (int i = summary.size() - 1; i >= 0; i--) { // oldest first, like the session table
            DailySummary.Day d = summary.get(i);
            if (y + 20 > FOOTER_Y - 20) {
                finishPage();
                startNewPage();
                drawDailyHeader();
            }
            y += 18;
            paint.setTextSize(10);
            paint.setFakeBoldText(false);
            paint.setColor(d.hasData() ? Color.BLACK : Color.GRAY);
            canvas.drawText(dayFmt.format(new Date(d.dayStartMillis)), MARGIN, y, paint);
            if (!d.hasData()) {
                canvas.drawText("No monitoring recorded", 180, y, paint);
                continue;
            }
            canvas.drawText(String.valueOf(d.sessions), 180, y, paint);
            canvas.drawText(String.valueOf(Math.round(d.monitoredMillis / 60000f)), 230, y, paint);
            canvas.drawText(d.averageBpm() > 0 ? String.valueOf(d.averageBpm()) : "--", 290, y, paint);
            canvas.drawText(String.valueOf(d.coughs), 345, y, paint);
            canvas.drawText(String.valueOf(d.wheezes), 400, y, paint);
            canvas.drawText(String.valueOf(d.inhalerPresses), 460, y, paint);
            for (String condition : d.possibleConditions) {
                ensureSpace(14);
                y += 12;
                paint.setColor(Color.DKGRAY);
                paint.setTextSize(9);
                canvas.drawText("   " + condition + " (not a diagnosis)", MARGIN, y, paint);
            }
        }
        y += 40;
        ensureSpace(60);
    }

    private void drawDailyHeader() {
        paint.setColor(Color.BLACK);
        paint.setTextSize(10);
        paint.setFakeBoldText(true);
        canvas.drawText("Day", MARGIN, y, paint);
        canvas.drawText("Sessions", 180, y, paint);
        canvas.drawText("Minutes", 230, y, paint);
        canvas.drawText("Avg BPM", 290, y, paint);
        canvas.drawText("Coughs", 345, y, paint);
        canvas.drawText("Wheezes", 400, y, paint);
        canvas.drawText("Presses", 460, y, paint);
        y += 6;
        paint.setStrokeWidth(1);
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, paint);
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
        canvas.drawText("Started: " + sdf.format(new Date(session.startTimeMillis)), MARGIN, y, paint);
        y += 25;
        long durationSec = Math.max(0, (session.endTimeMillis - session.startTimeMillis) / 1000);
        canvas.drawText("Ended: " + sdf.format(new Date(session.endTimeMillis))
                + String.format(Locale.getDefault(), "  (duration %d min %02d s)", durationSec / 60, durationSec % 60), MARGIN, y, paint);

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
        if (session.droppedPackets > 0) {
            y += 25;
            canvas.drawText("- Sensor packets lost over Bluetooth: " + session.droppedPackets, 70, y, paint);
        }

        SavedAssessment saved = SavedAssessment.of(session, HealthPrefs.loadProfile(this));
        if (saved.possibleCondition != null) {
            y += 35;
            drawWrappedSection("Possible Condition (not a diagnosis):", saved.possibleCondition);
        }
        if (session.aiPreliminaryInsight != null) {
            y += 35;
            drawWrappedSection("Preliminary Assessment (non-diagnostic):", session.aiPreliminaryInsight);
        }
        if (session.aiObservations != null) {
            y += 15;
            drawWrappedSection("Observations:", session.aiObservations);
        }
        if (saved.medicationGuidance != null) {
            y += 15;
            drawWrappedSection("Medicine Guidance (recommendation only):", saved.medicationGuidance);
        }
        if (session.baselineComparison != null) {
            y += 15;
            drawWrappedSection("Baseline Comparison:", session.baselineComparison);
        }
        drawTimeline(session);
    }

    /** Time-stamped readings: summaries, breathing rate per minute, then each key event. */
    private void drawTimeline(SessionEntity session) {
        List<SessionEvent> events = SessionEvent.decode(session.eventLog);
        if (events.isEmpty()) return;

        StringBuilder summary = new StringBuilder();
        String rates = SessionTimeline.breathingRateSummary(events);
        String inhalations = SessionTimeline.inhalationSummary(events);
        if (rates != null) summary.append("Breathing rate: ").append(rates).append('\n');
        if (inhalations != null) summary.append("Inhalations: ").append(inhalations).append('\n');
        if (summary.length() > 0) {
            y += 15;
            drawWrappedSection("Session Readings:", summary.toString().trim());
        }

        SimpleDateFormat clock = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        List<long[]> perMinute = SessionTimeline.breathingRatePerMinute(events, session.startTimeMillis);
        if (!perMinute.isEmpty()) {
            StringBuilder rows = new StringBuilder();
            for (long[] row : perMinute) {
                rows.append(clock.format(new Date(row[0]))).append(": ").append(row[1]).append(" BPM\n");
            }
            y += 15;
            drawWrappedSection("Breathing Rate per Minute (average):", rows.toString().trim());
        }

        List<String> lines = SessionTimeline.keyEventLines(events, Locale.getDefault());
        if (!lines.isEmpty()) {
            y += 15;
            drawWrappedSection("Event Timeline:", String.join("\n", lines));
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

            // Possible condition under the row, when there was one
            String condition = SavedAssessment.of(s, null).possibleCondition;
            if (condition != null && !AssessmentEngine.CATEGORY_STANDARD.equals(condition)) {
                ensureSpace(16);
                y += 14;
                paint.setColor(Color.DKGRAY);
                paint.setTextSize(9);
                canvas.drawText("   " + condition + " (not a diagnosis)", MARGIN, y, paint);
                paint.setTextSize(11);
            }
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

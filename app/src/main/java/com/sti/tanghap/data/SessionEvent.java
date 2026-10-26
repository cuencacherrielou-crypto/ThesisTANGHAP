package com.sti.tanghap.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One time-stamped reading or event recorded during a monitoring session
 * (a cough, an inhaler press, a breathing-rate reading, a connection drop, ...).
 * Stored with the session as text in Room and as a list of maps in Firestore.
 */
public class SessionEvent {

    public enum Type {
        /** Device connected (value 1 = reconnection after a drop). */
        CONNECTED,
        DISCONNECTED,
        /** Cough heard by the cough model; label = cough type, value = confidence %. */
        COUGH,
        /** Wheezing-like sound heard by the cough model; value = confidence %. */
        WHEEZE,
        /** Inhaler canister pressed (hall sensor). */
        ACTUATION,
        /**
         * Older sessions only (the air pressure sensor was removed): one inhalation measured by it;
         * value = duration in ms, label = peak effort in kPa. Kept so those saved sessions still load.
         */
        INHALATION,
        /** Older sessions only (from the removed air pressure sensor): breathing rate; value = BPM. */
        BREATHING_RATE,
        /** Older sessions only (from the removed air pressure sensor): packets lost; value = number of packets. */
        DATA_GAP,
        /** Microphone audio lost over Bluetooth and filled in; value = ms lost. */
        AUDIO_GAP,
        /**
         * Older sessions only (the nearby-cough filter was removed): a quieter cough that was not
         * counted. Kept so those saved sessions still load. Label = cough type, value = confidence %.
         */
        COUGH_IGNORED,
        /** Cough the user marked as not theirs on the result screen; not counted. */
        COUGH_REMOVED,
        /** Older sessions only (cough calibration was removed); value = level in dBFS, label = coughs heard. */
        CALIBRATION
    }

    /** True for the three cough types (counted, filtered as nearby, removed by the user). */
    public boolean isCoughEntry() {
        return type == Type.COUGH || type == Type.COUGH_IGNORED || type == Type.COUGH_REMOVED;
    }

    /** Same event with another type, e.g. when the user removes or restores a cough. */
    public SessionEvent withType(Type newType) {
        return new SessionEvent(timeMillis, newType, value, label);
    }

    /** Wall-clock time of the event in epoch milliseconds. */
    public final long timeMillis;
    public final Type type;
    public final float value;
    public final String label;

    public SessionEvent(long timeMillis, Type type, float value, String label) {
        this.timeMillis = timeMillis;
        this.type = type;
        this.value = value;
        this.label = label != null ? label : "";
    }

    /** Short human-readable description, without the time. */
    public String describe() {
        switch (type) {
            case CONNECTED:
                return value > 0 ? "Device reconnected" : "Device connected";
            case DISCONNECTED:
                return "Device disconnected";
            case COUGH:
                return "Cough detected: " + label + " (" + Math.round(value) + "% confidence)";
            case WHEEZE:
                return "Wheezing-like sound detected (" + Math.round(value) + "% confidence)";
            case ACTUATION:
                return "Inhaler pressed";
            case INHALATION:
                return String.format(Locale.US, "Inhalation: %.1f s", value / 1000f)
                        + (label.isEmpty() ? "" : ", peak " + label + " kPa");
            case BREATHING_RATE:
                return "Breathing rate: " + Math.round(value) + " BPM";
            case DATA_GAP:
                int lost = Math.round(value);
                return "Sensor data gap: " + lost + (lost == 1 ? " packet" : " packets") + " lost";
            case AUDIO_GAP:
                return "Audio gap: " + Math.round(value) + " ms lost over Bluetooth (filled in)";
            case COUGH_IGNORED:
                return "Not counted, likely someone nearby (quieter cough): " + label
                        + " (" + Math.round(value) + "% confidence)";
            case COUGH_REMOVED:
                return "Removed by you (not your cough): " + label
                        + " (" + Math.round(value) + "% confidence)";
            case CALIBRATION:
                return String.format(Locale.US, "Cough loudness calibrated from %s cough(s): %.0f dBFS",
                        label.isEmpty() ? "?" : label, value);
            default:
                return type.name();
        }
    }

    // =========================================================
    // ROOM TEXT FORMAT: one event per line, "time<TAB>TYPE<TAB>value<TAB>label"
    // =========================================================

    public static String encode(List<SessionEvent> events) {
        if (events == null || events.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (SessionEvent e : events) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.timeMillis).append('\t')
                    .append(e.type.name()).append('\t')
                    .append(e.value).append('\t')
                    .append(e.label.replace('\t', ' ').replace('\n', ' '));
        }
        return sb.toString();
    }

    /** Parses {@link #encode} output; malformed lines are skipped. */
    public static List<SessionEvent> decode(String text) {
        List<SessionEvent> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String line : text.split("\n")) {
            String[] parts = line.split("\t", 4);
            if (parts.length < 3) continue;
            try {
                out.add(new SessionEvent(
                        Long.parseLong(parts[0]),
                        Type.valueOf(parts[1]),
                        Float.parseFloat(parts[2]),
                        parts.length > 3 ? parts[3] : ""));
            } catch (IllegalArgumentException ignored) {
                // NumberFormatException is an IllegalArgumentException; also covers unknown types
            }
        }
        return out;
    }

    // =========================================================
    // FIRESTORE FORMAT: list of {timeMillis, type, value, label}
    // =========================================================

    public static List<Map<String, Object>> toMaps(List<SessionEvent> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionEvent e : events) {
            Map<String, Object> m = new HashMap<>();
            m.put("timeMillis", e.timeMillis);
            m.put("type", e.type.name());
            m.put("value", e.value);
            m.put("label", e.label);
            out.add(m);
        }
        return out;
    }

    public static List<SessionEvent> fromMaps(Object raw) {
        List<SessionEvent> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            Object time = m.get("timeMillis");
            Object type = m.get("type");
            Object value = m.get("value");
            Object label = m.get("label");
            if (!(time instanceof Number) || type == null) continue;
            try {
                out.add(new SessionEvent(
                        ((Number) time).longValue(),
                        Type.valueOf(String.valueOf(type)),
                        value instanceof Number ? ((Number) value).floatValue() : 0f,
                        label != null ? String.valueOf(label) : ""));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }
}

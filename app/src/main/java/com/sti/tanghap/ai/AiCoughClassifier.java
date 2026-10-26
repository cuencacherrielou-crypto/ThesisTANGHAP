package com.sti.tanghap.ai;

import android.content.Context;

import com.sti.tanghap.ai.processor.MfccExtractor;
import com.sti.tanghap.ai.processor.RumbleFilter;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * On-device cough / wheeze classifier backed by the trained TensorFlow Lite model.
 * Not thread-safe: create it once and call {@link #classify} from a single thread.
 */
public class AiCoughClassifier implements Closeable {
    private static final String MODEL_PATH = "tanghap_model.tflite";

    /**
     * A sensed sound is recorded as a cough only when a cough type is the top class AND the model
     * is at least this sure it is a cough (dry + wet + severe + persistent together). The model
     * splits a cough between types (TANGHAP device: dry 47% + wet 45% = 93% cough), so the top
     * type alone was 60% or more for only 5 of 28 real device coughs, while the cough total was
     * 65-94% for all 28. A cough is called severe only when severe alone reaches this level:
     * a device cough at severe 36% was wrongly called severe.
     */
    public static final float MIN_CONFIDENCE = 0.60f;

    /**
     * Wheezing needs more: breathing through the mouthpiece sounds partly like a wheeze to a model
     * trained on chest recordings (TANGHAP device tests: wheeze at 50-77% with no wheezing).
     * Held-out chest recordings at 70%: 68% of wheeze windows found, 25% false wheeze.
     */
    public static final float WHEEZE_MIN_CONFIDENCE = 0.70f;

    private final Interpreter interpreter;
    private final MfccExtractor features = new MfccExtractor();
    private final float[][] output = new float[1][CoughClass.values().length];

    public AiCoughClassifier(Context context) throws IOException {
        interpreter = new Interpreter(loadModel(context));
    }

    /** @param pcm16 2 seconds of 16 kHz mono 16-bit little-endian PCM */
    public Result classify(byte[] pcm16) {
        float[][][][] input = {features.extract(RumbleFilter.apply(toFloat(pcm16)))};
        interpreter.run(input, output);

        float[] probabilities = output[0].clone();
        int best = best(probabilities);
        return new Result(CoughClass.values()[best], probabilities[best], probabilities);
    }

    /**
     * The most likely class, never PERSISTENT_COUGH: whether a cough is persistent is measured
     * from the cough history (CoughHistory), not heard in one cough.
     */
    static int best(float[] probabilities) {
        int best = -1;
        for (int i = 0; i < probabilities.length; i++) {
            if (i == CoughClass.PERSISTENT_COUGH.ordinal()) continue;
            if (best < 0 || probabilities[i] > probabilities[best]) best = i;
        }
        return best;
    }

    /** Same scaling as librosa.load: int16 / 32768. */
    static float[] toFloat(byte[] pcm16) {
        float[] samples = new float[pcm16.length / 2];
        for (int i = 0; i < samples.length; i++) {
            short s = (short) ((pcm16[2 * i + 1] << 8) | (pcm16[2 * i] & 0xFF));
            samples[i] = s / 32768f;
        }
        return samples;
    }

    private static ByteBuffer loadModel(Context context) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream is = context.getAssets().open(MODEL_PATH)) {
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = is.read(chunk)) != -1) bytes.write(chunk, 0, read);
        }
        ByteBuffer model = ByteBuffer.allocateDirect(bytes.size()).order(ByteOrder.nativeOrder());
        model.put(bytes.toByteArray());
        model.rewind();
        return model;
    }

    @Override
    public void close() {
        interpreter.close();
    }

    public static class Result {
        public final CoughClass type;
        public final float confidence;
        /** Softmax output, indexed by {@link CoughClass#ordinal()}. */
        public final float[] probabilities;

        public Result(CoughClass type, float confidence, float[] probabilities) {
            this.type = type;
            this.confidence = confidence;
            this.probabilities = probabilities;
        }

        public boolean isConfident() {
            return confidence >= MIN_CONFIDENCE;
        }

        /** How sure the model is that the sound is a cough of any type, 0-1. */
        public float coughProbability() {
            return probabilities[CoughClass.DRY_COUGH.ordinal()] + probabilities[CoughClass.WET_COUGH.ordinal()]
                    + probabilities[CoughClass.SEVERE_COUGH.ordinal()] + probabilities[CoughClass.PERSISTENT_COUGH.ordinal()];
        }

        /** A cough type is the top class and the cough total reaches {@link #MIN_CONFIDENCE}. */
        public boolean isConfidentCough() {
            return type.isCough && coughProbability() >= MIN_CONFIDENCE;
        }

        /** The cough's type: severe only at {@link #MIN_CONFIDENCE} or more, otherwise dry or wet. */
        public CoughClass coughType() {
            if (probabilities[CoughClass.SEVERE_COUGH.ordinal()] >= MIN_CONFIDENCE) return CoughClass.SEVERE_COUGH;
            return probabilities[CoughClass.WET_COUGH.ordinal()] > probabilities[CoughClass.DRY_COUGH.ordinal()]
                    ? CoughClass.WET_COUGH : CoughClass.DRY_COUGH;
        }

        /** {@link #coughProbability()} as a whole percentage, 0-100. */
        public int coughPercent() {
            return Math.round(coughProbability() * 100);
        }

        /** Wheezing at {@link #WHEEZE_MIN_CONFIDENCE} or more. */
        public boolean isConfidentWheeze() {
            return type == CoughClass.WHEEZING && confidence >= WHEEZE_MIN_CONFIDENCE;
        }

        /** Confidence as a whole percentage, 0-100. */
        public int percent() {
            return Math.round(confidence * 100);
        }
    }
}

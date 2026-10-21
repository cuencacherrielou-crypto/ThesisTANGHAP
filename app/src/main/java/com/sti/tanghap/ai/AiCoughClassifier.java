package com.sti.tanghap.ai;

import android.content.Context;

import com.sti.tanghap.ai.processor.MfccExtractor;

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

    /** Below this softmax score the model is unsure, and the clip is ignored. */
    public static final float MIN_CONFIDENCE = 0.60f;

    private final Interpreter interpreter;
    private final MfccExtractor features = new MfccExtractor();
    private final float[][] output = new float[1][CoughClass.values().length];

    public AiCoughClassifier(Context context) throws IOException {
        interpreter = new Interpreter(loadModel(context));
    }

    /** @param pcm16 2 seconds of 16 kHz mono 16-bit little-endian PCM */
    public Result classify(byte[] pcm16) {
        float[][][][] input = {features.extract(toFloat(pcm16))};
        interpreter.run(input, output);

        float[] probabilities = output[0].clone();
        int best = 0;
        for (int i = 1; i < probabilities.length; i++) {
            if (probabilities[i] > probabilities[best]) best = i;
        }
        return new Result(CoughClass.values()[best], probabilities[best], probabilities);
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

        /** Confidence as a whole percentage, 0-100. */
        public int percent() {
            return Math.round(confidence * 100);
        }
    }
}

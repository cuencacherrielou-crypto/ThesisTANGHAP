package com.sti.tanghap.ai.processor;

import org.jtransforms.fft.DoubleFFT_1D;

/**
 * Input features for the cough model, computed exactly as in the Python training code:
 * <pre>
 *   mfcc = librosa.feature.mfcc(y=y, sr=16000, n_mfcc=20)
 *   x = np.stack([mfcc, librosa.feature.delta(mfcc), librosa.feature.delta(mfcc, order=2)], axis=-1)
 * </pre>
 * i.e. the librosa defaults: 2048-point FFT, hop 512, periodic Hann window, centered frames
 * with zero padding, 128 Slaney mel bands, power_to_db(ref=1, top_db=80), orthonormal DCT-II,
 * and Savitzky-Golay deltas of width 9. A 2-second clip gives the model's 20 x 63 x 3 input.
 */
public final class MfccExtractor {

    public static final int SAMPLE_RATE = 16000;
    public static final int CLIP_SAMPLES = 2 * SAMPLE_RATE;
    public static final int N_MFCC = 20;
    public static final int N_FFT = 2048;
    public static final int HOP = 512;
    public static final int N_MELS = 128;
    public static final int N_FRAMES = 1 + CLIP_SAMPLES / HOP; // 63
    public static final int N_CHANNELS = 3;                    // mfcc, delta, delta-delta

    private static final int N_BINS = N_FFT / 2 + 1;
    private static final double AMIN = 1e-10;
    private static final double TOP_DB = 80.0;
    private static final int DELTA_HALF_WIDTH = 4;              // librosa delta width=9

    private final double[] window = new double[N_FFT];
    private final double[][] melFilters = melFilterbank();
    private final double[][] dct = dctMatrix();
    private final DoubleFFT_1D fft = new DoubleFFT_1D(N_FFT);

    public MfccExtractor() {
        for (int n = 0; n < N_FFT; n++) {
            window[n] = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / N_FFT);
        }
    }

    /**
     * @param clip mono audio in [-1, 1] at 16 kHz; cut or zero-padded to {@link #CLIP_SAMPLES}
     * @return features indexed [mfcc][frame][channel]
     */
    public float[][][] extract(float[] clip) {
        double[][] mfcc = mfcc(clip);
        double[][] d1 = delta(mfcc, 1);
        double[][] d2 = delta(mfcc, 2);

        float[][][] out = new float[N_MFCC][N_FRAMES][N_CHANNELS];
        for (int c = 0; c < N_MFCC; c++) {
            for (int t = 0; t < N_FRAMES; t++) {
                out[c][t][0] = (float) mfcc[c][t];
                out[c][t][1] = (float) d1[c][t];
                out[c][t][2] = (float) d2[c][t];
            }
        }
        return out;
    }

    double[][] mfcc(float[] clip) {
        // Centered frames: N_FFT / 2 zeros on both sides of the fixed-length clip
        double[] padded = new double[CLIP_SAMPLES + N_FFT];
        int n = Math.min(clip.length, CLIP_SAMPLES);
        for (int i = 0; i < n; i++) padded[N_FFT / 2 + i] = clip[i];

        double[][] melDb = new double[N_MELS][N_FRAMES];
        double[] frame = new double[N_FFT];
        double[] power = new double[N_BINS];
        double maxDb = Double.NEGATIVE_INFINITY;

        for (int t = 0; t < N_FRAMES; t++) {
            int start = t * HOP;
            for (int i = 0; i < N_FFT; i++) frame[i] = padded[start + i] * window[i];
            fft.realForward(frame);

            // realForward layout: [Re0, Re(n/2), Re1, Im1, Re2, Im2, ...]
            power[0] = frame[0] * frame[0];
            power[N_BINS - 1] = frame[1] * frame[1];
            for (int k = 1; k < N_BINS - 1; k++) {
                double re = frame[2 * k];
                double im = frame[2 * k + 1];
                power[k] = re * re + im * im;
            }

            for (int m = 0; m < N_MELS; m++) {
                double[] w = melFilters[m];
                double sum = 0;
                for (int k = 0; k < N_BINS; k++) sum += w[k] * power[k];
                double db = 10.0 * Math.log10(Math.max(AMIN, sum));
                melDb[m][t] = db;
                if (db > maxDb) maxDb = db;
            }
        }

        double floor = maxDb - TOP_DB;
        double[][] mfcc = new double[N_MFCC][N_FRAMES];
        for (int t = 0; t < N_FRAMES; t++) {
            for (int c = 0; c < N_MFCC; c++) {
                double sum = 0;
                for (int m = 0; m < N_MELS; m++) sum += dct[c][m] * Math.max(melDb[m][t], floor);
                mfcc[c][t] = sum;
            }
        }
        return mfcc;
    }

    /**
     * librosa.feature.delta (scipy savgol_filter, mode="interp"). With polyorder == deriv the
     * fitted derivative is constant over the edge windows, so edge frames copy the nearest
     * fully-covered frame.
     */
    static double[][] delta(double[][] data, int order) {
        double[] coeffs = new double[2 * DELTA_HALF_WIDTH + 1];
        for (int k = -DELTA_HALF_WIDTH; k <= DELTA_HALF_WIDTH; k++) {
            coeffs[k + DELTA_HALF_WIDTH] = order == 1
                    ? k / 60.0                 // first derivative of a linear least-squares fit
                    : (3.0 * k * k - 20) / 462.0; // second derivative of a quadratic fit
        }

        int frames = data[0].length;
        double[][] out = new double[data.length][frames];
        for (int r = 0; r < data.length; r++) {
            for (int t = DELTA_HALF_WIDTH; t < frames - DELTA_HALF_WIDTH; t++) {
                double sum = 0;
                for (int k = -DELTA_HALF_WIDTH; k <= DELTA_HALF_WIDTH; k++) {
                    sum += coeffs[k + DELTA_HALF_WIDTH] * data[r][t + k];
                }
                out[r][t] = sum;
            }
            for (int t = 0; t < DELTA_HALF_WIDTH; t++) {
                out[r][t] = out[r][DELTA_HALF_WIDTH];
                out[r][frames - 1 - t] = out[r][frames - 1 - DELTA_HALF_WIDTH];
            }
        }
        return out;
    }

    /** librosa.filters.mel(sr=16000, n_fft=2048, n_mels=128): Slaney mel scale and area norm. */
    private static double[][] melFilterbank() {
        double minMel = hzToMel(0);
        double maxMel = hzToMel(SAMPLE_RATE / 2.0);
        double[] melF = new double[N_MELS + 2];
        for (int i = 0; i < melF.length; i++) {
            melF[i] = melToHz(minMel + (maxMel - minMel) * i / (N_MELS + 1));
        }

        double[][] weights = new double[N_MELS][N_BINS];
        for (int m = 0; m < N_MELS; m++) {
            double lowerWidth = melF[m + 1] - melF[m];
            double upperWidth = melF[m + 2] - melF[m + 1];
            double enorm = 2.0 / (melF[m + 2] - melF[m]);
            for (int k = 0; k < N_BINS; k++) {
                double f = (double) k * SAMPLE_RATE / N_FFT;
                double lower = (f - melF[m]) / lowerWidth;
                double upper = (melF[m + 2] - f) / upperWidth;
                weights[m][k] = Math.max(0, Math.min(lower, upper)) * enorm;
            }
        }
        return weights;
    }

    private static final double F_SP = 200.0 / 3;
    private static final double MIN_LOG_HZ = 1000.0;
    private static final double MIN_LOG_MEL = MIN_LOG_HZ / F_SP;
    private static final double LOG_STEP = Math.log(6.4) / 27.0;

    private static double hzToMel(double hz) {
        return hz >= MIN_LOG_HZ ? MIN_LOG_MEL + Math.log(hz / MIN_LOG_HZ) / LOG_STEP : hz / F_SP;
    }

    private static double melToHz(double mel) {
        return mel >= MIN_LOG_MEL ? MIN_LOG_HZ * Math.exp(LOG_STEP * (mel - MIN_LOG_MEL)) : F_SP * mel;
    }

    /** Orthonormal DCT-II rows (scipy.fftpack.dct(type=2, norm="ortho")), first N_MFCC only. */
    private static double[][] dctMatrix() {
        double[][] m = new double[N_MFCC][N_MELS];
        for (int c = 0; c < N_MFCC; c++) {
            double scale = Math.sqrt((c == 0 ? 1.0 : 2.0) / N_MELS);
            for (int n = 0; n < N_MELS; n++) {
                m[c][n] = scale * Math.cos(Math.PI * c * (2 * n + 1) / (2.0 * N_MELS));
            }
        }
        return m;
    }
}

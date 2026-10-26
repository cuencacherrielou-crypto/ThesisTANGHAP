package com.sti.tanghap.ai.processor;

/**
 * Removes the low rumble the device's microphone picks up when breath and coughs push air
 * through the inhaler: on the TANGHAP attachment most of a cough's energy is a pressure pulse
 * below 40 Hz, far louder than the cough sound itself. Applied to every clip before the MFCCs,
 * exactly as in the Python training code:
 * <pre>
 *   y = y - y.mean()
 *   y = scipy.signal.sosfilt(scipy.signal.butter(4, 100, 'highpass', fs=16000, output='sos'), y)
 * </pre>
 * (4th-order Butterworth high-pass at 100 Hz: -56 dB at 20 Hz, -3 dB at 100 Hz, flat above 200 Hz.)
 */
public final class RumbleFilter {

    /** butter(4, 100, 'highpass', fs=16000, output='sos'): rows of b0, b1, b2, a0, a1, a2. */
    static final double[][] SOS = {
            {0.9499817826392998, -1.8999635652785996, 0.9499817826392998, 1.0, -1.9285084850826342, 0.9299964423952546},
            {1.0, -2.0, 1.0, 1.0, -1.968877497385758, 0.9703966017571153},
    };

    private RumbleFilter() {
    }

    /** Returns a filtered copy of the clip (DC removed, then the cascade, starting from rest). */
    public static float[] apply(float[] clip) {
        double mean = 0;
        for (float v : clip) mean += v;
        mean /= Math.max(1, clip.length);

        double[] x = new double[clip.length];
        for (int i = 0; i < clip.length; i++) x[i] = clip[i] - mean;
        for (double[] s : SOS) {
            // transposed direct form II, as scipy.signal.sosfilt
            double z1 = 0, z2 = 0;
            for (int i = 0; i < x.length; i++) {
                double in = x[i];
                double out = s[0] * in + z1;
                z1 = s[1] * in - s[4] * out + z2;
                z2 = s[2] * in - s[5] * out;
                x[i] = out;
            }
        }
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) y[i] = (float) x[i];
        return y;
    }
}

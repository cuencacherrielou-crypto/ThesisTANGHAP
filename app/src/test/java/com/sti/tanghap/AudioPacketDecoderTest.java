package com.sti.tanghap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ble.ble.AudioPacketDecoder;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

public class AudioPacketDecoderTest {

    /** Line-by-line port of the firmware's encoder (tanghap_esp32_wroom.ino: sendAudioSample). */
    private static final class FirmwareEncoder {
        static final int MAX_BYTES = 244, HEADER = 9, MAX_COUNT = 1600, ESC = 16;
        final List<byte[]> sent = new ArrayList<>();
        final byte[] packet = new byte[MAX_BYTES];
        long streamPos = 0;
        int count = 0, bits = 0, prev = 0, k = 4, accCount = 0, bytePos = 0;
        long diffSum = 0, acc = 0;

        void putBits(long v, int n) {
            acc = (acc << n) | (v & ((1L << n) - 1));
            accCount += n;
            while (accCount >= 8) {
                accCount -= 8;
                packet[bytePos++] = (byte) (acc >> accCount);
            }
            acc &= (1L << accCount) - 1;
        }

        void start(int first) {
            packet[0] = (byte) streamPos; packet[1] = (byte) (streamPos >> 8);
            packet[2] = (byte) (streamPos >> 16); packet[3] = (byte) (streamPos >> 24);
            packet[6] = (byte) k;
            packet[7] = (byte) first; packet[8] = (byte) (first >> 8);
            bytePos = HEADER; acc = 0; accCount = 0; count = 1; bits = 0; diffSum = 0; prev = first;
        }

        void send() {
            if (accCount > 0) packet[bytePos++] = (byte) (acc << (8 - accCount));
            packet[4] = (byte) count; packet[5] = (byte) (count >> 8);
            sent.add(Arrays.copyOf(packet, bytePos));
            streamPos += count;
            if (count > 1) {
                long mean = diffSum / (count - 1);
                k = 0;
                while (k < 14 && (1L << (k + 1)) <= mean + 1) k++;
            }
            count = 0;
        }

        void sample(int s) {
            if (count == 0) { start(s); return; }
            int r = s - prev;
            long u = r >= 0 ? 2L * r : -2L * r - 1;
            long q = u >> k;
            int b = q >= ESC ? ESC + 17 : (int) q + 1 + k;
            if (bits + b > (MAX_BYTES - HEADER) * 8 || count >= MAX_COUNT) {
                send();
                start(s);
                return;
            }
            if (q >= ESC) {
                putBits((1L << ESC) - 1, ESC);
                putBits(u, 17);
            } else {
                putBits(((1L << q) - 1) << 1, (int) q + 1);
                if (k > 0) putBits(u, k);
            }
            bits += b; diffSum += u; count++; prev = s;
        }

        List<byte[]> encode(short[] x) {
            for (short s : x) sample(s);
            if (count > 0) send();             // the test flushes the last partial packet
            return sent;
        }
    }

    /** The signal of the Python reference check (tanghap_ml/tools/rice.py). */
    private static short[] referenceSignal() {
        short[] x = new short[8000];
        long r = 12345;
        for (int i = 0; i < x.length; i++) {
            r = (r * 1103515245L + 12345) & 0x7fffffffL;
            int tri = (i * 37) % 4000 - 2000;
            int v = tri * 8 + (int) ((r >> 16) % 2001 - 1000) + (i >= 1000 && i < 1100 ? 20000 : 0);
            if (i >= 2000 && i < 2050) v = i % 2 == 1 ? 30000 : -30000;   // forces escape codes
            if (i >= 5000 && i < 7000) v = (int) ((r >> 16) % 7 - 3);     // near silence
            x[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return x;
    }

    private static byte[] pcmOf(short[] x) {
        byte[] b = new byte[x.length * 2];
        for (int i = 0; i < x.length; i++) { b[2 * i] = (byte) x[i]; b[2 * i + 1] = (byte) (x[i] >> 8); }
        return b;
    }

    private static byte[] decodeAll(AudioPacketDecoder dec, List<byte[]> packets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : packets) {
            byte[] pcm = dec.decode(p);
            out.write(pcm, 0, pcm.length);
        }
        return out.toByteArray();
    }

    @Test
    public void firmwarePacketsMatchThePythonReferenceByteForByte() {
        List<byte[]> packets = new FirmwareEncoder().encode(referenceSignal());
        CRC32 crc = new CRC32();
        int bytes = 0;
        for (byte[] p : packets) { crc.update(p); bytes += p.length; }
        // Printed by rice.Encoder on the same signal
        assertEquals(46, packets.size());
        assertEquals(11126, bytes);
        assertEquals(2619992802L, crc.getValue());
        for (byte[] p : packets) assertTrue(p.length <= 244);
    }

    @Test
    public void decodingIsLossless() {
        short[] x = referenceSignal();
        assertArrayEquals(pcmOf(x), decodeAll(new AudioPacketDecoder(), new FirmwareEncoder().encode(x)));
    }

    @Test
    public void extremeSignalsStayLosslessAndFitOneNotification() {
        short[] noise = new short[16000];
        short[] square = new short[16000];
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (short) (rnd.nextInt(65536) - 32768);
            square[i] = (short) ((i / 8) % 2 == 0 ? 32767 : -32768);
        }
        for (short[] x : new short[][]{noise, square, new short[16000]}) {
            List<byte[]> packets = new FirmwareEncoder().encode(x);
            for (byte[] p : packets) assertTrue(p.length <= 244);
            assertArrayEquals(pcmOf(x), decodeAll(new AudioPacketDecoder(), packets));
        }
    }

    @Test
    public void lostPacketIsFilledToItsExactLengthWithQuietAudio() {
        short[] x = referenceSignal();
        List<byte[]> packets = new FirmwareEncoder().encode(x);
        AudioPacketDecoder dec = new AudioPacketDecoder();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int lostIndex = 30;                                  // inside the near-silent part
        int lostSamples = 0;
        for (int i = 0; i < packets.size(); i++) {
            byte[] p = packets.get(i);
            if (i == lostIndex) { lostSamples = (p[4] & 0xFF) | (p[5] & 0xFF) << 8; continue; }
            byte[] pcm = dec.decode(p);
            if (i == lostIndex + 1) assertEquals(lostSamples, dec.getLastGapSamples());
            out.write(pcm, 0, pcm.length);
        }
        assertTrue(lostSamples > 0);
        assertEquals(lostSamples, dec.getLostSamples());
        assertEquals(x.length * 2, out.size());               // timing kept: same total length

        // Everything after the gap is exactly the original audio again
        byte[] got = out.toByteArray();
        byte[] want = pcmOf(x);
        int afterGap = 0;
        for (int i = 0; i <= lostIndex; i++) afterGap += (packets.get(i)[4] & 0xFF) | (packets.get(i)[5] & 0xFF) << 8;
        assertArrayEquals(Arrays.copyOfRange(want, afterGap * 2, want.length),
                Arrays.copyOfRange(got, afterGap * 2, got.length));
    }

    @Test
    public void deviceRestartIsNotFilled() {
        short[] x = new short[4000];
        List<byte[]> first = new FirmwareEncoder().encode(x);
        List<byte[]> again = new FirmwareEncoder().encode(x);   // counter starts at 0 again
        AudioPacketDecoder dec = new AudioPacketDecoder();
        decodeAll(dec, first);
        byte[] pcm = dec.decode(again.get(0));
        assertEquals(((again.get(0)[4] & 0xFF) | (again.get(0)[5] & 0xFF) << 8) * 2, pcm.length);
        assertEquals(0, dec.getLostSamples());
    }

    @Test
    public void corruptPacketsAreRejected() {
        AudioPacketDecoder dec = new AudioPacketDecoder();
        assertNull(dec.decode(null));
        assertNull(dec.decode(new byte[5]));
        byte[] good = new FirmwareEncoder().encode(referenceSignal()).get(0);
        assertNull(dec.decode(Arrays.copyOf(good, good.length - 1)));      // truncated
        assertNull(dec.decode(Arrays.copyOf(good, good.length + 3)));      // trailing junk
        byte[] badK = good.clone();
        badK[6] = 20;
        assertNull(dec.decode(badK));
    }
}

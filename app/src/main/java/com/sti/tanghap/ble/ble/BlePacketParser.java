package com.sti.tanghap.ble.ble;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
public final class BlePacketParser {
    public static class PressurePacket {
        public long sequence;
        public List<Float> kpaSamples;
    }
    public static class ActuationEvent {
        public long unixMillis;
    }
    private BlePacketParser(){}
    // Pressure packet:
    // uint32 sequence
    // uint16 sampleCount
    // sampleCount * int16 pressure_x100  (12.34 kPa => 1234)
    public static PressurePacket parsePressure(byte[] bytes) {
        if (bytes == null || bytes.length < 6) return null;
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long seq = Integer.toUnsignedLong(bb.getInt());
        int count = Short.toUnsignedInt(bb.getShort());
        if (count <= 0) return null;
        int needed = 6 + count * 2;
        if (bytes.length < needed) return null;
        List<Float> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            short v = bb.getShort();
            float kpa = v / 100.0f;
            samples.add(kpa);
        }
        PressurePacket pkt = new PressurePacket();
        pkt.sequence = seq;
        pkt.kpaSamples = samples;
        return pkt;
    }
    // Actuation packet:
    // uint64 unixMillis
    public static ActuationEvent parseActuation(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return null;
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long ts = bb.getLong();
        ActuationEvent ev = new ActuationEvent();
        ev.unixMillis = ts;
        return ev;
    }
    // Battery:
    // uint8 percent
    public static Integer parseBattery(byte[] bytes) {
        if (bytes == null || bytes.length < 1) return null;
        return Byte.toUnsignedInt(bytes[0]);
    }
    // Device info:
    // UTF-8 "deviceId|fwVersion"
    public static String parseDeviceInfo(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        return new String(bytes);
    }
}

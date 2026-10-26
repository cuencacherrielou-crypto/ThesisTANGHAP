package com.sti.tanghap.ble.ble;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
public final class BlePacketParser {
    public static class ActuationEvent {
        public long unixMillis;
    }
    private BlePacketParser(){}
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

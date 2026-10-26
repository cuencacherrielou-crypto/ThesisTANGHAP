package com.sti.tanghap.ble.ble;
import java.util.UUID;
public final class BleUuids {
    private BleUuids(){}
    // CHANGE THESE to match your ESP32 firmware if needed
    public static final UUID TANGHAP_SERVICE_UUID =
            UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb");
    // AB01 (air pressure) is no longer used: the air pressure sensor was removed from the design,
    // and the breathing pattern now comes from the microphone. The app does not subscribe to it.
    // INMP441 microphone audio (16 kHz PCM) for the on-device cough model
    public static final UUID AUDIO_STREAM_CHAR =
            UUID.fromString("0000ab02-0000-1000-8000-00805f9b34fb");
    // The same audio losslessly compressed (about 18 KB/s instead of 32), sent by newer firmware;
    // see AudioPacketDecoder
    public static final UUID AUDIO_COMPRESSED_CHAR =
            UUID.fromString("0000ab04-0000-1000-8000-00805f9b34fb");
    public static final UUID ACTUATION_EVENT_CHAR =
            UUID.fromString("0000ab03-0000-1000-8000-00805f9b34fb");
    public static final UUID BATTERY_CHAR =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    public static final UUID DEVICE_INFO_CHAR =
            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb");
    // Standard CCCD descriptor UUID to enable notifications
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
}
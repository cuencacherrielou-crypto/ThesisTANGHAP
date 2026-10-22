package com.sti.tanghap.ble.ble;
import java.util.List;
public interface BleListener {
    void onScanResults(List<BleDeviceItem> results);
    void onConnected(String deviceName);
    void onDisconnected();
    /** @param sequence the packet counter from the device, used to detect lost packets */
    void onPressureSamples(long sequence, float[] kpaSamples);
    void onActuationEvent(long tsMillis);
    void onBattery(Integer percent);
    void onDeviceInfo(String info);
    void onAudioDataReceived(byte[] data);
    void onError(String message);
}
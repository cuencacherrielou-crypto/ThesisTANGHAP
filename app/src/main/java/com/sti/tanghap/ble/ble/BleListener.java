package com.sti.tanghap.ble.ble;
import java.util.List;
public interface BleListener {
    void onScanResults(List<BleDeviceItem> results);
    void onConnected(String deviceName);
    void onDisconnected();
    void onPressureSamples(float[] kpaSamples);
    void onCoughEvent(long tsMillis, int intensity);
    void onActuationEvent(long tsMillis);
    void onBattery(Integer percent);
    void onDeviceInfo(String info);
    void onAudioDataReceived(byte[] data);
    void onError(String message);
}
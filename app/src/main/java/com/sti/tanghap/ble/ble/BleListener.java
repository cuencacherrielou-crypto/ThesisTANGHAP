package com.sti.tanghap.ble.ble;
import java.util.List;
public interface BleListener {
    void onScanResults(List<BleDeviceItem> results);
    void onConnected(String deviceName);
    void onDisconnected();
    /** Audio was lost over Bluetooth and filled in; called before the audio that follows the gap. */
    void onAudioGap(int lostMs);
    void onActuationEvent(long tsMillis);
    void onBattery(Integer percent);
    void onDeviceInfo(String info);
    void onAudioDataReceived(byte[] data);
    void onError(String message);
}
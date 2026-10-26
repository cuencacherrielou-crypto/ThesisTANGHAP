package com.sti.tanghap.ble.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BleManager {

    private static final String TAG = "TANGHAP_BLE";

    /** Bluetooth MAC of the TANGHAP ESP32-WROOM board, used when no device has been selected yet. */
    public static final String TANGHAP_DEVICE_ADDRESS = "20:50:0D:D3:1D:F2";

    private final Context ctx;
    private final BleListener listener;
    private final BluetoothAdapter adapter;

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;

    private BluetoothGattCharacteristic pressureChar;
    private BluetoothGattCharacteristic audioChar;
    private BluetoothGattCharacteristic actChar;
    private BluetoothGattCharacteristic batteryChar;
    private BluetoothGattCharacteristic deviceInfoChar;

    // Newer firmware sends the audio losslessly compressed (AB04); older firmware as raw PCM (AB02)
    private final AudioPacketDecoder audioDecoder = new AudioPacketDecoder();

    private final Map<String, BleDeviceItem> scanMap = new ConcurrentHashMap<>();

    private ScanCallback scanCallback;

    // Prevent duplicate actuation events
    private long lastActuationMillis = 0;

    // Prevents rapid UI redraws during BLE scan
    private long lastScanUiUpdate = 0;

    private final Queue<BluetoothGattCharacteristic> notifyQueue = new ArrayDeque<>();

    // GATT allows one operation at a time: discover services only after the MTU exchange finishes
    private static final long MTU_TIMEOUT_MS = 1500;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothGatt discoveryGatt;
    private boolean discoveryStarted = false;

    // Device we were connected to before; reconnecting to it waits for it instead of timing out
    private volatile String lastConnectedAddress;


    // =========================================================
    // CONSTRUCTOR
    // =========================================================

    public BleManager(Context ctx, BleListener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;

        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
    }


    // =========================================================
    // PERMISSIONS
    // =========================================================

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }


    // =========================================================
    // BLE SCANNING (THROTTLED FOR SMOOTH TOUCH INTERACTION)
    // =========================================================

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (adapter == null) {
            listener.onError("Bluetooth not supported on this device.");
            return;
        }

        if (!isBluetoothEnabled()) {
            listener.onError("Bluetooth is OFF. Turn it ON.");
            return;
        }

        if (!hasScanPermission()) {
            listener.onError("Missing Bluetooth/Location permission for scanning.");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onError("BLE scanner not available.");
            return;
        }

        scanMap.clear();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = "Unknown";

                if (hasConnectPermission()) {
                    try {
                        String deviceName = device.getName();
                        if (deviceName != null) {
                            name = deviceName;
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "Cannot read device name", e);
                    }
                }

                boolean isTanghap = name.toUpperCase(java.util.Locale.ROOT).contains("TANGHAP");
                boolean hasService = false;

                if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                    for (ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                        if (uuid.getUuid().equals(BleUuids.TANGHAP_SERVICE_UUID)) {
                            hasService = true;
                            break;
                        }
                    }
                }

                if (isTanghap || hasService) {
                    boolean isNewDevice = !scanMap.containsKey(device.getAddress());
                    BleDeviceItem item = new BleDeviceItem(name, device.getAddress(), result.getRssi());
                    scanMap.put(device.getAddress(), item);

                    long now = System.currentTimeMillis();
                    // Ina-update lamang ang UI kapag BAGONG device o bawat 1 segundo para hindi ma-cancel ang click touch event
                    if (isNewDevice || (now - lastScanUiUpdate > 1000)) {
                        lastScanUiUpdate = now;
                        listener.onScanResults(new ArrayList<>(scanMap.values()));
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                listener.onError("Scan failed: " + errorCode);
                Log.e(TAG, "BLE scan failed: " + errorCode);
            }
        };

        Log.d(TAG, "Starting BLE scan...");
        scanner.startScan(null, settings, scanCallback);
    }


    // =========================================================
    // STOP SCAN
    // =========================================================

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner != null && scanCallback != null && hasScanPermission()) {
            scanner.stopScan(scanCallback);
            Log.d(TAG, "BLE scan stopped.");
        }
    }


    // =========================================================
    // CONNECT & DISCONNECT
    // =========================================================

    /**
     * Starts connecting. Returns false when no attempt could start (Bluetooth off, no
     * permission or a bad address); a started attempt ends in onConnected or onDisconnected.
     */
    @SuppressLint("MissingPermission")
    public boolean connect(String address) {
        if (!isBluetoothEnabled()) {
            listener.onError("Bluetooth is OFF. Turn it on to continue monitoring.");
            return false;
        }

        if (!hasConnectPermission()) {
            listener.onError("Missing Bluetooth connection permission.");
            return false;
        }

        stopScan();

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            listener.onError("Invalid device address. Please scan for the device again.");
            return false;
        }
        Log.d(TAG, "Connecting to: " + address);

        // Close any previous connection object first; Android allows only a few at a time
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        // First connection: direct (fast). After a drop: autoConnect, so Android keeps waiting and
        // reconnects as soon as the device is back in range instead of failing after ~30 s.
        boolean autoConnect = address.equals(lastConnectedAddress);
        gatt = device.connectGatt(
                ctx,
                autoConnect,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
        );
        return gatt != null;
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (gatt != null && hasConnectPermission()) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
            }
        }
    }


    // =========================================================
    // GATT CALLBACK
    // =========================================================

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.d(TAG, "Connection state changed. Status=" + status + " (" + describeStatus(status)
                    + ") State=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g;
                lastConnectedAddress = g.getDevice().getAddress();
                lastActuationMillis = 0; // Reset debounce timer

                // The audio stream needs a short connection interval; on the default interval the
                // link backs up and the connection times out even with the device close by
                if (hasConnectPermission()) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                }

                synchronized (BleManager.this) {
                    discoveryGatt = g;
                    discoveryStarted = false;
                }

                String deviceName = "TANGHAP";
                if (hasConnectPermission()) {
                    try {
                        String tempName = g.getDevice().getName();
                        if (tempName != null) {
                            deviceName = tempName;
                        }
                    } catch (SecurityException ignored) {
                    }
                }

                Log.d(TAG, "Connected to " + deviceName);
                listener.onConnected(deviceName);

                // Request mas mataas na MTU para sa INMP441 Audio Stream.
                // Service discovery starts in onMtuChanged (or after a timeout if no reply comes).
                if (hasConnectPermission() && g.requestMtu(247)) {
                    mainHandler.postDelayed(() -> discoverServicesOnce(g), MTU_TIMEOUT_MS);
                } else {
                    discoverServicesOnce(g);
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "BLE disconnected.");
                listener.onDisconnected();
                notifyQueue.clear();
                synchronized (BleManager.this) {
                    if (discoveryGatt == g) discoveryGatt = null;
                }

                try {
                    g.close();
                } catch (Exception ignored) {
                }

                if (gatt == g) {
                    gatt = null;
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "BLE MTU Size Changed to: " + mtu + " (Status: " + status + ")");
            discoverServicesOnce(gatt);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            Log.d(TAG, "Services discovered. Status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("BLE service discovery failed.");
                return;
            }

            BluetoothGattService service = g.getService(BleUuids.TANGHAP_SERVICE_UUID);

            if (service == null) {
                listener.onError("TANGHAP service not found.");
                Log.e(TAG, "TANGHAP SERVICE NOT FOUND");
                return;
            }

            Log.d(TAG, "TANGHAP service found.");

            pressureChar = service.getCharacteristic(BleUuids.PRESSURE_STREAM_CHAR);
            audioChar = service.getCharacteristic(BleUuids.AUDIO_COMPRESSED_CHAR);
            if (audioChar == null) audioChar = service.getCharacteristic(BleUuids.AUDIO_STREAM_CHAR); // older firmware: raw PCM
            Log.d(TAG, "Audio stream: " + (audioChar == null ? "none"
                    : BleUuids.AUDIO_COMPRESSED_CHAR.equals(audioChar.getUuid()) ? "compressed (AB04)" : "raw PCM (AB02)"));
            audioDecoder.reset();
            actChar = service.getCharacteristic(BleUuids.ACTUATION_EVENT_CHAR);
            batteryChar = service.getCharacteristic(BleUuids.BATTERY_CHAR);
            deviceInfoChar = service.getCharacteristic(BleUuids.DEVICE_INFO_CHAR);

            setupNotifications(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "CCCD write finished: " + descriptor.getCharacteristic().getUuid() + " status=" + status);
            enableNextNotification(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            handleCharacteristicChanged(c, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            handleCharacteristicChanged(c, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;

            UUID uuid = c.getUuid();
            byte[] value = c.getValue();

            if (BleUuids.BATTERY_CHAR.equals(uuid)) {
                listener.onBattery(BlePacketParser.parseBattery(value));
                readDeviceInfo(g);
            } else if (BleUuids.DEVICE_INFO_CHAR.equals(uuid)) {
                listener.onDeviceInfo(BlePacketParser.parseDeviceInfo(value));
            }
        }
    };


    /** Readable reason for a connection change, so logcat shows why a drop happened. */
    private static String describeStatus(int status) {
        switch (status) {
            case 0: return "OK / disconnect requested";
            case 8: return "link timeout: out of range, interference or device stopped responding";
            case 19: return "device ended the connection (ESP32 disconnected or reset)";
            case 22: return "phone ended the connection";
            case 62: return "connection could not be established";
            case 133: return "GATT error (connect failed, try again)";
            default: return "status " + status;
        }
    }

    @SuppressLint("MissingPermission")
    private void discoverServicesOnce(BluetoothGatt g) {
        synchronized (this) {
            // Ignore stale timeouts from an earlier connection, and run discovery only once
            if (discoveryStarted || g != discoveryGatt) return;
            discoveryStarted = true;
        }
        if (hasConnectPermission()) {
            Log.d(TAG, "Discovering services...");
            if (!g.discoverServices()) {
                listener.onError("BLE service discovery could not start.");
            }
        }
    }


    // =========================================================
    // HANDLE SENSOR NOTIFICATIONS
    // =========================================================

    private void handleCharacteristicChanged(BluetoothGattCharacteristic c, byte[] value) {
        if (c == null) return;

        UUID uuid = c.getUuid();

        // 1. PRESSURE DATA
        if (BleUuids.PRESSURE_STREAM_CHAR.equals(uuid)) {
            BlePacketParser.PressurePacket pkt = BlePacketParser.parsePressure(value);
            if (pkt != null && pkt.kpaSamples != null) {
                float[] arr = new float[pkt.kpaSamples.size()];
                for (int i = 0; i < pkt.kpaSamples.size(); i++) {
                    arr[i] = pkt.kpaSamples.get(i);
                }
                listener.onPressureSamples(pkt.sequence, arr);
            }

            // 2. INMP441 AUDIO STREAM DATA (AB04 compressed, or AB02 raw PCM from older firmware)
        } else if (BleUuids.AUDIO_COMPRESSED_CHAR.equals(uuid)) {
            byte[] pcm = audioDecoder.decode(value);
            if (pcm == null) {
                Log.w(TAG, "Corrupt audio packet ignored (" + (value == null ? 0 : value.length) + " bytes)");
            } else {
                int gap = audioDecoder.getLastGapSamples();
                if (gap > 0) {
                    long lost = audioDecoder.getLostSamples();
                    Log.d(TAG, String.format(java.util.Locale.ROOT,
                            "Audio lost: %d ms (filled); total %.1f%% of %d s",
                            gap / 16, 100.0 * lost / (lost + audioDecoder.getReceivedSamples()),
                            (lost + audioDecoder.getReceivedSamples()) / 16000));
                }
                listener.onAudioDataReceived(pcm);
            }
        } else if (BleUuids.AUDIO_STREAM_CHAR.equals(uuid)) {
            if (value != null && value.length > 0) {
                listener.onAudioDataReceived(value);
            }

            // 3. HALL SENSOR / ACTUATION LOGIC
        } else if (BleUuids.ACTUATION_EVENT_CHAR.equals(uuid)) {
            StringBuilder hex = new StringBuilder();
            if (value != null) for (byte b : value) hex.append(String.format("%02X ", b));
            Log.d(TAG, "ACTUATION NOTIFICATION RECEIVED FROM ESP32! len="
                    + (value == null ? 0 : value.length) + " bytes=" + hex);

            long currentPhoneTime = System.currentTimeMillis();

            if (currentPhoneTime - lastActuationMillis > 300) {
                lastActuationMillis = currentPhoneTime;

                Log.d(TAG, ">>> CALLING onActuationEvent() UI UPDATE! <<<");
                listener.onActuationEvent(currentPhoneTime);
            }

            // 4. BATTERY
        } else if (BleUuids.BATTERY_CHAR.equals(uuid)) {
            Integer battery = BlePacketParser.parseBattery(value);
            listener.onBattery(battery);
        }
    }


    // =========================================================
    // SET UP NOTIFICATIONS QUEUE
    // =========================================================

    private void setupNotifications(BluetoothGatt g) {
        notifyQueue.clear();

        if (pressureChar != null) notifyQueue.add(pressureChar);
        if (audioChar != null) notifyQueue.add(audioChar);
        if (actChar != null) notifyQueue.add(actChar);
        if (batteryChar != null) notifyQueue.add(batteryChar);

        enableNextNotification(g);
    }

    @SuppressLint("MissingPermission")
    private void enableNextNotification(BluetoothGatt g) {
        if (!hasConnectPermission()) {
            listener.onError("Missing Bluetooth connection permission.");
            return;
        }

        BluetoothGattCharacteristic c = notifyQueue.poll();

        if (c == null) {
            Log.d(TAG, "All BLE notifications configured successfully.");
            readInitialCharacteristics(g);
            return;
        }

        UUID uuid = c.getUuid();
        Log.d(TAG, "Enabling notification for: " + uuid);

        g.setCharacteristicNotification(c, true);

        BluetoothGattDescriptor descriptor = c.getDescriptor(BleUuids.CLIENT_CHARACTERISTIC_CONFIG);
        if (descriptor == null) {
            Log.w(TAG, "No CCCD found for: " + uuid);
            enableNextNotification(g);
            return;
        }

        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean started = g.writeDescriptor(descriptor);

        if (!started) {
            enableNextNotification(g);
        }
    }


    // =========================================================
    // READ INITIAL CHARACTERISTICS
    // =========================================================

    @SuppressLint("MissingPermission")
    private void readInitialCharacteristics(BluetoothGatt g) {
        if (!hasConnectPermission()) return;

        if (batteryChar != null) {
            Log.d(TAG, "Reading battery...");
            if (g.readCharacteristic(batteryChar)) return;
        }

        readDeviceInfo(g);
    }

    @SuppressLint("MissingPermission")
    private void readDeviceInfo(BluetoothGatt g) {
        if (!hasConnectPermission()) return;

        if (deviceInfoChar != null) {
            Log.d(TAG, "Reading device info...");
            g.readCharacteristic(deviceInfoChar);
        }
    }
}
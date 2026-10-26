package com.sti.tanghap.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.sti.tanghap.R;
import com.sti.tanghap.ble.ble.BleDeviceItem;
import com.sti.tanghap.ble.ble.BleListener;
import com.sti.tanghap.ble.ble.BleManager;
import com.sti.tanghap.ui.adapter.DeviceAdapter;
import java.util.ArrayList;
import java.util.List;

public class DeviceScanActivity extends AppCompatActivity implements BleListener {
    private static final String TAG = "DeviceScanActivity";
    private BleManager ble;
    private DeviceAdapter adapter;
    private final List<BleDeviceItem> deviceList = new ArrayList<>();
    private boolean isConnecting = false; // Flag para maiwasan ang double click

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_scan);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        ble = new BleManager(this, this);

        RecyclerView rv = findViewById(R.id.rvDevices);
        rv.setLayoutManager(new LinearLayoutManager(this));

        // Setup Device Adapter at Mabilis na Click Listener
        adapter = new DeviceAdapter(deviceList, selectedDevice -> {
            if (isConnecting) return; // Pigilan ang paulit-ulit na click habang nag-poproseso

            if (selectedDevice != null && selectedDevice.getAddress() != null) {
                isConnecting = true;

                // 1. I-STOP AGAD ANG SCAN PARA MA-UNLOCK ANG UI AT BLE STACK
                if (ble != null) {
                    ble.stopScan();
                }

                String macAddress = selectedDevice.getAddress();
                Log.d(TAG, "Selected BLE Device: " + selectedDevice.name + " | Address: " + macAddress);

                Toast.makeText(this, "Connecting to " + (selectedDevice.name != null ? selectedDevice.name : "Inhaler") + "...", Toast.LENGTH_SHORT).show();

                // 2. IPASA ANG MAC ADDRESS PAPUNTA SA LIVESESSIONACTIVITY
                Intent intent = new Intent(DeviceScanActivity.this, LiveSessionActivity.class);
                intent.putExtra("deviceAddress", macAddress);
                startActivity(intent);
                finish();
            } else {
                Toast.makeText(this, "Invalid device selected. Try scanning again.", Toast.LENGTH_SHORT).show();
            }
        });
        rv.setAdapter(adapter);

        findViewById(R.id.btnScan).setOnClickListener(v -> {
            isConnecting = false;
            ensurePermThenScan();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        isConnecting = false;
        // Kusa nang mag-i-scan pagkabukas ng screen kung may permission na
        if (hasPermissions()) {
            startBleScanning();
        }
    }

    private boolean hasPermissions() {
        if (!ble.isBluetoothEnabled()) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void ensurePermThenScan() {
        if (!ble.isBluetoothEnabled()) {
            Toast.makeText(this, "Turn ON Bluetooth first.", Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, 1001);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, 1002);
        }
    }

    private void startBleScanning() {
        if (ble != null && ble.isBluetoothEnabled()) {
            deviceList.clear();
            adapter.notifyDataSetChanged();
            ble.startScan();
            Toast.makeText(this, "Searching for inhalers...", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = true;
        if (grantResults.length > 0) {
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) granted = false;
            }
        } else {
            granted = false;
        }

        if (granted) {
            startBleScanning();
        } else {
            Toast.makeText(this, "Permissions required to find devices.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onScanResults(List<BleDeviceItem> results) {
        runOnUiThread(() -> {
            deviceList.clear();
            if (results != null) {
                deviceList.addAll(results);
            }
            adapter.notifyDataSetChanged();
        });
    }

    @Override public void onConnected(String deviceName) {}
    @Override public void onDisconnected() {}
    @Override public void onAudioGap(int lostMs) {}
    @Override public void onActuationEvent(long tsMillis) {}
    @Override public void onBattery(Integer percent) {}
    @Override public void onDeviceInfo(String info) {}
    @Override public void onAudioDataReceived(byte[] data) {}
    @Override public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ble != null) ble.stopScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ble != null) ble.stopScan();
    }
}
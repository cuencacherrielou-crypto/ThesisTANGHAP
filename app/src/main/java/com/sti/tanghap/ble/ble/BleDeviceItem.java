package com.sti.tanghap.ble.ble;
public class BleDeviceItem {
    public String name;
    public String address;
    public int rssi;
    public BleDeviceItem(String name, String address, int rssi){
        this.name = name;
        this.address = address;
        this.rssi = rssi;
    }

    public String getAddress() {
        return address;
    }
}
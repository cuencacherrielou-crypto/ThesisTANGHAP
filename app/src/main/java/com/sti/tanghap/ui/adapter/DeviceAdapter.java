package com.sti.tanghap.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.sti.tanghap.R;
import com.sti.tanghap.ble.ble.BleDeviceItem;
import java.util.List;
import java.util.Locale;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {
    private List<BleDeviceItem> devices;
    private final OnDeviceClickListener listener;

    public interface OnDeviceClickListener {
        void onDeviceClick(BleDeviceItem device);
    }

    public DeviceAdapter(List<BleDeviceItem> devices, OnDeviceClickListener listener) {
        this.devices = devices;
        this.listener = listener;
    }

    public void setDevices(List<BleDeviceItem> devices) {
        this.devices = devices;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ble_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BleDeviceItem device = devices.get(position);
        holder.tvName.setText(device.name != null ? device.name : "Unknown Device");
        holder.tvAddress.setText(device.address);
        holder.tvRssi.setText(String.format(Locale.getDefault(), "%d dBm", device.rssi));

        // Direct tap with instant disable to prevent touch cancellation and rapid multiple clicks
        holder.itemView.setOnClickListener(v -> {
            v.setEnabled(false); // Sanhi para maging smooth at hindi mag-double trigger
            if (listener != null) {
                listener.onDeviceClick(device);
            }
            v.postDelayed(() -> v.setEnabled(true), 1000);
        });
    }

    @Override
    public int getItemCount() {
        return devices == null ? 0 : devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvAddress, tvRssi;

        ViewHolder(View view) {
            super(view);
            tvName = view.findViewById(R.id.tvDeviceName);
            tvAddress = view.findViewById(R.id.tvDeviceAddress);
            tvRssi = view.findViewById(R.id.tvRssi);
        }
    }
}
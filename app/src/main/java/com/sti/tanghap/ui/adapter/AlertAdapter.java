package com.sti.tanghap.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.sti.tanghap.R;
import com.sti.tanghap.domain.AlertEngine;
import java.util.List;

public class AlertAdapter extends RecyclerView.Adapter<AlertAdapter.ViewHolder> {
    private final List<AlertEngine.Alert> alerts;

    public AlertAdapter(List<AlertEngine.Alert> alerts) {
        this.alerts = alerts;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_alert, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AlertEngine.Alert alert = alerts.get(position);
        holder.tvTitle.setText(alert.title);
        holder.tvMessage.setText(alert.message);
        
        int color = alert.severity.equals("WARN") ? 
                holder.itemView.getContext().getResources().getColor(R.color.warning) :
                holder.itemView.getContext().getResources().getColor(R.color.secondary);
        holder.indicator.getBackground().setTint(color);
    }

    @Override
    public int getItemCount() {
        return alerts.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvMessage;
        View indicator;

        ViewHolder(View view) {
            super(view);
            tvTitle = view.findViewById(R.id.tvTitle);
            tvMessage = view.findViewById(R.id.tvMessage);
            indicator = view.findViewById(R.id.indicator);
        }
    }
}
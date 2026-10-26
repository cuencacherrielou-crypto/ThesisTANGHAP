package com.sti.tanghap.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.sti.tanghap.R;
import com.sti.tanghap.data.SessionEntity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.ViewHolder> {
    private List<SessionEntity> sessions;
    private final OnSessionClickListener listener;
    private final SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault());

    public interface OnSessionClickListener {
        void onSessionClick(SessionEntity session);
    }

    public SessionAdapter(List<SessionEntity> sessions, OnSessionClickListener listener) {
        this.sessions = sessions;
        this.listener = listener;
    }

    public void setSessions(List<SessionEntity> sessions) {
        this.sessions = sessions;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SessionEntity session = sessions.get(position);
        holder.tvDate.setText(sdf.format(new Date(session.startTimeMillis)));
        
        // Breathing pattern (a label from the breathing sounds, never a number) and coughs
        String pattern = session.detectedPattern != null ? session.detectedPattern : "Breathing not measured";
        holder.tvSummary.setText(pattern + " | " + session.coughCount + (session.coughCount == 1 ? " cough" : " coughs"));
        
        // Color code based on pattern
        int color = R.color.success;
        if (pattern.contains("Tachypnea") || pattern.contains("Apnea") || pattern.contains("Kussmaul")) {
            color = R.color.error;
        } else if (pattern.contains("Bradypnea")) {
            color = R.color.warning;
        }
        holder.tvSummary.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), color));

        holder.tvStatus.setVisibility(session.synced ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(v -> listener.onSessionClick(session));
    }

    @Override
    public int getItemCount() {
        return sessions == null ? 0 : sessions.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDate, tvSummary, tvStatus;

        ViewHolder(View view) {
            super(view);
            tvDate = view.findViewById(R.id.tvDate);
            tvSummary = view.findViewById(R.id.tvSummary);
            tvStatus = view.findViewById(R.id.tvStatus);
        }
    }
}
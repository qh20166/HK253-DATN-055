package com.example.traffigo.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.traffigo.R;

import java.util.List;

/** Danh sách các tuyến đường đã đi (Lịch sử lộ trình), mới nhất lên đầu. */
public class RouteHistoryAdapter extends RecyclerView.Adapter<RouteHistoryAdapter.VH> {

    public static class HistoryEntry {
        public final String originAddress, destAddress, relativeTime;
        public final double originLat, originLng, destLat, destLng;

        public HistoryEntry(String originAddress, double originLat, double originLng,
                             String destAddress, double destLat, double destLng, String relativeTime) {
            this.originAddress = originAddress;
            this.originLat = originLat;
            this.originLng = originLng;
            this.destAddress = destAddress;
            this.destLat = destLat;
            this.destLng = destLng;
            this.relativeTime = relativeTime;
        }
    }

    public interface OnHistoryClickListener {
        void onHistoryClick(HistoryEntry entry);
    }

    private final List<HistoryEntry> items;
    private final OnHistoryClickListener listener;

    public RouteHistoryAdapter(List<HistoryEntry> items, OnHistoryClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_route_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        HistoryEntry entry = items.get(position);
        holder.origin.setText(entry.originAddress);
        holder.dest.setText(entry.destAddress);
        holder.time.setText(entry.relativeTime);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onHistoryClick(entry);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView origin, dest, time;

        VH(@NonNull View itemView) {
            super(itemView);
            origin = itemView.findViewById(R.id.tvHistoryOrigin);
            dest = itemView.findViewById(R.id.tvHistoryDest);
            time = itemView.findViewById(R.id.tvHistoryTime);
        }
    }
}

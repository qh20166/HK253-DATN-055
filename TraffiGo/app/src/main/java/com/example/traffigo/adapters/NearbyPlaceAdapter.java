package com.example.traffigo.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.traffigo.R;
import com.example.traffigo.models.NearbyPlace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Danh sách địa điểm lân cận (Places Nearby Search). Tái dùng item_place_prediction,
 * luôn hiện khoảng cách (khác PlacePredictionAdapter chỉ hiện khi có origin).
 */
public class NearbyPlaceAdapter extends RecyclerView.Adapter<NearbyPlaceAdapter.VH> {

    public interface OnPlaceClickListener {
        void onPlaceClick(NearbyPlace place);
    }

    private final List<NearbyPlace> places = new ArrayList<>();
    private final OnPlaceClickListener listener;

    public NearbyPlaceAdapter(OnPlaceClickListener listener) {
        this.listener = listener;
    }

    public void setPlaces(List<NearbyPlace> newList) {
        places.clear();
        if (newList != null) places.addAll(newList);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_place_prediction, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        NearbyPlace p = places.get(position);
        holder.tvPrimary.setText(p.name);
        holder.tvSecondary.setText(p.address);
        holder.tvDistance.setText(formatDistance(p.distanceMeters));
        holder.tvDistance.setVisibility(View.VISIBLE);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPlaceClick(p);
        });
    }

    @Override
    public int getItemCount() {
        return places.size();
    }

    /** Dưới 1km hiện theo mét, còn lại theo km 1 chữ số thập phân. */
    static String formatDistance(float meters) {
        if (meters < 1000) return Math.round(meters) + " m";
        return String.format(Locale.US, "%.1f km", meters / 1000f);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvPrimary;
        final TextView tvSecondary;
        final TextView tvDistance;

        VH(@NonNull View itemView) {
            super(itemView);
            tvPrimary = itemView.findViewById(R.id.tvPrimary);
            tvSecondary = itemView.findViewById(R.id.tvSecondary);
            tvDistance = itemView.findViewById(R.id.tvDistance);
        }
    }
}

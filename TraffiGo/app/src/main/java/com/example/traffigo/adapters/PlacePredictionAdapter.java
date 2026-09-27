package com.example.traffigo.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.traffigo.R;
import com.google.android.libraries.places.api.model.AutocompletePrediction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hiển thị danh sách gợi ý địa điểm (Places Autocomplete) trong màn hình tìm kiếm tùy biến.
 */
public class PlacePredictionAdapter extends RecyclerView.Adapter<PlacePredictionAdapter.VH> {

    public interface OnPlaceClickListener {
        void onPlaceClick(AutocompletePrediction prediction);
    }

    private final List<AutocompletePrediction> predictions = new ArrayList<>();
    private final OnPlaceClickListener listener;

    public PlacePredictionAdapter(OnPlaceClickListener listener) {
        this.listener = listener;
    }

    public void setPredictions(List<AutocompletePrediction> newList) {
        predictions.clear();
        if (newList != null) predictions.addAll(newList);
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
        AutocompletePrediction p = predictions.get(position);
        holder.tvPrimary.setText(p.getPrimaryText(null));
        holder.tvSecondary.setText(p.getSecondaryText(null));

        // getDistanceMeters chỉ khác null khi request có setOrigin (màn "Địa điểm lân cận").
        Integer dist = p.getDistanceMeters();
        if (dist != null) {
            holder.tvDistance.setText(formatDistance(dist));
            holder.tvDistance.setVisibility(View.VISIBLE);
        } else {
            holder.tvDistance.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPlaceClick(p);
        });
    }

    /** Khoảng cách gọn: dưới 1km hiện theo mét, còn lại theo km 1 chữ số thập phân. */
    static String formatDistance(int meters) {
        if (meters < 1000) return meters + " m";
        return String.format(Locale.US, "%.1f km", meters / 1000f);
    }

    @Override
    public int getItemCount() {
        return predictions.size();
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

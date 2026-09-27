package com.example.traffigo.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.traffigo.R;

import java.util.List;

/**
 * Carousel 3 thẻ hero trên trang chủ: Chỉ đường / Nhà / Công ty.
 * "Chỉ đường" dùng gradient theo màu chủ đạo đã chọn (đổi được trong Cài đặt).
 * "Nhà" và "Công ty" dùng màu cố định riêng (fixedBackgroundRes) để phân biệt, không đổi theo màu chủ đạo.
 */
public class HeroCarouselAdapter extends RecyclerView.Adapter<HeroCarouselAdapter.VH> {

    public static class HeroItem {
        public final int iconRes;
        public final String title;
        public String subtitle; // có thể cập nhật sau khi tải dữ liệu Nhà/Công ty từ Firebase
        public final Runnable onClick;
        public final Integer fixedBackgroundRes; // null = dùng màu chủ đạo chung

        public HeroItem(int iconRes, String title, String subtitle, Runnable onClick) {
            this(iconRes, title, subtitle, onClick, null);
        }

        public HeroItem(int iconRes, String title, String subtitle, Runnable onClick, Integer fixedBackgroundRes) {
            this.iconRes = iconRes;
            this.title = title;
            this.subtitle = subtitle;
            this.onClick = onClick;
            this.fixedBackgroundRes = fixedBackgroundRes;
        }
    }

    private final List<HeroItem> items;
    private int backgroundRes;

    public HeroCarouselAdapter(List<HeroItem> items, int backgroundRes) {
        this.items = items;
        this.backgroundRes = backgroundRes;
    }

    /** Đổi gradient nền theo màu chủ đạo mới (chỉ áp dụng cho thẻ không có fixedBackgroundRes). */
    public void setBackgroundRes(int backgroundRes) {
        this.backgroundRes = backgroundRes;
        notifyItemRangeChanged(0, items.size());
    }

    /** Gọi lại khi dữ liệu Nhà/Công ty đã tải xong từ Firebase để cập nhật tiêu đề phụ. */
    public void refresh() {
        notifyItemRangeChanged(0, items.size());
    }

    public HeroItem getItem(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_hero_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        HeroItem item = items.get(position);
        holder.bgHero.setBackgroundResource(item.fixedBackgroundRes != null ? item.fixedBackgroundRes : backgroundRes);
        holder.icon.setImageResource(item.iconRes);
        holder.watermark.setImageResource(item.iconRes);
        holder.title.setText(item.title);
        holder.subtitle.setText(item.subtitle);
        holder.itemView.setOnClickListener(v -> {
            if (item.onClick != null) item.onClick.run();
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View bgHero;
        final ImageView icon;
        final ImageView watermark;
        final TextView title;
        final TextView subtitle;

        VH(@NonNull View itemView) {
            super(itemView);
            bgHero = itemView.findViewById(R.id.bgHero);
            icon = itemView.findViewById(R.id.ivHeroIcon);
            watermark = itemView.findViewById(R.id.ivHeroWatermark);
            title = itemView.findViewById(R.id.tvHeroTitle);
            subtitle = itemView.findViewById(R.id.tvHeroSubtitle);
        }
    }
}

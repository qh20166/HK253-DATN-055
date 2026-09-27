package com.example.traffigo.adapters;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.traffigo.R;
import com.example.traffigo.models.OfflineMap;

import java.util.List;

public class OfflineMapAdapter extends RecyclerView.Adapter<OfflineMapAdapter.ViewHolder> {

    public interface OnItemClickListener { void onItemClick(OfflineMap map); }
    public interface OnDeleteClickListener { void onDeleteClick(OfflineMap map, int position); }

    private final List<OfflineMap> maps;
    private final OnItemClickListener clickListener;
    private final OnDeleteClickListener deleteListener;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public OfflineMapAdapter(List<OfflineMap> maps,
                             OnItemClickListener clickListener,
                             OnDeleteClickListener deleteListener) {
        this.maps = maps;
        this.clickListener = clickListener;
        this.deleteListener = deleteListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_offline_map, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OfflineMap map = maps.get(position);
        holder.tvName.setText(map.title);
        holder.tvDate.setText(map.dateTime);
        holder.tvSize.setText(map.fileSizeStr);

        // Clear old image and tag before async load to avoid stale bitmaps on recycled views
        holder.imgThumb.setImageResource(R.color.brand_primary);
        holder.imgThumb.setTag(map.imagePath);

        String path = map.imagePath;
        new Thread(() -> {
            Bitmap thumb = loadThumbnail(path, 240);
            uiHandler.post(() -> {
                // Only update if this ViewHolder still shows the same item
                if (path.equals(holder.imgThumb.getTag())) {
                    holder.imgThumb.setImageBitmap(thumb);
                }
            });
        }).start();

        holder.itemView.setOnClickListener(v -> clickListener.onItemClick(map));
        // Dùng vị trí HIỆN TẠI của ViewHolder, không dùng `position` bắt lúc bind (đã cũ sau khi xoá
        // item khác) — nếu không sẽ xoá nhầm file hoặc IndexOutOfBounds ở lần xoá thứ hai trở đi.
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) deleteListener.onDeleteClick(maps.get(pos), pos);
        });
    }

    @Override
    public int getItemCount() { return maps.size(); }

    private Bitmap loadThumbnail(String path, int targetPx) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);

        int inSampleSize = 1;
        int h = opts.outHeight, w = opts.outWidth;
        while (h / (inSampleSize * 2) >= targetPx && w / (inSampleSize * 2) >= targetPx) {
            inSampleSize *= 2;
        }

        opts.inSampleSize = inSampleSize;
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, opts);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumb;
        TextView tvName, tvDate, tvSize;
        ImageButton btnDelete;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumb = itemView.findViewById(R.id.imgThumb);
            tvName = itemView.findViewById(R.id.tvMapName);
            tvDate = itemView.findViewById(R.id.tvMapDate);
            tvSize = itemView.findViewById(R.id.tvMapSize);
            btnDelete = itemView.findViewById(R.id.btnDeleteItem);
        }
    }
}

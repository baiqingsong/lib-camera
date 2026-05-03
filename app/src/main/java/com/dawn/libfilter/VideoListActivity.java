package com.dawn.libfilter;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频列表界面。
 * 列出与录制功能相同目录下的所有 .mp4 文件，点击进入 {@link VideoPlayerActivity}。
 */
public class VideoListActivity extends AppCompatActivity {

    private RecyclerView  rvVideos;
    private TextView      tvEmpty;
    private VideoAdapter  adapter;

    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(2);
    private final Handler         mainHandler   = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_list);

        rvVideos = findViewById(R.id.rv_videos);
        tvEmpty  = findViewById(R.id.tv_empty);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        rvVideos.setLayoutManager(new LinearLayoutManager(this));
        rvVideos.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        adapter = new VideoAdapter();
        rvVideos.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadVideos();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbnailPool.shutdownNow();
    }

    // ──────────────────────────────────────────────────────────────────────────

    private void loadVideos() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        List<File> list = new ArrayList<>();
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".mp4"));
            if (files != null) {
                // 按最后修改时间倒序（最新在前）
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                list.addAll(Arrays.asList(files));
            }
        }
        adapter.setVideos(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        rvVideos.setVisibility(list.isEmpty() ? View.GONE  : View.VISIBLE);
    }

    private void openPlayer(File file) {
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_PATH, file.getAbsolutePath());
        startActivity(intent);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────────────────────────────────────

    private class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {

        private final List<File> items = new ArrayList<>();

        void setVideos(List<File> videos) {
            items.clear();
            items.addAll(videos);
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_video, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            File file = items.get(position);

            holder.tvName.setText(file.getName());

            long   sizeBytes = file.length();
            String sizeStr   = sizeBytes < 1024 * 1024
                    ? String.format(Locale.getDefault(), "%.1f KB", sizeBytes / 1024.0)
                    : String.format(Locale.getDefault(), "%.2f MB", sizeBytes / (1024.0 * 1024));
            String dateStr   = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(file.lastModified()));
            holder.tvInfo.setText(dateStr + "  " + sizeStr);

            // 异步加载缩略图
            holder.ivThumb.setImageResource(android.R.drawable.ic_media_play);
            final String tag = file.getAbsolutePath();
            holder.ivThumb.setTag(tag);
            thumbnailPool.execute(() -> {
                Bitmap thumb = extractThumbnail(file);
                mainHandler.post(() -> {
                    if (tag.equals(holder.ivThumb.getTag()) && thumb != null) {
                        holder.ivThumb.setImageBitmap(thumb);
                    }
                });
            });

            holder.itemView.setOnClickListener(v -> openPlayer(file));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final TextView  tvName;
            final TextView  tvInfo;

            VH(View v) {
                super(v);
                ivThumb = v.findViewById(R.id.iv_thumb);
                tvName  = v.findViewById(R.id.tv_video_name);
                tvInfo  = v.findViewById(R.id.tv_video_info);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    /** 提取视频第一帧作为缩略图，缩放至 192×108。 */
    private static Bitmap extractThumbnail(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap raw = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (raw == null) return null;
            float scale = Math.min(192f / raw.getWidth(), 108f / raw.getHeight());
            int   w     = Math.max(1, (int) (raw.getWidth()  * scale));
            int   h     = Math.max(1, (int) (raw.getHeight() * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(raw, w, h, true);
            if (scaled != raw) raw.recycle();
            return scaled;
        } catch (Exception e) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }
}

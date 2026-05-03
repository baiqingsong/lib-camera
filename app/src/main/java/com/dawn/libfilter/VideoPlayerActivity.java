package com.dawn.libfilter;

import android.app.AlertDialog;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 简单视频播放 Activity
 * 通过 Intent extra "video_path" 接收文件路径
 */
public class VideoPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_PATH = "video_path";

    private VideoView videoView;
    private TextView tvVideoInfo;
    private TextView tvVideoTitle;
    private File videoFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        videoView = findViewById(R.id.video_view);
        tvVideoInfo = findViewById(R.id.tv_video_info);
        tvVideoTitle = findViewById(R.id.tv_video_title);
        Button btnBack = findViewById(R.id.btn_back);
        Button btnDelete = findViewById(R.id.btn_delete);

        btnBack.setOnClickListener(v -> finish());
        btnDelete.setOnClickListener(v -> confirmDelete());

        String path = getIntent().getStringExtra(EXTRA_VIDEO_PATH);
        if (path == null) {
            Toast.makeText(this, "视频路径无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        videoFile = new File(path);
        if (!videoFile.exists()) {
            Toast.makeText(this, "视频文件不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupVideo();
    }

    private void setupVideo() {
        // 显示文件名
        tvVideoTitle.setText(videoFile.getName());

        // 文件信息
        long size = videoFile.length();
        String sizeStr = size < 1024 * 1024
                ? String.format(Locale.getDefault(), "%.1f KB", size / 1024.0)
                : String.format(Locale.getDefault(), "%.2f MB", size / (1024.0 * 1024));

        String createTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(videoFile.lastModified()));

        // 获取视频时长
        String durationStr = getVideoDuration(videoFile);
        tvVideoInfo.setText(String.format(Locale.getDefault(), "%s  |  %s  |  %s",
                sizeStr, durationStr, createTime));

        // 设置 MediaController（带播放/暂停/进度条）
        MediaController mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);
        videoView.setVideoURI(Uri.fromFile(videoFile));

        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            videoView.start();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "视频播放失败 (" + what + ")", Toast.LENGTH_SHORT).show();
            return true;
        });

        videoView.setOnCompletionListener(MediaPlayer::reset);
    }

    private String getVideoDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                long ms = Long.parseLong(dur);
                return String.format(Locale.getDefault(), "%d秒", ms / 1000);
            }
        } catch (Exception e) {
            // ignore
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        return "未知时长";
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除视频")
                .setMessage("确认删除 " + videoFile.getName() + " ？")
                .setPositiveButton("删除", (d, w) -> {
                    if (videoFile.delete()) {
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK, new Intent().putExtra("deleted", true));
                        finish();
                    } else {
                        Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView.isPlaying()) {
            videoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        videoView.stopPlayback();
    }
}

package com.dawn.libfilter;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dawn.filter.BeautyParams;
import com.dawn.filter.CameraFilterHelper;
import com.dawn.filter.CameraFilterView;
import com.dawn.filter.FilterStyle;
import com.dawn.filter.FilterManager;

import java.util.List;

public class CameraFilterActivity extends AppCompatActivity {

    private CameraFilterView cameraFilterView;
    private CameraFilterHelper cameraHelper;
    private SeekBar seekBarIntensity;
    private SeekBar seekBarSmoothness;
    private SeekBar seekBarWhiten;
    private SeekBar seekBarRosy;
    private SeekBar seekBarBeautyBrightness;
    private SeekBar seekBarBeautyContrast;
    private SeekBar seekBarBeautyGamma;
    private SeekBar seekBarBeautySaturation;
    private TextView tvFilterName;
    private RecyclerView rvFilters;

    // Tab 切换
    private LinearLayout layoutTabs;
    private View tabIndicator;
    private View panelFilter, panelBeauty, panelSetting;
    private TextView tabFilter, tabBeauty, tabSetting;
    private int currentTab = 0;

    private Button btnRecord;
    private TextView tvRecordCountdown;
    private CountDownTimer countDownTimer;

    private Button btnFlipH;
    private Button btnFlipV;

    private FilterStyle currentFilterStyle = FilterStyle.ORIGINAL;
    private float currentFilterIntensity = 0.8f;
    private BeautyParams currentBeautyParams = BeautyParams.defaultCamera();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_filter);

        cameraFilterView = findViewById(R.id.camera_filter_view);
        seekBarIntensity = findViewById(R.id.seekbar_intensity);
        seekBarSmoothness = findViewById(R.id.seekbar_smoothness);
        seekBarWhiten = findViewById(R.id.seekbar_whiten);
        seekBarRosy = findViewById(R.id.seekbar_rosy);
        seekBarBeautyBrightness = findViewById(R.id.seekbar_beauty_brightness);
        seekBarBeautyContrast = findViewById(R.id.seekbar_beauty_contrast);
        seekBarBeautyGamma = findViewById(R.id.seekbar_beauty_gamma);
        seekBarBeautySaturation = findViewById(R.id.seekbar_beauty_saturation);
        tvFilterName = findViewById(R.id.tv_filter_name);
        rvFilters = findViewById(R.id.rv_filters);

        // Tab 切换
        layoutTabs = findViewById(R.id.layout_tabs);
        tabIndicator = findViewById(R.id.tab_indicator);
        panelFilter = findViewById(R.id.panel_filter);
        panelBeauty = findViewById(R.id.panel_beauty);
        panelSetting = findViewById(R.id.panel_setting);
        tabFilter = findViewById(R.id.tab_filter);
        tabBeauty = findViewById(R.id.tab_beauty);
        tabSetting = findViewById(R.id.tab_setting);

        cameraHelper = new CameraFilterHelper(this, cameraFilterView);

        // 首帧到达时隐藏 loading 遮罩
        cameraHelper.setOnFirstFrameListener(() -> cameraFilterView.setLoadingVisible(false));

        setupFilterList();
        setupFilterIntensitySeekBar();
        setupBeautyControls();
        setupTabs();

        applyModules();

        // 切换前后摄像头
        View btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        boolean canSwitchCamera = cameraHelper.canSwitchCamera();
        btnSwitchCamera.setEnabled(canSwitchCamera);
        btnSwitchCamera.setAlpha(canSwitchCamera ? 1f : 0.5f);
        btnSwitchCamera.setOnClickListener(v -> {
            if (!cameraHelper.switchCamera()) {
                Toast.makeText(this, "镜头切换中或当前设备不支持", Toast.LENGTH_SHORT).show();
            }
        });

        // 拍照
        findViewById(R.id.btn_take_picture).setOnClickListener(v ->
                cameraHelper.takePicture(bitmap -> {
                    if (bitmap != null) {
                        Toast.makeText(this, "拍照成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "拍照失败，请稍后重试", Toast.LENGTH_SHORT).show();
                    }
                }));

        // 录制
        btnRecord = findViewById(R.id.btn_record);
        tvRecordCountdown = findViewById(R.id.tv_record_countdown);
        btnRecord.setOnClickListener(v -> {
            if (cameraHelper.isRecording()) {
                stopRecordingUi();
            } else {
                startRecordingUi();
            }
        });

        // 水平镜像
        btnFlipH = findViewById(R.id.btn_flip_h);
        btnFlipH.setOnClickListener(v -> {
            boolean next = !cameraHelper.isExtraFlipH();
            cameraHelper.setExtraFlipH(next);
            btnFlipH.setAlpha(next ? 1f : 0.5f);
        });
        btnFlipH.setAlpha(0.5f);

        // 垂直翻转
        btnFlipV = findViewById(R.id.btn_flip_v);
        btnFlipV.setOnClickListener(v -> {
            boolean next = !cameraHelper.isExtraFlipV();
            cameraHelper.setExtraFlipV(next);
            btnFlipV.setAlpha(next ? 1f : 0.5f);
        });
        btnFlipV.setAlpha(0.5f);

        // 视频列表
        findViewById(R.id.btn_video_list).setOnClickListener(v ->
                startActivity(new Intent(this, VideoListActivity.class)));

        // 相机在 onResume 中启动，避免 onCreate+onResume 双重权限请求
    }

    @Override
    protected void onResume() {
        super.onResume();
        cameraHelper.onHostResume();
        if (!cameraHelper.isPreviewing()) {
            cameraHelper.startCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开界面时停止录制
        if (cameraHelper.isRecording()) {
            stopRecordingUi();
        }
        cameraHelper.stopCamera();
        cameraHelper.onHostPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CameraFilterHelper.REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限授予后 onResume() 会立即被调用，由 onResume() 统一启动相机。
                // 此处不再重复调用 startCamera()，避免产生双重 ProcessCameraProvider 回调。
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else if (requestCode == CameraFilterHelper.REQUEST_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，用户需再次点击录制
                Toast.makeText(this, "录音权限已授予，请再次点击录制", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要录音权限才能录制视频", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ==================== 录制逻辑 ====================

    private void startRecordingUi() {
        cameraHelper.startRecording(null, new CameraFilterHelper.OnVideoRecordListener() {
            @Override
            public void onVideoSaved(File videoFile) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    resetRecordingUi();
                    String msg = "录制完成: " + videoFile.getName();
                    Toast.makeText(CameraFilterActivity.this, msg, Toast.LENGTH_LONG).show();
                    // 立即打开播放器
                    openVideoPlayer(videoFile);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    resetRecordingUi();
                    Toast.makeText(CameraFilterActivity.this, "录制失败: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });

        // 更新按钮 + 开始倒计时
        btnRecord.setText("停止录制");
        tvRecordCountdown.setVisibility(View.VISIBLE);

        long maxMs = CameraFilterHelper.MAX_RECORD_DURATION_MS;
        countDownTimer = new CountDownTimer(maxMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                tvRecordCountdown.setText("录制中… 剩余 " + (millisUntilFinished / 1000) + " 秒");
            }

            @Override
            public void onFinish() {
                // 30s 自动停止由 CameraFilterHelper 触发，UI 在 onVideoSaved 回调中重置
                tvRecordCountdown.setText("录制中… 0 秒");
            }
        }.start();
    }

    private void stopRecordingUi() {
        cameraHelper.stopRecording();
        resetRecordingUi();
    }

    private void resetRecordingUi() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        if (btnRecord != null) {
            btnRecord.setText("开始录制");
        }
        if (tvRecordCountdown != null) {
            tvRecordCountdown.setVisibility(View.GONE);
            tvRecordCountdown.setText("");
        }
    }

    private void openVideoPlayer(File videoFile) {
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_PATH, videoFile.getAbsolutePath());
        startActivity(intent);
    }

    private void setupFilterList() {
        List<FilterStyle> filters = FilterManager.getSupportedFilterStyles();
        FilterAdapter adapter = new FilterAdapter(filters);
        rvFilters.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvFilters.setAdapter(adapter);
    }

    private void applyModules() {
        cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        tvFilterName.setText(currentFilterStyle.getDisplayNameCn());
    }

    private void setupFilterIntensitySeekBar() {
        seekBarIntensity.setMax(100);
        seekBarIntensity.setProgress(Math.round(currentFilterIntensity * 100f));
        seekBarIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentFilterIntensity = progress / 100f;
                    cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void setupBeautyControls() {
        setupBeautySeekBar(seekBarSmoothness, currentBeautyParams.getSmoothness(), value -> {
            currentBeautyParams.setSmoothness(value);
            cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        });
        setupBeautySeekBar(seekBarWhiten, currentBeautyParams.getWhiten(), value -> {
            currentBeautyParams.setWhiten(value);
            cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        });
        setupBeautySeekBar(seekBarRosy, currentBeautyParams.getRosy(), value -> {
            currentBeautyParams.setRosy(value);
            cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        });
        setupBeautySeekBar(seekBarBeautyBrightness, currentBeautyParams.getBrightness(), value -> {
            currentBeautyParams.setBrightness(value);
            cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        });
        setupBeautySeekBar(seekBarBeautyContrast, currentBeautyParams.getContrast(), value -> {
            currentBeautyParams.setContrast(value);
            cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        });
        setupBeautySeekBar(seekBarBeautyGamma, currentBeautyParams.getGamma(), value -> {
            currentBeautyParams.setGamma(value);
            cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        });
        setupBeautySeekBar(seekBarBeautySaturation, currentBeautyParams.getSaturation(), value -> {
            currentBeautyParams.setSaturation(value);
            cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        });
    }

    private void syncBeautyControlProgress() {
        seekBarSmoothness.setProgress(Math.round(currentBeautyParams.getSmoothness() * 100f));
        seekBarWhiten.setProgress(Math.round(currentBeautyParams.getWhiten() * 100f));
        seekBarRosy.setProgress(Math.round(currentBeautyParams.getRosy() * 100f));
        seekBarBeautyBrightness.setProgress(Math.round(currentBeautyParams.getBrightness() * 100f));
        seekBarBeautyContrast.setProgress(Math.round(currentBeautyParams.getContrast() * 100f));
        seekBarBeautyGamma.setProgress(Math.round(currentBeautyParams.getGamma() * 100f));
        seekBarBeautySaturation.setProgress(Math.round(currentBeautyParams.getSaturation() * 100f));
    }

    private void setupBeautySeekBar(SeekBar seekBar, float initialValue,
                                    OnBeautyValueChanged listener) {
        seekBar.setMax(100);
        seekBar.setProgress(Math.round(initialValue * 100f));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    listener.onChanged(progress / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private interface OnBeautyValueChanged {
        void onChanged(float value);
    }

    // ==================== Tab 切换 ====================

    private void setupTabs() {
        tabFilter.setOnClickListener(v -> switchTab(0));
        tabBeauty.setOnClickListener(v -> switchTab(1));
        tabSetting.setOnClickListener(v -> switchTab(2));
        // 初始化指示器宽度为屏幕1/3
        tabIndicator.post(() -> {
            int tabW = layoutTabs.getWidth() / 3;
            tabIndicator.getLayoutParams().width = tabW;
            tabIndicator.requestLayout();
        });
    }

    private void switchTab(int tab) {
        if (currentTab == tab) return;
        currentTab = tab;

        // 面板切换
        panelFilter.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        panelBeauty.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        panelSetting.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);

        // Tab 文字颜色
        tabFilter.setTextColor(tab == 0 ? 0xFFFFFFFF : 0x88FFFFFF);
        tabBeauty.setTextColor(tab == 1 ? 0xFFFFFFFF : 0x88FFFFFF);
        tabSetting.setTextColor(tab == 2 ? 0xFFFFFFFF : 0x88FFFFFF);

        // 指示器动画
        int tabW = layoutTabs.getWidth() / 3;
        float targetX = tab * tabW;
        ValueAnimator anim = ValueAnimator.ofFloat(tabIndicator.getTranslationX(), targetX);
        anim.setDuration(200);
        anim.addUpdateListener(a ->
                tabIndicator.setTranslationX((Float) a.getAnimatedValue()));
        anim.start();
    }

    // ==================== 滤镜 Adapter ====================

    private class FilterAdapter extends RecyclerView.Adapter<FilterAdapter.VH> {
        private final List<FilterStyle> items;
        private int selectedPos = 0;

        FilterAdapter(List<FilterStyle> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_filter_thumb, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            FilterStyle style = items.get(position);
            boolean selected = position == selectedPos;
            holder.tvName.setText(style.getDisplayNameCn());
            holder.tvName.setTextColor(selected ? 0xFFFFFFFF : 0x88FFFFFF);
            holder.itemView.setSelected(selected);
            holder.itemView.setOnClickListener(v -> {
                int old = selectedPos;
                selectedPos = holder.getBindingAdapterPosition();
                if (old >= 0) {
                    notifyItemChanged(old);
                }
                notifyItemChanged(selectedPos);
                currentFilterStyle = style;
                currentBeautyParams = style.getRecommendedBeautyParams(currentBeautyParams);
                syncBeautyControlProgress();
                seekBarIntensity.setEnabled(style.isAdjustable());
                if (!style.isAdjustable()) {
                    currentFilterIntensity = 0f;
                } else if (currentFilterIntensity <= 0f) {
                    currentFilterIntensity = 0.8f;
                }
                seekBarIntensity.setProgress(Math.round(currentFilterIntensity * 100f));
                cameraHelper.notifyFilterChanged(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
                tvFilterName.setText(style.getDisplayNameCn());
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_filter_item_name);
            }
        }
    }
}

package com.dawn.libfilter;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

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
    private LinearLayout beautyControls;
    private SeekBar seekBarSmoothness;
    private SeekBar seekBarWhiten;
    private SeekBar seekBarRosy;
    private SeekBar seekBarBeautyBrightness;
    private SeekBar seekBarBeautyContrast;
    private SeekBar seekBarBeautyGamma;
    private SeekBar seekBarBeautySaturation;
    private TextView tvFilterName;
    private RecyclerView rvFilters;

    private FilterStyle currentFilterStyle = FilterStyle.ORIGINAL;
    private float currentFilterIntensity = 0.8f;
    private BeautyParams currentBeautyParams = BeautyParams.defaultCamera();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_filter);

        cameraFilterView = findViewById(R.id.camera_filter_view);
        seekBarIntensity = findViewById(R.id.seekbar_intensity);
        beautyControls = findViewById(R.id.layout_beauty_controls);
        seekBarSmoothness = findViewById(R.id.seekbar_smoothness);
        seekBarWhiten = findViewById(R.id.seekbar_whiten);
        seekBarRosy = findViewById(R.id.seekbar_rosy);
        seekBarBeautyBrightness = findViewById(R.id.seekbar_beauty_brightness);
        seekBarBeautyContrast = findViewById(R.id.seekbar_beauty_contrast);
        seekBarBeautyGamma = findViewById(R.id.seekbar_beauty_gamma);
        seekBarBeautySaturation = findViewById(R.id.seekbar_beauty_saturation);
        tvFilterName = findViewById(R.id.tv_filter_name);
        rvFilters = findViewById(R.id.rv_filters);

        cameraHelper = new CameraFilterHelper(this, cameraFilterView);

        setupFilterList();
        setupFilterIntensitySeekBar();
        setupBeautyControls();

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
        cameraHelper.stopCamera();
        cameraHelper.onHostPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CameraFilterHelper.REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cameraHelper.startCamera();
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void setupFilterList() {
        List<FilterStyle> filters = FilterManager.getSupportedFilterStyles();
        FilterAdapter adapter = new FilterAdapter(filters);
        rvFilters.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvFilters.setAdapter(adapter);
    }

    private void applyModules() {
        cameraFilterView.setBeautyAndFilter(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
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
                    cameraFilterView.updateFilterIntensity(currentFilterIntensity);
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
            cameraFilterView.updateBeautyParams(currentBeautyParams);
        });
        setupBeautySeekBar(seekBarWhiten, currentBeautyParams.getWhiten(), value -> {
            currentBeautyParams.setWhiten(value);
            cameraFilterView.updateBeautyParams(currentBeautyParams);
        });
        setupBeautySeekBar(seekBarRosy, currentBeautyParams.getRosy(), value -> {
            currentBeautyParams.setRosy(value);
            cameraFilterView.updateBeautyParams(currentBeautyParams);
        });
        setupBeautySeekBar(seekBarBeautyBrightness, currentBeautyParams.getBrightness(), value -> {
            currentBeautyParams.setBrightness(value);
            cameraFilterView.updateBeautyParams(currentBeautyParams);
        });
        setupBeautySeekBar(seekBarBeautyContrast, currentBeautyParams.getContrast(), value -> {
            currentBeautyParams.setContrast(value);
            cameraFilterView.updateBeautyParams(currentBeautyParams);
        });
        setupBeautySeekBar(seekBarBeautyGamma, currentBeautyParams.getGamma(), value -> {
            currentBeautyParams.setGamma(value);
            cameraFilterView.updateBeautyParams(currentBeautyParams);
        });
        setupBeautySeekBar(seekBarBeautySaturation, currentBeautyParams.getSaturation(), value -> {
            currentBeautyParams.setSaturation(value);
            cameraFilterView.updateBeautyParams(currentBeautyParams);
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
            holder.tvName.setText(style.getDisplayNameCn());
            holder.itemView.setSelected(position == selectedPos);
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
                cameraFilterView.updateFilterStyle(currentFilterStyle, currentFilterIntensity);
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

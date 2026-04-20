package com.dawn.libfilter;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dawn.filter.BeautyFilterPipeline;
import com.dawn.filter.BeautyParams;
import com.dawn.filter.FilterStyle;
import com.dawn.filter.FilterManager;

import java.io.InputStream;
import java.util.List;

import jp.co.cyberagent.android.gpuimage.GPUImageView;

public class ImageFilterActivity extends AppCompatActivity {

    private GPUImageView gpuImageView;
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

    private FilterManager filterManager;
    private Bitmap originalBitmap;
    private BeautyFilterPipeline activePipeline;
    private BeautyParams currentBeautyParams = BeautyParams.defaultCamera();
    private FilterStyle currentFilterStyle = FilterStyle.ORIGINAL;
    private float currentFilterIntensity = 0.8f;

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    loadImage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_filter);

        filterManager = new FilterManager(this);

        gpuImageView = findViewById(R.id.gpu_image_view);
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

        setupFilterList();
        setupFilterIntensitySeekBar();
        setupBeautyControls();

        // 选择图片按钮
        findViewById(R.id.btn_pick_image).setOnClickListener(v -> pickImage.launch("image/*"));
        // 保存按钮
        findViewById(R.id.btn_save).setOnClickListener(v -> saveImage());
    }

    private void loadImage(Uri uri) {
        try {
            // 第一遍：只读取尺寸，计算采样率，防止超大图片 OOM
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, opts);
            }
            int maxSide = 2048;
            int sampleSize = 1;
            while (opts.outWidth / sampleSize > maxSide || opts.outHeight / sampleSize > maxSide) {
                sampleSize *= 2;
            }
            // 第二遍：按采样率解码
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                originalBitmap = BitmapFactory.decodeStream(is, null, opts);
            }
            if (originalBitmap != null) {
                gpuImageView.setImage(originalBitmap);
                applyModules(true);
            } else {
                Toast.makeText(this, "无法解码图片", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "加载图片失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyModules(boolean rebuildPipeline) {
        if (originalBitmap == null) {
            return;
        }
        if (rebuildPipeline || activePipeline == null
                || activePipeline.getFilterStyle() != currentFilterStyle) {
            activePipeline = new BeautyFilterPipeline(currentBeautyParams, currentFilterStyle,
                    currentFilterIntensity);
            gpuImageView.setFilter(activePipeline);
        } else {
            activePipeline.updateBeautyParams(currentBeautyParams);
            activePipeline.updateFilterIntensity(currentFilterIntensity);
        }
        gpuImageView.requestRender();
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
                    applyModules(false);
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
            applyModules(false);
        });
        setupBeautySeekBar(seekBarWhiten, currentBeautyParams.getWhiten(), value -> {
            currentBeautyParams.setWhiten(value);
            applyModules(false);
        });
        setupBeautySeekBar(seekBarRosy, currentBeautyParams.getRosy(), value -> {
            currentBeautyParams.setRosy(value);
            applyModules(false);
        });
        setupBeautySeekBar(seekBarBeautyBrightness, currentBeautyParams.getBrightness(), value -> {
            currentBeautyParams.setBrightness(value);
            applyModules(false);
        });
        setupBeautySeekBar(seekBarBeautyContrast, currentBeautyParams.getContrast(), value -> {
            currentBeautyParams.setContrast(value);
            applyModules(false);
        });
        setupBeautySeekBar(seekBarBeautyGamma, currentBeautyParams.getGamma(), value -> {
            currentBeautyParams.setGamma(value);
            applyModules(false);
        });
        setupBeautySeekBar(seekBarBeautySaturation, currentBeautyParams.getSaturation(), value -> {
            currentBeautyParams.setSaturation(value);
            applyModules(false);
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

    private void setupFilterList() {
        List<FilterStyle> filters = FilterManager.getSupportedFilterStyles();
        FilterAdapter adapter = new FilterAdapter(filters);
        rvFilters.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvFilters.setAdapter(adapter);
    }

    private void saveImage() {
        if (originalBitmap == null) {
            Toast.makeText(this, "请先选择一张图片", Toast.LENGTH_SHORT).show();
            return;
        }
        // Android 6~9 需要运行时请求写存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2001);
                return;
            }
        }
        doSaveImage();
    }

    private void doSaveImage() {
        gpuImageView.saveToPictures("LibFilter", System.currentTimeMillis() + ".jpg",
                uri -> runOnUiThread(() ->
                        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 2001 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doSaveImage();
        } else if (requestCode == 2001) {
            Toast.makeText(this, "需要存储权限才能保存", Toast.LENGTH_SHORT).show();
        }
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
                applyModules(true);
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

    private interface OnBeautyValueChanged {
        void onChanged(float value);
    }
}

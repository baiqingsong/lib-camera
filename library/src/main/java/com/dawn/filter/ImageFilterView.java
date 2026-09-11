package com.dawn.filter;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;

import java.util.Arrays;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.GPUImageView;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;

/**
 * 图片滤镜处理控件（静态图片实时预览）。
 * <p>
 * 与 {@link CameraFilterView} 对应：{@link CameraFilterView} 处理相机实时预览，
 * 本控件处理静态图片的实时美颜 + 滤镜预览，把「源图 + 美颜 + 滤镜 + 结果导出」封装为
 * 一个可直接放进布局的 View，外部无需接触 GPUImageView / 滤镜链细节。
 * <p>
 * 使用示例：
 * <pre>
 *   ImageFilterView view = findViewById(R.id.imageFilterView);
 *   view.setImage(bitmap);                                    // 设置源图
 *   view.setBeautyAndFilter(BeautyParams.defaultImage(),      // 默认图更明显的美颜
 *           FilterStyle.FRESH, 0.8f);
 *   view.updateFilterIntensity(0.6f);                         // 滑块调整，无需重建
 *   view.getFilteredBitmap(bmp -&gt; save(bmp));                // 导出结果 Bitmap
 *   view.saveToPictures("MyApp", "out.jpg", uri -&gt; { });     // 或直接保存到相册
 * </pre>
 */
public class ImageFilterView extends FrameLayout {

    private static final String TAG = "ImageFilterView";

    private GPUImageView gpuImageView;
    private BeautyFilterPipeline activePipeline;
    private GPUImageClarityFilter clarityFilter;
    private GPUImageFilterGroup effectiveGroup;
    private BeautyParams currentBeautyParams = BeautyParams.defaultImage();
    private FilterStyle currentFilterStyle = FilterStyle.ORIGINAL;
    private float currentFilterIntensity = 0.8f;
    private float currentClarity = 0f;
    private Bitmap sourceBitmap;

    public ImageFilterView(Context context) {
        super(context);
        init(context);
    }

    public ImageFilterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ImageFilterView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        gpuImageView = new GPUImageView(context);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        gpuImageView.setLayoutParams(lp);
        gpuImageView.setScaleType(GPUImage.ScaleType.CENTER_CROP);
        activePipeline = new BeautyFilterPipeline(
                currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        clarityFilter = new GPUImageClarityFilter(0f);
        effectiveGroup = new GPUImageFilterGroup(Arrays.asList(activePipeline, clarityFilter));
        gpuImageView.setFilter(effectiveGroup);
        addView(gpuImageView);
    }

    // =========================================================
    //  图片输入
    // =========================================================

    /** 设置待处理的源图，并立即应用当前美颜 + 滤镜。 */
    public void setImage(Bitmap bitmap) {
        sourceBitmap = bitmap;
        if (bitmap != null && !bitmap.isRecycled()) {
            clarityFilter.setImageSize(bitmap.getWidth(), bitmap.getHeight());
            gpuImageView.setImage(bitmap);
            applyPipeline(true);
        }
    }

    public Bitmap getSourceBitmap() {
        return sourceBitmap;
    }

    // =========================================================
    //  美颜 & 滤镜（与 CameraFilterView 相同的 API 风格）
    // =========================================================

    /**
     * 同时设置美颜参数和滤镜风格。滤镜风格未变化时仅更新参数，避免重建 shader / LUT。
     */
    public void setBeautyAndFilter(BeautyParams params, FilterStyle style, float intensity) {
        currentBeautyParams = params == null ? BeautyParams.defaultImage() : params.copy();
        currentFilterIntensity = clamp(intensity);

        if (activePipeline != null && style != null && currentFilterStyle == style) {
            activePipeline.updateBeautyParams(currentBeautyParams);
            activePipeline.updateFilterIntensity(currentFilterIntensity);
            gpuImageView.requestRender();
            return;
        }

        if (style != null) {
            currentFilterStyle = style;
        }
        activePipeline = new BeautyFilterPipeline(
                currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        effectiveGroup = new GPUImageFilterGroup(Arrays.asList(activePipeline, clarityFilter));
        gpuImageView.setFilter(effectiveGroup);
        gpuImageView.requestRender();
    }

    /** 仅更新美颜参数（不重建滤镜链，实时生效）。 */
    public void updateBeautyParams(BeautyParams params) {
        currentBeautyParams = params == null ? BeautyParams.defaultImage() : params.copy();
        applyPipeline(false);
    }

    /** 切换滤镜风格。 */
    public void updateFilterStyle(FilterStyle style, float intensity) {
        if (style != null) {
            currentFilterStyle = style;
        }
        currentFilterIntensity = clamp(intensity);
        applyPipeline(true);
    }

    /** 仅调整当前滤镜强度（不重建滤镜，实时生效）。 */
    public void updateFilterIntensity(float intensity) {
        currentFilterIntensity = clamp(intensity);
        applyPipeline(false);
    }

    /**
     * 设置清晰度（锐化）强度 0~1。基于 Unsharp Mask 增强边缘细节，改善模糊。
     * 0 = 关闭（原图），越大锐化越强，推荐 0.3~0.7。
     */
    public void setClarity(float clarity) {
        currentClarity = clamp(clarity);
        Log.i(TAG, "setClarity=" + currentClarity);
        if (clarityFilter != null) {
            clarityFilter.setIntensity(currentClarity);
            gpuImageView.requestRender();
        }
    }

    public float getClarity() {
        return currentClarity;
    }

    private void applyPipeline(boolean rebuild) {
        if (sourceBitmap == null || sourceBitmap.isRecycled()) return;
        if (rebuild || activePipeline == null || effectiveGroup == null
                || activePipeline.getFilterStyle() != currentFilterStyle) {
            activePipeline = new BeautyFilterPipeline(
                    currentBeautyParams, currentFilterStyle, currentFilterIntensity);
            effectiveGroup = new GPUImageFilterGroup(Arrays.asList(activePipeline, clarityFilter));
            gpuImageView.setFilter(effectiveGroup);
        } else {
            activePipeline.updateBeautyParams(currentBeautyParams);
            activePipeline.updateFilterIntensity(currentFilterIntensity);
        }
        gpuImageView.requestRender();
    }

    // =========================================================
    //  结果导出
    // =========================================================

    /** 滤镜结果回调（主线程）。 */
    public interface OnFilteredListener {
        void onFiltered(Bitmap bitmap);
    }

    /** 保存回调（主线程）。 */
    public interface OnSavedListener {
        void onSaved(Uri uri);
    }

    /** 获取应用当前美颜 + 滤镜后的 Bitmap（后台渲染，回调在主线程）。 */
    public void getFilteredBitmap(OnFilteredListener listener) {
        if (listener == null) return;
        final Bitmap src = sourceBitmap;
        if (src == null || src.isRecycled()) {
            listener.onFiltered(null);
            return;
        }
        final Bitmap copy;
        try {
            copy = src.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            listener.onFiltered(null);
            return;
        }
        new Thread(() -> {
            Bitmap result;
            try {
                result = gpuImageView.getGPUImage().getBitmapWithFilterApplied(copy);
            } catch (Throwable t) {
                result = copy;
            }
            final Bitmap out = result != null ? result : copy;
            post(() -> listener.onFiltered(out));
        }, "ImageFilterViewRender").start();
    }

    /** 把当前处理后的画面保存到相册。 */
    public void saveToPictures(String folderName, String fileName, OnSavedListener listener) {
        gpuImageView.saveToPictures(folderName, fileName,
                uri -> {
                    if (listener != null) listener.onSaved(uri);
                });
    }

    // =========================================================
    //  状态查询 & 资源释放
    // =========================================================

    public BeautyParams getCurrentBeautyParams() {
        return currentBeautyParams.copy();
    }

    public FilterStyle getCurrentFilterStyle() {
        return currentFilterStyle;
    }

    public float getCurrentFilterIntensity() {
        return currentFilterIntensity;
    }

    /** 释放 GL 资源（View 销毁时调用，可选）。 */
    public void release() {
        try {
            gpuImageView.getGPUImage().deleteImage();
        } catch (Throwable ignored) {
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}

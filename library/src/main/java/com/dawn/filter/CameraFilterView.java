package com.dawn.filter;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.GPUImageView;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;

/**
 * 相机滤镜预览视图。
 * 封装 GPUImageView，提供简单的 setFilter/switchCamera API。
 * <p>
 * 在布局中直接使用：
 * <pre>
 *   &lt;com.dawn.filter.CameraFilterView
 *       android:id="@+id/cameraFilterView"
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent" /&gt;
 * </pre>
 */
public class CameraFilterView extends FrameLayout {

    private static final float DEFAULT_PREVIEW_RATIO = 3f / 4f;

    private GPUImageView gpuImageView;
    private View         loadingOverlay;  // 启动时的 loading 遮罩
    private GPUImageFilter activeFilter;
    private BeautyFilterPipeline activePipeline;
    private FilterType currentFilterType = FilterType.NONE;
    private float currentIntensity = 0.5f;
    private BeautyParams currentBeautyParams = BeautyParams.defaultCamera();
    private FilterStyle currentFilterStyle = FilterStyle.ORIGINAL;
    private float currentFilterIntensity = 1.0f;

    public CameraFilterView(Context context) {
        super(context);
        init(context);
    }

    public CameraFilterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CameraFilterView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        gpuImageView = new GPUImageView(context);
        LayoutParams layoutParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        layoutParams.gravity = Gravity.CENTER;
        gpuImageView.setLayoutParams(layoutParams);
        gpuImageView.setScaleType(GPUImage.ScaleType.CENTER_CROP);
        gpuImageView.setRatio(DEFAULT_PREVIEW_RATIO);
        activeFilter = new GPUImageFilter();
        gpuImageView.setFilter(activeFilter);
        addView(gpuImageView);

        // Loading 遮罩：覆盖在 GPUImageView 上方，首帧到达后隐藏
        loadingOverlay = buildLoadingOverlay(context);
        addView(loadingOverlay);
    }

    private View buildLoadingOverlay(Context context) {
        FrameLayout overlay = new FrameLayout(context);
        overlay.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(0xFF000000);  // 纯黑背景，遮住 GLSurfaceView 初始化花屏

        ProgressBar spinner = new ProgressBar(context);
        LayoutParams spinnerLp = new LayoutParams(80, 80);
        spinnerLp.gravity = Gravity.CENTER;
        spinnerLp.bottomMargin = 48;
        spinner.setLayoutParams(spinnerLp);
        overlay.addView(spinner);

        TextView hint = new TextView(context);
        hint.setText("正在启动摄像头…");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(14);
        LayoutParams hintLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.CENTER;
        hintLp.topMargin = 96;
        hint.setLayoutParams(hintLp);
        overlay.addView(hint);

        return overlay;
    }

    /** 显示 / 隐藏 Loading 遮罩（主线程调用）。 */
    public void setLoadingVisible(boolean visible) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 获取内部 GPUImageView，用于 CameraHelper 绑定相机。
     */
    public GPUImageView getGPUImageView() {
        return gpuImageView;
    }

    /**
     * 销毁旧的 GPUImageView 并创建全新实例。
     * 用于切换摄像头时彻底释放旧的 GL 上下文和 SurfaceTexture，
     * 避免 GLThread 访问已失效资源导致 SIGSEGV。
     * 调用后需重新 setUpCamera。
     */
    public void recreateGPUImageView() {
        // 暂停旧 View 的 GL 线程
        if (gpuImageView != null) {
            try {
                gpuImageView.onPause();
            } catch (Throwable ignored) {
            }
            removeView(gpuImageView);
        }

        // 创建全新的 GPUImageView
        gpuImageView = new GPUImageView(getContext());
        LayoutParams layoutParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        layoutParams.gravity = Gravity.CENTER;
        gpuImageView.setLayoutParams(layoutParams);
        gpuImageView.setScaleType(GPUImage.ScaleType.CENTER_CROP);
        gpuImageView.setRatio(DEFAULT_PREVIEW_RATIO);

        // 重新应用当前滤镜/美颜
        if (activePipeline != null) {
            activePipeline = new BeautyFilterPipeline(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
            activeFilter = activePipeline;
            gpuImageView.setFilter(activePipeline);
        } else {
            activeFilter = FilterFactory.createFilter(currentFilterType, currentIntensity);
            gpuImageView.setFilter(activeFilter);
        }

        addView(gpuImageView);
        // 把 loading 遮罩移到最顶层（addView 后 gpuImageView 在下，遮罩在上）
        if (loadingOverlay != null) {
            bringChildToFront(loadingOverlay);
            loadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    public void setPreviewAspectRatio(float ratio) {
        if (ratio > 0f) {
            gpuImageView.setRatio(ratio);
        }
    }

    /**
     * 设置滤镜类型和强度。
     */
    public void setFilter(FilterType type, float intensity) {
        if (type == FilterType.BEAUTY) {
            setBeautyFilter(BeautyParams.fromIntensity(intensity));
            return;
        }
        this.currentFilterType = type;
        this.currentIntensity = intensity;
        activeFilter = FilterFactory.createFilter(type, intensity);
        gpuImageView.setFilter(activeFilter);
        gpuImageView.requestRender();
    }

    /**
     * 设置滤镜类型，使用默认强度。
     */
    public void setFilter(FilterType type) {
        setFilter(type, 0.5f);
    }

    /**
     * 仅更新当前滤镜的强度（不重新创建滤镜）。
     */
    public void updateIntensity(float intensity) {
        this.currentIntensity = intensity;
        if (currentFilterType == FilterType.BEAUTY) {
            updateBeautyParams(BeautyParams.fromIntensity(intensity));
            return;
        }
        FilterFactory.updateIntensity(activeFilter, currentFilterType, intensity);
        gpuImageView.requestRender();
    }

    public void setBeautyAndFilter(BeautyParams beautyParams, FilterStyle filterStyle, float intensity) {
        currentBeautyParams = beautyParams == null ? BeautyParams.defaultCamera() : beautyParams.copy();
        currentFilterStyle = filterStyle == null ? FilterStyle.ORIGINAL : filterStyle;
        currentFilterIntensity = Math.max(0f, Math.min(1f, intensity));
        activePipeline = new BeautyFilterPipeline(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        activeFilter = activePipeline;
        gpuImageView.setFilter(activePipeline);
        gpuImageView.requestRender();
    }

    public void setBeautyFilter(BeautyParams beautyParams) {
        setBeautyAndFilter(beautyParams, currentFilterStyle, currentFilterIntensity);
    }

    public void updateBeautyParams(BeautyParams beautyParams) {
        this.currentBeautyParams = beautyParams == null
                ? BeautyParams.defaultCamera()
                : beautyParams.copy();
        if (activePipeline == null) {
            setBeautyAndFilter(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        } else {
            activePipeline.updateBeautyParams(currentBeautyParams);
            gpuImageView.requestRender();
        }
    }

    public void updateFilterStyle(FilterStyle filterStyle, float intensity) {
        setBeautyAndFilter(currentBeautyParams, filterStyle, intensity);
    }

    public void updateFilterIntensity(float intensity) {
        currentFilterIntensity = Math.max(0f, Math.min(1f, intensity));
        if (activePipeline == null) {
            setBeautyAndFilter(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        } else {
            activePipeline.updateFilterIntensity(currentFilterIntensity);
            gpuImageView.requestRender();
        }
    }

    /**
     * 获取当前滤镜类型。
     */
    public FilterType getCurrentFilterType() {
        return currentFilterType;
    }

    /**
     * 获取当前强度值。
     */
    public float getCurrentIntensity() {
        return currentIntensity;
    }

    public BeautyParams getCurrentBeautyParams() {
        return currentBeautyParams.copy();
    }

    public FilterStyle getCurrentFilterStyle() {
        return currentFilterStyle;
    }

    public float getCurrentFilterIntensity() {
        return currentFilterIntensity;
    }
}

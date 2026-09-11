package com.dawn.filter;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.os.Environment;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.util.Arrays;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.GPUImageView;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;

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
public class CameraFilterView extends FrameLayout implements DefaultLifecycleObserver {

    private static final float DEFAULT_PREVIEW_RATIO = 3f / 4f;

    private GPUImageView gpuImageView;
    private View         loadingOverlay;  // 启动时的 loading 遮罩
    private GPUImageFilter activeFilter;
    private BeautyFilterPipeline activePipeline;
    private GPUImageClarityFilter clarityFilter;   // 清晰度（锐化）
    private GPUImageFilterGroup effectiveGroup;    // 当前生效的组合滤镜
    private FilterType currentFilterType = FilterType.NONE;
    private float currentIntensity = 0.5f;
    private BeautyParams currentBeautyParams = BeautyParams.defaultCamera();
    private FilterStyle currentFilterStyle = FilterStyle.ORIGINAL;
    private float currentFilterIntensity = 1.0f;
    private float currentClarity = 0f;

    // ── 一体化自动管理（相机模式） ──────────────────────────────
    private boolean autoManageLifecycle = false;  // 是否自动管理权限/生命周期/相机启停
    private CameraFilterHelper cameraHelper;       // 自动管理模式下的相机辅助
    private LifecycleOwner lifecycleOwner;         // 宿主 LifecycleOwner（Activity）
    private CameraFilterHelper.OnFirstFrameListener firstFrameListener;  // 用户首帧回调

    // ── 外部视频流输入模式 ─────────────────────────────────────
    private boolean streamMode = false;            // 是否处于视频流输入模式
    private Bitmap streamLastFrame;                // 最近一帧（截图/录像使用）
    private Bitmap streamPendingRecycle;           // 上上帧，可安全 recycle
    private int    lastDisplayW = 0;               // 最近一帧宽（纹理尺寸变化检测）
    private int    lastDisplayH = 0;
    private VideoStreamRecorder streamRecorder;    // 流模式录像器
    private File pendingStreamOutput;              // 首帧前请求录像的输出文件
    private VideoStreamRecorder.OnVideoRecordListener pendingStreamListener;  // 首帧前请求录像的回调

    public CameraFilterView(Context context) {
        super(context);
        init(context, null);
    }

    public CameraFilterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public CameraFilterView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        if (attrs != null) {
            TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.CameraFilterView);
            autoManageLifecycle = ta.getBoolean(R.styleable.CameraFilterView_autoManageLifecycle, false);
            ta.recycle();
        }
        gpuImageView = new GPUImageView(context);
        LayoutParams layoutParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        layoutParams.gravity = Gravity.CENTER;
        gpuImageView.setLayoutParams(layoutParams);
        gpuImageView.setScaleType(GPUImage.ScaleType.CENTER_CROP);
        gpuImageView.setRatio(DEFAULT_PREVIEW_RATIO);
        activeFilter = new GPUImageFilter();
        clarityFilter = new GPUImageClarityFilter(0f);
        applyEffectiveFilter();
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
    GPUImageView getGPUImageView() {
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

        // 重新应用当前滤镜/美颜（GL 上下文已重建，清晰度滤镜也需新建）
        clarityFilter = new GPUImageClarityFilter(currentClarity);
        if (activePipeline != null) {
            activePipeline = new BeautyFilterPipeline(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
            activeFilter = activePipeline;
        } else {
            activeFilter = FilterFactory.createFilter(currentFilterType, currentIntensity);
        }
        applyEffectiveFilter();

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
     * 设置清晰度（锐化）强度 0~1，改善模糊画面。0 = 关闭（原图）。
     * 基于 Unsharp Mask 边缘增强，推荐 0.3~0.7。
     */
    public void setClarity(float clarity) {
        currentClarity = Math.max(0f, Math.min(1f, clarity));
        if (clarityFilter != null) {
            clarityFilter.setIntensity(currentClarity);
            gpuImageView.requestRender();
        }
    }

    public float getClarity() {
        return currentClarity;
    }

    /** 用「内容滤镜 + 清晰度滤镜」组合构建当前生效滤镜并应用到 GPUImageView。 */
    private void applyEffectiveFilter() {
        GPUImageFilter content = (activePipeline != null) ? activePipeline : activeFilter;
        if (clarityFilter == null) {
            clarityFilter = new GPUImageClarityFilter(currentClarity);
        }
        effectiveGroup = new GPUImageFilterGroup(Arrays.asList(content, clarityFilter));
        gpuImageView.setFilter(effectiveGroup);
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
        applyEffectiveFilter();
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
        currentFilterIntensity = Math.max(0f, Math.min(1f, intensity));

        // 滤镜类型没变时，仅更新强度和美颜参数，避免重建 shader 和 LUT 导致崩溃
        if (activePipeline != null && currentFilterStyle == filterStyle) {
            activePipeline.updateBeautyParams(currentBeautyParams);
            activePipeline.updateFilterIntensity(currentFilterIntensity);
            gpuImageView.requestRender();
            syncStreamRecorderFilter();
            return;
        }

        currentFilterStyle = filterStyle == null ? FilterStyle.ORIGINAL : filterStyle;
        activePipeline = new BeautyFilterPipeline(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        activeFilter = activePipeline;
        applyEffectiveFilter();
        gpuImageView.requestRender();
        syncStreamRecorderFilter();
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
        syncStreamRecorderFilter();
    }

    public void updateFilterStyle(FilterStyle filterStyle, float intensity) {
        setBeautyAndFilter(currentBeautyParams, filterStyle, intensity);
    }

    /**
     * 在 GL 上下文重建后（onResume）重新初始化当前滤镜的 shader program。
     * <p>
     * 注意：GPUImage 的 {@code ifNeedInit()} 只检查 {@code isInitialized} 布尔标志，
     * 无法感知 EGL 上下文丢失——离开相机页（如录制后跳转播放器）时 GLSurfaceView
     * 会销毁 GL 上下文，返回后旧 filter 的 program 已失效但标志仍为 true，导致
     * 黑屏（Mali 报 "program is not a value generated by OpenGL"）。
     * 因此这里强制<b>新建</b>滤镜实例，使 isInitialized=false，让 setFilter 内部的
     * ifNeedInit() 在新上下文中重新编译 shader program。
     */
    public void refreshActiveFilter() {
        if (activePipeline != null) {
            activePipeline = new BeautyFilterPipeline(
                    currentBeautyParams, currentFilterStyle, currentFilterIntensity);
            activeFilter = activePipeline;
        } else {
            activeFilter = FilterFactory.createFilter(currentFilterType, currentIntensity);
        }
        // GL 上下文已重建，清晰度滤镜需新建实例
        clarityFilter = new GPUImageClarityFilter(currentClarity);
        applyEffectiveFilter();
        // GL 上下文重建后，GPUImageRenderer 的 glTextureId 仍是旧上下文的纹理 ID，
        // 已经失效。deleteImage() 将其重置为 NO_IMAGE(-1)，使下一帧 setImageBitmap
        // 通过 glGenTextures 重新创建纹理，避免 glTexSubImage2D 报
        // "texture image does not exist" 导致黑屏。
        gpuImageView.getGPUImage().deleteImage();
        gpuImageView.requestRender();
    }

    /**
     * 在离开页面（onPause）时销毁当前滤镜，将其 isInitialized 置为 false。
     * GL 上下文随后会被 GLSurfaceView 销毁；返回页面时 onSurfaceCreated 会调用
     * ifNeedInit()，因 isInitialized=false 而在新上下文中重新编译 shader program，
     * 避免旧 program 失效导致黑屏（Mali: "program is not a value generated by OpenGL"）。
     */
    public void destroyActiveFilter() {
        if (effectiveGroup != null) {
            try {
                effectiveGroup.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    public void updateFilterIntensity(float intensity) {
        currentFilterIntensity = Math.max(0f, Math.min(1f, intensity));
        if (activePipeline == null) {
            setBeautyAndFilter(currentBeautyParams, currentFilterStyle, currentFilterIntensity);
        } else {
            activePipeline.updateFilterIntensity(currentFilterIntensity);
            gpuImageView.requestRender();
        }
        syncStreamRecorderFilter();
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

    // =========================================================
    //  一体化自动管理（相机模式）
    // =========================================================

    /**
     * 开启/关闭一体化自动管理。
     * <p>
     * 开启后，本控件自动完成：相机权限申请、相机启停（跟随宿主 Activity 生命周期）、
     * 首帧 loading 隐藏等。外部仅需在 {@code onRequestPermissionsResult} 转发一行：
     * <pre>
     *   &#64;Override protected void onRequestPermissionsResult(int r, String[] p, int[] g) {
     *       super.onRequestPermissionsResult(r, p, g);
     *       cameraFilterView.onRequestPermissionsResult(r, g);
     *   }
     * </pre>
     * 其余（onResume/onPause/start/stop）无需再手动处理。
     * 也可在 XML 中用 {@code app:autoManageLifecycle="true"} 开启。
     */
    public void setAutoManageLifecycle(boolean enable) {
        if (autoManageLifecycle == enable) return;
        autoManageLifecycle = enable;
        if (enable) {
            Activity activity = findActivity();
            if (activity instanceof LifecycleOwner) {
                attachLifecycle((LifecycleOwner) activity);
                if (((LifecycleOwner) activity).getLifecycle().getCurrentState()
                        .isAtLeast(Lifecycle.State.RESUMED)) {
                    startCameraIfNeeded();
                }
            }
        } else {
            detachLifecycle();
            releaseCameraHelper();
        }
    }

    public boolean isAutoManageLifecycle() {
        return autoManageLifecycle;
    }

    private Activity findActivity() {
        Context c = getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private void attachLifecycle(LifecycleOwner owner) {
        if (lifecycleOwner == owner) return;
        detachLifecycle();
        lifecycleOwner = owner;
        owner.getLifecycle().addObserver(this);
    }

    private void detachLifecycle() {
        if (lifecycleOwner != null) {
            lifecycleOwner.getLifecycle().removeObserver(this);
            lifecycleOwner = null;
        }
    }

    private void ensureCameraHelper() {
        Activity activity = findActivity();
        if (activity == null || !(activity instanceof LifecycleOwner)) return;
        if (cameraHelper == null) {
            cameraHelper = new CameraFilterHelper(activity, this);
            // 自动模式：首帧到达自动隐藏 loading 遮罩，并转发用户首帧回调
            cameraHelper.setOnFirstFrameListener(this::onInternalFirstFrame);
        }
    }

    private void onInternalFirstFrame() {
        setLoadingVisible(false);
        CameraFilterHelper.OnFirstFrameListener l = firstFrameListener;
        if (l != null) l.onFirstFrame();
    }

    private void releaseCameraHelper() {
        if (cameraHelper != null) {
            try {
                if (cameraHelper.isRecording()) cameraHelper.stopRecording();
                cameraHelper.stopCamera();
            } catch (Throwable ignored) {}
            cameraHelper = null;
        }
    }

    private void startCameraIfNeeded() {
        streamMode = false;
        ensureCameraHelper();
        if (cameraHelper != null) {
            cameraHelper.onHostResume();
            if (!cameraHelper.isPreviewing()) {
                cameraHelper.startCamera();
            }
        }
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        if (!autoManageLifecycle) return;
        startCameraIfNeeded();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        if (!autoManageLifecycle) return;
        if (cameraHelper != null) {
            cameraHelper.stopCamera();
            cameraHelper.onHostPause();
        }
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        detachLifecycle();
        releaseCameraHelper();
        releaseStreamRecorder();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (autoManageLifecycle && lifecycleOwner == null) {
            Activity activity = findActivity();
            if (activity instanceof LifecycleOwner) {
                attachLifecycle((LifecycleOwner) activity);
                if (((LifecycleOwner) activity).getLifecycle().getCurrentState()
                        .isAtLeast(Lifecycle.State.RESUMED)) {
                    startCameraIfNeeded();
                }
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (autoManageLifecycle) {
            detachLifecycle();
            releaseCameraHelper();
        }
        releaseStreamRecorder();
    }

    /**
     * 处理权限请求结果（相机模式）。
     * 宿主 Activity 只需在 {@code onRequestPermissionsResult} 中转发一行。
     */
    public void onRequestPermissionsResult(int requestCode, @NonNull int[] grantResults) {
        if (requestCode == CameraFilterHelper.REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (cameraHelper != null) {
                    cameraHelper.startCamera();
                }
            }
        }
        // REQUEST_AUDIO_PERMISSION：授权后用户重新点击录制即可
    }

    // ── 相机操作（自动管理模式下委托给内部 CameraFilterHelper） ──

    public void takePicture(CameraFilterHelper.OnPictureTakenListener listener) {
        if (streamMode) {
            takeSnapshot(bitmap -> {
                if (listener != null) listener.onPictureTaken(bitmap);
            });
            return;
        }
        if (cameraHelper == null) ensureCameraHelper();
        if (cameraHelper != null) cameraHelper.takePicture(listener);
    }

    public void startRecording(File outputFile, CameraFilterHelper.OnVideoRecordListener listener) {
        if (streamMode) {
            startStreamRecording(outputFile, new VideoStreamRecorder.OnVideoRecordListener() {
                @Override public void onVideoSaved(File file) {
                    if (listener != null) listener.onVideoSaved(file);
                }
                @Override public void onError(String message) {
                    if (listener != null) listener.onError(message);
                }
            });
            return;
        }
        if (cameraHelper == null) ensureCameraHelper();
        if (cameraHelper != null) cameraHelper.startRecording(outputFile, listener);
    }

    public void stopRecording() {
        if (streamMode || (streamRecorder != null && streamRecorder.isRecording())) {
            stopStreamRecording();
            return;
        }
        if (cameraHelper != null) cameraHelper.stopRecording();
    }

    public boolean isRecording() {
        if (streamRecorder != null && streamRecorder.isRecording()) return true;
        return cameraHelper != null && cameraHelper.isRecording();
    }

    public boolean switchCamera() {
        return cameraHelper != null && cameraHelper.switchCamera();
    }

    public boolean canSwitchCamera() {
        return cameraHelper != null && cameraHelper.canSwitchCamera();
    }

    public void setOnFirstFrameListener(CameraFilterHelper.OnFirstFrameListener listener) {
        this.firstFrameListener = listener;
        if (cameraHelper == null) ensureCameraHelper();
        if (cameraHelper != null) cameraHelper.setOnFirstFrameListener(this::onInternalFirstFrame);
    }

    public void setExtraFlipH(boolean flip) { if (cameraHelper != null) cameraHelper.setExtraFlipH(flip); }
    public boolean isExtraFlipH() { return cameraHelper != null && cameraHelper.isExtraFlipH(); }
    public void setExtraFlipV(boolean flip) { if (cameraHelper != null) cameraHelper.setExtraFlipV(flip); }
    public boolean isExtraFlipV() { return cameraHelper != null && cameraHelper.isExtraFlipV(); }
    public void setExtraRotate90(boolean rotate) { if (cameraHelper != null) cameraHelper.setExtraRotate90(rotate); }
    public boolean isExtraRotate90() { return cameraHelper != null && cameraHelper.isExtraRotate90(); }

    /**
     * 指定优先使用的摄像头朝向（CameraSelector.LENS_FACING_FRONT / BACK）。
     * 建议在相机启动前调用；若已启动则自动重启生效。
     */
    public void setPreferredLensFacing(int facing) {
        if (cameraHelper == null) ensureCameraHelper();
        if (cameraHelper != null) {
            cameraHelper.setPreferredLensFacing(facing);
            restartCameraIfPreviewing();
        }
    }

    /**
     * 指定外接摄像头索引（设备无 FRONT/BACK 摄像头时生效，如 RK 主板 USB 摄像头）。
     * 建议在相机启动前调用；若已启动则自动重启生效。
     */
    public void setCameraIndex(int index) {
        if (cameraHelper == null) ensureCameraHelper();
        if (cameraHelper != null) {
            cameraHelper.setCameraIndex(index);
            restartCameraIfPreviewing();
        }
    }

    private void restartCameraIfPreviewing() {
        if (cameraHelper != null && cameraHelper.isPreviewing()) {
            cameraHelper.stopCamera();
            cameraHelper.startCamera();
        }
    }

    public boolean isPreviewing() {
        return cameraHelper != null && cameraHelper.isPreviewing();
    }

    // =========================================================
    //  外部视频流输入模式
    // =========================================================

    /**
     * 喂入一帧外部视频流（Bitmap），实时应用当前美颜 + 滤镜并渲染到本控件。
     * 首次调用后进入「视频流模式」，此时请勿同时开启相机。
     *
     * @param frame 输入帧。内部做延迟安全回收，调用方【不得】再复用或 recycle 该 Bitmap
     */
    public void feedFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) return;
        streamMode = true;

        // 2 帧延迟 recycle：GL 线程可能仍在读取上一帧
        if (streamPendingRecycle != null && !streamPendingRecycle.isRecycled()) {
            streamPendingRecycle.recycle();
        }
        streamPendingRecycle = streamLastFrame;
        streamLastFrame = frame;

        int dw = frame.getWidth();
        int dh = frame.getHeight();
        if (dw != lastDisplayW || dh != lastDisplayH) {
            lastDisplayW = dw;
            lastDisplayH = dh;
            gpuImageView.getGPUImage().deleteImage();
            final float ratio = (float) dw / dh;
            post(() -> setPreviewAspectRatio(ratio));
        }

        gpuImageView.getGPUImage().getRenderer().setImageBitmap(frame, false);
        gpuImageView.requestRender();
        setLoadingVisible(false);

        // 首帧到达后启动之前延迟的录像请求（使用实际帧尺寸）
        if (pendingStreamListener != null) {
            VideoStreamRecorder.OnVideoRecordListener l = pendingStreamListener;
            File out = pendingStreamOutput;
            pendingStreamListener = null;
            pendingStreamOutput = null;
            doStartStreamRecording(out, l);
        }

        VideoStreamRecorder rec = streamRecorder;
        if (rec != null && rec.isRecording()) {
            rec.feedFrame(frame);
        }
    }

    /** 截图回调：返回应用当前美颜 + 滤镜后的帧。 */
    public interface OnSnapshotListener {
        void onSnapshot(Bitmap bitmap);
    }

    /** 截取当前帧（含美颜 + 滤镜效果），视频流模式下可用。回调在主线程。 */
    public void takeSnapshot(OnSnapshotListener listener) {
        if (listener == null) return;
        final Bitmap copy;
        try {
            final Bitmap src = (streamLastFrame != null && !streamLastFrame.isRecycled())
                    ? streamLastFrame : null;
            if (src == null) {
                listener.onSnapshot(null);
                return;
            }
            copy = src.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            listener.onSnapshot(null);
            return;
        }
        // GL 处理在后台线程执行，避免阻塞主线程；结果回调切回主线程
        new Thread(() -> {
            Bitmap result;
            try {
                result = gpuImageView.getGPUImage().getBitmapWithFilterApplied(copy);
            } catch (Throwable t) {
                result = copy;
            }
            final Bitmap out = result != null ? result : copy;
            post(() -> listener.onSnapshot(out));
        }, "CameraFilterViewSnapshot").start();
    }

    /**
     * 开始视频流录像（视频流模式下使用），录制的视频包含当前美颜 + 滤镜。
     *
     * @param outputFile 输出 .mp4，传 null 自动生成到应用外部 Movies 目录
     */
    public void startStreamRecording(File outputFile, VideoStreamRecorder.OnVideoRecordListener listener) {
        if (listener == null) return;
        if (streamRecorder != null && streamRecorder.isRecording()) {
            listener.onError("已在录制中");
            return;
        }
        if (streamLastFrame == null) {
            // 尚无帧：记录请求，首帧到达后用实际尺寸启动录像
            pendingStreamOutput = outputFile;
            pendingStreamListener = listener;
            return;
        }
        doStartStreamRecording(outputFile, listener);
    }

    private void doStartStreamRecording(File outputFile,
                                        VideoStreamRecorder.OnVideoRecordListener listener) {
        File dest = outputFile != null ? outputFile : generateStreamVideoFile();
        int w = streamLastFrame.getWidth();
        int h = streamLastFrame.getHeight();
        streamRecorder = new VideoStreamRecorder(dest);
        streamRecorder.prepare(currentBeautyParams, currentFilterStyle, currentFilterIntensity,
                w, h, listener);
    }

    public void stopStreamRecording() {
        if (streamRecorder != null) {
            streamRecorder.stop();
            streamRecorder = null;
        }
    }

    public boolean isStreamRecording() {
        return streamRecorder != null && streamRecorder.isRecording();
    }

    private void releaseStreamRecorder() {
        if (streamRecorder != null) {
            try { streamRecorder.release(); } catch (Throwable ignored) {}
            streamRecorder = null;
        }
        pendingStreamOutput = null;
        pendingStreamListener = null;
    }

    private void syncStreamRecorderFilter() {
        if (streamRecorder != null && streamRecorder.isRecording()) {
            streamRecorder.updateFilter(currentBeautyParams, currentFilterStyle,
                    currentFilterIntensity);
        }
    }

    private File generateStreamVideoFile() {
        Context ctx = getContext();
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = new File(ctx.getFilesDir(), "videos");
        dir.mkdirs();
        return new File(dir, "STREAM_" + System.currentTimeMillis() + ".mp4");
    }
}

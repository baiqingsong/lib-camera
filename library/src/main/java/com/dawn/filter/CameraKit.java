package com.dawn.filter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.File;

/**
 * LibCamera 库的统一对外入口（门面类）。
 * <p>
 * 封装美颜模块（{@link BeautyParams} + {@link GPUImageBeautyFilter}）
 * 和滤镜模块（{@link FilterStyle} + {@link FilterPreset}），
 * 提供相机实时预览 和 Bitmap 离线处理两大场景的统一 API。
 * <p>
 * <b>初始化（在 Application.onCreate 中）：</b>
 * <pre>
 *   CameraKit.init(this);
 * </pre>
 *
 * <b>相机实时预览 + 美颜滤镜：</b>
 * <pre>
 *   // 布局中添加 CameraFilterView
 *   // &lt;com.dawn.filter.CameraFilterView android:id="@+id/cameraView" ... /&gt;
 *
 *   CameraFilterView cameraView = findViewById(R.id.cameraView);
 *   CameraKit.CameraSession session = CameraKit.get().newCameraSession(this, cameraView);
 *
 *   // 设置美颜 + 滤镜（链式调用）
 *   session.setBeautyAndFilter(BeautyParams.defaultCamera(), FilterStyle.FRESH, 0.8f);
 *
 *   // 在 onResume 中启动
 *   session.onResume();
 *   session.start();
 *
 *   // 拍照（已应用当前美颜+滤镜）
 *   session.takePicture(bitmap -> { ... });
 *
 *   // 在 onPause 中停止
 *   session.onPause();
 *   session.stop();
 * </pre>
 *
 * <b>Bitmap 离线处理：</b>
 * <pre>
 *   // 美颜 + 滤镜一步到位
 *   Bitmap result = CameraKit.get().processPhoto(
 *       original,
 *       BeautyParams.defaultCamera(),
 *       FilterStyle.ICE_BLUE,
 *       0.7f
 *   );
 *
 *   // 或使用 FilterManager 进行精细控制
 *   FilterManager fm = CameraKit.get().newFilterManager();
 *   Bitmap r1 = fm.applyBeauty(original, BeautyParams.fromIntensity(0.6f));
 *   Bitmap r2 = fm.applyFilter(original, FilterStyle.PEACH, 0.8f);
 *   fm.release();
 * </pre>
 */
public final class CameraKit {

    private static volatile CameraKit instance;

    private final Context appContext;

    private CameraKit(Context context) {
        this.appContext = context.getApplicationContext();
    }

    // =========================================================
    //  初始化 & 单例
    // =========================================================

    /**
     * 初始化 CameraKit。建议在 {@code Application.onCreate()} 中调用。
     *
     * @param context Application 或 Activity context 均可，内部持有 ApplicationContext。
     */
    public static void init(Context context) {
        if (instance == null) {
            synchronized (CameraKit.class) {
                if (instance == null) {
                    instance = new CameraKit(context);
                }
            }
        }
    }

    /**
     * 获取 CameraKit 单例。需先调用 {@link #init(Context)}，否则抛 IllegalStateException。
     */
    public static CameraKit get() {
        if (instance == null) {
            throw new IllegalStateException(
                    "CameraKit not initialized. Call CameraKit.init(context) in Application.onCreate().");
        }
        return instance;
    }

    /** 是否已初始化 */
    public static boolean isInitialized() {
        return instance != null;
    }

    // =========================================================
    //  Bitmap 离线处理 API
    // =========================================================

    /**
     * 创建图片处理管理器（FilterManager）。
     * <p>
     * 适用于需要对同一个 Bitmap 多次处理的场景（复用 GPU 上下文开销更低）。
     * 使用完毕后必须调用 {@link FilterManager#release()} 释放 GL 资源。
     *
     * @return 新的 FilterManager 实例
     */
    public FilterManager newFilterManager() {
        return new FilterManager(appContext);
    }

    /**
     * 快速对 Bitmap 应用 <b>美颜 + 滤镜</b>（一次性，内部自动释放资源）。
     *
     * @param input           输入图片
     * @param beautyParams    美颜参数，传 null 使用默认值
     * @param filterStyle     滤镜风格，传 null 则不加滤镜
     * @param filterIntensity 滤镜强度 0.0~1.0
     * @return 处理后的 Bitmap
     */
    public Bitmap processPhoto(Bitmap input, BeautyParams beautyParams,
                               FilterStyle filterStyle, float filterIntensity) {
        FilterManager fm = new FilterManager(appContext);
        try {
            return fm.applyModules(
                    input,
                    beautyParams == null ? BeautyParams.defaultCamera() : beautyParams,
                    filterStyle == null ? FilterStyle.ORIGINAL : filterStyle,
                    filterIntensity
            );
        } finally {
            fm.release();
        }
    }

    /**
     * 快速对 Bitmap 应用 <b>美颜</b>（不加滤镜，一次性）。
     *
     * @param input        输入图片
     * @param beautyParams 美颜参数，传 null 使用默认值
     * @return 处理后的 Bitmap
     */
    public Bitmap applyBeauty(Bitmap input, BeautyParams beautyParams) {
        FilterManager fm = new FilterManager(appContext);
        try {
            return fm.applyBeauty(input,
                    beautyParams == null ? BeautyParams.defaultCamera() : beautyParams);
        } finally {
            fm.release();
        }
    }

    /**
     * 快速对 Bitmap 应用 <b>滤镜风格</b>（不加美颜，一次性）。
     *
     * @param input       输入图片
     * @param filterStyle 滤镜风格，传 null 则返回原图
     * @param intensity   滤镜强度 0.0~1.0
     * @return 处理后的 Bitmap
     */
    public Bitmap applyFilter(Bitmap input, FilterStyle filterStyle, float intensity) {
        if (filterStyle == null || filterStyle == FilterStyle.ORIGINAL) {
            return input;
        }
        FilterManager fm = new FilterManager(appContext);
        try {
            return fm.applyFilter(input, filterStyle, intensity);
        } finally {
            fm.release();
        }
    }

    // =========================================================
    //  相机实时预览 API
    // =========================================================

    /**
     * 创建相机会话，用于实时预览美颜 + 滤镜。
     * <p>
     * {@link CameraSession} 对 {@link CameraFilterHelper} 进行了生命周期封装，
     * 在 Activity 中只需按生命周期调用 start/stop 即可。
     *
     * @param activity         宿主 Activity（用于权限请求）
     * @param cameraFilterView 布局中的预览视图
     * @return 新的 CameraSession
     */
    public CameraSession newCameraSession(Activity activity, CameraFilterView cameraFilterView) {
        return new CameraSession(activity, cameraFilterView);
    }

    // =========================================================
    //  便捷工厂方法
    // =========================================================

    /**
     * 创建默认美颜参数（磨皮0.40/美白0.18/红润0.16/亮度0.10/对比度0.16）。
     */
    public static BeautyParams defaultBeautyParams() {
        return BeautyParams.defaultCamera();
    }

    /**
     * 根据统一强度 0.0~1.0 生成美颜参数，适合用单一滑块控制整体美颜程度。
     *
     * @param intensity 0.0 = 关闭美颜，1.0 = 最强美颜
     */
    public static BeautyParams beautyFromIntensity(float intensity) {
        return BeautyParams.fromIntensity(intensity);
    }

    /**
     * 获取所有支持的滤镜风格列表（含推荐顺序）。
     */
    public static java.util.List<FilterStyle> getSupportedFilterStyles() {
        return FilterStyle.getSupportedStyles();
    }

    // =========================================================
    //  CameraSession — 相机实时预览的生命周期封装
    // =========================================================

    /**
     * 相机会话，封装了相机启动/停止、美颜滤镜切换和拍照的完整生命周期。
     * <p>
     * 典型用法（Activity 中）：
     * <pre>
     *   // onCreate
     *   session = CameraKit.get().newCameraSession(this, cameraFilterView);
     *   session.setBeautyAndFilter(BeautyParams.defaultCamera(), FilterStyle.ICE_BLUE, 0.8f);
     *
     *   // onResume
     *   session.onResume();
     *   session.start();
     *
     *   // 用户操作
     *   session.updateBeauty(params);
     *   session.switchFilter(FilterStyle.PEACH, 0.7f);
     *   session.switchCamera();
     *   session.takePicture(bitmap -> savePhoto(bitmap));
     *
     *   // onPause
     *   session.onPause();
     *   session.stop();
     * </pre>
     */
    public static final class CameraSession {

        private final CameraFilterHelper helper;
        private final CameraFilterView filterView;

        private CameraSession(Context context, CameraFilterView filterView) {
            this.filterView = filterView;
            this.helper = new CameraFilterHelper(context, filterView);
        }

        // ── 美颜 & 滤镜 ──────────────────────────────────────────

        /**
         * 同时设置美颜和滤镜风格（立即渲染生效）。
         *
         * @param params    美颜参数，null 使用默认值
         * @param style     滤镜风格，null 保持当前
         * @param intensity 滤镜强度 0.0~1.0
         * @return this（支持链式调用）
         */
        public CameraSession setBeautyAndFilter(BeautyParams params, FilterStyle style,
                                                float intensity) {
            helper.notifyFilterChanged(params, style, intensity);
            return this;
        }

        /**
         * 更新美颜参数（不重建滤镜链，实时生效，性能开销极低）。
         *
         * @param params 新美颜参数，null 使用默认值
         * @return this
         */
        public CameraSession updateBeauty(BeautyParams params) {
            BeautyParams bp = params != null ? params : BeautyParams.defaultCamera();
            helper.notifyFilterChanged(bp,
                    filterView.getCurrentFilterStyle(),
                    filterView.getCurrentFilterIntensity());
            return this;
        }

        /**
         * 切换滤镜风格。
         *
         * @param style     目标滤镜风格
         * @param intensity 滤镜强度 0.0~1.0
         * @return this
         */
        public CameraSession switchFilter(FilterStyle style, float intensity) {
            helper.notifyFilterChanged(filterView.getCurrentBeautyParams(), style, intensity);
            return this;
        }

        /**
         * 调整当前滤镜强度（不重建滤镜，实时生效）。
         *
         * @param intensity 0.0~1.0
         * @return this
         */
        public CameraSession setFilterIntensity(float intensity) {
            helper.notifyFilterChanged(filterView.getCurrentBeautyParams(),
                    filterView.getCurrentFilterStyle(), intensity);
            return this;
        }

        /**
         * 开始录制视频（带音频+滤镜效果）。
         * <p>
         * 录制的视频与预览画面一致，包含当前美颜和滤镜效果。
         * 需要 {@code CAMERA} + {@code RECORD_AUDIO} 权限。
         *
         * @param outputFile 输出 .mp4 文件，传 {@code null} 自动生成到应用外部 Movies 目录
         * @param listener   录制结果回调（主线程），不可为 null
         * @return this（支持链式调用）
         */
        public CameraSession startRecording(File outputFile,
                                            CameraFilterHelper.OnVideoRecordListener listener) {
            helper.startRecording(outputFile, listener);
            return this;
        }

        /**
         * 停止录制视频，结果通过 {@link CameraFilterHelper.OnVideoRecordListener#onVideoSaved} 回调。
         */
        public void stopRecording() {
            helper.stopRecording();
        }

        /**
         * 是否正在录制视频。
         */
        public boolean isRecording() {
            return helper.isRecording();
        }

        /**
         * 检查录制所需权限（CAMERA + RECORD_AUDIO）。
         *
         * @return true 所有权限已授予
         */
        public boolean checkRecordPermissions() {
            return helper.checkRecordPermissions();
        }

        // ── 生命周期 ──────────────────────────────────────────────

        /**
         * 启动相机预览。需已持有 {@code CAMERA} 权限。
         * 通常在 {@code onResume()} 中调用。
         */
        public void start() {
            helper.startCamera();
        }

        /**
         * 停止相机预览并释放硬件资源。
         * 通常在 {@code onPause()} 或 {@code onDestroy()} 中调用。
         */
        public void stop() {
            helper.stopCamera();
        }

        /** 在 Activity/Fragment 的 {@code onResume()} 中调用。 */
        public void onResume() {
            helper.onHostResume();
        }

        /** 在 Activity/Fragment 的 {@code onPause()} 中调用。 */
        public void onPause() {
            helper.onHostPause();
        }

        // ── 功能操作 ──────────────────────────────────────────────

        /**
         * 切换前后摄像头。
         *
         * @return true 切换成功，false 设备不支持或正在切换中
         */
        public boolean switchCamera() {
            return helper.switchCamera();
        }

        /**
         * 判断是否支持切换前后摄像头。
         */
        public boolean canSwitchCamera() {
            return helper.canSwitchCamera();
        }

        /**
         * 拍照。回调时 Bitmap 已应用当前美颜 + 滤镜效果。
         * 回调在主线程执行。
         *
         * @param listener 拍照结果回调，bitmap 为 null 表示失败
         */
        public void takePicture(CameraFilterHelper.OnPictureTakenListener listener) {
            helper.takePicture(listener);
        }

        /** 是否正在预览 */
        public boolean isPreviewing() {
            return helper.isPreviewing();
        }

        /**
         * 处理权限请求结果（在 Activity.onRequestPermissionsResult 中调用）。
         * 若相机权限被授予，自动启动相机。
         * 录制权限授予后，需重新调用 {@link #startRecording} 开始录制。
         */
        public void onRequestPermissionsResult(int requestCode, int[] grantResults) {
            if (requestCode == CameraFilterHelper.REQUEST_CAMERA_PERMISSION
                    && grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                start();
            }
            // REQUEST_AUDIO_PERMISSION: 用户授权后需重新调用 startRecording()
        }

        // ── 高级访问 ──────────────────────────────────────────────

        /**
         * 返回内部 {@link CameraFilterHelper}，供需要精细控制的高级用法。
         */
        public CameraFilterHelper getHelper() {
            return helper;
        }

        /**
         * 返回内部 {@link CameraFilterView}，供直接操作视图。
         */
        public CameraFilterView getFilterView() {
            return filterView;
        }
    }
}

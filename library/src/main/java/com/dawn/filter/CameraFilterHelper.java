package com.dawn.filter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import androidx.camera.core.CameraInfo;

import com.google.common.util.concurrent.ListenableFuture;

import android.graphics.Matrix;

import jp.co.cyberagent.android.gpuimage.GPUImageView;

/**
 * 相机滤镜辅助类（CameraX 版本）。
 * <p>
 * 预览：CameraX ImageAnalysis → NV21 → GPUImage（含美颜+滤镜）→ GLSurfaceView<br>
 * 录制：同一帧 NV21 → {@link GlFilterRecorder}（独立 EGL + MediaCodec Surface）→ MP4<br>
 * 录制的视频与预览画面一致，包含美颜和滤镜效果。
 */
public class CameraFilterHelper {

    private static final String TAG = "CameraFilterHelper";

    public static final int MAX_RECORD_DURATION_MS = 30_000;
    public static final int REQUEST_CAMERA_PERMISSION = 1001;
    public static final int REQUEST_AUDIO_PERMISSION  = 1002;

    private final Context         context;
    private final CameraFilterView filterView;

    private ProcessCameraProvider cameraProvider;
    private Camera                camera;
    private int                   lensFacing       = CameraSelector.LENS_FACING_FRONT;
    private int                   cameraIndex      = 0;  // 外接摄像头按索引切换时使用
    private boolean               isPreviewing     = false;
    private boolean               isSwitchingCamera= false;
    private boolean               isCapturing      = false;
    private boolean               isRecording      = false;
    // 防止多次调用 startCamera() 产生重复的 ProcessCameraProvider 回调
    private volatile int          startCameraGen   = 0;
    private long                  lastSwitchAtMs   = 0L;
    private static final long     SWITCH_DEBOUNCE_MS = 500L;

    // 当前帧尺寸（由 ImageAnalysis 回调更新）
    private volatile int  previewWidth    = 1280;
    private volatile int  previewHeight   = 720;
    private volatile int  rotationDegrees = 0;

    // 录制
    private GlFilterRecorder   glFilterRecorder;
    private File               currentRecordingFile;
    private OnVideoRecordListener videoRecordListener;
    private final Handler      mainHandler      = new Handler(Looper.getMainLooper());
    private Runnable           autoStopRunnable;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();

    public interface OnPictureTakenListener {
        void onPictureTaken(Bitmap bitmap);
    }

    public interface OnVideoRecordListener {
        void onVideoSaved(File videoFile);
        void onError(String message);
    }

    public CameraFilterHelper(Context context, CameraFilterView filterView) {
        this.context    = context;
        this.filterView = filterView;
    }

    // ==========================================================
    // 权限
    // ==========================================================

    public boolean checkPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (context instanceof Activity) {
                ActivityCompat.requestPermissions((Activity) context,
                        new String[]{Manifest.permission.CAMERA},
                        REQUEST_CAMERA_PERMISSION);
            }
            return false;
        }
        return true;
    }

    public boolean checkRecordPermissions() {
        boolean hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasAudio  = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasCamera || !hasAudio) {
            if (context instanceof Activity) {
                List<String> missing = new ArrayList<>();
                if (!hasCamera) missing.add(Manifest.permission.CAMERA);
                if (!hasAudio)  missing.add(Manifest.permission.RECORD_AUDIO);
                ActivityCompat.requestPermissions((Activity) context,
                        missing.toArray(new String[0]), REQUEST_AUDIO_PERMISSION);
            }
            return false;
        }
        return true;
    }

    // ==========================================================
    // 相机生命周期
    // ==========================================================

    public void startCamera() {
        if (!checkPermission()) return;
        if (isPreviewing) return;
        // 每次调用分配一个新的 generation token，使上一轮尚未触发的回调失效，
        // 避免双重 bindCamera() → 第二次 unbindAll() 销毁刚绑定的相机。
        final int gen = ++startCameraGen;
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (gen != startCameraGen) {
                Log.w(TAG, "startCamera: stale callback discarded (gen=" + gen + ")");
                return;
            }
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                Log.e(TAG, "CameraProvider failed", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @SuppressLint("RestrictedApi")
    private void bindCamera() {
        if (cameraProvider == null) return;
        if (!(context instanceof LifecycleOwner)) {
            Log.e(TAG, "context must implement LifecycleOwner");
            return;
        }
        cameraProvider.unbindAll();

        // ── 提前设置持续渲染，不等绑定结果 ──────────────────────────
        GPUImageView gpuView = filterView.getGPUImageView();
        gpuView.setRenderMode(GPUImageView.RENDERMODE_CONTINUOUSLY);

        CameraSelector selector = buildCameraSelector();
        if (selector == null) {
            Log.e(TAG, "No available camera on this device");
            return;
        }

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();

        analysis.setAnalyzer(analysisExecutor, image -> {
            try {
                processFrame(image);
            } finally {
                image.close();
            }
        });

        try {
            camera = cameraProvider.bindToLifecycle(
                    (LifecycleOwner) context, selector, analysis);
            isPreviewing = true;
            Log.i(TAG, "CameraX bound, lensFacing=" + lensFacing + " index=" + cameraIndex);
        } catch (Exception e) {
            Log.e(TAG, "bindToLifecycle failed", e);
            isPreviewing = false;
        }
    }

    @ExperimentalGetImage
    private void processFrame(@NonNull ImageProxy image) {
        int w    = image.getWidth();
        int h    = image.getHeight();
        int rDeg = image.getImageInfo().getRotationDegrees();
        previewWidth    = w;
        previewHeight   = h;
        rotationDegrees = rDeg;

        // ── 预览路径 ──────────────────────────────────────────────────────────
        // 用 CameraX 内置的 YUV→ARGB_8888 转换，完全绕过 GPUImageNativeLibrary.YUVtoRBGA。
        // 该 native 方法若加载失败会直接杀死 GL 线程；同时 onPreviewFrame 里的
        // "if (runOnDraw.isEmpty())" 检查在特定时机会丢弃所有帧。
        Bitmap srcBitmap = image.toBitmap();

        // 旋转 + 前置摄像头水平镜像（在 CPU 侧用 Matrix 处理，不依赖 GPUImage setRotation）
        boolean isFront = (lensFacing == CameraSelector.LENS_FACING_FRONT);
        Bitmap displayBitmap;
        if (rDeg == 0 && !isFront) {
            displayBitmap = srcBitmap;
        } else {
            Matrix matrix = new Matrix();
            matrix.setRotate(rDeg);
            if (isFront) {
                matrix.postScale(-1f, 1f);
            }
            displayBitmap = Bitmap.createBitmap(srcBitmap, 0, 0, w, h, matrix, true);
            srcBitmap.recycle();
        }

        // 上传到 GPUImage 渲染管线（美颜 + 风格滤镜）
        // setImageBitmap(bitmap, recycle=true)：GL 线程上传后自动回收 Bitmap，无内存泄漏
        GPUImageView gpuView = filterView.getGPUImageView();
        gpuView.getGPUImage().getRenderer().setImageBitmap(displayBitmap, true);
        gpuView.requestRender();

        // ── 录制路径 ──────────────────────────────────────────────────────────
        if (isRecording && glFilterRecorder != null) {
            byte[] nv21 = yuv420ToNv21(image);  // 录制仍用 NV21 路径
            glFilterRecorder.enqueueFrame(nv21, w, h);
        }
    }

    /**
     * YUV_420_888 → NV21 (Y plane + 交错 VU plane)。
     * 正确处理各平面的 rowStride / pixelStride padding，适配所有设备。
     */
    private static byte[] yuv420ToNv21(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int w = image.getWidth();
        int h = image.getHeight();

        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yRowStride  = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uPixStride  = planes[1].getPixelStride();

        byte[] nv21 = new byte[w * h * 3 / 2];

        // ── Y 平面：逐行复制，去除行末 padding ──
        int yDst = 0;
        for (int row = 0; row < h; row++) {
            yBuf.position(row * yRowStride);
            yBuf.get(nv21, yDst, w);
            yDst += w;
        }

        // ── UV 平面 → NV21 (VU 交错) ──
        int uvH   = h / 2;
        int uvW   = w / 2;
        int uvDst = w * h;

        if (uPixStride == 2) {
            // 半平面（NV12/NV21 内存布局）：用绝对索引读取，避免流式读取越界
            for (int row = 0; row < uvH; row++) {
                for (int col = 0; col < uvW; col++) {
                    int pos = row * uvRowStride + col * 2;
                    nv21[uvDst++] = vBuf.get(pos);  // V
                    nv21[uvDst++] = uBuf.get(pos);  // U
                }
            }
        } else {
            // 完全平面（pixelStride == 1）
            for (int row = 0; row < uvH; row++) {
                for (int col = 0; col < uvW; col++) {
                    int pos = row * uvRowStride + col;
                    nv21[uvDst++] = vBuf.get(pos);  // V
                    nv21[uvDst++] = uBuf.get(pos);  // U
                }
            }
        }
        return nv21;
    }

    public void stopCamera() {
        startCameraGen++;        // 使所有待触发的 startCamera() 回调失效
        if (isRecording) {
            stopRecordingInternal(false);
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        isPreviewing = false;
    }

    /**
     * 构建摄像头选择器。
     * <ul>
     *   <li>先尝试 {@code lensFacing}（FRONT / BACK）——适配普通正而且前/后置摄像头。
     *   <li>找不到时，按 {@code cameraIndex} 取当前可用列表内对应条目——
     *       适配外接 USB 摄像头、只有 EXTERNAL 类型摄像头的设备（如 rk3568_r）。
     * </ul>
     */
    private CameraSelector buildCameraSelector() {
        if (cameraProvider == null) return null;
        List<CameraInfo> available = cameraProvider.getAvailableCameraInfos();
        if (available.isEmpty()) return null;

        // 1. 尝试按 lensFacing 匹配
        CameraSelector byFacing = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();
        try {
            if (cameraProvider.hasCamera(byFacing)) {
                return byFacing;
            }
        } catch (Exception ignored) {}

        // 2. 外接/无 lensFacing 设备：按索引取当前可用摄像头
        Log.w(TAG, "No FRONT/BACK camera, using available camera index=" + cameraIndex
                + " (total=" + available.size() + ")");
        cameraIndex = Math.min(cameraIndex, available.size() - 1);
        return available.get(cameraIndex).getCameraSelector();
    }

    // ==========================================================================================

    public boolean canSwitchCamera() {
        if (cameraProvider == null) return false;
        // 优先检查标准前/后置
        try {
            if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    && cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                return true;
            }
        } catch (Exception ignored) {}
        // 外接摄像头：有多个可用摄像头时支持切换
        return cameraProvider.getAvailableCameraInfos().size() > 1;
    }

    public boolean switchCamera() {
        long now = System.currentTimeMillis();
        if (isSwitchingCamera || isCapturing) return false;
        if (now - lastSwitchAtMs < SWITCH_DEBOUNCE_MS) return false;
        lastSwitchAtMs    = now;
        isSwitchingCamera = true;

        if (isRecording) stopRecordingInternal(true);

        // 尝试切换 FRONT/BACK；如果是外接摄像头则按索引循环
        List<CameraInfo> available = cameraProvider != null
                ? cameraProvider.getAvailableCameraInfos() : null;
        boolean hasStandardFacing = false;
        try {
            hasStandardFacing = cameraProvider != null
                    && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    && cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA);
        } catch (Exception ignored) {}

        if (hasStandardFacing) {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_FRONT)
                    ? CameraSelector.LENS_FACING_BACK
                    : CameraSelector.LENS_FACING_FRONT;
        } else if (available != null && available.size() > 1) {
            cameraIndex = (cameraIndex + 1) % available.size();
        }

        filterView.recreateGPUImageView();
        bindCamera();
        isSwitchingCamera = false;
        return true;
    }

    // ==========================================================
    // 拍照
    // ==========================================================

    public void takePicture(final OnPictureTakenListener listener) {
        if (listener == null) return;
        GPUImageView gpuImageView = filterView.getGPUImageView();
        if (!isPreviewing || isCapturing || isSwitchingCamera) return;
        isCapturing = true;
        try {
            gpuImageView.saveToPictures("LibFilter", System.currentTimeMillis() + ".jpg",
                    uri -> {
                        Bitmap bitmap = null;
                        try {
                            bitmap = gpuImageView.getGPUImage().getBitmapWithFilterApplied();
                        } catch (Throwable t) {
                            Log.e(TAG, "getBitmapWithFilterApplied failed", t);
                        }
                        Bitmap finalBitmap = bitmap;
                        Runnable notify = () -> {
                            try {
                                listener.onPictureTaken(finalBitmap);
                            } finally {
                                isCapturing = false;
                            }
                        };
                        if (context instanceof Activity) {
                            ((Activity) context).runOnUiThread(notify);
                        } else {
                            notify.run();
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "saveToPictures failed", t);
            isCapturing = false;
        }
    }

    // ==========================================================
    // 生命周期透传
    // ==========================================================

    public void onHostResume() {
        try { filterView.getGPUImageView().onResume(); } catch (Throwable ignored) {}
    }

    public void onHostPause() {
        try { filterView.getGPUImageView().onPause(); } catch (Throwable ignored) {}
    }

    public boolean isPreviewing() { return isPreviewing; }
    public boolean isRecording()  { return isRecording; }

    // ==========================================================
    // 滤镜同步（供 CameraKit.CameraSession 调用）
    // ==========================================================

    /**
     * 同时更新预览滤镜和录制滤镜（如果正在录制）。
     * CameraKit.CameraSession 的所有滤镜操作都通过此方法路由，
     * 保证预览和录制效果一致。
     */
    public void notifyFilterChanged(BeautyParams beautyParams, FilterStyle filterStyle,
                                    float filterIntensity) {
        filterView.setBeautyAndFilter(beautyParams, filterStyle, filterIntensity);
        if (isRecording && glFilterRecorder != null) {
            glFilterRecorder.updateFilter(beautyParams, filterStyle, filterIntensity);
        }
    }

    // ==========================================================
    // 录制
    // ==========================================================

    public void startRecording(File outputFile, OnVideoRecordListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        if (!checkRecordPermissions()) {
            listener.onError("缺少权限（CAMERA / RECORD_AUDIO）");
            return;
        }
        if (!isPreviewing || isSwitchingCamera || isRecording) {
            listener.onError("相机未就绪或正在进行其他操作");
            return;
        }

        final File dest = (outputFile != null) ? outputFile : generateVideoFile();
        if (dest.getParentFile() != null) dest.getParentFile().mkdirs();

        videoRecordListener  = listener;
        currentRecordingFile = dest;

        // 读取当前滤镜参数，确保录制与预览一致
        BeautyParams bp = filterView.getCurrentBeautyParams();
        FilterStyle  fs = filterView.getCurrentFilterStyle();
        float        fi = filterView.getCurrentFilterIntensity();

        glFilterRecorder = new GlFilterRecorder(dest, previewWidth, previewHeight,
                bp, fs, fi,
                new GlFilterRecorder.RecorderCallback() {
                    @Override
                    public void onVideoSaved(File file) {
                        isRecording = false;
                        cancelAutoStop();
                        videoRecordListener  = null;
                        currentRecordingFile = null;
                        listener.onVideoSaved(file);
                    }
                    @Override
                    public void onError(String message) {
                        isRecording = false;
                        cancelAutoStop();
                        videoRecordListener  = null;
                        currentRecordingFile = null;
                        listener.onError(message);
                    }
                });

        // 设置 MP4 方向 hint（让播放器正确旋转）
        glFilterRecorder.setOrientationHint(rotationDegrees);

        try {
            glFilterRecorder.prepare();
            glFilterRecorder.start();
            isRecording = true;

            autoStopRunnable = () -> {
                Log.i(TAG, "录制自动停止（超过 " + MAX_RECORD_DURATION_MS / 1000 + "s）");
                stopRecording();
            };
            mainHandler.postDelayed(autoStopRunnable, MAX_RECORD_DURATION_MS);
            Log.i(TAG, "录制开始（含滤镜）: " + dest.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            if (glFilterRecorder != null) { glFilterRecorder.release(); glFilterRecorder = null; }
            isRecording         = false;
            videoRecordListener = null;
            currentRecordingFile= null;
            listener.onError("录制启动失败：" + e.getMessage());
        }
    }

    public void stopRecording() {
        stopRecordingInternal(false);
    }

    private void stopRecordingInternal(boolean forced) {
        cancelAutoStop();
        if (!isRecording) return;
        if (glFilterRecorder != null) {
            if (forced) {
                OnVideoRecordListener l = videoRecordListener;
                glFilterRecorder.release();
                glFilterRecorder    = null;
                isRecording         = false;
                videoRecordListener = null;
                currentRecordingFile= null;
                if (l != null) mainHandler.post(() -> l.onError("录制被强制中断"));
            } else {
                glFilterRecorder.stop();
                glFilterRecorder = null;
                // isRecording 由 RecorderCallback 回调置 false
            }
        } else {
            isRecording = false;
        }
    }

    private void cancelAutoStop() {
        if (autoStopRunnable != null) {
            mainHandler.removeCallbacks(autoStopRunnable);
            autoStopRunnable = null;
        }
    }

    private File generateVideoFile() {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = new File(context.getFilesDir(), "videos");
        dir.mkdirs();
        return new File(dir, "VID_" + System.currentTimeMillis() + ".mp4");
    }
}
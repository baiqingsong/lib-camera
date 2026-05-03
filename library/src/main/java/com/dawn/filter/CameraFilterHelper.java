package com.dawn.filter;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import jp.co.cyberagent.android.gpuimage.GPUImageView;

/**
 * 相机滤镜辅助类。
 * 管理 Camera 生命周期，并与 CameraFilterView 配合实现实时滤镜预览。
 * <p>
 * 使用示例：
 * <pre>
 *   CameraFilterView view = findViewById(R.id.cameraFilterView);
 *   CameraFilterHelper helper = new CameraFilterHelper(this, view);
 *   helper.startCamera();
 *   // 切换前后摄像头
 *   helper.switchCamera();
 *   // 拍照
 *   helper.takePicture(bitmap -> { ... });
 *   // 在 onDestroy 中
 *   helper.stopCamera();
 * </pre>
 */
@SuppressWarnings("deprecation")
public class CameraFilterHelper {

    private static final String TAG = "CameraFilterHelper";
    private static final float TARGET_PREVIEW_RATIO = 4f / 3f;
    private static final long CAMERA_SWITCH_DEBOUNCE_MS = 500L;
    private static final int MAX_PREVIEW_AREA = 1920 * 1080;
    private static final int MAX_PICTURE_AREA = 2560 * 1440;
    private static final int MAX_VIDEO_AREA    = 1920 * 1080;
    private static final int VIDEO_BITRATE     = 5_000_000; // 5 Mbps
    private static final int VIDEO_FRAME_RATE  = 30;
    /** 最长录制时长（毫秒），到达后自动停止 */
    public  static final int MAX_RECORD_DURATION_MS = 30_000;
    public static final int REQUEST_CAMERA_PERMISSION = 1001;
    public static final int REQUEST_AUDIO_PERMISSION  = 1002;

    private final Context context;
    private final CameraFilterView filterView;
    private final Object cameraLock = new Object();
    private Camera camera;
    private int currentCameraId = -1;
    private boolean isPreviewing = false;
    private boolean isSwitchingCamera = false;
    private boolean isCapturing = false;
    private boolean isRecording = false;
    private long lastSwitchAtMs = 0L;

    private MediaRecorder mediaRecorder;
    private File currentRecordingFile;
    private OnVideoRecordListener videoRecordListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable autoStopRunnable;

    public interface OnPictureTakenListener {
        void onPictureTaken(Bitmap bitmap);
    }

    /** 视频录制回调 */
    public interface OnVideoRecordListener {
        /** 录制成功，videoFile 是已保存的 MP4 文件 */
        void onVideoSaved(File videoFile);
        /** 录制出错或时间过短无法保存 */
        void onError(String message);
    }

    public CameraFilterHelper(Context context, CameraFilterView filterView) {
        this.context = context;
        this.filterView = filterView;
        this.currentCameraId = resolveInitialCameraId();
    }

    /**
     * 检查相机权限，如果未授权则请求权限。
     *
     * @return true 已有权限，false 需要请求
     */
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

    /**
     * 打开相机并开始预览。
     */
    public void startCamera() {
        if (!checkPermission()) {
            return;
        }
        synchronized (cameraLock) {
            if (isPreviewing) {
                return;
            }
            if (currentCameraId < 0) {
                currentCameraId = resolveInitialCameraId();
            }
            if (currentCameraId < 0) {
                Log.e(TAG, "No available camera on device");
                return;
            }
            try {
                camera = Camera.open(currentCameraId);
                setupCameraParameters();
                GPUImageView gpuImageView = filterView.getGPUImageView();
                gpuImageView.setUpCamera(camera,
                        getCameraRotation(),
                        isFrontFacing(currentCameraId),
                        false);
                isPreviewing = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to open camera", e);
                isPreviewing = false;
            }
        }
    }

    /**
     * 停止相机预览并释放资源。
     */
    public void stopCamera() {
        synchronized (cameraLock) {
            releaseCamera();
        }
    }

    private void releaseCamera() {
        // 取消自动停止计时器
        if (autoStopRunnable != null) {
            mainHandler.removeCallbacks(autoStopRunnable);
            autoStopRunnable = null;
        }
        // 如果正在录制，先停止并保存
        File savedFile = null;
        OnVideoRecordListener savedListener = null;
        boolean recordSuccess = false;
        if (isRecording) {
            savedFile = currentRecordingFile;
            savedListener = videoRecordListener;
            videoRecordListener = null;
            currentRecordingFile = null;
            recordSuccess = stopMediaRecorderLocked();
        }
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewCallback(null);
                camera.release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to release camera", e);
            }
            camera = null;
        }
        isPreviewing = false;
        isCapturing = false;
        // 异步回调（避免在 synchronized 块内回调导致死锁）
        if (savedListener != null) {
            final File file = savedFile;
            final OnVideoRecordListener listener = savedListener;
            final boolean success = recordSuccess;
            mainHandler.post(() -> {
                if (success && file != null && file.exists() && file.length() > 0) {
                    listener.onVideoSaved(file);
                }
                // 强制关机时不触发 onError
            });
        }
    }

    /**
     * 切换前后摄像头。
     * <p>
     * 通过销毁并重建 GPUImageView 来获得全新的 GL 上下文和 SurfaceTexture，
     * 彻底避免 GLThread 访问已失效 SurfaceTexture 导致的 SIGSEGV。
     */
    public boolean switchCamera() {
        synchronized (cameraLock) {
            long now = System.currentTimeMillis();
            if (isSwitchingCamera || isCapturing) {
                return false;
            }
            if (now - lastSwitchAtMs < CAMERA_SWITCH_DEBOUNCE_MS) {
                return false;
            }
            lastSwitchAtMs = now;
            isSwitchingCamera = true;
            // 切换摄像头前先停止录制
            if (isRecording) {
                // 取消自动停止计时器
                if (autoStopRunnable != null) {
                    mainHandler.removeCallbacks(autoStopRunnable);
                    autoStopRunnable = null;
                }
                File savedFile = currentRecordingFile;
                OnVideoRecordListener savedListener = videoRecordListener;
                videoRecordListener = null;
                currentRecordingFile = null;
                boolean success = stopMediaRecorderLocked();
                if (savedListener != null) {
                    final File f = savedFile;
                    final OnVideoRecordListener l = savedListener;
                    final boolean ok = success;
                    mainHandler.post(() -> {
                        if (ok && f != null && f.exists() && f.length() > 0) l.onVideoSaved(f);
                        else l.onError("切换摄像头，录制已强制停止");
                    });
                }
            }
            try {
                if (currentCameraId < 0) {
                    currentCameraId = resolveInitialCameraId();
                }
                if (currentCameraId < 0) {
                    return false;
                }
                int targetFacing = isFrontFacing(currentCameraId)
                        ? Camera.CameraInfo.CAMERA_FACING_BACK
                        : Camera.CameraInfo.CAMERA_FACING_FRONT;
                int targetCameraId = findCameraIdByFacing(targetFacing);
                if (targetCameraId < 0 || targetCameraId == currentCameraId) {
                    Log.w(TAG, "No alternate camera found for facing=" + targetFacing);
                    return false;
                }

                // 1. 释放旧相机（stopPreview + release）
                releaseCamera();

                // 2. 销毁旧的 GPUImageView 并创建全新的实例。
                //    旧的 GLThread/SurfaceTexture 随 View 销毁而终止，
                //    新的 GPUImageView 拥有全新的 GL 上下文，不存在竞争。
                filterView.recreateGPUImageView();

                // 3. 在全新的 GPUImageView 上打开新相机
                currentCameraId = targetCameraId;
                camera = Camera.open(currentCameraId);
                setupCameraParameters();
                GPUImageView gpuImageView = filterView.getGPUImageView();
                gpuImageView.setUpCamera(camera,
                        getCameraRotation(),
                        isFrontFacing(currentCameraId),
                        false);
                isPreviewing = true;
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to switch camera", e);
                isPreviewing = false;
                return false;
            } finally {
                isSwitchingCamera = false;
            }
        }
    }

    /**
     * 拍照并返回应用了当前滤镜的 Bitmap。
     */
    public void takePicture(final OnPictureTakenListener listener) {
        if (listener == null) {
            return;
        }
        GPUImageView gpuImageView = filterView.getGPUImageView();
        synchronized (cameraLock) {
            if (!isPreviewing || isCapturing || isSwitchingCamera) {
                return;
            }
            isCapturing = true;
        }
        try {
            gpuImageView.saveToPictures("LibFilter", System.currentTimeMillis() + ".jpg",
                    uri -> {
                        Bitmap bitmap = null;
                        try {
                            // 低端机/切换瞬间 GPUImage 可能尚未准备好当前帧，需容错。
                            bitmap = gpuImageView.getGPUImage().getBitmapWithFilterApplied();
                        } catch (Throwable t) {
                            Log.e(TAG, "Failed to get filtered bitmap", t);
                        }
                        Bitmap finalBitmap = bitmap;
                        Runnable notifyResult = () -> {
                            try {
                                listener.onPictureTaken(finalBitmap);
                            } finally {
                                synchronized (cameraLock) {
                                    isCapturing = false;
                                }
                            }
                        };
                        // saveToPictures 回调可能在后台线程，需切回主线程。
                        if (context instanceof Activity) {
                            ((Activity) context).runOnUiThread(notifyResult);
                        } else {
                            notifyResult.run();
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save picture", t);
            synchronized (cameraLock) {
                isCapturing = false;
            }
        }
    }

    /**
     * 是否正在预览。
     */
    public boolean isPreviewing() {
        return isPreviewing;
    }

    /**
     * 获取当前摄像头 ID。
     */
    public int getCurrentCameraId() {
        return currentCameraId;
    }

    /**
     * 设备是否同时具备前后摄像头（可切换）。
     */
    public boolean canSwitchCamera() {
        return findCameraIdByFacing(Camera.CameraInfo.CAMERA_FACING_BACK) >= 0
                && findCameraIdByFacing(Camera.CameraInfo.CAMERA_FACING_FRONT) >= 0;
    }

    public void onHostResume() {
        try {
            filterView.getGPUImageView().onResume();
        } catch (Throwable t) {
            Log.w(TAG, "GPUImageView onResume failed", t);
        }
    }

    public void onHostPause() {
        try {
            filterView.getGPUImageView().onPause();
        } catch (Throwable t) {
            Log.w(TAG, "GPUImageView onPause failed", t);
        }
    }

    // =========================================================
    //  视频录制 API
    // =========================================================

    /**
     * 检查录制所需权限（CAMERA + RECORD_AUDIO）。
     * 如未授权则自动弹出系统权限请求。
     *
     * @return true 表示所有权限已授予，可以调用 startRecording
     */
    public boolean checkRecordPermissions() {
        boolean hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
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

    /**
     * 开始录制视频（带音频）。
     * <p>
     * <b>注意：</b>Camera1 API 限制，录制的视频不包含 GPU 滤镜/美颜效果；
     * 预览界面仍会正常显示美颜 + 滤镜。
     * <p>
     * 需要 {@code CAMERA} + {@code RECORD_AUDIO} 权限。
     *
     * @param outputFile 输出 MP4 文件，传 {@code null} 则自动生成到应用外部存储 Movies 目录
     * @param listener   录制结果回调（主线程回调），不可为 null
     */
    public void startRecording(File outputFile, OnVideoRecordListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        if (!checkRecordPermissions()) {
            listener.onError("缺少录制权限（CAMERA / RECORD_AUDIO）");
            return;
        }
        synchronized (cameraLock) {
            if (!isPreviewing || isSwitchingCamera || isRecording) {
                listener.onError("相机未就绪或正在进行其他操作");
                return;
            }
            if (camera == null) {
                listener.onError("相机未打开");
                return;
            }
            final File dest = (outputFile != null) ? outputFile : generateVideoFile();
            if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
            try {
                // Camera1 + MediaRecorder: 必须先停预览再 unlock，否则 start() 在部分设备上会失败
                camera.stopPreview();
                camera.unlock();
                mediaRecorder = new MediaRecorder();
                mediaRecorder.setCamera(camera);
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
                // 用 CamcorderProfile 代替手动参数：自动选择设备支持的安全配置
                CamcorderProfile profile = CamcorderProfile.hasProfile(currentCameraId, CamcorderProfile.QUALITY_HIGH)
                        ? CamcorderProfile.get(currentCameraId, CamcorderProfile.QUALITY_HIGH)
                        : CamcorderProfile.get(currentCameraId, CamcorderProfile.QUALITY_LOW);
                // 限制最高分辨率不超过 1920×1080
                if (profile.videoFrameWidth > 1920 || profile.videoFrameHeight > 1080) {
                    if (CamcorderProfile.hasProfile(currentCameraId, CamcorderProfile.QUALITY_1080P)) {
                        profile = CamcorderProfile.get(currentCameraId, CamcorderProfile.QUALITY_1080P);
                    }
                }
                mediaRecorder.setProfile(profile);
                Camera.CameraInfo camInfo = new Camera.CameraInfo();
                Camera.getCameraInfo(currentCameraId, camInfo);
                mediaRecorder.setOrientationHint(camInfo.orientation);
                mediaRecorder.setOutputFile(dest.getAbsolutePath());
                mediaRecorder.prepare();
                mediaRecorder.start();
                isRecording = true;
                currentRecordingFile = dest;
                videoRecordListener = listener;
                Log.i(TAG, "Recording started: " + dest.getAbsolutePath());
                // 启动自动停止计时器（30 秒）
                autoStopRunnable = () -> {
                    Log.i(TAG, "Recording auto-stopped after " + MAX_RECORD_DURATION_MS + "ms");
                    stopRecording();
                };
                mainHandler.postDelayed(autoStopRunnable, MAX_RECORD_DURATION_MS);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "startRecording failed", e);
                if (mediaRecorder != null) {
                    try { mediaRecorder.reset(); mediaRecorder.release(); } catch (Exception ignored) {}
                    mediaRecorder = null;
                }
                isRecording = false;
                if (camera != null) { try { camera.lock(); } catch (Exception ignored) {} }
                reconnectGPUImagePreview();
                listener.onError("录制启动失败：" + e.getMessage());
            }
        }
    }

    /**
     * 停止录制视频。
     * 录制结果（文件路径）通过 {@link OnVideoRecordListener#onVideoSaved(File)} 在主线程回调。
     */
    public void stopRecording() {
        // 取消自动停止计时器
        if (autoStopRunnable != null) {
            mainHandler.removeCallbacks(autoStopRunnable);
            autoStopRunnable = null;
        }
        File savedFile;
        OnVideoRecordListener savedListener;
        boolean success;
        synchronized (cameraLock) {
            if (!isRecording) return;
            savedFile = currentRecordingFile;
            savedListener = videoRecordListener;
            videoRecordListener = null;
            currentRecordingFile = null;
            success = stopMediaRecorderLocked();
        }
        // 释放锁后重新绑定 GPUImage SurfaceTexture，恢复预览
        reconnectGPUImagePreview();
        if (savedListener != null) {
            final File file = savedFile;
            final OnVideoRecordListener listener = savedListener;
            final boolean saved = success;
            mainHandler.post(() -> {
                if (saved && file != null && file.exists() && file.length() > 0) {
                    listener.onVideoSaved(file);
                } else {
                    if (file != null) file.delete();
                    listener.onError("录制时间过短或录制异常，视频未保存");
                }
            });
        }
    }

    /**
     * 是否正在录制视频。
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 停止并释放 MediaRecorder（必须在持有 cameraLock 时调用）。
     * 完成后相机恢复 locked 状态。
     *
     * @return true 表示 MediaRecorder.stop() 执行成功（文件有效）
     */
    private boolean stopMediaRecorderLocked() {
        if (mediaRecorder == null) {
            isRecording = false;
            return false;
        }
        boolean success = false;
        try {
            mediaRecorder.stop();
            success = true;
        } catch (RuntimeException e) {
            // 录制时间极短（< 1帧）时 stop() 会抛异常，文件将无效
            Log.w(TAG, "MediaRecorder.stop() RuntimeException（录制时间过短）", e);
        }
        try {
            mediaRecorder.reset();
            mediaRecorder.release();
        } catch (Exception ignored) {}
        mediaRecorder = null;
        isRecording = false;
        if (camera != null) {
            try {
                camera.lock();
                // 注意：不在此处 startPreview，纹理需由调用方通过 reconnectGPUImagePreview() 重新绑定
            } catch (Exception e) {
                Log.w(TAG, "camera.lock after stopMediaRecorder failed", e);
            }
        }
        return success;
    }

    /**
     * 录制停止后，重新将相机接入 GPUImage SurfaceTexture 以恢复实时预览。
     * 必须在 camera.lock() 之后调用，可在主线程或 GL 线程调用。
     */
    private void reconnectGPUImagePreview() {
        synchronized (cameraLock) {
            if (camera == null || !isPreviewing) return;
            try {
                GPUImageView gpuImageView = filterView.getGPUImageView();
                gpuImageView.setUpCamera(camera,
                        getCameraRotation(),
                        isFrontFacing(currentCameraId),
                        false);
            } catch (Exception e) {
                Log.w(TAG, "reconnectGPUImagePreview failed", e);
            }
        }
    }

    /**
     * 选择适合录制的视频尺寸（不超过 {@link #MAX_VIDEO_AREA}）。
     */
    private Camera.Size chooseVideoSize() {
        if (camera == null) return null;
        try {
            Camera.Parameters params = camera.getParameters();
            List<Camera.Size> videoSizes = params.getSupportedVideoSizes();
            if (videoSizes == null || videoSizes.isEmpty()) {
                return params.getPreviewSize();
            }
            Camera.Size best = null;
            for (Camera.Size size : videoSizes) {
                int area = size.width * size.height;
                if (area > MAX_VIDEO_AREA) continue;
                if (best == null || area > best.width * best.height) {
                    best = size;
                }
            }
            return best != null ? best : params.getPreviewSize();
        } catch (Exception e) {
            Log.w(TAG, "chooseVideoSize failed", e);
            return null;
        }
    }

    /**
     * 自动生成视频输出文件（应用私有外部存储 Movies 目录，无需存储权限）。
     */
    private File generateVideoFile() {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = new File(context.getFilesDir(), "videos");
        dir.mkdirs();
        return new File(dir, "VID_" + System.currentTimeMillis() + ".mp4");
    }

    private void setupCameraParameters() {
        if (camera == null) return;
        try {
            Camera.Parameters params = camera.getParameters();
            Camera.Size previewSize = chooseBestSize(
                    params.getSupportedPreviewSizes(), TARGET_PREVIEW_RATIO, MAX_PREVIEW_AREA);
            if (previewSize != null) {
                params.setPreviewSize(previewSize.width, previewSize.height);
                filterView.setPreviewAspectRatio(previewSize.height / (float) previewSize.width);
            }

            Camera.Size pictureSize = chooseBestSize(
                    params.getSupportedPictureSizes(), TARGET_PREVIEW_RATIO, MAX_PICTURE_AREA);
            if (pictureSize != null) {
                params.setPictureSize(pictureSize.width, pictureSize.height);
            }

            // 自动对焦（如果支持）
            if (params.getSupportedFocusModes()
                    .contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(params);
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup camera parameters", e);
        }
    }

    private int getCameraRotation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(currentCameraId, info);
        return info.orientation;
    }

    private int resolveInitialCameraId() {
        // 优先使用前置摄像头
        int frontId = findCameraIdByFacing(Camera.CameraInfo.CAMERA_FACING_FRONT);
        if (frontId >= 0) {
            return frontId;
        }
        int backId = findCameraIdByFacing(Camera.CameraInfo.CAMERA_FACING_BACK);
        if (backId >= 0) {
            return backId;
        }
        return -1;
    }

    private int findCameraIdByFacing(int facing) {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == facing) {
                return i;
            }
        }
        return -1;
    }

    private boolean isFrontFacing(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
    }

    private Camera.Size chooseBestSize(List<Camera.Size> sizes, float targetRatio, int maxArea) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }

        Camera.Size bestSize = null;
        float bestRatioDiff = Float.MAX_VALUE;
        int bestArea = -1;

        for (Camera.Size size : sizes) {
            float ratio = size.width / (float) size.height;
            float ratioDiff = Math.abs(ratio - targetRatio);
            int area = size.width * size.height;
            if (maxArea > 0 && area > maxArea) {
                continue;
            }

            if (bestSize == null
                    || ratioDiff < bestRatioDiff - 0.01f
                    || (Math.abs(ratioDiff - bestRatioDiff) < 0.01f && area > bestArea)) {
                bestSize = size;
                bestRatioDiff = ratioDiff;
                bestArea = area;
            }
        }

        if (bestSize == null) {
            for (Camera.Size size : sizes) {
                float ratio = size.width / (float) size.height;
                float ratioDiff = Math.abs(ratio - targetRatio);
                int area = size.width * size.height;
                if (bestSize == null
                        || ratioDiff < bestRatioDiff - 0.01f
                        || (Math.abs(ratioDiff - bestRatioDiff) < 0.01f && area < bestArea)) {
                    bestSize = size;
                    bestRatioDiff = ratioDiff;
                    bestArea = area;
                }
            }
        }

        return bestSize;
    }
}

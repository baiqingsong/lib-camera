package com.dawn.filter;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.util.Log;

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
    public static final int REQUEST_CAMERA_PERMISSION = 1001;

    private final Context context;
    private final CameraFilterView filterView;
    private final Object cameraLock = new Object();
    private Camera camera;
    private int currentCameraId = -1;
    private boolean isPreviewing = false;
    private boolean isSwitchingCamera = false;
    private boolean isCapturing = false;
    private long lastSwitchAtMs = 0L;

    public interface OnPictureTakenListener {
        void onPictureTaken(Bitmap bitmap);
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

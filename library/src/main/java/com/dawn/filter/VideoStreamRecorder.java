package com.dawn.filter;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * 视频流录像器：把外部逐帧喂入的 Bitmap（叠加当前美颜 + 滤镜）编码为 MP4。
 * <p>
 * 与相机模式无关，适用于「外部通过视频流方式输入」的场景：外部拿到一帧 Bitmap 后，
 * 先调用 {@link #feedFrame(Bitmap)}，停止时调用 {@link #stop()}。
 * <p>
 * 使用示例：
 * <pre>
 *   VideoStreamRecorder rec = new VideoStreamRecorder(new File(path, "out.mp4"));
 *   rec.prepare(BeautyParams.defaultCamera(), FilterStyle.FRESH, 0.8f, 720, 1280,
 *       new VideoStreamRecorder.OnVideoRecordListener() {
 *           public void onVideoSaved(File f) { ... }
 *           public void onError(String msg) { ... }
 *       });
 *   // 每来一帧：
 *   rec.feedFrame(bitmap);
 *   // 结束：
 *   rec.stop();
 * </pre>
 */
public class VideoStreamRecorder {

    /** 录像结果回调（主线程）。 */
    public interface OnVideoRecordListener {
        void onVideoSaved(File videoFile);
        void onError(String message);
    }

    private volatile GlFilterRecorder recorder;
    private final File outputFile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean recording = false;
    private volatile boolean preparing = false;
    private volatile boolean cancelled = false;

    public VideoStreamRecorder(File outputFile) {
        if (outputFile == null) {
            throw new IllegalArgumentException("outputFile must not be null");
        }
        this.outputFile = outputFile;
    }

    /**
     * 准备并启动录像（编码器初始化在后台线程执行）。
     *
     * @param beautyParams 美颜参数，null 使用默认
     * @param filterStyle  滤镜风格，null 为原图
     * @param intensity    滤镜强度 0.0~1.0
     * @param width        视频帧宽（须与 {@link #feedFrame(Bitmap)} 的 Bitmap 一致）
     * @param height       视频帧高
     * @param listener     结果回调（不可为 null）
     */
    public void prepare(BeautyParams beautyParams, FilterStyle filterStyle, float intensity,
                        int width, int height, OnVideoRecordListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (preparing || recording) {
            listener.onError("已在录制中");
            return;
        }
        if (width <= 0 || height <= 0) {
            listener.onError("无效的视频尺寸: " + width + "x" + height);
            return;
        }

        if (outputFile.getParentFile() != null) {
            outputFile.getParentFile().mkdirs();
        }

        final BeautyParams bp = beautyParams != null ? beautyParams : BeautyParams.defaultCamera();
        final FilterStyle  fs = filterStyle  != null ? filterStyle  : FilterStyle.ORIGINAL;
        final float        fi = Math.max(0f, Math.min(1f, intensity));

        preparing = true;
        cancelled = false;
        recorder = new GlFilterRecorder(outputFile, width, height, bp, fs, fi,
                new GlFilterRecorder.RecorderCallback() {
                    @Override
                    public void onVideoSaved(File file) {
                        mainHandler.post(() -> {
                            recording = false;
                            preparing = false;
                            listener.onVideoSaved(file);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            recording = false;
                            preparing = false;
                            listener.onError(message);
                        });
                    }
                });

        new Thread(() -> {
            try {
                recorder.prepare();
                if (cancelled) {
                    recorder.release();
                    preparing = false;
                    mainHandler.post(() -> listener.onError("录像已取消"));
                    return;
                }
                recorder.start();
                if (cancelled) {
                    recorder.stop();
                    preparing = false;
                    return;
                }
                recording = true;
                preparing = false;
            } catch (Exception e) {
                preparing = false;
                final GlFilterRecorder failed = recorder;
                recorder = null;
                if (failed != null) {
                    try { failed.release(); } catch (Throwable ignored) {}
                }
                mainHandler.post(() -> listener.onError("录像启动失败：" + e.getMessage()));
            }
        }, "StreamRecorderPrepare").start();
    }

    /** 喂入一帧（Bitmap 会被转换为 RGBA 后入队，内部不再持有该 Bitmap）。 */
    public void feedFrame(Bitmap bitmap) {
        GlFilterRecorder r = recorder;
        if (r == null || !recording || bitmap == null || bitmap.isRecycled()) return;
        byte[] rgba = bitmapToRgba(bitmap);
        r.enqueueFrame(rgba, bitmap.getWidth(), bitmap.getHeight());
    }

    /** 更新美颜 / 滤镜参数，下一帧生效。 */
    public void updateFilter(BeautyParams beautyParams, FilterStyle filterStyle, float intensity) {
        GlFilterRecorder r = recorder;
        if (r != null) {
            r.updateFilter(beautyParams, filterStyle, intensity);
        }
    }

    /** 停止录像（后台线程阻塞等待编码完成），结果通过 {@code onVideoSaved} 回调。 */
    public void stop() {
        if (!recording) {
            // 尚未开始录制（可能仍在 prepare）：标记取消，由 prepare 线程负责清理并回调
            cancelled = true;
            return;
        }
        recording = false;
        final GlFilterRecorder r = recorder;
        recorder = null;
        if (r != null) {
            new Thread(r::stop, "StreamRecorderStop").start();
        }
    }

    /** 强制释放（不等待编码完成）。 */
    public void release() {
        cancelled = true;
        recording = false;
        final GlFilterRecorder r = recorder;
        recorder = null;
        if (r != null) {
            r.release();
        }
    }

    public boolean isRecording() {
        return recording;
    }

    /** Bitmap → 连续 RGBA byte[]（R,G,B,A 字节序，无行填充）。 */
    private static byte[] bitmapToRgba(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            rgba[i * 4]     = (byte) ((p >> 16) & 0xFF); // R
            rgba[i * 4 + 1] = (byte) ((p >> 8)  & 0xFF); // G
            rgba[i * 4 + 2] = (byte) ( p        & 0xFF); // B
            rgba[i * 4 + 3] = (byte) ((p >> 24) & 0xFF); // A
        }
        return rgba;
    }
}

package com.dawn.filter;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 YUV 帧输入的视频录制器。
 * <p>
 * 工作原理：
 * <pre>
 *   CameraX ImageAnalysis → NV21 byte[] → enqueuFrame() → MediaCodec(YUV) → MediaMuxer
 *   AudioRecord(PCM) ──────────────────────────────────────────────────────┘
 * </pre>
 * 视频帧与 GPUImage 渲染完全无关（直接使用原始 YUV），美颜效果不在录制里。
 * <p>
 * ⚠️ 若需要录制带美颜效果的视频，请使用 GPU 渲染 → EGL Surface 的方案。
 * 此实现是"稳定可靠"的折中方案：预览有美颜，录制为原始画面。
 */
class FilterVideoRecorder {

    private static final String TAG = "FilterVideoRecorder";

    private static final String MIME_VIDEO = "video/avc";
    private static final String MIME_AUDIO = "audio/mp4a-latm";
    private static final int VIDEO_BITRATE = 4_000_000;
    private static final int VIDEO_FRAME_RATE = 30;
    private static final int VIDEO_I_FRAME_INTERVAL = 1;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_BITRATE = 128_000;
    private static final long TIMEOUT_US = 10_000L;
    private static final int MAX_QUEUE_SIZE = 5; // 最多缓存 5 帧避免内存暴涨

    interface RecorderCallback {
        void onVideoSaved(File file);
        void onError(String message);
    }

    private final File outputFile;
    private final int width;
    private final int height;
    private final RecorderCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private MediaMuxer muxer;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private volatile boolean muxerStarted = false;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final LinkedBlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private Thread videoEncodeThread;
    private Thread audioThread;
    private Thread drainThread;
    private long videoFrameCount = 0;

    FilterVideoRecorder(File outputFile, int width, int height, RecorderCallback callback) {
        this.outputFile = outputFile;
        // MediaCodec YUV 要求宽高是 16 的倍数
        this.width = alignTo16(width);
        this.height = alignTo16(height);
        this.callback = callback;
    }

    /**
     * 初始化编码器（在开始录制前调用）。
     */
    void prepare() throws Exception {
        // 视频编码器（YUV420SP 输入）
        MediaFormat vFmt = MediaFormat.createVideoFormat(MIME_VIDEO, width, height);
        vFmt.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
        vFmt.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
        vFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL);
        vFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        videoEncoder = MediaCodec.createEncoderByType(MIME_VIDEO);
        videoEncoder.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        videoEncoder.start();

        // 音频编码器
        MediaFormat aFmt = MediaFormat.createAudioFormat(MIME_AUDIO, AUDIO_SAMPLE_RATE, 1);
        aFmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        aFmt.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
        audioEncoder = MediaCodec.createEncoderByType(MIME_AUDIO);
        audioEncoder.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();

        // AudioRecord
        int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);

        // Muxer
        muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /** 开始录制线程。 */
    void start() {
        if (!running.compareAndSet(false, true)) return;
        videoFrameCount = 0;
        audioRecord.startRecording();

        videoEncodeThread = new Thread(this::videoEncodeLoop, "Rec-VideoEncode");
        audioThread = new Thread(this::audioLoop, "Rec-Audio");
        drainThread = new Thread(this::drainLoop, "Rec-Drain");

        videoEncodeThread.start();
        audioThread.start();
        drainThread.start();
    }

    /**
     * 投递一帧 NV21 数据（从 CameraX ImageAnalysis 回调调用）。
     * 如果队列满则丢帧（避免 OOM）。
     */
    void enqueueFrame(byte[] nv21) {
        if (!running.get()) return;
        frameQueue.offer(nv21); // 满了直接丢弃
    }

    /** 停止录制（阻塞直到编码完成）。 */
    void stop() {
        if (!running.compareAndSet(true, false)) return;
        // 投一个空帧作为 EOS 标记
        frameQueue.offer(new byte[0]);

        try {
            if (videoEncodeThread != null) videoEncodeThread.join(5000);
            if (audioThread != null) audioThread.join(3000);
            if (drainThread != null) drainThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        releaseMuxer(true);
    }

    /** 强制释放（不等待）。 */
    void release() {
        running.set(false);
        frameQueue.offer(new byte[0]);
        releaseMuxer(false);
    }

    // -------------------------------------------------------

    private void videoEncodeLoop() {
        long frameIntervalUs = 1_000_000L / VIDEO_FRAME_RATE;
        while (true) {
            byte[] frame;
            try {
                frame = frameQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            boolean eos = frame.length == 0;
            int idx = -1;
            try {
                idx = videoEncoder.dequeueInputBuffer(TIMEOUT_US * 5);
            } catch (Exception e) {
                break;
            }
            if (idx >= 0) {
                ByteBuffer ib = videoEncoder.getInputBuffer(idx);
                if (ib != null && !eos) {
                    ib.clear();
                    // NV21 → NV12 (YUV420SemiPlanar): U/V swap
                    byte[] nv12 = nv21ToNv12(frame, width, height);
                    // 若尺寸对不上（相机输出尺寸可能与编码器对齐尺寸不同）则裁剪/填充
                    int needed = width * height * 3 / 2;
                    if (nv12.length >= needed) {
                        ib.put(nv12, 0, needed);
                    } else {
                        ib.put(nv12);
                    }
                }
                long pts = videoFrameCount * frameIntervalUs;
                videoEncoder.queueInputBuffer(idx, 0, eos ? 0 : (width * height * 3 / 2), pts,
                        eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                videoFrameCount++;
            }
            if (eos) break;
        }
    }

    private void audioLoop() {
        int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        byte[] buf = new byte[minBuf];
        long bytesPerSec = AUDIO_SAMPLE_RATE * 2L;
        long audioBytes = 0;

        while (running.get()) {
            int read = audioRecord.read(buf, 0, buf.length);
            if (read <= 0) continue;
            int idx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
            if (idx >= 0) {
                ByteBuffer ib = audioEncoder.getInputBuffer(idx);
                if (ib != null) { ib.clear(); ib.put(buf, 0, read); }
                long pts = audioBytes * 1_000_000L / bytesPerSec;
                audioEncoder.queueInputBuffer(idx, 0, read, pts, 0);
                audioBytes += read;
            }
        }
        // EOS
        int idx = audioEncoder.dequeueInputBuffer(TIMEOUT_US * 5);
        if (idx >= 0) {
            audioEncoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean videoDone = false;
        boolean audioDone = false;

        while (!videoDone || !audioDone) {
            if (!videoDone) {
                int idx = videoEncoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(videoEncoder.getOutputFormat());
                    maybeStartMuxer();
                } else if (idx >= 0) {
                    ByteBuffer ob = videoEncoder.getOutputBuffer(idx);
                    if (ob != null && muxerStarted
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && info.size > 0) {
                        muxer.writeSampleData(videoTrack, ob, info);
                    }
                    videoEncoder.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) videoDone = true;
                }
            }
            if (!audioDone) {
                int idx = audioEncoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioTrack = muxer.addTrack(audioEncoder.getOutputFormat());
                    maybeStartMuxer();
                } else if (idx >= 0) {
                    ByteBuffer ob = audioEncoder.getOutputBuffer(idx);
                    if (ob != null && muxerStarted
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && info.size > 0) {
                        muxer.writeSampleData(audioTrack, ob, info);
                    }
                    audioEncoder.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) audioDone = true;
                }
            }
        }
    }

    private synchronized void maybeStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
            muxer.start();
            muxerStarted = true;
            Log.i(TAG, "Muxer started, videoTrack=" + videoTrack + " audioTrack=" + audioTrack);
        }
    }

    private void releaseMuxer(boolean success) {
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; } } catch (Exception ignored) {}
        try { if (videoEncoder != null) { videoEncoder.stop(); videoEncoder.release(); videoEncoder = null; } } catch (Exception ignored) {}
        try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); audioEncoder = null; } } catch (Exception ignored) {}
        boolean saved = false;
        if (muxerStarted) {
            try { muxer.stop(); saved = true; } catch (Exception e) { Log.w(TAG, "muxer.stop failed", e); }
        }
        try { if (muxer != null) { muxer.release(); muxer = null; } } catch (Exception ignored) {}
        muxerStarted = false;

        final boolean ok = saved && success && outputFile.exists() && outputFile.length() > 0;
        mainHandler.post(() -> {
            if (ok) {
                callback.onVideoSaved(outputFile);
            } else {
                if (outputFile.exists()) outputFile.delete();
                callback.onError("录制时间过短或编码异常，视频未保存");
            }
        });
    }

    // -------------------------------------------------------
    // 工具
    // -------------------------------------------------------

    /** NV21 (YYYYVUVU) → NV12 (YYYYUVUV)：交换 U/V 分量 */
    private static byte[] nv21ToNv12(byte[] nv21, int width, int height) {
        int ySize = width * height;
        byte[] nv12 = new byte[nv21.length];
        System.arraycopy(nv21, 0, nv12, 0, ySize); // Y plane 不变
        for (int i = ySize; i < nv21.length - 1; i += 2) {
            nv12[i]     = nv21[i + 1]; // U
            nv12[i + 1] = nv21[i];     // V
        }
        return nv12;
    }

    private static int alignTo16(int val) {
        return (val + 15) & ~15;
    }
}

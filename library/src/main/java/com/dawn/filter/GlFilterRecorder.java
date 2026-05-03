package com.dawn.filter;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;

/**
 * 带 GL 滤镜效果的视频录制器。
 * <p>
 * 渲染管线（全部在独立 EGL 上下文中运行）：
 * <pre>
 *   CameraX NV21 → [YUV→RGBA shader] → FBO[0]
 *               → [GPUImageBeautyFilter]   → FBO[1]
 *               → [GPUImageFilter style]   → MediaCodec InputSurface (FB 0)
 *               → MediaMuxer → MP4
 * </pre>
 * 音频：AudioRecord → AAC encoder → MediaMuxer
 */
public class GlFilterRecorder {

    private static final String TAG = "GlFilterRecorder";

    // EGL
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext  = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface  = EGL14.EGL_NO_SURFACE;

    // MediaCodec video
    private MediaCodec videoEncoder;
    private Surface    encoderInputSurface;
    private int        videoTrackIndex = -1;

    // MediaCodec audio + AudioRecord
    private MediaCodec  audioEncoder;
    private AudioRecord audioRecord;
    private int         audioTrackIndex = -1;

    // Muxer
    private MediaMuxer muxer;
    private boolean    muxerStarted = false;
    private int        orientationHint = 0;
    private final Object muxerLock = new Object();

    // Config
    private final File              outputFile;
    private final int               encodeWidth;
    private final int               encodeHeight;
    private final RecorderCallback  callback;

    // GL textures / FBOs
    private int[] yTexId   = new int[1];   // Y  plane (GL_LUMINANCE)
    private int[] uvTexId  = new int[1];   // UV plane (GL_LUMINANCE_ALPHA, NV21: V,U)
    private int[] fboId    = new int[2];   // [0]=YUV→RGBA, [1]=beauty output
    private int[] fboTexId = new int[2];   // textures attached to above FBOs

    // YUV→RGBA shader
    private int yuvProgId;
    private int yuvPosAttr, yuvTexCoordAttr, yuvYUniform, yuvUvUniform;

    // Filter instances (live in OUR EGL context)
    private GPUImageBeautyFilter beautyFilter;
    private GPUImageFilter        styleFilter;

    // Pending filter params (written from any thread, applied on encode thread)
    private volatile BeautyParams pendingBeautyParams;
    private volatile FilterStyle  pendingFilterStyle;
    private volatile float        pendingFilterIntensity;
    private volatile boolean      filterDirty = true;

    // Frame dimensions (set on first frame)
    private int frameW = 0;
    private int frameH = 0;

    // Pre-allocated direct ByteBuffers for glTexImage2D (避免 heap buffer 导致 Android 11+ 指针标记截断 SIGABRT)
    private ByteBuffer yDirectBuf  = null;  // capacity = frameW * frameH
    private ByteBuffer uvDirectBuf = null;  // capacity = frameW * frameH / 2

    // Pre-allocated FloatBuffers for quad geometry (每帧复用，避免 GC 压力)
    private final FloatBuffer cubeBuf     = makeDirectFloat(CUBE);
    private final FloatBuffer texCoordBuf = makeDirectFloat(TEX_COORDS);  // 标准坐标，用于美颜/风格 pass
    private FloatBuffer yuvTexCoordBuf;   // 含旋转+镜像的坐标，用于 YUV→RGBA pass

    // 渲染变换（旋转 + 镜像），在 prepare() 前通过 setTransform() 设置
    private int     transformRotDeg = 0;
    private boolean transformFlipH  = false;
    private boolean transformFlipV  = false;

    // Frame queue
    private static final int QUEUE_CAP = 4;
    private final LinkedBlockingQueue<FrameData> queue = new LinkedBlockingQueue<>(QUEUE_CAP);

    // Threads
    private Thread           encodeThread;
    private Thread           audioThread;
    private final AtomicBoolean running      = new AtomicBoolean(false);
    private final AtomicBoolean audioRunning = new AtomicBoolean(false);

    // Audio constants
    private static final int SAMPLE_RATE    = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_BIT_RATE = 128_000;

    // GL quad geometry (triangle strip: BL, BR, TL, TR)
    private static final float[] CUBE = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
    };
    private static final float[] TEX_COORDS = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
    };

    // NV21 → RGBA shaders
    // NV21 UV plane is interleaved V,U,V,U → upload as GL_LUMINANCE_ALPHA → .r=V, .a=U
    private static final String YUV_VERT =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String YUV_FRAG =
            "precision highp float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uY;\n" +
            "uniform sampler2D uUV;\n" +
            "void main() {\n" +
            "    float y = texture2D(uY,  vTexCoord).r;\n" +
            "    float v = texture2D(uUV, vTexCoord).r - 0.5;\n" +
            "    float u = texture2D(uUV, vTexCoord).a - 0.5;\n" +
            "    float r = clamp(y + 1.402   * v,                0.0, 1.0);\n" +
            "    float g = clamp(y - 0.34414 * u - 0.71414 * v,  0.0, 1.0);\n" +
            "    float b = clamp(y + 1.772   * u,                0.0, 1.0);\n" +
            "    gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}\n";

    // ──────────────────────────────────────────────────────────────────────────

    public interface RecorderCallback {
        void onVideoSaved(File file);
        void onError(String message);
    }

    private static class FrameData {
        final byte[] nv21;
        final int    w, h;
        final long   timestampNs;

        static final FrameData EOS = new FrameData(null, 0, 0, -1);

        FrameData(byte[] nv21, int w, int h, long tsNs) {
            this.nv21 = nv21;
            this.w = w;
            this.h = h;
            this.timestampNs = tsNs;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Constructor / config
    // ──────────────────────────────────────────────────────────────────────────

    public GlFilterRecorder(File outputFile, int width, int height,
                             BeautyParams beautyParams, FilterStyle filterStyle,
                             float filterIntensity, RecorderCallback callback) {
        this.outputFile             = outputFile;
        this.encodeWidth            = alignTo16(width);
        this.encodeHeight           = alignTo16(height);
        this.pendingBeautyParams    = beautyParams != null ? beautyParams : BeautyParams.defaultCamera();
        this.pendingFilterStyle     = filterStyle  != null ? filterStyle  : FilterStyle.ORIGINAL;
        this.pendingFilterIntensity = filterIntensity;
        this.callback               = callback;
    }

    /** 设置 MP4 方向 hint（在 prepare() 之前调用）。 */
    public void setOrientationHint(int degrees) {
        this.orientationHint = degrees;
    }

    /**
     * 设置 YUV→RGBA 渲染通道的旋转和镜像变换，将效果烘焙进视频帧，
     * 使录制内容与预览画面方向完全一致。在 prepare() 之前调用。
     *
     * @param rotDeg 旋转角度（0/90/180/270），与 CameraX rotationDegrees 一致
     * @param flipH  是否水平镜像（前置摄像头自动镜像 XOR 用户手动镜像）
     * @param flipV  是否垂直翻转
     */
    public void setTransform(int rotDeg, boolean flipH, boolean flipV) {
        this.transformRotDeg = rotDeg;
        this.transformFlipH  = flipH;
        this.transformFlipV  = flipV;
    }

    /** 更新滤镜参数，录制中也可调用，下一帧生效。 */
    public void updateFilter(BeautyParams beautyParams, FilterStyle filterStyle, float intensity) {
        pendingBeautyParams    = beautyParams != null ? beautyParams : BeautyParams.defaultCamera();
        pendingFilterStyle     = filterStyle  != null ? filterStyle  : FilterStyle.ORIGINAL;
        pendingFilterIntensity = intensity;
        filterDirty            = true;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    public void prepare() throws Exception {
        // Video encoder (COLOR_FormatSurface = GPU直通，无CPU拷贝)
        MediaFormat vf = MediaFormat.createVideoFormat("video/avc", encodeWidth, encodeHeight);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoEncoder = MediaCodec.createEncoderByType("video/avc");
        videoEncoder.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();

        // Audio encoder
        MediaFormat af = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
        af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();

        // AudioRecord
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBuf * 2);

        // Muxer
        muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        if (orientationHint != 0) {
            muxer.setOrientationHint(orientationHint);
        }
    }

    public void start() {
        running.set(true);
        audioRunning.set(true);
        audioRecord.startRecording();
        encodeThread = new Thread(this::encodeLoop, "GlFilterEncoder");
        audioThread  = new Thread(this::audioLoop,  "GlFilterAudio");
        encodeThread.start();
        audioThread.start();
    }

    /** 入队一帧 NV21，如队满则丢弃最旧帧。 */
    public void enqueueFrame(byte[] nv21, int w, int h) {
        if (!running.get()) return;
        FrameData fd = new FrameData(nv21, w, h, System.nanoTime());
        if (!queue.offer(fd)) {
            queue.poll();
            queue.offer(fd);
        }
    }

    /** 正常停止：等待当前队列排空后停止。 */
    public void stop() {
        running.set(false);
        audioRunning.set(false);
        queue.offer(FrameData.EOS);
        try {
            if (encodeThread != null) encodeThread.join(6000);
            if (audioThread  != null) audioThread.join(4000);
        } catch (InterruptedException ignored) {}
        finalizeMuxer(true);
    }

    /** 强制释放，不等待。 */
    public void release() {
        running.set(false);
        audioRunning.set(false);
        queue.offer(FrameData.EOS);
        finalizeMuxer(false);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Encode loop (runs on encodeThread)
    // ──────────────────────────────────────────────────────────────────────────

    private void encodeLoop() {
        if (!initEgl()) {
            postError("EGL 初始化失败");
            return;
        }
        initGlResources();

        try {
            while (true) {
                FrameData fd = queue.poll(100, TimeUnit.MILLISECONDS);
                if (fd == null) {
                    if (!running.get()) break;
                    continue;
                }
                if (fd == FrameData.EOS) break;

                if (filterDirty) {
                    filterDirty = false;
                    rebuildFilters();
                }

                renderAndEncode(fd);
                drainVideoEncoder(false);
            }
            // EOS → signal MediaCodec and drain remaining output
            videoEncoder.signalEndOfInputStream();
            drainVideoEncoder(true);

        } catch (Exception e) {
            Log.e(TAG, "encodeLoop error", e);
            postError("视频编码错误: " + e.getMessage());
        } finally {
            releaseGlResources();
            releaseEgl();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Audio loop (runs on audioThread)
    // ──────────────────────────────────────────────────────────────────────────

    private void audioLoop() {
        int    minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        byte[] buf    = new byte[minBuf];
        long   startUs = -1L;

        while (audioRunning.get()) {
            int read = audioRecord.read(buf, 0, buf.length);
            if (read <= 0) continue;

            if (startUs < 0) startUs = System.nanoTime() / 1000L;

            int idx = audioEncoder.dequeueInputBuffer(10_000L);
            if (idx >= 0) {
                ByteBuffer inBuf = audioEncoder.getInputBuffer(idx);
                if (inBuf != null) {
                    inBuf.clear();
                    inBuf.put(buf, 0, read);
                    long pts = System.nanoTime() / 1000L - startUs;
                    audioEncoder.queueInputBuffer(idx, 0, read, pts, 0);
                }
            }
            drainAudioEncoder(false);
        }

        // EOS
        int idx = audioEncoder.dequeueInputBuffer(10_000L);
        if (idx >= 0) {
            audioEncoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
        drainAudioEncoder(true);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GL rendering (all called from encodeThread with EGL current)
    // ──────────────────────────────────────────────────────────────────────────

    private void renderAndEncode(FrameData fd) {
        // FBO 和编码目标使用 encodeWidth/Height（当旋转 90°/270° 时与传感器尺寸不同）
        if (encodeWidth != frameW || encodeHeight != frameH) {
            frameW = encodeWidth;
            frameH = encodeHeight;
            setupFbos(encodeWidth, encodeHeight);
            if (beautyFilter != null) beautyFilter.onOutputSizeChanged(encodeWidth, encodeHeight);
            if (styleFilter  != null) styleFilter.onOutputSizeChanged(encodeWidth, encodeHeight);
        }

        GLES20.glViewport(0, 0, encodeWidth, encodeHeight);

        // ── Pass 1: NV21 → RGBA into fbo[0] ─────────────────────────────────
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0]);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        uploadNv21AndDraw(fd.nv21, fd.w, fd.h);  // 以传感器尺寸上传纹理

        // ── Pass 2: Beauty filter → fbo[1] ──────────────────────────────────
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[1]);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (beautyFilter != null) {
            cubeBuf.rewind(); texCoordBuf.rewind();
            beautyFilter.onDraw(fboTexId[0], cubeBuf, texCoordBuf);
        }

        // ── Pass 3: Style filter → encoder surface (FB 0) ───────────────────
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (styleFilter != null) {
            cubeBuf.rewind(); texCoordBuf.rewind();
            styleFilter.onDraw(fboTexId[1], cubeBuf, texCoordBuf);
        }

        // Commit frame to MediaCodec with PTS
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, fd.timestampNs);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    /** Upload NV21 data as two GL textures and run YUV→RGBA shader. */
    private void uploadNv21AndDraw(byte[] nv21, int w, int h) {
        int yLen  = w * h;
        int uvLen = yLen / 2;

        // 确保 direct buffer 已分配（首帧或尺寸变化后）
        if (yDirectBuf == null || yDirectBuf.capacity() < yLen) {
            yDirectBuf  = ByteBuffer.allocateDirect(yLen).order(ByteOrder.nativeOrder());
            uvDirectBuf = ByteBuffer.allocateDirect(uvLen).order(ByteOrder.nativeOrder());
        }

        // 复制 Y 平面到 direct buffer
        yDirectBuf.clear();
        yDirectBuf.put(nv21, 0, yLen);
        yDirectBuf.rewind();

        // Y: GL_LUMINANCE, w x h
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTexId[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
                yDirectBuf);
        texParams();

        // 复制 UV 平面到 direct buffer
        uvDirectBuf.clear();
        uvDirectBuf.put(nv21, yLen, uvLen);
        uvDirectBuf.rewind();

        // UV: GL_LUMINANCE_ALPHA, w/2 x h/2  (NV21 stores V,U interleaved → .r=V, .a=U)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uvTexId[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA,
                w / 2, h / 2, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE,
                uvDirectBuf);
        texParams();

        // Draw YUV→RGBA
        GLES20.glUseProgram(yuvProgId);

        cubeBuf.rewind();
        GLES20.glVertexAttribPointer(yuvPosAttr, 2, GLES20.GL_FLOAT, false, 0, cubeBuf);
        GLES20.glEnableVertexAttribArray(yuvPosAttr);

        yuvTexCoordBuf.rewind();
        GLES20.glVertexAttribPointer(yuvTexCoordAttr, 2, GLES20.GL_FLOAT, false, 0, yuvTexCoordBuf);
        GLES20.glEnableVertexAttribArray(yuvTexCoordAttr);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTexId[0]);
        GLES20.glUniform1i(yuvYUniform, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uvTexId[0]);
        GLES20.glUniform1i(yuvUvUniform, 1);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(yuvPosAttr);
        GLES20.glDisableVertexAttribArray(yuvTexCoordAttr);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void setupFbos(int w, int h) {
        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            texParams();

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, fboTexId[i], 0);

            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                    != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "FBO[" + i + "] incomplete");
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private static void texParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // EGL setup
    // ──────────────────────────────────────────────────────────────────────────

    private boolean initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) { Log.e(TAG, "eglGetDisplay failed"); return false; }

        int[] ver = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { Log.e(TAG, "eglInitialize failed"); return false; }

        int[] cfgAttribs = {
                EGL14.EGL_RED_SIZE,        8,
                EGL14.EGL_GREEN_SIZE,      8,
                EGL14.EGL_BLUE_SIZE,       8,
                EGL14.EGL_ALPHA_SIZE,      8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,    EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfgs    = new EGLConfig[1];
        int[]       numCfgs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, cfgs, 0, 1, numCfgs, 0)
                || numCfgs[0] == 0) {
            Log.e(TAG, "eglChooseConfig failed");
            return false;
        }

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) { Log.e(TAG, "eglCreateContext failed"); return false; }

        int[] surfAttribs = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], encoderInputSurface, surfAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) { Log.e(TAG, "eglCreateWindowSurface failed"); return false; }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed");
            return false;
        }
        return true;
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GL resource management (all on encode thread)
    // ──────────────────────────────────────────────────────────────────────────

    private void initGlResources() {
        // Build YUV→RGBA shader
        yuvProgId       = buildProgram(YUV_VERT, YUV_FRAG);
        yuvPosAttr      = GLES20.glGetAttribLocation(yuvProgId,  "aPosition");
        yuvTexCoordAttr = GLES20.glGetAttribLocation(yuvProgId,  "aTexCoord");
        yuvYUniform     = GLES20.glGetUniformLocation(yuvProgId, "uY");
        yuvUvUniform    = GLES20.glGetUniformLocation(yuvProgId, "uUV");

        // Allocate textures / FBOs
        GLES20.glGenTextures(1, yTexId,   0);
        GLES20.glGenTextures(1, uvTexId,  0);
        GLES20.glGenFramebuffers(2, fboId,    0);
        GLES20.glGenTextures(2,    fboTexId,  0);

        // 根据旋转/镜像参数计算 YUV pass 专用纹理坐标
        yuvTexCoordBuf = computeYuvTexCoords(transformRotDeg, transformFlipH, transformFlipV);

        // Build filter pipeline
        rebuildFilters();
    }

    /**
     * 计算 YUV→RGBA 通道的纹理坐标，将旋转和镜像烘焙进去。
     * 顶点顺序（GL_TRIANGLE_STRIP）: BL(-1,-1), BR(1,-1), TL(-1,1), TR(1,1)。
     * GL 纹理 (0,0) 对应 NV21 第 0 行（图像顶部），(0,1) 对应图像底部。
     */
    private static FloatBuffer computeYuvTexCoords(int rotDeg, boolean flipH, boolean flipV) {
        // 屏幕顶点归一化坐标 (sx=0左/1右, sy=0下/1上)
        float[] screen = { 0, 0,  1, 0,  0, 1,  1, 1 }; // BL BR TL TR
        float[] result = new float[8];
        for (int i = 0; i < 4; i++) {
            float sx = screen[i * 2];
            float sy = screen[i * 2 + 1];
            float tx, ty;
            // 将屏幕坐标映射到传感器纹理坐标（含旋转修正）
            switch (rotDeg) {
                case 90:  tx = 1 - sy; ty = 1 - sx; break;  // 顺时针 90°
                case 180: tx = 1 - sx; ty = sy;      break;  // 180°
                case 270: tx = sy;     ty = sx;       break;  // 顺时针 270°
                default:  tx = sx;     ty = 1 - sy;   break;  // 0°（含 GL Y 轴翻转）
            }
            // 镜像（在旋转之后叠加）
            if (flipH) tx = 1 - tx;
            if (flipV) ty = 1 - ty;
            result[i * 2]     = tx;
            result[i * 2 + 1] = ty;
        }
        return makeDirectFloat(result);
    }

    /**
     * (Re)create beauty + style filters in THIS GL context.
     * Called on encode thread after filterDirty is set.
     */
    private void rebuildFilters() {
        destroyFilters();

        BeautyParams bp = pendingBeautyParams;
        FilterStyle  fs = pendingFilterStyle;
        float        fi = pendingFilterIntensity;

        Log.i(TAG, "rebuildFilters: filterStyle=" + fs + " intensity=" + fi + " frameW=" + frameW + " frameH=" + frameH);

        beautyFilter = new GPUImageBeautyFilter(bp);
        beautyFilter.onInit();

        styleFilter = fs.createFilter(fi);
        styleFilter.onInit();

        if (frameW > 0 && frameH > 0) {
            beautyFilter.onOutputSizeChanged(frameW, frameH);
            styleFilter.onOutputSizeChanged(frameW, frameH);
        }
    }

    private void destroyFilters() {
        if (beautyFilter != null) {
            try { beautyFilter.destroy(); } catch (Throwable ignored) {}
            beautyFilter = null;
        }
        if (styleFilter != null) {
            try { styleFilter.destroy(); } catch (Throwable ignored) {}
            styleFilter = null;
        }
    }

    private void releaseGlResources() {
        destroyFilters();
        if (fboId[0]    != 0) { GLES20.glDeleteFramebuffers(2, fboId,    0); }
        if (fboTexId[0] != 0) { GLES20.glDeleteTextures(2,    fboTexId,  0); }
        if (yTexId[0]   != 0) { GLES20.glDeleteTextures(1,    yTexId,    0); }
        if (uvTexId[0]  != 0) { GLES20.glDeleteTextures(1,    uvTexId,   0); }
        if (yuvProgId   != 0) { GLES20.glDeleteProgram(yuvProgId); yuvProgId = 0; }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Encoder draining
    // ──────────────────────────────────────────────────────────────────────────

    private void drainVideoEncoder(boolean eos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx = videoEncoder.dequeueOutputBuffer(info, eos ? 10_000L : 0L);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxerLock) {
                    videoTrackIndex = muxer.addTrack(videoEncoder.getOutputFormat());
                    maybeStartMuxer();
                }
                continue;
            }
            if (idx < 0) break;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                videoEncoder.releaseOutputBuffer(idx, false);
                continue;
            }
            if (muxerStarted && info.size > 0) {
                ByteBuffer buf = videoEncoder.getOutputBuffer(idx);
                if (buf != null) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    synchronized (muxerLock) {
                        muxer.writeSampleData(videoTrackIndex, buf, info);
                    }
                }
            }
            videoEncoder.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    private void drainAudioEncoder(boolean eos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx = audioEncoder.dequeueOutputBuffer(info, eos ? 10_000L : 0L);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxerLock) {
                    audioTrackIndex = muxer.addTrack(audioEncoder.getOutputFormat());
                    maybeStartMuxer();
                }
                continue;
            }
            if (idx < 0) break;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                audioEncoder.releaseOutputBuffer(idx, false);
                continue;
            }
            if (muxerStarted && info.size > 0) {
                ByteBuffer buf = audioEncoder.getOutputBuffer(idx);
                if (buf != null) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    synchronized (muxerLock) {
                        muxer.writeSampleData(audioTrackIndex, buf, info);
                    }
                }
            }
            audioEncoder.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    private void maybeStartMuxer() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer.start();
            muxerStarted = true;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Finalize
    // ──────────────────────────────────────────────────────────────────────────

    private void finalizeMuxer(boolean notifySuccess) {
        // 1. 先停止音频录制输入源
        if (audioRecord != null) {
            try { audioRecord.stop();    } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        // 2. 停止并释放 Muxer（此时 encode/audio 线程已将所有数据写入 Muxer）
        //    正确顺序：stop() 写入 MOOV 原子 → release() 清理资源
        //    stop() 后立即置 muxerStarted=false，防止后续误写
        synchronized (muxerLock) {
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                        muxerStarted = false;
                    }
                } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
                muxer = null;
            }
        }
        // 3. 释放编码器（已被 encode/audio 线程 drain 至 EOS）
        safeStop(videoEncoder);    safeRelease(videoEncoder);    videoEncoder = null;
        safeStop(audioEncoder);    safeRelease(audioEncoder);    audioEncoder = null;
        // 4. 释放编码器输入 Surface
        if (encoderInputSurface != null) {
            encoderInputSurface.release();
            encoderInputSurface = null;
        }
        if (notifySuccess) {
            final File f = outputFile;
            new Handler(Looper.getMainLooper()).post(
                    () -> { if (callback != null) callback.onVideoSaved(f); });
        }
    }

    private static void safeStop(MediaCodec mc) {
        if (mc != null) try { mc.stop(); }    catch (Exception ignored) {}
    }
    private static void safeRelease(MediaCodec mc) {
        if (mc != null) try { mc.release(); } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GL helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static int buildProgram(String vert, String frag) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER,   vert);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, frag);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) Log.e(TAG, "Link error: " + GLES20.glGetProgramInfoLog(prog));
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return prog;
    }

    private static int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) Log.e(TAG, "Compile error: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static FloatBuffer makeDirectFloat(float[] arr) {
        FloatBuffer buf = ByteBuffer.allocateDirect(arr.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(arr).rewind();
        return buf;
    }

    private static int alignTo16(int v) {
        return (v + 15) & ~15;
    }

    private void postError(String msg) {
        new Handler(Looper.getMainLooper())
                .post(() -> { if (callback != null) callback.onError(msg); });
    }
}

package com.dawn.filter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 人像抠图引擎（基于 Robust Video Matting, RVM，ONNX Runtime 推理）。
 * <p>
 * 与 ML Kit Selfie Segmenter（输出 256×256 低分辨率 mask）不同，RVM 直接输出
 * <b>源分辨率</b>的 alpha matte（前景透明度），发丝级边缘，从根源消除放大锯齿。
 * <p>
 * 模型约定（rvm_mobilenetv3_fp32.onnx）：
 * <ul>
 *   <li>输入 src: [1,3,H,W] NCHW，RGB 归一化 [0,1]</li>
 *   <li>输入 r1i/r2i/r3i/r4i: 循环状态（单帧推理置零）</li>
 *   <li>输入 downsample_ratio: 标量，固定 0.25</li>
 *   <li>输出 fgr: [1,3,H,W] 前景 RGB；pha: [1,1,H,W] alpha matte</li>
 * </ul>
 * H、W 需为 32 的倍数（downsample_ratio=0.5 叠加 4 次下采样）。模型文件（约 15MB）
 * 由接入方放入 app 的 assets，例如 {@code matting/rvm_mobilenetv3_fp32.onnx}。
 */
public class PortraitMatting implements AutoCloseable {

    private static final String TAG = "PortraitMatting";

    /** 分块推理尺寸（必须为 32 的倍数，RVM 内部会下采样 5 次）。 */
    private static final int TILE = 512;
    /** 相邻 tile 的重叠像素，用于消除 tile 边界的接缝。 */
    private static final int OVERLAP = 32;
    /** RVM 下采样比：0.5 比 0.25 边缘更精细（内部特征图分辨率翻倍）。 */
    private static final float DOWNSAMPLE_RATIO = 0.5f;

    private final OrtEnvironment env;
    private final OrtSession session;

    /** 从 assets 加载 RVM 模型。 */
    public static PortraitMatting load(Context context, String assetPath) throws IOException, OrtException {
        byte[] modelBytes;
        try (InputStream is = context.getAssets().open(assetPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            modelBytes = baos.toByteArray();
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession session = env.createSession(modelBytes, new OrtSession.SessionOptions());
        return new PortraitMatting(env, session);
    }

    private PortraitMatting(OrtEnvironment env, OrtSession session) {
        this.env = env;
        this.session = session;
        Log.i(TAG, "RVM model loaded, inputs=" + session.getNumInputs()
                + " outputs=" + session.getNumOutputs());
    }

    /** 抠出人物前景，背景透明。 */
    public Bitmap matte(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();

        int[] srcPixels = new int[w * h];
        src.getPixels(srcPixels, 0, w, 0, 0, w, h);

        float[] alphaAcc = new float[w * h];
        float[] fgrRAcc = new float[w * h];
        float[] fgrGAcc = new float[w * h];
        float[] fgrBAcc = new float[w * h];
        float[] weightAcc = new float[w * h];

        int stride = TILE - 2 * OVERLAP;

        for (int y0 = 0; y0 < h; y0 += stride) {
            for (int x0 = 0; x0 < w; x0 += stride) {
                int sx0 = Math.max(0, x0 - OVERLAP);
                int sy0 = Math.max(0, y0 - OVERLAP);
                int sx1 = Math.min(w, x0 + TILE - OVERLAP);
                int sy1 = Math.min(h, y0 + TILE - OVERLAP);

                TileResult tile = runTile(srcPixels, w, h, sx0, sy0, sx1, sy1);
                if (tile == null) continue;

                int outX0 = Math.max(x0, sx0 + OVERLAP);
                int outY0 = Math.max(y0, sy0 + OVERLAP);
                int outX1 = Math.min(x0 + stride, sx1 - OVERLAP);
                int outY1 = Math.min(y0 + stride, sy1 - OVERLAP);
                for (int y = outY0; y < outY1; y++) {
                    for (int x = outX0; x < outX1; x++) {
                        int idx = y * w + x;
                        int ty = y - sy0, tx = x - sx0;
                        alphaAcc[idx] += tile.alpha[ty][tx];
                        fgrRAcc[idx] += tile.fgr[ty][tx][0];
                        fgrGAcc[idx] += tile.fgr[ty][tx][1];
                        fgrBAcc[idx] += tile.fgr[ty][tx][2];
                        weightAcc[idx] += 1f;
                    }
                }
            }
        }

        // 归一化 alpha 与前景 RGB
        float[] alpha = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            if (weightAcc[i] > 0f) {
                alpha[i] = clamp(alphaAcc[i] / weightAcc[i], 0f, 1f);
                fgrRAcc[i] /= weightAcc[i];
                fgrGAcc[i] /= weightAcc[i];
                fgrBAcc[i] /= weightAcc[i];
            }
        }

        // 人脸空间约束：剔除头顶上方/两侧远处的椅子靠背
        Rect face = PortraitCutout.detectFaceRect(src);
        if (face != null) {
            applyFaceConstraint(alpha, srcPixels, w, h, face);
        }

        // 细节增强：细线/发丝（中等 alpha 但邻域有强前景）提升 alpha，让细节更实
        enhanceFineDetail(alpha, w, h);

        // 合成：主体用原图 RGB（颜色最准），边缘用 RVM 前景色（去背景溢色，无白边）
        int[] outPixels = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            float a = alpha[i];
            int alphaByte = Math.round(a * 255f);
            if (alphaByte <= 1) {
                outPixels[i] = Color.TRANSPARENT;
                continue;
            }
            int c = srcPixels[i];
            float mix = smoothstep(0.3f, 0.65f, a); // 0=边缘用 fgr，1=主体用原图
            int r = Math.round(clamp(fgrRAcc[i] * 255f * (1f - mix) + Color.red(c) * mix, 0f, 255f));
            int g = Math.round(clamp(fgrGAcc[i] * 255f * (1f - mix) + Color.green(c) * mix, 0f, 255f));
            int b = Math.round(clamp(fgrBAcc[i] * 255f * (1f - mix) + Color.blue(c) * mix, 0f, 255f));
            outPixels[i] = Color.argb(alphaByte, r, g, b);
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(outPixels, 0, w, 0, 0, w, h);
        return result;
    }

    /**
     * 人脸空间约束：框外像素按颜色 + 置信度清理。
     * 浅色无彩（白色/浅灰头枕、椅子）即使 RVM 给高 alpha 也直接归零；
     * 彩色/深色（手、耳麦线）按置信度加权衰减，保留强前景。
     */
    private static void applyFaceConstraint(float[] alpha, int[] srcPixels, int w, int h, Rect face) {
        int cx = face.centerX();
        int cy = face.centerY();
        float fw = face.width();
        float fh = face.height();
        int coreLeft   = clamp(Math.round(cx - fw * 2.2f), 0, w - 1);
        int coreRight  = clamp(Math.round(cx + fw * 2.2f), 0, w - 1);
        int coreTop    = clamp(Math.round(cy - fh * 1.15f), 0, h - 1);
        int coreBottom = clamp(Math.round(cy + fh * 4.5f), 0, h - 1);
        float softW = Math.max(1f, fw * 0.5f);

        for (int y = 0; y < h; y++) {
            int dy = y < coreTop ? coreTop - y : y > coreBottom ? y - coreBottom : 0;
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (alpha[idx] <= 0f) continue;
                int dx = x < coreLeft ? coreLeft - x : x > coreRight ? x - coreRight : 0;
                float d = Math.max(dx, dy);
                if (d <= 0f) continue;
                float falloff = Math.min(1f, d / softW);

                int c = srcPixels[idx];
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                int maxC = Math.max(r, Math.max(g, b));
                int minC = Math.min(r, Math.min(g, b));
                boolean lightAchromatic = (maxC - minC < 45 && maxC > 140);

                if (lightAchromatic) {
                    // 浅色无彩 = 头枕/白椅，即使高 alpha 也强衰减清除
                    alpha[idx] *= Math.max(0f, 1f - falloff * 1.8f);
                } else {
                    // 彩色/深色 = 手/耳麦线，置信度加权，保强前景
                    float conf = alpha[idx];
                    alpha[idx] *= 1f - (1f - conf) * falloff;
                }
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 单个 tile 的推理结果。 */
    private static final class TileResult {
        final float[][] alpha;   // [th][tw]
        final float[][][] fgr;   // [th][tw][3] RGB 前景色
        TileResult(float[][] alpha, float[][][] fgr) {
            this.alpha = alpha;
            this.fgr = fgr;
        }
    }

    /** 对单个 tile 做推理，返回 alpha matte 与前景 RGB。 */
    private TileResult runTile(int[] srcPixels, int w, int h,
                               int sx0, int sy0, int sx1, int sy1) {
        int tw = sx1 - sx0;
        int th = sy1 - sy0;
        if (tw <= 0 || th <= 0) return null;

        // NCHW 输入：src [1,3,512,512]，边缘 clamp
        float[] srcData = new float[1 * 3 * TILE * TILE];
        for (int j = 0; j < TILE; j++) {
            int sy = Math.min(sy0 + j, sy1 - 1);
            for (int i = 0; i < TILE; i++) {
                int sx = Math.min(sx0 + i, sx1 - 1);
                int p = srcPixels[sy * w + sx];
                int base = j * TILE + i;
                srcData[base] = ((p >> 16) & 0xFF) / 255f;
                srcData[TILE * TILE + base] = ((p >> 8) & 0xFF) / 255f;
                srcData[2 * TILE * TILE + base] = (p & 0xFF) / 255f;
            }
        }

        try {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("src", OnnxTensor.createTensor(env, FloatBuffer.wrap(srcData), new long[]{1, 3, TILE, TILE}));
            // 循环状态分辨率 = src × downsample_ratio / (2, 4, 8, 16)
            inputs.put("r1i", zeroTensor(1, 16, TILE / 4, TILE / 4));
            inputs.put("r2i", zeroTensor(1, 20, TILE / 8, TILE / 8));
            inputs.put("r3i", zeroTensor(1, 40, TILE / 16, TILE / 16));
            inputs.put("r4i", zeroTensor(1, 64, TILE / 32, TILE / 32));
            inputs.put("downsample_ratio", OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(new float[]{DOWNSAMPLE_RATIO}), new long[]{1}));

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][][] phaOut = (float[][][][]) result.get("pha").get().getValue();
                float[][][][] fgrOut = (float[][][][]) result.get("fgr").get().getValue();
                float[][] alpha = new float[th][tw];
                float[][][] fgr = new float[th][tw][3];
                for (int j = 0; j < th; j++) {
                    for (int i = 0; i < tw; i++) {
                        alpha[j][i] = clamp(phaOut[0][0][j][i], 0f, 1f);
                        fgr[j][i][0] = clamp(fgrOut[0][0][j][i], 0f, 1f);
                        fgr[j][i][1] = clamp(fgrOut[0][1][j][i], 0f, 1f);
                        fgr[j][i][2] = clamp(fgrOut[0][2][j][i], 0f, 1f);
                    }
                }
                return new TileResult(alpha, fgr);
            } finally {
                for (OnnxTensor t : inputs.values()) t.close();
            }
        } catch (OrtException e) {
            Log.e(TAG, "RVM tile inference failed", e);
            return null;
        }
    }

    /** 细节增强：被强前景包围的中等 alpha 像素（细线/发丝）提升 alpha，让细节更实。 */
    private static void enhanceFineDetail(float[] alpha, int w, int h) {
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = y * w + x;
                float a = alpha[idx];
                if (a < 0.12f || a > 0.65f) continue;
                boolean nearStrong = false;
                for (int ky = -1; ky <= 1 && !nearStrong; ky++) {
                    for (int kx = -1; kx <= 1; kx++) {
                        if (kx == 0 && ky == 0) continue;
                        if (alpha[idx + ky * w + kx] > 0.85f) { nearStrong = true; break; }
                    }
                }
                if (nearStrong) {
                    alpha[idx] = Math.min(1f, a * 1.45f);
                }
            }
        }
    }

    /** 构造零填充的 float 张量。 */
    private OnnxTensor zeroTensor(long c, long ch, long h, long w) throws OrtException {
        float[] zeros = new float[(int) (c * ch * h * w)];
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(zeros), new long[]{c, ch, h, w});
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Throwable ignored) {
        }
    }
}

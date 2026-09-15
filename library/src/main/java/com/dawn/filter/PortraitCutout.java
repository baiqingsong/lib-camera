package com.dawn.filter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 使用 ML Kit 人像分割把人物前景保留，背景设置为透明。
 */
public final class PortraitCutout {

    private static final String TAG = "PortraitCutout";

    private PortraitCutout() {
    }

    /**
     * 抠出人物前景，背景透明。
     *
     * @param context Android Context
     * @param input 原始图片
     * @param threshold 前景阈值，建议 0.35 ~ 0.7，越大越保守
     * @return 处理后的透明背景图片；失败时返回 null
     */
    public static Bitmap cutoutPerson(Context context, Bitmap input, float threshold) {
        if (context == null || input == null || input.isRecycled()) {
            return null;
        }

        final float safeThreshold = clamp(threshold, 0.05f, 0.95f);
        final Bitmap src = input.copy(Bitmap.Config.ARGB_8888, false);
        if (src == null) {
            return null;
        }

        try {
            SelfieSegmenterOptions options = new SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .build();

            Segmenter segmenter = Segmentation.getClient(options);
            try {
                SegmentationMask mask = Tasks.await(
                        segmenter.process(InputImage.fromBitmap(src, 0)),
                        30,
                        TimeUnit.SECONDS
                );
                if (mask == null) {
                    return null;
                }

                ByteBuffer buffer = mask.getBuffer();
                if (buffer == null) {
                    return null;
                }
                buffer.order(ByteOrder.nativeOrder());
                buffer.rewind();

                int width = mask.getWidth();
                int height = mask.getHeight();
                if (width <= 0 || height <= 0) {
                    return null;
                }

                int srcW = src.getWidth();
                int srcH = src.getHeight();
                int[] srcPixels = new int[srcW * srcH];
                src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH);

                float[] scoreMap = new float[width * height];
                for (int i = 0; i < scoreMap.length; i++) {
                    if (buffer.remaining() >= 4) {
                        scoreMap[i] = buffer.getFloat();
                    } else {
                        scoreMap[i] = 0f;
                    }
                }

                // Morphological opening at mask resolution: erode→dilate breaks thin person↔headrest bridges
                float[] openScore = maxFilter2D(minFilter2D(scoreMap, width, height, 3), width, height, 3);
                // Blend: erode the score in uncertain zone so headrest (borderline score) falls below threshold
                for (int i = 0; i < scoreMap.length; i++) {
                    scoreMap[i] = scoreMap[i] > 0.85f ? scoreMap[i]  // keep solid foreground
                            : scoreMap[i] < 0.10f ? scoreMap[i]      // keep solid background
                            : scoreMap[i] * 0.55f + openScore[i] * 0.45f;
                }

                // Step 1: stronger blur on low-res mask — σ=3 spreads boundary ~15 mask px before upscale
                float[] blurredMask = gaussianBlur(scoreMap, width, height, 3.0f);

                // Step 2: bilinear upsample to src resolution
                float[] alphaMap = new float[srcW * srcH];
                for (int y = 0; y < srcH; y++) {
                    for (int x = 0; x < srcW; x++) {
                        float fx = (x + 0.5f) * width / (float) srcW - 0.5f;
                        float fy = (y + 0.5f) * height / (float) srcH - 0.5f;
                        int x0 = clamp((int) Math.floor(fx), 0, width - 1);
                        int x1 = clamp(x0 + 1, 0, width - 1);
                        int y0 = clamp((int) Math.floor(fy), 0, height - 1);
                        int y1 = clamp(y0 + 1, 0, height - 1);
                        float tx = fx - x0, ty = fy - y0;
                        alphaMap[y * srcW + x] = clamp(lerp(
                                lerp(blurredMask[y0 * width + x0], blurredMask[y0 * width + x1], tx),
                                lerp(blurredMask[y1 * width + x0], blurredMask[y1 * width + x1], tx),
                                ty), 0f, 1f);
                    }
                }

                // Step 3: stronger full-res blur — factor 1.5 covers one full mask pixel's width
                float blurSigma = Math.max(2.0f, (float) srcW / width * 1.5f);
                alphaMap = gaussianBlur(alphaMap, srcW, srcH, blurSigma);

                // Step 3.5: face-based spatial constraint — the chair (headrest + side frames) sits
                // outside the person's plausible silhouette; zero alpha there so it cannot survive.
                Rect faceRect = detectFaceRect(src);
                if (faceRect != null) {
                    float[] alphaBefore = alphaMap.clone();
                    applyFaceConstraint(alphaMap, srcW, srcH, faceRect);
                    removeChairFringe(alphaMap, srcPixels, srcW, srcH, faceRect);
                    restoreNonChairForeground(alphaMap, alphaBefore, srcPixels, srcW, srcH, faceRect);
                }

                // Step 4: guided filter — pulls alpha to follow real image edges (hair, clothing)
                // skip for very large images to avoid excessive heap usage
                if ((long) srcW * srcH <= 2_500_000L) {
                    float[] guide = new float[srcW * srcH];
                    for (int i = 0; i < srcPixels.length; i++) {
                        int c = srcPixels[i];
                        guide[i] = (0.299f * Color.red(c) + 0.587f * Color.green(c)
                                + 0.114f * Color.blue(c)) / 255f;
                    }
                    int gfRadius = Math.max(6, (int) ((float) srcW / width * 1.8f));
                    alphaMap = guidedFilter(guide, alphaMap, srcW, srcH, gfRadius, 0.05f);
                }

                // Step 5: smoothstep feathering — tighter zone keeps person solid, removes hazy halos
                final float feather = 0.12f;
                final float lo = safeThreshold - feather;
                final float hi = safeThreshold + feather;
                int[] outPixels = new int[srcW * srcH];
                for (int i = 0; i < srcPixels.length; i++) {
                    float alpha = smoothstep(lo, hi, alphaMap[i]);
                    int a = Math.min(255, (int) (alpha * 255f + 0.5f));
                    if (a <= 0) {
                        outPixels[i] = Color.TRANSPARENT;
                    } else {
                        int color = srcPixels[i];
                        outPixels[i] = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
                    }
                }

                // Step 6: connectivity cleanup — remove semi-transparent pixels (chair headrest, stray BG)
                // that are not within cleanR pixels of any solid foreground pixel
                int cleanR = Math.max(15, (int) ((float) srcW / width * 3));
                byte[] nearFG = dilateAlpha(outPixels, srcW, srcH, cleanR, 160);
                for (int i = 0; i < outPixels.length; i++) {
                    int a = Color.alpha(outPixels[i]);
                    if (a > 8 && a < 150 && nearFG[i] == 0) {
                        outPixels[i] = Color.TRANSPARENT;
                    }
                }

                // Step 7: color-based cleanup — achromatic medium-luminance pixels in the transition zone
                // are chair/furniture, not person (skin has warmth; white shirt has high lum; BG is black)
                for (int i = 0; i < outPixels.length; i++) {
                    int a = Color.alpha(outPixels[i]);
                    if (a <= 20 || a >= 215) continue;
                    int r = Color.red(outPixels[i]);
                    int g = Color.green(outPixels[i]);
                    int b = Color.blue(outPixels[i]);
                    int maxC = Math.max(r, Math.max(g, b));
                    int minC = Math.min(r, Math.min(g, b));
                    int chroma = maxC - minC;
                    if (chroma < 40 && maxC > 35 && maxC < 195) {
                        outPixels[i] = Color.TRANSPARENT;
                    }
                }

                // Step 8: despill — recover foreground RGB from background-contaminated edge pixels.
                // Formula: F = (C - B*(1-α)) / α  (alpha-matting inversion)
                // Background colour is estimated from the transparent region of the source image.
                {
                    long bR = 0, bG = 0, bB = 0;
                    int bCnt = 0;
                    for (int i = 0; i < outPixels.length; i += 7) {
                        if (Color.alpha(outPixels[i]) < 12) {
                            bR += Color.red(srcPixels[i]);
                            bG += Color.green(srcPixels[i]);
                            bB += Color.blue(srcPixels[i]);
                            bCnt++;
                        }
                    }
                    if (bCnt >= 80) {
                        float fBgR = (float) bR / bCnt;
                        float fBgG = (float) bG / bCnt;
                        float fBgB = (float) bB / bCnt;
                        for (int i = 0; i < outPixels.length; i++) {
                            int a = Color.alpha(outPixels[i]);
                            if (a <= 20 || a >= 235) continue;
                            float fa = a / 255f;
                            int r = Color.red(srcPixels[i]);
                            int g = Color.green(srcPixels[i]);
                            int b = Color.blue(srcPixels[i]);
                            outPixels[i] = Color.argb(a,
                                    clamp(Math.round((r - fBgR * (1f - fa)) / fa), 0, 255),
                                    clamp(Math.round((g - fBgG * (1f - fa)) / fa), 0, 255),
                                    clamp(Math.round((b - fBgB * (1f - fa)) / fa), 0, 255));
                        }
                    }
                }

                // Step 9: local edge-band alpha smoothing — 3×3 Gaussian only in the transition zone,
                // strength reduced near high-gradient pixels (glasses, headphones stay sharp).
                {
                    float[] curA = new float[srcW * srcH];
                    for (int i = 0; i < outPixels.length; i++) curA[i] = Color.alpha(outPixels[i]) / 255f;
                    for (int y = 1; y < srcH - 1; y++) {
                        for (int x = 1; x < srcW - 1; x++) {
                            int idx = y * srcW + x;
                            float a = curA[idx];
                            if (a < 0.08f || a > 0.92f) continue;
                            // L1 gradient magnitude of source image (horizontal + vertical)
                            int dxR = Color.red(srcPixels[idx + 1])   - Color.red(srcPixels[idx - 1]);
                            int dxG = Color.green(srcPixels[idx + 1]) - Color.green(srcPixels[idx - 1]);
                            int dxB = Color.blue(srcPixels[idx + 1])  - Color.blue(srcPixels[idx - 1]);
                            int dyR = Color.red(srcPixels[idx + srcW])   - Color.red(srcPixels[idx - srcW]);
                            int dyG = Color.green(srcPixels[idx + srcW]) - Color.green(srcPixels[idx - srcW]);
                            int dyB = Color.blue(srcPixels[idx + srcW])  - Color.blue(srcPixels[idx - srcW]);
                            float grad = (Math.abs(dxR) + Math.abs(dxG) + Math.abs(dxB)
                                    + Math.abs(dyR) + Math.abs(dyG) + Math.abs(dyB)) / (6f * 255f);
                            // High-gradient pixels (glasses/earphones) get weaker smoothing
                            float w = Math.max(0f, 1f - grad * 5f) * 0.45f;
                            if (w < 0.01f) continue;
                            // 3×3 Gaussian [1,2,1; 2,4,2; 1,2,1] / 16
                            float s = curA[(y-1)*srcW+(x-1)] + curA[(y-1)*srcW+(x+1)]
                                    + curA[(y+1)*srcW+(x-1)] + curA[(y+1)*srcW+(x+1)]
                                    + (curA[(y-1)*srcW+x] + curA[y*srcW+(x-1)]
                                    + curA[y*srcW+(x+1)] + curA[(y+1)*srcW+x]) * 2f
                                    + a * 4f;
                            float smoothed = s / 16f;
                            int newA = clamp(Math.round((a * (1f - w) + smoothed * w) * 255f), 0, 255);
                            outPixels[idx] = Color.argb(newA,
                                    Color.red(outPixels[idx]), Color.green(outPixels[idx]), Color.blue(outPixels[idx]));
                        }
                    }
                }

                Bitmap result = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888);
                result.setPixels(outPixels, 0, srcW, 0, 0, srcW, srcH);
                return result;
            } finally {
                try {
                    segmenter.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "cutoutPerson failed", t);
            return null;
        }
    }

    /** Separable 2-D min filter (erosion) — O(n×r) per pass, cheap at mask resolution. */
    private static float[] minFilter2D(float[] in, int w, int h, int r) {
        float[] tmp = new float[w * h];
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float min = 1f;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++)
                    if (in[y * w + k] < min) min = in[y * w + k];
                tmp[y * w + x] = min;
            }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float min = 1f;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++)
                    if (tmp[k * w + x] < min) min = tmp[k * w + x];
                out[y * w + x] = min;
            }
        return out;
    }

    /** Separable 2-D max filter (dilation) — O(n×r) per pass, cheap at mask resolution. */
    private static float[] maxFilter2D(float[] in, int w, int h, int r) {
        float[] tmp = new float[w * h];
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float max = 0f;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++)
                    if (in[y * w + k] > max) max = in[y * w + k];
                tmp[y * w + x] = max;
            }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float max = 0f;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++)
                    if (tmp[k * w + x] > max) max = tmp[k * w + x];
                out[y * w + x] = max;
            }
        return out;
    }

    /** Separable max dilation — each output pixel = 1 if any pixel in r-neighbourhood exceeds alphaThreshold. */
    private static byte[] dilateAlpha(int[] pixels, int w, int h, int r, int alphaThreshold) {
        byte[] seed = new byte[w * h];
        for (int i = 0; i < w * h; i++) {
            seed[i] = Color.alpha(pixels[i]) >= alphaThreshold ? (byte) 1 : (byte) 0;
        }
        // Horizontal pass with early exit
        byte[] tmp = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                byte max = 0;
                int klo = Math.max(0, x - r), khi = Math.min(w - 1, x + r);
                for (int k = klo; k <= khi; k++) {
                    if (seed[y * w + k] == 1) { max = 1; break; }
                }
                tmp[y * w + x] = max;
            }
        }
        // Vertical pass with early exit
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                byte max = 0;
                int klo = Math.max(0, y - r), khi = Math.min(h - 1, y + r);
                for (int k = klo; k <= khi; k++) {
                    if (tmp[k * w + x] == 1) { max = 1; break; }
                }
                out[y * w + x] = max;
            }
        }
        return out;
    }

    /** Largest face bounding box, or null if detection fails or the person is too small. */
    public static Rect detectFaceRect(Bitmap src) {
        try {
            FaceDetectorOptions opts = new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .build();
            FaceDetector detector = FaceDetection.getClient(opts);
            try {
                List<Face> faces = Tasks.await(
                        detector.process(InputImage.fromBitmap(src, 0)), 10, TimeUnit.SECONDS);
                if (faces == null || faces.isEmpty()) return null;
                Rect best = null;
                for (Face f : faces) {
                    Rect r = f.getBoundingBox();
                    if (r == null) continue;
                    if (best == null || r.width() * r.height() > best.width() * best.height()) {
                        best = r;
                    }
                }
                // Person too small in frame → constraint unreliable, skip to avoid cutting body
                if (best != null && best.width() < src.getWidth() * 0.06f) return null;
                return best;
            } finally {
                try { detector.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.e(TAG, "face detection failed", t);
            return null;
        }
    }

    /**
     * Zero out alpha outside an expanded face box (person silhouette) with a soft falloff band.
     * Kills the chair headrest/side frames while preserving head, shoulders and the headset.
     */
    private static void applyFaceConstraint(float[] alpha, int w, int h, Rect face) {
        int cx = face.centerX();
        int cy = face.centerY();
        float fw = face.width();
        float fh = face.height();
        // core region: head + shoulders + torso + arms (half-length portrait)
        int coreLeft   = clamp(Math.round(cx - fw * 2.0f), 0, w - 1);
        int coreRight  = clamp(Math.round(cx + fw * 2.0f), 0, w - 1);
        int coreTop    = clamp(Math.round(cy - fh * 1.15f), 0, h - 1);
        int coreBottom = clamp(Math.round(cy + fh * 4.6f), 0, h - 1);
        float softW = Math.max(1f, fw * 0.35f);

        for (int y = 0; y < h; y++) {
            int dy = y < coreTop ? coreTop - y : y > coreBottom ? y - coreBottom : 0;
            if (dy > softW) {
                // entire row outside the falloff band → zero it
                for (int x = 0; x < w; x++) alpha[y * w + x] = 0f;
                continue;
            }
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (alpha[idx] <= 0f) continue;
                int dx = x < coreLeft ? coreLeft - x : x > coreRight ? x - coreRight : 0;
                float d = Math.max(dx, dy);
                if (d > 0f) {
                    if (d >= softW) {
                        alpha[idx] = 0f;
                    } else {
                        alpha[idx] *= 1f - d / softW;
                    }
                }
            }
        }
    }

    /**
     * Remove chair remnants around the head and neck with three bands, each with its own guard:
     *   A) above the face box — full width, no guard (headrest bar is light, hair is dark)
     *   B) beside the face — wide, guard only the face column (chair side frames)
     *   C) beside the neck — narrow, guard the face column (neck remnants, never reaches shoulders)
     */
    private static void removeChairFringe(float[] alpha, int[] srcPixels, int w, int h, Rect face) {
        int cx = face.centerX();
        float fw = face.width();
        float fh = face.height();
        int faceL = clamp(face.left, 0, w - 1);
        int faceR = clamp(face.right, 0, w - 1);

        // A) above face: y0 .. face.top-1
        int aX0 = clamp(Math.round(cx - fw * 2.6f), 0, w - 1);
        int aX1 = clamp(Math.round(cx + fw * 2.6f), 0, w - 1);
        int aY0 = clamp(face.top - Math.round(fh * 1.4f), 0, h - 1);
        int aY1 = clamp(face.top - 1, 0, h - 1);
        for (int y = aY0; y <= aY1; y++)
            for (int x = aX0; x <= aX1; x++)
                clearIfLightAchromatic(alpha, srcPixels, y * w + x);

        // B) beside face: face.top .. face.bottom, wide, guard face column
        int bX0 = aX0, bX1 = aX1;
        int bY0 = clamp(face.top, 0, h - 1);
        int bY1 = clamp(face.bottom, 0, h - 1);
        for (int y = bY0; y <= bY1; y++)
            for (int x = bX0; x <= bX1; x++) {
                if (x >= faceL && x <= faceR) continue;
                clearIfLightAchromatic(alpha, srcPixels, y * w + x);
            }

        // C) beside neck: narrow band just below chin, guard face column
        int cX0 = clamp(Math.round(cx - fw * 0.6f), 0, w - 1);
        int cX1 = clamp(Math.round(cx + fw * 0.6f), 0, w - 1);
        int cY0 = clamp(face.bottom + 1, 0, h - 1);
        int cY1 = clamp(face.bottom + Math.round(fh * 0.3f), 0, h - 1);
        for (int y = cY0; y <= cY1; y++)
            for (int x = cX0; x <= cX1; x++) {
                if (x >= faceL && x <= faceR) continue;
                clearIfLightAchromatic(alpha, srcPixels, y * w + x);
            }
    }

    /** Zero alpha at idx if the source pixel is light and achromatic (chair), else keep. */
    private static void clearIfLightAchromatic(float[] alpha, int[] srcPixels, int idx) {
        if (alpha[idx] <= 0.1f) return;
        int c = srcPixels[idx];
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        int maxC = Math.max(r, Math.max(g, b));
        int minC = Math.min(r, Math.min(g, b));
        if (maxC - minC < 45 && maxC > 140) {
            alpha[idx] = 0f;
        }
    }

    /**
     * Restore foreground pixels the face constraint zeroed outside the core region, as long as
     * ML Kit strongly classified them as person and they are not light-achromatic (i.e. not the
     * white chair). This rescues a raised hand (skin) and a dark controller without bringing
     * the chair back.
     */
    private static void restoreNonChairForeground(float[] alpha, float[] before, int[] srcPixels,
                                                  int w, int h, Rect face) {
        int cx = face.centerX();
        float fw = face.width();
        float fh = face.height();
        int coreLeft   = clamp(Math.round(cx - fw * 2.0f), 0, w - 1);
        int coreRight  = clamp(Math.round(cx + fw * 2.0f), 0, w - 1);
        int coreTop    = clamp(Math.round(face.centerY() - fh * 1.15f), 0, h - 1);
        int coreBottom = clamp(Math.round(face.centerY() + fh * 4.6f), 0, h - 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= coreLeft && x <= coreRight && y >= coreTop && y <= coreBottom) continue;
                int idx = y * w + x;
                if (before[idx] < 0.7f) continue;   // ML Kit not confident → background
                if (alpha[idx] > 0.1f) continue;    // not zeroed → leave as is
                int c = srcPixels[idx];
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                int maxC = Math.max(r, Math.max(g, b));
                int minC = Math.min(r, Math.min(g, b));
                boolean lightAchromatic = (maxC - minC < 45 && maxC > 140);
                if (!lightAchromatic) {
                    alpha[idx] = before[idx];       // restore dark/colored foreground
                }
            }
        }
    }

    /**
     * Guided filter: pulls alpha to follow edges in the luminance guide image.
     * He et al. 2013 — O(n) via box filter.
     */
    private static float[] guidedFilter(float[] guide, float[] src, int w, int h, int r, float eps) {
        float[] meanI  = boxFilter(guide, w, h, r);
        float[] meanP  = boxFilter(src,   w, h, r);
        float[] II = new float[w * h];
        float[] IP = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            II[i] = guide[i] * guide[i];
            IP[i] = guide[i] * src[i];
        }
        float[] meanII = boxFilter(II, w, h, r);
        float[] meanIP = boxFilter(IP, w, h, r);
        float[] a = new float[w * h];
        float[] b = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            float varI  = meanII[i] - meanI[i] * meanI[i];
            float covIP = meanIP[i] - meanI[i] * meanP[i];
            a[i] = covIP / (varI + eps);
            b[i] = meanP[i] - a[i] * meanI[i];
        }
        float[] meanA = boxFilter(a, w, h, r);
        float[] meanB = boxFilter(b, w, h, r);
        float[] out = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            out[i] = clamp(meanA[i] * guide[i] + meanB[i], 0f, 1f);
        }
        return out;
    }

    /** Separable box filter using prefix sums — O(n) regardless of radius. */
    private static float[] boxFilter(float[] input, int w, int h, int r) {
        float[] tmp = new float[w * h];
        float[] out = new float[w * h];
        int maxDim = Math.max(w, h);
        float[] prefix = new float[maxDim + 1];
        // Horizontal
        for (int y = 0; y < h; y++) {
            prefix[0] = 0f;
            for (int x = 0; x < w; x++) prefix[x + 1] = prefix[x] + input[y * w + x];
            for (int x = 0; x < w; x++) {
                int lo = Math.max(0, x - r), hi = Math.min(w - 1, x + r);
                tmp[y * w + x] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1);
            }
        }
        // Vertical
        for (int x = 0; x < w; x++) {
            prefix[0] = 0f;
            for (int y = 0; y < h; y++) prefix[y + 1] = prefix[y] + tmp[y * w + x];
            for (int y = 0; y < h; y++) {
                int lo = Math.max(0, y - r), hi = Math.min(h - 1, y + r);
                out[y * w + x] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1);
            }
        }
        return out;
    }

    /**
     * Separable Gaussian blur on a float array. Clamps to border pixels.
     */
    private static float[] gaussianBlur(float[] input, int w, int h, float sigma) {
        int radius = (int) Math.ceil(sigma * 3);
        float[] kernel = new float[2 * radius + 1];
        float sum = 0f;
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = (float) Math.exp(-0.5 * i * i / (sigma * sigma));
            sum += kernel[i + radius];
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;

        float[] tmp = new float[w * h];
        float[] out = new float[w * h];

        // Horizontal pass
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0f;
                for (int k = -radius; k <= radius; k++) {
                    acc += input[y * w + clamp(x + k, 0, w - 1)] * kernel[k + radius];
                }
                tmp[y * w + x] = acc;
            }
        }
        // Vertical pass
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0f;
                for (int k = -radius; k <= radius; k++) {
                    acc += tmp[clamp(y + k, 0, h - 1) * w + x] * kernel[k + radius];
                }
                out[y * w + x] = acc;
            }
        }
        return out;
    }

    /** Smooth Hermite interpolation between 0 and 1. */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }
}

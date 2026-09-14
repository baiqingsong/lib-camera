package com.dawn.filter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

                float[] alphaMap = new float[srcW * srcH];
                final float lowEdge = Math.max(0.08f, safeThreshold - 0.18f);
                final float highEdge = Math.min(0.92f, safeThreshold + 0.12f);

                for (int y = 0; y < srcH; y++) {
                    for (int x = 0; x < srcW; x++) {
                        int idx = y * srcW + x;

                        float fx = (x + 0.5f) * width / (float) srcW - 0.5f;
                        float fy = (y + 0.5f) * height / (float) srcH - 0.5f;

                        int x0 = clamp((int) Math.floor(fx), 0, width - 1);
                        int x1 = clamp(x0 + 1, 0, width - 1);
                        int y0 = clamp((int) Math.floor(fy), 0, height - 1);
                        int y1 = clamp(y0 + 1, 0, height - 1);

                        float tx = fx - x0;
                        float ty = fy - y0;

                        float v00 = scoreMap[y0 * width + x0];
                        float v10 = scoreMap[y0 * width + x1];
                        float v01 = scoreMap[y1 * width + x0];
                        float v11 = scoreMap[y1 * width + x1];

                        float top = lerp(v00, v10, tx);
                        float bottom = lerp(v01, v11, tx);
                        float value = lerp(top, bottom, ty);
                        value = clamp(value, 0f, 1f);

                        float sum = value;
                        int count = 1;
                        int xMin = clamp(x0 - 1, 0, width - 1);
                        int xMax = clamp(x0 + 1, 0, width - 1);
                        int yMin = clamp(y0 - 1, 0, height - 1);
                        int yMax = clamp(y0 + 1, 0, height - 1);
                        for (int yy = yMin; yy <= yMax; yy++) {
                            for (int xx = xMin; xx <= xMax; xx++) {
                                if (xx == x0 && yy == y0) continue;
                                sum += scoreMap[yy * width + xx];
                                count++;
                            }
                        }
                        float avg = sum / count;

                        float alpha = 0f;
                        if (value >= safeThreshold) {
                            alpha = 1f;
                        } else if (value >= lowEdge) {
                            alpha = clamp((value - lowEdge) / Math.max(0.01f, safeThreshold - lowEdge), 0f, 1f);
                        } else if (avg > lowEdge - 0.05f) {
                            alpha = 0.18f + clamp((avg - (lowEdge - 0.05f)) / 0.25f, 0f, 1f) * 0.45f;
                        }

                        if (value > 0.55f && avg < 0.35f) {
                            alpha = Math.max(alpha, 0.68f);
                        }
                        if (value > 0.22f && avg > 0.28f) {
                            alpha = Math.max(alpha, 0.28f);
                        }
                        if (value < 0.10f && avg < 0.10f) {
                            alpha = 0f;
                        }

                        alphaMap[idx] = clamp(alpha, 0f, 1f);
                    }
                }

                int[] outPixels = new int[srcPixels.length];
                for (int y = 0; y < srcH; y++) {
                    for (int x = 0; x < srcW; x++) {
                        int idx = y * srcW + x;
                        int color = srcPixels[idx];

                        float center = alphaMap[idx];
                        float neighborSum = 0f;
                        int neighborCount = 0;
                        for (int ky = -1; ky <= 1; ky++) {
                            int ny = y + ky;
                            if (ny < 0 || ny >= srcH) continue;
                            for (int kx = -1; kx <= 1; kx++) {
                                int nx = x + kx;
                                if (nx < 0 || nx >= srcW) continue;
                                neighborSum += alphaMap[ny * srcW + nx];
                                neighborCount++;
                            }
                        }
                        float neighborAvg = neighborCount > 0 ? neighborSum / neighborCount : center;

                        float alpha = center;
                        if (center > 0.08f && center < 0.55f) {
                            alpha = center * 0.7f + neighborAvg * 0.3f;
                        }
                        if (center < 0.15f && neighborAvg > 0.32f) {
                            alpha = 0.18f;
                        }
                        if (center > 0.60f && neighborAvg < 0.18f) {
                            alpha = 0.72f;
                        }

                        int alphaByte = (int) (clamp(alpha, 0f, 1f) * 255f);
                        if (alphaByte <= 6) {
                            outPixels[idx] = Color.TRANSPARENT;
                        } else {
                            outPixels[idx] = Color.argb(alphaByte,
                                    Color.red(color),
                                    Color.green(color),
                                    Color.blue(color));
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

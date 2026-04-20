package com.dawn.filter;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.Serializable;

/**
 * LUT（Look-Up Table）颜色查找表生成器。
 * <p>
 * 生成 512×512 的颜色查找表位图，配合 {@link jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter} 使用。
 * 所有颜色变换在 LUT 生成阶段一次完成，实际滤镜渲染仅需单次 GPU 纹理查找，
 * 效果远优于多滤镜链叠加（FilterGroup）。
 * <p>
 * 这是 Instagram / VSCO / 抖音等主流滤镜 App 的核心方案。
 */
public class LutGenerator {

    private static final int LUT_SIZE = 512;
    private static final int CELL_SIZE = 64; // 512 / 8

    /**
     * 颜色变换参数。所有参数都有明确的中立值（无变化），偏离中立值即产生效果。
     */
    public static class ColorParams implements Serializable {
        private static final long serialVersionUID = 1L;

        // ===== 基础调整 =====
        /** 亮度偏移，范围 -0.3 ~ 0.3，中立值 0 */
        public float brightness = 0f;
        /** 对比度倍率，范围 0.5 ~ 2.0，中立值 1.0 */
        public float contrast = 1.0f;
        /** 饱和度倍率，范围 0.0 ~ 2.0，中立值 1.0（0=灰度） */
        public float saturation = 1.0f;
        /** Gamma 校正，范围 0.5 ~ 2.0，中立值 1.0（<1 提亮暗部，>1 压暗） */
        public float gamma = 1.0f;

        // ===== 色温色调 =====
        /** 色温偏移，-0.3(冷蓝) ~ 0.3(暖橙)，中立值 0 */
        public float temperature = 0f;
        /** 色调偏移，-0.2(偏绿) ~ 0.2(偏品红)，中立值 0 */
        public float tint = 0f;

        // ===== 影调 =====
        /** 暗部提升（褪色感），0 ~ 0.15，中立值 0 */
        public float shadowsLift = 0f;
        /** 高光压缩，0 ~ 0.15，中立值 0 */
        public float highlightsPull = 0f;

        // ===== 分离色调（专业调色核心）=====
        /** 暗部色调偏移 R/G/B，范围 -0.1 ~ 0.1 */
        public float shadowTintR = 0f, shadowTintG = 0f, shadowTintB = 0f;
        /** 亮部色调偏移 R/G/B，范围 -0.1 ~ 0.1 */
        public float highlightTintR = 0f, highlightTintG = 0f, highlightTintB = 0f;

        // ===== 链式设置 =====
        public ColorParams brightness(float v) { brightness = v; return this; }
        public ColorParams contrast(float v) { contrast = v; return this; }
        public ColorParams saturation(float v) { saturation = v; return this; }
        public ColorParams gamma(float v) { gamma = v; return this; }
        public ColorParams temperature(float v) { temperature = v; return this; }
        public ColorParams tint(float v) { tint = v; return this; }
        public ColorParams shadowsLift(float v) { shadowsLift = v; return this; }
        public ColorParams highlightsPull(float v) { highlightsPull = v; return this; }
        public ColorParams shadowTint(float r, float g, float b) {
            shadowTintR = r; shadowTintG = g; shadowTintB = b; return this;
        }
        public ColorParams highlightTint(float r, float g, float b) {
            highlightTintR = r; highlightTintG = g; highlightTintB = b; return this;
        }
    }

    /**
     * 根据颜色参数生成 LUT 位图（512×512，8×8 网格，每格 64×64）。
     * <p>
     * 处理流水线：色温色调 → 亮度 → 对比度 → Gamma → 饱和度 → 影调 → 分离色调 → 裁剪
     */
    public static Bitmap generateLut(ColorParams p) {
        int[] pixels = new int[LUT_SIZE * LUT_SIZE];

        for (int by = 0; by < 8; by++) {
            for (int bx = 0; bx < 8; bx++) {
                int blueIndex = by * 8 + bx;
                float bOrig = blueIndex / 63f;

                for (int gy = 0; gy < CELL_SIZE; gy++) {
                    float gOrig = gy / 63f;

                    for (int rx = 0; rx < CELL_SIZE; rx++) {
                        float rOrig = rx / 63f;

                        float r = rOrig, g = gOrig, b = bOrig;

                        // 1. 色温（暖：+R -B，冷：-R +B）+ 色调（+G 或 -G）
                        r += p.temperature;
                        b -= p.temperature;
                        g += p.tint;

                        // 2. 亮度
                        r += p.brightness;
                        g += p.brightness;
                        b += p.brightness;

                        // 3. 对比度（以 0.5 为中心缩放）
                        r = (r - 0.5f) * p.contrast + 0.5f;
                        g = (g - 0.5f) * p.contrast + 0.5f;
                        b = (b - 0.5f) * p.contrast + 0.5f;

                        // 4. Gamma
                        if (p.gamma != 1.0f) {
                            float invG = 1.0f / p.gamma;
                            r = (float) Math.pow(Math.max(r, 0f), invG);
                            g = (float) Math.pow(Math.max(g, 0f), invG);
                            b = (float) Math.pow(Math.max(b, 0f), invG);
                        }

                        // 5. 饱和度（基于 BT.709 亮度系数）
                        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                        r = lum + (r - lum) * p.saturation;
                        g = lum + (g - lum) * p.saturation;
                        b = lum + (b - lum) * p.saturation;

                        // 6. 暗部提升 / 高光压缩
                        float range = 1f - p.shadowsLift - p.highlightsPull;
                        r = p.shadowsLift + r * range;
                        g = p.shadowsLift + g * range;
                        b = p.shadowsLift + b * range;

                        // 7. 分离色调（基于亮度混合）
                        float lumFinal = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                        float shadowW = 1f - clamp(lumFinal * 2f);   // 暗部权重
                        float highlightW = clamp(lumFinal * 2f - 1f); // 亮部权重
                        r += p.shadowTintR * shadowW + p.highlightTintR * highlightW;
                        g += p.shadowTintG * shadowW + p.highlightTintG * highlightW;
                        b += p.shadowTintB * shadowW + p.highlightTintB * highlightW;

                        // 8. 裁剪
                        r = clamp(r);
                        g = clamp(g);
                        b = clamp(b);

                        int px = bx * CELL_SIZE + rx;
                        int py = by * CELL_SIZE + gy;
                        pixels[py * LUT_SIZE + px] = Color.argb(255,
                                (int) (r * 255f + 0.5f),
                                (int) (g * 255f + 0.5f),
                                (int) (b * 255f + 0.5f));
                    }
                }
            }
        }

        Bitmap lut = Bitmap.createBitmap(LUT_SIZE, LUT_SIZE, Bitmap.Config.ARGB_8888);
        lut.setPixels(pixels, 0, LUT_SIZE, 0, 0, LUT_SIZE, LUT_SIZE);
        return lut;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}

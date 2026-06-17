package com.dawn.filter;

import android.graphics.PointF;

import java.util.Arrays;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageGaussianBlurFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVignetteFilter;

/**
 * 复古滤镜 — 经典复古照相亭风格。
 * 滤镜链：纯黑白 LUT → 轻微柔焦 → 暗角。
 * <p>
 * 核心效果：
 * <ul>
 *   <li>纯黑白：完全去色 + 中性色调，还原老式照相亭的黑白质感</li>
 *   <li>高对比度：强化主体的黑白层次感，黑实白亮</li>
 *   <li>柔焦：轻微高斯模糊，模拟老式照相亭镜头的柔软质感</li>
 *   <li>暗角：四角轻微自然压暗，模拟照相亭闪光灯效果</li>
 * </ul>
 */
public class RetroFilter extends GPUImageFilterGroup {

    private final GPUImageLookupFilter lookupFilter;
    private final GPUImageGaussianBlurFilter blurFilter;
    private final GPUImageVignetteFilter vignetteFilter;
    private float intensity;

    public RetroFilter(float intensity) {
        this(FilterPreset.RETRO_BOOTH.createLookupFilter(clamp(intensity)), clamp(intensity));
    }

    private RetroFilter(GPUImageLookupFilter lookupFilter, float intensity) {
        this(lookupFilter,
                new GPUImageGaussianBlurFilter(),
                new GPUImageVignetteFilter(new PointF(0.5f, 0.5f),
                        new float[]{0.0f, 0.0f, 0.0f}, 0.60f, 0.95f),
                intensity);
    }

    private RetroFilter(GPUImageLookupFilter lookupFilter,
                        GPUImageGaussianBlurFilter blurFilter,
                        GPUImageVignetteFilter vignetteFilter,
                        float intensity) {
        super(Arrays.asList(lookupFilter, blurFilter, vignetteFilter));
        this.lookupFilter = lookupFilter;
        this.blurFilter = blurFilter;
        this.vignetteFilter = vignetteFilter;
        setIntensity(intensity);
    }

    /**
     * 设置滤镜强度。
     *
     * @param intensity 0.0~1.0，其中 1.0 为完整复古效果
     */
    public void setIntensity(float intensity) {
        this.intensity = clamp(intensity);
        lookupFilter.setIntensity(this.intensity);

        // 柔焦：极轻微高斯模糊，模拟老式镜头质感但不损失清晰度（blurSize 0~0.22）
        blurFilter.setBlurSize(this.intensity * 0.22f);

        // 暗角：适中自然压暗，模拟照相亭闪光灯衰减，为亮底画面增加层次感
        float vignetteStrength = 0.06f + this.intensity * 0.05f;
        vignetteFilter.setVignetteStart(0.62f - vignetteStrength * 0.40f);
        vignetteFilter.setVignetteEnd(0.94f - vignetteStrength * 0.12f);
    }

    public float getIntensity() {
        return intensity;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}

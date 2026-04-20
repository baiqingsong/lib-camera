package com.dawn.filter;

import android.graphics.PointF;

import java.util.Arrays;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVignetteFilter;

/**
 * 黑白氛围滤镜。
 * 在黑白 LUT 基础上叠加轻暗角，强化主体聚焦和复古氛围。
 */
public class BlackWhiteMoodFilter extends GPUImageFilterGroup {

    private final GPUImageLookupFilter lookupFilter;
    private final GPUImageVignetteFilter vignetteFilter;
    private float intensity;

    public BlackWhiteMoodFilter(float intensity) {
        this(FilterPreset.BLACK_WHITE.createLookupFilter(clamp(intensity)), clamp(intensity));
    }

    private BlackWhiteMoodFilter(GPUImageLookupFilter lookupFilter, float intensity) {
        this(lookupFilter,
                new GPUImageVignetteFilter(new PointF(0.5f, 0.5f),
                new float[]{0.0f, 0.0f, 0.0f}, 0.50f, 0.80f),
                intensity);
    }

    private BlackWhiteMoodFilter(GPUImageLookupFilter lookupFilter,
                                 GPUImageVignetteFilter vignetteFilter,
                                 float intensity) {
        super(Arrays.asList(lookupFilter, vignetteFilter));
        this.lookupFilter = lookupFilter;
        this.vignetteFilter = vignetteFilter;
        setIntensity(intensity);
    }

    public void setIntensity(float intensity) {
        this.intensity = clamp(intensity);
        lookupFilter.setIntensity(this.intensity);

        // 保持约 10%~15% 的暗角强度，但让聚焦更明确，适合黑白氛围自拍。
        float vignetteStrength = 0.10f + this.intensity * 0.05f;
        vignetteFilter.setVignetteStart(0.58f - vignetteStrength * 0.45f);
        vignetteFilter.setVignetteEnd(0.80f - vignetteStrength * 0.18f);
    }

    public float getIntensity() {
        return intensity;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
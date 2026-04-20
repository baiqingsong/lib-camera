package com.dawn.filter;

import java.util.Arrays;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter;

/**
 * 质感灰滤镜。
 * 在冷青灰 LUT 基础上叠加适度锐化，强化面部和服饰细节。
 */
public class TexturedGrayFilter extends GPUImageFilterGroup {

    private final GPUImageLookupFilter lookupFilter;
    private final GPUImageSharpenFilter sharpenFilter;
    private float intensity;

    public TexturedGrayFilter(float intensity) {
        this(FilterPreset.TEXTURED_GRAY.createLookupFilter(clamp(intensity)), clamp(intensity));
    }

    private TexturedGrayFilter(GPUImageLookupFilter lookupFilter, float intensity) {
        this(lookupFilter, new GPUImageSharpenFilter(), intensity);
    }

    private TexturedGrayFilter(GPUImageLookupFilter lookupFilter,
                               GPUImageSharpenFilter sharpenFilter,
                               float intensity) {
        super(Arrays.asList(lookupFilter, sharpenFilter));
        this.lookupFilter = lookupFilter;
        this.sharpenFilter = sharpenFilter;
        setIntensity(intensity);
    }

    public void setIntensity(float intensity) {
        this.intensity = clamp(intensity);
        lookupFilter.setIntensity(this.intensity);

        // 中等偏上的锐化，保留商务和氛围自拍需要的轮廓细节。
        sharpenFilter.setSharpness(0.20f + this.intensity * 0.55f);
    }

    public float getIntensity() {
        return intensity;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
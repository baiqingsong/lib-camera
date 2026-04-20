package com.dawn.filter;

import java.util.Arrays;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter;

/**
 * 个性滤镜。
 * 通过更明确的色调风格和适度锐化，强化创意自拍的清晰度和层次感。
 */
public class PersonalityFilter extends GPUImageFilterGroup {

    private final GPUImageLookupFilter lookupFilter;
    private final GPUImageSharpenFilter sharpenFilter;
    private float intensity;

    public PersonalityFilter(float intensity) {
        this(FilterPreset.PERSONALITY.createLookupFilter(clamp(intensity)), clamp(intensity));
    }

    private PersonalityFilter(GPUImageLookupFilter lookupFilter, float intensity) {
        this(lookupFilter, new GPUImageSharpenFilter(), intensity);
    }

    private PersonalityFilter(GPUImageLookupFilter lookupFilter,
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

        // 个性滤镜需要更清楚的轮廓，但避免过锐导致肤质发硬。
        sharpenFilter.setSharpness(0.15f + this.intensity * 0.55f);
    }

    public float getIntensity() {
        return intensity;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
package com.dawn.filter;

import java.util.Arrays;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter;

/**
 * 美颜模块 + 滤镜模块的组合渲染管线。
 * 两个模块在数据结构上独立，在渲染链路上串联。
 */
public class BeautyFilterPipeline extends GPUImageFilterGroup {

    private final GPUImageBeautyFilter beautyFilter;
    private final GPUImageFilter styleFilter;
    private final FilterStyle filterStyle;
    private float filterIntensity;

    public BeautyFilterPipeline(BeautyParams beautyParams, FilterStyle filterStyle, float filterIntensity) {
        this(new GPUImageBeautyFilter(beautyParams),
                filterStyle == null ? FilterStyle.ORIGINAL : filterStyle,
                clamp(filterIntensity));
    }

    private BeautyFilterPipeline(GPUImageBeautyFilter beautyFilter, FilterStyle filterStyle,
                                 float filterIntensity) {
        this(beautyFilter, filterStyle, filterStyle.createFilter(filterIntensity), filterIntensity);
    }

    private BeautyFilterPipeline(GPUImageBeautyFilter beautyFilter, FilterStyle filterStyle,
                                 GPUImageFilter styleFilter, float filterIntensity) {
        super(Arrays.asList(beautyFilter, styleFilter));
        this.beautyFilter = beautyFilter;
        this.styleFilter = styleFilter;
        this.filterStyle = filterStyle;
        this.filterIntensity = filterIntensity;
    }

    public void updateBeautyParams(BeautyParams beautyParams) {
        beautyFilter.setBeautyParams(beautyParams);
    }

    public void updateFilterIntensity(float filterIntensity) {
        this.filterIntensity = clamp(filterIntensity);
        if (styleFilter instanceof GPUImageLookupFilter) {
            ((GPUImageLookupFilter) styleFilter).setIntensity(this.filterIntensity);
        } else if (styleFilter instanceof BlackWhiteMoodFilter) {
            ((BlackWhiteMoodFilter) styleFilter).setIntensity(this.filterIntensity);
        } else if (styleFilter instanceof TexturedGrayFilter) {
            ((TexturedGrayFilter) styleFilter).setIntensity(this.filterIntensity);
        } else if (styleFilter instanceof PersonalityFilter) {
            ((PersonalityFilter) styleFilter).setIntensity(this.filterIntensity);
        }
    }

    public FilterStyle getFilterStyle() {
        return filterStyle;
    }

    public float getFilterIntensity() {
        return filterIntensity;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
package com.dawn.filter;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;

/**
 * 滤镜管理器 — 核心对外 API。
 * <p>
 * 使用示例：
 * <pre>
 *   FilterManager fm = new FilterManager(context);
 *   // 单滤镜
 *   Bitmap result = fm.applyFilter(bitmap, FilterType.SEPIA, 0.8f);
 *   // 预设
 *   Bitmap result2 = fm.applyPreset(bitmap, FilterPreset.VINTAGE);
 *   // 滤镜链
 *   List&lt;FilterSetting&gt; chain = new ArrayList&lt;&gt;();
 *   chain.add(new FilterSetting(FilterType.BRIGHTNESS, 0.6f));
 *   chain.add(new FilterSetting(FilterType.VIGNETTE, 0.7f));
 *   Bitmap result3 = fm.applyFilterChain(bitmap, chain);
 * </pre>
 */
public class FilterManager {

    private final GPUImage gpuImage;

    public FilterManager(Context context) {
        this.gpuImage = new GPUImage(context);
    }

    /**
     * 应用单个滤镜到 Bitmap。
     *
     * @param input     输入图片
     * @param type      滤镜类型
     * @param intensity 强度 0.0~1.0
     * @return 处理后的 Bitmap
     */
    public Bitmap applyFilter(Bitmap input, FilterType type, float intensity) {
        if (input == null || type == null || type == FilterType.NONE) {
            return input;
        }
        gpuImage.setImage(input);
        gpuImage.setFilter(FilterFactory.createFilter(type, intensity));
        return gpuImage.getBitmapWithFilterApplied();
    }

    /**
     * 应用实时美颜参数到 Bitmap。
     */
    public Bitmap applyBeauty(Bitmap input, BeautyParams beautyParams) {
        if (input == null) {
            return input;
        }
        gpuImage.setImage(input);
        gpuImage.setFilter(FilterFactory.createBeautyFilter(beautyParams));
        return gpuImage.getBitmapWithFilterApplied();
    }

    /**
     * 应用滤镜模块。
     */
    public Bitmap applyFilter(Bitmap input, FilterStyle filterStyle, float intensity) {
        if (input == null || filterStyle == null) {
            return input;
        }
        gpuImage.setImage(input);
        gpuImage.setFilter(filterStyle.createFilter(intensity));
        return gpuImage.getBitmapWithFilterApplied();
    }

    /**
     * 应用美颜模块和滤镜模块。
     */
    public Bitmap applyModules(Bitmap input, BeautyParams beautyParams,
                               FilterStyle filterStyle, float filterIntensity) {
        if (input == null) {
            return input;
        }
        gpuImage.setImage(input);
        gpuImage.setFilter(new BeautyFilterPipeline(beautyParams, filterStyle, filterIntensity));
        return gpuImage.getBitmapWithFilterApplied();
    }

    /**
     * 应用单个滤镜，使用默认强度 0.5。
     */
    public Bitmap applyFilter(Bitmap input, FilterType type) {
        return applyFilter(input, type, 0.5f);
    }

    /**
     * 应用滤镜配置。
     */
    public Bitmap applyFilter(Bitmap input, FilterSetting setting) {
        if (setting == null) return input;
        return applyFilter(input, setting.getFilterType(), setting.getIntensity());
    }

    /**
     * 应用滤镜链（多个滤镜叠加）。
     *
     * @param input    输入图片
     * @param settings 滤镜配置列表，按顺序叠加
     * @return 处理后的 Bitmap
     */
    public Bitmap applyFilterChain(Bitmap input, List<FilterSetting> settings) {
        if (input == null || settings == null || settings.isEmpty()) {
            return input;
        }
        List<GPUImageFilter> filters = new ArrayList<>();
        for (FilterSetting s : settings) {
            if (s.getFilterType() != FilterType.NONE) {
                filters.add(FilterFactory.createFilter(s.getFilterType(), s.getIntensity()));
            }
        }
        if (filters.isEmpty()) return input;

        gpuImage.setImage(input);
        GPUImageFilterGroup group = new GPUImageFilterGroup(filters);
        gpuImage.setFilter(group);
        return gpuImage.getBitmapWithFilterApplied();
    }

    /**
     * 应用预设滤镜（基于 LUT 单通道查找，效果远优于滤镜链）。
     */
    public Bitmap applyPreset(Bitmap input, FilterPreset preset) {
        if (preset == null || input == null) return input;
        gpuImage.setImage(input);
        gpuImage.setFilter(preset.createFilter());
        return gpuImage.getBitmapWithFilterApplied();
    }

    /**
     * 获取所有可用滤镜类型。
     */
    public static List<FilterType> getAvailableFilters() {
        return Arrays.asList(FilterType.values());
    }

    /**
     * 获取适合相机/图片调色使用的滤镜列表（排除边缘检测、畸变等非摄影滤镜）。
     */
    public static List<FilterType> getPhotographyFilters() {
        List<FilterType> result = new ArrayList<>();
        for (FilterType type : FilterType.values()) {
            switch (type) {
                case SOBEL_EDGE:
                case LAPLACIAN:
                case EMBOSS:
                case CGA_COLORSPACE:
                case CROSSHATCH:
                case GLASS_SPHERE:
                case SPHERE_REFRACTION:
                case SWIRL:
                case FALSE_COLOR:
                case LOOKUP:
                case OPACITY:
                case LEVELS:
                case TONE_CURVE:
                case HALFTONE:
                    break; // 排除
                default:
                    result.add(type);
            }
        }
        return result;
    }

    /**
     * 获取所有预设滤镜。
     */
    public static List<FilterPreset> getPresets() {
        return FilterPreset.getAllPresets();
    }

    public static List<FilterStyle> getSupportedFilterStyles() {
        return FilterStyle.getSupportedStyles();
    }

    /**
     * 获取内部 GPUImage 实例（高级用法，如绑定到 GPUImageView）。
     */
    public GPUImage getGpuImage() {
        return gpuImage;
    }

    /**
     * 释放 GPUImage 资源。不再使用时调用。
     */
    public void release() {
        if (gpuImage != null) {
            gpuImage.deleteImage();
        }
    }
}

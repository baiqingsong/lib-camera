package com.dawn.filter;

import java.io.Serializable;

/**
 * 单个滤镜的配置：滤镜类型 + 强度参数。
 * 可序列化，用于保存/恢复滤镜设置。
 */
public class FilterSetting implements Serializable {

    private static final long serialVersionUID = 1L;

    private FilterType filterType;
    private float intensity; // 0.0 ~ 1.0

    public FilterSetting() {
        this.filterType = FilterType.NONE;
        this.intensity = 0.5f;
    }

    public FilterSetting(FilterType filterType) {
        this.filterType = filterType;
        this.intensity = 0.5f;
    }

    public FilterSetting(FilterType filterType, float intensity) {
        this.filterType = filterType;
        this.intensity = Math.max(0f, Math.min(1f, intensity));
    }

    public FilterType getFilterType() {
        return filterType;
    }

    public void setFilterType(FilterType filterType) {
        this.filterType = filterType;
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = Math.max(0f, Math.min(1f, intensity));
    }

    @Override
    public String toString() {
        return filterType.getDisplayNameCn() + " (" + (int) (intensity * 100) + "%)";
    }
}

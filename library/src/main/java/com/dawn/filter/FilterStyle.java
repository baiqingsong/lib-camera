package com.dawn.filter;

import java.util.ArrayList;
import java.util.List;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;

/**
 * 业务层滤镜模块。
 * 仅保留产品要求的核心滤镜，并统一支持强度调节。
 */
public enum FilterStyle {

    ORIGINAL("原图", null),
    BLACK_WHITE("黑白", FilterPreset.BLACK_WHITE),
    FRESH("小清新", FilterPreset.FRESH),
    ICE_BLUE("冰蓝", FilterPreset.ICE_BLUE),
    PEACH("蜜桃", FilterPreset.PEACH),
    TEXTURED_GRAY("质感灰", FilterPreset.TEXTURED_GRAY),
    PERSONALITY("个性", FilterPreset.PERSONALITY),
    COOL_WHITE_SKIN("冷白皮", FilterPreset.COOL_WHITE_SKIN),
    ADVANCED_GRAY("高级灰", FilterPreset.ADVANCED_GRAY);

    private final String displayNameCn;
    private final FilterPreset preset;

    FilterStyle(String displayNameCn, FilterPreset preset) {
        this.displayNameCn = displayNameCn;
        this.preset = preset;
    }

    public String getDisplayNameCn() {
        return displayNameCn;
    }

    public boolean isAdjustable() {
        return preset != null;
    }

    public GPUImageFilter createFilter(float intensity) {
        if (this == BLACK_WHITE) {
            return new BlackWhiteMoodFilter(intensity);
        }
        if (this == TEXTURED_GRAY) {
            return new TexturedGrayFilter(intensity);
        }
        if (this == PERSONALITY) {
            return new PersonalityFilter(intensity);
        }
        if (preset == null) {
            return new GPUImageFilter();
        }
        return preset.createFilter(intensity);
    }

    public BeautyParams getRecommendedBeautyParams(BeautyParams currentParams) {
        if (this != PEACH) {
            return currentParams == null ? BeautyParams.defaultCamera() : currentParams.copy();
        }
        BeautyParams params = currentParams == null ? BeautyParams.defaultCamera() : currentParams.copy();
        params.setSmoothness(Math.min(params.getSmoothness(), 0.40f));
        params.setWhiten(Math.max(params.getWhiten(), 0.20f));
        params.setRosy(Math.max(params.getRosy(), 0.24f));
        params.setBrightness(Math.max(params.getBrightness(), 0.15f));
        params.setContrast(Math.min(params.getContrast(), 0.14f));
        return params;
    }

    public static List<FilterStyle> getSupportedStyles() {
        List<FilterStyle> styles = new ArrayList<>();
        styles.add(ORIGINAL);
        styles.add(ICE_BLUE);
        styles.add(FRESH);
        styles.add(PEACH);
        styles.add(COOL_WHITE_SKIN);
        styles.add(ADVANCED_GRAY);
        styles.add(BLACK_WHITE);
        styles.add(TEXTURED_GRAY);
        styles.add(PERSONALITY);
        return styles;
    }
}
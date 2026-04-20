package com.dawn.filter;

/**
 * 所有支持的滤镜类型枚举。
 * 每种滤镜关联：中文名称、英文名称、是否支持强度参数。
 */
public enum FilterType {

    // ===== 无滤镜 =====
    NONE("原图", "Original", false),
    BEAUTY("实时美颜", "Beauty", true),

    // ===== 色调类 =====
    SEPIA("怀旧", "Sepia", true),
    GRAYSCALE("灰度", "Grayscale", false),
    INVERT("反色", "Invert", false),
    POSTERIZE("色调分离", "Posterize", true),
    HUE("色相", "Hue", true),
    WHITE_BALANCE("白平衡", "White Balance", true),

    // ===== 调整类 =====
    BRIGHTNESS("亮度", "Brightness", true),
    CONTRAST("对比度", "Contrast", true),
    SATURATION("饱和度", "Saturation", true),
    GAMMA("伽马", "Gamma", true),
    EXPOSURE("曝光", "Exposure", true),
    HIGHLIGHT_SHADOW("高光阴影", "Highlight Shadow", true),
    SHARPEN("锐化", "Sharpen", true),

    // ===== 模糊类 =====
    GAUSSIAN_BLUR("高斯模糊", "Gaussian Blur", true),
    BOX_BLUR("方框模糊", "Box Blur", true),
    BILATERAL_BLUR("双边模糊", "Bilateral Blur", true),

    // ===== 风格类 =====
    VIGNETTE("暗角", "Vignette", true),
    PIXELATION("像素化", "Pixelation", true),
    SKETCH("素描", "Sketch", false),
    TOON("卡通", "Toon", true),
    EMBOSS("浮雕", "Emboss", true),
    SWIRL("漩涡", "Swirl", true),
    CROSSHATCH("交叉线", "Crosshatch", true),
    CGA_COLORSPACE("CGA色域", "CGA Colorspace", false),
    HALFTONE("半色调", "Halftone", true),
    GLASS_SPHERE("水晶球", "Glass Sphere", true),
    SPHERE_REFRACTION("球面折射", "Sphere Refraction", true),

    // ===== 边缘检测类 =====
    SOBEL_EDGE("边缘检测", "Sobel Edge", true),
    LAPLACIAN("拉普拉斯", "Laplacian", false),

    // ===== 颜色混合类 =====
    COLOR_BALANCE("色彩平衡", "Color Balance", true),
    RGB("RGB通道", "RGB", true),
    MONOCHROME("单色", "Monochrome", true),
    FALSE_COLOR("伪色彩", "False Color", false),
    LOOKUP("色表映射", "Lookup", false),

    // ===== 复合效果类 =====
    VIBRANCE("自然饱和度", "Vibrance", true),
    OPACITY("透明度", "Opacity", true),
    LEVELS("色阶", "Levels", true),
    TONE_CURVE("曲线", "Tone Curve", false);

    private final String displayNameCn;
    private final String displayNameEn;
    private final boolean adjustable;

    FilterType(String displayNameCn, String displayNameEn, boolean adjustable) {
        this.displayNameCn = displayNameCn;
        this.displayNameEn = displayNameEn;
        this.adjustable = adjustable;
    }

    /** 获取中文显示名 */
    public String getDisplayNameCn() {
        return displayNameCn;
    }

    /** 获取英文显示名 */
    public String getDisplayNameEn() {
        return displayNameEn;
    }

    /** 该滤镜是否支持强度/参数调节 */
    public boolean isAdjustable() {
        return adjustable;
    }
}

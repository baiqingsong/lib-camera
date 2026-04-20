package com.dawn.filter;

import jp.co.cyberagent.android.gpuimage.filter.*;

/**
 * 滤镜工厂：根据 FilterType 创建对应的 GPUImageFilter 实例。
 * 支持传入 intensity (0.0~1.0) 来控制滤镜强度。
 */
public class FilterFactory {

    private FilterFactory() {
    }

    /**
     * 创建滤镜实例，使用默认强度。
     */
    public static GPUImageFilter createFilter(FilterType type) {
        return createFilter(type, 0.5f);
    }

    public static GPUImageFilter createBeautyFilter(BeautyParams beautyParams) {
        return new GPUImageBeautyFilter(beautyParams);
    }

    /**
     * 创建滤镜实例。
     *
     * @param type      滤镜类型
     * @param intensity 强度，范围 0.0~1.0（对不可调节的滤镜此参数无效）
     * @return GPUImageFilter 实例
     */
    public static GPUImageFilter createFilter(FilterType type, float intensity) {
        if (type == null) {
            return new GPUImageFilter(); // 无滤镜
        }
        switch (type) {
            case NONE:
                return new GPUImageFilter();

            case BEAUTY:
                return createBeautyFilter(BeautyParams.fromIntensity(intensity));

            // ===== 色调类 =====
            case SEPIA:
                GPUImageSepiaToneFilter sepia = new GPUImageSepiaToneFilter();
                sepia.setIntensity(intensity);
                return sepia;

            case GRAYSCALE:
                return new GPUImageGrayscaleFilter();

            case INVERT:
                return new GPUImageColorInvertFilter();

            case POSTERIZE:
                GPUImagePosterizeFilter posterize = new GPUImagePosterizeFilter();
                posterize.setColorLevels((int) (intensity * 20 + 1)); // 1~21
                return posterize;

            case HUE:
                GPUImageHueFilter hue = new GPUImageHueFilter();
                hue.setHue(intensity * 360f); // 0~360
                return hue;

            case WHITE_BALANCE:
                GPUImageWhiteBalanceFilter wb = new GPUImageWhiteBalanceFilter();
                wb.setTemperature(intensity * 10000f - 2000f); // -2000~8000
                wb.setTint(0f);
                return wb;

            // ===== 调整类 =====
            case BRIGHTNESS:
                GPUImageBrightnessFilter brightness = new GPUImageBrightnessFilter();
                brightness.setBrightness(intensity * 2f - 1f); // -1.0 ~ 1.0
                return brightness;

            case CONTRAST:
                GPUImageContrastFilter contrast = new GPUImageContrastFilter();
                contrast.setContrast(intensity * 4f); // 0.0 ~ 4.0
                return contrast;

            case SATURATION:
                GPUImageSaturationFilter saturation = new GPUImageSaturationFilter();
                saturation.setSaturation(intensity * 2f); // 0.0 ~ 2.0
                return saturation;

            case GAMMA:
                GPUImageGammaFilter gamma = new GPUImageGammaFilter();
                gamma.setGamma(intensity * 3f); // 0.0 ~ 3.0
                return gamma;

            case EXPOSURE:
                GPUImageExposureFilter exposure = new GPUImageExposureFilter();
                exposure.setExposure(intensity * 4f - 2f); // -2.0 ~ 2.0
                return exposure;

            case HIGHLIGHT_SHADOW:
                GPUImageHighlightShadowFilter hs = new GPUImageHighlightShadowFilter();
                hs.setShadows(intensity);
                hs.setHighlights(1f - intensity);
                return hs;

            case SHARPEN:
                GPUImageSharpenFilter sharpen = new GPUImageSharpenFilter();
                sharpen.setSharpness(intensity * 4f - 1f); // -1.0 ~ 3.0
                return sharpen;

            // ===== 模糊类 =====
            case GAUSSIAN_BLUR:
                GPUImageGaussianBlurFilter gaussianBlur = new GPUImageGaussianBlurFilter();
                gaussianBlur.setBlurSize(intensity * 2f); // 0.0 ~ 2.0
                return gaussianBlur;

            case BOX_BLUR:
                GPUImageBoxBlurFilter boxBlur = new GPUImageBoxBlurFilter();
                boxBlur.setBlurSize(intensity * 2f);
                return boxBlur;

            case BILATERAL_BLUR:
                GPUImageBilateralBlurFilter bilateral = new GPUImageBilateralBlurFilter();
                bilateral.setDistanceNormalizationFactor(intensity * 20f); // 0~20
                return bilateral;

            // ===== 风格类 =====
            case VIGNETTE:
                GPUImageVignetteFilter vignette = new GPUImageVignetteFilter();
                vignette.setVignetteStart(0.3f);
                vignette.setVignetteEnd(0.3f + intensity * 0.45f);
                return vignette;

            case PIXELATION:
                GPUImagePixelationFilter pixelation = new GPUImagePixelationFilter();
                pixelation.setPixel(intensity * 50f + 1f); // 1~51
                return pixelation;

            case SKETCH:
                return new GPUImageSketchFilter();

            case TOON:
                GPUImageToonFilter toon = new GPUImageToonFilter();
                toon.setThreshold(intensity * 0.5f + 0.1f);
                toon.setQuantizationLevels(intensity * 15f + 5f);
                return toon;

            case EMBOSS:
                GPUImageEmbossFilter emboss = new GPUImageEmbossFilter();
                emboss.setIntensity(intensity * 4f);
                return emboss;

            case SWIRL:
                GPUImageSwirlFilter swirl = new GPUImageSwirlFilter();
                swirl.setAngle(intensity * 2f);
                swirl.setRadius(0.5f);
                return swirl;

            case CROSSHATCH:
                GPUImageCrosshatchFilter crosshatch = new GPUImageCrosshatchFilter();
                crosshatch.setCrossHatchSpacing(0.01f + intensity * 0.05f);
                crosshatch.setLineWidth(0.001f + intensity * 0.005f);
                return crosshatch;

            case CGA_COLORSPACE:
                return new GPUImageCGAColorspaceFilter();

            case HALFTONE:
                GPUImageHalftoneFilter halftone = new GPUImageHalftoneFilter();
                halftone.setFractionalWidthOfAPixel(intensity * 0.05f + 0.002f);
                return halftone;

            case GLASS_SPHERE:
                GPUImageGlassSphereFilter glassSphere = new GPUImageGlassSphereFilter();
                glassSphere.setRadius(intensity * 0.5f + 0.1f);
                glassSphere.setRefractiveIndex(intensity * 0.1f + 0.6f);
                return glassSphere;

            case SPHERE_REFRACTION:
                GPUImageSphereRefractionFilter sphere = new GPUImageSphereRefractionFilter();
                sphere.setRadius(intensity * 0.5f + 0.1f);
                sphere.setRefractiveIndex(intensity * 0.1f + 0.6f);
                return sphere;

            // ===== 边缘检测类 =====
            case SOBEL_EDGE:
                GPUImageSobelEdgeDetectionFilter sobel = new GPUImageSobelEdgeDetectionFilter();
                sobel.setLineSize(intensity * 3f + 1f);
                return sobel;

            case LAPLACIAN:
                return new GPUImageLaplacianFilter();

            // ===== 颜色混合类 =====
            case COLOR_BALANCE:
                GPUImageColorBalanceFilter cb = new GPUImageColorBalanceFilter();
                cb.setMidtones(new float[]{intensity * 0.5f, 0f, intensity * -0.5f});
                return cb;

            case RGB:
                GPUImageRGBFilter rgb = new GPUImageRGBFilter();
                rgb.setRed(1.0f);
                rgb.setGreen(intensity * 2f);
                rgb.setBlue(intensity * 2f);
                return rgb;

            case MONOCHROME:
                GPUImageMonochromeFilter mono = new GPUImageMonochromeFilter();
                mono.setIntensity(intensity);
                return mono;

            case FALSE_COLOR:
                return new GPUImageFalseColorFilter();

            case LOOKUP:
                return new GPUImageFilter(); // 需要 LUT 纹理，默认返回空滤镜

            // ===== 复合效果类 =====
            case VIBRANCE:
                GPUImageVibranceFilter vibrance = new GPUImageVibranceFilter();
                vibrance.setVibrance(intensity * 3f - 1.5f); // -1.5 ~ 1.5
                return vibrance;

            case OPACITY:
                GPUImageOpacityFilter opacity = new GPUImageOpacityFilter();
                opacity.setOpacity(intensity);
                return opacity;

            case LEVELS:
                GPUImageLevelsFilter levels = new GPUImageLevelsFilter();
                levels.setMin(0f, intensity * 0.5f, 1f);
                return levels;

            case TONE_CURVE:
                GPUImageToneCurveFilter toneCurve = new GPUImageToneCurveFilter();
                // 默认 S 曲线增强对比
                return toneCurve;

            default:
                return new GPUImageFilter();
        }
    }

    /**
     * 更新已有滤镜的强度参数。
     * 避免重新创建滤镜对象，用于实时滑块调节。
     */
    public static void updateIntensity(GPUImageFilter filter, FilterType type, float intensity) {
        if (filter == null || type == null || !type.isAdjustable()) return;

        switch (type) {
            case BEAUTY:
                if (filter instanceof GPUImageBeautyFilter) {
                    ((GPUImageBeautyFilter) filter).setBeautyParams(BeautyParams.fromIntensity(intensity));
                }
                break;
            case SEPIA:
                ((GPUImageSepiaToneFilter) filter).setIntensity(intensity);
                break;
            case POSTERIZE:
                ((GPUImagePosterizeFilter) filter).setColorLevels((int) (intensity * 20 + 1));
                break;
            case HUE:
                ((GPUImageHueFilter) filter).setHue(intensity * 360f);
                break;
            case WHITE_BALANCE:
                ((GPUImageWhiteBalanceFilter) filter).setTemperature(intensity * 10000f - 2000f);
                break;
            case BRIGHTNESS:
                ((GPUImageBrightnessFilter) filter).setBrightness(intensity * 2f - 1f);
                break;
            case CONTRAST:
                ((GPUImageContrastFilter) filter).setContrast(intensity * 4f);
                break;
            case SATURATION:
                ((GPUImageSaturationFilter) filter).setSaturation(intensity * 2f);
                break;
            case GAMMA:
                ((GPUImageGammaFilter) filter).setGamma(intensity * 3f);
                break;
            case EXPOSURE:
                ((GPUImageExposureFilter) filter).setExposure(intensity * 4f - 2f);
                break;
            case HIGHLIGHT_SHADOW:
                ((GPUImageHighlightShadowFilter) filter).setShadows(intensity);
                ((GPUImageHighlightShadowFilter) filter).setHighlights(1f - intensity);
                break;
            case SHARPEN:
                ((GPUImageSharpenFilter) filter).setSharpness(intensity * 4f - 1f);
                break;
            case GAUSSIAN_BLUR:
                ((GPUImageGaussianBlurFilter) filter).setBlurSize(intensity * 2f);
                break;
            case BOX_BLUR:
                ((GPUImageBoxBlurFilter) filter).setBlurSize(intensity * 2f);
                break;
            case BILATERAL_BLUR:
                ((GPUImageBilateralBlurFilter) filter).setDistanceNormalizationFactor(intensity * 20f);
                break;
            case VIGNETTE:
                ((GPUImageVignetteFilter) filter).setVignetteEnd(0.3f + intensity * 0.45f);
                break;
            case PIXELATION:
                ((GPUImagePixelationFilter) filter).setPixel(intensity * 50f + 1f);
                break;
            case TOON:
                ((GPUImageToonFilter) filter).setThreshold(intensity * 0.5f + 0.1f);
                break;
            case EMBOSS:
                ((GPUImageEmbossFilter) filter).setIntensity(intensity * 4f);
                break;
            case SWIRL:
                ((GPUImageSwirlFilter) filter).setAngle(intensity * 2f);
                break;
            case CROSSHATCH:
                ((GPUImageCrosshatchFilter) filter).setCrossHatchSpacing(0.01f + intensity * 0.05f);
                break;
            case HALFTONE:
                ((GPUImageHalftoneFilter) filter).setFractionalWidthOfAPixel(intensity * 0.05f + 0.002f);
                break;
            case GLASS_SPHERE:
                ((GPUImageGlassSphereFilter) filter).setRadius(intensity * 0.5f + 0.1f);
                break;
            case SPHERE_REFRACTION:
                ((GPUImageSphereRefractionFilter) filter).setRadius(intensity * 0.5f + 0.1f);
                break;
            case SOBEL_EDGE:
                ((GPUImageSobelEdgeDetectionFilter) filter).setLineSize(intensity * 3f + 1f);
                break;
            case LEVELS:
                ((GPUImageLevelsFilter) filter).setMin(0f, intensity * 0.5f, 1f);
                break;
            case COLOR_BALANCE:
                ((GPUImageColorBalanceFilter) filter).setMidtones(
                        new float[]{intensity * 0.5f, 0f, intensity * -0.5f});
                break;
            case RGB:
                ((GPUImageRGBFilter) filter).setGreen(intensity * 2f);
                ((GPUImageRGBFilter) filter).setBlue(intensity * 2f);
                break;
            case MONOCHROME:
                ((GPUImageMonochromeFilter) filter).setIntensity(intensity);
                break;
            case VIBRANCE:
                ((GPUImageVibranceFilter) filter).setVibrance(intensity * 3f - 1.5f);
                break;
            case OPACITY:
                ((GPUImageOpacityFilter) filter).setOpacity(intensity);
                break;
            default:
                break;
        }
    }

    public static void updateBeautyParams(GPUImageFilter filter, BeautyParams beautyParams) {
        if (filter instanceof GPUImageBeautyFilter) {
            ((GPUImageBeautyFilter) filter).setBeautyParams(beautyParams);
        }
    }
}

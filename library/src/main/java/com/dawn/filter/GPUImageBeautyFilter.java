package com.dawn.filter;

import android.opengl.GLES20;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;

/**
 * 单 pass 实时美颜滤镜。
 * 将磨皮、美白、红润、亮度和对比度合并到同一着色器中，降低实时预览开销。
 */
public class GPUImageBeautyFilter extends GPUImageFilter {

    private static final String BEAUTY_FRAGMENT_SHADER =
            "precision highp float;\n" +
            "varying highp vec2 textureCoordinate;\n" +
            "uniform sampler2D inputImageTexture;\n" +
            "uniform highp vec2 singleStepOffset;\n" +
            "uniform highp float smoothness;\n" +
            "uniform highp float whiten;\n" +
            "uniform highp float rosy;\n" +
            "uniform highp float brighten;\n" +
            "uniform highp float contrast;\n" +
            "uniform highp float gammaParam;\n" +
            "uniform highp float satParam;\n" +
            "\n" +
            "float getSkinMask(vec3 color) {\n" +
            "    float maxColor = max(max(color.r, color.g), color.b);\n" +
            "    float minColor = min(min(color.r, color.g), color.b);\n" +
            "    float rgbMask = step(0.3725, color.r)\n" +
            "        * step(0.1568, color.g)\n" +
            "        * step(0.0784, color.b)\n" +
            "        * step(0.0588, maxColor - minColor)\n" +
            "        * step(0.0588, abs(color.r - color.g))\n" +
            "        * step(color.g, color.r)\n" +
            "        * step(color.b, color.r);\n" +
            "    float cb = 0.5 + dot(color, vec3(-0.168736, -0.331264, 0.5));\n" +
            "    float cr = 0.5 + dot(color, vec3(0.5, -0.418688, -0.081312));\n" +
            "    float cbMask = smoothstep(0.24, 0.34, cb) * (1.0 - smoothstep(0.40, 0.50, cb));\n" +
            "    float crMask = smoothstep(0.48, 0.56, cr) * (1.0 - smoothstep(0.72, 0.82, cr));\n" +
            "    return clamp(max(rgbMask, cbMask * crMask), 0.0, 1.0);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec4 source = texture2D(inputImageTexture, textureCoordinate);\n" +
            "    vec2 stepOffset = singleStepOffset;\n" +
            "    vec3 blur = source.rgb * 4.0;\n" +
            "    blur += texture2D(inputImageTexture, textureCoordinate + vec2(stepOffset.x, 0.0)).rgb * 2.0;\n" +
            "    blur += texture2D(inputImageTexture, textureCoordinate - vec2(stepOffset.x, 0.0)).rgb * 2.0;\n" +
            "    blur += texture2D(inputImageTexture, textureCoordinate + vec2(0.0, stepOffset.y)).rgb * 2.0;\n" +
            "    blur += texture2D(inputImageTexture, textureCoordinate - vec2(0.0, stepOffset.y)).rgb * 2.0;\n" +
            "    blur += texture2D(inputImageTexture, textureCoordinate + stepOffset).rgb;\n" +
            "    blur += texture2D(inputImageTexture, textureCoordinate - stepOffset).rgb;\n" +
            "    blur += texture2D(inputImageTexture, textureCoordinate + vec2(stepOffset.x, -stepOffset.y)).rgb;\n" +
            "    blur += texture2D(inputImageTexture, textureCoordinate + vec2(-stepOffset.x, stepOffset.y)).rgb;\n" +
            "    blur /= 16.0;\n" +
            "\n" +
            "    float skinMask = getSkinMask(source.rgb);\n" +
            "    float edgeMask = clamp(length(source.rgb - blur) * 4.5, 0.0, 1.0);\n" +
            "    float smoothAlpha = min(1.0, smoothness * 1.35) * skinMask * (1.0 - edgeMask);\n" +
            "    vec3 detail = source.rgb - blur;\n" +
            "    vec3 smoothColor = mix(source.rgb, blur, smoothAlpha);\n" +
            "    smoothColor += detail * (0.12 + (1.0 - smoothness) * 0.10);\n" +
            "\n" +
            "    vec3 whitenColor = mix(smoothColor, sqrt(clamp(smoothColor, 0.0, 1.0)), whiten * 0.55);\n" +
            "    whitenColor += vec3(0.04, 0.01, 0.02) * rosy * skinMask;\n" +
            "    whitenColor += vec3(1.0) * (brighten * 0.18);\n" +
            "\n" +
            "    float contrastValue = 0.85 + contrast * 0.55;\n" +
            "    vec3 finalColor = (whitenColor - 0.5) * contrastValue + 0.5;\n" +
            "\n" +
            "    float g = 1.5 - gammaParam;\n" +
            "    finalColor = pow(clamp(finalColor, 0.001, 1.0), vec3(g));\n" +
            "\n" +
            "    float sat = satParam * 2.0;\n" +
            "    vec3 gray = vec3(dot(finalColor, vec3(0.2126, 0.7152, 0.0722)));\n" +
            "    finalColor = mix(gray, finalColor, sat);\n" +
            "\n" +
            "    gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), source.a);\n" +
            "}\n";

    private int singleStepOffsetLocation;
    private int smoothnessLocation;
    private int whitenLocation;
    private int rosyLocation;
    private int brightenLocation;
    private int contrastLocation;
    private int gammaParamLocation;
    private int satParamLocation;

    private BeautyParams beautyParams = BeautyParams.defaultCamera();
    private float texelStepMultiplier = 2.2f;

    public GPUImageBeautyFilter() {
        this(BeautyParams.defaultCamera());
    }

    public GPUImageBeautyFilter(BeautyParams beautyParams) {
        super(NO_FILTER_VERTEX_SHADER, BEAUTY_FRAGMENT_SHADER);
        setBeautyParams(beautyParams);
    }

    @Override
    public void onInit() {
        super.onInit();
        singleStepOffsetLocation = GLES20.glGetUniformLocation(getProgram(), "singleStepOffset");
        smoothnessLocation = GLES20.glGetUniformLocation(getProgram(), "smoothness");
        whitenLocation = GLES20.glGetUniformLocation(getProgram(), "whiten");
        rosyLocation = GLES20.glGetUniformLocation(getProgram(), "rosy");
        brightenLocation = GLES20.glGetUniformLocation(getProgram(), "brighten");
        contrastLocation = GLES20.glGetUniformLocation(getProgram(), "contrast");
        gammaParamLocation = GLES20.glGetUniformLocation(getProgram(), "gammaParam");
        satParamLocation = GLES20.glGetUniformLocation(getProgram(), "satParam");
    }

    @Override
    public void onInitialized() {
        super.onInitialized();
        updateTexelOffset();
        pushBeautyParams();
    }

    @Override
    public void onOutputSizeChanged(int width, int height) {
        super.onOutputSizeChanged(width, height);
        updateTexelOffset();
    }

    public void setBeautyParams(BeautyParams beautyParams) {
        this.beautyParams = beautyParams == null ? BeautyParams.defaultCamera() : beautyParams.copy();
        pushBeautyParams();
    }

    public BeautyParams getBeautyParams() {
        return beautyParams.copy();
    }

    public void setSmoothness(float smoothness) {
        beautyParams.setSmoothness(smoothness);
        setFloat(smoothnessLocation, beautyParams.getSmoothness());
    }

    public void setWhiten(float whiten) {
        beautyParams.setWhiten(whiten);
        setFloat(whitenLocation, beautyParams.getWhiten());
    }

    public void setRosy(float rosy) {
        beautyParams.setRosy(rosy);
        setFloat(rosyLocation, beautyParams.getRosy());
    }

    public void setBrightness(float brightness) {
        beautyParams.setBrightness(brightness);
        setFloat(brightenLocation, beautyParams.getBrightness());
    }

    public void setContrast(float contrast) {
        beautyParams.setContrast(contrast);
        setFloat(contrastLocation, beautyParams.getContrast());
    }

    public void setGamma(float gamma) {
        beautyParams.setGamma(gamma);
        setFloat(gammaParamLocation, beautyParams.getGamma());
    }

    public void setSaturation(float saturation) {
        beautyParams.setSaturation(saturation);
        setFloat(satParamLocation, beautyParams.getSaturation());
    }

    private void pushBeautyParams() {
        setFloat(smoothnessLocation, beautyParams.getSmoothness());
        setFloat(whitenLocation, beautyParams.getWhiten());
        setFloat(rosyLocation, beautyParams.getRosy());
        setFloat(brightenLocation, beautyParams.getBrightness());
        setFloat(contrastLocation, beautyParams.getContrast());
        setFloat(gammaParamLocation, beautyParams.getGamma());
        setFloat(satParamLocation, beautyParams.getSaturation());
    }

    private void updateTexelOffset() {
        if (getOutputWidth() <= 0 || getOutputHeight() <= 0) {
            return;
        }
        setFloatVec2(singleStepOffsetLocation, new float[]{
                texelStepMultiplier / getOutputWidth(),
                texelStepMultiplier / getOutputHeight()
        });
    }
}
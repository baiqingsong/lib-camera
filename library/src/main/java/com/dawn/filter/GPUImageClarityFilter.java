package com.dawn.filter;

import android.opengl.GLES20;
import android.util.Log;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;

/**
 * 清晰度（锐化）滤镜 — 基于 Unsharp Mask（反锐化掩模）的边缘细节增强。
 * <p>
 * 原理：先对图像做两次不同半径的近似高斯模糊，取出高频细节（小半径）与
 * 中频局部对比（大半径），再按强度叠加回原图。双尺度能同时锐化边缘、
 * 恢复被模糊抹掉的中频细节，比单尺度反锐化掩模效果更明显。
 * 这是纯 GLSL 单 pass 实现，无模型、无额外依赖，实时可调。
 * <p>
 * 强度语义：{@code intensity = 0} 时等于原图（pass-through），
 * {@code intensity = 1} 时最强，推荐 0.4~0.8。
 */
public class GPUImageClarityFilter extends GPUImageFilter {

    private static final String TAG = "ClarityFilter";

    private static final String CLARITY_FRAGMENT_SHADER =
            "precision highp float;\n" +
            "varying highp vec2 textureCoordinate;\n" +
            "uniform sampler2D inputImageTexture;\n" +
            "uniform highp vec2 singleStepOffset;\n" +
            "uniform highp vec2 largeStepOffset;\n" +
            "uniform highp float intensity;\n" +
            "\n" +
            // 9-tap 近似高斯模糊（中心 4，上下左右 2，对角 1）
            "vec3 blur9(vec2 off) {\n" +
            "    vec3 c = texture2D(inputImageTexture, textureCoordinate).rgb;\n" +
            "    vec3 b = c * 4.0;\n" +
            "    b += texture2D(inputImageTexture, textureCoordinate + vec2(off.x, 0.0)).rgb * 2.0;\n" +
            "    b += texture2D(inputImageTexture, textureCoordinate - vec2(off.x, 0.0)).rgb * 2.0;\n" +
            "    b += texture2D(inputImageTexture, textureCoordinate + vec2(0.0, off.y)).rgb * 2.0;\n" +
            "    b += texture2D(inputImageTexture, textureCoordinate - vec2(0.0, off.y)).rgb * 2.0;\n" +
            "    b += texture2D(inputImageTexture, textureCoordinate + off).rgb;\n" +
            "    b += texture2D(inputImageTexture, textureCoordinate - off).rgb;\n" +
            "    b += texture2D(inputImageTexture, textureCoordinate + vec2(off.x, -off.y)).rgb;\n" +
            "    b += texture2D(inputImageTexture, textureCoordinate + vec2(-off.x, off.y)).rgb;\n" +
            "    return b / 16.0;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec4 color = texture2D(inputImageTexture, textureCoordinate);\n" +
            // 双尺度反锐化掩模：小半径取细节，大半径取低频
            "    vec3 blurSmall = blur9(singleStepOffset);\n" +
            "    vec3 blurLarge = blur9(largeStepOffset);\n" +
            "    vec3 detail = color.rgb - blurSmall;\n" +
            "    vec3 local  = blurSmall - blurLarge;\n" +
            "\n" +
            // edge 强: 锁化边缘、中频质感; edge 弱: 平坦区去除 JPG 块状噪点
            "    float edgeStrength = smoothstep(0.012, 0.07, length(detail));\n" +
            // 平坦区轻度向 blurSmall 靠拢（去除小块噪），边缘区保持原图
            "    vec3 base = mix(blurSmall, color.rgb, 0.25 + 0.75 * edgeStrength);\n" +
            // 锐化 + Lightroom 风格 clarity(中频局部对比)
            "    vec3 outColor = base\n" +
            "        + local  * (intensity * 1.8) * (0.4 + 0.6 * edgeStrength)\n" +
            "        + detail * (intensity * 2.2) * edgeStrength;\n" +
            "    gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), color.a);\n" +
            "}\n";

    private int singleStepOffsetLocation = -1;
    private int largeStepOffsetLocation = -1;
    private int intensityLocation = -1;
    private float intensity = 0.5f;
    private float smallRadius = 2.5f;
    private float largeRadius = 12.0f;
    /** >0 means use explicit image dimensions instead of view dimensions */
    private int imageWidth = 0;
    private int imageHeight = 0;

    public GPUImageClarityFilter() {
        this(0.5f);
    }

    public GPUImageClarityFilter(float intensity) {
        super(NO_FILTER_VERTEX_SHADER, CLARITY_FRAGMENT_SHADER);
        setIntensity(intensity);
    }

    @Override
    public void onInit() {
        super.onInit();
        singleStepOffsetLocation = GLES20.glGetUniformLocation(getProgram(), "singleStepOffset");
        largeStepOffsetLocation = GLES20.glGetUniformLocation(getProgram(), "largeStepOffset");
        intensityLocation = GLES20.glGetUniformLocation(getProgram(), "intensity");
        pushParams();
    }

    @Override
    public void onOutputSizeChanged(int width, int height) {
        super.onOutputSizeChanged(width, height);
        updateStepOffsets();
        int effW = imageWidth > 0 ? imageWidth : width;
        int effH = imageHeight > 0 ? imageHeight : height;
        Log.i(TAG, "onOutputSizeChanged view=" + width + "x" + height
                + " eff=" + effW + "x" + effH
                + " smallOff=" + (smallRadius / (float) effW));
    }

    /** Call after loading a static image so blur radii are measured in image pixels, not view pixels. */
    public void setImageSize(int width, int height) {
        imageWidth = width;
        imageHeight = height;
        updateStepOffsets();
    }

    /** 设置锐化强度 0~1。0 = 原图（pass-through），越大越锐利，推荐 0.3~0.7。 */
    public void setIntensity(float intensity) {
        float clamped = Math.max(0f, Math.min(1f, intensity));
        if (this.intensity == clamped) return;
        this.intensity = clamped;
        Log.i(TAG, "setIntensity=" + this.intensity + " location=" + intensityLocation);
        if (intensityLocation >= 0) {
            setFloat(intensityLocation, this.intensity);
        }
    }

    /**
     * 设置清晰度半径（像素）。smallRadius 控制细节锐化，largeRadius 控制局部对比（质感）。
     */
    public void setRadius(float smallRadius, float largeRadius) {
        this.smallRadius = Math.max(0.5f, Math.min(8f, smallRadius));
        this.largeRadius = Math.max(smallRadius, Math.min(16f, largeRadius));
        updateStepOffsets();
    }

    private void pushParams() {
        if (intensityLocation < 0) return;
        setFloat(intensityLocation, intensity);
    }

    private void updateStepOffsets() {
        if (singleStepOffsetLocation < 0) return;
        int w = imageWidth > 0 ? imageWidth : getOutputWidth();
        int h = imageHeight > 0 ? imageHeight : getOutputHeight();
        if (w <= 0 || h <= 0) return;
        setFloatVec2(singleStepOffsetLocation, new float[]{smallRadius / w, smallRadius / h});
        setFloatVec2(largeStepOffsetLocation,  new float[]{largeRadius  / w, largeRadius  / h});
    }
}

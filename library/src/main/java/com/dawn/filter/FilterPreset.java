package com.dawn.filter;

import android.graphics.Bitmap;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageLookupFilter;

/**
 * 滤镜预设 — 基于 LUT（颜色查找表）的单通道调色方案。
 * <p>
 * 核心原理：将所有颜色变换（色温、对比度、饱和度、分离色调等）预先烘焙到一张
 * 512×512 LUT 纹理中，GPU 仅需一次纹理查找即完成全部调色。
 * 这是 Instagram / VSCO / 抖音等主流 App 的标准方案，效果远优于多滤镜链叠加。
 */
public class FilterPreset implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String name;
    private final String description;
    private final LutGenerator.ColorParams colorParams;

    /** 缓存生成的 LUT，避免重复计算 */
    private transient Bitmap cachedLut;

    public FilterPreset(String name, String description, LutGenerator.ColorParams colorParams) {
        this.name = name;
        this.description = description;
        this.colorParams = colorParams;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public LutGenerator.ColorParams getColorParams() {
        return colorParams;
    }

    /**
     * 创建该预设的 GPU 滤镜（单通道 LUT 查找）。
     * 可多次调用，LUT 位图会缓存。
     */
    public GPUImageFilter createFilter() {
        return createFilter(1.0f);
    }

    public GPUImageFilter createFilter(float intensity) {
        return createLookupFilter(intensity);
    }

    public GPUImageLookupFilter createLookupFilter(float intensity) {
        if (cachedLut == null) {
            cachedLut = LutGenerator.generateLut(colorParams);
        }
                GPUImageLookupFilter filter = new GPUImageLookupFilter(Math.max(0f, Math.min(1f, intensity)));
        filter.setBitmap(cachedLut);
        return filter;
    }

    // ========================= 预设定义 =========================

    /** 复古：微暖 + 轻褪色 + 暗部偏暖 */
    public static final FilterPreset VINTAGE = new FilterPreset(
            "复古", "经典复古胶片风格",
            new LutGenerator.ColorParams()
                    .temperature(0.06f).saturation(0.8f).contrast(1.05f)
                    .shadowsLift(0.04f).shadowTint(0.03f, 0.01f, -0.02f));

    /** 清新：冷青通透、低饱和，适合日常和探店自拍 */
    public static final FilterPreset FRESH = new FilterPreset(
            "清新", "冷青通透风格",
            new LutGenerator.ColorParams()
                    .brightness(0.012f).contrast(1.19f).saturation(0.85f)
                    .temperature(-0.075f).tint(-0.022f)
                    .gamma(1.02f).highlightsPull(0.015f)
                    .shadowTint(-0.005f, 0.008f, 0.012f)
                    .highlightTint(-0.008f, 0.014f, 0.020f));

    /** 冰蓝：高亮、低饱和、冷蓝青氛围，适合空间感和空气感画面 */
    public static final FilterPreset ICE_BLUE = new FilterPreset(
            "冰蓝", "冷淡冰蓝空气感",
            new LutGenerator.ColorParams()
                    .brightness(0.058f).contrast(1.10f).saturation(0.82f)
                    .temperature(-0.088f).tint(-0.018f)
                    .gamma(0.95f).shadowsLift(0.015f).highlightsPull(0.025f)
                    .shadowTint(-0.008f, 0.010f, 0.028f)
                    .highlightTint(-0.012f, 0.018f, 0.038f));

    /** 黑白：高对比 + 更立体的灰阶层次，适合复古氛围自拍 */
    public static final FilterPreset BLACK_WHITE = new FilterPreset(
            "黑白", "复古高级黑白",
            new LutGenerator.ColorParams()
                    .brightness(-0.015f).saturation(0f).contrast(1.42f).gamma(1.04f)
                    .shadowsLift(0f).highlightsPull(0.06f));

    /** 胶片：暖调 + 褪色 + 暗部偏暖绿 */
    public static final FilterPreset FILM = new FilterPreset(
            "胶片", "复古胶片色彩",
            new LutGenerator.ColorParams()
                    .temperature(0.05f).saturation(0.82f).contrast(1.08f)
                    .shadowsLift(0.06f).highlightsPull(0.03f)
                    .shadowTint(0.02f, 0.01f, 0f));

    /** 梦幻：明亮 + 低对比 + 柔和 */
    public static final FilterPreset DREAMY = new FilterPreset(
            "梦幻", "柔光梦幻效果",
            new LutGenerator.ColorParams()
                    .brightness(0.06f).contrast(0.88f).saturation(0.92f)
                    .gamma(0.92f));

    /** 冷色调：蓝调 + 轻降饱和 */
    public static final FilterPreset COOL = new FilterPreset(
            "冷色调", "冷淡蓝色风格",
            new LutGenerator.ColorParams()
                    .temperature(-0.10f).saturation(0.92f).contrast(1.05f));

    /** 暖色调：暖金 + 轻饱和 */
    public static final FilterPreset WARM = new FilterPreset(
            "暖色调", "温暖阳光风格",
            new LutGenerator.ColorParams()
                    .temperature(0.10f).saturation(1.08f).brightness(0.02f));

    /** LOMO：高对比 + 饱和 + 交叉冲印感 */
    public static final FilterPreset LOMO = new FilterPreset(
            "LOMO", "LOMO 风格",
            new LutGenerator.ColorParams()
                    .contrast(1.2f).saturation(1.15f).temperature(0.05f)
                    .shadowTint(0f, -0.02f, 0f).highlightTint(0.03f, 0f, 0f));

    /** 蜜桃：去黄提粉、轻提亮，适合自然粉嫩自拍 */
    public static final FilterPreset PEACH = new FilterPreset(
            "蜜桃", "粉嫩蜜桃色调",
            new LutGenerator.ColorParams()
                    .temperature(0.018f).tint(0.095f).brightness(0.040f)
                    .saturation(0.98f).contrast(1.10f).gamma(0.99f)
                    .shadowTint(0.012f, -0.006f, 0.012f)
                    .highlightTint(0.045f, -0.006f, 0.030f));

    /** 质感灰：冷青灰 + 更强对比，突出商务和氛围感质感 */
    public static final FilterPreset TEXTURED_GRAY = new FilterPreset(
            "质感灰", "高级灰色质感",
            new LutGenerator.ColorParams()
                    .saturation(0.55f).contrast(1.25f).temperature(-0.06f).tint(-0.018f)
                    .brightness(-0.015f).gamma(1.03f).highlightsPull(0.03f)
                    .shadowTint(-0.010f, 0.010f, 0.020f)
                    .highlightTint(-0.008f, 0.012f, 0.022f));

    /** 个性：更明确的反差和轻微分离色调，适合风格化画面 */
    public static final FilterPreset PERSONALITY = new FilterPreset(
            "个性", "风格化个性表达",
            new LutGenerator.ColorParams()
                    .contrast(1.30f).saturation(1.02f).temperature(-0.05f).tint(0.02f)
                    .brightness(-0.015f).gamma(1.01f).highlightsPull(0.02f)
                    .shadowTint(-0.010f, 0.012f, 0.032f)
                    .highlightTint(0.018f, 0.006f, 0.018f));

    /** 日系：明亮 + 低对比 + 轻淡色 + 提暗部 */
    public static final FilterPreset JAPANESE = new FilterPreset(
            "日系", "日系清透风格",
            new LutGenerator.ColorParams()
                    .brightness(0.06f).contrast(0.85f).saturation(0.88f)
                    .gamma(0.92f).temperature(-0.01f));

    /** 落日：深暖橙 + 饱和 + 暗部偏橙 */
    public static final FilterPreset SUNSET = new FilterPreset(
            "落日", "金色落日氛围",
            new LutGenerator.ColorParams()
                    .temperature(0.15f).saturation(1.12f).contrast(1.06f)
                    .shadowTint(0.04f, 0.01f, 0f));

    /** 港风：高对比 + 暖 + 暗部微提 */
    public static final FilterPreset HK_VINTAGE = new FilterPreset(
            "港风", "港式复古风格",
            new LutGenerator.ColorParams()
                    .contrast(1.2f).temperature(0.07f).saturation(1.05f)
                    .shadowsLift(0.02f));

    /** 森系：冷绿调 + 柔和 */
    public static final FilterPreset FOREST = new FilterPreset(
            "森系", "森林绿意风格",
            new LutGenerator.ColorParams()
                    .temperature(-0.03f).tint(-0.04f).saturation(1.05f)
                    .brightness(0.02f).contrast(0.95f));

    /** 薄荷：冷调 + 微亮 + 轻降饱和 */
    public static final FilterPreset MINT = new FilterPreset(
            "薄荷", "薄荷清凉色调",
            new LutGenerator.ColorParams()
                    .temperature(-0.08f).tint(-0.02f).brightness(0.03f)
                    .saturation(0.92f));

    /** 奶油：暖 + 低对比 + 柔 + 亮部偏暖黄 */
    public static final FilterPreset CREAM = new FilterPreset(
            "奶油", "柔和奶油色调",
            new LutGenerator.ColorParams()
                    .temperature(0.07f).contrast(0.88f).brightness(0.04f)
                    .saturation(0.92f).highlightTint(0.02f, 0.01f, 0f));

    /** 暗黑：压暗 + 高对比 + 低饱和 + 暗部偏蓝 */
    public static final FilterPreset DARK_MOODY = new FilterPreset(
            "暗黑", "暗黑情绪风格",
            new LutGenerator.ColorParams()
                    .brightness(-0.06f).contrast(1.25f).saturation(0.75f)
                    .shadowTint(0f, 0f, 0.02f));

    /** 清冷：冷蓝 + 轻降饱和 + 亮部偏蓝 */
    public static final FilterPreset ICY = new FilterPreset(
            "清冷", "冷淡高级风格",
            new LutGenerator.ColorParams()
                    .temperature(-0.12f).saturation(0.88f).contrast(1.08f)
                    .highlightTint(0f, 0f, 0.03f));

    /** 美白：提亮 + 柔和 + 轻提 Gamma */
    public static final FilterPreset BRIGHTEN = new FilterPreset(
            "美白", "自然美白提亮",
            new LutGenerator.ColorParams()
                    .brightness(0.08f).contrast(0.9f).saturation(0.97f)
                    .gamma(0.88f));

    /** 褪色：低对比 + 大幅提黑 + 降饱和 */
    public static final FilterPreset FADED = new FilterPreset(
            "褪色", "复古褪色效果",
            new LutGenerator.ColorParams()
                    .contrast(0.82f).saturation(0.75f).shadowsLift(0.1f));

    /** 糖果：高饱和 + 微亮 */
    public static final FilterPreset CANDY = new FilterPreset(
            "糖果", "缤纷糖果色",
            new LutGenerator.ColorParams()
                    .saturation(1.35f).brightness(0.03f).contrast(1.05f));

    /** 赛博朋克：高对比 + 冷蓝品红 + 分离色调 */
    public static final FilterPreset CYBERPUNK = new FilterPreset(
            "赛博朋克", "赛博未来风格",
            new LutGenerator.ColorParams()
                    .contrast(1.25f).temperature(-0.08f).tint(0.04f)
                    .saturation(1.15f)
                    .shadowTint(0f, 0f, 0.04f).highlightTint(0.03f, 0f, 0f));

    /** ins风：暖金 + 微对比 + 轻褪色 */
    public static final FilterPreset INSTAGRAM = new FilterPreset(
            "ins风", "Instagram 暖调风格",
            new LutGenerator.ColorParams()
                    .temperature(0.07f).contrast(1.06f).saturation(1.05f)
                    .brightness(0.02f).shadowsLift(0.02f));

    /** 紫调：冷紫品红 + 轻降饱和 + 暗部偏紫 */
    public static final FilterPreset PURPLE_HAZE = new FilterPreset(
            "紫调", "梦幻紫色氛围",
            new LutGenerator.ColorParams()
                    .tint(0.06f).temperature(-0.04f).saturation(0.9f)
                    .shadowTint(0.02f, 0f, 0.04f).highlightTint(0.01f, 0f, 0.02f));

    /** 橘子汽水：明亮暖橙 + 高饱和 */
    public static final FilterPreset ORANGE_SODA = new FilterPreset(
            "橘子汽水", "活力橙色风格",
            new LutGenerator.ColorParams()
                    .temperature(0.12f).saturation(1.2f).brightness(0.03f)
                    .contrast(1.05f).highlightTint(0.02f, 0.01f, -0.02f));

    /** 青橙：暗部偏青 + 亮部偏橙（电影调色经典手法）*/
    public static final FilterPreset TEAL_ORANGE = new FilterPreset(
            "青橙", "电影级青橙对比",
            new LutGenerator.ColorParams()
                    .contrast(1.12f).saturation(1.1f)
                    .shadowTint(-0.02f, 0.03f, 0.04f)
                    .highlightTint(0.04f, 0.01f, -0.02f));

    /** 冷白皮：自然冷白通透，轻微冷调 + 提亮 + 保持色彩鲜活 */
    public static final FilterPreset COOL_WHITE_SKIN = new FilterPreset(
            "冷白皮", "冷白通透美肌色调",
            new LutGenerator.ColorParams()
                    .brightness(0.045f).contrast(1.08f).saturation(0.95f)
                    .temperature(-0.018f).tint(0.012f)
                    .gamma(0.93f).highlightsPull(0.008f)
                    .shadowTint(0.002f, 0.000f, 0.005f)
                    .highlightTint(0.003f, 0.001f, 0.006f));

    /** 高级灰：明亮哑光 + 大幅降饱和 + 暗部提升 + 暖中性色调 */
    public static final FilterPreset ADVANCED_GRAY = new FilterPreset(
            "高级灰", "高级灰哑光质感",
            new LutGenerator.ColorParams()
                    .brightness(0.055f).contrast(0.95f).saturation(0.42f)
                    .temperature(0.012f).tint(0.003f)
                    .gamma(0.90f).shadowsLift(0.06f).highlightsPull(0.012f)
                    .shadowTint(0.005f, 0.002f, 0.000f)
                    .highlightTint(0.003f, 0.001f, 0.000f));

    /**
     * 获取所有预设列表。
     */
    public static List<FilterPreset> getAllPresets() {
        List<FilterPreset> presets = new ArrayList<>();
        presets.add(BLACK_WHITE);
        presets.add(FRESH);
        presets.add(ICE_BLUE);
        presets.add(PEACH);
        presets.add(TEXTURED_GRAY);
        presets.add(PERSONALITY);
        presets.add(COOL_WHITE_SKIN);
        presets.add(ADVANCED_GRAY);
        return presets;
    }

    /**
     * 释放缓存的 LUT 位图，回收内存。
     */
    public void clearLutCache() {
        if (cachedLut != null && !cachedLut.isRecycled()) {
            cachedLut.recycle();
        }
        cachedLut = null;
    }

    /**
     * 释放所有预设的 LUT 缓存。
     */
    public static void clearAllLutCaches() {
        for (FilterPreset preset : getAllPresets()) {
            preset.clearLutCache();
        }
        // 也清除未在列表中的预设
        VINTAGE.clearLutCache();
        FILM.clearLutCache();
        DREAMY.clearLutCache();
        COOL.clearLutCache();
        WARM.clearLutCache();
        LOMO.clearLutCache();
        JAPANESE.clearLutCache();
        SUNSET.clearLutCache();
        HK_VINTAGE.clearLutCache();
        FOREST.clearLutCache();
        MINT.clearLutCache();
        CREAM.clearLutCache();
        DARK_MOODY.clearLutCache();
        ICY.clearLutCache();
        BRIGHTEN.clearLutCache();
        FADED.clearLutCache();
        CANDY.clearLutCache();
        CYBERPUNK.clearLutCache();
        INSTAGRAM.clearLutCache();
        PURPLE_HAZE.clearLutCache();
        ORANGE_SODA.clearLutCache();
        TEAL_ORANGE.clearLutCache();
    }

    @Override
    public String toString() {
        return name + " - " + description;
    }
}

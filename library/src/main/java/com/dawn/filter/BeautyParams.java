package com.dawn.filter;

import java.io.Serializable;

/**
 * 实时美颜参数集合。
 * 所有参数范围均为 0.0 ~ 1.0。
 */
public class BeautyParams implements Serializable {

    private static final long serialVersionUID = 1L;

    private float smoothness;
    private float whiten;
    private float rosy;
    private float brightness;
    private float contrast;
    private float gamma;
    private float saturation;

    public BeautyParams() {
        this(0.40f, 0.20f, 0.18f, 0.12f, 0.18f, 0.5f, 0.5f);
    }

    public BeautyParams(float smoothness, float whiten, float rosy,
                        float brightness, float contrast) {
        this(smoothness, whiten, rosy, brightness, contrast, 0.5f, 0.5f);
    }

    public BeautyParams(float smoothness, float whiten, float rosy,
                        float brightness, float contrast,
                        float gamma, float saturation) {
        setSmoothness(smoothness);
        setWhiten(whiten);
        setRosy(rosy);
        setBrightness(brightness);
        setContrast(contrast);
        setGamma(gamma);
        setSaturation(saturation);
    }

    public static BeautyParams defaultCamera() {
        return new BeautyParams(0.40f, 0.18f, 0.16f, 0.10f, 0.16f, 0.5f, 0.5f);
    }

    public static BeautyParams fromIntensity(float intensity) {
        float level = clamp(intensity);
        return new BeautyParams(
                0.35f + level * 0.65f,
                0.08f + level * 0.28f,
                0.06f + level * 0.22f,
                0.03f + level * 0.18f,
                0.06f + level * 0.24f,
                0.5f, 0.5f
        );
    }

    public BeautyParams copy() {
        return new BeautyParams(smoothness, whiten, rosy, brightness, contrast, gamma, saturation);
    }

    public float getSmoothness() {
        return smoothness;
    }

    public void setSmoothness(float smoothness) {
        this.smoothness = clamp(smoothness);
    }

    public float getWhiten() {
        return whiten;
    }

    public void setWhiten(float whiten) {
        this.whiten = clamp(whiten);
    }

    public float getRosy() {
        return rosy;
    }

    public void setRosy(float rosy) {
        this.rosy = clamp(rosy);
    }

    public float getBrightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = clamp(brightness);
    }

    public float getContrast() {
        return contrast;
    }

    public void setContrast(float contrast) {
        this.contrast = clamp(contrast);
    }

    public float getGamma() {
        return gamma;
    }

    public void setGamma(float gamma) {
        this.gamma = clamp(gamma);
    }

    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        this.saturation = clamp(saturation);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
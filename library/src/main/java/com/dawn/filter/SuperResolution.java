package com.dawn.filter;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * AI 超分辨率引擎（基于 TensorFlow Lite）。
 * <p>
 * 加载一个超分模型（.tflite），对任意尺寸图片做分块（tile）推理，输出放大
 * {@code scale} 倍、细节更清晰的结果。模型约定：
 * <ul>
 *   <li>输入：NHWC float32，形状 [1, tileH, tileW, 3]，像素归一化到 [0,1]</li>
 *   <li>输出：NHWC float32，形状 [1, tileH*scale, tileW*scale, 3]，像素 [0,1]</li>
 * </ul>
 * 常见模型（Real-ESRGAN / EDSR 的 tflite 版）均符合该约定。
 * <p>
 * 使用示例：
 * <pre>
 *   SuperResolution sr = SuperResolution.load(context, "superres/esrgan_x2.tflite");
 *   Bitmap hd = sr.upscale(inputBitmap);   // 放大 2 倍
 *   sr.close();
 * </pre>
 */
public class SuperResolution implements AutoCloseable {

    private static final String TAG = "SuperResolution";

    private final Interpreter interpreter;
    private final int inputW;
    private final int inputH;
    private final int outputW;
    private final int outputH;
    private final int scale;

    /** 从 assets 加载模型。 */
    public static SuperResolution load(Context context, String assetPath) throws IOException {
        try (AssetFileDescriptor afd = context.getAssets().openFd(assetPath);
             FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            MappedByteBuffer model = fis.getChannel().map(
                    FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
            Interpreter interpreter = new Interpreter(model);
            return new SuperResolution(interpreter);
        }
    }

    private SuperResolution(Interpreter interpreter) {
        this.interpreter = interpreter;
        int[] in = interpreter.getInputTensor(0).shape();
        int[] out = interpreter.getOutputTensor(0).shape();
        if (in == null || out == null || in.length < 4 || out.length < 4) {
            throw new IllegalArgumentException("模型必须是 NHWC 四维输入输出，如 [1,H,W,3]");
        }
        this.inputH = in[1];
        this.inputW = in[2];
        this.outputH = out[1];
        this.outputW = out[2];
        this.scale = Math.max(1, Math.round((float) outputH / inputH));
        Log.i(TAG, "model loaded, input=" + inputW + "x" + inputH
                + " output=" + outputW + "x" + outputH + " scale=" + scale);
    }

    /** 放大倍率（由模型输入/输出尺寸推导）。 */
    public int getScale() {
        return scale;
    }

    /** 对任意尺寸 Bitmap 做分块超分，返回放大 scale 倍的结果。 */
    public Bitmap upscale(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int outW = srcW * scale;
        int outH = srcH * scale;

        int[] srcPixels = new int[srcW * srcH];
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH);
        int[] outPixels = new int[outW * outH];

        float[][][][] input = new float[1][inputH][inputW][3];
        float[][][][] output = new float[1][outputH][outputW][3];

        for (int y = 0; y < srcH; y += inputH) {
            for (int x = 0; x < srcW; x += inputW) {
                // 填充输入 tile（边缘 clamp，超出部分重复边缘像素）
                for (int j = 0; j < inputH; j++) {
                    int sy = Math.min(y + j, srcH - 1);
                    for (int i = 0; i < inputW; i++) {
                        int sx = Math.min(x + i, srcW - 1);
                        int p = srcPixels[sy * srcW + sx];
                        input[0][j][i][0] = ((p >> 16) & 0xFF) / 255f;
                        input[0][j][i][1] = ((p >> 8) & 0xFF) / 255f;
                        input[0][j][i][2] = (p & 0xFF) / 255f;
                    }
                }

                interpreter.run(input, output);

                // 写回（边缘 tile 只写有效区域）
                int ow = Math.min(outputW, outW - x * scale);
                int oh = Math.min(outputH, outH - y * scale);
                for (int j = 0; j < oh; j++) {
                    for (int i = 0; i < ow; i++) {
                        int r = clamp255(output[0][j][i][0]);
                        int g = clamp255(output[0][j][i][1]);
                        int b = clamp255(output[0][j][i][2]);
                        outPixels[(y * scale + j) * outW + (x * scale + i)] =
                                0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }
        }

        Bitmap result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        result.setPixels(outPixels, 0, outW, 0, 0, outW, outH);
        return result;
    }

    private static int clamp255(float v) {
        int i = Math.round(v * 255f);
        return Math.max(0, Math.min(255, i));
    }

    @Override
    public void close() {
        try {
            interpreter.close();
        } catch (Throwable ignored) {
        }
    }
}

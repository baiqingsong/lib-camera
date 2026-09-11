# 超分辨率模型放置说明

把超分模型（`.tflite`）放到本目录（`app/src/main/assets/superres/`）下，
并命名为 `esrgan_x2.tflite`（或修改 `CameraKit.DEFAULT_SR_MODEL_ASSET` 指向你的文件）。

## 模型要求

- 格式：TensorFlow Lite（`.tflite`）
- 输入：NHWC float32，`[1, H, W, 3]`，像素归一化到 `[0, 1]`，固定 H/W
- 输出：NHWC float32，`[1, H*scale, W*scale, 3]`，像素 `[0, 1]`

## 常见可用模型（自行下载并遵守各自许可）

- Real-ESRGAN 的 tflite 转换版（x2 / x4，去 JPEG 伪影 + 提清晰度）
- EDSR x2 / x4（轻量，适合移动端）

> 注意：预训练模型权重有其独立许可，请确认授权后使用。
> 模型文件通常较大（1~10MB+），不建议内置到库 AAR，建议由接入方放入自己 app 的 assets。

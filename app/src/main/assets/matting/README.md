# AI 抠图模型（RVM / Robust Video Matting）

本目录已放置 RVM 模型：`rvm_mobilenetv3_fp32.onnx`（约 15MB，ONNX 格式）。

- 来源：https://github.com/PeterL1n/RobustVideoMatting/releases
  （`rvm_mobilenetv3_fp32.onnx`）
- 推理引擎：ONNX Runtime Android（`com.microsoft.onnxruntime:onnxruntime-android`）

模型约定：
- 输入 `src`: [1,3,H,W] NCHW，RGB 归一化 [0,1]，H/W 需为 16 的倍数
- 输入 `r1i/r2i/r3i/r4i`: 循环状态（单帧推理置零）
- 输入 `downsample_ratio`: 标量，固定 0.25
- 输出 `fgr`: [1,3,H,W] 前景 RGB；`pha`: [1,1,H,W] alpha matte

> 与 ML Kit Selfie Segmenter（256×256 低分辨率 mask）不同，RVM 输出**源分辨率**的
> alpha matte，边缘发丝级，无放大锯齿。demo 的「抠图」按钮会自动优先使用 RVM；
> 若模型缺失则回退到 ML Kit。

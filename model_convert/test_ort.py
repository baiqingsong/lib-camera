import onnxruntime as ort
import numpy as np

sess = ort.InferenceSession('rvm_mobilenetv3_fp32.onnx', providers=['CPUExecutionProvider'])

for H, W in [(512, 512), (256, 256), (1920, 1080)]:
    try:
        inputs = {
            'src': np.zeros((1, 3, H, W), dtype=np.float32),
            'r1i': np.zeros((1, 16, H//2, W//2), dtype=np.float32),
            'r2i': np.zeros((1, 20, H//4, W//4), dtype=np.float32),
            'r3i': np.zeros((1, 40, H//8, W//8), dtype=np.float32),
            'r4i': np.zeros((1, 64, H//16, W//16), dtype=np.float32),
            'downsample_ratio': np.array([0.25], dtype=np.float32),
        }
        outputs = sess.run(None, inputs)
        print(f"{H}x{W}: OK, outputs={[o.shape for o in outputs]}")
    except Exception as e:
        print(f"{H}x{W}: FAILED -> {str(e)[:200]}")

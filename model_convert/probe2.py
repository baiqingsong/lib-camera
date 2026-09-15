import onnxruntime as ort
import numpy as np

sess = ort.InferenceSession('rvm_mobilenetv3_fp32.onnx', providers=['CPUExecutionProvider'])

# RVM 官方循环状态分辨率：H/4, H/8, H/16, H/32
for H, W in [(512, 512), (512, 288)]:
    try:
        inputs = {
            'src': np.random.rand(1, 3, H, W).astype(np.float32),
            'r1i': np.zeros((1, 16, H//4, W//4), dtype=np.float32),
            'r2i': np.zeros((1, 20, H//8, W//8), dtype=np.float32),
            'r3i': np.zeros((1, 40, H//16, W//16), dtype=np.float32),
            'r4i': np.zeros((1, 64, H//32, W//32), dtype=np.float32),
            'downsample_ratio': np.array([0.25], dtype=np.float32),
        }
        outputs = sess.run(None, inputs)
        print(f"{H}x{W}: OK")
        print(f"  fgr={outputs[0].shape} pha={outputs[1].shape}")
    except Exception as e:
        print(f"{H}x{W}: FAILED -> {str(e)[:150]}")

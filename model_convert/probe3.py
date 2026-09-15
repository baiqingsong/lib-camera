import onnxruntime as ort
import numpy as np

sess = ort.InferenceSession('rvm_mobilenetv3_fp32.onnx', providers=['CPUExecutionProvider'])

# downsample_ratio=0.25 时：r1=H/8, r2=H/16, r3=H/32, r4=H/64
for H, W in [(512, 512), (512, 288)]:
    try:
        inputs = {
            'src': np.random.rand(1, 3, H, W).astype(np.float32),
            'r1i': np.zeros((1, 16, H//8, W//8), dtype=np.float32),
            'r2i': np.zeros((1, 20, H//16, W//16), dtype=np.float32),
            'r3i': np.zeros((1, 40, H//32, W//32), dtype=np.float32),
            'r4i': np.zeros((1, 64, H//64, W//64), dtype=np.float32),
            'downsample_ratio': np.array([0.25], dtype=np.float32),
        }
        outputs = sess.run(None, inputs)
        print(f"{H}x{W}: OK")
        print(f"  fgr={outputs[0].shape} pha={outputs[1].shape}")
    except Exception as e:
        print(f"{H}x{W}: FAILED -> {str(e)[:150]}")

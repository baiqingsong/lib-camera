import onnxruntime as ort
import numpy as np

sess = ort.InferenceSession('rvm_mobilenetv3_fp32.onnx', providers=['CPUExecutionProvider'])

H, W = 512, 512
# r4i 理论上是 H/16, W/16 = 32x32；测试其他倍数看哪个能过 Expand_174
candidates = {
    'r4i=H/16 (32)': (1, 64, 32, 32),
    'r4i=H/8 (64)':  (1, 64, 64, 64),
    'r4i=H/4 (128)': (1, 64, 128, 128),
    'r4i=H/2 (256)': (1, 64, 256, 256),
}

for label, r4i_shape in candidates.items():
    try:
        inputs = {
            'src': np.zeros((1, 3, H, W), dtype=np.float32),
            'r1i': np.zeros((1, 16, H//2, W//2), dtype=np.float32),
            'r2i': np.zeros((1, 20, H//4, W//4), dtype=np.float32),
            'r3i': np.zeros((1, 40, H//8, W//8), dtype=np.float32),
            'r4i': np.zeros(r4i_shape, dtype=np.float32),
            'downsample_ratio': np.array([0.25], dtype=np.float32),
        }
        outputs = sess.run(None, inputs)
        print(f"{label}: OK, outputs={[o.shape for o in outputs]}")
    except Exception as e:
        msg = str(e)
        # 只看是哪个节点失败
        node = 'Expand_174'
        print(f"{label}: FAILED at {node if node in msg else 'other'}: {msg[:120]}")

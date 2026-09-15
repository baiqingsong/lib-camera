import onnxruntime as ort
import numpy as np

sess = ort.InferenceSession('rvm_mobilenetv3_fp32.onnx', providers=['CPUExecutionProvider'])

H, W = 512, 512
# downsample_ratio 决定 recurrent 分辨率：r_k = H * ratio / 2^k
for ratio in [0.25, 0.5, 1.0]:
    f = ratio
    try:
        inputs = {
            'src': np.random.rand(1, 3, H, W).astype(np.float32),
            'r1i': np.zeros((1, 16, int(H*f/2), int(W*f/2)), dtype=np.float32),
            'r2i': np.zeros((1, 20, int(H*f/4), int(W*f/4)), dtype=np.float32),
            'r3i': np.zeros((1, 40, int(H*f/8), int(W*f/8)), dtype=np.float32),
            'r4i': np.zeros((1, 64, int(H*f/16), int(W*f/16)), dtype=np.float32),
            'downsample_ratio': np.array([f], dtype=np.float32),
        }
        outputs = sess.run(None, inputs)
        print(f"downsample_ratio={f}: OK, r1={int(H*f/2)}, r4={int(H*f/16)}, pha={outputs[1].shape}")
    except Exception as e:
        print(f"downsample_ratio={f}: FAILED -> {str(e)[:120]}")

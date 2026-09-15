import tensorflow as tf

MODEL_DIR = 'rvm_tf/rvm_mobilenetv3_tf'
OUT = 'rvm_mobilenetv3_fp32.tflite'

print("loading saved model...")
converter = tf.lite.TFLiteConverter.from_saved_model(MODEL_DIR)

# 保持 float32 精度（不量化），发丝级边缘质量
converter.optimizations = []

# 旧版 SavedModel 的 op 需要 flex ops 支持
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS,
    tf.lite.OpsSet.SELECT_TF_OPS,
]

print("converting...")
tflite_model = converter.convert()

with open(OUT, 'wb') as f:
    f.write(tflite_model)
print("saved:", OUT, "size:", len(tflite_model))

# 打印输入输出签名，用于核对 Android 端张量顺序
interp = tf.lite.Interpreter(model_content=tflite_model)
interp.allocate_tensors()
inp = interp.get_input_details()
out = interp.get_output_details()
print("\nINPUTS:")
for d in inp:
    print(f"  idx={d['index']} name={d['name']} shape={d['shape']} dtype={d['dtype']}")
print("OUTPUTS:")
for d in out:
    print(f"  idx={d['index']} name={d['name']} shape={d['shape']} dtype={d['dtype']}")

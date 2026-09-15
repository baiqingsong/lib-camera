import tensorflow as tf

MODEL_DIR = 'rvm_tf/rvm_mobilenetv3_tf'
model = tf.saved_model.load(MODEL_DIR)
print("signatures:", list(model.signatures.keys()))
sig = model.signatures['serving_default']
print("\nstructured_input_signature:")
for k, v in sig.structured_input_signature:
    print(f"  {k}: {v}")
print("\nstructured_outputs:")
for k, v in sig.structured_outputs.items():
    print(f"  {k}: {v}")

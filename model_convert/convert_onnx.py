import onnx2tf

onnx2tf.convert(
    input_onnx_file_path='rvm_mobilenetv3_fp32.onnx',
    output_folder_path='rvm_tflite',
)
print("done")

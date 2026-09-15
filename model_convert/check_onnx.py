import onnx

m = onnx.load('rvm_mobilenetv3_fp32.onnx')
print("ir_version:", m.ir_version)
print("opset:", [op.version for op in m.opset_import])
print("\nINPUTS:")
for inp in m.graph.input:
    dims = [d.dim_value if d.dim_value else d.dim_param for d in inp.type.tensor_type.shape.dim]
    print(f"  name={inp.name} dims={dims} type={inp.type.tensor_type.elem_type}")
print("\nOUTPUTS:")
for out in m.graph.output:
    dims = [d.dim_value if d.dim_value else d.dim_param for d in out.type.tensor_type.shape.dim]
    print(f"  name={out.name} dims={dims} type={out.type.tensor_type.elem_type}")

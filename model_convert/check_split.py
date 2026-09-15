import onnx
from onnx import shape_inference

m = onnx.load('rvm_mobilenetv3_fp32.onnx')

# Split 节点属性
for node in m.graph.node:
    if node.op_type == 'Split':
        attrs = {a.name: onnx.helper.get_attribute_value(a) for a in node.attribute}
        print(f"Split {node.name}: inputs={list(node.input)} outputs={list(node.output)} attrs={attrs}")

# 形状推断：给 src 512x512，recurrent 状态动态
print("\n--- shape inference (512x512) ---")
inferred = shape_inference.infer_shapes(m)
for vi in inferred.graph.value_info:
    if vi.name in ('605', '606', '608', '601', '961'):
        dims = [d.dim_value if d.dim_value else d.dim_param for d in vi.type.tensor_type.shape.dim]
        print(f"  {vi.name}: {dims}")

# 也直接查 r4i 图输入的 shape
print("\n--- 图输入 r4i/r3i/r2i/r1i 定义 ---")
for inp in m.graph.input:
    if inp.name in ('r1i','r2i','r3i','r4i'):
        dims = [d.dim_value if d.dim_value else d.dim_param for d in inp.type.tensor_type.shape.dim]
        print(f"  {inp.name}: {dims}")

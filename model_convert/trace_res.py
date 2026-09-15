import onnx

m = onnx.load('rvm_mobilenetv3_fp32.onnx')

# 追踪 590 的分辨率来源（关注 Resize / stride>1 Conv / MaxPool / Upsample）
def trace_res(tensor_name, depth=0, seen=None):
    if seen is None:
        seen = set()
    if tensor_name in seen or depth > 12:
        return
    seen.add(tensor_name)
    for init in m.graph.initializer:
        if init.name == tensor_name:
            return
    for inp in m.graph.input:
        if inp.name == tensor_name:
            print(f"{'  '*depth}INPUT {tensor_name}")
            return
    for node in m.graph.node:
        if tensor_name in list(node.output):
            op = node.op_type
            attrs = {a.name: onnx.helper.get_attribute_value(a) for a in node.attribute} if node.attribute else {}
            if op in ('Resize', 'Upsample', 'MaxPool', 'AveragePool') or (op == 'Conv' and attrs.get('strides', [1,1]) != [1,1]):
                print(f"{'  '*depth}{node.name} op={op} attrs={attrs} inputs={list(node.input)}")
            elif op == 'Conv':
                pass
            for i in node.input:
                trace_res(i, depth+1, seen)
            return

print("=== 追踪 606 的分辨率链（关键尺寸变化节点）===")
trace_res('606')

print("\n=== 追踪 r4i 在图中的使用（除了 Expand）===")
for node in m.graph.node:
    if 'r4i' in list(node.input):
        print(f"{node.name} op={node.op_type} inputs={list(node.input)} outputs={list(node.output)}")

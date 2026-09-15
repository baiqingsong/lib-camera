import onnx

m = onnx.load('rvm_mobilenetv3_fp32.onnx')

# 追踪张量 606 的产生链（反向）
def trace(tensor_name, depth=0, seen=None):
    if seen is None:
        seen = set()
    if tensor_name in seen or depth > 8:
        return
    seen.add(tensor_name)
    # 初始化器？
    for init in m.graph.initializer:
        if init.name == tensor_name:
            print(f"{'  '*depth}init {tensor_name} dims={init.dims}")
            return
    # 图输入？
    for inp in m.graph.input:
        if inp.name == tensor_name:
            print(f"{'  '*depth}INPUT {tensor_name}")
            return
    # 产生它的节点
    for node in m.graph.node:
        if tensor_name in list(node.output):
            print(f"{'  '*depth}node {node.name} op={node.op_type} inputs={list(node.input)} -> {list(node.output)}")
            for i in node.input:
                trace(i, depth+1, seen)
            return

print("=== 追踪 606 ===")
trace('606')
print("\n=== 追踪 714 (r1i 的 Shape 源) ===")
trace('714')

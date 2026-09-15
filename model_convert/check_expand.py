import onnx

m = onnx.load('rvm_mobilenetv3_fp32.onnx')

# 打印所有 Expand 节点及其输入，找到 Expand_174 及其上下文
for node in m.graph.node:
    if node.op_type == 'Expand':
        print(f"=== {node.name} ===")
        print(f"  inputs: {list(node.input)}")
        print(f"  outputs: {list(node.output)}")
        for a in node.attribute:
            print(f"  attr: {a.name} = {onnx.helper.get_attribute_value(a)}")

# 查看 shape 相关的初始化器（Expand 的 target shape 通常是常量或来自 Shape op）
print("\n--- Expand_174 相关节点 ---")
for node in m.graph.node:
    if 'Expand_174' in node.name or 'Expand_174' in list(node.input) or 'Expand_174' in list(node.output):
        print(f"node {node.name} op={node.op_type} inputs={list(node.input)} outputs={list(node.output)}")

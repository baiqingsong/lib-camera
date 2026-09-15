import onnx
import numpy as np

m = onnx.load('rvm_mobilenetv3_fp32.onnx')

# 查找并打印 target shape 初始化器
targets = ['607', '643', '679', '715']
for init in m.graph.initializer:
    if init.name in targets:
        data = onnx.numpy_helper.to_array(init)
        print(f"initializer {init.name}: shape={init.dims} values={data.tolist()}")

# 也查找这些 shape 的来源节点（可能是 Shape/Gather/Concat 动态构造）
print("\n--- 产生这些 target shape 的节点 ---")
shape_names = set(targets)
for node in m.graph.node:
    outs = list(node.output)
    if any(o in shape_names for o in outs):
        print(f"node {node.name} op={node.op_type} inputs={list(node.input)} outputs={outs}")

# 查看 src 输入相关的 Shape 节点
print("\n--- 所有 Shape 节点 ---")
for node in m.graph.node:
    if node.op_type == 'Shape':
        print(f"node {node.name} op=Shape inputs={list(node.input)} outputs={list(node.output)}")

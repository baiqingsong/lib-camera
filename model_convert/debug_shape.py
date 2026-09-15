import onnx
from onnx import helper, TensorProto
import onnxruntime as ort
import numpy as np

m = onnx.load('rvm_mobilenetv3_fp32.onnx')

# 把 4 个 Expand 节点替换为 Identity，避免推理失败，并暴露 Shape 输出
expand_map = {'Expand_174': ('r4i', '607'), 'Expand_204': ('r3i', '643'),
              'Expand_234': ('r2i', '679'), 'Expand_264': ('r1i', '715')}
shape_outs = list(expand_map.values())  # 607, 643, 679, 715

new_nodes = []
for node in m.graph.node:
    if node.name in expand_map:
        inp, _ = expand_map[node.name]
        new_nodes.append(helper.make_node('Identity', [inp], list(node.output), name=node.name))
    else:
        new_nodes.append(node)

m.graph.ClearField('node')
m.graph.node.extend(new_nodes)

# 添加 shape 输出
for s in [v for _, v in expand_map.values()]:
    m.graph.output.append(helper.make_tensor_value_info(s, TensorProto.INT64, [None]))

m = onnx.shape_inference.infer_shapes(m)
onnx.save(m, 'rvm_debug.onnx')

sess = ort.InferenceSession('rvm_debug.onnx', providers=['CPUExecutionProvider'])
H, W = 512, 512
inputs = {
    'src': np.zeros((1, 3, H, W), dtype=np.float32),
    'r1i': np.zeros((1, 16, H//2, W//2), dtype=np.float32),
    'r2i': np.zeros((1, 20, H//4, W//4), dtype=np.float32),
    'r3i': np.zeros((1, 40, H//8, W//8), dtype=np.float32),
    'r4i': np.zeros((1, 64, H//16, W//16), dtype=np.float32),
    'downsample_ratio': np.array([0.25], dtype=np.float32),
}
outputs = sess.run(['fgr', 'pha', '607', '643', '679', '715'], inputs)
print("fgr:", outputs[0].shape, "pha:", outputs[1].shape)
print("Shape(606)=607:", outputs[2])
print("Shape(642)=643:", outputs[3])
print("Shape(678)=679:", outputs[4])
print("Shape(714)=715:", outputs[5])

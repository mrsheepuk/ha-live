#!/usr/bin/env python3
"""
Step 1: Create a static-shape ONNX model from the dynamic one.

The original melspectrogram.onnx has dynamic input shape ['batch_size', 'samples'].
This script creates a new model with static shape [1, 1280].

Usage:
    python3 01_create_static_onnx.py

Output:
    melspectrogram_static.onnx - ONNX model with static input shape [1, 1280]
"""

import onnx
from onnx import helper, TensorProto, shape_inference
import numpy as np
import os

# Configuration
INPUT_SAMPLES = 1280  # Python openwakeword uses 1280 as default
# For Kotlin code using 1152: change to 1152 and OUTPUT_TIME to 5
# Formula: output_time = floor((input_samples - 512) / 160) + 1
OUTPUT_TIME = 5  # floor((1280-512)/160)+1 = 5

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ASSETS_DIR = os.path.join(SCRIPT_DIR, "../app/app/src/main/assets")
INPUT_MODEL = os.path.join(ASSETS_DIR, "melspectrogram.onnx")
OUTPUT_MODEL = os.path.join(SCRIPT_DIR, "melspectrogram_static.onnx")


def main():
    print(f"Loading ONNX model from {INPUT_MODEL}...")
    model = onnx.load(INPUT_MODEL)

    print("Original inputs:")
    for inp in model.graph.input:
        shape = [d.dim_value if d.dim_value else d.dim_param for d in inp.type.tensor_type.shape.dim]
        print(f"  {inp.name}: {shape}")

    # Create new input with static shape
    new_input = helper.make_tensor_value_info(
        'input',
        TensorProto.FLOAT,
        [1, INPUT_SAMPLES]
    )

    # Replace the input
    model.graph.ClearField('input')
    model.graph.input.append(new_input)

    # Update output shape - computed based on input
    # For melspectrogram: output shape = [1, 1, floor((x-512)/160)+1, 32]
    new_output = helper.make_tensor_value_info(
        'output',
        TensorProto.FLOAT,
        [1, 1, OUTPUT_TIME, 32]
    )

    model.graph.ClearField('output')
    model.graph.output.append(new_output)

    # Run shape inference to propagate shapes through the graph
    print("\nRunning shape inference...")
    try:
        model = shape_inference.infer_shapes(model)
        print("Shape inference completed")
    except Exception as e:
        print(f"Shape inference warning: {e}")

    print("\nNew inputs:")
    for inp in model.graph.input:
        shape = [d.dim_value if d.dim_value else d.dim_param for d in inp.type.tensor_type.shape.dim]
        print(f"  {inp.name}: {shape}")

    print("\nNew outputs:")
    for out in model.graph.output:
        shape = [d.dim_value if d.dim_value else d.dim_param for d in out.type.tensor_type.shape.dim]
        print(f"  {out.name}: {shape}")

    # Save the modified model
    onnx.save(model, OUTPUT_MODEL)
    print(f"\nSaved static-shape ONNX model to {OUTPUT_MODEL}")

    # Verify with onnxruntime
    try:
        import onnxruntime as ort
        print("\nTesting with ONNX Runtime...")
        session = ort.InferenceSession(OUTPUT_MODEL)
        test_input = np.random.randn(1, INPUT_SAMPLES).astype(np.float32)
        result = session.run(None, {'input': test_input})
        print(f"Input shape: {test_input.shape}")
        print(f"Output shape: {result[0].shape}")
        print("Verification successful!")
    except ImportError:
        print("\nonnxruntime not installed, skipping verification")


if __name__ == "__main__":
    main()

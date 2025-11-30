#!/usr/bin/env python3
"""
Alternative conversion: Use TensorFlow directly to convert ONNX to TFLite.

This script uses a more manual approach that avoids the complex onnx2tf pipeline:
1. Load the ONNX model
2. Create a concrete TensorFlow function with fixed input shapes
3. Convert to TFLite

Prerequisites:
    pip install onnx onnxruntime tensorflow

Usage:
    python3 03_direct_tf_convert.py
"""

import os
import numpy as np

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
INPUT_ONNX = os.path.join(SCRIPT_DIR, "melspectrogram_static.onnx")
OUTPUT_TFLITE = os.path.join(SCRIPT_DIR, "melspectrogram_static.tflite")

# Input configuration - must match what was used in 01_create_static_onnx.py
INPUT_SAMPLES = 1280


def convert_via_saved_model():
    """Convert by creating a TF SavedModel wrapper around ONNX inference."""
    import tensorflow as tf
    import onnxruntime as ort

    print("Loading ONNX model...")
    ort_session = ort.InferenceSession(INPUT_ONNX)

    # Get input/output info
    input_name = ort_session.get_inputs()[0].name
    input_shape = ort_session.get_inputs()[0].shape
    output_name = ort_session.get_outputs()[0].name

    print(f"ONNX Input: {input_name}, shape: {input_shape}")
    print(f"ONNX Output: {output_name}")

    # Test ONNX model
    test_input = np.random.randn(1, INPUT_SAMPLES).astype(np.float32)
    onnx_output = ort_session.run(None, {input_name: test_input})[0]
    print(f"ONNX test output shape: {onnx_output.shape}")

    # Create TF function that wraps ONNX
    # Note: This approach creates a TF model that calls ONNX at inference time
    # For a true TFLite model, we need the actual TF ops

    print("\nNote: Direct ONNX→TFLite conversion requires onnx2tf or similar tools.")
    print("This script demonstrates the shape verification.")
    print("\nFor actual conversion, use setup_and_convert.sh with a clean virtual environment.")

    return False


def verify_existing_tflite():
    """If a TFLite file exists, verify it."""
    if not os.path.exists(OUTPUT_TFLITE):
        print(f"\nNo TFLite file at {OUTPUT_TFLITE}")
        return False

    import tensorflow as tf

    print(f"\nVerifying existing TFLite: {OUTPUT_TFLITE}")
    interpreter = tf.lite.Interpreter(model_path=OUTPUT_TFLITE)

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    print(f"Input shape: {input_details[0]['shape']}")
    print(f"Output shape: {output_details[0]['shape']}")

    # Check if input shape is static
    if -1 in input_details[0]['shape'] or 0 in input_details[0]['shape']:
        print("WARNING: Input shape is still dynamic!")
        return False

    # Try allocation
    try:
        interpreter.allocate_tensors()
        print("Tensor allocation successful!")

        # Test inference
        test_input = np.random.randn(*input_details[0]['shape']).astype(np.float32)
        interpreter.set_tensor(input_details[0]['index'], test_input)
        interpreter.invoke()
        output = interpreter.get_tensor(output_details[0]['index'])
        print(f"Test inference output shape: {output.shape}")
        return True
    except Exception as e:
        print(f"Allocation/inference failed: {e}")
        return False


def main():
    if not os.path.exists(INPUT_ONNX):
        print(f"Error: {INPUT_ONNX} not found!")
        print("Run 01_create_static_onnx.py first.")
        return

    # Check ONNX model
    convert_via_saved_model()

    # Verify any existing TFLite
    verify_existing_tflite()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Step 2: Convert the static-shape ONNX model to TFLite.

This script uses onnx2tf to convert the ONNX model to TensorFlow SavedModel
and then to TFLite format.

Prerequisites:
    pip install onnx onnx2tf tensorflow tf_keras psutil onnxruntime

Usage:
    python3 02_onnx_to_tflite.py

Output:
    melspectrogram_static.tflite - TFLite model with static input shape
"""

import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
INPUT_MODEL = os.path.join(SCRIPT_DIR, "melspectrogram_static.onnx")
OUTPUT_DIR = os.path.join(SCRIPT_DIR, "tflite_output")
OUTPUT_TFLITE = os.path.join(SCRIPT_DIR, "melspectrogram_static.tflite")


def convert_with_onnx2tf():
    """Try conversion using onnx2tf (recommended method)."""
    try:
        import onnx2tf
        print(f"Converting {INPUT_MODEL} using onnx2tf...")

        onnx2tf.convert(
            input_onnx_file_path=INPUT_MODEL,
            output_folder_path=OUTPUT_DIR,
            copy_onnx_input_output_names_to_tflite=True,
            non_verbose=False,
        )

        # Find the generated tflite file
        import glob
        tflite_files = glob.glob(os.path.join(OUTPUT_DIR, "*.tflite"))
        if tflite_files:
            import shutil
            shutil.copy(tflite_files[0], OUTPUT_TFLITE)
            print(f"\nTFLite model saved to {OUTPUT_TFLITE}")
            return True
        else:
            print("No TFLite file generated!")
            return False

    except ImportError as e:
        print(f"onnx2tf not available: {e}")
        return False
    except Exception as e:
        print(f"onnx2tf conversion failed: {e}")
        return False


def convert_with_tf_directly():
    """Alternative: Convert via TensorFlow SavedModel using onnx-tf."""
    try:
        import onnx
        from onnx_tf.backend import prepare
        import tensorflow as tf

        print(f"Converting {INPUT_MODEL} using onnx-tf...")

        # Load ONNX model
        onnx_model = onnx.load(INPUT_MODEL)

        # Convert to TensorFlow
        tf_rep = prepare(onnx_model)

        # Export to SavedModel
        saved_model_dir = os.path.join(SCRIPT_DIR, "saved_model")
        tf_rep.export_graph(saved_model_dir)
        print(f"Exported SavedModel to {saved_model_dir}")

        # Convert SavedModel to TFLite
        converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS  # Enable TF ops if needed
        ]
        tflite_model = converter.convert()

        # Save TFLite model
        with open(OUTPUT_TFLITE, 'wb') as f:
            f.write(tflite_model)

        print(f"\nTFLite model saved to {OUTPUT_TFLITE}")
        return True

    except ImportError as e:
        print(f"onnx-tf not available: {e}")
        return False
    except Exception as e:
        print(f"onnx-tf conversion failed: {e}")
        import traceback
        traceback.print_exc()
        return False


def verify_tflite():
    """Verify the converted TFLite model works."""
    try:
        import tensorflow as tf
        import numpy as np

        print(f"\nVerifying {OUTPUT_TFLITE}...")

        interpreter = tf.lite.Interpreter(model_path=OUTPUT_TFLITE)
        interpreter.allocate_tensors()

        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()

        print(f"Input details: {input_details}")
        print(f"Output details: {output_details}")

        # Test inference
        input_shape = input_details[0]['shape']
        test_input = np.random.randn(*input_shape).astype(np.float32)

        interpreter.set_tensor(input_details[0]['index'], test_input)
        interpreter.invoke()
        output = interpreter.get_tensor(output_details[0]['index'])

        print(f"\nTest inference:")
        print(f"  Input shape: {test_input.shape}")
        print(f"  Output shape: {output.shape}")
        print("Verification successful!")

        return True

    except Exception as e:
        print(f"Verification failed: {e}")
        import traceback
        traceback.print_exc()
        return False


def main():
    if not os.path.exists(INPUT_MODEL):
        print(f"Error: {INPUT_MODEL} not found!")
        print("Run 01_create_static_onnx.py first.")
        sys.exit(1)

    # Try onnx2tf first
    success = convert_with_onnx2tf()

    # Fall back to onnx-tf if onnx2tf fails
    if not success:
        print("\n--- Trying alternative conversion method ---\n")
        success = convert_with_tf_directly()

    if success and os.path.exists(OUTPUT_TFLITE):
        verify_tflite()
    else:
        print("\nConversion failed. Please check the error messages above.")
        print("\nTroubleshooting:")
        print("1. Ensure all dependencies are installed:")
        print("   pip install onnx onnx2tf tensorflow tf_keras psutil onnxruntime ai-edge-litert")
        print("2. Try running in a clean virtual environment")
        print("3. Check for version conflicts between packages")
        sys.exit(1)


if __name__ == "__main__":
    main()

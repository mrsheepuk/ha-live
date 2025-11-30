# TFLite Melspectrogram Model Conversion

## The Problem

The original `melspectrogram.tflite` model from openWakeWord has **dynamic input shapes** (`[batch_size, samples]`), which causes Android's TFLite Interpreter to crash during initialization:

```
java.lang.IllegalStateException: Internal error: Unexpected failure when preparing tensor allocations:
tensorflow/lite/util.cc BytesRequired number of elements overflowed.
Node number 3 (CONV_2D) failed to prepare.
```

### Root Cause

- **Android TFLite auto-allocates tensors** during `Interpreter` construction
- **Python TFLite allows resize-before-allocate**: `resize_tensor_input()` then `allocate_tensors()`
- With dynamic shapes like `[1, -1]`, CONV_2D computes invalid output dimensions, causing integer overflow

See:
- [TensorFlow Issue #57131](https://github.com/tensorflow/tensorflow/issues/57131)
- [openWakeWord Issue #223](https://github.com/dscripka/openWakeWord/issues/223)

## The Solution

Convert the ONNX model to TFLite with **static input shapes baked in**.

### Step 1: Create Static ONNX Model

```bash
python3 01_create_static_onnx.py
```

This modifies the ONNX model's input/output signatures from dynamic to static:
- Input: `['batch_size', 'samples']` → `[1, 1280]`
- Output: `['time', 1, 'Clipoutput_dim_2', 32]` → `[1, 1, 5, 32]`

### Step 2: Convert to TFLite

```bash
python3 02_onnx_to_tflite.py
```

This uses `onnx2tf` to convert the static ONNX model to TFLite.

### Step 3: Deploy

Copy the generated `melspectrogram_static.tflite` to:
```
app/app/src/main/assets/melspectrogram.tflite
```

## Configuration

The default configuration uses:
- **Input samples**: 1280 (Python openwakeword default)
- **Output time**: 5 (calculated as `floor((1280-512)/160)+1`)

If you want to use different input sizes (e.g., 1152 samples as in the Kotlin code), modify `01_create_static_onnx.py`:

```python
INPUT_SAMPLES = 1152  # For Kotlin's MEL_INPUT_COUNT
OUTPUT_TIME = 5       # floor((1152-512)/160)+1 = 5
```

### Formula
The melspectrogram model output shape is:
```
[1, 1, floor((input_samples - 512) / 160) + 1, 32]
```

| Input Samples | Output Time Dimension |
|---------------|----------------------|
| 1152          | 5                    |
| 1280          | 5                    |
| 1440          | 6                    |
| 1600          | 7                    |

## Troubleshooting

### Package Conflicts

If you encounter import errors or conflicts:

1. **Use a virtual environment**:
   ```bash
   python3 -m venv venv
   source venv/bin/activate
   pip install -r requirements.txt
   ```

2. **ai-edge-litert conflicts**: Try version 1.4.0:
   ```bash
   pip install ai-edge-litert==1.4.0
   ```

3. **onnx-tf "mapping" error**: onnx-tf may be incompatible with newer onnx versions. Use onnx2tf instead.

### Alternative: Use ONNX Runtime

If TFLite conversion continues to fail, the codebase already supports ONNX Runtime via `OnnxWakeWordModel.kt`. ONNX handles dynamic shapes gracefully.

## Files

- `01_create_static_onnx.py` - Creates static-shape ONNX model
- `02_onnx_to_tflite.py` - Converts ONNX to TFLite
- `requirements.txt` - Python dependencies
- `melspectrogram_static.onnx` - Output from step 1
- `melspectrogram_static.tflite` - Output from step 2

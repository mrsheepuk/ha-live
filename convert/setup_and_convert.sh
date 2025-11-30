#!/bin/bash
# Setup a clean virtual environment and convert the model

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Setting up virtual environment ==="
python3 -m venv venv
source venv/bin/activate

echo "=== Installing dependencies ==="
pip install --upgrade pip
pip install onnx==1.15.0 onnxruntime==1.16.3
pip install tensorflow==2.15.0
pip install tf-keras==2.15.0
pip install onnx2tf==1.22.3
pip install psutil flatbuffers
pip install onnx_graphsurgeon --index-url https://pypi.ngc.nvidia.com
pip install sng4onnx

echo "=== Step 1: Create static ONNX model ==="
python3 01_create_static_onnx.py

echo "=== Step 2: Convert to TFLite ==="
python3 02_onnx_to_tflite.py

echo "=== Done! ==="
echo "Output: melspectrogram_static.tflite"

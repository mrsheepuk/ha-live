# Wake Word Detection

## Overview
Foreground-only, on-device wake word detection using openwakeword ONNX models. Enables hands-free activation by saying the wake word while the app is in the foreground.

**Battery Optimizations:**
- Models loaded once per app session (lazy initialization)
- ONNX Runtime configured for power efficiency (sequential execution, single thread)
- Pre-allocated buffers eliminate memory churn in inference hot path
- Models kept in memory across listening sessions (only closed on destroy)

## Architecture

### Microphone Handoff Pattern
```
App Launch → ReadyToTalk
    ↓
WakeWordService owns mic (if enabled)
    ↓ (wake word detected)
Stop WakeWordService → startChat()
    ↓
GeminiService owns mic (ChatActive)
    ↓ (user stops chat)
Stop GeminiService → stopChat()
    ↓
WakeWordService owns mic (back to ReadyToTalk)
```

**Foreground-only:** `MainActivity.onPause()` → stops listening, `onResume()` → resumes listening

## Key Components

### Models (3 ONNX files in `app/src/main/assets/`)
- `melspectrogram.onnx` - Melspectrogram preprocessor (INPUT: audio samples)
- `embedding_model.onnx` - Embedding model (INPUT: mel outputs)
- `alexa.onnx` - Wake word classifier (INPUT: embeddings, OUTPUT: probability)

**Migration Note:** Previously used TensorFlow Lite, but migrated to ONNX Runtime due to shape compatibility issues in the TFLite melspectrogram model from openwakeword v0.5.1 release.

### Core Classes

**`services/wake/OwwModel.kt`**
- Three-stage ONNX Runtime inference pipeline
- Processes 1152 audio samples (72ms @ 16kHz)
- Accumulates outputs for streaming detection
- Constructor: `OwwModel(melFile: File, embFile: File, wakeFile: File)`
- Uses `ai.onnxruntime.OrtSession` for inference
- Battery optimizations:
  - `BASIC_OPT` graph optimization level
  - `SEQUENTIAL` execution mode
  - Single-threaded (`intraOpNumThreads = 1`)
  - Pre-allocated transformation buffer

**`services/WakeWordService.kt`**
- Manages AudioRecord (16kHz mono PCM)
- Runs inference on IO dispatcher, callback on Main
- Detection threshold: `0.5f` (configurable at line 37)
- Auto-stops after detection
- Battery optimization: Lazy model initialization (once per service lifetime)
- Models reused across multiple listening sessions
- `resetAccumulators()` clears state when starting new session

**`core/WakeWordConfig.kt`**
- SharedPreferences storage: "wake_word_prefs"
- Default: OFF (disabled)
- Persists across app restarts

**`core/AssetCopyUtil.kt`**
- Copies .onnx files from assets to filesDir on app launch
- Only copies if files don't already exist
- Models: melspectrogram.onnx, embedding_model.onnx, alexa.onnx

### ViewModel Integration

**MainViewModel.kt:**
- `wakeWordEnabled: StateFlow<Boolean>` - Exposed to UI
- `toggleWakeWord(enabled)` - User control
- `startWakeWordListening()` - Checks enabled state before starting
- Lifecycle: `onActivityResume()` / `onActivityPause()` called by MainActivity

### UI

**Toolbar switch** (top-right, ear emoji 👂):
- Enabled: `ReadyToTalk` state only
- Disabled: All other states
- Status text updates: "Listening for wake word..." vs "Ready to chat"

## Audio Processing

```kotlin
// 16kHz, mono, 16-bit PCM
SAMPLE_RATE = 16000
CHUNK_SIZE = 1152  // 72ms of audio

// Convert Short → Float [-1.0, 1.0]
floatBuffer[i] = audioBuffer[i] / 32768.0f

// Run inference
val probability = owwModel.processFrame(floatBuffer)

// Check threshold
if (probability > 0.5f) {
    onWakeWordDetected()
}
```

## Configuration

### Wake Word Toggle
- **Location:** Toolbar (top-right)
- **Storage:** SharedPreferences ("wake_word_prefs", "wake_word_enabled")
- **Default:** OFF
- **Access:** `WakeWordConfig.isEnabled(context)` / `setEnabled(context, enabled)`

### Detection Threshold
- **File:** `WakeWordService.kt:39`
- **Default:** `0.5f`
- **Tuning:** Increase (0.6-0.7) for fewer false positives, decrease (0.3-0.4) for better detection

## Model Replacement

To replace with trained models:
1. Place trained .onnx files in `app/src/main/assets/`
   - `melspectrogram.onnx` - From openwakeword release
   - `embedding_model.onnx` - From openwakeword release
   - `<wake_word>.onnx` - Your trained wake word model
2. Update `AssetCopyUtil.kt` if using different wake word model name
3. Delete existing files from device: `adb shell rm -rf /data/data/uk.co.mrsheep.halive/files/*.onnx`
4. Or uninstall/reinstall app to trigger AssetCopyUtil

**Model Sources:**
- Download from openwakeword v0.5.1 release: https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1
- Or convert from ONNX models in the Python package

## Lifecycle Flow

1. **App Launch:** `HAGeminiApp.onCreate()` → `AssetCopyUtil.copyAssetsToFilesDir()`
2. **Init:** `MainViewModel.init` → Load `WakeWordConfig.isEnabled()`
3. **Ready:** `checkConfiguration()` → `UiState.ReadyToTalk`
4. **Resume:** `MainActivity.onResume()` → `viewModel.onActivityResume()` → `startWakeWordListening()`
5. **Detection:** `WakeWordService` callback → `onChatButtonClicked()`
6. **Chat Start:** `startChat()` → `wakeWordService.stopListening()` (release mic)
7. **Chat End:** `stopChat()` → `startWakeWordListening()` (reacquire mic)
8. **Pause:** `MainActivity.onPause()` → `viewModel.onActivityPause()` → `wakeWordService.stopListening()`

## Dependencies

```kotlin
// build.gradle.kts
implementation(libs.onnx.runtime)  // Version 1.17.0

// libs.versions.toml
onnxRuntime = "1.17.0"
onnx-runtime = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxRuntime" }
```

## Troubleshooting

**Model loading errors:**
- Check logcat for "Failed to load ONNX model" errors
- Verify all three .onnx files exist in assets directory
- Check AssetCopyUtil logs: "Copied melspectrogram.onnx to..."

**Not detecting wake word:**
- Check logs: "Wake word disabled, skipping" → Toggle is OFF
- Check threshold: Lower value in `WakeWordService.kt:39`
- Verify models copied: Check logcat for "Copied *.onnx"
- Ensure ONNX Runtime is properly initialized

**False positives:**
- Increase threshold in `WakeWordService.kt:39`

**Permission errors:**
- MainActivity checks `RECORD_AUDIO` permission before calling lifecycle methods
- Check `onResume()` permission guard at `MainActivity.kt:77-82`

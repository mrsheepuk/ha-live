# Wake Word Detection

## Overview
Foreground-only, on-device wake word detection using openwakeword TFLite models. Enables hands-free activation by saying the wake word while the app is in the foreground.

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

### Models (3 TFLite files in `app/src/main/assets/`)
- `oww_mel.tflite` - Melspectrogram preprocessor (INPUT: audio samples)
- `oww_emb.tflite` - Embedding model (INPUT: mel outputs)
- `oww_wake.tflite` - Wake word classifier (INPUT: embeddings, OUTPUT: probability)

**Critical Issue Found:** The `oww_mel.tflite` from openwakeword v0.5.1 release has invalid input shape `[1, 1]` instead of `[1, 1152]`, causing "BytesRequired overflow" error. The ONNX version (`melspectrogram.onnx`) works correctly and needs conversion to TFLite with fixed input shape.

### Core Classes

**`services/wake/OwwModel.kt`**
- Three-stage TFLite inference pipeline
- Processes 1152 audio samples (72ms @ 16kHz)
- Accumulates outputs for streaming detection
- Constructor: `OwwModel(melBuffer: ByteBuffer, embBuffer: ByteBuffer, wakeBuffer: ByteBuffer)`

**`services/WakeWordService.kt`**
- Manages AudioRecord (16kHz mono PCM)
- Runs inference on IO dispatcher, callback on Main
- Detection threshold: `0.5f` (configurable at line 39)
- Auto-stops after detection

**`core/WakeWordConfig.kt`**
- SharedPreferences storage: "wake_word_prefs"
- Default: OFF (disabled)
- Persists across app restarts

**`core/AssetCopyUtil.kt`**
- Copies .tflite files from assets to filesDir on app launch
- Only copies if files don't already exist

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
1. Place trained .tflite files in `app/src/main/assets/`
2. Delete existing files from device: `adb shell rm -rf /data/data/uk.co.mrsheep.halive/files/oww_*.tflite`
3. Or uninstall/reinstall app to trigger AssetCopyUtil

**Known Issue:** Current `oww_mel.tflite` has invalid shape. Convert from ONNX:
```bash
# Download melspectrogram.onnx from openwakeword v0.5.1
# Use conversion script to fix input shape to [1, 1152]
# Replace oww_mel.tflite in assets/
```

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
implementation(libs.tensorflow.lite)  // Version 2.16.1

// libs.versions.toml
tensorflowLite = "2.16.1"
```

## Troubleshooting

**"BytesRequired overflow" error:**
- Caused by invalid `oww_mel.tflite` input shape
- Solution: Convert from ONNX with fixed shape or download corrected model

**Not detecting wake word:**
- Check logs: "Wake word disabled, skipping" → Toggle is OFF
- Check threshold: Lower value in `WakeWordService.kt:39`
- Verify models copied: Check logcat for "Copied oww_*.tflite"

**False positives:**
- Increase threshold in `WakeWordService.kt:39`

**Permission errors:**
- MainActivity checks `RECORD_AUDIO` permission before calling lifecycle methods
- Check `onResume()` permission guard at `MainActivity.kt:77-82`

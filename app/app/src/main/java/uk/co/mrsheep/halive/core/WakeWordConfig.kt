package uk.co.mrsheep.halive.core

import android.content.Context
import ai.onnxruntime.OrtSession

/**
 * Predefined performance modes for ONNX wake word detection.
 * Each mode balances detection latency, accuracy, and battery consumption.
 */
enum class PerformanceMode {
    /**
     * Battery Saver Mode
     * - Execution: Sequential (1 thread)
     * - Optimization: Basic
     * - Threshold: 0.6 (stricter detection)
     * - Use when battery life is critical
     */
    BATTERY_SAVER,

    /**
     * Balanced Mode (default)
     * - Execution: Sequential (1 thread)
     * - Optimization: Basic
     * - Threshold: 0.5 (moderate detection)
     * - Good balance between performance and latency
     */
    BALANCED,

    /**
     * Performance Mode
     * - Execution: Parallel (2 threads)
     * - Optimization: Extended
     * - Threshold: 0.45 (more sensitive detection)
     * - Use when device has available resources
     */
    PERFORMANCE;

    /**
     * Returns the thread count for this performance mode.
     */
    fun getThreadCount(): Int = when (this) {
        BATTERY_SAVER, BALANCED -> 1
        PERFORMANCE -> 2
    }

    /**
     * Returns the execution mode for this performance mode.
     */
    fun getExecutionMode(): ExecutionMode = when (this) {
        BATTERY_SAVER, BALANCED -> ExecutionMode.SEQUENTIAL
        PERFORMANCE -> ExecutionMode.PARALLEL
    }

    /**
     * Returns the optimization level for this performance mode.
     */
    fun getOptimizationLevel(): OptimizationLevel = when (this) {
        BATTERY_SAVER, BALANCED -> OptimizationLevel.BASIC_OPT
        PERFORMANCE -> OptimizationLevel.EXTENDED_OPT
    }

    /**
     * Returns the confidence threshold for this performance mode.
     */
    fun getThreshold(): Float = when (this) {
        BATTERY_SAVER -> 0.6f
        BALANCED -> 0.5f
        PERFORMANCE -> 0.45f
    }
}

/**
 * Execution mode for ONNX Runtime session.
 * Determines how ONNX models are executed (sequential vs parallel).
 */
enum class ExecutionMode {
    /**
     * Sequential execution - models run one operation at a time.
     * Lower latency, uses 1 thread.
     */
    SEQUENTIAL,

    /**
     * Parallel execution - models can use multiple threads.
     * May have higher latency but can utilize multiple cores.
     */
    PARALLEL;

    /**
     * Converts this ExecutionMode to OrtSession.SessionOptions.ExecutionMode.
     */
    fun toOrtExecutionMode(): OrtSession.SessionOptions.ExecutionMode = when (this) {
        SEQUENTIAL -> OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL
        PARALLEL -> OrtSession.SessionOptions.ExecutionMode.PARALLEL
    }
}

/**
 * Optimization level for ONNX Runtime.
 * Determines how aggressively the runtime optimizes the model.
 */
enum class OptimizationLevel {
    /**
     * No optimizations - execute model as-is.
     */
    NO_OPT,

    /**
     * Basic optimizations - constant folding, redundant node elimination.
     * Good balance between speed and safety.
     */
    BASIC_OPT,

    /**
     * Extended optimizations - includes shape inversion, layout transformation.
     * Higher speed at the cost of more startup time.
     */
    EXTENDED_OPT,

    /**
     * All optimizations enabled.
     * Maximum performance but longest setup time.
     */
    ALL_OPT;

    /**
     * Converts this OptimizationLevel to OrtSession.SessionOptions.OptLevel.
     */
    fun toOrtOptLevel(): OrtSession.SessionOptions.OptLevel = when (this) {
        NO_OPT -> OrtSession.SessionOptions.OptLevel.NO_OPT
        BASIC_OPT -> OrtSession.SessionOptions.OptLevel.BASIC_OPT
        EXTENDED_OPT -> OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
        ALL_OPT -> OrtSession.SessionOptions.OptLevel.ALL_OPT
    }
}

/**
 * Configuration data model for ONNX wake word detection.
 * Directly specifies detection parameters without preset modes.
 */
data class WakeWordSettings(
    /**
     * Whether wake word detection is enabled.
     */
    val enabled: Boolean = false,

    /**
     * Confidence threshold for wake word detection (range: 0.3-0.8).
     * Higher values = stricter detection, lower false positives.
     * Lower values = more sensitive detection, higher false positives.
     */
    val threshold: Float = 0.5f,

    /**
     * Number of threads to use for ONNX model execution.
     */
    val threadCount: Int = 1,

    /**
     * Execution mode for ONNX Runtime session.
     */
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,

    /**
     * Optimization level for ONNX Runtime.
     */
    val optimizationLevel: OptimizationLevel = OptimizationLevel.BASIC_OPT
)

object WakeWordConfig {
    private const val PREFS_NAME = "wake_word_prefs"
    private const val KEY_ENABLED = "wake_word_enabled"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_THREAD_COUNT = "thread_count"
    private const val KEY_EXECUTION_MODE = "execution_mode"
    private const val KEY_OPTIMIZATION_LEVEL = "optimization_level"
    private const val KEY_SELECTED_MODEL_ID = "selected_model_id"

    /**
     * Check if wake word detection is enabled (backward compatible).
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false) // Default OFF
    }

    /**
     * Set wake word detection enabled state (backward compatible).
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Load all wake word settings from SharedPreferences.
     * Returns defaults if no settings have been saved yet.
     */
    fun getSettings(context: Context): WakeWordSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val threshold = prefs.getFloat(KEY_THRESHOLD, 0.5f)
        val threadCount = prefs.getInt(KEY_THREAD_COUNT, 1)

        val executionModeStr = prefs.getString(KEY_EXECUTION_MODE, ExecutionMode.SEQUENTIAL.name)
        val executionMode = try {
            ExecutionMode.valueOf(executionModeStr!!)
        } catch (e: Exception) {
            ExecutionMode.SEQUENTIAL
        }

        val optimizationLevelStr = prefs.getString(KEY_OPTIMIZATION_LEVEL, OptimizationLevel.BASIC_OPT.name)
        val optimizationLevel = try {
            OptimizationLevel.valueOf(optimizationLevelStr!!)
        } catch (e: Exception) {
            OptimizationLevel.BASIC_OPT
        }

        return WakeWordSettings(
            enabled = enabled,
            threshold = threshold,
            threadCount = threadCount,
            executionMode = executionMode,
            optimizationLevel = optimizationLevel
        )
    }

    /**
     * Save all wake word settings to SharedPreferences.
     */
    fun saveSettings(context: Context, settings: WakeWordSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, settings.enabled)
            putFloat(KEY_THRESHOLD, settings.threshold)
            putInt(KEY_THREAD_COUNT, settings.threadCount)
            putString(KEY_EXECUTION_MODE, settings.executionMode.name)
            putString(KEY_OPTIMIZATION_LEVEL, settings.optimizationLevel.name)
        }.apply()
    }

    /**
     * Get the currently selected wake word model ID.
     * Defaults to "ok_computer" (the built-in model).
     */
    fun getSelectedModelId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_MODEL_ID, "ok_computer") ?: "ok_computer"
    }

    /**
     * Set the currently selected wake word model ID.
     */
    fun setSelectedModelId(context: Context, modelId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_MODEL_ID, modelId).apply()
    }
}

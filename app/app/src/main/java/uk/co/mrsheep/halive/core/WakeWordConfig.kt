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
 * Comprehensive configuration data model for ONNX wake word detection.
 *
 * Supports both preset performance modes and advanced manual configuration.
 * When useAdvancedSettings is true, manual settings override the preset mode.
 */
data class WakeWordSettings(
    /**
     * Whether wake word detection is enabled.
     */
    val enabled: Boolean = false,

    /**
     * Preset performance mode (used when useAdvancedSettings is false).
     */
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,

    /**
     * Confidence threshold for wake word detection (range: 0.3-0.8).
     * Higher values = stricter detection, lower false positives.
     * Lower values = more sensitive detection, higher false positives.
     */
    val threshold: Float = 0.5f,

    /**
     * Whether to use advanced manual settings instead of performance mode preset.
     */
    val useAdvancedSettings: Boolean = false,

    /**
     * Advanced setting: number of threads to use (nullable, overrides preset if set).
     */
    val advancedThreadCount: Int? = null,

    /**
     * Advanced setting: execution mode (nullable, overrides preset if set).
     */
    val advancedExecutionMode: ExecutionMode? = null,

    /**
     * Advanced setting: optimization level (nullable, overrides preset if set).
     */
    val advancedOptimizationLevel: OptimizationLevel? = null
) {
    /**
     * Returns the effective thread count.
     * Uses advanced setting if available, otherwise returns preset value.
     */
    fun getEffectiveThreadCount(): Int = if (useAdvancedSettings && advancedThreadCount != null) {
        advancedThreadCount
    } else {
        performanceMode.getThreadCount()
    }

    /**
     * Returns the effective execution mode.
     * Uses advanced setting if available, otherwise returns preset value.
     */
    fun getEffectiveExecutionMode(): ExecutionMode = if (useAdvancedSettings && advancedExecutionMode != null) {
        advancedExecutionMode
    } else {
        performanceMode.getExecutionMode()
    }

    /**
     * Returns the effective optimization level.
     * Uses advanced setting if available, otherwise returns preset value.
     */
    fun getEffectiveOptimizationLevel(): OptimizationLevel = if (useAdvancedSettings && advancedOptimizationLevel != null) {
        advancedOptimizationLevel
    } else {
        performanceMode.getOptimizationLevel()
    }

    /**
     * Returns the effective confidence threshold.
     * (Currently always returns the configured threshold - no preset override)
     */
    fun getEffectiveThreshold(): Float = threshold
}

object WakeWordConfig {
    private const val PREFS_NAME = "wake_word_prefs"
    private const val KEY_ENABLED = "wake_word_enabled"
    private const val KEY_PERFORMANCE_MODE = "performance_mode"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_USE_ADVANCED = "use_advanced_settings"
    private const val KEY_ADVANCED_THREAD_COUNT = "advanced_thread_count"
    private const val KEY_ADVANCED_EXECUTION_MODE = "advanced_execution_mode"
    private const val KEY_ADVANCED_OPTIMIZATION_LEVEL = "advanced_optimization_level"

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
        val performanceModeStr = prefs.getString(KEY_PERFORMANCE_MODE, PerformanceMode.BALANCED.name)
        val performanceMode = try {
            PerformanceMode.valueOf(performanceModeStr!!)
        } catch (e: Exception) {
            PerformanceMode.BALANCED
        }
        val threshold = prefs.getFloat(KEY_THRESHOLD, 0.5f)
        val useAdvanced = prefs.getBoolean(KEY_USE_ADVANCED, false)

        val advancedThreadCount = if (prefs.contains(KEY_ADVANCED_THREAD_COUNT)) {
            prefs.getInt(KEY_ADVANCED_THREAD_COUNT, -1).takeIf { it >= 0 }
        } else {
            null
        }

        val advancedExecutionModeStr = prefs.getString(KEY_ADVANCED_EXECUTION_MODE, null)
        val advancedExecutionMode = advancedExecutionModeStr?.let {
            try {
                ExecutionMode.valueOf(it)
            } catch (e: Exception) {
                null
            }
        }

        val advancedOptimizationLevelStr = prefs.getString(KEY_ADVANCED_OPTIMIZATION_LEVEL, null)
        val advancedOptimizationLevel = advancedOptimizationLevelStr?.let {
            try {
                OptimizationLevel.valueOf(it)
            } catch (e: Exception) {
                null
            }
        }

        return WakeWordSettings(
            enabled = enabled,
            performanceMode = performanceMode,
            threshold = threshold,
            useAdvancedSettings = useAdvanced,
            advancedThreadCount = advancedThreadCount,
            advancedExecutionMode = advancedExecutionMode,
            advancedOptimizationLevel = advancedOptimizationLevel
        )
    }

    /**
     * Save all wake word settings to SharedPreferences.
     */
    fun saveSettings(context: Context, settings: WakeWordSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, settings.enabled)
            putString(KEY_PERFORMANCE_MODE, settings.performanceMode.name)
            putFloat(KEY_THRESHOLD, settings.threshold)
            putBoolean(KEY_USE_ADVANCED, settings.useAdvancedSettings)

            if (settings.advancedThreadCount != null) {
                putInt(KEY_ADVANCED_THREAD_COUNT, settings.advancedThreadCount)
            } else {
                remove(KEY_ADVANCED_THREAD_COUNT)
            }

            if (settings.advancedExecutionMode != null) {
                putString(KEY_ADVANCED_EXECUTION_MODE, settings.advancedExecutionMode.name)
            } else {
                remove(KEY_ADVANCED_EXECUTION_MODE)
            }

            if (settings.advancedOptimizationLevel != null) {
                putString(KEY_ADVANCED_OPTIMIZATION_LEVEL, settings.advancedOptimizationLevel.name)
            } else {
                remove(KEY_ADVANCED_OPTIMIZATION_LEVEL)
            }
        }.apply()
    }
}

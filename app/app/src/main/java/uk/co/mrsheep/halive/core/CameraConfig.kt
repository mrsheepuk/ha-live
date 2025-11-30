package uk.co.mrsheep.halive.core

import android.content.Context

/**
 * Camera resolution options for video streaming.
 */
enum class CameraResolution(val maxDimension: Int, val displayName: String) {
    LOW(256, "256x256"),
    MEDIUM(512, "512x512"),
    HIGH(1024, "1024x1024");

    companion object {
        fun fromMaxDimension(dimension: Int): CameraResolution {
            return entries.find { it.maxDimension == dimension } ?: MEDIUM
        }
    }
}

/**
 * Camera frame rate options for video streaming.
 */
enum class CameraFrameRate(val intervalMs: Long, val displayName: String) {
    VERY_SLOW(5000L, "0.2 FPS (1 per 5s)"),
    SLOW(2000L, "0.5 FPS (1 per 2s)"),
    NORMAL(1000L, "1 FPS");

    companion object {
        fun fromIntervalMs(interval: Long): CameraFrameRate {
            return entries.find { it.intervalMs == interval } ?: NORMAL
        }
    }
}

/**
 * Configuration data model for camera settings.
 */
data class CameraSettings(
    /**
     * Maximum resolution for captured frames.
     */
    val resolution: CameraResolution = CameraResolution.HIGH,

    /**
     * Frame rate for video streaming.
     */
    val frameRate: CameraFrameRate = CameraFrameRate.NORMAL
)

object CameraConfig {
    private const val PREFS_NAME = "camera_prefs"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FRAME_RATE = "frame_rate"

    /**
     * Load camera settings from SharedPreferences.
     * Returns defaults if no settings have been saved yet.
     */
    fun getSettings(context: Context): CameraSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val resolutionStr = prefs.getString(KEY_RESOLUTION, CameraResolution.HIGH.name)
        val resolution = try {
            CameraResolution.valueOf(resolutionStr!!)
        } catch (e: Exception) {
            CameraResolution.HIGH
        }

        val frameRateStr = prefs.getString(KEY_FRAME_RATE, CameraFrameRate.NORMAL.name)
        val frameRate = try {
            CameraFrameRate.valueOf(frameRateStr!!)
        } catch (e: Exception) {
            CameraFrameRate.NORMAL
        }

        return CameraSettings(
            resolution = resolution,
            frameRate = frameRate
        )
    }

    /**
     * Save camera settings to SharedPreferences.
     */
    fun saveSettings(context: Context, settings: CameraSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_RESOLUTION, settings.resolution.name)
            putString(KEY_FRAME_RATE, settings.frameRate.name)
        }.apply()
    }
}

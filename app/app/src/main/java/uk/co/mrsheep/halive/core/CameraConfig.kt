package uk.co.mrsheep.halive.core

import android.content.Context
import uk.co.mrsheep.halive.services.camera.CameraFacing
import uk.co.mrsheep.halive.services.camera.VideoSourceType

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
    private const val KEY_FACING = "facing"
    private const val KEY_VIDEO_START_ENABLED = "video_start_enabled"
    private const val KEY_LAST_VIDEO_SOURCE = "last_video_source"

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

    /**
     * Get the last used camera facing direction.
     * Defaults to FRONT if not set.
     */
    fun getFacing(context: Context): CameraFacing {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val facingStr = prefs.getString(KEY_FACING, CameraFacing.FRONT.name)
        return try {
            CameraFacing.valueOf(facingStr!!)
        } catch (e: Exception) {
            CameraFacing.FRONT
        }
    }

    /**
     * Save the camera facing direction.
     */
    fun saveFacing(context: Context, facing: CameraFacing) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FACING, facing.name).apply()
    }

    /**
     * Get whether video should auto-start on session initialization.
     * Defaults to false if not set.
     */
    fun isVideoStartEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_VIDEO_START_ENABLED, false)
    }

    /**
     * Save whether video should auto-start on session initialization.
     */
    fun setVideoStartEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_VIDEO_START_ENABLED, enabled).apply()
    }

    /**
     * Save the last used video source.
     * Serialization scheme:
     * - VideoSourceType.None → "NONE"
     * - VideoSourceType.DeviceCamera(FRONT) → "DEVICE_FRONT"
     * - VideoSourceType.DeviceCamera(BACK) → "DEVICE_BACK"
     * - VideoSourceType.HACamera(entityId, friendlyName) → "HA|$entityId|$friendlyName"
     */
    fun saveLastVideoSource(context: Context, sourceType: VideoSourceType) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serialized = when (sourceType) {
            is VideoSourceType.None -> "NONE"
            is VideoSourceType.DeviceCamera -> {
                if (sourceType.facing == CameraFacing.FRONT) "DEVICE_FRONT" else "DEVICE_BACK"
            }
            is VideoSourceType.HACamera -> "HA|${sourceType.entityId}|${sourceType.friendlyName}"
        }
        prefs.edit().putString(KEY_LAST_VIDEO_SOURCE, serialized).apply()
    }

    /**
     * Get the last used video source.
     * Returns null if none has been saved or if the saved value is invalid.
     * Deserialization scheme matches [saveLastVideoSource].
     */
    fun getLastVideoSource(context: Context): VideoSourceType? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serialized = prefs.getString(KEY_LAST_VIDEO_SOURCE, null) ?: return null

        return when {
            serialized == "NONE" -> VideoSourceType.None
            serialized == "DEVICE_FRONT" -> VideoSourceType.DeviceCamera(CameraFacing.FRONT)
            serialized == "DEVICE_BACK" -> VideoSourceType.DeviceCamera(CameraFacing.BACK)
            serialized.startsWith("HA|") -> {
                try {
                    val parts = serialized.substring(3).split("|", limit = 2)
                    if (parts.size == 2) {
                        VideoSourceType.HACamera(parts[0], parts[1])
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            else -> null
        }
    }
}

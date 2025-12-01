package uk.co.mrsheep.halive.services.camera

import kotlinx.coroutines.flow.Flow

/**
 * Interface for video sources that can provide frames to Gemini Live.
 * Implementations include device cameras and Home Assistant cameras.
 */
interface VideoSource {
    /** Unique identifier for this source */
    val sourceId: String

    /** Display name for UI */
    val displayName: String

    /** Flow of JPEG-encoded frames, processed to configured resolution */
    val frameFlow: Flow<ByteArray>

    /** Whether this source is currently active */
    val isActive: Boolean

    /** Start producing frames */
    suspend fun start()

    /** Stop producing frames and release resources */
    fun stop()
}

/**
 * Represents available video source types.
 */
sealed class VideoSourceType {
    data class DeviceCamera(val facing: CameraFacing) : VideoSourceType() {
        override fun toString() = "Phone Camera (${if (facing == CameraFacing.FRONT) "Front" else "Back"})"
    }

    data class HACamera(val entityId: String, val friendlyName: String) : VideoSourceType() {
        override fun toString() = friendlyName
    }

    data object None : VideoSourceType() {
        override fun toString() = "Off"
    }
}

/**
 * Data class for available camera options shown in UI.
 */
data class VideoSourceOption(
    val type: VideoSourceType,
    val displayName: String
)

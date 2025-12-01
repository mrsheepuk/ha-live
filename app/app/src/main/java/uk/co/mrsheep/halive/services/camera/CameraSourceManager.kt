package uk.co.mrsheep.halive.services.camera

import android.content.Context
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import uk.co.mrsheep.halive.core.CameraConfig
import uk.co.mrsheep.halive.services.CameraEntity
import uk.co.mrsheep.halive.services.HomeAssistantApiClient

/**
 * Manages video sources (device cameras and Home Assistant cameras).
 * Provides available source options and creates source instances.
 */
class CameraSourceManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "CameraSourceManager"
    }

    /** Cached list of HA camera entities, populated during session prep */
    private var haCameras: List<CameraEntity> = emptyList()

    /**
     * Update the list of available Home Assistant cameras.
     * Called during session preparation.
     */
    fun setHACameras(cameras: List<CameraEntity>) {
        haCameras = cameras
        Log.i(TAG, "Updated HA cameras: ${cameras.size} cameras available")
        cameras.forEach {
            Log.d(TAG, "  - ${it.entityId}: ${it.friendlyName} (${it.state})")
        }
    }

    /**
     * Get list of available video source options for the UI.
     * Includes device cameras and cached HA cameras.
     */
    fun getAvailableSourceOptions(): List<VideoSourceOption> {
        val options = mutableListOf<VideoSourceOption>()

        // Always add "Off" option first
        options.add(VideoSourceOption(
            type = VideoSourceType.None,
            displayName = "Off"
        ))

        // Add device cameras
        options.add(VideoSourceOption(
            type = VideoSourceType.DeviceCamera(CameraFacing.FRONT),
            displayName = "Phone Camera (Front)"
        ))
        options.add(VideoSourceOption(
            type = VideoSourceType.DeviceCamera(CameraFacing.BACK),
            displayName = "Phone Camera (Back)"
        ))

        // Add HA cameras (filtered to available ones)
        haCameras
            .filter { it.state != "unavailable" }
            .forEach { camera ->
                options.add(VideoSourceOption(
                    type = VideoSourceType.HACamera(camera.entityId, camera.friendlyName),
                    displayName = camera.friendlyName
                ))
            }

        return options
    }

    /**
     * Create a video source instance for the given type.
     *
     * @param type The type of video source to create
     * @param lifecycleOwner Required for device cameras
     * @param previewView Optional preview for device cameras
     * @param haApiClient Required for HA cameras
     * @return The created VideoSource, or null if type is None
     */
    fun createSource(
        type: VideoSourceType,
        lifecycleOwner: LifecycleOwner? = null,
        previewView: PreviewView? = null,
        haApiClient: HomeAssistantApiClient? = null
    ): VideoSource? {
        val settings = CameraConfig.getSettings(context)

        return when (type) {
            is VideoSourceType.None -> null

            is VideoSourceType.DeviceCamera -> {
                requireNotNull(lifecycleOwner) { "LifecycleOwner required for device camera" }
                DeviceCameraSource(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    facing = type.facing
                )
            }

            is VideoSourceType.HACamera -> {
                requireNotNull(haApiClient) { "HomeAssistantApiClient required for HA camera" }
                HACameraSource(
                    entityId = type.entityId,
                    friendlyName = type.friendlyName,
                    haApiClient = haApiClient,
                    maxDimension = settings.resolution.maxDimension,
                    frameIntervalMs = settings.frameRate.intervalMs
                )
            }
        }
    }

    /**
     * Check if there are any HA cameras available.
     */
    fun hasHACameras(): Boolean = haCameras.any { it.state != "unavailable" }

    /**
     * Get the number of available HA cameras.
     */
    fun getHACameraCount(): Int = haCameras.count { it.state != "unavailable" }

    /**
     * Clear cached HA cameras (e.g., when session ends).
     */
    fun clearHACameras() {
        haCameras = emptyList()
    }
}

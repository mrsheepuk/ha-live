package uk.co.mrsheep.halive.services.camera

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.Flow
import uk.co.mrsheep.halive.core.CameraConfig

/**
 * Video source implementation for device cameras (front/back).
 * Wraps CameraHelper to provide the VideoSource interface.
 */
class DeviceCameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView?,
    private val facing: CameraFacing
) : VideoSource {

    private val cameraHelper = CameraHelper(context)

    override val sourceId: String = "device_camera_${facing.name.lowercase()}"

    override val displayName: String = when (facing) {
        CameraFacing.FRONT -> "Phone Camera (Front)"
        CameraFacing.BACK -> "Phone Camera (Back)"
    }

    override val frameFlow: Flow<ByteArray>
        get() = cameraHelper.frameFlow

    override val isActive: Boolean
        get() = cameraHelper.isActive

    /**
     * Get the underlying CameraHelper for direct access if needed.
     * Used for preview binding and camera switching.
     */
    val helper: CameraHelper get() = cameraHelper

    /**
     * Current camera facing direction.
     */
    val currentFacing: CameraFacing get() = cameraHelper.facing

    override suspend fun start() {
        // Load saved facing preference or use provided facing
        val savedFacing = CameraConfig.getFacing(context)
        val actualFacing = if (savedFacing != facing) facing else savedFacing

        cameraHelper.startCapture(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            facing = actualFacing,
            onReady = null,
            onError = null
        )
    }

    override fun stop() {
        cameraHelper.stopCapture()
    }

    /**
     * Switch between front and back camera.
     */
    fun switchCamera() {
        cameraHelper.switchCamera(lifecycleOwner, previewView)
        CameraConfig.saveFacing(context, cameraHelper.facing)
    }

    /**
     * Set specific camera facing.
     */
    fun setFacing(newFacing: CameraFacing) {
        cameraHelper.setFacing(newFacing, lifecycleOwner, previewView)
        CameraConfig.saveFacing(context, cameraHelper.facing)
    }
}

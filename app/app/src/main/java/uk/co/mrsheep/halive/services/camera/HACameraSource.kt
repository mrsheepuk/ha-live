package uk.co.mrsheep.halive.services.camera

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.services.HomeAssistantApiClient

/**
 * Video source implementation for Home Assistant cameras.
 * Fetches snapshots from HA camera entities at configured intervals.
 */
class HACameraSource(
    private val entityId: String,
    private val friendlyName: String,
    private val haApiClient: HomeAssistantApiClient,
    private val maxDimension: Int,
    private val frameIntervalMs: Long
) : VideoSource {

    companion object {
        private const val TAG = "HACameraSource"
    }

    override val sourceId: String = "ha_camera_$entityId"

    override val displayName: String = friendlyName

    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null
    private var _isActive = false

    // Flag to prevent start() from running if stop() was called before start() executed
    // This handles the race condition where stop() is called during the async delay before start()
    @Volatile
    private var _isCancelled = false

    // Flow for emitting JPEG frames
    private val _frameFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val frameFlow: Flow<ByteArray> = _frameFlow.asSharedFlow()

    override val isActive: Boolean
        get() = _isActive

    /** The most recently fetched frame, for preview display */
    var lastFrame: ByteArray? = null
        private set

    /** Callback for when a new frame is available (for preview updates) */
    var onFrameAvailable: ((ByteArray) -> Unit)? = null

    /** Callback for errors during capture */
    var onError: ((Exception) -> Unit)? = null

    override suspend fun start() {
        // Check if stop() was called before we even started
        if (_isCancelled) {
            Log.i(TAG, "HACameraSource start() aborted - already cancelled: $entityId")
            return
        }

        if (_isActive) {
            Log.w(TAG, "HACameraSource already active: $entityId")
            return
        }

        _isActive = true
        captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        captureJob = captureScope?.launch {
            Log.i(TAG, "Starting HA camera capture: $entityId (interval: ${frameIntervalMs}ms, maxDim: $maxDimension)")

            while (isActive && _isActive) {
                try {
                    // Fetch snapshot from Home Assistant
                    val rawJpeg = haApiClient.getCameraSnapshot(entityId)

                    // Process to fit within max dimension
                    val processedJpeg = FrameProcessor.processJpeg(
                        jpegData = rawJpeg,
                        maxDimension = maxDimension
                    )

                    // Store for preview
                    lastFrame = processedJpeg

                    // Emit to flow
                    _frameFlow.tryEmit(processedJpeg)

                    // Notify preview callback
                    onFrameAvailable?.invoke(processedJpeg)

                    Log.d(TAG, "HA camera frame: $entityId, ${processedJpeg.size} bytes")

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching HA camera snapshot: $entityId", e)
                    onError?.invoke(e)
                    // Don't break the loop, continue trying
                }

                // Wait for next frame interval
                delay(frameIntervalMs)
            }

            Log.i(TAG, "HA camera capture loop ended: $entityId")
        }
    }

    override fun stop() {
        // Set cancelled flag FIRST to prevent any pending start() from running
        // This handles the race condition where stop() is called before start() executes
        _isCancelled = true

        if (!_isActive) {
            Log.i(TAG, "HACameraSource stop() called before active: $entityId (marked cancelled)")
            return
        }

        Log.i(TAG, "Stopping HA camera capture: $entityId")
        _isActive = false
        captureJob?.cancel()
        captureJob = null
        captureScope?.cancel()
        captureScope = null
        lastFrame = null
    }
}

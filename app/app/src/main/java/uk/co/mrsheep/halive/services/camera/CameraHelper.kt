package uk.co.mrsheep.halive.services.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Helper class for capturing camera frames using CameraX.
 *
 * Captures frames at 1 FPS, converts to JPEG with max 1024x1024 resolution,
 * and emits them via a Flow for sending to Gemini Live API.
 */
class CameraHelper(
    private val context: Context
) {
    companion object {
        private const val TAG = "CameraHelper"
        // Debug: Reduced for testing - normally 1024
        private const val MAX_DIMENSION = 512
        // Debug: Reduced for testing - normally 1000L (1 FPS)
        private const val FRAME_INTERVAL_MS = 5000L // 1 frame every 5 seconds
        private const val JPEG_QUALITY = 80

        // Debug: Set to true to save frames to external storage for inspection
        private const val DEBUG_SAVE_FRAMES = true
        private const val DEBUG_FRAMES_DIR = "camera_debug_frames"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var cameraExecutor: ExecutorService? = null

    private var currentFacing = CameraFacing.FRONT
    private var isCapturing = false
    private var lastFrameTime = 0L
    private var frameCounter = 0

    // Flow for emitting JPEG frames
    private val _frameFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Flow for tracking frame count (for UI display)
    private val _frameCountFlow = MutableStateFlow(0)

    /** Flow of JPEG-encoded frames (max 512x512, 1 frame per 5 sec for debug) */
    val frameFlow: Flow<ByteArray> = _frameFlow.asSharedFlow()

    /** Flow of frame count for UI display */
    val frameCountFlow: StateFlow<Int> = _frameCountFlow.asStateFlow()

    /** Current camera facing direction */
    val facing: CameraFacing get() = currentFacing

    /** Whether camera is currently capturing */
    val isActive: Boolean get() = isCapturing

    /**
     * Initialize and start camera capture.
     *
     * @param lifecycleOwner The lifecycle owner to bind camera to
     * @param previewView Optional preview view to display camera feed
     * @param facing Initial camera facing direction (default: FRONT)
     * @param onReady Callback when camera is ready
     * @param onError Callback on camera error
     */
    fun startCapture(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null,
        facing: CameraFacing = CameraFacing.FRONT,
        onReady: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        if (isCapturing) {
            Log.w(TAG, "Camera capture already active")
            return
        }

        currentFacing = facing
        frameCounter = 0
        _frameCountFlow.value = 0

        // Clear old debug frames when starting new capture
        if (DEBUG_SAVE_FRAMES) {
            clearDebugFrames()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner, previewView)
                isCapturing = true
                Log.i(TAG, "Camera capture started (facing: $currentFacing)")
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera capture", e)
                onError?.invoke(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Stop camera capture and release resources.
     */
    fun stopCapture() {
        if (!isCapturing) return

        isCapturing = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageAnalysis = null
        preview = null

        cameraExecutor?.shutdown()
        cameraExecutor = null

        Log.i(TAG, "Camera capture stopped")
    }

    /**
     * Switch between front and back camera.
     *
     * @param lifecycleOwner The lifecycle owner to rebind camera to
     * @param previewView Optional preview view to display camera feed
     */
    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null
    ) {
        currentFacing = if (currentFacing == CameraFacing.FRONT) {
            CameraFacing.BACK
        } else {
            CameraFacing.FRONT
        }

        if (isCapturing) {
            cameraProvider?.unbindAll()
            bindCameraUseCases(lifecycleOwner, previewView)
            Log.i(TAG, "Camera switched to: $currentFacing")
        }
    }

    /**
     * Set the camera facing direction.
     *
     * @param facing The desired camera facing
     * @param lifecycleOwner The lifecycle owner to rebind camera to
     * @param previewView Optional preview view to display camera feed
     */
    fun setFacing(
        facing: CameraFacing,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null
    ) {
        if (facing == currentFacing) return
        currentFacing = facing

        if (isCapturing) {
            cameraProvider?.unbindAll()
            bindCameraUseCases(lifecycleOwner, previewView)
            Log.i(TAG, "Camera facing set to: $currentFacing")
        }
    }

    private fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?
    ) {
        val provider = cameraProvider ?: return
        val executor = cameraExecutor ?: return

        // Camera selector based on facing direction
        val cameraSelector = when (currentFacing) {
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Preview use case (for displaying in UI)
        preview = Preview.Builder()
            .build()
            .also { previewUseCase ->
                previewView?.let {
                    previewUseCase.setSurfaceProvider(it.surfaceProvider)
                }
            }

        // Image analysis use case (for capturing frames)
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(MAX_DIMENSION, MAX_DIMENSION))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(executor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }

        try {
            // Unbind any existing use cases
            provider.unbindAll()

            // Bind use cases to camera
            if (previewView != null) {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } else {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )
            }

            Log.d(TAG, "Camera use cases bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", e)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // Rate limit to 1 FPS
        if (currentTime - lastFrameTime < FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        try {
            // Convert ImageProxy to JPEG
            val jpegData = imageProxyToJpeg(imageProxy)

            if (jpegData != null) {
                lastFrameTime = currentTime
                frameCounter++
                _frameCountFlow.value = frameCounter

                // Debug: Save frame to external storage for inspection
                if (DEBUG_SAVE_FRAMES) {
                    saveDebugFrame(jpegData, frameCounter)
                }

                _frameFlow.tryEmit(jpegData)
                Log.d(TAG, "Frame #$frameCounter emitted: ${jpegData.size} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Save a frame to external storage for debugging.
     * Frames are saved to: Android/data/uk.co.mrsheep.halive/files/camera_debug_frames/
     */
    private fun saveDebugFrame(jpegData: ByteArray, frameNum: Int) {
        try {
            val externalDir = context.getExternalFilesDir(null) ?: return
            val debugDir = File(externalDir, DEBUG_FRAMES_DIR)
            if (!debugDir.exists()) {
                debugDir.mkdirs()
                Log.i(TAG, "Created debug frames directory: ${debugDir.absolutePath}")
            }

            val timestamp = SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date())
            val filename = "frame_${frameNum}_${timestamp}.jpg"
            val file = File(debugDir, filename)

            file.writeBytes(jpegData)
            Log.d(TAG, "Debug frame saved: ${file.absolutePath} (${jpegData.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug frame", e)
        }
    }

    /**
     * Clear old debug frames from previous sessions.
     */
    private fun clearDebugFrames() {
        try {
            val externalDir = context.getExternalFilesDir(null) ?: return
            val debugDir = File(externalDir, DEBUG_FRAMES_DIR)
            if (debugDir.exists()) {
                val files = debugDir.listFiles() ?: return
                var deletedCount = 0
                for (file in files) {
                    if (file.name.endsWith(".jpg")) {
                        file.delete()
                        deletedCount++
                    }
                }
                Log.i(TAG, "Cleared $deletedCount old debug frames from ${debugDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear debug frames", e)
        }
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        // Convert YUV to NV21 format
        val nv21 = yuv420ToNv21(imageProxy)
        val width = imageProxy.width
        val height = imageProxy.height

        // Create YuvImage for JPEG encoding
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)

        // Encode to JPEG
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, outputStream)
        val jpegData = outputStream.toByteArray()

        // Decode to bitmap for resizing if needed
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            ?: return jpegData

        // Resize if larger than max dimension
        val resizedBitmap = resizeBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
        bitmap.recycle()

        // Re-encode to JPEG
        val resizedOutputStream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, resizedOutputStream)
        resizedBitmap.recycle()

        return resizedOutputStream.toByteArray()
    }

    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        // NV21 format: Y plane followed by interleaved VU
        // Total size: width * height * 3 / 2
        val nv21 = ByteArray(width * height + (width * height / 2))

        // Copy Y plane row by row (handles row stride padding)
        var destOffset = 0
        if (yRowStride == width) {
            // No padding, can copy directly
            yBuffer.rewind()
            yBuffer.get(nv21, 0, width * height)
            destOffset = width * height
        } else {
            // Has padding, copy row by row
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, destOffset, width)
                destOffset += width
            }
        }

        // Copy UV planes - need to interleave V and U for NV21 format (VUVU)
        val uvHeight = height / 2
        val uvWidth = width / 2

        if (uvPixelStride == 1) {
            // Planar format (rare) - U and V are separate, need to interleave
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col
                    nv21[destOffset++] = vBuffer.get(uvIndex)
                    nv21[destOffset++] = uBuffer.get(uvIndex)
                }
            }
        } else if (uvPixelStride == 2) {
            // Semi-planar format (common) - UV are interleaved in buffer
            // Check if it's already VU order (NV21) or UV order (NV12)
            // CameraX typically gives us UV (NV12), so we need to swap
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    // V comes from vBuffer, U comes from uBuffer
                    nv21[destOffset++] = vBuffer.get(uvIndex)
                    nv21[destOffset++] = uBuffer.get(uvIndex)
                }
            }
        } else {
            // Unexpected pixel stride, log and try best effort
            Log.w(TAG, "Unexpected UV pixel stride: $uvPixelStride")
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    nv21[destOffset++] = vBuffer.get(uvIndex)
                    nv21[destOffset++] = uBuffer.get(uvIndex)
                }
            }
        }

        return nv21
    }

    private fun resizeBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Apply rotation if needed
        val matrix = Matrix()
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }

        // Calculate scale to fit within MAX_DIMENSION
        val maxDim = maxOf(width, height)
        if (maxDim > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / maxDim
            matrix.postScale(scale, scale)
        }

        // Apply transformations only if needed
        return if (rotationDegrees != 0 || maxDim > MAX_DIMENSION) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        } else {
            bitmap
        }
    }
}

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
import kotlinx.coroutines.flow.asSharedFlow
import uk.co.mrsheep.halive.core.CameraConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Helper class for capturing camera frames using CameraX.
 *
 * Captures frames and converts to JPEG for sending to Gemini Live API.
 * Resolution and frame rate are configurable via Settings.
 */
class CameraHelper(
    private val context: Context
) {
    companion object {
        private const val TAG = "CameraHelper"
        private const val JPEG_QUALITY = 80
    }

    // Settings loaded at capture start
    private var maxDimension: Int = 1024
    private var frameIntervalMs: Long = 1000L

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

    /** Flow of JPEG-encoded frames */
    val frameFlow: Flow<ByteArray> = _frameFlow.asSharedFlow()

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

        // Load settings from config
        val settings = CameraConfig.getSettings(context)
        maxDimension = settings.resolution.maxDimension
        frameIntervalMs = settings.frameRate.intervalMs
        Log.i(TAG, "Camera settings: ${settings.resolution.displayName}, ${settings.frameRate.displayName}")

        currentFacing = facing
        frameCounter = 0

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
            .setTargetResolution(Size(maxDimension, maxDimension))
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

        // Rate limit based on configured frame interval
        if (currentTime - lastFrameTime < frameIntervalMs) {
            imageProxy.close()
            return
        }

        try {
            // Convert ImageProxy to JPEG
            val jpegData = imageProxyToJpeg(imageProxy)

            if (jpegData != null) {
                lastFrameTime = currentTime
                frameCounter++

                _frameFlow.tryEmit(jpegData)
                Log.d(TAG, "Frame #$frameCounter emitted: ${jpegData.size} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            imageProxy.close()
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

        // Calculate scale to fit within configured max dimension
        val maxDim = maxOf(width, height)
        if (maxDim > maxDimension) {
            val scale = maxDimension.toFloat() / maxDim
            matrix.postScale(scale, scale)
        }

        // Apply transformations only if needed
        return if (rotationDegrees != 0 || maxDim > maxDimension) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        } else {
            bitmap
        }
    }
}

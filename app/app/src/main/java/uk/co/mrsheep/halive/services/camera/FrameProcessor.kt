package uk.co.mrsheep.halive.services.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * Utility for processing video frames before sending to Gemini.
 * Handles resizing to configured max dimension while maintaining aspect ratio.
 */
object FrameProcessor {
    private const val DEFAULT_JPEG_QUALITY = 80

    /**
     * Process a JPEG image to ensure it fits within the max dimension.
     *
     * @param jpegData Original JPEG data
     * @param maxDimension Maximum dimension (width or height)
     * @param rotationDegrees Rotation to apply (0, 90, 180, 270)
     * @param quality JPEG compression quality (1-100)
     * @return Processed JPEG data
     */
    fun processJpeg(
        jpegData: ByteArray,
        maxDimension: Int,
        rotationDegrees: Int = 0,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            ?: return jpegData

        val width = bitmap.width
        val height = bitmap.height

        // Check if any transformation is needed
        val maxDim = maxOf(width, height)
        val needsResize = maxDim > maxDimension
        val needsRotation = rotationDegrees != 0

        if (!needsResize && !needsRotation) {
            bitmap.recycle()
            return jpegData
        }

        // Build transformation matrix
        val matrix = Matrix()

        if (needsRotation) {
            matrix.postRotate(rotationDegrees.toFloat())
        }

        if (needsResize) {
            val scale = maxDimension.toFloat() / maxDim
            matrix.postScale(scale, scale)
        }

        // Apply transformations
        val processedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        bitmap.recycle()

        // Re-encode to JPEG
        val outputStream = ByteArrayOutputStream()
        processedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        processedBitmap.recycle()

        return outputStream.toByteArray()
    }

    /**
     * Decode JPEG to get dimensions without fully loading into memory.
     */
    fun getJpegDimensions(jpegData: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, options)

        return if (options.outWidth > 0 && options.outHeight > 0) {
            Pair(options.outWidth, options.outHeight)
        } else {
            null
        }
    }
}

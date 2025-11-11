package uk.co.mrsheep.halive.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetCopyUtil {
    private const val TAG = "AssetCopyUtil"
    val MODEL_FILES = listOf("oww_mel.tflite", "oww_emb.tflite", "oww_wake.tflite")

    fun copyAssetsToFilesDir(context: Context) {
        val assetManager = context.assets
        MODEL_FILES.forEach { filename ->
            val outFile = File(context.filesDir, filename)
            // Only copy if it doesn't exist to prevent overwriting on every launch
            if (outFile.exists()) {
                Log.d(TAG, "$filename already exists. Skipping.")
                return@forEach
            }
            try {
                assetManager.open(filename).use { inputStream ->
                    FileOutputStream(outFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                        Log.i(TAG, "Copied $filename to ${outFile.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy $filename", e)
            }
        }
    }
}

package uk.co.mrsheep.halive.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetCopyUtil {
    private const val TAG = "AssetCopyUtil"
    private const val WAKE_WORDS_DIR = "wake_words"

    // Map of filename to destination subdirectory (empty string = filesDir root)
    private val MODEL_FILES = mapOf(
        "melspectrogram.onnx" to "",
        "embedding_model.onnx" to "",
        "ok_computer.onnx" to WAKE_WORDS_DIR
    )

    fun copyAssetsToFilesDir(context: Context) {
        val assetManager = context.assets

        // Create wake_words subdirectory if it doesn't exist
        val wakeWordsDir = File(context.filesDir, WAKE_WORDS_DIR)
        if (!wakeWordsDir.exists()) {
            if (wakeWordsDir.mkdir()) {
                Log.d(TAG, "Created $WAKE_WORDS_DIR directory at ${wakeWordsDir.absolutePath}")
            } else {
                Log.e(TAG, "Failed to create $WAKE_WORDS_DIR directory")
            }
        }

        MODEL_FILES.forEach { (filename, subdir) ->
            val destDir = if (subdir.isEmpty()) context.filesDir else File(context.filesDir, subdir)
            val outFile = File(destDir, filename)

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

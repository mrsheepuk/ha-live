package uk.co.mrsheep.halive.core

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Manages wake word model lifecycle including import, deletion, and retrieval.
 * Models can be either built-in (immutable) or user-imported (mutable).
 *
 * Uses a singleton pattern with double-checked locking for thread-safe initialization.
 * Provides methods to:
 * - Initialize with built-in model
 * - Import custom ONNX models with validation
 * - Query available models
 * - Delete user-imported models
 */
object WakeWordModelManager {
    private const val TAG = "WakeWordModelManager"
    private const val MODELS_DIR = "wake_words"
    private const val METADATA_FILE = "wake_word_metadata.json"
    private const val BUILT_IN_MODEL_ID = "ok_computer"
    private const val BUILT_IN_MODEL_NAME = "OK Computer"

    @Serializable
    private data class ModelMetadata(
        val id: String,
        val displayName: String,
        val fileName: String,
        val isBuiltIn: Boolean,
        val addedDate: Long,
        val fileSizeBytes: Long
    )

    @Serializable
    private data class MetadataIndex(
        val models: List<ModelMetadata> = emptyList()
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private var initialized = false

    /**
     * Initializes the wake word model manager.
     * Creates necessary directories and copies built-in model from assets if needed.
     * Safe to call multiple times.
     *
     * @param context Android application context
     */
    fun ensureInitialized(context: Context) {
        if (initialized) {
            Log.d(TAG, "WakeWordModelManager already initialized")
            return
        }

        try {
            val modelsDir = File(context.filesDir, MODELS_DIR)
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
                Log.d(TAG, "Created models directory: ${modelsDir.absolutePath}")
            }

            val metadataFile = File(context.filesDir, METADATA_FILE)

            // Check if built-in model exists (should be copied by AssetCopyUtil)
            val builtInModelFile = File(modelsDir, "$BUILT_IN_MODEL_ID.onnx")
            if (!builtInModelFile.exists()) {
                Log.w(TAG, "Built-in model not found at: ${builtInModelFile.absolutePath}. " +
                        "AssetCopyUtil should have copied it during app initialization.")
            }

            // Initialize metadata if needed
            if (!metadataFile.exists()) {
                Log.d(TAG, "Creating initial metadata file...")
                val initialMetadata = if (builtInModelFile.exists()) {
                    MetadataIndex(
                        models = listOf(
                            ModelMetadata(
                                id = BUILT_IN_MODEL_ID,
                                displayName = BUILT_IN_MODEL_NAME,
                                fileName = "$BUILT_IN_MODEL_ID.onnx",
                                isBuiltIn = true,
                                addedDate = System.currentTimeMillis(),
                                fileSizeBytes = builtInModelFile.length()
                            )
                        )
                    )
                } else {
                    MetadataIndex()
                }
                saveMetadata(context, initialMetadata)
            }

            initialized = true
            Log.d(TAG, "WakeWordModelManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WakeWordModelManager: ${e.message}", e)
        }
    }

    /**
     * Gets all available models (built-in + imported).
     *
     * @param context Android application context
     * @return List of all available WakeWordModelConfig objects
     */
    fun getAllModels(context: Context): List<WakeWordModelConfig> {
        ensureInitialized(context)
        return try {
            val metadata = loadMetadata(context)
            metadata.models.mapNotNull { modelMeta ->
                val modelFile = File(context.filesDir, "$MODELS_DIR/${modelMeta.fileName}")
                if (modelFile.exists()) {
                    WakeWordModelConfig(
                        id = modelMeta.id,
                        displayName = modelMeta.displayName,
                        fileName = modelMeta.fileName,
                        isBuiltIn = modelMeta.isBuiltIn,
                        filePath = modelFile.absolutePath,
                        addedDate = modelMeta.addedDate,
                        fileSizeBytes = modelFile.length()
                    )
                } else {
                    Log.w(TAG, "Model file not found, removing from metadata: ${modelMeta.id}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all models: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Gets a specific model by ID.
     *
     * @param context Android application context
     * @param id Model ID to retrieve
     * @return WakeWordModelConfig if found, null otherwise
     */
    fun getModel(context: Context, id: String): WakeWordModelConfig? {
        return getAllModels(context).find { it.id == id }
    }

    /**
     * Imports a new ONNX model from a URI.
     * Validates the model before importing using OnnxModelValidator.
     *
     * @param context Android application context
     * @param uri URI of the ONNX file to import
     * @param displayName User-friendly display name for the model
     * @return Result<WakeWordModelConfig> - success with imported model or failure with exception
     */
    fun importModel(context: Context, uri: Uri, displayName: String): Result<WakeWordModelConfig> {
        return try {
            ensureInitialized(context)

            Log.d(TAG, "Starting model import: $displayName from $uri")

            val modelsDir = File(context.filesDir, MODELS_DIR)
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }

            // Generate unique ID and filename
            val modelId = UUID.randomUUID().toString()
            val fileName = "${modelId}.onnx"
            val modelFile = File(modelsDir, fileName)

            // Copy file from URI to models directory
            context.contentResolver.openInputStream(uri)?.use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                return Result.failure(Exception("Failed to open input stream from URI: $uri"))
            }

            Log.d(TAG, "Model file copied to: ${modelFile.absolutePath} (${modelFile.length()} bytes)")

            // Validate the model using OnnxModelValidator
            Log.d(TAG, "Validating ONNX model...")
            val validationResult = OnnxModelValidator.validateWakeWordModel(modelFile)
            if (validationResult.isFailure) {
                modelFile.delete()
                Log.e(TAG, "Model validation failed, file deleted")
                return Result.failure(
                    Exception("Model validation failed: ${validationResult.exceptionOrNull()?.message}")
                )
            }

            Log.d(TAG, "Model validation successful")

            // Create model config
            val modelConfig = WakeWordModelConfig(
                id = modelId,
                displayName = displayName,
                fileName = fileName,
                isBuiltIn = false,
                filePath = modelFile.absolutePath,
                addedDate = System.currentTimeMillis(),
                fileSizeBytes = modelFile.length()
            )

            // Add to metadata
            val currentMetadata = loadMetadata(context)
            val newModelMeta = ModelMetadata(
                id = modelId,
                displayName = displayName,
                fileName = fileName,
                isBuiltIn = false,
                addedDate = modelConfig.addedDate,
                fileSizeBytes = modelFile.length()
            )
            val updatedMetadata = currentMetadata.copy(
                models = currentMetadata.models + newModelMeta
            )
            saveMetadata(context, updatedMetadata)

            Log.d(TAG, "Model imported successfully: $modelId")
            Result.success(modelConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Model import failed: ${e.message}", e)
            Result.failure(Exception("Failed to import model: ${e.message}", e))
        }
    }

    /**
     * Deletes a user-imported model.
     * Built-in models cannot be deleted.
     *
     * @param context Android application context
     * @param id Model ID to delete
     * @return True if deletion was successful, false if model not found or is built-in
     */
    fun deleteModel(context: Context, id: String): Boolean {
        return try {
            // Cannot delete built-in model
            if (id == BUILT_IN_MODEL_ID) {
                Log.w(TAG, "Cannot delete built-in model: $id")
                return false
            }

            val metadata = loadMetadata(context)
            val modelMeta = metadata.models.find { it.id == id }
                ?: run {
                    Log.w(TAG, "Model not found for deletion: $id")
                    return false
                }

            // Prevent deletion of built-in models
            if (modelMeta.isBuiltIn) {
                Log.w(TAG, "Cannot delete built-in model: $id")
                return false
            }

            // Delete physical file
            val modelFile = File(context.filesDir, "$MODELS_DIR/${modelMeta.fileName}")
            val fileDeleted = if (modelFile.exists()) {
                modelFile.delete()
            } else {
                Log.w(TAG, "Model file not found: ${modelFile.absolutePath}")
                true // Consider it deleted if file doesn't exist
            }

            // Update metadata
            val updatedMetadata = metadata.copy(
                models = metadata.models.filter { it.id != id }
            )
            saveMetadata(context, updatedMetadata)

            Log.d(TAG, "Model deleted successfully: $id (file: $fileDeleted)")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: ${e.message}", e)
            false
        }
    }

    /**
     * Loads model metadata from JSON file.
     *
     * @param context Android application context
     * @return MetadataIndex loaded from file, or empty index if file doesn't exist
     */
    private fun loadMetadata(context: Context): MetadataIndex {
        return try {
            val metadataFile = File(context.filesDir, METADATA_FILE)
            if (metadataFile.exists()) {
                val json = metadataFile.readText()
                Json.decodeFromString<MetadataIndex>(json)
            } else {
                Log.d(TAG, "Metadata file doesn't exist yet")
                MetadataIndex()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading metadata: ${e.message}", e)
            MetadataIndex()
        }
    }

    /**
     * Saves model metadata to JSON file.
     *
     * @param context Android application context
     * @param metadata MetadataIndex to save
     */
    private fun saveMetadata(context: Context, metadata: MetadataIndex) {
        try {
            val metadataFile = File(context.filesDir, METADATA_FILE)
            val jsonString = json.encodeToString(metadata)
            metadataFile.writeText(jsonString)
            Log.d(TAG, "Metadata saved successfully (${metadata.models.size} models)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving metadata: ${e.message}", e)
        }
    }
}

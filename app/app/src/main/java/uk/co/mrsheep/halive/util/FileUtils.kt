package uk.co.mrsheep.halive.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object FileUtils {

    /**
     * Creates an Intent for creating/saving a document file.
     * Used for exporting profiles to .haprofile files.
     *
     * @param suggestedFileName The suggested filename for the new document (e.g., "profile.haprofile")
     * @return Intent configured for ACTION_CREATE_DOCUMENT with application/json MIME type
     */
    fun createExportIntent(suggestedFileName: String): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, suggestedFileName)
        }
    }

    /**
     * Creates an Intent for opening a document file.
     * Used for importing .haprofile and .json files.
     *
     * @return Intent configured for ACTION_OPEN_DOCUMENT with application/json MIME type
     */
    fun createImportIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
    }

    /**
     * Writes string content to a URI using the content resolver.
     * Properly manages streams with try-with-resources to ensure closure.
     *
     * @param context Android context for content resolver access
     * @param uri The URI to write to (from ACTION_CREATE_DOCUMENT result)
     * @param content The string content to write
     * @return Result.success(Unit) on success, Result.failure(Throwable) on error
     */
    fun writeToUri(context: Context, uri: Uri, content: String): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            OutputStreamWriter(outputStream).use { writer ->
                writer.write(content)
                writer.flush()
            }
        } ?: throw IOException("Failed to open output stream for URI: $uri")
    }

    /**
     * Reads string content from a URI using the content resolver.
     * Properly manages streams with try-with-resources to ensure closure.
     *
     * @param context Android context for content resolver access
     * @param uri The URI to read from (from ACTION_OPEN_DOCUMENT result)
     * @return Result.success(content) with trimmed string content on success,
     *         Result.failure(Throwable) on error
     */
    fun readFromUri(context: Context, uri: Uri): Result<String> = runCatching {
        val stringBuilder = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line).append("\n")
                }
            }
        } ?: throw IOException("Failed to open input stream for URI: $uri")

        // Return trimmed content (removes trailing newline from last line)
        stringBuilder.toString().trimEnd()
    }
}

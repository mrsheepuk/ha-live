package uk.co.mrsheep.halive.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash logger that writes exceptions to a file for debugging.
 * Useful when logcat is not accessible.
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val LOG_FILE_NAME = "crash_log.txt"
    private const val MAX_LOG_SIZE = 500_000 // 500KB max file size

    /**
     * Initializes the crash logger with an uncaught exception handler.
     */
    fun initialize(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(context, thread, throwable)
            // Call the default handler to let the system handle the crash normally
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.d(TAG, "Crash logger initialized. Logs will be saved to:")
        Log.d(TAG, "  Internal: ${getLogFile(context).absolutePath}")
        Log.d(TAG, "  External: ${getExternalLogFile(context).absolutePath}")
    }

    /**
     * Logs a crash to the log file.
     */
    private fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val logFile = getLogFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            throwable.printStackTrace(printWriter)
            val stackTrace = stringWriter.toString()

            val logEntry = buildString {
                append("\n")
                append("=".repeat(80))
                append("\n")
                append("CRASH at $timestamp\n")
                append("Thread: ${thread.name}\n")
                append("Exception: ${throwable.javaClass.name}\n")
                append("Message: ${throwable.message}\n")
                append("\nStack Trace:\n")
                append(stackTrace)
                append("=".repeat(80))
                append("\n\n")
            }

            // Append to internal storage file
            logFile.appendText(logEntry)

            // Trim file if too large
            trimLogFileIfNeeded(logFile)

            // ALSO write to external storage (accessible via file manager)
            try {
                val externalLogFile = getExternalLogFile(context)
                externalLogFile.appendText(logEntry)
                trimLogFileIfNeeded(externalLogFile)
                Log.e(TAG, "Crash logged to: ${logFile.absolutePath} AND ${externalLogFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write external crash log", e)
            }

            Log.e(TAG, "Crash logged to: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash", e)
        }
    }

    /**
     * Manually log an exception (for non-fatal errors).
     */
    fun logException(context: Context, message: String, throwable: Throwable) {
        try {
            val logFile = getLogFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            throwable.printStackTrace(printWriter)
            val stackTrace = stringWriter.toString()

            val logEntry = buildString {
                append("\n")
                append("-".repeat(80))
                append("\n")
                append("ERROR at $timestamp\n")
                append("Message: $message\n")
                append("Exception: ${throwable.javaClass.name}\n")
                append("Details: ${throwable.message}\n")
                append("\nStack Trace:\n")
                append(stackTrace)
                append("-".repeat(80))
                append("\n\n")
            }

            logFile.appendText(logEntry)
            trimLogFileIfNeeded(logFile)

            // ALSO write to external storage
            try {
                val externalLogFile = getExternalLogFile(context)
                externalLogFile.appendText(logEntry)
                trimLogFileIfNeeded(externalLogFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write external error log", e)
            }

            Log.e(TAG, message, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log exception", e)
        }
    }

    /**
     * Gets the crash log file (internal storage).
     */
    fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    /**
     * Gets the external crash log file (accessible via file manager).
     * Location: Android/data/uk.co.mrsheep.halive/files/crash_log.txt
     */
    fun getExternalLogFile(context: Context): File {
        // Use getExternalFilesDir which doesn't require permissions
        // and is accessible via file manager on most devices
        val externalDir = context.getExternalFilesDir(null)
            ?: context.filesDir // Fallback to internal if external not available
        return File(externalDir, LOG_FILE_NAME)
    }

    /**
     * Reads the crash log file contents.
     */
    fun readLog(context: Context): String {
        val logFile = getLogFile(context)
        return if (logFile.exists()) {
            logFile.readText()
        } else {
            "No crash logs found."
        }
    }

    /**
     * Clears the crash log file.
     */
    fun clearLog(context: Context) {
        val logFile = getLogFile(context)
        if (logFile.exists()) {
            logFile.delete()
        }
        Log.d(TAG, "Crash log cleared")
    }

    /**
     * Trims the log file if it exceeds the maximum size.
     */
    private fun trimLogFileIfNeeded(logFile: File) {
        if (logFile.length() > MAX_LOG_SIZE) {
            // Keep only the last portion of the file
            val content = logFile.readText()
            val trimmed = content.takeLast(MAX_LOG_SIZE / 2)
            logFile.writeText("... (log trimmed) ...\n\n$trimmed")
        }
    }
}

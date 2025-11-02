package uk.co.mrsheep.halive.core

import android.content.Context
import android.net.Uri
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object FirebaseConfig {

    private const val PREFS_NAME = "firebase_creds"
    private const val KEY_API = "api_key"
    private const val KEY_APP_ID = "app_id"
    private const val KEY_PROJECT_ID = "project_id"
    private const val KEY_GCM_SENDER_ID = "gcm_sender_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Tries to initialize Firebase if it's not already.
     * Returns true if successful or already initialized.
     */
    fun initializeFirebase(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            return true // Already initialized
        }

        val options = loadOptions(context)
        if (options != null) {
            try {
                FirebaseApp.initializeApp(context, options)
                return true
            } catch (e: Exception) {
                // Config is bad, clear it
                clearConfig(context)
                return false
            }
        }
        return false // Not configured
    }

    /**
     * Reads a google-services.json file, parses it, and saves it to SharedPreferences.
     */
    fun saveConfigFromUri(context: Context, uri: Uri) {
        val inputStream = context.contentResolver.openInputStream(uri)
        val jsonString = inputStream.use {
            BufferedReader(InputStreamReader(it)).readText()
        }

        // Parse the google-services.json
        val json = JSONObject(jsonString)
        val projectInfo = json.getJSONObject("project_info")
        val client = json.getJSONArray("client").getJSONObject(0)
        val apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key")
        val appId = client.getJSONObject("client_info").getString("mobilesdk_app_id")
        val projectId = projectInfo.getString("project_id")
        val gcmSenderId = projectInfo.getString("project_number")

        // Save to SharedPreferences
        getPrefs(context).edit()
            .putString(KEY_API, apiKey)
            .putString(KEY_APP_ID, appId)
            .putString(KEY_PROJECT_ID, projectId)
            .putString(KEY_GCM_SENDER_ID, gcmSenderId)
            .apply()
    }

    private fun loadOptions(context: Context): FirebaseOptions? {
        val prefs = getPrefs(context)
        val apiKey = prefs.getString(KEY_API, null)
        val appId = prefs.getString(KEY_APP_ID, null)
        val projectId = prefs.getString(KEY_PROJECT_ID, null)
        val gcmSenderId = prefs.getString(KEY_GCM_SENDER_ID, null)

        if (apiKey == null || appId == null || projectId == null || gcmSenderId == null) {
            return null // Not configured
        }

        return FirebaseOptions.Builder()
            .setApiKey(apiKey)
            .setApplicationId(appId)
            .setProjectId(projectId)
            .setGcmSenderId(gcmSenderId)
            .build()
    }

    private fun clearConfig(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}

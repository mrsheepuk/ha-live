package uk.co.mrsheep.halive.ui

import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import uk.co.mrsheep.halive.R
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var statusText: TextView
    private lateinit var mainButton: Button
    private lateinit var haUrlInput: EditText
    private lateinit var haTokenInput: EditText
    private lateinit var haConfigContainer: View

    // Activity Result Launcher for the file picker
    private val selectConfigFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.saveFirebaseConfigFile(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        mainButton = findViewById(R.id.mainButton)
        haUrlInput = findViewById(R.id.haUrlInput)
        haTokenInput = findViewById(R.id.haTokenInput)
        haConfigContainer = findViewById(R.id.haConfigContainer)

        // Observe the UI state from the ViewModel
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUiForState(state)
            }
        }
    }

    private fun updateUiForState(state: UiState) {
        when (state) {
            UiState.Loading -> {
                mainButton.visibility = View.GONE
                haConfigContainer.visibility = View.GONE
                statusText.text = "Loading..."
            }
            UiState.FirebaseConfigNeeded -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.GONE
                mainButton.text = "Import google-services.json"
                statusText.text = "Firebase configuration needed"
                mainButton.setOnClickListener {
                    // Launch file picker
                    selectConfigFileLauncher.launch(arrayOf("application/json"))
                }
                mainButton.setOnTouchListener(null) // Remove talk listener
            }
            UiState.HAConfigNeeded -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.VISIBLE
                mainButton.text = "Save HA Config"
                statusText.text = "Home Assistant configuration needed"
                mainButton.setOnClickListener {
                    val url = haUrlInput.text.toString()
                    val token = haTokenInput.text.toString()
                    viewModel.saveHAConfig(url, token)
                }
                mainButton.setOnTouchListener(null) // Remove talk listener
            }
            UiState.ReadyToTalk -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.GONE
                mainButton.text = "TALK"
                statusText.text = "Hold to Talk"
                mainButton.setOnClickListener(null) // Remove config listeners
                mainButton.setOnTouchListener(talkListener)
            }
            UiState.Listening -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.GONE
                statusText.text = "Listening..."
                // Listener is already active
            }
            UiState.ExecutingAction -> {
                mainButton.visibility = View.VISIBLE
                haConfigContainer.visibility = View.GONE
                statusText.text = "Executing action..."
                // Keep button active but show execution status
            }
            is UiState.Error -> {
                mainButton.visibility = View.GONE
                haConfigContainer.visibility = View.GONE
                statusText.text = state.message
            }
        }
    }

    // Talk button listener for push-to-talk functionality
    // TODO: Add runtime permission request for RECORD_AUDIO
    @android.annotation.SuppressLint("MissingPermission")
    private val talkListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                viewModel.onTalkButtonPressed()
                true
            }
            MotionEvent.ACTION_UP -> {
                viewModel.onTalkButtonReleased()
                true
            }
            else -> false
        }
    }
}

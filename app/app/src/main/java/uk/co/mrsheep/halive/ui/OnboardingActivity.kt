package uk.co.mrsheep.halive.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.ConversationServicePreference
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private val viewModel: OnboardingViewModel by viewModels()

    // Step containers
    private lateinit var step1Container: View
    private lateinit var step2Container: View
    private lateinit var step3Container: View
    private lateinit var step4Container: View

    // Progress indicator
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    // Step 1: Provider Selection
    private lateinit var geminiDirectCard: MaterialCardView
    private lateinit var firebaseCard: MaterialCardView

    // Step 2: Dynamic containers
    private lateinit var firebaseConfigContainer: LinearLayout
    private lateinit var geminiConfigContainer: LinearLayout
    private lateinit var geminiApiKeyLayout: TextInputLayout
    private lateinit var geminiApiKeyInput: TextInputEditText
    private lateinit var geminiContinueButton: Button

    // Step 2: Firebase config
    private lateinit var firebaseButton: Button
    private lateinit var firebaseErrorText: TextView

    // Step 3: HA Config
    private lateinit var haUrlInput: EditText
    private lateinit var haTokenInput: EditText
    private lateinit var haTestButton: Button
    private lateinit var haContinueButton: Button

    // Step 4: Complete
    private lateinit var completeButton: Button

    private val selectConfigFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.saveFirebaseConfig(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        initViews()
        observeState()

        // Start with step 1
        viewModel.startOnboarding()
    }

    private fun initViews() {
        // Progress
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)

        // Step containers
        step1Container = findViewById(R.id.step1Container)
        step2Container = findViewById(R.id.step2Container)
        step3Container = findViewById(R.id.step3Container)
        step4Container = findViewById(R.id.step4Container)

        // Step 1: Provider Selection
        geminiDirectCard = findViewById(R.id.geminiDirectCard)
        firebaseCard = findViewById(R.id.firebaseCard)

        geminiDirectCard.setOnClickListener {
            viewModel.selectProvider(ConversationServicePreference.PreferredService.GEMINI_DIRECT)
        }

        firebaseCard.setOnClickListener {
            viewModel.selectProvider(ConversationServicePreference.PreferredService.FIREBASE)
        }

        // Step 2: Dynamic config containers
        firebaseConfigContainer = findViewById(R.id.firebaseConfigContainer)
        geminiConfigContainer = findViewById(R.id.geminiConfigContainer)

        // Firebase config
        firebaseButton = findViewById(R.id.firebaseButton)
        firebaseErrorText = findViewById(R.id.firebaseErrorText)
        firebaseButton.setOnClickListener {
            // Clear previous errors
            firebaseErrorText.visibility = View.GONE
            selectConfigFileLauncher.launch(arrayOf("application/json"))
        }

        // Gemini config
        geminiApiKeyLayout = findViewById(R.id.geminiApiKeyLayout)
        geminiApiKeyInput = findViewById(R.id.geminiApiKeyInput)
        geminiContinueButton = findViewById(R.id.geminiContinueButton)
        geminiContinueButton.setOnClickListener {
            val apiKey = geminiApiKeyInput.text.toString()
            viewModel.saveGeminiApiKey(apiKey)
        }

        // Step 3: HA Config
        haUrlInput = findViewById(R.id.haUrlInput)
        haTokenInput = findViewById(R.id.haTokenInput)
        haTestButton = findViewById(R.id.haTestButton)
        haContinueButton = findViewById(R.id.haContinueButton)

        haTestButton.setOnClickListener {
            val url = haUrlInput.text.toString()
            val token = haTokenInput.text.toString()
            viewModel.testHAConnection(url, token)
        }

        haContinueButton.setOnClickListener {
            viewModel.saveHAConfigAndContinue()
        }

        // Step 4: Complete
        completeButton = findViewById(R.id.completeButton)
        completeButton.setOnClickListener {
            viewModel.completeOnboarding()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.onboardingState.collect { state ->
                updateUIForState(state)
            }
        }
    }

    private fun updateUIForState(state: OnboardingState) {
        when (state) {
            OnboardingState.Step1ProviderSelection -> {
                progressText.text = "Step 1 of 4"
                progressBar.progress = 25
                showStep(1)
            }
            OnboardingState.Step2ProviderConfig -> {
                progressText.text = "Step 2 of 4"
                progressBar.progress = 50
                showStep(2)

                // Show appropriate config UI based on selection
                val preference = ConversationServicePreference.getPreferred(this)
                when (preference) {
                    ConversationServicePreference.PreferredService.FIREBASE -> {
                        firebaseConfigContainer.visibility = View.VISIBLE
                        geminiConfigContainer.visibility = View.GONE
                    }
                    ConversationServicePreference.PreferredService.GEMINI_DIRECT -> {
                        firebaseConfigContainer.visibility = View.GONE
                        geminiConfigContainer.visibility = View.VISIBLE
                    }
                }
            }
            OnboardingState.ValidatingGeminiKey -> {
                geminiContinueButton.isEnabled = false
                geminiContinueButton.text = "Validating..."
            }
            is OnboardingState.GeminiKeyValid -> {
                geminiContinueButton.isEnabled = true
                geminiContinueButton.text = "✓ Valid"
            }
            is OnboardingState.GeminiKeyInvalid -> {
                geminiContinueButton.isEnabled = true
                geminiContinueButton.text = "Continue"
                geminiApiKeyLayout.error = state.error
            }
            is OnboardingState.FirebaseConfigInvalid -> {
                firebaseErrorText.text = state.error
                firebaseErrorText.visibility = View.VISIBLE
            }
            OnboardingState.Step3HomeAssistant -> {
                progressText.text = "Step 3 of 4"
                progressBar.progress = 75
                showStep(3)
            }
            is OnboardingState.TestingConnection -> {
                haTestButton.isEnabled = false
                haTestButton.text = "Testing..."
            }
            is OnboardingState.ConnectionSuccess -> {
                haTestButton.isEnabled = true
                haTestButton.text = "✓ Connection successful"
                haContinueButton.isEnabled = true
            }
            is OnboardingState.ConnectionFailed -> {
                haTestButton.isEnabled = true
                haTestButton.text = "✗ Test Connection"
            }
            OnboardingState.Step4Complete -> {
                progressText.text = "Step 4 of 4"
                progressBar.progress = 100
                showStep(4)
            }
            is OnboardingState.Finished -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    private fun showStep(step: Int) {
        step1Container.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2Container.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3Container.visibility = if (step == 3) View.VISIBLE else View.GONE
        step4Container.visibility = if (step == 4) View.VISIBLE else View.GONE
    }
}

// Onboarding states
sealed class OnboardingState {
    object Step1ProviderSelection : OnboardingState()
    object Step2ProviderConfig : OnboardingState()
    object ValidatingGeminiKey : OnboardingState()
    data class GeminiKeyValid(val message: String) : OnboardingState()
    data class GeminiKeyInvalid(val error: String) : OnboardingState()
    data class FirebaseConfigInvalid(val error: String) : OnboardingState()
    object Step3HomeAssistant : OnboardingState()
    object TestingConnection : OnboardingState()
    data class ConnectionSuccess(val message: String) : OnboardingState()
    data class ConnectionFailed(val error: String) : OnboardingState()
    object Step4Complete : OnboardingState()
    object Finished : OnboardingState()
}

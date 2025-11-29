package uk.co.mrsheep.halive.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import uk.co.mrsheep.halive.R
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private val viewModel: OnboardingViewModel by viewModels()

    // Step containers
    private lateinit var step1Container: View
    private lateinit var step2Container: View
    private lateinit var step3Container: View

    // Progress indicator
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    // Step 1: Gemini Configuration
    private lateinit var geminiConfigContainer: LinearLayout
    private lateinit var geminiApiKeyLayout: TextInputLayout
    private lateinit var geminiApiKeyInput: TextInputEditText
    private lateinit var geminiContinueButton: Button

    // Step 3: HA Config
    private lateinit var haUrlInput: EditText
    private lateinit var haTokenInput: EditText
    private lateinit var haTestButton: Button
    private lateinit var haContinueButton: Button

    // Step 4: Complete
    private lateinit var completeButton: Button

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

        // Step 1: Gemini Configuration
        geminiConfigContainer = findViewById(R.id.geminiConfigContainer)
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
            OnboardingState.Step2ProviderConfig -> {
                progressText.text = "Step 1 of 3"
                progressBar.progress = 33
                showStep(1)
                geminiConfigContainer.visibility = View.VISIBLE
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
            OnboardingState.Step3HomeAssistant -> {
                progressText.text = "Step 2 of 3"
                progressBar.progress = 66
                showStep(2)
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
                progressText.text = "Step 3 of 3"
                progressBar.progress = 100
                showStep(3)
            }
            is OnboardingState.Finished -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            else -> {}
        }
    }

    private fun showStep(step: Int) {
        step1Container.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2Container.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3Container.visibility = if (step == 3) View.VISIBLE else View.GONE
    }
}

// Onboarding states
sealed class OnboardingState {
    object Step2ProviderConfig : OnboardingState()
    object ValidatingGeminiKey : OnboardingState()
    data class GeminiKeyValid(val message: String) : OnboardingState()
    data class GeminiKeyInvalid(val error: String) : OnboardingState()
    object Step3HomeAssistant : OnboardingState()
    object TestingConnection : OnboardingState()
    data class ConnectionSuccess(val message: String) : OnboardingState()
    data class ConnectionFailed(val error: String) : OnboardingState()
    object Step4Complete : OnboardingState()
    object Finished : OnboardingState()
}

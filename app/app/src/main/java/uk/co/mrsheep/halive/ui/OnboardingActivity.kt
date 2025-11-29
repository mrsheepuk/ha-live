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
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
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
    private lateinit var step4SharedConfigContainer: View
    private lateinit var step5NoIntegrationContainer: View

    // Progress indicator
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    // Step 1: Gemini Configuration
    private lateinit var geminiConfigContainer: LinearLayout
    private lateinit var geminiApiKeyLayout: TextInputLayout
    private lateinit var geminiApiKeyInput: TextInputEditText
    private lateinit var geminiContinueButton: Button

    // Step 2: OAuth Flow
    private lateinit var haUrlOnlyInput: EditText
    private lateinit var haConnectButton: Button

    // Step 3: Complete
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
        step4SharedConfigContainer = findViewById(R.id.step4SharedConfigContainer)
        step5NoIntegrationContainer = findViewById(R.id.step5NoIntegrationContainer)

        // Step 1: Gemini Configuration
        geminiConfigContainer = findViewById(R.id.geminiConfigContainer)
        geminiApiKeyLayout = findViewById(R.id.geminiApiKeyLayout)
        geminiApiKeyInput = findViewById(R.id.geminiApiKeyInput)
        geminiContinueButton = findViewById(R.id.geminiContinueButton)
        geminiContinueButton.setOnClickListener {
            val apiKey = geminiApiKeyInput.text.toString()
            viewModel.saveGeminiApiKey(apiKey)
        }

        // Step 2: OAuth Flow
        haUrlOnlyInput = findViewById(R.id.haUrlOnlyInput)
        haConnectButton = findViewById(R.id.haConnectButton)

        haConnectButton.setOnClickListener {
            val url = haUrlOnlyInput.text.toString()
            if (url.isBlank()) {
                Toast.makeText(this, "Please enter your Home Assistant URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val authUrl = viewModel.startOAuthFlow(url)

            // Set source so callback knows where to return
            OAuthCallbackActivity.setSourceActivity(this, OAuthCallbackActivity.SOURCE_ONBOARDING)

            // Open browser for OAuth
            try {
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.launchUrl(this, Uri.parse(authUrl))
            } catch (e: Exception) {
                // Fallback to regular browser
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                startActivity(intent)
            }
        }

        // Step 3: Complete
        completeButton = findViewById(R.id.completeButton)
        completeButton.setOnClickListener {
            viewModel.completeOnboarding()
        }

        // Step 4: Shared Config Found
        val sharedConfigApiKeyInput = findViewById<TextInputEditText?>(R.id.sharedConfigApiKeyInput)
        val sharedConfigSaveButton = findViewById<Button?>(R.id.sharedConfigSaveButton)
        val sharedConfigSkipButton = findViewById<Button?>(R.id.sharedConfigSkipButton)

        if (sharedConfigSaveButton != null) {
            sharedConfigSaveButton.setOnClickListener {
                val apiKey = sharedConfigApiKeyInput?.text.toString()
                if (apiKey.isNotBlank()) {
                    viewModel.setSharedGeminiKey(apiKey)
                } else {
                    Toast.makeText(this, "Please enter a valid API key", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (sharedConfigSkipButton != null) {
            sharedConfigSkipButton.setOnClickListener {
                viewModel.skipToComplete()
            }
        }

        // Step 5: No Integration
        val noIntegrationContinueButton = findViewById<Button?>(R.id.noIntegrationContinueButton)
        if (noIntegrationContinueButton != null) {
            noIntegrationContinueButton.setOnClickListener {
                viewModel.continueWithLocalSetup()
            }
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
            OnboardingState.Step1GeminiConfig -> {
                progressText.text = "Step 3 of 3"
                progressBar.progress = 85
                showStep(1)
                geminiConfigContainer.visibility = View.VISIBLE
            }
            OnboardingState.ValidatingGeminiKey -> {
                geminiContinueButton.isEnabled = false
                geminiContinueButton.text = "Validating..."
            }
            is OnboardingState.GeminiKeyValid -> {
                geminiContinueButton.isEnabled = true
                geminiContinueButton.text = "Valid"
            }
            is OnboardingState.GeminiKeyInvalid -> {
                geminiContinueButton.isEnabled = true
                geminiContinueButton.text = "Continue"
                geminiApiKeyLayout.error = state.error
            }
            OnboardingState.Step2HomeAssistant -> {
                progressText.text = "Step 1 of 3"
                progressBar.progress = 33
                showStep(2)
            }
            is OnboardingState.OAuthError -> {
                Toast.makeText(this, "OAuth error: ${state.error}", Toast.LENGTH_LONG).show()
            }
            OnboardingState.CheckingSharedConfig -> {
                progressText.text = "Step 2 of 3"
                progressBar.progress = 66
                showStep(4)
            }
            is OnboardingState.SharedConfigFound -> {
                progressBar.progress = if (state.hasApiKey) 95 else 80
                progressText.text = "Step 3 of 3"
                showStep(4)
                showSharedConfigStep(state.hasApiKey, state.profileCount)
            }
            OnboardingState.NoSharedConfig -> {
                progressText.text = "Step 2 of 3"
                progressBar.progress = 75
                showStep(5)
                showNoIntegrationStep()
            }
            OnboardingState.Step3Complete -> {
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
        }
    }

    private fun showStep(step: Int) {
        step1Container.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2Container.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3Container.visibility = if (step == 3) View.VISIBLE else View.GONE
        step4SharedConfigContainer.visibility = if (step == 4) View.VISIBLE else View.GONE
        step5NoIntegrationContainer.visibility = if (step == 5) View.VISIBLE else View.GONE
    }

    private fun showSharedConfigStep(hasApiKey: Boolean, profileCount: Int) {
        val title = findViewById<TextView?>(R.id.sharedConfigTitle)
        val message = findViewById<TextView?>(R.id.sharedConfigMessage)
        val apiKeyLayout = findViewById<TextInputLayout?>(R.id.sharedConfigApiKeyLayout)
        val apiKeyInput = findViewById<TextInputEditText?>(R.id.sharedConfigApiKeyInput)

        if (hasApiKey) {
            // Integration with API key already set
            title?.text = "Shared Configuration Found"
            message?.text = "Integration found with $profileCount profile(s) and API key is configured.\n\nYou're all set!"
            apiKeyLayout?.visibility = View.GONE
            apiKeyInput?.visibility = View.GONE

            val saveButton = findViewById<Button?>(R.id.sharedConfigSaveButton)
            saveButton?.visibility = View.GONE

            val skipButton = findViewById<Button?>(R.id.sharedConfigSkipButton)
            skipButton?.text = "Continue"
            skipButton?.setOnClickListener {
                viewModel.skipToComplete()
            }
        } else {
            // Integration found but no API key
            title?.text = "Shared Configuration Found"
            message?.text = "Integration found with $profileCount profile(s), but no Gemini API key is configured.\n\nPlease enter your API key to continue, or skip to set up locally."
            apiKeyLayout?.visibility = View.VISIBLE
            apiKeyInput?.visibility = View.VISIBLE

            val saveButton = findViewById<Button?>(R.id.sharedConfigSaveButton)
            saveButton?.visibility = View.VISIBLE

            val skipButton = findViewById<Button?>(R.id.sharedConfigSkipButton)
            skipButton?.text = "Skip"
        }
    }

    private fun showNoIntegrationStep() {
        val title = findViewById<TextView?>(R.id.noIntegrationTitle)
        val message = findViewById<TextView?>(R.id.noIntegrationMessage)

        title?.text = "No Integration Found"
        message?.text = "The ha_live_config integration is not installed in your Home Assistant instance.\n\nYou can continue with local profile setup, or install the integration for shared configuration support."
    }

    override fun onResume() {
        super.onResume()
        // Check if we returned from OAuth with result
        checkPendingOAuthResult()
    }

    private fun checkPendingOAuthResult() {
        val (code, state, error) = OAuthCallbackActivity.getPendingResult(this)

        if (code != null) {
            viewModel.handleOAuthCallback(code, state)
        } else if (error != null) {
            viewModel.handleOAuthError(error)
        }
    }
}

// Onboarding states
sealed class OnboardingState {
    object Step1GeminiConfig : OnboardingState()
    object ValidatingGeminiKey : OnboardingState()
    data class GeminiKeyValid(val message: String) : OnboardingState()
    data class GeminiKeyInvalid(val error: String) : OnboardingState()
    object Step2HomeAssistant : OnboardingState()
    data class OAuthError(val error: String) : OnboardingState()
    object CheckingSharedConfig : OnboardingState()
    data class SharedConfigFound(val hasApiKey: Boolean, val profileCount: Int) : OnboardingState()
    object NoSharedConfig : OnboardingState()
    object Step3Complete : OnboardingState()
    object Finished : OnboardingState()
}

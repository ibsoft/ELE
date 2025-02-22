package com.ibsoft.ele

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.ibsoft.ele.databinding.ActivitySettingsBinding
import com.ibsoft.ele.db.AppDatabase
import com.ibsoft.ele.model.ApiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var db: AppDatabase

    // Define a CoroutineScope for UI operations.
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Force light mode by default.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // Initialize the database.
        db = AppDatabase.getDatabase(this)

        // Obtain SharedPreferences.
        val sharedPref = getSharedPreferences("UserSettings", Context.MODE_PRIVATE)

        // Load the custom prompt.
        binding.editTextCustomPrompt.setText(sharedPref.getString("custom_user_prompt", ""))
        // Load the conversation context window setting; default is "3".
        binding.editTextContextWindow.setText(sharedPref.getString("context_window", "3"))

        binding.buttonSavePrompt.setOnClickListener {
            val newPrompt = binding.editTextCustomPrompt.text.toString().trim()
            with(sharedPref.edit()) {
                putString("custom_user_prompt", newPrompt)
                apply()
            }
            Toast.makeText(this, "Custom prompt saved", Toast.LENGTH_SHORT).show()
        }

        // Load API configuration from the database.
        uiScope.launch {
            val config = withContext(Dispatchers.IO) {
                db.apiConfigDao().getConfig()
            }
            if (config != null) {
                binding.editTextOpenaiApiKey.setText(config.openaiApiKey)
                binding.editTextAssistantId.setText(config.assistantId)
                binding.editTextVectorstoreId.setText(config.vectorstoreId)
                Toast.makeText(this@SettingsActivity, "API configuration loaded", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SettingsActivity, "API configuration not set", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up Save button for API configuration.
        // Only the API key is mandatory; the other fields are optional.
        binding.buttonSaveApiConfig.setOnClickListener {
            val newApiKey = binding.editTextOpenaiApiKey.text.toString().trim()
            val newAssistantId = binding.editTextAssistantId.text.toString().trim()
            val newVectorstoreId = binding.editTextVectorstoreId.text.toString().trim()
            val newContextWindow = binding.editTextContextWindow.text.toString().trim()

            if (newApiKey.isEmpty()) {
                Toast.makeText(this, "Please fill in the API key.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Save the context window setting.
            with(sharedPref.edit()) {
                putString("context_window", newContextWindow)
                apply()
            }
            uiScope.launch {
                withContext(Dispatchers.IO) {
                    db.apiConfigDao().insertConfig(
                        ApiConfig(
                            id = 1,
                            openaiApiKey = newApiKey,
                            assistantId = newAssistantId,   // Optional
                            vectorstoreId = newVectorstoreId  // Optional
                        )
                    )
                }
                Toast.makeText(this@SettingsActivity, "API configuration saved", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up Reset button for API configuration.
        binding.buttonResetApiConfig.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset API Configuration")
                .setMessage("Are you sure you want to reset the API configuration to blank?")
                .setPositiveButton("Yes") { _, _ ->
                    // Clear the EditText fields.
                    binding.editTextOpenaiApiKey.setText("")
                    binding.editTextAssistantId.setText("")
                    binding.editTextVectorstoreId.setText("")
                    // Reset the conversation context window to default "5".
                    binding.editTextContextWindow.setText("1")
                    // Update the database with blank values.
                    uiScope.launch {
                        withContext(Dispatchers.IO) {
                            db.apiConfigDao().insertConfig(
                                ApiConfig(
                                    id = 1,
                                    openaiApiKey = "",
                                    assistantId = "",
                                    vectorstoreId = ""
                                )
                            )
                        }
                        Toast.makeText(this@SettingsActivity, "API configuration reset", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("No") { _, _ ->
                    Toast.makeText(this, "Reset cancelled", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // Set up the "Play welcome message" switch.
        binding.switchPlayWelcomeMessage.isChecked = sharedPref.getBoolean("play_welcome_message", true)
        binding.switchPlayWelcomeMessage.setOnCheckedChangeListener { _, isChecked ->
            with(sharedPref.edit()) {
                putBoolean("play_welcome_message", isChecked)
                apply()
            }
            val msg = if (isChecked) "Welcome message enabled" else "Welcome message disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Clear Chat History button.
        binding.buttonClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Chat History")
                .setMessage("Are you sure you want to clear all chat history?")
                .setPositiveButton("Yes") { _, _ ->
                    uiScope.launch {
                        withContext(Dispatchers.IO) {
                            db.messageDao().deleteAllMessages() // Make sure you have messageDao() implemented.
                        }
                        Toast.makeText(this@SettingsActivity, "Chat history cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("No") { _, _ ->
                    Toast.makeText(this, "Clear chat history cancelled", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    // Override the back button so that it always navigates to ConversationListActivity.
    override fun onBackPressed() {
        val intent = Intent(this, ConversationListActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any running coroutines to avoid leaks.
        uiScope.cancel()
    }
}

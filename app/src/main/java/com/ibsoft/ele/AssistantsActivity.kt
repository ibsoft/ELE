package com.ibsoft.ele

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import com.ibsoft.ele.adapter.AssistantAdapter
import com.ibsoft.ele.databinding.ActivityAssistantsBinding
import com.ibsoft.ele.db.AppDatabase
import com.ibsoft.ele.model.AssistantResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AssistantsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssistantsBinding
    private lateinit var db: AppDatabase
    private lateinit var assistantAdapter: AssistantAdapter
    private val assistantList = mutableListOf<AssistantResponse>()
    private val activityJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + activityJob)
    private lateinit var api: OpenAIAssistantApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssistantsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        supportActionBar?.title = "Assistants"

        db = AppDatabase.getDatabase(this)

        // Setup adapter with delete and copy callbacks.
        assistantAdapter = AssistantAdapter(
            assistantList,
            onDeleteClick = { assistant ->
                showDeleteConfirmationDialog(assistant)
            },
            onCopyClick = { id ->
                copyToClipboard(id)
            }
        )
        binding.assistantsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AssistantsActivity)
            adapter = assistantAdapter
        }

        // Setup Retrofit with logging.
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        api = retrofit.create(OpenAIAssistantApi::class.java)

        // Wire up the Refresh button.
        binding.buttonRefreshAssistants.setOnClickListener {
            Toast.makeText(this, "Refreshing...", Toast.LENGTH_SHORT).show()
            loadAssistants()
        }

        // Wire up the "Add Assistant" button.
        binding.buttonAddAssistant.setOnClickListener {
            startActivity(Intent(this, CreateAssistantActivity::class.java))
        }

        Toast.makeText(this, "Refreshing vector stores...", Toast.LENGTH_SHORT).show()
        loadAssistants()
    }

    /**
     * Displays a confirmation dialog before deleting an assistant.
     */
    private fun showDeleteConfirmationDialog(assistant: AssistantResponse) {
        AlertDialog.Builder(this)
            .setTitle("Delete Assistant")
            .setMessage("Are you sure you want to delete this assistant?")
            .setPositiveButton("Yes") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Deleting assistant...", Toast.LENGTH_SHORT).show()
                uiScope.launch { deleteAssistant(assistant) }
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /**
     * Retrieves the API auth header from the stored configuration.
     * If the API key is missing, it shows a toast advising the user to update settings.
     */
    private suspend fun getAuthHeader(): String? {
        val config = withContext(Dispatchers.IO) { db.apiConfigDao().getConfig() }
        return if (config != null && config.openaiApiKey.isNotBlank()) {
            "Bearer ${config.openaiApiKey}"
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@AssistantsActivity,
                    "API configuration is missing. Please update your settings.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            null
        }
    }

    /**
     * Loads all assistants from the API.
     * If a 401 error is returned, advises the user that the API key is invalid.
     */
    private fun loadAssistants() {
        if (!isNetworkAvailable()) {
            Toast.makeText(
                this@AssistantsActivity,
                "It looks like you're offline. Please check your internet connection.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        uiScope.launch {
            assistantList.clear()
            Log.d("AssistantsActivity", "Loading assistants")
            val authHeader = getAuthHeader() ?: return@launch
            try {
                val response = withContext(Dispatchers.IO) { api.listAssistants(authHeader) }
                if (response.isSuccessful) {
                    val data: List<AssistantResponse> = response.body()?.assistants ?: emptyList()
                    assistantList.addAll(data)
                    assistantAdapter.notifyDataSetChanged()
                    Log.d("AssistantsActivity", "Loaded ${data.size} assistants")
                    if (data.isEmpty()) {
                        Toast.makeText(
                            this@AssistantsActivity,
                            "No assistants were found.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    if (response.code() == 401) {
                        Toast.makeText(
                            this@AssistantsActivity,
                            "Invalid API key. Please update your settings.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@AssistantsActivity,
                            "We couldn't load your assistants. Check your settings.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("AssistantsActivity", "Error loading assistants: ${e.message}")
                Toast.makeText(
                    this@AssistantsActivity,
                    "Please check your internet connection.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Deletes the specified assistant via the API and refreshes the list.
     * If a 401 error is returned, advises the user that the API key is invalid.
     */
    private suspend fun deleteAssistant(assistant: AssistantResponse) {
        if (!isNetworkAvailable()) {
            Toast.makeText(
                this@AssistantsActivity,
                "You appear to be offline. Please check your connection and try again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val config = withContext(Dispatchers.IO) { db.apiConfigDao().getConfig() }
        if (config == null) {
            Toast.makeText(
                this,
                "It looks like the API configuration is missing.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val authHeader = "Bearer " + config.openaiApiKey
        try {
            val response = withContext(Dispatchers.IO) { api.deleteAssistant(assistant.id, authHeader) }
            if (response.isSuccessful) {
                Toast.makeText(
                    this,
                    "Your assistant has been successfully deleted.",
                    Toast.LENGTH_SHORT
                ).show()
                loadAssistants()
            } else {
                if (response.code() == 401) {
                    Toast.makeText(
                        this,
                        "Invalid API key. Please update your settings.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "We couldn't delete your assistant right now. Please try again later.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "An error occurred while deleting your assistant. Please try again later.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Copies a given string (assistant ID) to the clipboard.
     */
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Assistant ID", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Assistant ID copied to clipboard.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Checks whether there is an active internet connection.
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnected
    }

    override fun onDestroy() {
        super.onDestroy()
        activityJob.cancel()
        Log.d("AssistantsActivity", "onDestroy called")
    }
}

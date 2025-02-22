package com.ibsoft.ele

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.ibsoft.ele.adapter.MessageAdapter
import com.ibsoft.ele.db.AppDatabase
import com.ibsoft.ele.databinding.ActivityChatBinding
import com.ibsoft.ele.model.Message
import com.ibsoft.ele.model.VectorFile
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityChatBinding
    private lateinit var db: AppDatabase
    private lateinit var messageAdapter: MessageAdapter
    private val messageList = mutableListOf<Message>()
    private var conversationId: Long = 0
    private lateinit var tts: TextToSpeech
    private val activityJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + activityJob)
    private lateinit var api: OpenAIAssistantApi

    // File picker launcher for any file type
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            uiScope.launch { processFileUpload(it) }
        }
    }

    private val randomWords = listOf(
        "Okay","Ok","Reasoning>", "Thinking>", "Analyzing>", "Processing>", "Computing>",
        "Evaluating>", "Synthesizing>", "Formulating>", "Deciding>", "Conceptualizing>",
        "Imagining>", "Predicting>", "Investigating>", "Exploring>", "Innovating>",
        "Almost there>", "Designing>", "Solving>", "Examining>", "Reviewing>",
        "Studying>", "Contemplating>", "Ruminating>", "Musing>", "Reflecting>",
        "Inferring>", "Calculating>", "Interpreting>", "Considering>", "Perceiving>",
        "Observing>", "Diagnosing>", "Brainstorming>", "Pondering>", "Rationalizing>",
        "Understanding>", "Learning>", "Communicating>", "Deciphering>", "Correlating>",
        "Visualizing>", "Estimating>", "Optimizing>", "Planning>", "Organizing>",
        "Mapping>", "Charting>", "Strategizing>", "Hypothesizing>", "Projecting>",
        "Comparing>", "Contrasting>", "Benchmarking>", "Prioritizing>", "Assessing>",
        "Appraising>", "Scrutinizing>", "Detecting>", "Confirming>", "Verifying>",
        "Aligning>", "Matching>", "Balancing>", "Adapting>", "Transforming>",
        "Enhancing>", "Envisioning>", "Focusing>", "Clarifying>", "Distilling>",
        "Revising>", "Experimenting>", "Modulating>", "Simplifying>", "Complicating>",
        "Enriching>", "Broadening>", "Narrowing>", "Interacting>", "Merging>",
        "Integrating>", "Unifying>", "Bridging>", "Linking>", "Combining>",
        "Aggregating>", "Harmonizing>", "Coordinating>", "Implementing>", "Deploying>",
        "Executing>", "Activating>", "Engaging>", "Inspiring>", "Creating>",
        "Conceiving>", "Refining>", "Controlling>", "Realizing>", "Evolving>"
    )


    private var loadingWordsJob: Job? = null

    private fun startRandomWordCycle() {
        loadingWordsJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                binding.loadingRandomText.text = randomWords.random()
                delay(1100L)  // change word every second
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Force light mode by default (can be changed via settings)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // Retrieve conversationId from the intent
        conversationId = intent.getLongExtra("conversationId", 0)

        // Initialize the database and TextToSpeech engine
        db = AppDatabase.getDatabase(this)
        tts = TextToSpeech(this, this)

        // Set up the RecyclerView and its adapter (with like/dislike functionality)
        messageAdapter = MessageAdapter(
            messages = messageList,
            onCopyClick = { text ->
                copyToClipboard(this, text)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onTTSClick = { text ->
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            },
            onLikeClick = { message ->
                uiScope.launch {
                    withContext(Dispatchers.IO) {
                        message.likes += 1
                        db.messageDao().updateMessage(message)
                    }
                    Toast.makeText(this@ChatActivity, "Liked", Toast.LENGTH_SHORT).show()
                }
            },
            onDislikeClick = { message ->
                uiScope.launch {
                    withContext(Dispatchers.IO) {
                        message.dislikes += 1
                        db.messageDao().updateMessage(message)
                    }
                    Toast.makeText(this@ChatActivity, "Disliked", Toast.LENGTH_SHORT).show()
                }
            }
        )
        binding.messageRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.messageRecyclerView.adapter = messageAdapter

        loadMessages()

        // Modify the send button so the user's message appears immediately.
        binding.buttonSend.setOnClickListener {
            val text = binding.editTextMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                // Immediately insert the user's message into the UI and persist it.
                val userLocalMsg = Message(
                    conversationId = conversationId,
                    sender = "user",
                    message = text,
                    timestamp = System.currentTimeMillis()
                )
                uiScope.launch {
                    withContext(Dispatchers.IO) {
                        db.messageDao().insertMessage(userLocalMsg)
                    }
                }
                messageList.add(userLocalMsg)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                binding.messageRecyclerView.scrollToPosition(messageList.size - 1)
                binding.editTextMessage.text.clear()

                // Now send the message via API (this will eventually add the assistant's reply)
                uiScope.launch { sendMessageThroughAssistant(text) }
            }
        }

        // Button to trigger file upload
        binding.buttonUpload.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        // Load an animated GIF into the loadingImageView
        Glide.with(this)
            .asGif()
            .load(R.drawable.loading_animation)
            .into(binding.loadingImageView)


        // Set up Retrofit with logging interceptor and increased timeouts.
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        api = retrofit.create(OpenAIAssistantApi::class.java)

        // Update the assistant with the vector store configuration before chat begins.
        uiScope.launch {
            val config = withContext(Dispatchers.IO) { db.apiConfigDao().getConfig() }
            if (config != null &&
                config.vectorstoreId.isNotEmpty() &&
                config.openaiApiKey.isNotEmpty() &&
                config.assistantId.isNotEmpty()
            ) {
                updateAssistantWithVectorStore(
                    vectorStoreId = config.vectorstoreId,
                    apiKey = config.openaiApiKey,
                    assistantId = config.assistantId
                )
            } else {
                Log.d("ChatActivity", "Vector store or API configuration not set.")
            }
        }
    }

    private fun loadMessages() {
        uiScope.launch {
            messageList.clear()
            val messages = withContext(Dispatchers.IO) { db.messageDao().getMessagesForConversation(conversationId) }
            messageList.addAll(messages)
            messageAdapter.notifyDataSetChanged()
            if (messageList.isNotEmpty()) {
                binding.messageRecyclerView.scrollToPosition(messageList.size - 1)
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Copied Text", text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Builds a conversation context string from the last 3 messages.
     */
    private suspend fun buildConversationContext(): String = withContext(Dispatchers.IO) {
        // Retrieve the context window setting from SharedPreferences ("context_window").
        // Default to "1" if not set.
        val sharedPref = getSharedPreferences("UserSettings", Context.MODE_PRIVATE)
        val contextWindowString = sharedPref.getString("context_window", "3")
        val contextWindow = contextWindowString?.toIntOrNull() ?: 3

        // Retrieve all messages for the conversation and sort them by timestamp.
        val allMessages = db.messageDao().getMessagesForConversation(conversationId)
        val sortedMessages = allMessages.sortedBy { it.timestamp }

        // Take the last 'contextWindow' messages.
        val lastMessages = sortedMessages.takeLast(contextWindow)

        // Build a context string with each message prefixed by its sender.
        lastMessages.joinToString(separator = "\n") { msg ->
            when (msg.sender.lowercase(Locale.getDefault())) {
                "user" -> "User: ${msg.message}"
                "bot"  -> "Assistant: ${msg.message}"
                else   -> msg.message
            }
        }
    }



    /**
     * Updates the assistant with the specified vector store configuration.
     */
    private suspend fun updateAssistantWithVectorStore(vectorStoreId: String, apiKey: String, assistantId: String) {
        try {
            val request = UpdateAssistantRequest(
                toolResources = mapOf("file_search" to mapOf("vector_store_ids" to listOf(vectorStoreId)))
            )
            val response = withContext(Dispatchers.IO) {
                api.updateAssistant(assistantId, "Bearer $apiKey", request)
            }
            if (response.isSuccessful) {
                response.body()?.let { assistantResponse ->
                    Log.d("ChatActivity", "Assistant updated with vector store! Response id: ${assistantResponse.id}")
                    runOnUiThread {
                        Toast.makeText(this@ChatActivity, "Ready to chat!", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // Read the error message from the response error body.
                val errorBodyString = response.errorBody()?.string() ?: "Unknown error"
                Log.e("ChatActivity", "Error updating assistant: $errorBodyString")
                if (errorBodyString.contains("No vector store found", ignoreCase = true)) {
                    runOnUiThread {
                        Toast.makeText(this@ChatActivity, "Check your settings", Toast.LENGTH_LONG).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@ChatActivity, "Check your settings", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Exception updating assistant: ${e.message}")
            runOnUiThread {
                Toast.makeText(this@ChatActivity, "Check your settings.", Toast.LENGTH_LONG).show()
            }
        }
    }


    /**
     * Sends a user message using the OpenAI Assistants API workflow.
     * The user's message is already added to the UI locally.
     * The conversation context (last 5 messages) is built and appended.
     */
    private suspend fun sendMessageThroughAssistant(userMessage: String) {
        val config = withContext(Dispatchers.IO) { db.apiConfigDao().getConfig() }
        if (config == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@ChatActivity,
                    "API configuration is missing. Please configure it in Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        if (config.assistantId.isEmpty() || config.vectorstoreId.isEmpty()) {
            withContext(Dispatchers.Main) {
                // Insert a fake assistant message prompting the user to configure settings.
                val fakeMsg = Message(
                    conversationId = conversationId,
                    sender = "bot",
                    message = "Configuration missing! Check your Settings",
                    timestamp = System.currentTimeMillis()
                )
                CoroutineScope(Dispatchers.IO).launch {
                    db.messageDao().insertMessage(fakeMsg)
                }
                messageList.add(fakeMsg)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                binding.messageRecyclerView.scrollToPosition(messageList.size - 1)
                Toast.makeText(
                    this@ChatActivity,
                    "Assistant config is missing. Please check your settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        val authHeader = "Bearer " + config.openaiApiKey
        val assistantId = config.assistantId

        try {
            Log.d("ChatActivity", "Assistant ID: $assistantId")
            withContext(Dispatchers.Main) {
                binding.loadingImageView.visibility = View.VISIBLE
                binding.loadingContainer.visibility = View.VISIBLE
                binding.loadingImageView.visibility = View.VISIBLE
                binding.loadingRandomText.visibility = View.VISIBLE
                startRandomWordCycle()  // Picks a random word
            }


            // Step 1: Create a new thread
            val threadResponse = withContext(Dispatchers.IO) { api.createThread(authHeader, CreateThreadRequest()) }
            val threadId = threadResponse.id
            Log.d("ChatActivity", "Created thread with id: $threadId")

            // Step 2: Optionally send a custom prompt
            val sharedPrefs = getSharedPreferences("UserSettings", MODE_PRIVATE)
            val customPrompt = sharedPrefs.getString("custom_user_prompt", null)
            if (!customPrompt.isNullOrBlank()) {
                val promptMsgReq = MessageRequest(role = "user", content = customPrompt)
                // Call API to add the prompt but do not update UI since the user message is already shown.
                withContext(Dispatchers.IO) {
                    api.addMessageToThread(threadId, authHeader, promptMsgReq)
                }
                Log.d("ChatActivity", "Added custom prompt")
            }

            // Step 3: Build conversation context (last 5 messages) and send the user's message via API.
            val conversationContext = buildConversationContext()
            val fullPrompt = "$conversationContext\nUser: $userMessage"
            val msgReq = MessageRequest(role = "user", content = fullPrompt)
            withContext(Dispatchers.IO) { api.addMessageToThread(threadId, authHeader, msgReq) }
            Log.d("ChatActivity", "Sent user message via API.")

            // Step 4: Start the assistant run
            val runReq = RunRequest(assistant_id = assistantId)
            val runResp = withContext(Dispatchers.IO) {
                api.runAssistant(threadId, authHeader, runReq)
            }
            val runId = runResp.id
            Log.d("ChatActivity", "Started assistant run; run id: $runId")

            // Step 5: Poll for run completion
            var runStatus: String
            do {
                delay(1000)
                val statusResp = withContext(Dispatchers.IO) {
                    api.getRunStatus(threadId, runId, authHeader)
                }
                runStatus = statusResp.status
                Log.d("ChatActivity", "Run status: $runStatus")
            } while (runStatus != "completed")

            // Step 6: Retrieve the assistant's reply
            val threadMessagesResp = withContext(Dispatchers.IO) {
                api.getThreadMessages(threadId, authHeader)
            }
            val messagesFromApi = threadMessagesResp.messages ?: emptyList()
            val assistantMessage = messagesFromApi
                .filter { it.role.lowercase(Locale.getDefault()) == "assistant" }
                .maxByOrNull { it.created_at }
            val assistantReply = assistantMessage?.content?.joinToString(" ") { it.text.value }
                ?: "No assistant response found."
            Log.d("ChatActivity", "Assistant reply: $assistantReply")

            // Step 7: Save the assistant reply locally and calculate response time
            val botMsg = Message(
                conversationId = conversationId,
                sender = "bot",
                message = assistantReply,
                timestamp = System.currentTimeMillis()
            )
            val lastUserMessageTimestamp = messageList.lastOrNull {
                it.sender.equals("user", ignoreCase = true)
            }?.timestamp
            if (lastUserMessageTimestamp != null) {
                val rawResponseTime = (botMsg.timestamp - lastUserMessageTimestamp) / 1000
                botMsg.responseTime = rawResponseTime
                Log.d("ChatActivity", "Formatted response time: ${formatResponseTime(rawResponseTime)}")
            }
            withContext(Dispatchers.IO) { db.messageDao().insertMessage(botMsg) }
            messageList.add(botMsg)
            withContext(Dispatchers.Main) {
                messageAdapter.notifyItemInserted(messageList.size - 1)
                binding.messageRecyclerView.scrollToPosition(messageList.size - 1)
                binding.loadingImageView.visibility = View.GONE
                binding.loadingRandomText.visibility = View.GONE
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                binding.loadingImageView.visibility = View.GONE
                Toast.makeText(
                    this@ChatActivity,
                    "Please check again your settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Helper function to format the response time.
     */
    private fun formatResponseTime(seconds: Long): String {
        return if (seconds < 60) {
            "Reasoned for $seconds seconds >"
        } else {
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            if (remainingSeconds == 0L) "Reasoned for $minutes minutes >"
            else "Reasoned for $minutes minutes $remainingSeconds seconds >"
        }
    }

    /**
     * Processes file upload:
     * 1. Extracts the file name.
     * 2. Uploads the file content via a multipart request with progress.
     * 3. Waits for the file to be ready.
     * 4. Registers the file with the vector store.
     * 5. Saves the file name and returned file ID to the database.
     * 6. Posts an assistant message: "File received: [filename]".
     */
    private suspend fun processFileUpload(fileUri: Uri) {
        val fileName = getFileName(fileUri) ?: "uploaded_file"
        Log.d("ChatActivity", "Original file name: $fileName")
        runOnUiThread {
            binding.progressBarUpload.visibility = View.VISIBLE
            binding.progressBarUpload.progress = 0
        }
        val validFileId = uploadFileContent(fileUri)
        runOnUiThread { binding.progressBarUpload.visibility = View.GONE }
        if (validFileId == null) {
            runOnUiThread {
                Toast.makeText(
                    this@ChatActivity,
                    "We couldn't upload your file. Please try again.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        runOnUiThread {
            Toast.makeText(this, "File uploaded successfully!", Toast.LENGTH_SHORT).show()
        }
        Log.d("ChatActivity", "File uploaded. Received file id: $validFileId")
        waitForFileToBeReady(validFileId)
        // Attempt to register the file
        val registrationSuccess = registerFileToVectorStore(validFileId)
        if (!registrationSuccess) {
            runOnUiThread {
                Toast.makeText(
                    this@ChatActivity,
                    "File registration failed. Your file was not saved.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        // Save file info to DB only if registration succeeded
        withContext(Dispatchers.IO) {
            db.vectorFileDao().insertVectorFile(VectorFile(fileName = fileName, fileId = validFileId))
        }
        // Post an assistant message about the received file
        uiScope.launch {
            val messageText = "I've received your file: $fileName."
            val botMsg = Message(
                conversationId = conversationId,
                sender = "bot",
                message = messageText,
                timestamp = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) { db.messageDao().insertMessage(botMsg) }
            messageList.add(botMsg)
            withContext(Dispatchers.Main) {
                messageAdapter.notifyItemInserted(messageList.size - 1)
                binding.messageRecyclerView.scrollToPosition(messageList.size - 1)
            }
            Toast.makeText(this@ChatActivity, "File received: $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Uploads file content with a progress callback.
     */
    private suspend fun uploadFileContent(fileUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(fileUri) ?: "uploaded_file"
            val inputStream = contentResolver.openInputStream(fileUri)
            if (inputStream == null) {
                Log.e("ChatActivity", "Unable to open InputStream for file")
                return@withContext null
            }
            val fileBytes = inputStream.readBytes()
            inputStream.close()
            val originalRequestBody = fileBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            val progressRequestBody = ProgressRequestBody(originalRequestBody, object : ProgressListener {
                override fun onProgressUpdate(percentage: Int) {
                    uiScope.launch(Dispatchers.Main) {
                        binding.progressBarUpload.progress = percentage
                    }
                }
            })
            val multipartPart = MultipartBody.Part.createFormData("file", fileName, progressRequestBody)
            val config = db.apiConfigDao().getConfig() ?: return@withContext null
            val authHeader = "Bearer " + config.openaiApiKey
            val purposeBody = "assistants".toRequestBody("text/plain".toMediaTypeOrNull())
            val response: Response<UploadFileResponse> = api.uploadFile(authHeader, multipartPart, purposeBody)
            if (response.isSuccessful) {
                response.body()?.id
            } else {
                Log.e("ChatActivity", "Error uploading file: HTTP ${response.code()}")
                response.errorBody()?.close()
                null
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Exception uploading file: ${e.message}")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@ChatActivity,
                    "We couldn't upload your file. Please check your connection and try again.",
                    Toast.LENGTH_LONG
                ).show()
            }
            null
        }
    }

    /**
     * Pretend to wait for the file to become ready.
     */
    private suspend fun waitForFileToBeReady(fileId: String) {
        delay(5000)
        Log.d("ChatActivity", "File $fileId is now ready.")
        runOnUiThread {
            Toast.makeText(this, "File is ready to be registered.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Registers the file with the vector store.
     */
    private suspend fun registerFileToVectorStore(validFileId: String): Boolean {
        val config = withContext(Dispatchers.IO) { db.apiConfigDao().getConfig() }
        if (config == null) {
            runOnUiThread {
                Toast.makeText(
                    this@ChatActivity,
                    "API config missing. Please configure it in Settings.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }
        val authHeader = "Bearer " + config.openaiApiKey
        val vectorStoreId = config.vectorstoreId
        val request = VectorStoreFileRequest(file_id = validFileId)
        return try {
            val response: Response<VectorStoreFileResponse> = withContext(Dispatchers.IO) {
                api.createVectorStoreFile(vectorStoreId, authHeader, request)
            }
            if (response.isSuccessful) {
                runOnUiThread {
                    Toast.makeText(this@ChatActivity, "File attached successfully!", Toast.LENGTH_LONG).show()
                }
                true
            } else {
                Log.e("ChatActivity", "Error registering file: HTTP ${response.code()}")
                response.errorBody()?.close()
                runOnUiThread {
                    Toast.makeText(
                        this@ChatActivity,
                        "Unable to attach the file right now. Please try again later.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                false
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Exception registering file: ${e.message}")
            runOnUiThread {
                Toast.makeText(
                    this@ChatActivity,
                    "We had trouble attaching the file. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
            }
            false
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = it.getString(index)
                }
            }
        }
        return result
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        } else {
            Toast.makeText(
                this,
                "Could not initialize text-to-speech. Please try again.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        activityJob.cancel()
        uiScope.cancel()
    }
}

package com.jarvis.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.ai.api.OpenAIService
import com.jarvis.ai.automation.ActionExecutor
import com.jarvis.ai.automation.JarvisAccessibilityService
import com.jarvis.ai.data.ChatCompletionRequest
import com.jarvis.ai.data.ChatMessage
import com.jarvis.ai.data.ConfigManager
import com.jarvis.ai.service.JarvisForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var configManager: ConfigManager
    private lateinit var openAIService: OpenAIService
    private lateinit var actionExecutor: ActionExecutor
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configManager = ConfigManager(this)
        openAIService = OpenAIService(configManager)
        actionExecutor = ActionExecutor(this)
        tts = TextToSpeech(this, this)

        // Start Foreground Service
        startForegroundService(Intent(this, JarvisForegroundService::class.java))

        setContent {
            JarvisTheme {
                MainAppScreen(
                    configManager = configManager,
                    openAIService = openAIService,
                    onSendMessage = { userText, onResult ->
                        sendChatMessage(userText, onResult)
                    }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    private fun speakOut(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JarvisTTS")
    }

    private fun sendChatMessage(userText: String, onResult: (String, String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val messages = listOf(
                ChatMessage("system", configManager.systemPrompt),
                ChatMessage("user", userText)
            )
            val request = ChatCompletionRequest(
                model = configManager.modelName.trim(),
                messages = messages
            )

            val result = openAIService.sendChatCompletion(request)

            withContext(Dispatchers.Main) {
                result.onSuccess { response ->
                    val aiReply = response.choices?.firstOrNull()?.message?.content
                        ?: "No response from AI server."

                    // Execute action if JSON command present
                    val spokenResponse = actionExecutor.parseAndExecute(aiReply)
                    speakOut(spokenResponse)
                    onResult(aiReply, spokenResponse)
                }.onFailure { error ->
                    val errorMsg = "Error: ${error.message}"
                    speakOut("Encountered an API error.")
                    onResult(errorMsg, errorMsg)
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E5FF),
            secondary = Color(0xFF7C4DFF),
            background = Color(0xFF0B0E14),
            surface = Color(0xFF161B22),
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun MainAppScreen(
    configManager: ConfigManager,
    openAIService: OpenAIService,
    onSendMessage: (String, (String, String) -> Unit) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Jarvis Assistant") },
                    icon = { Text("🤖") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("AI Settings") },
                    icon = { Text("⚙️") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedTab == 0) {
                ChatScreen(onSendMessage = onSendMessage)
            } else {
                SettingsScreen(configManager = configManager, openAIService = openAIService)
            }
        }
    }
}

@Composable
fun ChatScreen(onSendMessage: (String, (String, String) -> Unit) -> Unit) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<Pair<String, String>>() }
    var isLoading by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var speechStatusText by remember { mutableStateOf("") }

    val isAccessibilityActive = JarvisAccessibilityService.isRunning()

    // Permission launcher for Microphone
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Microphone permission is required for voice commands!", Toast.LENGTH_SHORT).show()
        }
    }

    // Speech Recognizer setup
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }

    fun startListening() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (speechRecognizer == null) {
            Toast.makeText(context, "Speech recognition is not available on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                speechStatusText = "Listening... Speak now!"
            }

            override fun onBeginningOfSpeech() {
                speechStatusText = "Recording audio..."
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
                speechStatusText = "Processing speech..."
            }

            override fun onError(error: Int) {
                isListening = false
                speechStatusText = "Voice error ($error). Try again."
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                speechStatusText = ""
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    messages.add(Pair("User", recognizedText))
                    isLoading = true

                    onSendMessage(recognizedText) { rawResult, spoken ->
                        isLoading = false
                        messages.add(Pair("Jarvis", spoken))
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(intent)
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Service Banner Status
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isAccessibilityActive) Color(0xFF1B5E20) else Color(0xFFB71C1C)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isAccessibilityActive) "● Automation Active" else "● Accessibility Disabled",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                if (!isAccessibilityActive) {
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) {
                        Text("Enable", color = Color.Black)
                    }
                }
            }
        }

        // Chat Log
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = false
        ) {
            items(messages) { msg ->
                val isUser = msg.first == "User"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Surface(
                        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = msg.second,
                            modifier = Modifier.padding(12.dp),
                            color = if (isUser) Color.Black else Color.White
                        )
                    }
                }
            }
        }

        if (speechStatusText.isNotEmpty()) {
            Text(
                text = speechStatusText,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input Box with Voice Mic Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Voice Mic Button
            IconButton(
                onClick = { startListening() },
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (isListening) Color.Red else MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            ) {
                Text(if (isListening) "🎙️..." else "🎙️", fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Talk or type to Jarvis...") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        val textToSend = inputText
                        inputText = ""
                        messages.add(Pair("User", textToSend))
                        isLoading = true

                        onSendMessage(textToSend) { rawResult, spoken ->
                            isLoading = false
                            messages.add(Pair("Jarvis", spoken))
                        }
                    }
                },
                enabled = !isLoading
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun SettingsScreen(configManager: ConfigManager, openAIService: OpenAIService) {
    val context = LocalContext.current

    var baseUrl by remember { mutableStateOf(configManager.baseUrl) }
    var apiKey by remember { mutableStateOf(configManager.apiKey) }
    var modelName by remember { mutableStateOf(configManager.modelName) }
    var systemPrompt by remember { mutableStateOf(configManager.systemPrompt) }

    var testResult by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            "Custom OpenAI Configuration",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 1-Click Quick Presets
        Text("Quick Provider Presets:", fontSize = 12.sp, color = Color.Gray)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FilterChip(
                selected = false,
                onClick = {
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
                    modelName = "gemini-1.5-flash"
                },
                label = { Text("Gemini") }
            )
            FilterChip(
                selected = false,
                onClick = {
                    baseUrl = "https://api.groq.com/openai/v1/"
                    modelName = "llama-3.1-70b-versatile"
                },
                label = { Text("Groq") }
            )
            FilterChip(
                selected = false,
                onClick = {
                    baseUrl = "https://api.openai.com/v1/"
                    modelName = "gpt-4o"
                },
                label = { Text("OpenAI") }
            )
            FilterChip(
                selected = false,
                onClick = {
                    baseUrl = "https://openrouter.ai/api/v1/"
                    modelName = "meta-llama/llama-3.1-70b-instruct"
                },
                label = { Text("OpenRouter") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("OpenAI Compatible Base URL") },
            placeholder = { Text("https://generativelanguage.googleapis.com/v1beta/openai/") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            placeholder = { Text("Enter API Key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = modelName,
            onValueChange = { modelName = it },
            label = { Text("Model Name") },
            placeholder = { Text("gemini-1.5-flash, gpt-4o, llama-3.1-70b-versatile") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (testResult.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (testResult.startsWith("✓")) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = testResult,
                    color = Color.White,
                    modifier = Modifier.padding(8.dp),
                    fontSize = 12.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    // Save first
                    configManager.baseUrl = baseUrl.trim()
                    configManager.apiKey = apiKey.trim()
                    configManager.modelName = modelName.trim()
                    configManager.systemPrompt = systemPrompt

                    isTesting = true
                    testResult = "Testing connection..."

                    CoroutineScope(Dispatchers.IO).launch {
                        val result = openAIService.testConnection()
                        withContext(Dispatchers.Main) {
                            isTesting = false
                            result.onSuccess { reply ->
                                testResult = "✓ Connected successfully! AI: \"$reply\""
                            }.onFailure { err ->
                                testResult = "❌ Connection Failed:\n${err.message}"
                            }
                        }
                    }
                },
                enabled = !isTesting,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isTesting) "Testing..." else "Test Connection")
            }

            Button(
                onClick = {
                    configManager.baseUrl = baseUrl.trim()
                    configManager.apiKey = apiKey.trim()
                    configManager.modelName = modelName.trim()
                    configManager.systemPrompt = systemPrompt
                    Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save Settings")
            }
        }
    }
}

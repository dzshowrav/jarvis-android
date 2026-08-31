package com.jarvis.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.lifecycleScope
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
                model = configManager.modelName,
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
                    speakOut("Sorry sir, encountered an error.")
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
                SettingsScreen(configManager = configManager)
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

    val isAccessibilityActive = JarvisAccessibilityService.isRunning()

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

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input Box
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ask Jarvis to do anything...") },
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
fun SettingsScreen(configManager: ConfigManager) {
    val context = LocalContext.current

    var baseUrl by remember { mutableStateOf(configManager.baseUrl) }
    var apiKey by remember { mutableStateOf(configManager.apiKey) }
    var modelName by remember { mutableStateOf(configManager.modelName) }
    var systemPrompt by remember { mutableStateOf(configManager.systemPrompt) }

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
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("OpenAI Compatible Base URL") },
            placeholder = { Text("https://api.openai.com/v1/ or custom endpoint") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = modelName,
            onValueChange = { modelName = it },
            label = { Text("Model Name") },
            placeholder = { Text("gpt-4o, llama3-70b-8192, deepseek-chat") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("System Prompt & Action Schema") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                configManager.baseUrl = baseUrl
                configManager.apiKey = apiKey
                configManager.modelName = modelName
                configManager.systemPrompt = systemPrompt
                Toast.makeText(context, "Settings saved successfully!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Configuration")
        }
    }
}

package com.jarvis.ai.data

import android.content.Context
import android.content.SharedPreferences

class ConfigManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_config_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BASE_URL = "openai_base_url"
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_MODEL_NAME = "openai_model_name"
        private const val KEY_SYSTEM_PROMPT = "openai_system_prompt"

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1/"
        const val DEFAULT_MODEL = "gpt-4o"
        const val DEFAULT_SYSTEM_PROMPT = """You are Jarvis, an advanced AI smartphone assistant. 
You have full access to execute actions on the user's phone via JSON command responses.
When requested to perform a phone action, respond ONLY with a JSON block in the following format:
```json
{
  "action": "OPEN_APP | CLICK_TEXT | TYPE_TEXT | GLOBAL_HOME | GLOBAL_BACK | MAKE_CALL",
  "data": {
    "package_name": "com.whatsapp",
    "text": "Target button text or message content",
    "phone_number": "+1234567890"
  },
  "speech_response": "Opening WhatsApp for you, sir."
}
```
If no phone action is required, reply normally with text."""
    }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            val formatted = if (value.endsWith("/")) value else "$value/"
            prefs.edit().putString(KEY_BASE_URL, formatted).apply()
        }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var modelName: String
        get() = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL_NAME, value).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()
}

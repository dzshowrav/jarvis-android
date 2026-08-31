package com.jarvis.ai.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.jarvis.ai.data.JarvisActionCommand

class ActionExecutor(private val context: Context) {

    private val gson = Gson()

    fun parseAndExecute(aiResponseText: String): String {
        try {
            // Check if AI output contains a JSON block
            val jsonStart = aiResponseText.indexOf("{")
            val jsonEnd = aiResponseText.lastIndexOf("}")

            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonString = aiResponseText.substring(jsonStart, jsonEnd + 1)
                val actionCmd = gson.fromJson(jsonString, JarvisActionCommand::class.java)

                if (actionCmd.action != null) {
                    val resultSuccess = executeCommand(actionCmd)
                    val statusText = if (resultSuccess) "Action executed successfully." else "Failed to execute action."
                    val spokenText = actionCmd.speechResponse ?: statusText
                    return spokenText
                }
            }
        } catch (e: Exception) {
            Log.e("ActionExecutor", "Error parsing or executing JSON action", e)
        }

        // Return standard AI text response if no JSON action found
        return aiResponseText
    }

    private fun executeCommand(cmd: JarvisActionCommand): Boolean {
        val accessibility = JarvisAccessibilityService.instance

        return when (cmd.action?.uppercase()) {
            "OPEN_APP" -> {
                val packageName = cmd.data?.get("package_name") ?: return false
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                } else false
            }

            "CLICK_TEXT" -> {
                val text = cmd.data?.get("text") ?: return false
                accessibility?.clickText(text) ?: false
            }

            "TYPE_TEXT" -> {
                val text = cmd.data?.get("text") ?: return false
                accessibility?.typeTextIntoActiveField(text) ?: false
            }

            "GLOBAL_HOME" -> {
                accessibility?.pressHome() ?: false
            }

            "GLOBAL_BACK" -> {
                accessibility?.pressBack() ?: false
            }

            "MAKE_CALL" -> {
                val phoneNumber = cmd.data?.get("phone_number") ?: return false
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    true
                } catch (e: Exception) {
                    false
                }
            }

            else -> false
        }
    }
}

package com.phoneagent.api

import com.phoneagent.data.ConversationMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class KimiResponse(
    val screen: String,
    val action: String,
    val target: String,
    val text: String?,
    val packageName: String?,
    val reason: String,
    val isComplete: Boolean,
    val rawContent: String,
    val thinkingContent: String = ""
)

class KimiApiClient(private val memory: ConversationMemory) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
You are PhoneAgent, an autonomous AI agent running on an Android phone. You can see the phone screen through screenshots and control the phone by deciding what actions to take. Your job is to complete tasks given by the user by analyzing the screen and taking precise actions one step at a time.

Always respond in EXACTLY this format with no deviation:
SCREEN: [what is currently visible on screen]
ACTION: [one of: tap, type, scroll_up, scroll_down, swipe_left, swipe_right, open_app, press_back, press_home, done, failed]
TARGET: [description of the exact UI element to interact with, or coordinates like (540, 1200)]
TEXT: [text to type if action is type, otherwise null]
PACKAGE: [app package name if action is open_app, otherwise null]
REASON: [why this action is being taken]
COMPLETE: [yes or no]

Be precise about which element to tap. If you cannot see the element needed, scroll to find it. If a task is impossible, say so clearly with ACTION: failed. Think carefully before each action. You have memory of past conversations and user preferences. You are helpful, efficient, and honest.

For coordinates, estimate them based on typical Android screen layout (1080x2340 or similar). Common positions:
- Status bar: top 50px
- Navigation bar: bottom 100px  
- Center of screen: approximately (540, 1170)
""".trimIndent()

    suspend fun sendMessage(
        userMessage: String,
        screenshotBase64: String?,
        apiKey: String,
        modelId: String = "moonshotai/kimi-k2.5",
        thinkingEnabled: Boolean = true
    ): Result<KimiResponse> = withContext(Dispatchers.IO) {
        try {
            val history = memory.getFormattedHistory()

            val messages = JSONArray()

            // System message
            val systemMsg = JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            }
            messages.put(systemMsg)

            // History messages
            for (i in 0 until history.length()) {
                messages.put(history.getJSONObject(i))
            }

            // Current user message with optional screenshot
            val userContent = if (screenshotBase64 != null) {
                val contentArray = JSONArray()

                // Screenshot image
                val imageObj = JSONObject().apply {
                    put("type", "image_url")
                    val imageUrlObj = JSONObject().apply {
                        put("url", "data:image/png;base64,$screenshotBase64")
                    }
                    put("image_url", imageUrlObj)
                }
                contentArray.put(imageObj)

                // Text message
                val textObj = JSONObject().apply {
                    put("type", "text")
                    put("text", userMessage)
                }
                contentArray.put(textObj)

                contentArray
            } else {
                userMessage
            }

            val userMsg = JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            }
            messages.put(userMsg)

            // Build request body
            val requestBody = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("max_tokens", 2048)
                put("temperature", 1.0)
                put("stream", false)
                if (thinkingEnabled) {
                    val kwargs = JSONObject()
                    kwargs.put("thinking", true)
                    put("chat_template_kwargs", kwargs)
                }
            }

            val request = Request.Builder()
                .url("https://integrate.api.nvidia.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty response from API")
            )

            if (!response.isSuccessful) {
                val errorJson = try {
                    JSONObject(responseBody)
                } catch (e: Exception) {
                    null
                }
                val errorMsg = errorJson?.optJSONObject("error")?.optString("message")
                    ?: "API error ${response.code}: $responseBody"
                return@withContext Result.failure(Exception(errorMsg))
            }

            val responseJson = JSONObject(responseBody)
            val choices = responseJson.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext Result.failure(Exception("No choices in response"))
            }

            val firstChoice = choices.getJSONObject(0)
            val messageObj = firstChoice.optJSONObject("message")
                ?: return@withContext Result.failure(Exception("No message in response"))

            val rawContent = messageObj.optString("content", "")
            val thinkingContent = extractThinkingContent(rawContent)
            val cleanContent = removeThinkingContent(rawContent)

            val parsed = parseStructuredResponse(cleanContent)
            Result.success(parsed.copy(thinkingContent = thinkingContent, rawContent = rawContent))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractThinkingContent(content: String): String {
        val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        return thinkRegex.find(content)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun removeThinkingContent(content: String): String {
        return content.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
    }

    private fun parseStructuredResponse(content: String): KimiResponse {
        fun extractField(fieldName: String): String {
            val lines = content.lines()
            for (line in lines) {
                if (line.startsWith("$fieldName:")) {
                    return line.removePrefix("$fieldName:").trim()
                }
            }
            return ""
        }

        val screen = extractField("SCREEN").ifEmpty { "Unable to parse screen description" }
        val action = extractField("ACTION").lowercase().trim().ifEmpty { "failed" }
        val target = extractField("TARGET").ifEmpty { "" }
        val textRaw = extractField("TEXT")
        val text = if (textRaw.equals("null", ignoreCase = true) || textRaw.isEmpty()) null else textRaw
        val packageRaw = extractField("PACKAGE")
        val packageName = if (packageRaw.equals("null", ignoreCase = true) || packageRaw.isEmpty()) null else packageRaw
        val reason = extractField("REASON").ifEmpty { "No reason provided" }
        val completeRaw = extractField("COMPLETE")
        val isComplete = completeRaw.equals("yes", ignoreCase = true)

        return KimiResponse(
            screen = screen,
            action = action,
            target = target,
            text = text,
            packageName = packageName,
            reason = reason,
            isComplete = isComplete,
            rawContent = content
        )
    }
}

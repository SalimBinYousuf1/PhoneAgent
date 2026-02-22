package com.phoneagent

import android.util.Log
import com.phoneagent.api.KimiApiClient
import com.phoneagent.api.KimiResponse
import com.phoneagent.data.ConversationMemory
import com.phoneagent.services.AgentAccessibilityService
import com.phoneagent.services.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class StepUpdate(
    val stepNumber: Int,
    val maxSteps: Int,
    val screen: String,
    val action: String,
    val target: String,
    val reason: String,
    val thinking: String,
    val isComplete: Boolean,
    val isFailed: Boolean,
    val message: String
)

class AgentLoop(
    private val memory: ConversationMemory,
    private val apiClient: KimiApiClient
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val STEP_DELAY_MS = 1500L
    }

    var isCancelled = false

    suspend fun execute(
        command: String,
        apiKey: String,
        maxSteps: Int = 15,
        thinkingEnabled: Boolean = true,
        onStepUpdate: (StepUpdate) -> Unit
    ): String = withContext(Dispatchers.IO) {
        isCancelled = false
        var finalResult = ""
        var taskId = -1L

        try {
            taskId = memory.saveTask(command, "running", 0, "")
            memory.saveMessage("user", command)

            for (step in 1..maxSteps) {
                if (isCancelled) {
                    finalResult = "Task cancelled by user after $step steps."
                    break
                }

                // Take screenshot
                val screenshot = ScreenCaptureService.instance?.captureScreenshot()
                Log.d(TAG, "Step $step: screenshot ${if (screenshot != null) "captured (${screenshot.length} chars)" else "failed"}")

                // Build context-enriched message for first step
                val contextMessage = if (step == 1) {
                    buildContextMessage(command)
                } else {
                    "Continue with the task. Step $step of $maxSteps."
                }

                // Call Kimi
                val result = apiClient.sendMessage(
                    userMessage = contextMessage,
                    screenshotBase64 = screenshot,
                    apiKey = apiKey,
                    thinkingEnabled = thinkingEnabled
                )

                if (result.isFailure) {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    val errorMsg = "API error at step $step: $error"
                    onStepUpdate(StepUpdate(
                        stepNumber = step, maxSteps = maxSteps,
                        screen = "Error", action = "failed", target = "", reason = errorMsg,
                        thinking = "", isComplete = false, isFailed = true, message = errorMsg
                    ))
                    finalResult = errorMsg
                    break
                }

                val response = result.getOrNull()!!
                Log.d(TAG, "Step $step: action=${response.action}, target=${response.target}")

                // Save assistant response
                memory.saveMessage("assistant", response.rawContent)

                // Emit step update
                val stepUpdate = StepUpdate(
                    stepNumber = step,
                    maxSteps = maxSteps,
                    screen = response.screen,
                    action = response.action,
                    target = response.target,
                    reason = response.reason,
                    thinking = response.thinkingContent,
                    isComplete = response.isComplete || response.action == "done",
                    isFailed = response.action == "failed",
                    message = formatStepMessage(step, response)
                )
                onStepUpdate(stepUpdate)

                // Check completion
                if (response.isComplete || response.action == "done") {
                    finalResult = "✅ Task completed successfully.\n${response.reason}"
                    break
                }

                if (response.action == "failed") {
                    finalResult = "❌ Task failed: ${response.reason}"
                    break
                }

                // Execute action
                executeAction(response)

                // Wait for screen to update
                delay(STEP_DELAY_MS)

                // Check if this was the last step
                if (step == maxSteps) {
                    finalResult = "⚠️ Reached maximum steps ($maxSteps). Task may be incomplete."
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "AgentLoop error", e)
            finalResult = "❌ Error during task execution: ${e.message}"
        }

        // Save final result
        if (taskId > 0) {
            try {
                val db = AppController.getDatabase()
                val tasks = db.taskDao().getRecent()
                val task = tasks.firstOrNull { it.id == taskId }
                if (task != null) {
                    db.taskDao().update(
                        task.copy(
                            status = if (finalResult.startsWith("✅")) "completed" else "failed",
                            result = finalResult
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update task status", e)
            }
        }

        memory.saveMessage("assistant", finalResult)
        finalResult
    }

    private suspend fun buildContextMessage(command: String): String {
        val prefs = memory.getAllPreferences()
        val notifications = memory.getRecentNotificationsFormatted()
        val prefsStr = if (prefs.isNotEmpty()) {
            "User preferences: ${prefs.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        } else ""

        return buildString {
            appendLine("Task: $command")
            if (prefsStr.isNotEmpty()) appendLine(prefsStr)
            appendLine(notifications)
            appendLine("Please analyze the screenshot and take the first action to complete this task.")
        }
    }

    private suspend fun executeAction(response: KimiResponse) {
        val accessibility = AgentAccessibilityService.instance
        if (accessibility == null) {
            Log.w(TAG, "Accessibility service not available for action: ${response.action}")
            return
        }

        try {
            when (response.action.lowercase()) {
                "tap" -> {
                    val target = response.target
                    // Check if it's coordinates like (540, 1200) or just text
                    val coordRegex = Regex("""[(\[]?(\d+)[,\s]+(\d+)[)\]]?""")
                    val match = coordRegex.find(target)
                    if (match != null) {
                        val x = match.groupValues[1].toFloat()
                        val y = match.groupValues[2].toFloat()
                        accessibility.tapAtCoordinates(x, y)
                    } else {
                        // Try tap by text first, then description
                        val tappedByText = accessibility.tapByText(target)
                        if (!tappedByText) {
                            accessibility.tapByDescription(target)
                        }
                    }
                }

                "type" -> {
                    val text = response.text ?: ""
                    if (text.isNotEmpty()) {
                        accessibility.typeText(text)
                    }
                }

                "scroll_down" -> accessibility.scrollDown()
                "scroll_up" -> accessibility.scrollUp()
                "swipe_left" -> accessibility.swipeLeft()
                "swipe_right" -> accessibility.swipeRight()

                "open_app" -> {
                    val pkg = response.packageName ?: ""
                    if (pkg.isNotEmpty()) {
                        accessibility.openApp(pkg)
                    } else {
                        // Try to open by app name in target
                        val appName = response.target
                        accessibility.tapByText(appName)
                    }
                }

                "press_back" -> accessibility.pressBack()
                "press_home" -> accessibility.pressHome()

                else -> Log.w(TAG, "Unknown action: ${response.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute action ${response.action}", e)
        }
    }

    private fun formatStepMessage(step: Int, response: KimiResponse): String {
        return buildString {
            appendLine("**Step $step** — ${response.action.uppercase()}")
            appendLine("📱 Screen: ${response.screen}")
            appendLine("🎯 Target: ${response.target}")
            appendLine("💡 Reason: ${response.reason}")
            if (!response.text.isNullOrEmpty()) {
                appendLine("⌨️ Typing: \"${response.text}\"")
            }
        }
    }

    fun cancel() {
        isCancelled = true
    }
}

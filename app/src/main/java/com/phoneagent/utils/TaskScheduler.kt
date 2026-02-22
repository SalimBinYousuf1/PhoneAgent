package com.phoneagent.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.phoneagent.AppController
import com.phoneagent.api.KimiApiClient
import com.phoneagent.data.ConversationMemory
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TaskScheduler(private val context: Context) {

    companion object {
        const val HEARTBEAT_WORK_TAG = "phoneagent_heartbeat"
        private const val TAG = "TaskScheduler"
    }

    fun scheduleHeartbeat(intervalMinutes: Long) {
        if (intervalMinutes <= 0) {
            cancelHeartbeat()
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            intervalMinutes, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES // flex interval
        )
            .setConstraints(constraints)
            .addTag(HEARTBEAT_WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEARTBEAT_WORK_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            heartbeatRequest
        )
        Log.d(TAG, "Heartbeat scheduled every $intervalMinutes minutes")
    }

    fun cancelHeartbeat() {
        WorkManager.getInstance(context).cancelAllWorkByTag(HEARTBEAT_WORK_TAG)
        Log.d(TAG, "Heartbeat cancelled")
    }
}

class HeartbeatWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val CHANNEL_ID = "phoneagent_heartbeat"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Heartbeat running")
            createNotificationChannel()

            val db = AppController.getDatabase()
            val memory = ConversationMemory(db)
            val prefs = applicationContext.getSharedPreferences("phoneagent_prefs", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("api_key", "") ?: ""

            if (apiKey.isEmpty()) {
                Log.d(TAG, "No API key, skipping heartbeat")
                return Result.success()
            }

            // Get scheduled tasks
            val scheduledTasks = db.scheduledTaskDao().getActive()
            val recentNotifications = memory.getRecentNotificationsFormatted()

            if (scheduledTasks.isEmpty() && recentNotifications.contains("No recent")) {
                return Result.success()
            }

            // Build context for Kimi
            val contextMessage = buildString {
                appendLine("HEARTBEAT CHECK - No user action needed, just check if anything needs attention.")
                appendLine()
                appendLine(recentNotifications)
                appendLine()
                if (scheduledTasks.isNotEmpty()) {
                    appendLine("Scheduled tasks to check:")
                    for (task in scheduledTasks) {
                        appendLine("- ${task.command} (cron: ${task.cronExpression}, last run: ${java.util.Date(task.lastRun)})")
                    }
                }
                appendLine()
                appendLine("If any scheduled task needs to run now or any notification requires attention, say ALERT: followed by a brief description. Otherwise say ALL_CLEAR.")
            }

            val apiClient = KimiApiClient(memory)
            val result = apiClient.sendMessage(
                userMessage = contextMessage,
                screenshotBase64 = null,
                apiKey = apiKey,
                thinkingEnabled = false
            )

            if (result.isSuccess) {
                val response = result.getOrNull()
                val content = response?.rawContent ?: ""
                if (content.contains("ALERT:", ignoreCase = true)) {
                    val alertText = content.substringAfter("ALERT:").trim().take(200)
                    showNotification("PhoneAgent Alert", alertText)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed", e)
            Result.retry()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PhoneAgent Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun showNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

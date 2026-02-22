package com.phoneagent.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, rescheduling heartbeat")
            val prefs = context.getSharedPreferences("phoneagent_prefs", Context.MODE_PRIVATE)
            val interval = prefs.getLong("heartbeat_interval", 30L)
            if (interval > 0) {
                TaskScheduler(context).scheduleHeartbeat(interval)
            }
        }
    }
}

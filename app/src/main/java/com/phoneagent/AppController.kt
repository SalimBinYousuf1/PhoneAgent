package com.phoneagent

import android.app.Application
import android.content.Context
import android.util.Log
import com.phoneagent.data.AppDatabase
import com.phoneagent.data.ConversationMemory

class AppController : Application() {

    companion object {
        private const val TAG = "AppController"
        private lateinit var instance: AppController

        fun getDatabase(): AppDatabase = AppDatabase.getInstance(instance)
        fun getMemory(): ConversationMemory = ConversationMemory(getDatabase())
        fun getContext(): Context = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "PhoneAgent started")
    }
}

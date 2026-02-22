package com.phoneagent.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.phoneagent.AgentLoop
import com.phoneagent.AppController
import com.phoneagent.R
import com.phoneagent.api.KimiApiClient
import com.phoneagent.data.ConversationMemory
import com.phoneagent.databinding.ActivityMainBinding
import com.phoneagent.services.ScreenCaptureService
import com.phoneagent.utils.PermissionManager
import com.phoneagent.utils.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var permissionManager: PermissionManager
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var memory: ConversationMemory
    private lateinit var agentLoop: AgentLoop
    private var isAgentRunning = false

    private val pulseAnimation = AlphaAnimation(1f, 0.2f).apply {
        duration = 600
        repeatMode = Animation.REVERSE
        repeatCount = Animation.INFINITE
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(serviceIntent)
            addSystemMessage("✅ Screen capture enabled")
        } else {
            addSystemMessage("⚠️ Screen capture denied - agent will use text-only mode")
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceInput()
        } else {
            Toast.makeText(this, "Microphone permission required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)
        memory = AppController.getMemory()
        voiceEngine = VoiceEngine(this)

        val apiClient = KimiApiClient(memory)
        agentLoop = AgentLoop(memory, apiClient)

        setupRecyclerView()
        setupClickListeners()
        checkAndRequestNotificationPermission()

        // Check if first launch
        val prefs = getSharedPreferences("phoneagent_prefs", Context.MODE_PRIVATE)
        val setupDone = prefs.getBoolean("setup_done", false)
        if (!setupDone || !permissionManager.isAccessibilityEnabled()) {
            startActivity(Intent(this, PermissionSetupActivity::class.java))
        } else {
            initScreenCapture()
        }

        addSystemMessage("👋 PhoneAgent ready. Type or speak a command to get started.")
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanners()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.rvChat.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).also {
                it.stackFromEnd = true
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etInput.setText("")
                executeCommand(text)
            }
        }

        binding.btnMic.setOnClickListener {
            if (isAgentRunning) return@setOnClickListener
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startVoiceInput()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnCancel.setOnClickListener {
            agentLoop.cancel()
            setAgentRunning(false)
            addSystemMessage("⏹ Task cancelled")
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.bannerFix.setOnClickListener {
            startActivity(permissionManager.getAccessibilitySettingsIntent())
        }
    }

    private fun startVoiceInput() {
        binding.btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
        addSystemMessage("🎙 Listening...")

        voiceEngine.startListening(
            onResult = { text ->
                runOnUiThread {
                    binding.btnMic.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    // Remove listening message
                    executeCommand(text)
                }
            },
            onError = { error ->
                runOnUiThread {
                    binding.btnMic.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    addSystemMessage("🎙 Voice error: $error")
                }
            }
        )
    }

    private fun executeCommand(command: String) {
        if (isAgentRunning) {
            Toast.makeText(this, "Agent is already running a task", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("phoneagent_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            addSystemMessage("❌ No API key configured. Go to Settings to add your NVIDIA API key.")
            return
        }

        // Add user message to UI
        chatAdapter.addMessage(ChatMessage(role = "user", content = command))
        scrollToBottom()

        setAgentRunning(true)

        val maxSteps = prefs.getInt("max_steps", 15)
        val thinkingEnabled = prefs.getBoolean("thinking_enabled", true)
        val voiceEnabled = prefs.getBoolean("voice_response", false)

        lifecycleScope.launch {
            var agentMessageId = -1L
            var currentContent = ""

            try {
                val result = agentLoop.execute(
                    command = command,
                    apiKey = apiKey,
                    maxSteps = maxSteps,
                    thinkingEnabled = thinkingEnabled
                ) { stepUpdate ->
                    runOnUiThread {
                        val stepMsg = "**Step ${stepUpdate.stepNumber}/${stepUpdate.maxSteps}**\n${stepUpdate.message}"

                        if (agentMessageId < 0) {
                            val msg = ChatMessage(
                                role = "agent",
                                content = stepMsg,
                                thinkingContent = stepUpdate.thinking
                            )
                            agentMessageId = msg.id
                            chatAdapter.addMessage(msg)
                        } else {
                            chatAdapter.updateLastMessage(stepMsg)
                        }

                        currentContent = stepMsg
                        binding.tvStepCounter.text = "Step ${stepUpdate.stepNumber}/${stepUpdate.maxSteps}"
                        scrollToBottom()

                        if (stepUpdate.isComplete || stepUpdate.isFailed) {
                            setAgentRunning(false)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    setAgentRunning(false)
                    val finalMsg = ChatMessage(role = "agent", content = result)
                    chatAdapter.addMessage(finalMsg)
                    scrollToBottom()

                    if (voiceEnabled) {
                        voiceEngine.speak(result)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setAgentRunning(false)
                    addSystemMessage("❌ Error: ${e.message}")
                }
            }
        }
    }

    private fun setAgentRunning(running: Boolean) {
        isAgentRunning = running
        if (running) {
            binding.tvStatus.text = "Working"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_working))
            binding.tvStatus.startAnimation(pulseAnimation)
            binding.btnCancel.visibility = View.VISIBLE
            binding.tvStepCounter.visibility = View.VISIBLE
            binding.btnSend.isEnabled = false
        } else {
            binding.tvStatus.text = "Idle"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
            binding.tvStatus.clearAnimation()
            binding.btnCancel.visibility = View.GONE
            binding.tvStepCounter.visibility = View.GONE
            binding.btnSend.isEnabled = true
        }
    }

    private fun addSystemMessage(text: String) {
        chatAdapter.addMessage(ChatMessage(role = "system", content = text))
        scrollToBottom()
    }

    private fun scrollToBottom() {
        val count = chatAdapter.itemCount
        if (count > 0) {
            binding.rvChat.post {
                binding.rvChat.smoothScrollToPosition(count - 1)
            }
        }
    }

    private fun updatePermissionBanners() {
        if (!permissionManager.isAccessibilityEnabled()) {
            binding.bannerPermission.visibility = View.VISIBLE
            binding.tvBannerText.text = "⚠️ Accessibility Service disabled. Tap to fix."
        } else {
            binding.bannerPermission.visibility = View.GONE
        }
    }

    private fun initScreenCapture() {
        if (ScreenCaptureService.instance == null) {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine.destroy()
    }
}

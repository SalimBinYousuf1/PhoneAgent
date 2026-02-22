package com.phoneagent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.phoneagent.AppController
import com.phoneagent.R
import com.phoneagent.databinding.ActivitySettingsBinding
import com.phoneagent.utils.TaskScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var isApiKeyVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("phoneagent_prefs", Context.MODE_PRIVATE)

        binding.etApiKey.setText(prefs.getString("api_key", ""))
        binding.etModel.setText(prefs.getString("model_id", "moonshotai/kimi-k2.5"))
        binding.etAgentName.setText(prefs.getString("agent_name", "PhoneAgent"))
        binding.switchThinking.isChecked = prefs.getBoolean("thinking_enabled", true)
        binding.switchVoice.isChecked = prefs.getBoolean("voice_response", false)

        val maxSteps = prefs.getInt("max_steps", 15)
        binding.sliderMaxSteps.value = maxSteps.toFloat()
        binding.tvMaxStepsValue.text = maxSteps.toString()

        val heartbeatMinutes = prefs.getLong("heartbeat_interval", 30L).toInt()
        val heartbeatIndex = when (heartbeatMinutes) {
            15 -> 0
            30 -> 1
            60 -> 2
            else -> 3 // off
        }
        binding.spinnerHeartbeat.setSelection(heartbeatIndex)
    }

    private fun setupListeners() {
        binding.btnToggleApiKey.setOnClickListener {
            isApiKeyVisible = !isApiKeyVisible
            if (isApiKeyVisible) {
                binding.etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                binding.btnToggleApiKey.text = "HIDE"
            } else {
                binding.etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.btnToggleApiKey.text = "SHOW"
            }
            binding.etApiKey.setSelection(binding.etApiKey.text.length)
        }

        binding.sliderMaxSteps.addOnChangeListener { _, value, _ ->
            binding.tvMaxStepsValue.text = value.toInt().toString()
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        binding.btnClearMemory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All Memory")
                .setMessage("This will delete all conversations, tasks, preferences, and notification logs. Are you sure?")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        AppController.getMemory().clearAll()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsActivity, "Memory cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnExport.setOnClickListener {
            exportConversations()
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("phoneagent_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putString("api_key", binding.etApiKey.text.toString().trim())
        editor.putString("model_id", binding.etModel.text.toString().trim().ifEmpty { "moonshotai/kimi-k2.5" })
        editor.putString("agent_name", binding.etAgentName.text.toString().trim().ifEmpty { "PhoneAgent" })
        editor.putBoolean("thinking_enabled", binding.switchThinking.isChecked)
        editor.putBoolean("voice_response", binding.switchVoice.isChecked)
        editor.putInt("max_steps", binding.sliderMaxSteps.value.toInt())

        val heartbeatMinutes = when (binding.spinnerHeartbeat.selectedItemPosition) {
            0 -> 15L
            1 -> 30L
            2 -> 60L
            else -> 0L
        }
        editor.putLong("heartbeat_interval", heartbeatMinutes)
        editor.apply()

        // Update heartbeat schedule
        val scheduler = TaskScheduler(this)
        if (heartbeatMinutes > 0) {
            scheduler.scheduleHeartbeat(heartbeatMinutes)
        } else {
            scheduler.cancelHeartbeat()
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun exportConversations() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = AppController.getMemory().exportHistory()
                val dir = File(getExternalFilesDir(null), "exports")
                dir.mkdirs()
                val file = File(dir, "phoneagent_export_${System.currentTimeMillis()}.txt")
                FileWriter(file).use { it.write(content) }

                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        this@SettingsActivity,
                        "$packageName.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Export Conversations"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

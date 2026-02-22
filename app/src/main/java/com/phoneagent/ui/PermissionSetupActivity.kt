package com.phoneagent.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phoneagent.R
import com.phoneagent.databinding.ActivityPermissionSetupBinding
import com.phoneagent.services.ScreenCaptureService
import com.phoneagent.utils.PermissionManager

class PermissionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionSetupBinding
    private lateinit var permissionManager: PermissionManager

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) updateUI()
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
        }
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)

        binding.btnStep1.setOnClickListener {
            startActivity(permissionManager.getAccessibilitySettingsIntent())
        }

        binding.btnStep2.setOnClickListener {
            startActivity(permissionManager.getNotificationListenerIntent())
        }

        binding.btnStep3.setOnClickListener {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        binding.btnStep4.setOnClickListener {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                val prefs = getSharedPreferences("phoneagent_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("api_key", key).apply()
                updateUI()
            }
        }

        binding.btnContinue.setOnClickListener {
            val prefs = getSharedPreferences("phoneagent_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("setup_done", true).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val step1Done = permissionManager.isAccessibilityEnabled()
        val step2Done = permissionManager.isNotificationListenerEnabled()
        val step3Done = ScreenCaptureService.instance != null
        val step4Done = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val step5Done = permissionManager.hasApiKey()

        binding.check1.visibility = if (step1Done) View.VISIBLE else View.INVISIBLE
        binding.check2.visibility = if (step2Done) View.VISIBLE else View.INVISIBLE
        binding.check3.visibility = if (step3Done) View.VISIBLE else View.INVISIBLE
        binding.check4.visibility = if (step4Done) View.VISIBLE else View.INVISIBLE
        binding.check5.visibility = if (step5Done) View.VISIBLE else View.INVISIBLE

        // All critical permissions done (screen capture is optional)
        val allDone = step1Done && step4Done && step5Done
        binding.btnContinue.isEnabled = allDone
        binding.btnContinue.alpha = if (allDone) 1f else 0.5f
    }
}

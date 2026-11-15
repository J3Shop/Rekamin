package com.rekamin.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var recordButton: MaterialButton
    private lateinit var audioGroup: MaterialButtonToggleGroup
    private lateinit var prefs: SharedPreferences

    private val audioIds by lazy {
        mapOf(
            R.id.audioNone to "none",
            R.id.audioMic to "mic",
            R.id.audioInternal to "internal",
            R.id.audioBoth to "both"
        )
    }

    // Setelah izin mikrofon/notifikasi dijawab, lanjut ke langkah berikutnya.
    // Walau ditolak, rekaman tetap bisa jalan (tanpa suara bila mikrofon ditolak).
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            checkOverlayThenProject()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, RecorderService::class.java).apply {
                    action = RecorderService.ACTION_PREPARE
                    putExtra(RecorderService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(RecorderService.EXTRA_DATA, data)
                }
                ContextCompat.startForegroundService(this, intent)
                // Pindah ke latar belakang supaya pengguna bisa langsung membuka game.
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, R.string.toast_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        statusText = findViewById(R.id.statusText)
        summaryText = findViewById(R.id.summaryText)
        recordButton = findViewById(R.id.recordButton)
        audioGroup = findViewById(R.id.audioGroup)

        recordButton.setOnClickListener { onRecordClick() }
        findViewById<MaterialButton>(R.id.videosButton).setOnClickListener {
            startActivity(Intent(this, VideosActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        audioGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                audioIds[checkedId]?.let { prefs.edit().putString(Keys.AUDIO, it).apply() }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecorderService.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Sinkronkan dengan perubahan dari layar Pengaturan.
        val current = prefs.getString(Keys.AUDIO, "internal")
        audioIds.entries.firstOrNull { it.value == current }?.let { audioGroup.check(it.key) }
        summaryText.text = RecorderConfig.load(this).summary(this)
    }

    private fun render(state: RecState) {
        when (state) {
            RecState.IDLE -> {
                statusText.setText(R.string.status_idle)
                recordButton.setText(R.string.btn_record)
            }
            RecState.READY -> {
                statusText.setText(R.string.status_ready)
                recordButton.setText(R.string.btn_cancel)
            }
            RecState.COUNTDOWN -> {
                statusText.setText(R.string.status_countdown)
                recordButton.setText(R.string.btn_cancel)
            }
            RecState.RECORDING -> {
                statusText.setText(R.string.status_recording)
                recordButton.setText(R.string.btn_stop)
            }
            RecState.PAUSED -> {
                statusText.setText(R.string.status_paused)
                recordButton.setText(R.string.btn_stop)
            }
        }
        val enabled = state == RecState.IDLE
        for (i in 0 until audioGroup.childCount) audioGroup.getChildAt(i).isEnabled = enabled
    }

    private fun onRecordClick() {
        if (RecorderService.state.value == RecState.IDLE) {
            beginFlow()
        } else {
            startService(
                Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
            )
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun beginFlow() {
        val cfg = RecorderConfig.load(this)
        val needed = mutableListOf<String>()
        if (cfg.audio != AudioMode.NONE && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) checkOverlayThenProject() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun checkOverlayThenProject() {
        val cfg = RecorderConfig.load(this)
        if (cfg.floating && !Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.overlay_title)
                .setMessage(R.string.overlay_msg)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton(R.string.continue_without) { _, _ -> askScreenCapture() }
                .show()
        } else {
            askScreenCapture()
        }
    }

    private fun askScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

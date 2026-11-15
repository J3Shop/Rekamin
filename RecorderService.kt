package com.rekamin.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecState { IDLE, READY, COUNTDOWN, RECORDING, PAUSED }

/**
 * Foreground service yang mengatur siklus rekaman:
 * IDLE -> READY (menunggu ketukan tombol mengambang) -> COUNTDOWN -> RECORDING <-> PAUSED -> IDLE.
 */
class RecorderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var config: RecorderConfig? = null
    private var projection: MediaProjection? = null
    private var engine: ScreenEngine? = null
    private var floating: FloatingUi? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> prepare(intent)
            ACTION_START_NOW -> beginCountdown()
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_STOP -> stopAll()
            else -> if (_state.value == RecState.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        floating?.destroy()
        floating = null
        _state.value = RecState.IDLE
        super.onDestroy()
    }

    // ---------------------------------------------------------------- persiapan

    private fun prepare(intent: Intent) {
        if (_state.value != RecState.IDLE) return
        finished = false

        var cfg = RecorderConfig.load(this)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null || resultCode != Activity.RESULT_OK) {
            stopSelf()
            return
        }

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (cfg.audio != AudioMode.NONE && !micGranted) {
            cfg = cfg.copy(audio = AudioMode.NONE)
            toast(getString(R.string.toast_no_audio_perm))
        }
        config = cfg

        createChannels()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (cfg.audio != AudioMode.NONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        _state.value = RecState.READY
        // Android 14+: service foreground harus aktif sebelum getMediaProjection().
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val proj = manager.getMediaProjection(resultCode, data)
            // Android 14+: callback wajib didaftarkan sebelum membuat VirtualDisplay.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { stopAll() }
                }
            }, handler)
            projection = proj
        } catch (e: Exception) {
            toast(getString(R.string.toast_failed, e.message ?: "-"))
            stopAll()
            return
        }

        floating = FloatingUi(
            this,
            onStart = { beginCountdown() },
            onTogglePause = { togglePause() },
            onStop = { stopAll() }
        )
        if (cfg.floating && Settings.canDrawOverlays(this)) {
            floating?.showBubble(FloatingUi.Mode.READY)
        } else {
            beginCountdown()
        }
    }

    // ---------------------------------------------------------------- hitung mundur

    private fun beginCountdown() {
        if (_state.value != RecState.READY) return
        val cfg = config ?: return
        floating?.hideBubble()
        if (cfg.countdownSec <= 0) {
            startRecordingNow()
            return
        }
        _state.value = RecState.COUNTDOWN
        updateNotification()

        var remaining = cfg.countdownSec
        val tick = object : Runnable {
            override fun run() {
                if (_state.value != RecState.COUNTDOWN) return
                if (remaining > 0) {
                    val shown = floating?.showCountdown(remaining) ?: false
                    if (!shown) toast(remaining.toString())
                    remaining--
                    handler.postDelayed(this, 1000)
                } else {
                    floating?.hideCountdown()
                    // Beri jeda singkat agar angka hitung mundur tidak ikut terekam.
                    handler.postDelayed({ startRecordingNow() }, 250)
                }
            }
        }
        handler.post(tick)
    }

    // ---------------------------------------------------------------- rekam

    private fun startRecordingNow() {
        if (finished) return
        val cfg = config ?: return
        val proj = projection ?: return
        try {
            val e = ScreenEngine(applicationContext, proj, cfg)
            e.start()
            engine = e
            e.audioNote?.let { toast(getString(R.string.toast_audio_fallback, it)) }
        } catch (ex: Exception) {
            toast(getString(R.string.toast_failed, ex.message ?: "-"))
            stopAll()
            return
        }
        _state.value = RecState.RECORDING
        if (!cfg.hideWhileRecording && cfg.floating && Settings.canDrawOverlays(this)) {
            floating?.showBubble(FloatingUi.Mode.RECORDING)
        }
        updateNotification()
        handler.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val st = _state.value
            if (st == RecState.RECORDING || st == RecState.PAUSED) {
                floating?.updateTime(engine?.elapsedMs() ?: 0L)
                handler.postDelayed(this, 500)
            }
        }
    }

    private fun togglePause() {
        val e = engine ?: return
        when (_state.value) {
            RecState.RECORDING -> {
                e.pause()
                _state.value = RecState.PAUSED
                floating?.setMode(FloatingUi.Mode.PAUSED)
            }
            RecState.PAUSED -> {
                e.resume()
                _state.value = RecState.RECORDING
                floating?.setMode(FloatingUi.Mode.RECORDING)
            }
            else -> return
        }
        updateNotification()
    }

    // ---------------------------------------------------------------- berhenti

    private fun stopAll() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        floating?.destroy()
        floating = null

        val e = engine
        engine = null
        if (e == null) {
            finish(null, ok = false, hadEngine = false)
            return
        }
        // Menutup file bisa memakan waktu: jalankan di thread terpisah agar tidak memblokir UI.
        Thread {
            val ok = e.stop()
            val uri = e.outputUri
            handler.post { finish(uri, ok, hadEngine = true) }
        }.start()
    }

    private fun finish(savedUri: Uri?, ok: Boolean, hadEngine: Boolean) {
        if (hadEngine) {
            toast(getString(if (ok) R.string.toast_saved else R.string.toast_too_short))
        }
        try { projection?.stop() } catch (ex: Exception) {}
        projection = null
        _state.value = RecState.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (ok && savedUri != null) notifySaved(savedUri)
        stopSelf()
    }

    // ---------------------------------------------------------------- notifikasi

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REC, getString(R.string.channel_rec), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SAVED, getString(R.string.channel_saved), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_REC)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        when (_state.value) {
            RecState.READY -> {
                b.setContentTitle(getString(R.string.notif_ready_title))
                b.setContentText(getString(R.string.notif_ready_text))
                b.addAction(0, getString(R.string.action_start), actionIntent(ACTION_START_NOW, 1))
                b.addAction(0, getString(R.string.action_cancel), actionIntent(ACTION_STOP, 2))
            }
            RecState.COUNTDOWN -> {
                b.setContentTitle(getString(R.string.notif_counting))
                b.addAction(0, getString(R.string.action_cancel), actionIntent(ACTION_STOP, 2))
            }
            RecState.RECORDING -> {
                val elapsed = engine?.elapsedMs() ?: 0L
                b.setContentTitle(getString(R.string.notif_recording))
                b.setUsesChronometer(true)
                b.setShowWhen(true)
                b.setWhen(System.currentTimeMillis() - elapsed)
                b.addAction(0, getString(R.string.action_pause), actionIntent(ACTION_TOGGLE_PAUSE, 3))
                b.addAction(0, getString(R.string.action_stop), actionIntent(ACTION_STOP, 2))
            }
            RecState.PAUSED -> {
                b.setContentTitle(getString(R.string.notif_paused))
                b.addAction(0, getString(R.string.action_resume), actionIntent(ACTION_TOGGLE_PAUSE, 3))
                b.addAction(0, getString(R.string.action_stop), actionIntent(ACTION_STOP, 2))
            }
            RecState.IDLE -> b.setContentTitle(getString(R.string.app_name))
        }
        return b.build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun notifySaved(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(
            this, 20, view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_SAVED)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(getString(R.string.saved_title))
            .setContentText(getString(R.string.saved_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_SAVED_ID, n)
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_PREPARE = "com.rekamin.app.PREPARE"
        const val ACTION_START_NOW = "com.rekamin.app.START_NOW"
        const val ACTION_TOGGLE_PAUSE = "com.rekamin.app.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.rekamin.app.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_REC = "recording"
        private const val CHANNEL_SAVED = "saved"
        private const val NOTIF_ID = 1
        private const val NOTIF_SAVED_ID = 2

        private val _state = MutableStateFlow(RecState.IDLE)
        val state: StateFlow<RecState> = _state.asStateFlow()
    }
}

package com.rekamin.app

import android.content.Context
import androidx.preference.PreferenceManager

object Keys {
    const val RESOLUTION = "resolution"
    const val FPS = "fps"
    const val BITRATE = "bitrate"
    const val AUDIO = "audio_source"
    const val ORIENTATION = "orientation"
    const val COUNTDOWN = "countdown"
    const val FLOATING = "floating"
    const val HIDE_WHILE_RECORDING = "hide_while_recording"
}

enum class AudioMode { NONE, MIC, INTERNAL, BOTH }

enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE }

data class RecorderConfig(
    val shortSide: Int,          // sisi terpendek dalam piksel; 0 = resolusi asli layar
    val fps: Int,
    val bitrateMbps: Int,
    val audio: AudioMode,
    val orientation: OrientationMode,
    val countdownSec: Int,
    val floating: Boolean,
    val hideWhileRecording: Boolean
) {
    fun summary(context: Context): String {
        val res = if (shortSide > 0) "${shortSide}p" else context.getString(R.string.res_native)
        return "$res · $fps fps · $bitrateMbps Mbps"
    }

    companion object {
        fun load(context: Context): RecorderConfig {
            val p = PreferenceManager.getDefaultSharedPreferences(context)
            return RecorderConfig(
                shortSide = p.getString(Keys.RESOLUTION, "1080")?.toIntOrNull() ?: 1080,
                fps = p.getString(Keys.FPS, "30")?.toIntOrNull() ?: 30,
                bitrateMbps = p.getString(Keys.BITRATE, "8")?.toIntOrNull() ?: 8,
                audio = when (p.getString(Keys.AUDIO, "internal")) {
                    "none" -> AudioMode.NONE
                    "mic" -> AudioMode.MIC
                    "both" -> AudioMode.BOTH
                    else -> AudioMode.INTERNAL
                },
                orientation = when (p.getString(Keys.ORIENTATION, "auto")) {
                    "portrait" -> OrientationMode.PORTRAIT
                    "landscape" -> OrientationMode.LANDSCAPE
                    else -> OrientationMode.AUTO
                },
                countdownSec = p.getString(Keys.COUNTDOWN, "3")?.toIntOrNull() ?: 3,
                floating = p.getBoolean(Keys.FLOATING, true),
                hideWhileRecording = p.getBoolean(Keys.HIDE_WHILE_RECORDING, true)
            )
        }
    }
}

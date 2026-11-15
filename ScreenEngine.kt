package com.rekamin.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * Mesin rekam: layar (MediaProjection -> encoder H.264) + suara game (AudioPlaybackCapture)
 * + mikrofon, digabung dan di-encode AAC, lalu ditulis ke MP4 lewat MediaMuxer.
 * Mendukung jeda/lanjut. Hasil disimpan di MediaStore (Movies/Rekamin).
 */
@SuppressLint("MissingPermission")
class ScreenEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val config: RecorderConfig
) {
    var outputUri: Uri? = null
        private set

    /** Berisi pesan bila suara gagal disiapkan (video tetap direkam). */
    var audioNote: String? = null
        private set

    private var pfd: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private var videoEnc: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var audioEnc: MediaCodec? = null
    private var internalRec: AudioRecord? = null
    private var micRec: AudioRecord? = null

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var micThread: Thread? = null

    private val lock = ReentrantLock()
    private val muxerReady = lock.newCondition()
    private var videoTrack = -1
    private var audioTrack = -1
    @Volatile private var muxerStarted = false
    @Volatile private var aborted = false
    @Volatile private var running = false
    @Volatile private var stopped = false
    private var expectAudio = false
    @Volatile private var videoFramesWritten = 0

    private var startUs = 0L
    private val pauseLock = Any()
    private val pauseIntervals = ArrayList<LongArray>()
    @Volatile private var paused = false
    @Volatile private var waitKeyFrame = false
    private var clockSkewUs = 0L
    private var skewChecked = false
    private var lastVideoPts = -1L

    private val audioInfo = MediaCodec.BufferInfo()
    private var lastAudioPts = -1L

    private fun nowUs() = System.nanoTime() / 1000

    // ------------------------------------------------------------------ mulai

    fun start() {
        try {
            startUs = nowUs()
            setupOutput()
            setupVideo()
            setupAudio()
            running = true
            videoThread = Thread({ videoLoop() }, "rekamin-video").also { it.start() }
            if (expectAudio) {
                audioThread = Thread({ audioLoop() }, "rekamin-audio").also { it.start() }
            }
        } catch (e: Exception) {
            running = false
            aborted = true
            releaseAll()
            outputUri?.let {
                try { context.contentResolver.delete(it, null, null) } catch (ignored: Exception) {}
            }
            outputUri = null
            throw e
        }
    }

    private fun setupOutput() {
        val name = "Rekamin_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Rekamin")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Tidak bisa membuat berkas")
        outputUri = uri
        val fd = resolver.openFileDescriptor(uri, "rw") ?: error("Tidak bisa membuka berkas")
        pfd = fd
        muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    // ------------------------------------------------------------------ video

    private fun realScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun computeSize(): Pair<Int, Int> {
        var (w, h) = realScreenSize()
        when (config.orientation) {
            OrientationMode.PORTRAIT -> if (w > h) { val t = w; w = h; h = t }
            OrientationMode.LANDSCAPE -> if (h > w) { val t = w; w = h; h = t }
            OrientationMode.AUTO -> {}
        }
        if (config.shortSide > 0) {
            val short = minOf(w, h)
            if (short > config.shortSide) {
                val s = config.shortSide.toFloat() / short
                w = (w * s).toInt()
                h = (h * s).toInt()
            }
        }
        return (w / 2 * 2) to (h / 2 * 2)
    }

    private fun setupVideo() {
        val (w, h) = computeSize()
        val dpi = context.resources.displayMetrics.densityDpi

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateMbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Batas fps ke encoder (best effort; diabaikan perangkat yang tidak mendukung).
            try { setFloat("max-fps-to-encoder", config.fps.toFloat()) } catch (ignored: Exception) {}
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEnc = enc
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        inputSurface = surface
        enc.start()

        virtualDisplay = projection.createVirtualDisplay(
            "Rekamin",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null, null
        )
    }

    private fun videoLoop() {
        val enc = videoEnc ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (aborted) break
                    continue
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onTrackFormat(true, enc.outputFormat)
                    continue
                }
                if (idx < 0) continue

                val buf = enc.getOutputBuffer(idx)
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (buf != null && info.size > 0 && !isConfig) {
                    handleVideoFrame(buf, info)
                }
                enc.releaseOutputBuffer(idx, false)
                if (eos) break
            }
        } catch (ignored: Exception) {
            // encoder dihentikan
        }
    }

    private fun handleVideoFrame(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val raw = info.presentationTimeUs
        if (!skewChecked) {
            skewChecked = true
            val d = raw - nowUs()
            clockSkewUs = if (abs(d) > 2_000_000L) d else 0L
        }
        val adj = adjustPts(raw - clockSkewUs)
        if (adj < 0) return // frame jatuh di periode jeda

        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (waitKeyFrame) {
            if (!key) return
            waitKeyFrame = false
        }
        var pts = adj
        if (pts <= lastVideoPts) pts = lastVideoPts + 1
        lastVideoPts = pts

        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        info.presentationTimeUs = pts
        writeSample(true, buf, info)
        videoFramesWritten++
    }

    /** Waktu frame dalam timeline rekaman (tanpa periode jeda), atau -1 bila frame ada di dalam jeda. */
    private fun adjustPts(ptsUs: Long): Long {
        synchronized(pauseLock) {
            var sub = 0L
            for (iv in pauseIntervals) {
                if (ptsUs < iv[0]) break
                if (ptsUs < iv[1]) return -1
                sub += iv[1] - iv[0]
            }
            return maxOf(0L, ptsUs - startUs - sub)
        }
    }

    // ------------------------------------------------------------------ jeda / lanjut

    fun pause() {
        if (paused || stopped) return
        synchronized(pauseLock) { pauseIntervals.add(longArrayOf(nowUs(), Long.MAX_VALUE)) }
        paused = true
    }

    fun resume() {
        if (!paused || stopped) return
        synchronized(pauseLock) {
            if (pauseIntervals.isNotEmpty()) pauseIntervals[pauseIntervals.size - 1][1] = nowUs()
        }
        paused = false
        // Frame setelah jeda harus dimulai dari keyframe agar video tidak rusak.
        waitKeyFrame = true
        try {
            videoEnc?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (ignored: Exception) {}
    }

    fun elapsedMs(): Long {
        val now = nowUs()
        synchronized(pauseLock) {
            var pausedUs = 0L
            for (iv in pauseIntervals) pausedUs += minOf(iv[1], now) - iv[0]
            return maxOf(0L, (now - startUs - pausedUs) / 1000)
        }
    }

    // ------------------------------------------------------------------ muxer

    private fun onTrackFormat(isVideo: Boolean, format: MediaFormat) {
        lock.withLock {
            val m = muxer ?: return
            val t = m.addTrack(format)
            if (isVideo) videoTrack = t else audioTrack = t
            if (videoTrack >= 0 && (!expectAudio || audioTrack >= 0)) {
                m.start()
                muxerStarted = true
                muxerReady.signalAll()
            }
        }
    }

    private fun writeSample(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        lock.withLock {
            while (!muxerStarted && !aborted) muxerReady.await(50, TimeUnit.MILLISECONDS)
            if (aborted || !muxerStarted) return
            val m = muxer ?: return
            m.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
        }
    }

    /** Dipanggil bila suara gagal di tengah jalan, supaya video tidak menunggu track audio selamanya. */
    private fun audioGaveUp() {
        lock.withLock {
            if (!expectAudio) return
            expectAudio = false
            val m = muxer
            if (m != null && !muxerStarted && videoTrack >= 0) {
                m.start()
                muxerStarted = true
                muxerReady.signalAll()
            }
        }
    }

    // ------------------------------------------------------------------ audio

    private fun setupAudio() {
        val mode = config.audio
        if (mode == AudioMode.NONE) return
        val wantInternal = mode == AudioMode.INTERNAL || mode == AudioMode.BOTH
        val wantMic = mode == AudioMode.MIC || mode == AudioMode.BOTH

        var internal: AudioRecord? = null
        var mic: AudioRecord? = null
        val problems = ArrayList<String>()
        if (wantInternal) {
            try { internal = buildInternalRecord() } catch (e: Exception) { problems += "suara game (${e.message})" }
        }
        if (wantMic) {
            try { mic = buildMicRecord() } catch (e: Exception) { problems += "mikrofon (${e.message})" }
        }
        if (internal == null && mic == null) {
            audioNote = problems.joinToString(", ")
            return
        }
        try {
            buildAudioEncoder()
        } catch (e: Exception) {
            internal?.release()
            mic?.release()
            audioNote = "encoder suara (${e.message})"
            return
        }
        internalRec = internal
        micRec = mic
        expectAudio = true
        if (problems.isNotEmpty()) audioNote = problems.joinToString(", ")
    }

    private fun buildInternalRecord(): AudioRecord {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(capture)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, 4096) * 4)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("tidak bisa diinisialisasi")
        }
        return rec
    }

    private fun buildMicRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 2048) * 4
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("tidak bisa diinisialisasi")
        }
        return rec
    }

    private fun buildAudioEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEnc = enc
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
    }

    private fun audioLoop() {
        val enc = audioEnc ?: return
        val internal = internalRec
        val mic = micRec
        val stereo = ShortArray(CHUNK_FRAMES * 2)
        val mono = ShortArray(CHUNK_FRAMES)
        val micTmp = ShortArray(CHUNK_FRAMES)
        val ring = if (internal != null && mic != null) ShortRing(SAMPLE_RATE * 2) else null

        var samplesWritten = 0L
        var basePtsUs = -1L
        try {
            internal?.startRecording()
            mic?.startRecording()
            if (ring != null && mic != null) {
                micThread = Thread({ micFeed(mic, ring) }, "rekamin-mic").also { it.start() }
            }

            while (running) {
                var frames = 0
                if (internal != null) {
                    val n = internal.read(stereo, 0, stereo.size)
                    if (n < 0) error("baca suara gagal ($n)")
                    if (n == 0) continue
                    frames = n / 2
                    if (ring != null) mixMic(stereo, frames, ring, micTmp)
                } else if (mic != null) {
                    val n = mic.read(mono, 0, mono.size)
                    if (n < 0) error("baca mikrofon gagal ($n)")
                    if (n == 0) continue
                    for (i in 0 until n) {
                        stereo[2 * i] = mono[i]
                        stereo[2 * i + 1] = mono[i]
                    }
                    frames = n
                }
                if (frames == 0) continue

                if (basePtsUs < 0) {
                    basePtsUs = maxOf(0L, nowUs() - frames * 1_000_000L / SAMPLE_RATE - startUs)
                }
                if (paused) {
                    ring?.clear()
                    continue
                }
                val pts = basePtsUs + samplesWritten * 1_000_000L / SAMPLE_RATE
                queueAudio(enc, stereo, frames * 2, pts, false)
                samplesWritten += frames
            }

            val endPts = maxOf(0L, basePtsUs) + samplesWritten * 1_000_000L / SAMPLE_RATE
            queueAudio(enc, stereo, 0, endPts, true)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                if (drainAudio(10_000)) break
            }
        } catch (ignored: Exception) {
            audioGaveUp()
        } finally {
            try { internal?.stop() } catch (ignored: Exception) {}
            try { mic?.stop() } catch (ignored: Exception) {}
        }
    }

    private fun micFeed(mic: AudioRecord, ring: ShortRing) {
        val buf = ShortArray(1024)
        while (running) {
            val n = mic.read(buf, 0, buf.size)
            if (n > 0) ring.write(buf, n) else if (n < 0) break
        }
    }

    private fun mixMic(stereo: ShortArray, frames: Int, ring: ShortRing, tmp: ShortArray) {
        val got = ring.read(tmp, frames)
        for (i in 0 until got) {
            val m = tmp[i].toInt()
            stereo[2 * i] = clamp16(stereo[2 * i] + m)
            stereo[2 * i + 1] = clamp16(stereo[2 * i + 1] + m)
        }
    }

    private fun clamp16(v: Int): Short = v.coerceIn(-32768, 32767).toShort()

    private fun queueAudio(enc: MediaCodec, data: ShortArray, shorts: Int, ptsUs: Long, eos: Boolean) {
        var sent = false
        var attempts = 0
        while (!sent && attempts < 200) {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val inBuf = enc.getInputBuffer(idx) ?: return
                inBuf.clear()
                inBuf.order(ByteOrder.LITTLE_ENDIAN)
                if (shorts > 0) inBuf.asShortBuffer().put(data, 0, shorts)
                enc.queueInputBuffer(
                    idx, 0, shorts * 2, ptsUs,
                    if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                )
                sent = true
            } else {
                drainAudio(0)
            }
            attempts++
        }
        drainAudio(0)
    }

    /** Mengambil hasil encoder AAC. Mengembalikan true bila sinyal akhir (EOS) sudah diterima. */
    private fun drainAudio(timeoutUs: Long): Boolean {
        val enc = audioEnc ?: return true
        while (true) {
            val idx = enc.dequeueOutputBuffer(audioInfo, timeoutUs)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onTrackFormat(false, enc.outputFormat)
                continue
            }
            if (idx < 0) continue

            val buf = enc.getOutputBuffer(idx)
            val isConfig = (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val eos = (audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            if (buf != null && audioInfo.size > 0 && !isConfig) {
                var pts = audioInfo.presentationTimeUs
                if (pts <= lastAudioPts) pts = lastAudioPts + 1
                lastAudioPts = pts
                audioInfo.presentationTimeUs = pts
                buf.position(audioInfo.offset)
                buf.limit(audioInfo.offset + audioInfo.size)
                writeSample(false, buf, audioInfo)
            }
            enc.releaseOutputBuffer(idx, false)
            if (eos) return true
        }
    }

    // ------------------------------------------------------------------ berhenti

    /** Menghentikan rekaman dan menyimpan berkas. Mengembalikan true bila video valid tersimpan. */
    fun stop(): Boolean {
        if (stopped) return false
        stopped = true
        running = false

        try { virtualDisplay?.release() } catch (ignored: Exception) {}
        virtualDisplay = null
        try { videoEnc?.signalEndOfInputStream() } catch (ignored: Exception) {}

        join(videoThread, 1500)
        join(audioThread, 1000)
        if (audioThread?.isAlive == true) {
            try { internalRec?.stop() } catch (ignored: Exception) {}
            try { micRec?.stop() } catch (ignored: Exception) {}
            join(audioThread, 700)
        }
        join(micThread, 300)

        aborted = true
        lock.withLock { muxerReady.signalAll() }

        var ok = false
        lock.withLock {
            try {
                if (muxerStarted && videoFramesWritten > 0) {
                    muxer?.stop()
                    ok = true
                }
            } catch (ignored: Exception) {}
        }
        releaseAll()

        val uri = outputUri
        if (uri != null) {
            try {
                if (ok) {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, done, null, null)
                } else {
                    context.contentResolver.delete(uri, null, null)
                    outputUri = null
                }
            } catch (ignored: Exception) {}
        }
        return ok
    }

    private fun join(t: Thread?, ms: Long) {
        try { t?.join(ms) } catch (ignored: InterruptedException) {}
    }

    private fun releaseAll() {
        try { virtualDisplay?.release() } catch (ignored: Exception) {}
        virtualDisplay = null
        try { videoEnc?.stop() } catch (ignored: Exception) {}
        try { videoEnc?.release() } catch (ignored: Exception) {}
        videoEnc = null
        try { inputSurface?.release() } catch (ignored: Exception) {}
        inputSurface = null
        try { audioEnc?.stop() } catch (ignored: Exception) {}
        try { audioEnc?.release() } catch (ignored: Exception) {}
        audioEnc = null
        try { internalRec?.release() } catch (ignored: Exception) {}
        internalRec = null
        try { micRec?.release() } catch (ignored: Exception) {}
        micRec = null
        try { muxer?.release() } catch (ignored: Exception) {}
        muxer = null
        try { pfd?.close() } catch (ignored: Exception) {}
        pfd = null
    }

    /** Buffer melingkar kecil untuk menampung sampel mikrofon sebelum dicampur dengan suara game. */
    private class ShortRing(private val cap: Int) {
        private val data = ShortArray(cap)
        private var head = 0
        private var size = 0

        @Synchronized
        fun write(src: ShortArray, len: Int) {
            for (i in 0 until len) {
                if (size == cap) {
                    head = (head + 1) % cap
                    size--
                }
                data[(head + size) % cap] = src[i]
                size++
            }
        }

        @Synchronized
        fun read(dst: ShortArray, len: Int): Int {
            val n = minOf(len, size)
            for (i in 0 until n) dst[i] = data[(head + i) % cap]
            head = (head + n) % cap
            size -= n
            return n
        }

        @Synchronized
        fun clear() {
            head = 0
            size = 0
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_FRAMES = 1024
    }
}

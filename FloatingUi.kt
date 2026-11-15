package com.rekamin.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Tombol mengambang (bubble) yang bisa digeser di atas game, plus angka hitung mundur.
 * Semua dibuat lewat kode agar tidak perlu layout XML tambahan.
 */
class FloatingUi(
    private val context: Context,
    private val onStart: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onStop: () -> Unit
) {
    enum class Mode { READY, RECORDING, PAUSED }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var panel: LinearLayout? = null
    private var pauseBtn: TextView? = null
    private var countdown: TextView? = null
    private var mode = Mode.READY
    private var lastX = 0
    private var lastY = 300

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun pill(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E61B1D22"))
            cornerRadius = dp(20).toFloat()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(6)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------ bubble

    fun showBubble(m: Mode) {
        mode = m
        if (root != null) {
            applyMode()
            return
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }

        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val l = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
        }
        val pnl = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val pause = pill(context.getString(R.string.action_pause)) { onTogglePause() }
        val stop = pill(context.getString(R.string.action_stop)) { onStop() }
        pnl.addView(pause)
        pnl.addView(stop)
        r.addView(l)
        r.addView(pnl)

        attachDrag(l, r, params)

        try {
            wm.addView(r, params)
        } catch (e: Exception) {
            return
        }
        root = r
        label = l
        panel = pnl
        pauseBtn = pause
        applyMode()
    }

    private fun applyMode() {
        val l = label ?: return
        when (mode) {
            Mode.READY -> {
                l.text = context.getString(R.string.bubble_rec)
                l.background = circle(Color.parseColor("#FF3B4E"))
                panel?.visibility = View.GONE
                root?.alpha = 1f
            }
            Mode.RECORDING -> {
                if (l.text.isNullOrEmpty() || l.text == context.getString(R.string.bubble_rec) ||
                    l.text == context.getString(R.string.bubble_paused)
                ) {
                    l.text = "00:00"
                }
                l.background = circle(Color.parseColor("#CCE53950"))
                pauseBtn?.text = context.getString(R.string.action_pause)
                root?.alpha = 0.85f
            }
            Mode.PAUSED -> {
                l.text = context.getString(R.string.bubble_paused)
                l.background = circle(Color.parseColor("#E6F59E0B"))
                pauseBtn?.text = context.getString(R.string.action_resume)
                root?.alpha = 0.95f
            }
        }
    }

    fun setMode(m: Mode) {
        mode = m
        applyMode()
    }

    fun updateTime(ms: Long) {
        if (mode != Mode.RECORDING) return
        val total = ms / 1000
        val text = String.format("%02d:%02d", total / 60, total % 60)
        label?.text = text
    }

    private fun onBubbleClick() {
        when (mode) {
            Mode.READY -> onStart()
            else -> panel?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
    }

    private fun attachDrag(handle: View, rootView: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        lastX = params.x
                        lastY = params.y
                        try { wm.updateViewLayout(rootView, params) } catch (ex: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleClick()
                    true
                }
                else -> false
            }
        }
    }

    fun hideBubble() {
        root?.let {
            try { wm.removeView(it) } catch (e: Exception) {}
        }
        root = null
        label = null
        panel = null
        pauseBtn = null
    }

    // ------------------------------------------------------------ hitung mundur

    /** Menampilkan angka hitung mundur di tengah layar. Mengembalikan false bila izin overlay tidak ada. */
    fun showCountdown(n: Int): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        var tv = countdown
        if (tv == null) {
            tv = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 96f
                typeface = Typeface.DEFAULT_BOLD
                minWidth = dp(180)
                minHeight = dp(180)
                background = circle(Color.parseColor("#99000000"))
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            try {
                wm.addView(tv, p)
            } catch (e: Exception) {
                return false
            }
            countdown = tv
        }
        tv.text = n.toString()
        return true
    }

    fun hideCountdown() {
        countdown?.let {
            try { wm.removeView(it) } catch (e: Exception) {}
        }
        countdown = null
    }

    fun destroy() {
        hideBubble()
        hideCountdown()
    }
}

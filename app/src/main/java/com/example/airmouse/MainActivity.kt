package com.example.airmouse

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.*

class MainActivity : Activity(), SensorEventListener {
    // Base Tuning
    private val pxPerRad = 1600f       // Base speed
    private val scrollPerRad = 45f     // Scroll speed
    private val deadzone = 0.012f      // Soft deadzone cutoff

    private var currentSensitivity = 1.0f

    // Smoothing & Acceleration State
    private var smoothGx = 0f
    private var smoothGz = 0f

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sm: SensorManager
    private lateinit var vibrator: Vibrator
    private lateinit var status: TextView
    private lateinit var connectView: LinearLayout
    private lateinit var padView: LinearLayout

    @Volatile private var connected = false
    @Volatile private var scrolling = false
    @Volatile private var paused = false
    private var dragging = false
    private var lastTs = 0L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager

        // Initialize Vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        val prefs = getSharedPreferences("air", MODE_PRIVATE)
        currentSensitivity = prefs.getFloat("sensitivity", 1.0f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#9EABB8"))
            gravity = Gravity.CENTER
            textSize = 14f
            text = "Enter your PC's IP address"
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(status)

        // --- CONNECT SCREEN ---
        connectView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val ipField = EditText(this).apply {
            hint = "192.168.x.x"
            setText(prefs.getString("ip", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#5A6572"))
            textSize = 20f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2028"))
                cornerRadius = dp(12).toFloat()
            }
        }
        val go = pad("Connect", Color.parseColor("#2563EB")).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(18) }
        }
        connectView.addView(ipField)
        connectView.addView(go)
        connectView.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        root.addView(connectView)

        // --- MOUSE SCREEN ---
        padView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        padView.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)

        // Hardware volume key hint banner
        val hintBanner = TextView(this).apply {
            text = "⚡ Tip: Vol Down = Left Click | Vol Up = Right Click"
            setTextColor(Color.parseColor("#60A5FA"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        padView.addView(hintBanner)

        // Touch Control Buttons
        val left = pad("Left Click (Tap)\nHold to Drag", Color.parseColor("#1E2430"))
        val right = pad("Right Click", Color.parseColor("#1E2430"))
        val scroll = pad("Hold + Tilt\n= Scroll", Color.parseColor("#1E2430"))
        val pause = pad("Hold = Freeze Cursor", Color.parseColor("#374151"))

        padView.addView(left, LinearLayout.LayoutParams(-1, 0, 2.6f).apply { bottomMargin = dp(8) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
        row.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4) })
        padView.addView(row, LinearLayout.LayoutParams(-1, 0, 1.2f).apply { bottomMargin = dp(8) })

        padView.addView(pause, LinearLayout.LayoutParams(-1, 0, 1.0f).apply { bottomMargin = dp(10) })

        // Sensitivity slider container
        val sliderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161A22"))
                cornerRadius = dp(12).toFloat()
            }
        }
        val sensLabel = TextView(this).apply {
            text = "Sensitivity: ${"%.1f".format(currentSensitivity)}x"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val sensBar = SeekBar(this).apply {
            max = 200 // 0.5x to 2.5x
            progress = ((currentSensitivity - 0.5f) / 2.0f * 200).toInt().coerceIn(0, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    currentSensitivity = 0.5f + (p / 200f) * 2.0f
                    sensLabel.text = "Sensitivity: ${"%.1f".format(currentSensitivity)}x"
                    prefs.edit().putFloat("sensitivity", currentSensitivity).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { haptic(12) }
            })
        }
        sliderContainer.addView(sensLabel)
        sliderContainer.addView(sensBar)
        padView.addView(sliderContainer, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // Disconnect button
        val disconnectBtn = TextView(this).apply {
            text = "Disconnect"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener {
                haptic(20)
                ws?.close(1000, "User disconnected")
                drop("Disconnected")
            }
        }
        padView.addView(disconnectBtn)

        root.addView(padView)
        setContentView(root)

        // Left Click & Drag logic
        val dragStart = Runnable {
            dragging = true
            haptic(30)
            send { put("t", "down"); put("b", "left") }
        }
        hold(left, {
            haptic(15)
            ui.postDelayed(dragStart, 220)
        }, {
            ui.removeCallbacks(dragStart)
            if (dragging) {
                dragging = false
                haptic(15)
                send { put("t", "up"); put("b", "left") }
            } else {
                haptic(20)
                send { put("t", "click"); put("b", "left") }
            }
        })

        hold(right, {
            haptic(20)
            send { put("t", "click"); put("b", "right") }
        }, {})

        hold(scroll, {
            haptic(15)
            scrolling = true
        }, {
            scrolling = false
        })

        hold(pause, {
            haptic(15)
            paused = true
        }, {
            paused = false
        })

        // Connect button handler
        go.setOnClickListener {
            haptic(20)
            val ip = ipField.text.toString().trim()
            if (ip.isEmpty()) return@setOnClickListener
            prefs.edit().putString("ip", ip).apply()
            status.text = "Connecting to $ip:8080..."
            ws?.cancel()
            ws = client.newWebSocket(Request.Builder().url("ws://$ip:8080").build(), object : WebSocketListener() {
                override fun onOpen(w: WebSocket, r: Response) = runOnUiThread {
                    connected = true
                    lastTs = 0L
                    smoothGx = 0f
                    smoothGz = 0f
                    haptic(40)
                    status.text = "Connected. Point phone like a remote."
                    connectView.visibility = View.GONE
                    padView.visibility = View.VISIBLE
                }
                override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = runOnUiThread {
                    drop("Connection Failed: ${t.localizedMessage ?: t.message}")
                }
                override fun onClosed(w: WebSocket, code: Int, reason: String) = runOnUiThread {
                    drop("Disconnected")
                }
            })
        }
    }

    private fun drop(msg: String) {
        connected = false
        status.text = msg
        connectView.visibility = View.VISIBLE
        padView.visibility = View.GONE
    }

    // --- HARDWARE VOLUME BUTTON CLICKS ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (connected && padView.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event?.repeatCount == 0) {
                    haptic(22)
                    dragging = true
                    send { put("t", "down"); put("b", "left") }
                }
                return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event?.repeatCount == 0) {
                    haptic(22)
                    send { put("t", "click"); put("b", "right") }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (connected && padView.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (dragging) {
                    dragging = false
                    haptic(15)
                    send { put("t", "up"); put("b", "left") }
                } else {
                    haptic(20)
                    send { put("t", "click"); put("b", "left") }
                }
                return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // --- SENSOR PROCESSING (ANTI-JITTER + DYNAMIC BALLISTICS) ---
    private fun softDeadzone(v: Float, threshold: Float): Float {
        val a = abs(v)
        if (a <= threshold) return 0f
        return (v / a) * (a - threshold)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!connected) return
        if (lastTs == 0L) { lastTs = e.timestamp; return }
        val dt = ((e.timestamp - lastTs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastTs = e.timestamp
        if (paused) return

        // 1. Soft Deadzone filter (eliminates harsh threshold cuts)
        val rawGx = softDeadzone(e.values[0], deadzone)
        val rawGz = softDeadzone(e.values[2], deadzone)

        // 2. Adaptive Exponential Smoothing (EMA)
        // High speed = instant response (low smoothing), Slow speed = steady hand (high smoothing)
        val angularSpeed = sqrt(rawGx * rawGx + rawGz * rawGz)
        val alpha = (0.35f + 0.60f * (angularSpeed / 0.8f).coerceIn(0f, 1f))
        smoothGx += alpha * (rawGx - smoothGx)
        smoothGz += alpha * (rawGz - smoothGz)

        if (scrolling) {
            if (abs(smoothGx) > 0.002f) {
                send {
                    put("t", "scroll")
                    put("dy", (-smoothGx * dt * scrollPerRad * currentSensitivity).toDouble())
                }
            }
        } else {
            val speed = sqrt(smoothGx * smoothGx + smoothGz * smoothGz)
            if (speed > 0.002f) {
                // 3. Dynamic Pointer Ballistics (Non-linear acceleration)
                // Precision zone: 0.6x for fine targeting; Flick zone: up to 2.8x for effortless screen crossing
                val accel = (0.6f + 2.0f * (speed / 1.6f).toDouble().pow(1.3).toFloat()).coerceIn(0.6f, 2.8f)
                val mult = pxPerRad * currentSensitivity * accel

                val dx = (-smoothGz * dt * mult).toDouble()
                val dy = (-smoothGx * dt * mult).toDouble()
                send {
                    put("t", "move")
                    put("dx", dx)
                    put("dy", dy)
                }
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onResume() {
        super.onResume()
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ws?.close(1000, null)
    }

    private fun haptic(ms: Long = 18) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    private fun send(build: JSONObject.() -> Unit) {
        ws?.send(JSONObject().apply(build).toString())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pad(text: String, color: Int = Color.parseColor("#1E2430")) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14).toFloat()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hold(v: View, down: () -> Unit, up: () -> Unit) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.alpha = 0.55f; down() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; up() }
            }
            true
        }
    }
}

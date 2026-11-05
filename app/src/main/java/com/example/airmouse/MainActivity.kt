package com.example.airmouse

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity(), SensorEventListener {
    // Tuning
    private val pxPerRad = 1400f      // cursor speed
    private val scrollPerRad = 40f    // scroll speed
    private val deadzone = 0.01f      // rad/s, ignore jitter

    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sm: SensorManager
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

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#111111")); setPadding(dp(16), dp(32), dp(16), dp(16)) }
        status = TextView(this).apply { setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; text = "Enter your PC's IP address"; setPadding(0, 0, 0, dp(12)) }
        root.addView(status)

        // Connect screen
        connectView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val prefs = getSharedPreferences("air", MODE_PRIVATE)
        val ipField = EditText(this).apply {
            hint = "192.168.x.x"; setText(prefs.getString("ip", "")); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT; gravity = Gravity.CENTER
        }
        val go = pad("Connect", Color.parseColor("#4f8cff")).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(16) } }
        connectView.addView(ipField); connectView.addView(go)
        connectView.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        root.addView(connectView)

        // Mouse screen
        padView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        padView.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        val left = pad("Left click (tap)\nHold = drag"); val right = pad("Right click")
        val scroll = pad("Hold + tilt\n= scroll"); val pause = pad("Hold = pause cursor")
        padView.addView(left, LinearLayout.LayoutParams(-1, 0, 3f).apply { bottomMargin = dp(10) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(5) })
        row.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(5) })
        padView.addView(row, LinearLayout.LayoutParams(-1, 0, 1.2f).apply { bottomMargin = dp(10) })
        padView.addView(pause, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(padView)
        setContentView(root)

        val dragStart = Runnable { dragging = true; send { put("t", "down"); put("b", "left") } }
        hold(left, { ui.postDelayed(dragStart, 250) }, {
            ui.removeCallbacks(dragStart)
            if (dragging) { dragging = false; send { put("t", "up"); put("b", "left") } }
            else send { put("t", "click"); put("b", "left") }
        })
        hold(right, { send { put("t", "click"); put("b", "right") } }, {})
        hold(scroll, { scrolling = true }, { scrolling = false })
        hold(pause, { paused = true }, { paused = false })

        go.setOnClickListener {
            val ip = ipField.text.toString().trim()
            if (ip.isEmpty()) return@setOnClickListener
            prefs.edit().putString("ip", ip).apply()
            status.text = "Connecting..."
            ws?.cancel()
            ws = client.newWebSocket(Request.Builder().url("ws://$ip:8080").build(), object : WebSocketListener() {
                override fun onOpen(w: WebSocket, r: Response) = runOnUiThread {
                    connected = true; lastTs = 0
                    status.text = "Connected. Hold the phone like a remote."
                    connectView.visibility = View.GONE; padView.visibility = View.VISIBLE
                }
                override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = runOnUiThread { drop("Failed: ${t.message}") }
                override fun onClosed(w: WebSocket, code: Int, reason: String) = runOnUiThread { drop("Disconnected") }
            })
        }
    }

    private fun drop(msg: String) {
        connected = false; status.text = msg
        connectView.visibility = View.VISIBLE; padView.visibility = View.GONE
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!connected) return
        if (lastTs == 0L) { lastTs = e.timestamp; return }
        val dt = (e.timestamp - lastTs) / 1e9f; lastTs = e.timestamp
        if (paused) return
        // Phone flat-ish like a remote: z = yaw (left/right), x = pitch (up/down). Flip signs if backwards.
        val gx = if (abs(e.values[0]) > deadzone) e.values[0] else 0f
        val gz = if (abs(e.values[2]) > deadzone) e.values[2] else 0f
        if (scrolling) {
            if (gx != 0f) send { put("t", "scroll"); put("dy", (-gx * dt * scrollPerRad).toDouble()) }
        } else if (gx != 0f || gz != 0f) {
            send { put("t", "move"); put("dx", (-gz * dt * pxPerRad).toDouble()); put("dy", (-gx * dt * pxPerRad).toDouble()) }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onResume() { super.onResume(); sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME) }
    override fun onPause() { super.onPause(); sm.unregisterListener(this) }
    override fun onDestroy() { super.onDestroy(); ws?.close(1000, null) }

    private fun send(build: JSONObject.() -> Unit) { ws?.send(JSONObject().apply(build).toString()) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pad(text: String, color: Int = Color.parseColor("#222222")) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hold(v: View, down: () -> Unit, up: () -> Unit) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.alpha = 0.6f; down() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; up() }
            }
            true
        }
    }
}

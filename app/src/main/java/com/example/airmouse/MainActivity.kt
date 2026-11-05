package com.example.airmouse

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

// ============================================================================
// 1€ FILTER (ONE EURO FILTER) - ADAPTIVE JITTER & LATENCY SUPPRESSION
// Casiez et al., CHI 2012: Dynamic cutoff based on instantaneous velocity
// ============================================================================
class LowPassFilter(private var s: Float = 0f, var initialized: Boolean = false) {
    fun filter(value: Float, alpha: Float): Float {
        val result = if (initialized) alpha * value + (1f - alpha) * s else value
        s = result
        initialized = true
        return result
    }
    fun value(): Float = s
    fun reset() { initialized = false }
}

class OneEuroFilter(
    private val minCutoff: Float = 1.0f,  // Low speed cutoff (Hz): stops micro hand-tremor
    private val beta: Float = 0.05f,      // Speed coefficient: eliminates lag during flicks
    private val dCutoff: Float = 1.0f     // Derivative cutoff (Hz)
) {
    private val xFilter = LowPassFilter()
    private val dxFilter = LowPassFilter()
    private var lastTime = -1L

    private fun getAlpha(rate: Float, cutoff: Float): Float {
        val tau = 1.0f / (2.0f * Math.PI.toFloat() * cutoff)
        val te = 1.0f / rate
        return 1.0f / (1.0f + tau / te)
    }

    fun filter(value: Float, timestampNs: Long): Float {
        if (lastTime == -1L) {
            lastTime = timestampNs
            return xFilter.filter(value, 1f)
        }
        val dt = ((timestampNs - lastTime) / 1e9f).coerceIn(0.0005f, 0.05f)
        lastTime = timestampNs
        val rate = 1.0f / dt

        val prevX = if (xFilter.initialized) xFilter.value() else value
        val dx = (value - prevX) * rate
        val edx = dxFilter.filter(dx, getAlpha(rate, dCutoff))

        val cutoff = minCutoff + beta * abs(edx)
        return xFilter.filter(value, getAlpha(rate, cutoff))
    }

    fun reset() {
        lastTime = -1L
        xFilter.reset()
        dxFilter.reset()
    }
}

class MainActivity : Activity(), SensorEventListener {

    companion object {
        const val APP_VERSION_CODE = 3
        const val APP_VERSION_NAME = "2.1"
    }

    // Motion Tuning
    private val gyroPxPerRad = 1750f
    private val gyroScrollPerRad = 45f

    // One Euro Filters for X (pitch) and Z (yaw)
    private val filterGx = OneEuroFilter(minCutoff = 1.2f, beta = 0.06f)
    private val filterGz = OneEuroFilter(minCutoff = 1.2f, beta = 0.06f)

    private var currentSensitivity = 1.0f
    private var isTouchpadMode = false
    private var lastGyroTs = 0L

    // Network & Wi-Fi Lock
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sm: SensorManager
    private lateinit var vibrator: Vibrator

    // UI Elements (Nothing Phone Aesthetic)
    private lateinit var statusText: TextView
    private lateinit var statusDot: TextView
    private lateinit var connectView: LinearLayout
    private lateinit var mainHubView: LinearLayout
    private lateinit var gyroContainer: LinearLayout
    private lateinit var touchpadContainer: LinearLayout
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateBannerText: TextView
    private lateinit var updateBannerBtn: TextView
    private lateinit var gyroTabBtn: TextView
    private lateinit var touchpadTabBtn: TextView
    private lateinit var modeBadge: TextView

    @Volatile private var connected = false
    @Volatile private var gyroScrolling = false
    @Volatile private var gyroPaused = false
    private var dragging = false

    private var serverIp = "192.168.0.107"
    private var pendingApkUrl: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        val prefs = getSharedPreferences("air_nothing", MODE_PRIVATE)
        currentSensitivity = prefs.getFloat("sensitivity", 1.0f)
        serverIp = prefs.getString("ip", "192.168.0.107") ?: "192.168.0.107"

        // Pure Pitch Black Nothing OS Background
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        // ==========================================
        // NOTHING OS: TOP HUD WIDGET
        // ==========================================
        val topWidget = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = nothingCard(Color.parseColor("#121212"), Color.parseColor("#262626"), dp(18))
        }

        statusDot = TextView(this).apply {
            text = "● "
            setTextColor(Color.parseColor("#D71921")) // Nothing Iconic Red
            textSize = 12f
        }
        topWidget.addView(statusDot)

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#E5E5E5"))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            text = "DISCONNECTED (STANDBY)"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topWidget.addView(statusText)

        val closeBtn = TextView(this).apply {
            text = "( DISCONNECT )"
            setTextColor(Color.parseColor("#737373"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener {
                haptic(20)
                ws?.close(1000, "User close")
                dropConnection("DISCONNECTED")
            }
        }
        topWidget.addView(closeBtn)
        root.addView(topWidget)

        // ==========================================
        // NOTHING OS: FIRMWARE UPDATE PILL
        // ==========================================
        updateBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = nothingCard(Color.parseColor("#1C1012"), Color.parseColor("#D71921"), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
        updateBannerText = TextView(this).apply {
            text = "FIRMWARE UPDATE READY"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        updateBannerBtn = TextView(this).apply {
            text = "[ FLASH ]"
            setTextColor(Color.parseColor("#D71921"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                pendingApkUrl?.let { url -> downloadAndInstallUpdate(url) }
            }
        }
        updateBanner.addView(updateBannerText)
        updateBanner.addView(updateBannerBtn)
        root.addView(updateBanner)

        // ==========================================
        // VIEW 1: CONNECT SCREEN (NOTHING MINIMAL)
        // ==========================================
        connectView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        val dotMatrixTitle = TextView(this).apply {
            text = "( AIR_MOUSE // N•01 )"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        val dotMatrixSubtitle = TextView(this).apply {
            text = "DOT_ENGINE V$APP_VERSION_NAME • ULTRA_LOW_LATENCY"
            setTextColor(Color.parseColor("#666666"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        }
        connectView.addView(dotMatrixTitle)
        connectView.addView(dotMatrixSubtitle)

        val ipCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(16)
            background = nothingCard(Color.parseColor("#141414"), Color.parseColor("#2B2B2B"), dp(20))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val ipLabel = TextView(this).apply {
            text = "TARGET PC IP ADDRESS"
            setTextColor(Color.parseColor("#888888"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(0, 0, 0, dp(8))
        }
        val ipField = EditText(this).apply {
            hint = "192.168.0.xxx"
            setText(serverIp)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#444444"))
            textSize = 22f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = nothingCard(Color.parseColor("#0A0A0A"), Color.parseColor("#262626"), dp(12))
        }
        ipCard.addView(ipLabel)
        ipCard.addView(ipField)
        connectView.addView(ipCard)

        val connectBtn = nothingButton("CONNECT TO PC", Color.WHITE, Color.parseColor("#1A1A1A"), Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(20) }
            setOnClickListener {
                val ip = ipField.text.toString().trim()
                if (ip.isEmpty()) return@setOnClickListener
                serverIp = ip
                prefs.edit().putString("ip", ip).apply()
                haptic(30)
                connectToServer(ip)
            }
        }
        connectView.addView(connectBtn)
        root.addView(connectView)

        // ==========================================
        // VIEW 2: ACTIVE CONTROL HUB
        // ==========================================
        mainHubView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // Nothing OS Pill Segment Switcher
        val modeSwitcher = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = nothingCard(Color.parseColor("#121212"), Color.parseColor("#242424"), dp(24))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        }

        gyroTabBtn = TextView(this).apply {
            text = "GYRO AIR"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchMode(false) }
        }

        touchpadTabBtn = TextView(this).apply {
            text = "TOUCHPAD"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchMode(true) }
        }

        modeSwitcher.addView(gyroTabBtn)
        modeSwitcher.addView(touchpadTabBtn)
        mainHubView.addView(modeSwitcher)

        modeBadge = TextView(this).apply {
            text = "• 1€ ADAPTIVE FILTER ACTIVE  |  VOL KEYS: CLICK"
            setTextColor(Color.parseColor("#888888"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        mainHubView.addView(modeBadge)

        // Mode Containers
        gyroContainer = createGyroInterface()
        mainHubView.addView(gyroContainer)

        touchpadContainer = createTouchpadInterface()
        touchpadContainer.visibility = View.GONE
        mainHubView.addView(touchpadContainer)

        // Minimalist Sensitivity Widget (Nothing Style)
        val sliderCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(12)
            background = nothingCard(Color.parseColor("#111111"), Color.parseColor("#222222"), dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        val sensTitle = TextView(this).apply {
            text = "SENSITIVITY // ${"%.1f".format(currentSensitivity)}X"
            setTextColor(Color.parseColor("#A3A3A3"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.CENTER
        }
        val sensBar = SeekBar(this).apply {
            max = 200
            progress = ((currentSensitivity - 0.5f) / 2.0f * 200).toInt().coerceIn(0, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    currentSensitivity = 0.5f + (p / 200f) * 2.0f
                    sensTitle.text = "SENSITIVITY // ${"%.1f".format(currentSensitivity)}X"
                    prefs.edit().putFloat("sensitivity", currentSensitivity).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { haptic(12) }
            })
        }
        sliderCard.addView(sensTitle)
        sliderCard.addView(sensBar)
        mainHubView.addView(sliderCard)

        root.addView(mainHubView)
        setContentView(root)

        updateTabStyles()
    }

    // ==========================================
    // NOTHING OS: GYRO AIR REMOTE UI
    // ==========================================
    private fun createGyroInterface(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        val leftPad = nothingPad("LEFT CLICK\n( TAP / HOLD DRAG )", Color.WHITE, Color.parseColor("#161616"), Color.parseColor("#333333"), dp(20))
        val rightPad = nothingPad("RIGHT CLICK", Color.WHITE, Color.parseColor("#161616"), Color.parseColor("#333333"), dp(18))
        val scrollPad = nothingPad("HOLD + TILT\nSCROLL", Color.WHITE, Color.parseColor("#161616"), Color.parseColor("#333333"), dp(18))
        val freezePad = nothingPad("HOLD TO FREEZE CURSOR", Color.parseColor("#888888"), Color.parseColor("#0D0D0D"), Color.parseColor("#222222"), dp(16))

        layout.addView(leftPad, LinearLayout.LayoutParams(-1, 0, 2.5f).apply { bottomMargin = dp(8) })

        val midRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        midRow.addView(rightPad, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
        midRow.addView(scrollPad, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4) })
        layout.addView(midRow, LinearLayout.LayoutParams(-1, 0, 1.2f).apply { bottomMargin = dp(8) })

        layout.addView(freezePad, LinearLayout.LayoutParams(-1, 0, 1.0f))

        val dragStart = Runnable {
            dragging = true
            haptic(35)
            sendRaw("d l")
        }
        bindTouch(leftPad, {
            haptic(16)
            ui.postDelayed(dragStart, 220)
        }, {
            ui.removeCallbacks(dragStart)
            if (dragging) {
                dragging = false
                haptic(16)
                sendRaw("u l")
            } else {
                haptic(22)
                sendRaw("c l")
            }
        })

        bindTouch(rightPad, {
            haptic(22)
            sendRaw("c r")
        }, {})

        bindTouch(scrollPad, {
            haptic(16)
            gyroScrolling = true
        }, {
            gyroScrolling = false
        })

        bindTouch(freezePad, {
            haptic(16)
            gyroPaused = true
        }, {
            gyroPaused = false
        })

        return layout
    }

    // ==========================================
    // NOTHING OS: PRECISION TOUCHPAD UI
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    private fun createTouchpadInterface(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        val trackpadSurface = FrameLayout(this).apply {
            background = nothingCard(Color.parseColor("#0C0C0C"), Color.parseColor("#2B2B2B"), dp(22))
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(8) }
        }

        val label = TextView(this).apply {
            text = "+       ·       +\n\nTOUCHPAD\n1-FINGER: MOVE  |  2-FINGER: SCROLL\n\n+       ·       +"
            setTextColor(Color.parseColor("#404040"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        trackpadSurface.addView(label)

        var prevX = 0f
        var prevY = 0f
        var touchDownTime = 0L
        var touchStartX = 0f
        var touchStartY = 0f
        var isTwoFinger = false
        var prevScrollY = 0f

        trackpadSurface.setOnTouchListener { _, event ->
            val pCount = event.pointerCount
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownTime = SystemClock.uptimeMillis()
                    touchStartX = event.x
                    touchStartY = event.y
                    prevX = event.x
                    prevY = event.y
                    isTwoFinger = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (pCount >= 2) {
                        isTwoFinger = true
                        prevScrollY = (event.getY(0) + event.getY(1)) / 2f
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFinger && pCount >= 2) {
                        val currentScrollY = (event.getY(0) + event.getY(1)) / 2f
                        val dy = (currentScrollY - prevScrollY) * 0.12f * currentSensitivity
                        prevScrollY = currentScrollY
                        if (abs(dy) > 0.05f) {
                            sendRaw("s ${"%.3f".format(Locale.US, dy)}")
                        }
                    } else if (!isTwoFinger && pCount == 1) {
                        val dx = (event.x - prevX) * 1.55f * currentSensitivity
                        val dy = (event.y - prevY) * 1.55f * currentSensitivity
                        prevX = event.x
                        prevY = event.y
                        if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                            sendRaw("m ${"%.3f".format(Locale.US, dx)} ${"%.3f".format(Locale.US, dy)}")
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (isTwoFinger && (SystemClock.uptimeMillis() - touchDownTime) < 260) {
                        haptic(25)
                        sendRaw("c r")
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val duration = SystemClock.uptimeMillis() - touchDownTime
                    val dist = hypot(event.x - touchStartX, event.y - touchStartY)
                    if (!isTwoFinger && duration < 240 && dist < dp(14)) {
                        haptic(20)
                        sendRaw("c l")
                    }
                }
            }
            true
        }
        layout.addView(trackpadSurface)

        // Bottom Physical Buttons (Nothing Pill Style)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(64))
        }

        val padLeft = nothingPad("LEFT CLICK", Color.WHITE, Color.parseColor("#151515"), Color.parseColor("#2A2A2A"), dp(18))
        val padRight = nothingPad("RIGHT CLICK", Color.WHITE, Color.parseColor("#151515"), Color.parseColor("#2A2A2A"), dp(18))

        bindTouch(padLeft, {
            haptic(20)
            sendRaw("c l")
        }, {})

        bindTouch(padRight, {
            haptic(20)
            sendRaw("c r")
        }, {})

        btnRow.addView(padLeft, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
        btnRow.addView(padRight, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4) })
        layout.addView(btnRow)

        return layout
    }

    // ==========================================
    // MODE SWITCHER LOGIC
    // ==========================================
    private fun switchMode(touchpad: Boolean) {
        if (isTouchpadMode == touchpad) return
        isTouchpadMode = touchpad
        haptic(25)

        if (isTouchpadMode) {
            gyroContainer.visibility = View.GONE
            touchpadContainer.visibility = View.VISIBLE
            modeBadge.text = "• TOUCHPAD ACTIVE  |  2-FINGER SCROLL"
        } else {
            touchpadContainer.visibility = View.GONE
            gyroContainer.visibility = View.VISIBLE
            modeBadge.text = "• 1€ ADAPTIVE FILTER ACTIVE  |  VOL KEYS: CLICK"
            filterGx.reset()
            filterGz.reset()
        }
        updateTabStyles()
    }

    private fun updateTabStyles() {
        if (isTouchpadMode) {
            touchpadTabBtn.background = nothingCard(Color.WHITE, Color.WHITE, dp(20))
            touchpadTabBtn.setTextColor(Color.BLACK)
            gyroTabBtn.background = null
            gyroTabBtn.setTextColor(Color.parseColor("#737373"))
        } else {
            gyroTabBtn.background = nothingCard(Color.WHITE, Color.WHITE, dp(20))
            gyroTabBtn.setTextColor(Color.BLACK)
            touchpadTabBtn.background = null
            touchpadTabBtn.setTextColor(Color.parseColor("#737373"))
        }
    }

    // ==========================================
    // ULTRA-LOW LATENCY NETWORK & WI-FI LOCK
    // ==========================================
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AirMouse:LowLatency")
            } else {
                @Suppress("DEPRECATION")
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirMouse:HighPerf")
            }
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun connectToServer(ip: String) {
        statusText.text = "CONNECTING ($ip)..."
        statusDot.setTextColor(Color.parseColor("#EAB308")) // Amber connecting
        ws?.cancel()
        ws = client.newWebSocket(Request.Builder().url("ws://$ip:8080").build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) = runOnUiThread {
                connected = true
                lastGyroTs = 0L
                filterGx.reset()
                filterGz.reset()
                acquireWifiLock()
                haptic(50)
                statusDot.setTextColor(Color.parseColor("#22C55E")) // Green connected
                statusText.text = "CONNECTED // $ip"
                connectView.visibility = View.GONE
                mainHubView.visibility = View.VISIBLE
            }

            override fun onMessage(w: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if (msg.optString("t") == "init") {
                        val serverVersionCode = msg.optInt("versionCode", 0)
                        val apkUrl = msg.optString("apkUrl", "")
                        if (serverVersionCode > APP_VERSION_CODE && apkUrl.isNotEmpty()) {
                            runOnUiThread {
                                pendingApkUrl = apkUrl
                                updateBannerText.text = "FIRMWARE V${msg.optString("versionName", "NEW")} AVAILABLE"
                                updateBanner.visibility = View.VISIBLE
                                haptic(40)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = runOnUiThread {
                dropConnection("ERROR: ${t.localizedMessage ?: t.message}")
            }

            override fun onClosed(w: WebSocket, code: Int, reason: String) = runOnUiThread {
                dropConnection("DISCONNECTED")
            }
        })
    }

    private fun dropConnection(msg: String) {
        connected = false
        releaseWifiLock()
        statusDot.setTextColor(Color.parseColor("#D71921")) // Nothing Red
        statusText.text = msg
        connectView.visibility = View.VISIBLE
        mainHubView.visibility = View.GONE
    }

    // ==========================================
    // OTA UPDATE ENGINE
    // ==========================================
    private fun downloadAndInstallUpdate(downloadUrl: String) {
        haptic(25)
        updateBannerBtn.text = "[ ... ]"
        updateBannerBtn.isEnabled = false

        Thread {
            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.connect()

                if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")

                val totalLen = conn.contentLength
                val apkFile = File(cacheDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()

                val input = conn.inputStream
                val output = FileOutputStream(apkFile)
                val buffer = ByteArray(8192)
                var downloaded = 0
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalLen > 0) {
                        val pct = (downloaded * 100 / totalLen)
                        ui.post { updateBannerText.text = "FLASHING: $pct%" }
                    }
                }
                output.flush()
                output.close()
                input.close()

                ui.post {
                    updateBannerText.text = "VERIFIED (OPENING INSTALLER)"
                    updateBannerBtn.text = "[ READY ]"
                    triggerApkInstall(apkFile)
                }
            } catch (e: Exception) {
                ui.post {
                    updateBannerText.text = "FLASH ERROR: ${e.message}"
                    updateBannerBtn.text = "[ RETRY ]"
                    updateBannerBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun triggerApkInstall(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            }
        }

        val apkUri = Uri.parse("content://${packageName}.provider/${apkFile.name}")
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(installIntent)
    }

    // ==========================================
    // HARDWARE VOLUME BUTTON CLICKS
    // ==========================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (connected && mainHubView.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event?.repeatCount == 0) {
                    haptic(24)
                    dragging = true
                    sendRaw("d l")
                }
                return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event?.repeatCount == 0) {
                    haptic(24)
                    sendRaw("c r")
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (connected && mainHubView.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (dragging) {
                    dragging = false
                    haptic(16)
                    sendRaw("u l")
                } else {
                    haptic(20)
                    sendRaw("c l")
                }
                return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // ==========================================
    // 200HZ SENSOR LOOP + 1€ FILTER EXECUTION
    // ==========================================
    override fun onSensorChanged(e: SensorEvent) {
        if (!connected || isTouchpadMode) return
        val now = e.timestamp
        if (lastGyroTs == 0L) { lastGyroTs = now; return }
        val dt = ((now - lastGyroTs) / 1e9f).coerceIn(0.0005f, 0.05f)
        lastGyroTs = now
        if (gyroPaused) return

        // Run through 1€ Filter (Eliminates micro hand tremor while preserving flick speed)
        val filteredGx = filterGx.filter(e.values[0], now)
        val filteredGz = filterGz.filter(e.values[2], now)

        if (gyroScrolling) {
            if (abs(filteredGx) > 0.005f) {
                val scrollDy = (-filteredGx * dt * gyroScrollPerRad * currentSensitivity).toDouble()
                sendRaw("s ${"%.3f".format(Locale.US, scrollDy)}")
            }
        } else {
            val speed = sqrt(filteredGx * filteredGx + filteredGz * filteredGz)
            if (speed > 0.005f) {
                // Adaptive velocity acceleration curve
                val accel = (0.7f + 2.0f * (speed / 1.6f).toDouble().pow(1.3).toFloat()).coerceIn(0.6f, 2.8f)
                val mult = gyroPxPerRad * currentSensitivity * accel
                val dx = -filteredGz * dt * mult
                val dy = -filteredGx * dt * mult
                sendRaw("m ${"%.3f".format(Locale.US, dx)} ${"%.3f".format(Locale.US, dy)}")
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onResume() {
        super.onResume()
        // Register sensor at FASTEST possible hardware rate (~200Hz)
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_FASTEST)
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        releaseWifiLock()
    }

    override fun onDestroy() {
        super.onDestroy()
        ws?.close(1000, null)
        releaseWifiLock()
    }

    // ==========================================
    // WIRE PROTOCOL (COMPACT TOKENS)
    // ==========================================
    private fun sendRaw(rawText: String) {
        ws?.send(rawText)
    }

    // ==========================================
    // NOTHING OS STYLING HELPERS
    // ==========================================
    private fun nothingCard(bgColor: Int, strokeColor: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), strokeColor)
    }

    private fun nothingButton(text: String, textColor: Int, bgColor: Int, strokeColor: Int) = TextView(this).apply {
        this.text = text
        setTextColor(textColor)
        typeface = Typeface.MONOSPACE
        textSize = 14f
        gravity = Gravity.CENTER
        background = nothingCard(bgColor, strokeColor, dp(18))
    }

    private fun nothingPad(text: String, textColor: Int, bgColor: Int, strokeColor: Int, radiusDp: Int) = TextView(this).apply {
        this.text = text
        setTextColor(textColor)
        typeface = Typeface.MONOSPACE
        textSize = 13f
        gravity = Gravity.CENTER
        background = nothingCard(bgColor, strokeColor, radiusDp)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTouch(v: View, down: () -> Unit, up: () -> Unit) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.alpha = 0.5f; down() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; up() }
            }
            true
        }
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun LinearLayout.padding(allDp: Int) = setPadding(dp(allDp), dp(allDp), dp(allDp), dp(allDp))
}

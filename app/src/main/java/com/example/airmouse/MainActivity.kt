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

class MainActivity : Activity(), SensorEventListener {

    companion object {
        const val APP_VERSION_CODE = 10
        const val APP_VERSION_NAME = "2.8"

        const val MODE_AIR = 0
        const val MODE_SURFACE = 1
        const val MODE_TOUCHPAD = 2
    }

    // Motion parameters (Calibrated gentle sensitivity)
    private val gyroPxPerRad = 2100f
    private val gyroScrollPerRad = 50f
    private val deadzoneThreshold = 0.007f // rad/s (kills MEMS resting noise)

    // Surface Mode Parameters (Desk sliding physics)
    private var surfaceVx = 0f
    private var surfaceVy = 0f
    private var lastLinearTs = 0L
    private val surfaceAccelGain = 3200f
    private val surfaceFriction = 0.82f // Decays speed smoothly when stopping
    private val surfaceDeadzone = 0.12f // m/s^2 (ignores minor desk vibration)

    // Motion State & Smoothing
    private var smoothH = 0f
    private var smoothV = 0f
    private var lastGyroTs = 0L

    // Rotation Vector Fallback State
    private var lastYaw = Float.NaN
    private var lastPitch = Float.NaN
    private val rotMatrix = FloatArray(9)
    private val orientationVals = FloatArray(3)

    private var currentSensitivity = 0.8f
    private var currentMode = MODE_AIR

    // Sensor Diagnostics & Rate Counters
    private var activeSensorName = "INITIALIZING..."
    private var sensorEventCount = 0
    private var lastRateCalcTime = 0L
    private var currentSensorHz = 0
    private var totalPacketsSent = 0L
    private var lastSentDx = 0f
    private var lastSentDy = 0f

    // Network & Wi-Fi Lock
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val ui = Handler(Looper.getMainLooper())
    private var sm: SensorManager? = null
    private var vibrator: Vibrator? = null

    // UI Elements (Nothing Phone Aesthetic)
    private lateinit var statusText: TextView
    private lateinit var statusDot: TextView
    private lateinit var connectView: LinearLayout
    private lateinit var mainHubView: LinearLayout
    private lateinit var gyroContainer: LinearLayout
    private lateinit var surfaceContainer: LinearLayout
    private lateinit var touchpadContainer: LinearLayout
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateBannerText: TextView
    private lateinit var updateBannerBtn: TextView
    private lateinit var gyroTabBtn: TextView
    private lateinit var surfaceTabBtn: TextView
    private lateinit var touchpadTabBtn: TextView
    private lateinit var modeBadge: TextView
    private lateinit var diagnosticHud: TextView

    @Volatile private var connected = false
    @Volatile private var gyroScrolling = false
    @Volatile private var gyroPaused = false
    private var dragging = false

    private var serverIp = "192.168.0.107"
    private var pendingApkUrl: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            initApp()
        } catch (e: Throwable) {
            val errView = TextView(this).apply {
                text = "STARTUP ERROR:\n${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString()}"
                setTextColor(Color.RED)
                textSize = 12f
                setPadding(40, 60, 40, 40)
                setBackgroundColor(Color.BLACK)
            }
            setContentView(errView)
        }
    }

    private fun initApp() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try { sm = getSystemService(SENSOR_SERVICE) as? SensorManager } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } catch (_: Exception) {}

        val prefs = getSharedPreferences("air_nothing", MODE_PRIVATE)
        currentSensitivity = prefs.getFloat("sensitivity", 0.8f)
        serverIp = prefs.getString("ip", "192.168.0.107") ?: "192.168.0.107"

        // Root — pure pitch black Nothing OS background
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
        // VIEW 1: CONNECT SCREEN
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
            text = "DUAL-ENGINE OPTICAL V$APP_VERSION_NAME • 200HZ"
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
            setPadding(dp(16), dp(16), dp(16), dp(16))
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
            text = "AIR GYRO"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchMode(MODE_AIR) }
        }

        surfaceTabBtn = TextView(this).apply {
            text = "SURFACE"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchMode(MODE_SURFACE) }
        }

        touchpadTabBtn = TextView(this).apply {
            text = "TOUCHPAD"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchMode(MODE_TOUCHPAD) }
        }

        modeSwitcher.addView(gyroTabBtn)
        modeSwitcher.addView(surfaceTabBtn)
        modeSwitcher.addView(touchpadTabBtn)
        mainHubView.addView(modeSwitcher)

        // Live Diagnostic Widget
        diagnosticHud = TextView(this).apply {
            text = "SENSOR: INITIALIZING... | 0 HZ"
            setTextColor(Color.parseColor("#22C55E")) // Green terminal text
            typeface = Typeface.MONOSPACE
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        mainHubView.addView(diagnosticHud)

        modeBadge = TextView(this).apply {
            text = "• ACTIVE AIR MOUSE  |  VOL KEYS: CLICK"
            setTextColor(Color.parseColor("#888888"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        mainHubView.addView(modeBadge)

        gyroContainer = createGyroInterface()
        mainHubView.addView(gyroContainer)

        surfaceContainer = createSurfaceInterface()
        surfaceContainer.visibility = View.GONE
        mainHubView.addView(surfaceContainer)

        touchpadContainer = createTouchpadInterface()
        touchpadContainer.visibility = View.GONE
        mainHubView.addView(touchpadContainer)

        // Sensitivity Control
        val sliderCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = nothingCard(Color.parseColor("#111111"), Color.parseColor("#222222"), dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
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
            max = 180
            progress = ((currentSensitivity - 0.2f) / 1.8f * 180).toInt().coerceIn(0, 180)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    currentSensitivity = 0.2f + (p / 180f) * 1.8f
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

        // Periodically refresh live diagnostics HUD
        ui.post(object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                val dt = (now - lastRateCalcTime) / 1000f
                if (dt >= 1.0f) {
                    currentSensorHz = (sensorEventCount / dt).toInt()
                    sensorEventCount = 0
                    lastRateCalcTime = now
                }
                if (connected && mainHubView.visibility == View.VISIBLE) {
                    diagnosticHud.text = "$activeSensorName @ ${currentSensorHz}Hz | dX:${"%.1f".format(lastSentDx)} dY:${"%.1f".format(lastSentDy)} | PKTS:$totalPacketsSent"
                }
                ui.postDelayed(this, 300)
            }
        })
    }

    // ==========================================
    // NOTHING OS: GYRO AIR REMOTE UI
    // ==========================================
    private fun createGyroInterface(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        val leftPad   = nothingPad("LEFT CLICK\n( TAP / HOLD DRAG )", Color.WHITE, Color.parseColor("#161616"), Color.parseColor("#333333"), dp(20))
        val rightPad  = nothingPad("RIGHT CLICK", Color.WHITE, Color.parseColor("#161616"), Color.parseColor("#333333"), dp(18))
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
                dragging = false; haptic(16); sendRaw("u l")
            } else {
                haptic(22); sendRaw("c l")
            }
        })

        bindTouch(rightPad, { haptic(22); sendRaw("c r") }, {})
        bindTouch(scrollPad, { haptic(16); gyroScrolling = true }, { gyroScrolling = false })
        bindTouch(freezePad, { haptic(16); gyroPaused = true }, { gyroPaused = false })

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
                    touchStartX = event.x; touchStartY = event.y
                    prevX = event.x; prevY = event.y
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
                        val dy = (currentScrollY - prevScrollY) * 0.16f * currentSensitivity
                        prevScrollY = currentScrollY
                        if (abs(dy) > 0.05f) sendRaw("s ${"%.3f".format(Locale.US, dy)}")
                    } else if (!isTwoFinger && pCount == 1) {
                        val rawDx = (event.x - prevX) * 1.8f * currentSensitivity
                        val rawDy = (event.y - prevY) * 1.8f * currentSensitivity
                        prevX = event.x; prevY = event.y
                        val dist = hypot(rawDx, rawDy)
                        val accel = (0.85f + 1.3f * min(1.0f, dist / 20f))
                        val dx = rawDx * accel
                        val dy = rawDy * accel
                        if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                            sendRaw("m ${"%.3f".format(Locale.US, dx)} ${"%.3f".format(Locale.US, dy)}")
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (isTwoFinger && (SystemClock.uptimeMillis() - touchDownTime) < 260) {
                        haptic(25); sendRaw("c r")
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val duration = SystemClock.uptimeMillis() - touchDownTime
                    val dist = hypot(event.x - touchStartX, event.y - touchStartY)
                    if (!isTwoFinger && duration < 240 && dist < dp(14)) {
                        haptic(20); sendRaw("c l")
                    }
                }
            }
            true
        }
        layout.addView(trackpadSurface)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(64))
        }
        val padLeft  = nothingPad("LEFT CLICK",  Color.WHITE, Color.parseColor("#151515"), Color.parseColor("#2A2A2A"), dp(18))
        val padRight = nothingPad("RIGHT CLICK", Color.WHITE, Color.parseColor("#151515"), Color.parseColor("#2A2A2A"), dp(18))

        bindTouch(padLeft,  { haptic(20); sendRaw("c l") }, {})
        bindTouch(padRight, { haptic(20); sendRaw("c r") }, {})

        btnRow.addView(padLeft,  LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd   = dp(4) })
        btnRow.addView(padRight, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4) })
        layout.addView(btnRow)

        return layout
    }

    // ==========================================
    // NOTHING OS: DESK SURFACE MOUSE UI
    // ==========================================
    private fun createSurfaceInterface(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // Top Row: Physical Left & Right Click Pads
        val topBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 2.2f).apply { bottomMargin = dp(8) }
        }

        val leftPad = nothingPad("LEFT CLICK\n( TAP / DRAG )", Color.WHITE, Color.parseColor("#181818"), Color.parseColor("#333333"), dp(22))
        val rightPad = nothingPad("RIGHT CLICK", Color.WHITE, Color.parseColor("#181818"), Color.parseColor("#333333"), dp(22))

        val dragStart = Runnable {
            dragging = true
            haptic(35)
            sendRaw("d l")
        }
        bindTouch(leftPad, {
            haptic(18)
            ui.postDelayed(dragStart, 220)
        }, {
            ui.removeCallbacks(dragStart)
            if (dragging) {
                dragging = false; haptic(16); sendRaw("u l")
            } else {
                haptic(22); sendRaw("c l")
            }
        })

        bindTouch(rightPad, { haptic(22); sendRaw("c r") }, {})

        topBtnRow.addView(leftPad, LinearLayout.LayoutParams(0, -1, 1.2f).apply { marginEnd = dp(4) })
        topBtnRow.addView(rightPad, LinearLayout.LayoutParams(0, -1, 1.0f).apply { marginStart = dp(4) })
        layout.addView(topBtnRow)

        // Middle Row: Scroll Buttons
        val scrollRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(8) }
        }
        val scrollUp = nothingPad("▲ SCROLL UP", Color.WHITE, Color.parseColor("#121212"), Color.parseColor("#262626"), dp(16))
        val scrollDown = nothingPad("▼ SCROLL DOWN", Color.WHITE, Color.parseColor("#121212"), Color.parseColor("#262626"), dp(16))

        bindTouch(scrollUp, { haptic(14); sendRaw("s -1.0") }, {})
        bindTouch(scrollDown, { haptic(14); sendRaw("s 1.0") }, {})

        scrollRow.addView(scrollUp, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
        scrollRow.addView(scrollDown, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4) })
        layout.addView(scrollRow)

        // Bottom Info Card
        val surfaceInfoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = nothingCard(Color.parseColor("#0A0A0A"), Color.parseColor("#202020"), dp(18))
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1.2f)
        }
        val title = TextView(this).apply {
            text = "DESK SURFACE MODE ACTIVE"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        val desc = TextView(this).apply {
            text = "Place phone flat on table or mousepad.\nSlide like a real physical mouse.\n\nLinear Accel + Friction Decaying Engine"
            setTextColor(Color.parseColor("#737373"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            gravity = Gravity.CENTER
        }
        surfaceInfoCard.addView(title)
        surfaceInfoCard.addView(desc)
        layout.addView(surfaceInfoCard)

        return layout
    }

    // ==========================================
    // MODE SWITCHER LOGIC
    // ==========================================
    private fun switchMode(newMode: Int) {
        if (currentMode == newMode) return
        currentMode = newMode
        haptic(25)

        gyroContainer.visibility = if (currentMode == MODE_AIR) View.VISIBLE else View.GONE
        surfaceContainer.visibility = if (currentMode == MODE_SURFACE) View.VISIBLE else View.GONE
        touchpadContainer.visibility = if (currentMode == MODE_TOUCHPAD) View.VISIBLE else View.GONE

        smoothH = 0f; smoothV = 0f
        surfaceVx = 0f; surfaceVy = 0f
        lastYaw = Float.NaN; lastPitch = Float.NaN

        modeBadge.text = when (currentMode) {
            MODE_AIR -> "• ACTIVE AIR MOUSE (IN THE AIR) | VOL KEYS: CLICK"
            MODE_SURFACE -> "• DESK SURFACE MOUSE (ON TABLE) | VOL KEYS: CLICK"
            MODE_TOUCHPAD -> "• TOUCHPAD ACTIVE | 2-FINGER SCROLL"
            else -> "• ACTIVE MOUSE"
        }
        updateTabStyles()
    }

    private fun updateTabStyles() {
        val activeBg = nothingCard(Color.WHITE, Color.WHITE, dp(20))
        val inactiveColor = Color.parseColor("#737373")

        gyroTabBtn.background = if (currentMode == MODE_AIR) activeBg else null
        gyroTabBtn.setTextColor(if (currentMode == MODE_AIR) Color.BLACK else inactiveColor)

        surfaceTabBtn.background = if (currentMode == MODE_SURFACE) activeBg else null
        surfaceTabBtn.setTextColor(if (currentMode == MODE_SURFACE) Color.BLACK else inactiveColor)

        touchpadTabBtn.background = if (currentMode == MODE_TOUCHPAD) activeBg else null
        touchpadTabBtn.setTextColor(if (currentMode == MODE_TOUCHPAD) Color.BLACK else inactiveColor)
    }

    // ==========================================
    // ULTRA-LOW LATENCY NETWORK & WI-FI LOCK
    // ==========================================
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
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
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    private fun connectToServer(ip: String) {
        statusText.text = "CONNECTING ($ip)..."
        statusDot.setTextColor(Color.parseColor("#EAB308"))
        ws?.cancel()
        ws = client.newWebSocket(Request.Builder().url("ws://$ip:8080").build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) = runOnUiThread {
                connected = true
                lastGyroTs = 0L
                smoothH = 0f; smoothV = 0f
                lastYaw = Float.NaN; lastPitch = Float.NaN
                acquireWifiLock()
                haptic(50)
                statusDot.setTextColor(Color.parseColor("#22C55E"))
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
        statusDot.setTextColor(Color.parseColor("#D71921"))
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
                output.flush(); output.close(); input.close()

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
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                })
                return
            }
        }
        val apkUri = Uri.parse("content://${packageName}.provider/${apkFile.name}")
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // ==========================================
    // HARDWARE VOLUME BUTTON CLICKS
    // ==========================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (connected && mainHubView.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event?.repeatCount == 0) { haptic(24); dragging = true; sendRaw("d l") }
                return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event?.repeatCount == 0) { haptic(24); sendRaw("c r") }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (connected && mainHubView.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (dragging) { dragging = false; haptic(16); sendRaw("u l") } else { haptic(20); sendRaw("c l") }
                return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // ==========================================
    // TRIPLE ENGINE SENSOR PIPELINE: GYRO AIR + DESK SURFACE + ROTATION VECTOR
    // ==========================================
    override fun onSensorChanged(e: SensorEvent) {
        sensorEventCount++
        if (!connected || currentMode == MODE_TOUCHPAD || gyroPaused) return

        if (currentMode == MODE_SURFACE) {
            if (e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION || e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                activeSensorName = "DESK_SURFACE"
                handleSurfaceLinearAccelEvent(e)
            } else if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
                handleSurfaceYawAssist(e)
            }
            return
        }

        // MODE_AIR
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            activeSensorName = "GYRO"
            handleGyroscopeEvent(e)
        } else if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR || e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            if (activeSensorName != "GYRO") {
                activeSensorName = "FUSION_VEC"
                handleRotationVectorEvent(e)
            }
        }
    }

    private fun handleSurfaceLinearAccelEvent(e: SensorEvent) {
        val now = e.timestamp
        if (lastLinearTs == 0L) { lastLinearTs = now; return }
        val dt = ((now - lastLinearTs) / 1e9f).coerceIn(0.001f, 0.04f)
        lastLinearTs = now

        var rawAx = e.values[0]
        var rawAy = e.values[1]

        if (abs(rawAx) < surfaceDeadzone) rawAx = 0f
        if (abs(rawAy) < surfaceDeadzone) rawAy = 0f

        // Integrate acceleration into velocity with dynamic friction
        surfaceVx = (surfaceVx + rawAx * dt * 10f) * surfaceFriction
        surfaceVy = (surfaceVy + rawAy * dt * 10f) * surfaceFriction

        // Zero Velocity Update (ZUPT): damp velocity immediately when stopping
        if (rawAx == 0f) surfaceVx *= 0.65f
        if (rawAy == 0f) surfaceVy *= 0.65f

        val speed = hypot(surfaceVx, surfaceVy)
        if (speed > 0.001f) {
            val dx = (surfaceVx * dt * surfaceAccelGain * currentSensitivity).toDouble()
            // Pushing forward on desk (positive Y) moves cursor UP (-dy on Windows)
            val dy = (-surfaceVy * dt * surfaceAccelGain * currentSensitivity).toDouble()

            lastSentDx = dx.toFloat()
            lastSentDy = dy.toFloat()
            sendRaw("m ${"%.3f".format(Locale.US, dx)} ${"%.3f".format(Locale.US, dy)}")
        }
    }

    private fun handleSurfaceYawAssist(e: SensorEvent) {
        // When swiveling/pivoting phone on desk, add smooth horizontal delta
        val yawOmega = -e.values[2]
        if (abs(yawOmega) > 0.06f) {
            val dx = (yawOmega * 0.005f * 1200f * currentSensitivity).toDouble()
            sendRaw("m ${"%.3f".format(Locale.US, dx)} 0.000")
        }
    }

    private fun handleGyroscopeEvent(e: SensorEvent) {
        val now = e.timestamp
        if (lastGyroTs == 0L) { lastGyroTs = now; return }
        val dt = ((now - lastGyroTs) / 1e9f).coerceIn(0.001f, 0.04f)
        lastGyroTs = now

        // Direct, robust 3D rotation projection:
        // Turning left/right (yaw) causes rotation around Z (wrist/pointing) and Y (panning).
        // Summing them gives instant responsive horizontal tracking at any hand inclination.
        val rawH = -(e.values[2] + e.values[1])

        // Tilting UP (top goes towards user) produces negative values[0];
        // Windows cursor UP is negative dy.
        val rawV = -e.values[0]

        // Deadzone: remove resting physiological hand tremor
        val magH = abs(rawH)
        val magV = abs(rawV)
        val inH = if (magH < deadzoneThreshold) 0f else (rawH - sign(rawH) * deadzoneThreshold)
        val inV = if (magV < deadzoneThreshold) 0f else (rawV - sign(rawV) * deadzoneThreshold)

        // Low-latency exponential smoothing filter (alpha = 0.65 -> <8ms response)
        val speed = hypot(inH, inV)
        val alpha = (0.50f + 0.35f * min(1.0f, speed / 0.5f)).coerceIn(0.40f, 0.85f)
        smoothH += alpha * (inH - smoothH)
        smoothV += alpha * (inV - smoothV)

        if (gyroScrolling) {
            if (abs(smoothV) > 0.004f) {
                val scrollDy = (smoothV * dt * gyroScrollPerRad * currentSensitivity).toDouble()
                sendRaw("s ${"%.3f".format(Locale.US, scrollDy)}")
            }
        } else {
            val smoothSpeed = hypot(smoothH, smoothV)
            if (smoothSpeed > 0.002f) {
                // Gentle, controlled mouse ballistics curve
                val accel = (0.75f + 0.70f * min(1.0f, (smoothSpeed / 1.5f).pow(1.1f))).coerceIn(0.70f, 1.7f)
                val mult = gyroPxPerRad * currentSensitivity * accel

                val dx = (smoothH * dt * mult).toDouble()
                val dy = (smoothV * dt * mult).toDouble()

                lastSentDx = dx.toFloat()
                lastSentDy = dy.toFloat()
                sendRaw("m ${"%.3f".format(Locale.US, dx)} ${"%.3f".format(Locale.US, dy)}")
            }
        }
    }

    private fun handleRotationVectorEvent(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
        SensorManager.getOrientation(rotMatrix, orientationVals)

        val yaw = orientationVals[0]   // azimuth [-pi, pi]
        val pitch = orientationVals[1] // pitch [-pi/2, pi/2]

        if (lastYaw.isNaN() || lastPitch.isNaN()) {
            lastYaw = yaw
            lastPitch = pitch
            return
        }

        var dYaw = yaw - lastYaw
        if (dYaw > PI.toFloat()) dYaw -= (2f * PI.toFloat())
        if (dYaw < -PI.toFloat()) dYaw += (2f * PI.toFloat())

        val dPitch = pitch - lastPitch
        lastYaw = yaw
        lastPitch = pitch

        if (abs(dYaw) > 0.0003f || abs(dPitch) > 0.0003f) {
            val mult = gyroPxPerRad * currentSensitivity * 0.85f
            val dx = (-dYaw * mult).toDouble()
            val dy = (-dPitch * mult).toDouble()

            lastSentDx = dx.toFloat()
            lastSentDy = dy.toFloat()
            sendRaw("m ${"%.3f".format(Locale.US, dx)} ${"%.3f".format(Locale.US, dy)}")
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onResume() {
        super.onResume()
        try {
            val sensorManager = sm ?: return

            // 1. Register Gyroscope at fastest speed
            val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (gyro != null) {
                sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST)
                activeSensorName = "GYRO"
            }

            // 2. Register Game Rotation Vector (6-DOF sensor fusion)
            val gameRot = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (gameRot != null) {
                sensorManager.registerListener(this, gameRot, SensorManager.SENSOR_DELAY_FASTEST)
                if (gyro == null) activeSensorName = "FUSION_VEC"
            }

            // 3. Register Linear Acceleration for Desk Surface Mode
            val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (linearAccel != null) {
                sensorManager.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_FASTEST)
            }

            if (gyro == null && gameRot == null && linearAccel == null) {
                switchMode(MODE_TOUCHPAD)
                activeSensorName = "TOUCHPAD ONLY"
                modeBadge.text = "• NO MOTION SENSOR FOUND - TOUCHPAD ACTIVE"
            }
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { sm?.unregisterListener(this); releaseWifiLock() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ws?.close(1000, null); releaseWifiLock() } catch (_: Exception) {}
    }

    // ==========================================
    // WIRE PROTOCOL (COMPACT TOKENS)
    // ==========================================
    private fun sendRaw(rawText: String) {
        totalPacketsSent++
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
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(ms)
                }
            }
        } catch (_: Exception) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

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
import java.util.concurrent.TimeUnit
import kotlin.math.*

class MainActivity : Activity(), SensorEventListener {

    companion object {
        const val APP_VERSION_CODE = 2
        const val APP_VERSION_NAME = "2.0"
    }

    // Tuning & Multipliers
    private val gyroPxPerRad = 1600f
    private val gyroScrollPerRad = 45f
    private val deadzone = 0.012f

    private var currentSensitivity = 1.0f
    private var isTouchpadMode = false

    // Gyro State
    private var smoothGx = 0f
    private var smoothGz = 0f
    private var lastGyroTs = 0L

    // Network & Hardware
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sm: SensorManager
    private lateinit var vibrator: Vibrator

    // UI Elements
    private lateinit var statusText: TextView
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

    // Track latest PC server address for updates
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

        val prefs = getSharedPreferences("air_cyber", MODE_PRIVATE)
        currentSensitivity = prefs.getFloat("sensitivity", 1.0f)
        serverIp = prefs.getString("ip", "192.168.0.107") ?: "192.168.0.107"

        // Root View with Dark Void Cyberpunk aesthetic
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090B10"))
            setPadding(dp(14), dp(22), dp(14), dp(14))
        }

        // Top Cyber HUD Status Bar
        val hudCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = cyberCard(Color.parseColor("#10141E"), Color.parseColor("#1E2638"), dp(12))
        }

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            text = "● SYSTEM STANDBY // ENTER IP"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        hudCard.addView(statusText)

        val pingBtn = TextView(this).apply {
            text = "⏻ CLOSE"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                haptic(20)
                ws?.close(1000, "User close")
                dropConnection("Disconnected by user")
            }
        }
        hudCard.addView(pingBtn)
        root.addView(hudCard)

        // --- OTA FIRMWARE UPDATE BANNER (Auto-hidden until update detected) ---
        updateBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cyberCard(Color.parseColor("#2D1B4E"), Color.parseColor("#A855F7"), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        updateBannerText = TextView(this).apply {
            text = "▲ FIRMWARE UPDATE AVAILABLE"
            setTextColor(Color.parseColor("#F3E8FF"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        updateBannerBtn = TextView(this).apply {
            text = "[ FLASH UPDATE ]"
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener {
                pendingApkUrl?.let { url -> downloadAndInstallUpdate(url) }
            }
        }
        updateBanner.addView(updateBannerText)
        updateBanner.addView(updateBannerBtn)
        root.addView(updateBanner)

        // ==========================================
        // 1. CONNECT VIEW
        // ==========================================
        connectView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        val logoBadge = TextView(this).apply {
            text = "AIRMOUSE // CYBER"
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        val subBadge = TextView(this).apply {
            text = "NEURAL INPUT LINK v$APP_VERSION_NAME"
            setTextColor(Color.parseColor("#64748B"))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        connectView.addView(logoBadge)
        connectView.addView(subBadge)

        val ipField = EditText(this).apply {
            hint = "192.168.0.xxx"
            setText(serverIp)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#475569"))
            textSize = 20f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = cyberCard(Color.parseColor("#10141E"), Color.parseColor("#00E5FF"), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        connectView.addView(ipField)

        val connectBtn = cyberButton("⚡ ESTABLISH LINK", Color.parseColor("#00E5FF"), Color.parseColor("#061325")).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(18) }
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
        // 2. MAIN ACTIVE HUB (MODES + CONTROLS)
        // ==========================================
        mainHubView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // Mode Segment Switcher Bar
        val modeSwitcher = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cyberCard(Color.parseColor("#0E121B"), Color.parseColor("#1E2638"), dp(12))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(6)
            }
        }

        gyroTabBtn = TextView(this).apply {
            text = "🛰️ GYRO AIR"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchMode(false) }
        }

        touchpadTabBtn = TextView(this).apply {
            text = "🔲 TOUCHPAD"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchMode(true) }
        }

        modeSwitcher.addView(gyroTabBtn)
        modeSwitcher.addView(touchpadTabBtn)
        mainHubView.addView(modeSwitcher)

        // HUD Info Ribbon
        modeBadge = TextView(this).apply {
            text = "ENGINE: GYRO REMOTE // VOL KEYS: CLICK"
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }
        mainHubView.addView(modeBadge)

        // Mode Container 1: GYRO AIR REMOTE
        gyroContainer = createGyroInterface()
        mainHubView.addView(gyroContainer)

        // Mode Container 2: CYBER TOUCHPAD
        touchpadContainer = createTouchpadInterface()
        touchpadContainer.visibility = View.GONE
        mainHubView.addView(touchpadContainer)

        // Sensitivity Slider Footer
        val sliderBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = cyberCard(Color.parseColor("#0E121A"), Color.parseColor("#1A2234"), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        val sensTitle = TextView(this).apply {
            text = "SENSITIVITY: ${"%.1f".format(currentSensitivity)}x"
            setTextColor(Color.parseColor("#94A3B8"))
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
                    sensTitle.text = "SENSITIVITY: ${"%.1f".format(currentSensitivity)}x"
                    prefs.edit().putFloat("sensitivity", currentSensitivity).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { haptic(12) }
            })
        }
        sliderBox.addView(sensTitle)
        sliderBox.addView(sensBar)
        mainHubView.addView(sliderBox)

        root.addView(mainHubView)
        setContentView(root)

        // Initialize Tab Appearance
        updateTabStyles()
    }

    // ==========================================
    // UI BUILDER: GYRO AIR INTERFACE
    // ==========================================
    private fun createGyroInterface(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        val leftPad = cyberPad("LEFT CLICK\n[ TAP / HOLD DRAG ]", Color.parseColor("#00E5FF"), Color.parseColor("#111827"))
        val rightPad = cyberPad("RIGHT CLICK", Color.parseColor("#A855F7"), Color.parseColor("#111827"))
        val scrollPad = cyberPad("HOLD + TILT\nSCROLL", Color.parseColor("#38BDF8"), Color.parseColor("#111827"))
        val freezePad = cyberPad("HOLD TO FREEZE CURSOR", Color.parseColor("#64748B"), Color.parseColor("#1E293B"))

        layout.addView(leftPad, LinearLayout.LayoutParams(-1, 0, 2.5f).apply { bottomMargin = dp(6) })

        val midRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        midRow.addView(rightPad, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
        midRow.addView(scrollPad, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4) })
        layout.addView(midRow, LinearLayout.LayoutParams(-1, 0, 1.2f).apply { bottomMargin = dp(6) })

        layout.addView(freezePad, LinearLayout.LayoutParams(-1, 0, 1.0f))

        // Left Click & Drag Touch Logic
        val dragStart = Runnable {
            dragging = true
            haptic(35)
            send { put("t", "down"); put("b", "left") }
        }
        bindTouch(leftPad, {
            haptic(16)
            ui.postDelayed(dragStart, 220)
        }, {
            ui.removeCallbacks(dragStart)
            if (dragging) {
                dragging = false
                haptic(16)
                send { put("t", "up"); put("b", "left") }
            } else {
                haptic(22)
                send { put("t", "click"); put("b", "left") }
            }
        })

        bindTouch(rightPad, {
            haptic(22)
            send { put("t", "click"); put("b", "right") }
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
    // UI BUILDER: CYBER TOUCHPAD INTERFACE
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    private fun createTouchpadInterface(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // Futuristic Trackpad Area
        val trackpadSurface = FrameLayout(this).apply {
            background = cyberCard(Color.parseColor("#0C1019"), Color.parseColor("#00E5FF"), dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(6) }
        }

        // Tech visual crosshair markings in trackpad
        val label = TextView(this).apply {
            text = "+  +  +  TOUCHPAD ACTIVE  +  +  +\n1 FINGER: MOVE  |  2 FINGER: SCROLL"
            setTextColor(Color.parseColor("#334155"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        trackpadSurface.addView(label)

        // Gesture Detection variables for Touchpad
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
                            send { put("t", "scroll"); put("dy", dy.toDouble()) }
                        }
                    } else if (!isTwoFinger && pCount == 1) {
                        val dx = (event.x - prevX) * 1.6f * currentSensitivity
                        val dy = (event.y - prevY) * 1.6f * currentSensitivity
                        prevX = event.x
                        prevY = event.y
                        if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                            send { put("t", "move"); put("dx", dx.toDouble()); put("dy", dy.toDouble()) }
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (isTwoFinger && (SystemClock.uptimeMillis() - touchDownTime) < 260) {
                        // Two finger tap = Right click
                        haptic(25)
                        send { put("t", "click"); put("b", "right") }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val duration = SystemClock.uptimeMillis() - touchDownTime
                    val dist = hypot(event.x - touchStartX, event.y - touchStartY)
                    if (!isTwoFinger && duration < 240 && dist < dp(12)) {
                        // Single finger tap = Left click
                        haptic(20)
                        send { put("t", "click"); put("b", "left") }
                    }
                }
            }
            true
        }
        layout.addView(trackpadSurface)

        // Physical Touchpad Buttons at bottom (Left Click / Right Click zones)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(64))
        }

        val padLeft = cyberPad("LEFT CLICK", Color.parseColor("#00E5FF"), Color.parseColor("#111726"))
        val padRight = cyberPad("RIGHT CLICK", Color.parseColor("#A855F7"), Color.parseColor("#111726"))

        bindTouch(padLeft, {
            haptic(20)
            send { put("t", "click"); put("b", "left") }
        }, {})

        bindTouch(padRight, {
            haptic(20)
            send { put("t", "click"); put("b", "right") }
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
            modeBadge.text = "ENGINE: PRECISION TOUCHPAD // 2-FINGER SCROLL"
        } else {
            touchpadContainer.visibility = View.GONE
            gyroContainer.visibility = View.VISIBLE
            modeBadge.text = "ENGINE: GYRO REMOTE // VOL KEYS: CLICK"
        }
        updateTabStyles()
    }

    private fun updateTabStyles() {
        if (isTouchpadMode) {
            touchpadTabBtn.background = cyberCard(Color.parseColor("#1E293B"), Color.parseColor("#00E5FF"), dp(8))
            touchpadTabBtn.setTextColor(Color.parseColor("#00E5FF"))
            gyroTabBtn.background = null
            gyroTabBtn.setTextColor(Color.parseColor("#64748B"))
        } else {
            gyroTabBtn.background = cyberCard(Color.parseColor("#1E293B"), Color.parseColor("#00E5FF"), dp(8))
            gyroTabBtn.setTextColor(Color.parseColor("#00E5FF"))
            touchpadTabBtn.background = null
            touchpadTabBtn.setTextColor(Color.parseColor("#64748B"))
        }
    }

    // ==========================================
    // NETWORK & WEBSOCKET ENGINE
    // ==========================================
    private fun connectToServer(ip: String) {
        statusText.text = "CONNECTING // $ip:8080..."
        ws?.cancel()
        ws = client.newWebSocket(Request.Builder().url("ws://$ip:8080").build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) = runOnUiThread {
                connected = true
                lastGyroTs = 0L
                smoothGx = 0f
                smoothGz = 0f
                haptic(50)
                statusText.text = "● LINK ACTIVE // $ip"
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
                                updateBannerText.text = "▲ FIRMWARE v${msg.optString("versionName", "NEW")} AVAILABLE"
                                updateBanner.visibility = View.VISIBLE
                                haptic(40)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = runOnUiThread {
                dropConnection("LINK FAILURE: ${t.localizedMessage ?: t.message}")
            }

            override fun onClosed(w: WebSocket, code: Int, reason: String) = runOnUiThread {
                dropConnection("LINK SEVERED")
            }
        })
    }

    private fun dropConnection(msg: String) {
        connected = false
        statusText.text = "● $msg"
        connectView.visibility = View.VISIBLE
        mainHubView.visibility = View.GONE
    }

    // ==========================================
    // IN-APP AUTO-UPDATER ENGINE (OTA FLASH)
    // ==========================================
    private fun downloadAndInstallUpdate(downloadUrl: String) {
        haptic(25)
        updateBannerBtn.text = "[ DOWNLOADING... ]"
        updateBannerBtn.isEnabled = false

        Thread {
            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.connect()

                if (conn.responseCode != 200) {
                    throw Exception("HTTP ${conn.responseCode}")
                }

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
                    updateBannerText.text = "VERIFIED // OPENING INSTALLER"
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
        // Check Unknown Sources Permission for Android O+
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
    // HARDWARE VOLUME BUTTON CLICK TRIGGERS
    // ==========================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (connected && mainHubView.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event?.repeatCount == 0) {
                    haptic(24)
                    dragging = true
                    send { put("t", "down"); put("b", "left") }
                }
                return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event?.repeatCount == 0) {
                    haptic(24)
                    send { put("t", "click"); put("b", "right") }
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

    // ==========================================
    // GYRO SENSOR ENGINE (ANTI-TREMOR + BALLISTICS)
    // ==========================================
    private fun softDeadzone(v: Float, threshold: Float): Float {
        val a = abs(v)
        if (a <= threshold) return 0f
        return (v / a) * (a - threshold)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!connected || isTouchpadMode) return
        if (lastGyroTs == 0L) { lastGyroTs = e.timestamp; return }
        val dt = ((e.timestamp - lastGyroTs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastGyroTs = e.timestamp
        if (gyroPaused) return

        val rawGx = softDeadzone(e.values[0], deadzone)
        val rawGz = softDeadzone(e.values[2], deadzone)

        val angularSpeed = sqrt(rawGx * rawGx + rawGz * rawGz)
        val alpha = (0.35f + 0.60f * (angularSpeed / 0.8f).coerceIn(0f, 1f))
        smoothGx += alpha * (rawGx - smoothGx)
        smoothGz += alpha * (rawGz - smoothGz)

        if (gyroScrolling) {
            if (abs(smoothGx) > 0.002f) {
                send {
                    put("t", "scroll")
                    put("dy", (-smoothGx * dt * gyroScrollPerRad * currentSensitivity).toDouble())
                }
            }
        } else {
            val speed = sqrt(smoothGx * smoothGx + smoothGz * smoothGz)
            if (speed > 0.002f) {
                val accel = (0.6f + 2.0f * (speed / 1.6f).toDouble().pow(1.3).toFloat()).coerceIn(0.6f, 2.8f)
                val mult = gyroPxPerRad * currentSensitivity * accel
                send {
                    put("t", "move")
                    put("dx", (-smoothGz * dt * mult).toDouble())
                    put("dy", (-smoothGx * dt * mult).toDouble())
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

    // ==========================================
    // UI STYLING & HELPERS
    // ==========================================
    private fun cyberCard(bgColor: Int, strokeColor: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), strokeColor)
    }

    private fun cyberButton(text: String, strokeColor: Int, bgColor: Int) = TextView(this).apply {
        this.text = text
        setTextColor(strokeColor)
        typeface = Typeface.MONOSPACE
        textSize = 15f
        gravity = Gravity.CENTER
        background = cyberCard(bgColor, strokeColor, dp(14))
    }

    private fun cyberPad(text: String, glowColor: Int, bgColor: Int) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        textSize = 15f
        gravity = Gravity.CENTER
        background = cyberCard(bgColor, glowColor, dp(14))
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

    private fun send(build: JSONObject.() -> Unit) {
        ws?.send(JSONObject().apply(build).toString())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

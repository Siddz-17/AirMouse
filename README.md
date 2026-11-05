# AirMouse (Nothing OS Aesthetic) // N•01

Turn your Android smartphone into an optical-grade air mouse to control your Windows PC cursor with ultra-low latency and zero jitter. Features a Nothing OS-inspired design with pitch-black UI, monospace typography, and Nothing Red accents.

---

## Features

- **Dual-Engine Motion Tracking**:
  - **Primary**: Direct Hardware Gyroscope (`Sensor.TYPE_GYROSCOPE`) running at high frequency (`SENSOR_DELAY_FASTEST`).
  - **Secondary / Fallback**: 6-DOF Hardware Sensor Fusion (`Sensor.TYPE_GAME_ROTATION_VECTOR`) for devices without a discrete gyroscope IC.
  - Compatible with Android 12, 13, and 14 with `HIGH_SAMPLING_RATE_SENSORS` permissions.
- **Ultra-Smooth Pointer Ballistics**:
  - Continuous smoothstep deadzone to eliminate MEMS sensor noise and resting hand tremor.
  - Low-latency exponential smoothing filter (<8ms latency).
  - Exponential mouse ballistics curve ($0.9\times$ micro-targeting up to $2.8\times$ for swift wrist flicks).
- **Live On-Screen Diagnostics HUD**:
  - Real-time terminal readout displaying active sensor engine, sampling rate (Hz), live $(dx, dy)$ coordinates, and total streaming packet counter.
- **Precision Touchpad Mode**:
  - 1-finger glide for cursor movement.
  - 2-finger swipe for smooth vertical scrolling.
  - Tap-to-click and dedicated click buttons.
- **Hardware Volume Buttons**:
  - **Volume Down**: Left Click (Tap = click, Hold >200ms = Drag, Release = Drop).
  - **Volume Up**: Right Click.
- **In-App Over-The-Air (OTA) Firmware Updates**:
  - Automatically notifies when a new server version is available.
  - Tap `[ FLASH ]` to download and install updates directly on the phone.
- **Ultra-Low Latency PC Server**:
  - 1ms Windows Kernel Timer (`timeBeginPeriod(1)`).
  - `TCP_NODELAY` WebSocket server on port `8080`.
  - In-memory OTA artifact HTTP server on port `8081`.
  - Windows Interactive Desktop Attachment (`WinSta0\Default`) with direct `SetCursorPos` sub-pixel accumulation.

---

## Tech Stack

- **Android App**: Kotlin, OkHttp 4.12, Android Sensors API
- **PC Server**: Python 3.12, `websockets`, `ctypes` (Win32 API)
- **Min SDK**: 26 (Android 8.0 Oreo) | **Target SDK**: 34 (Android 14)
- **CI/CD**: GitHub Actions automated build and APK signing with persistent keystore

---

## Quick Start

### 1. Start the PC Server
Double-click `run_server.bat` or run:
```bash
python server.py
```

### 2. Connect Your Phone
1. Connect your phone to the same Wi-Fi network as your PC.
2. Open the **AirMouse** app.
3. Enter your PC's IP address (displayed on the server console).
4. Tap **CONNECT TO PC**.

# AirMouse (Android)

Turn your Android smartphone into an air mouse to control your PC cursor and actions via device motion sensors (gyroscope) and WebSockets.

## Features

- **Motion Control**: Uses device gyroscope sensors for smooth cursor movement.
- **WebSocket Streaming**: Low-latency communication over local network.
- **Touch Controls**: Left-click, right-click, drag, scroll, and pause motion tracking.
- **Configurable**: Easily connect by specifying your PC's local IP address and port.

## Tech Stack

- **Platform**: Android (Kotlin)
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 34 (Android 14)
- **Network**: OkHttp (WebSocket client)
- **CI/CD**: GitHub Actions workflow for automated APK builds

## Build

You can build the debug APK using Gradle:

```bash
gradle assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

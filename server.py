import asyncio
import json
import ctypes
import ctypes.wintypes
import sys
import os
import socket
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import websockets

CURRENT_VERSION_CODE = 6
CURRENT_VERSION_NAME = "2.4"

# Ensure unbuffered console output
sys.stdout.reconfigure(line_buffering=True)

# Lock Windows Kernel timer resolution to 1ms (eliminates 15.6ms sleep jitter)
try:
    ctypes.windll.winmm.timeBeginPeriod(1)
except Exception:
    pass

# ==========================================
# SendInput Structures (absolute mouse input)
# ==========================================
PUL = ctypes.POINTER(ctypes.c_ulong)

class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx",          ctypes.c_long),
        ("dy",          ctypes.c_long),
        ("mouseData",   ctypes.c_ulong),
        ("dwFlags",     ctypes.c_ulong),
        ("time",        ctypes.c_ulong),
        ("dwExtraInfo", PUL),
    ]

class _INPUT_UNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT)]

class INPUT(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_ulong),
        ("_u",   _INPUT_UNION),
    ]

INPUT_MOUSE               = 0
MOUSEEVENTF_MOVE          = 0x0001
MOUSEEVENTF_LEFTDOWN      = 0x0002
MOUSEEVENTF_LEFTUP        = 0x0004
MOUSEEVENTF_RIGHTDOWN     = 0x0008
MOUSEEVENTF_RIGHTUP       = 0x0010
MOUSEEVENTF_WHEEL         = 0x0800
MOUSEEVENTF_ABSOLUTE      = 0x8000
MOUSEEVENTF_VIRTUALDESK   = 0x4000   # needed for multi-monitor absolute

user32 = ctypes.windll.user32

# Screen dimensions (read once at startup)
SM_CXVIRTUALSCREEN = 78
SM_CYVIRTUALSCREEN = 79
SM_XVIRTUALSCREEN  = 76
SM_YVIRTUALSCREEN  = 77

SCREEN_W = user32.GetSystemMetrics(0)   # primary width
SCREEN_H = user32.GetSystemMetrics(1)   # primary height

# Cursor tracking in pixel space
cur_x = float(SCREEN_W) / 2.0
cur_y = float(SCREEN_H) / 2.0

# Sub-pixel accumulators
accum_x = 0.0
accum_y = 0.0

move_counter = 0

def _send_input_move_absolute(px: int, py: int):
    """Send an absolute mouse move via SendInput — works from ANY Windows session."""
    # Normalize to 0-65535 range (SendInput absolute coordinates)
    norm_x = int(px * 65535 / max(1, SCREEN_W - 1))
    norm_y = int(py * 65535 / max(1, SCREEN_H - 1))
    norm_x = max(0, min(65535, norm_x))
    norm_y = max(0, min(65535, norm_y))

    mi = MOUSEINPUT(
        dx=norm_x,
        dy=norm_y,
        mouseData=0,
        dwFlags=MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE,
        time=0,
        dwExtraInfo=PUL(ctypes.c_ulong(0))
    )
    inp = INPUT(type=INPUT_MOUSE, _u=_INPUT_UNION(mi=mi))
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))


def _send_input_button(flags: int, wheel_delta: int = 0):
    mi = MOUSEINPUT(
        dx=0, dy=0,
        mouseData=ctypes.c_ulong(wheel_delta & 0xFFFFFFFF),
        dwFlags=flags,
        time=0,
        dwExtraInfo=PUL(ctypes.c_ulong(0))
    )
    inp = INPUT(type=INPUT_MOUSE, _u=_INPUT_UNION(mi=mi))
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))


def on_move(dx_str, dy_str):
    global cur_x, cur_y, accum_x, accum_y, move_counter
    try:
        dx = float(dx_str)
        dy = float(dy_str)
    except ValueError:
        return

    accum_x += dx
    accum_y += dy

    step_x = int(accum_x)
    step_y = int(accum_y)

    if step_x != 0 or step_y != 0:
        accum_x -= step_x
        accum_y -= step_y
        cur_x = max(0.0, min(float(SCREEN_W - 1), cur_x + step_x))
        cur_y = max(0.0, min(float(SCREEN_H - 1), cur_y + step_y))
        _send_input_move_absolute(int(cur_x), int(cur_y))

        move_counter += 1
        if move_counter % 100 == 0:
            print(f"[Move #{move_counter}] Cursor at ({int(cur_x)}, {int(cur_y)})", flush=True)


def on_scroll(dy_str):
    try:
        delta = int(round(float(dy_str) * 120))
    except ValueError:
        return
    if delta != 0:
        _send_input_button(MOUSEEVENTF_WHEEL, delta)


def on_click(button: str):
    print(f"[Click] {button}", flush=True)
    if button == "left":
        _send_input_button(MOUSEEVENTF_LEFTDOWN)
        _send_input_button(MOUSEEVENTF_LEFTUP)
    elif button == "right":
        _send_input_button(MOUSEEVENTF_RIGHTDOWN)
        _send_input_button(MOUSEEVENTF_RIGHTUP)


def on_down(button: str):
    print(f"[Down] {button}", flush=True)
    if button == "left":
        _send_input_button(MOUSEEVENTF_LEFTDOWN)


def on_up(button: str):
    print(f"[Up] {button}", flush=True)
    if button == "left":
        _send_input_button(MOUSEEVENTF_LEFTUP)


# ==========================================
# OTA HTTP UPDATE SERVER (PORT 8081)
# ==========================================
class OTAHandler(SimpleHTTPRequestHandler):
    def do_GET(self):
        base_dir = os.path.dirname(os.path.abspath(__file__))
        apk_path = os.path.join(base_dir, "build-artifacts", "AirMouse-apk", "app-debug.apk")

        if self.path in ("/AirMouse.apk", "/app-debug.apk"):
            if os.path.exists(apk_path):
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Disposition", 'attachment; filename="AirMouse.apk"')
                self.send_header("Content-Length", str(os.path.getsize(apk_path)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                with open(apk_path, "rb") as f:
                    self.wfile.write(f.read())
                print(f"[OTA] Delivered AirMouse.apk ({os.path.getsize(apk_path)} bytes) to phone", flush=True)
                return
            else:
                self.send_error(404, "APK artifact not found on server")
                return

        elif self.path == "/version":
            data = json.dumps({"versionCode": CURRENT_VERSION_CODE, "versionName": CURRENT_VERSION_NAME}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)
            return

        self.send_error(404, "Not Found")

    def do_HEAD(self):
        self.do_GET()

    def log_message(self, format, *args):
        pass


def run_http_ota(port=8081):
    server = ThreadingHTTPServer(("0.0.0.0", port), OTAHandler)
    server.serve_forever()


# ==========================================
# ULTRA-LOW LATENCY WEBSOCKET (PORT 8080)
# ==========================================
async def handler(websocket):
    try:
        sock = websocket.transport.get_extra_info("socket")
        if sock:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except Exception:
        pass

    client_ip = websocket.remote_address[0]
    print(f"\n[+] Phone connected from: {client_ip}", flush=True)

    # Detect our own outbound IP towards the phone
    server_ip = "127.0.0.1"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect((client_ip, 80))
        server_ip = s.getsockname()[0]
        s.close()
    except Exception:
        pass

    # Send init info for OTA updater
    init_payload = json.dumps({
        "t": "init",
        "versionCode": CURRENT_VERSION_CODE,
        "versionName": CURRENT_VERSION_NAME,
        "apkUrl": f"http://{server_ip}:8081/AirMouse.apk"
    })
    try:
        await websocket.send(init_payload)
    except Exception:
        pass

    try:
        async for raw in websocket:
            if raw.startswith("m "):
                parts = raw[2:].split()
                if len(parts) == 2:
                    on_move(parts[0], parts[1])
            elif raw.startswith("s "):
                on_scroll(raw[2:])
            elif raw.startswith("c "):
                btn = "left" if raw[2:].strip() == "l" else "right"
                on_click(btn)
            elif raw.startswith("d "):
                on_down("left")
            elif raw.startswith("u "):
                on_up("left")
            elif raw.startswith("{"):
                try:
                    msg = json.loads(raw)
                    action = msg.get("t")
                    if action == "move":
                        on_move(msg.get("dx", 0), msg.get("dy", 0))
                    elif action == "scroll":
                        on_scroll(msg.get("dy", 0))
                    elif action == "click":
                        on_click(msg.get("b", "left"))
                    elif action == "down":
                        on_down(msg.get("b", "left"))
                    elif action == "up":
                        on_up(msg.get("b", "left"))
                except Exception as e:
                    print(f"[!] JSON error: {e}", flush=True)
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        print(f"[-] Phone disconnected: {client_ip}\n", flush=True)


async def main():
    ws_port = 8080
    ota_port = 8081

    ota_thread = threading.Thread(target=run_http_ota, args=(ota_port,), daemon=True)
    ota_thread.start()

    print("=" * 65, flush=True)
    print(f"AirMouse Nothing Engine v{CURRENT_VERSION_NAME} (SendInput Absolute)")
    print(f"  Screen       : {SCREEN_W}x{SCREEN_H}")
    print(f"  WebSocket    : ws://0.0.0.0:{ws_port}")
    print(f"  OTA Flash    : http://0.0.0.0:{ota_port}/AirMouse.apk")
    print(f"  Input method : SendInput ABSOLUTE (session-independent)")
    print("Waiting for phone connection...", flush=True)
    print("=" * 65, flush=True)

    async with websockets.serve(handler, "0.0.0.0", ws_port, ping_interval=None, ping_timeout=None):
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")

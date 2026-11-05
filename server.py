import asyncio
import json
import ctypes
import sys
import os
import socket
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import websockets

CURRENT_VERSION_CODE = 12
CURRENT_VERSION_NAME = "3.0"

# Ensure unbuffered console output
sys.stdout.reconfigure(line_buffering=True)

# Lock Windows Kernel timer resolution to 1ms (eliminates 15.6ms sleep jitter)
try:
    ctypes.windll.winmm.timeBeginPeriod(1)
except Exception:
    pass

user32 = ctypes.windll.user32

WINSTA_ALL = 0x37F
DESKTOP_ALL = 0x1FF

# Windows mouse constants
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_WHEEL = 0x0800

class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

def attach_to_interactive_desktop():
    """Attach current thread and process to interactive WinSta0\\Default desktop."""
    try:
        hwinsta0 = user32.OpenWindowStationW("WinSta0", False, WINSTA_ALL)
        if hwinsta0:
            user32.SetProcessWindowStation(hwinsta0)
        hdesk = user32.OpenDesktopW("Default", 0, False, DESKTOP_ALL)
        if hdesk:
            user32.SetThreadDesktop(hdesk)
            return True
    except Exception as e:
        print(f"[!] Desktop attach error: {e}", flush=True)
    return False

# Initialize desktop attachment
attach_to_interactive_desktop()

SCREEN_W = user32.GetSystemMetrics(0)
SCREEN_H = user32.GetSystemMetrics(1)

accum_x = 0.0
accum_y = 0.0
move_counter = 0

def get_cursor_pos():
    pt = POINT()
    user32.GetCursorPos(ctypes.byref(pt))
    return pt.x, pt.y

def on_move(dx_str, dy_str):
    global accum_x, accum_y, move_counter
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

        cx, cy = get_cursor_pos()
        target_x = max(0, min(SCREEN_W - 1, cx + step_x))
        target_y = max(0, min(SCREEN_H - 1, cy + step_y))

        user32.SetCursorPos(target_x, target_y)
        user32.mouse_event(MOUSEEVENTF_MOVE, 0, 0, 0, 0)

        move_counter += 1
        if move_counter % 50 == 0:
            print(f"[Move #{move_counter}] dx:{dx:+.2f} dy:{dy:+.2f} -> Cursor: ({target_x}, {target_y})", flush=True)

def on_scroll(dy_str):
    try:
        delta = int(round(float(dy_str) * 120))
    except ValueError:
        return
    if delta != 0:
        user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, delta, 0)

def on_click(button: str):
    print(f"[Click] {button}", flush=True)
    if button == "left":
        user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
        user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
    elif button == "right":
        user32.mouse_event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
        user32.mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)

def on_down(button: str):
    print(f"[Down] {button}", flush=True)
    if button == "left":
        user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)

def on_up(button: str):
    print(f"[Up] {button}", flush=True)
    if button == "left":
        user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)

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
    attach_to_interactive_desktop()

    try:
        sock = websocket.transport.get_extra_info("socket")
        if sock:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except Exception:
        pass

    client_ip = websocket.remote_address[0]
    cx, cy = get_cursor_pos()
    print(f"\n[+] Phone connected from: {client_ip} | Starting cursor: ({cx}, {cy})", flush=True)

    server_ip = "192.168.0.107"
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
            # Wire tokens: m, s, c, d, u, recenter
            if raw.startswith("m "):
                parts = raw[2:].split()
                if len(parts) == 2:
                    on_move(parts[0], parts[1])
            elif raw.startswith("recenter"):
                user32.SetCursorPos(SCREEN_W // 2, SCREEN_H // 2)
                print(f"[Recenter] Cursor set to screen center ({SCREEN_W // 2}, {SCREEN_H // 2})", flush=True)
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

def get_local_ips():
    ips = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    # Sort so 192.168.x or 10.x LAN comes first
    ips.sort(key=lambda x: (not x.startswith("192.168."), not x.startswith("10."), x))
    return ips if ips else ["127.0.0.1"]

async def main():
    attach_to_interactive_desktop()
    ws_port = 8080
    ota_port = 8081
    local_ips = get_local_ips()
    primary_ip = local_ips[0]

    ota_thread = threading.Thread(target=run_http_ota, args=(ota_port,), daemon=True)
    ota_thread.start()

    cx, cy = get_cursor_pos()
    print("=" * 65, flush=True)
    print(f"AirMouse Nothing Engine v{CURRENT_VERSION_NAME} (1ms Timer + Interactive Desktop)")
    print(f"  Screen       : {SCREEN_W}x{SCREEN_H} (Current cursor: {cx}, {cy})")
    print(f"  Wi-Fi IP     : {primary_ip}")
    if len(local_ips) > 1:
        print(f"  Other IPs    : {', '.join(local_ips[1:])}")
    print(f"  WebSocket    : ws://{primary_ip}:{ws_port}")
    print(f"  OTA Flash    : http://{primary_ip}:{ota_port}/AirMouse.apk")
    print("Waiting for phone connection...", flush=True)
    print("=" * 65, flush=True)

    async with websockets.serve(handler, "0.0.0.0", ws_port, ping_interval=None, ping_timeout=None):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")

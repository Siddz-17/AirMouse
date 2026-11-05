import asyncio
import json
import ctypes
import sys
import socket
import websockets

# Ensure unbuffered console output
sys.stdout.reconfigure(line_buffering=True)

# Windows mouse_event constants
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_WHEEL = 0x0800
DESKTOP_ALL = 0x01FF

user32 = ctypes.windll.user32

class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

def get_cursor_pos():
    pt = POINT()
    user32.GetCursorPos(ctypes.byref(pt))
    return pt.x, pt.y

def attach_to_default_desktop():
    try:
        hdesk = user32.OpenDesktopW("Default", 0, False, DESKTOP_ALL)
        if hdesk:
            user32.SetThreadDesktop(hdesk)
            return True
    except Exception as e:
        print(f"[!] Desktop attach error: {e}", flush=True)
    return False

accum_x = 0.0
accum_y = 0.0
move_counter = 0

def on_move(dx, dy):
    global accum_x, accum_y, move_counter
    accum_x += float(dx)
    accum_y += float(dy)
    step_x = int(accum_x)
    step_y = int(accum_y)
    if step_x != 0 or step_y != 0:
        accum_x -= step_x
        accum_y -= step_y
        user32.mouse_event(MOUSEEVENTF_MOVE, step_x, step_y, 0, 0)
        move_counter += 1
        if move_counter % 50 == 0:
            cx, cy = get_cursor_pos()
            print(f"[Move #{move_counter}] Cursor at ({cx}, {cy})", flush=True)

def on_scroll(dy):
    delta = int(round(float(dy) * 120))
    if delta != 0:
        user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, delta, 0)

def on_click(button):
    print(f"[Click] {button}", flush=True)
    if button == "left":
        user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
        user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
    elif button == "right":
        user32.mouse_event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
        user32.mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)

def on_down(button):
    print(f"[Down] {button}", flush=True)
    if button == "left":
        user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)

def on_up(button):
    print(f"[Up] {button}", flush=True)
    if button == "left":
        user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)

async def handler(websocket):
    attach_to_default_desktop()
    # Enable TCP_NODELAY for lowest possible ping over Wi-Fi
    try:
        sock = websocket.transport.get_extra_info("socket")
        if sock:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except Exception:
        pass

    client_ip = websocket.remote_address[0]
    cx, cy = get_cursor_pos()
    print(f"\n[+] Phone connected from: {client_ip} | Cursor at: ({cx}, {cy})", flush=True)
    try:
        async for raw in websocket:
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
                print(f"[!] Parse error: {e}", flush=True)
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        print(f"[-] Phone disconnected: {client_ip}\n", flush=True)

async def main():
    attach_to_default_desktop()
    port = 8080
    host = "0.0.0.0"
    cx, cy = get_cursor_pos()
    print("=" * 60, flush=True)
    print(f"AirMouse Ultra-Low Latency Server on ws://0.0.0.0:{port}", flush=True)
    print(f"Connected to Windows desktop. Current cursor: ({cx}, {cy})", flush=True)
    print("Waiting for phone to connect...", flush=True)
    print("=" * 60, flush=True)
    async with websockets.serve(handler, host, port, ping_interval=None, ping_timeout=None):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")

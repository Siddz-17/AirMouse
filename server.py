import asyncio
import json
import win32api
import win32con
import websockets

def on_move(dx, dy):
    win32api.mouse_event(win32con.MOUSEEVENTF_MOVE, int(round(dx)), int(round(dy)), 0, 0)

def on_scroll(dy):
    # Windows standard wheel delta is 120 per notch
    delta = int(round(dy * 120))
    if delta != 0:
        win32api.mouse_event(win32con.MOUSEEVENTF_WHEEL, 0, 0, delta, 0)

def on_click(button):
    if button == "left":
        win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
        win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
    elif button == "right":
        win32api.mouse_event(win32con.MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
        win32api.mouse_event(win32con.MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)

def on_down(button):
    if button == "left":
        win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)

def on_up(button):
    if button == "left":
        win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)

async def handler(websocket):
    client_ip = websocket.remote_address[0]
    print(f"[+] Phone connected from: {client_ip}")
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
                print(f"[!] Message parse error: {e}")
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        print(f"[-] Phone disconnected: {client_ip}")

async def main():
    host = "0.0.0.0"
    port = 8080
    print("=" * 55)
    print("        AirMouse PC Server (Port 8080)")
    print("=" * 55)
    print(f"Listening on all interfaces: ws://0.0.0.0:{port}")
    print("Waiting for phone connection...")
    print("Press Ctrl+C to stop.")
    print("=" * 55)
    async with websockets.serve(handler, host, port):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")

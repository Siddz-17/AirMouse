import asyncio
import json
import ctypes
import sys
import os
import websockets

# Ensure immediate unbuffered output
sys.stdout.reconfigure(line_buffering=True)

# Windows mouse_event constants
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_WHEEL = 0x0800

user32 = ctypes.windll.user32

# Sub-pixel accumulator to prevent dropping fractional movements
accum_x = 0.0
accum_y = 0.0
move_counter = 0

def on_move(dx, dy):
    global accum_x, accum_y, move_counter
    # Sensitivity multiplier can be adjusted if needed
    accum_x += float(dx)
    accum_y += float(dy)
    step_x = int(accum_x)
    step_y = int(accum_y)
    if step_x != 0 or step_y != 0:
        accum_x -= step_x
        accum_y -= step_y
        user32.mouse_event(MOUSEEVENTF_MOVE, step_x, step_y, 0, 0)
        move_counter += 1
        if move_counter % 20 == 0:
            print(f"[Move] dx={step_x}, dy={step_y} (sent {move_counter} moves)", flush=True)

def on_scroll(dy):
    delta = int(round(float(dy) * 120))
    if delta != 0:
        user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, delta, 0)
        print(f"[Scroll] delta={delta}", flush=True)

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
    client_ip = websocket.remote_address[0]
    print(f"\n[+] Phone connected from: {client_ip}", flush=True)
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
                else:
                    print(f"[?] Unknown action: {msg}", flush=True)
            except Exception as e:
                print(f"[!] Error handling message: {e}", flush=True)
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        print(f"[-] Phone disconnected: {client_ip}\n", flush=True)

async def main():
    port = 8080
    host = "0.0.0.0"
    print("=" * 60, flush=True)
    print(f"AirMouse Server running on ws://0.0.0.0:{port}", flush=True)
    print("Waiting for phone to connect...", flush=True)
    print("=" * 60, flush=True)
    async with websockets.serve(handler, host, port):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")

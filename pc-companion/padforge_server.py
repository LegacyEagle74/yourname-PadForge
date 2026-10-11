#!/usr/bin/env python3
"""
PadForge PC companion.

Receives input events from the PadForge Android app over Wi-Fi and turns them
into real keyboard, mouse and Xbox 360 controller input on this PC.

  python padforge_server.py              # normal mode (Windows)
  python padforge_server.py --dry-run    # just print what would be pressed (any OS)

  python padforge_server.py --show-code  # print the pairing code and exit
  python padforge_server.py --new-code   # make a new pairing code (old phones must re-enter it)

Protocol (TCP, one UTF-8 text line per event, default port 47800):
  HELLO <version> <code>     -> "WELCOME <version> <hostname> <gamepad 0|1>", or "DENIED <reason>"
                                and the connection is closed. Nothing else is accepted
                                until the pairing code has been checked.
  PING <token>               -> server answers "PONG <token>"
  K  <KeyName> <0|1>         keyboard key up/down       (e.g. "K W 1")
  MB <Left|Right|Middle|X1|X2> <0|1>   mouse button
  MM <dx> <dy>               relative mouse move in pixels
  MW <ticks>                 vertical wheel, +up / -down
  MH <ticks>                 horizontal wheel, +right / -left
  GB <Button> <0|1>          gamepad button (A B X Y LB RB Back Start LS RS Guide Up Down Left Right)
  GS <L|R> <x> <y>           gamepad stick, floats -1..1, +y is up
  GT <L|R> <value>           gamepad trigger, float 0..1
  RESET                      release everything this phone is holding

Discovery: the phone broadcasts "PADFORGE_DISCOVER" over UDP to port 47801 and
this server answers "PADFORGE_SERVER <tcp-port> <hostname>".

Pairing: the companion keeps a random pairing code in padforge_config.json next
to this script. A phone must send it before any input is accepted, so nobody
else who can reach the port can type on this PC.

Remote use (e.g. with Parsec while away): put the PC and phone on the same
Tailscale network and connect to the PC's Tailscale address (100.x.y.z). Don't
forward this port on your router: the protocol itself is not encrypted.

Safety: everything a phone is holding is released when it disconnects, so a
dropped Wi-Fi connection never leaves a key stuck down.
"""

import argparse
import collections
import hmac
import ipaddress
import json
import os
import secrets
import socket
import sys
import threading
import time

PROTOCOL_VERSION = 2
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "padforge_config.json")
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # no 0/O or 1/I
TAILSCALE_NET = ipaddress.ip_network("100.64.0.0/10")
DEFAULT_PORT = 47800
DISCOVERY_PORT = 47801

# --------------------------------------------------------------------------
# Key names shared with the Android app (KeyNames.kt). Name -> Windows VK code.
# --------------------------------------------------------------------------
KEYS = {}
for _c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
    KEYS[_c] = ord(_c)
for _d in range(10):
    KEYS[str(_d)] = 0x30 + _d
for _f in range(1, 25):
    KEYS["F%d" % _f] = 0x70 + _f - 1
for _n in range(10):
    KEYS["Num%d" % _n] = 0x60 + _n
KEYS.update({
    "Space": 0x20, "Enter": 0x0D, "Escape": 0x1B, "Tab": 0x09, "Backspace": 0x08,
    "CapsLock": 0x14, "LShift": 0xA0, "RShift": 0xA1, "LCtrl": 0xA2, "RCtrl": 0xA3,
    "LAlt": 0xA4, "RAlt": 0xA5, "LWin": 0x5B, "RWin": 0x5C, "Menu": 0x5D,
    "Up": 0x26, "Down": 0x28, "Left": 0x25, "Right": 0x27,
    "Insert": 0x2D, "Delete": 0x2E, "Home": 0x24, "End": 0x23,
    "PageUp": 0x21, "PageDown": 0x22, "PrintScreen": 0x2C, "ScrollLock": 0x91,
    "Pause": 0x13, "NumLock": 0x90,
    "NumMultiply": 0x6A, "NumAdd": 0x6B, "NumSubtract": 0x6D, "NumDecimal": 0x6E,
    "NumDivide": 0x6F, "NumEnter": 0x0D,
    "Minus": 0xBD, "Equals": 0xBB, "LBracket": 0xDB, "RBracket": 0xDD,
    "Semicolon": 0xBA, "Quote": 0xDE, "Backquote": 0xC0, "Backslash": 0xDC,
    "Comma": 0xBC, "Period": 0xBE, "Slash": 0xBF,
    "VolumeMute": 0xAD, "VolumeDown": 0xAE, "VolumeUp": 0xAF,
    "MediaNext": 0xB0, "MediaPrev": 0xB1, "MediaPlayPause": 0xB3,
})
# Keys whose scancode needs the "extended" (E0) flag.
EXTENDED = {"Up", "Down", "Left", "Right", "Insert", "Delete", "Home", "End",
            "PageUp", "PageDown", "RCtrl", "RAlt", "LWin", "RWin", "Menu",
            "NumDivide", "NumEnter"}
# Keys that are sent by virtual-key code only (scancodes are odd or absent).
VK_ONLY = {"Pause", "NumLock", "PrintScreen", "VolumeMute", "VolumeDown", "VolumeUp",
           "MediaNext", "MediaPrev", "MediaPlayPause"}

MOUSE_BUTTONS = ("Left", "Right", "Middle", "X1", "X2")
PAD_BUTTONS = ("A", "B", "X", "Y", "LB", "RB", "Back", "Start", "LS", "RS", "Guide",
               "Up", "Down", "Left", "Right")


def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


# --------------------------------------------------------------------------
# Output backends
# --------------------------------------------------------------------------
class DryRunBackend:
    name = "dry-run (printing only)"

    def key(self, name, down):
        print("  key   %-12s %s" % (name, "DOWN" if down else "up"))

    def mouse_button(self, name, down):
        print("  mouse %-12s %s" % (name, "DOWN" if down else "up"))

    def mouse_move(self, dx, dy):
        print("  mouse move   %+d %+d" % (dx, dy))

    def wheel(self, ticks, horizontal):
        print("  wheel %s %+d" % ("horizontal" if horizontal else "vertical", ticks))


class WindowsBackend:
    """Keyboard and mouse through SendInput, using scancodes so games see them."""
    name = "Windows SendInput"

    def __init__(self):
        import ctypes
        from ctypes import wintypes
        self.ctypes = ctypes
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        ULONG_PTR = ctypes.c_size_t

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                        ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                        ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                        ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("dwExtraInfo", ULONG_PTR)]

        class HARDWAREINPUT(ctypes.Structure):
            _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
                        ("wParamH", wintypes.WORD)]

        class _U(ctypes.Union):
            _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

        class INPUT(ctypes.Structure):
            _anonymous_ = ("u",)
            _fields_ = [("type", wintypes.DWORD), ("u", _U)]

        self.INPUT, self.MOUSEINPUT, self.KEYBDINPUT = INPUT, MOUSEINPUT, KEYBDINPUT
        self.user32 = user32
        user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
        user32.SendInput.restype = wintypes.UINT
        user32.MapVirtualKeyW.argtypes = (wintypes.UINT, wintypes.UINT)
        user32.MapVirtualKeyW.restype = wintypes.UINT

        # Pre-compute (vk, scancode, extended) for every key.
        self.keymap = {}
        for name, vk in KEYS.items():
            if name in VK_ONLY:
                self.keymap[name] = (vk, 0, name in EXTENDED)
                continue
            sc = user32.MapVirtualKeyW(vk, 4)  # MAPVK_VK_TO_VSC_EX
            ext = name in EXTENDED or (sc & 0xFF00) in (0xE000, 0xE100)
            sc &= 0xFF
            if name == "NumEnter":
                sc = 0x1C
            self.keymap[name] = (vk, sc, ext)

    def _send(self, inp):
        if self.user32.SendInput(1, self.ctypes.byref(inp), self.ctypes.sizeof(inp)) != 1:
            err = self.ctypes.get_last_error()
            print("  SendInput failed (error %d). If the game runs as administrator, run this companion as administrator too." % err)

    def key(self, name, down):
        vk, sc, ext = self.keymap[name]
        flags = 0 if down else 0x0002              # KEYEVENTF_KEYUP
        if ext:
            flags |= 0x0001                        # KEYEVENTF_EXTENDEDKEY
        if sc:
            flags |= 0x0008                        # KEYEVENTF_SCANCODE
            ki = self.KEYBDINPUT(0, sc, flags, 0, 0)
        else:
            ki = self.KEYBDINPUT(vk, 0, flags, 0, 0)
        inp = self.INPUT(type=1)
        inp.ki = ki
        self._send(inp)

    def _mouse(self, flags, dx=0, dy=0, data=0):
        inp = self.INPUT(type=0)
        inp.mi = self.MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
        self._send(inp)

    def mouse_button(self, name, down):
        table = {"Left": (0x0002, 0x0004), "Right": (0x0008, 0x0010),
                 "Middle": (0x0020, 0x0040)}
        if name in table:
            self._mouse(table[name][0 if down else 1])
        else:  # X buttons
            self._mouse(0x0080 if down else 0x0100, data=1 if name == "X1" else 2)

    def mouse_move(self, dx, dy):
        self._mouse(0x0001, dx, dy)

    def wheel(self, ticks, horizontal):
        self._mouse(0x1000 if horizontal else 0x0800, data=ticks * 120)


def load_gamepad_factory():
    """Returns a function that creates a virtual Xbox 360 pad, or None."""
    try:
        import vgamepad as vg
    except Exception as e:  # not installed, or the ViGEmBus driver is missing
        print("Gamepad output unavailable: %s" % e)
        print("  -> install it with:  pip install vgamepad   (it also installs the ViGEmBus driver)")
        return None
    B = vg.XUSB_BUTTON
    buttons = {
        "A": B.XUSB_GAMEPAD_A, "B": B.XUSB_GAMEPAD_B, "X": B.XUSB_GAMEPAD_X, "Y": B.XUSB_GAMEPAD_Y,
        "LB": B.XUSB_GAMEPAD_LEFT_SHOULDER, "RB": B.XUSB_GAMEPAD_RIGHT_SHOULDER,
        "Back": B.XUSB_GAMEPAD_BACK, "Start": B.XUSB_GAMEPAD_START,
        "LS": B.XUSB_GAMEPAD_LEFT_THUMB, "RS": B.XUSB_GAMEPAD_RIGHT_THUMB,
        "Guide": B.XUSB_GAMEPAD_GUIDE,
        "Up": B.XUSB_GAMEPAD_DPAD_UP, "Down": B.XUSB_GAMEPAD_DPAD_DOWN,
        "Left": B.XUSB_GAMEPAD_DPAD_LEFT, "Right": B.XUSB_GAMEPAD_DPAD_RIGHT,
    }

    class XboxPad:
        def __init__(self):
            self.pad = vg.VX360Gamepad()

        def button(self, name, down):
            (self.pad.press_button if down else self.pad.release_button)(button=buttons[name])
            self.pad.update()

        def stick(self, side, x, y):
            (self.pad.left_joystick_float if side == "L" else self.pad.right_joystick_float)(
                x_value_float=x, y_value_float=y)
            self.pad.update()

        def trigger(self, side, v):
            (self.pad.left_trigger_float if side == "L" else self.pad.right_trigger_float)(value_float=v)
            self.pad.update()

        def close(self):
            try:
                self.pad.reset()
                self.pad.update()
            except Exception:
                pass
            del self.pad

    return XboxPad


class DryRunPad:
    def __init__(self):
        print("  (virtual Xbox pad created)")

    def button(self, name, down):
        print("  pad   %-12s %s" % (name, "DOWN" if down else "up"))

    def stick(self, side, x, y):
        print("  pad   stick %s %+.2f %+.2f" % (side, x, y))

    def trigger(self, side, v):
        print("  pad   trigger %s %.2f" % (side, v))

    def close(self):
        print("  (virtual Xbox pad removed)")


# --------------------------------------------------------------------------
# Pairing code
# --------------------------------------------------------------------------
def normalize_code(code):
    """Pairing codes are compared without dashes/spaces and case-insensitively."""
    return "".join(ch for ch in code.upper() if ch.isalnum())


def format_code(code):
    c = normalize_code(code)
    return "-".join(c[i:i + 4] for i in range(0, len(c), 4))


def load_config():
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_config(cfg):
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    os.replace(tmp, CONFIG_PATH)


def get_pairing_code(regenerate=False):
    cfg = load_config()
    code = normalize_code(cfg.get("pairing_code", ""))
    if regenerate or len(code) < 8:
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(8))
        cfg["pairing_code"] = code
        save_config(cfg)
    return code


# --------------------------------------------------------------------------
# Shared keyboard/mouse state with reference counting, so two on-screen
# buttons bound to the same key (or two phones) don't release each other.
# --------------------------------------------------------------------------
class Output:
    def __init__(self, backend):
        self.backend = backend
        self.lock = threading.Lock()
        self.refs = collections.Counter()

    def press(self, kind, name):
        with self.lock:
            self.refs[(kind, name)] += 1
            if self.refs[(kind, name)] == 1:
                self._emit(kind, name, True)

    def release(self, kind, name):
        with self.lock:
            if self.refs[(kind, name)] <= 0:
                return
            self.refs[(kind, name)] -= 1
            if self.refs[(kind, name)] == 0:
                del self.refs[(kind, name)]
                self._emit(kind, name, False)

    def _emit(self, kind, name, down):
        if kind == "K":
            self.backend.key(name, down)
        else:
            self.backend.mouse_button(name, down)

    def move(self, dx, dy):
        with self.lock:
            self.backend.mouse_move(dx, dy)

    def wheel(self, ticks, horizontal):
        with self.lock:
            self.backend.wheel(ticks, horizontal)


class Session(threading.Thread):
    def __init__(self, conn, addr, output, pad_factory, verbose, code):
        super().__init__(daemon=True)
        self.conn, self.addr = conn, addr
        self.output, self.pad_factory, self.verbose = output, pad_factory, verbose
        self.code = code            # None = pairing disabled
        self.authed = code is None
        self.denied = False
        self.held = collections.Counter()   # (kind, name) -> how many presses this phone holds
        self.pad = None
        self.pad_failed = False

    def log(self, msg):
        print("[%s] %s" % (self.addr[0], msg))

    def run(self):
        self.log("connected")
        self.conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.conn.settimeout(10.0)  # the phone pings every second; silence means it is gone
        buf = b""
        try:
            while True:
                data = self.conn.recv(4096)
                if not data:
                    break
                buf += data
                if not self.authed and len(buf) > 512:
                    break  # someone is sending junk without pairing
                while b"\n" in buf and not self.denied:
                    line, buf = buf.split(b"\n", 1)
                    line = line.decode("utf-8", "replace").strip()
                    if line:
                        try:
                            self.handle(line)
                        except Exception as e:
                            self.log("bad message %r: %s" % (line if self.authed else line.split()[0], e))
                if self.denied:
                    time.sleep(1.0)  # slow down code guessing
                    break
        except (OSError, socket.timeout) as e:
            self.log("connection lost (%s)" % e)
        finally:
            self.release_all()
            if self.pad:
                self.pad.close()
                self.pad = None
            try:
                self.conn.close()
            except OSError:
                pass
            if self.authed:
                self.log("disconnected, everything released")

    def reply(self, text):
        try:
            self.conn.sendall((text + "\n").encode("utf-8"))
        except OSError:
            pass

    def get_pad(self):
        if self.pad is None and not self.pad_failed:
            if self.pad_factory is None:
                self.pad_failed = True
                self.log("gamepad input ignored: gamepad output is not available")
            else:
                try:
                    self.pad = self.pad_factory()
                except Exception as e:
                    self.pad_failed = True
                    self.log("could not create a virtual gamepad: %s" % e)
        return self.pad

    def release_all(self):
        for (kind, name), n in list(self.held.items()):
            for _ in range(n):
                self.output.release(kind, name)
        self.held.clear()
        if self.pad:
            for b in PAD_BUTTONS:
                self.pad.button(b, False)
            self.pad.stick("L", 0.0, 0.0)
            self.pad.stick("R", 0.0, 0.0)
            self.pad.trigger("L", 0.0)
            self.pad.trigger("R", 0.0)

    def press_release(self, kind, name, down):
        if down:
            self.held[(kind, name)] += 1
            self.output.press(kind, name)
        elif self.held[(kind, name)] > 0:
            self.held[(kind, name)] -= 1
            self.output.release(kind, name)

    def handle(self, line):
        p = line.split()
        cmd = p[0]
        if self.verbose and self.authed and cmd not in ("PING", "MM", "GS", "HELLO"):
            self.log(line)
        if cmd == "PING":
            self.reply("PONG " + (p[1] if len(p) > 1 else ""))
        elif cmd == "HELLO":
            given = normalize_code(p[2]) if len(p) > 2 else ""
            if self.code is not None and not hmac.compare_digest(given.encode(), self.code.encode()):
                reason = "old-app" if len(p) < 3 else "wrong-code"
                self.reply("DENIED " + reason)
                self.log("rejected: %s" % ("app too old, update PadForge" if reason == "old-app" else "wrong pairing code"))
                self.denied = True
                return
            if not self.authed:
                self.authed = True
                self.log("paired")
            has_pad = 1 if self.pad_factory is not None else 0
            self.reply("WELCOME %d %s %d" % (PROTOCOL_VERSION, socket.gethostname().replace(" ", "_"), has_pad))
        elif not self.authed:
            raise ValueError("not paired yet")
        elif cmd == "K":
            if p[1] not in KEYS:
                raise ValueError("unknown key")
            self.press_release("K", p[1], p[2] == "1")
        elif cmd == "MB":
            if p[1] not in MOUSE_BUTTONS:
                raise ValueError("unknown mouse button")
            self.press_release("MB", p[1], p[2] == "1")
        elif cmd == "MM":
            dx, dy = int(p[1]), int(p[2])
            if dx or dy:
                self.output.move(dx, dy)
        elif cmd in ("MW", "MH"):
            ticks = clamp(int(p[1]), -50, 50)
            if ticks:
                self.output.wheel(ticks, cmd == "MH")
        elif cmd == "GB":
            if p[1] not in PAD_BUTTONS:
                raise ValueError("unknown gamepad button")
            pad = self.get_pad()
            if pad:
                pad.button(p[1], p[2] == "1")
        elif cmd == "GS":
            pad = self.get_pad()
            if pad and p[1] in ("L", "R"):
                pad.stick(p[1], clamp(float(p[2]), -1.0, 1.0), clamp(float(p[3]), -1.0, 1.0))
        elif cmd == "GT":
            pad = self.get_pad()
            if pad and p[1] in ("L", "R"):
                pad.trigger(p[1], clamp(float(p[2]), 0.0, 1.0))
        elif cmd == "RESET":
            self.release_all()
        else:
            raise ValueError("unknown command")


def discovery_loop(tcp_port, stop):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("", DISCOVERY_PORT))
    except OSError as e:
        print("Auto-discovery disabled (UDP port %d busy: %s). Type the IP into the app instead." % (DISCOVERY_PORT, e))
        return
    s.settimeout(1.0)
    name = socket.gethostname().replace(" ", "_")
    while not stop.is_set():
        try:
            data, addr = s.recvfrom(256)
        except socket.timeout:
            continue
        except OSError:
            continue
        if data.strip() == b"PADFORGE_DISCOVER":
            s.sendto(("PADFORGE_SERVER %d %s" % (tcp_port, name)).encode(), addr)


def describe_ip(ip):
    try:
        if ipaddress.ip_address(ip) in TAILSCALE_NET:
            return "Tailscale: use this one when you're away from home"
    except ValueError:
        pass
    return "home network"


def local_ips():
    ips = set()
    try:
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        u.connect(("10.255.255.255", 1))  # no packet is sent; just picks the LAN interface
        ips.add(u.getsockname()[0])
        u.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main():
    ap = argparse.ArgumentParser(description="PadForge PC companion")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help="TCP port (default %d)" % DEFAULT_PORT)
    ap.add_argument("--dry-run", action="store_true", help="print events instead of pressing anything")
    ap.add_argument("--no-gamepad", action="store_true", help="don't create a virtual Xbox controller")
    ap.add_argument("--verbose", "-v", action="store_true", help="log every button event")
    ap.add_argument("--show-code", action="store_true", help="print the pairing code and exit")
    ap.add_argument("--new-code", action="store_true", help="generate a new pairing code")
    ap.add_argument("--no-pairing", action="store_true",
                    help="accept any phone without a pairing code (only on a network you fully trust)")
    args = ap.parse_args()

    # Started without a console (pythonw, e.g. from the start-at-logon task): log to a file.
    if sys.stdout is None or sys.stderr is None:
        log = open(os.path.join(HERE, "padforge.log"), "a", buffering=1, encoding="utf-8")
        sys.stdout = sys.stderr = log
        print("\n===== %s =====" % time.strftime("%Y-%m-%d %H:%M:%S"))

    code = get_pairing_code(regenerate=args.new_code)
    if args.show_code:
        print("Pairing code: %s" % format_code(code))
        return
    if args.no_pairing:
        code = None

    if args.dry_run or sys.platform != "win32":
        if not args.dry_run:
            print("Not on Windows: running in dry-run mode (events are printed, nothing is pressed).")
        backend = DryRunBackend()
        pad_factory = None if args.no_gamepad else DryRunPad
        args.verbose = True
    else:
        backend = WindowsBackend()
        pad_factory = None if args.no_gamepad else load_gamepad_factory()

    output = Output(backend)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("", args.port))
    srv.listen(4)

    stop = threading.Event()
    threading.Thread(target=discovery_loop, args=(args.port, stop), daemon=True).start()

    print("PadForge companion is running")
    print("  keyboard/mouse : %s" % backend.name)
    print("  gamepad        : %s" % ("virtual Xbox 360 controller" if pad_factory else "off"))
    print("  listening on   : port %d" % args.port)
    print("  pairing code   : %s" % (format_code(code) if code else "OFF (any phone can connect)"))
    for ip in local_ips():
        print("  PC address     : %-15s (%s)" % (ip, describe_ip(ip)))
    print("In the app: tap 'Find PC' at home, or type an address above, then enter the pairing code.")
    print("Press Ctrl+C to quit.\n")

    srv.settimeout(1.0)
    try:
        while True:
            try:
                conn, addr = srv.accept()
            except socket.timeout:
                continue
            Session(conn, addr, output, pad_factory, args.verbose, code).start()
    except KeyboardInterrupt:
        print("\nBye.")
    finally:
        stop.set()
        srv.close()


if __name__ == "__main__":
    main()

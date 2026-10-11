# PadForge

Build your own on-screen controller on an Android phone and use it to play games on a Windows PC.
Add as many or as few buttons, joysticks and touchpads as you like, put them anywhere, and bind each
one to any keyboard key, mouse button, scroll wheel, or Xbox controller input.

```
 Android phone (PadForge app)  ──Wi-Fi──▶  PC companion  ──▶  real keyboard / mouse / Xbox 360 pad
```

## What you can build

| Control | What it can do |
|---|---|
| **Button** | Press any number of inputs at once (e.g. `Ctrl`+`C`, or `W`+`Shift`). Hold or toggle mode. Circle or rectangle. |
| **Joystick** | Act as the controller's left or right stick (analog), as 8-way keys/buttons (WASD, arrows, D-pad, anything), or as mouse movement. Optional "push to edge" input (e.g. sprint), dead zone, floating centre. |
| **Touchpad** | Drag to move the mouse (great for aiming). Optional tap input (e.g. left click). |

Every input type is available on every control: keyboard keys (all 122, including F13–F24, numpad and
media keys), mouse buttons (left, right, middle, back, forward), scroll up/down/left/right, and every Xbox
button and trigger. Starter layouts are included: **Keyboard & Mouse (FPS)**, **Xbox Controller** and
**Mouse & Touchpad**.

## 1. Set up the PC (Windows)

1. Install [Python 3](https://www.python.org/downloads/) (tick "Add python.exe to PATH").
2. Copy the `pc-companion` folder to your PC and double-click **`start_padforge.bat`**.
   - The first run installs `vgamepad`, which adds the ViGEmBus driver for the virtual Xbox controller.
     Accept the driver installer when it pops up.
   - When Windows Firewall asks, allow Python on **Private networks**.
3. The window shows your PC's address, e.g. `PC address : 192.168.1.20`.

Keyboard and mouse work without `vgamepad`; only controller bindings need it.
If a game runs as administrator, run the companion as administrator too, or Windows will block the input.

## 2. Install the app

**Easiest: let GitHub build it.** Put this folder in a GitHub repository and push. The included workflow
(`.github/workflows/build-apk.yml`) builds the app; open the **Actions** tab, click the latest run, and
download the **PadForge** artifact. Install `PadForge.apk` on your phone (allow "install unknown apps"
when asked). Every push to `main` also refreshes a **latest** release on the Releases page with `PadForge.apk` and the PC companion zip attached.

**Or with Android Studio:** open the `android` folder, wait for Gradle sync, then Run, or
*Build ▸ Build APK(s)*.

Requires Android 7.0 or newer.

## 3. Play

1. Phone and PC on the same Wi-Fi. Open PadForge and tap **Find PC** (or type the address).
2. Tap a layout to play. The bottom of the screen shows the connection and latency.
3. Tap **⋯** (top centre) to edit the layout, change settings, or go back.

### Editing a layout

- **+ Button / + Stick / + Touchpad** adds a control and opens its settings.
- **Drag** a control to move it (it snaps to a grid). **Drag the white handle** to resize it.
- **Tap a selected control** (or press **Edit**) to change its label, colour, shape, bindings and behaviour.
- **Copy** duplicates, **To front** fixes overlaps, **Settings** sets the name, visibility and vibration.
- Long-press a layout on the home screen to rename, duplicate, delete or **share** it as text; use
  **Import** to paste one back in (handy for moving layouts between phones).

## How it works

The app sends small text events over TCP (port 47800), for example `K W 1` (W down) or
`GS L 0.500 -1.000` (left stick). The companion turns them into input with Windows `SendInput`
(using scancodes, so games that ignore virtual keys still see them) and a ViGEm virtual Xbox 360
controller. Each phone that connects gets its own virtual controller, so two phones are two players.
If Wi-Fi drops, the companion releases everything that phone was holding, so keys never get stuck.
The full protocol is documented at the top of `pc-companion/padforge_server.py`.

Test the companion without pressing anything (works on any OS):

```
python padforge_server.py --dry-run
```

## Troubleshooting

- **Find PC finds nothing:** check both devices are on the same network (not a guest network), that the
  companion is running, and that Windows Firewall allows Python on private networks. You can always type
  the address by hand.
- **Mouse moves in menus but not in the game:** some games only read raw mouse input from real devices.
  Most work, but anti-cheat protected games may block injected input entirely.
- **Controller buttons do nothing:** the companion window says whether the virtual gamepad is on. If not,
  run `pip install vgamepad` and restart it.
- **Android's back gesture triggers near the edge:** PadForge asks the system not to, but Android limits this
  to part of each edge; move edge controls inward slightly if needed.

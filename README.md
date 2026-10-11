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
3. The window shows your PC's address (e.g. `192.168.1.20`) and a **pairing code** (e.g. `K7QM-3XPA`).
   The app needs both. The code stops anyone else on the network from typing on your PC; it's saved in
   `padforge_config.json`, and `padforge_server.py --new-code` replaces it.

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

1. Phone and PC on the same Wi-Fi. Open PadForge, tap **Find PC** (or type the address) and enter the
   pairing code.
2. Tap a layout to play. The bottom of the screen shows the connection and latency.
3. Tap **⋯** (top centre) to edit the layout, change settings, or go back.

### Editing a layout

- **+ Button / + Stick / + Touchpad** adds a control and opens its settings.
- **Drag** a control to move it (it snaps to a grid). **Drag the white handle** to resize it.
- **Tap a selected control** (or press **Edit**) to change its label, colour, shape, bindings and behaviour.
- **Copy** duplicates, **To front** fixes overlaps, **Settings** sets the name, visibility and vibration.
- Long-press a layout on the home screen to rename, duplicate, delete or **share** it as text; use
  **Import** to paste one back in (handy for moving layouts between phones).

## Playing away from home with Parsec

Parsec streams your PC's screen to your phone. PadForge's controls float on top of the Parsec app and
send their input straight to your PC, so you get your own custom layout instead of Parsec's built-in touch
controls. Touches anywhere that isn't a PadForge control go to Parsec as normal.

```
 Phone: Parsec (video)  ◀───────── internet ─────────  PC: Parsec host
        PadForge overlay ─── Tailscale (encrypted) ──▶  PC: PadForge companion
```

Your phone can't reach your PC directly from outside your home, so PadForge uses
[Tailscale](https://tailscale.com) (free for personal use): it puts your phone and PC on a private,
encrypted network that works from anywhere. **Don't forward port 47800 on your router instead**: the
PadForge connection itself isn't encrypted.

### One-time setup (at home)

1. **Tailscale:** install it on the PC and the phone and sign in to the same account on both.
2. **PC:** in the `pc-companion` folder, double-click
   - **`setup_firewall.bat`**: lets PadForge in from your home network and Tailscale only.
   - **`install_autostart.bat`**: runs the companion hidden, as administrator, every time you sign in
     to Windows, so it's already running when you're away. It prints your pairing code.
     (`remove_autostart.bat` undoes it; output goes to `padforge.log`.)
3. **Phone:** in PadForge, set the address to your PC's **Tailscale address** (starts with `100.`;
   it's shown in the Tailscale app and in the companion window labelled *Tailscale*), port `47800`,
   and the pairing code. This address works both at home and away.
4. Tick **Parsec mode** and tap a layout. The first time, Android asks you to allow
   **Display over other apps** for PadForge; turn it on, come back and tap the layout again.
   PadForge starts the overlay and opens Parsec.

### While playing

- The **small round button** (with a coloured dot) controls the overlay:
  **tap** to hide/show the controls (e.g. to use Parsec's menu), **drag** to move it,
  **long-press** to stop the overlay. Dot colours: green = connected, yellow = connecting, red = not connected
  (tap it to see why and retry).
- The **PadForge notification** has *Hide/Show controls*, *Edit layout* and *Stop* buttons.
- Long-press a layout and choose **Play over Parsec** to start the overlay without ticking Parsec mode.

### Tips

- **Keep your PC awake:** set Windows' sleep to *Never* while you're away (or use Wake-on-LAN).
- **Parsec's own controls:** turn off Parsec's on-screen gamepad if it overlaps your layout.
- **Two controllers in game?** PadForge creates its own virtual Xbox controller. If Parsec is also passing
  through a gamepad from your phone, the game may see both; disconnect one.
- **Lock screen and admin prompts:** Windows doesn't let other apps type into these, so use Parsec's own
  keyboard and mouse there. Everything else works, including games running as administrator
  (the autostart runs the companion as administrator).
- **Lag:** in the Tailscale app, check the PC shows a *direct* connection rather than a relay. PadForge's
  latency is shown in the notification.
- **"Display over other apps" greyed out:** open Android Settings → Apps → PadForge → ⋮ →
  *Allow restricted settings*, then try again.

## How it works

The app first sends the pairing code, then small text events over TCP (port 47800), for example `K W 1` (W down) or
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
  companion is running, and that Windows Firewall allows Python on private networks (or run
  `setup_firewall.bat`). You can always type the address by hand. Find PC doesn't work over Tailscale;
  type the `100.x.y.z` address instead.
- **"Wrong pairing code":** type the code shown in the companion window (or run
  `padforge_server.py --show-code`). Dashes and capitals don't matter.
- **Mouse moves in menus but not in the game:** some games only read raw mouse input from real devices.
  Most work, but anti-cheat protected games may block injected input entirely.
- **Controller buttons do nothing:** the companion window says whether the virtual gamepad is on. If not,
  run `pip install vgamepad` and restart it.
- **Android's back gesture triggers near the edge:** PadForge asks the system not to, but Android limits this
  to part of each edge; move edge controls inward slightly if needed.

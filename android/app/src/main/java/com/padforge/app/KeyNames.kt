package com.padforge.app

import android.view.KeyEvent

/**
 * Every input the PC companion understands. Codes must match padforge_server.py.
 */
object KeyNames {

    val KEYS: List<String> = buildList {
        for (c in 'A'..'Z') add(c.toString())
        for (d in 0..9) add(d.toString())
        addAll(listOf(
            "Space", "Enter", "Escape", "Tab", "Backspace", "CapsLock",
            "LShift", "RShift", "LCtrl", "RCtrl", "LAlt", "RAlt", "LWin", "RWin", "Menu",
            "Up", "Down", "Left", "Right",
        ))
        for (f in 1..24) add("F$f")
        addAll(listOf(
            "Insert", "Delete", "Home", "End", "PageUp", "PageDown",
            "PrintScreen", "ScrollLock", "Pause", "NumLock",
            "Minus", "Equals", "LBracket", "RBracket", "Semicolon", "Quote",
            "Backquote", "Backslash", "Comma", "Period", "Slash",
        ))
        for (n in 0..9) add("Num$n")
        addAll(listOf(
            "NumMultiply", "NumAdd", "NumSubtract", "NumDecimal", "NumDivide", "NumEnter",
            "VolumeMute", "VolumeDown", "VolumeUp", "MediaPlayPause", "MediaNext", "MediaPrev",
        ))
    }

    val MOUSE = listOf("Left", "Right", "Middle", "X1", "X2")
    val WHEEL = listOf("Up", "Down", "Left", "Right")
    val PAD = listOf("A", "B", "X", "Y", "LB", "RB", "Back", "Start", "LS", "RS", "Guide",
        "Up", "Down", "Left", "Right")
    val TRIGGER = listOf("LT", "RT")

    private val keyShort = mapOf(
        "Escape" to "Esc", "Backspace" to "⌫", "CapsLock" to "Caps",
        "LShift" to "Shift", "RShift" to "R-Shift", "LCtrl" to "Ctrl", "RCtrl" to "R-Ctrl",
        "LAlt" to "Alt", "RAlt" to "R-Alt", "LWin" to "Win", "RWin" to "R-Win",
        "Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→",
        "PageUp" to "PgUp", "PageDown" to "PgDn", "PrintScreen" to "PrtSc",
        "Minus" to "-", "Equals" to "=", "LBracket" to "[", "RBracket" to "]",
        "Semicolon" to ";", "Quote" to "'", "Backquote" to "`", "Backslash" to "\\",
        "Comma" to ",", "Period" to ".", "Slash" to "/",
        "NumMultiply" to "Num*", "NumAdd" to "Num+", "NumSubtract" to "Num-",
        "NumDecimal" to "Num.", "NumDivide" to "Num/", "NumEnter" to "NumEnt",
        "VolumeMute" to "Mute", "VolumeDown" to "Vol-", "VolumeUp" to "Vol+",
        "MediaPlayPause" to "⏯", "MediaNext" to "⏭", "MediaPrev" to "⏮",
    )
    private val keyLong = mapOf(
        "LShift" to "Left Shift", "RShift" to "Right Shift", "LCtrl" to "Left Ctrl",
        "RCtrl" to "Right Ctrl", "LAlt" to "Left Alt", "RAlt" to "Right Alt",
        "LWin" to "Left Windows", "RWin" to "Right Windows", "Menu" to "Menu (context key)",
        "Up" to "Arrow Up", "Down" to "Arrow Down", "Left" to "Arrow Left", "Right" to "Arrow Right",
        "Minus" to "- (minus)", "Equals" to "= (equals)", "LBracket" to "[", "RBracket" to "]",
        "Semicolon" to "; (semicolon)", "Quote" to "' (quote)", "Backquote" to "` (backquote / console)",
        "Backslash" to "\\", "Comma" to ", (comma)", "Period" to ". (period)", "Slash" to "/ (slash)",
        "NumMultiply" to "Numpad *", "NumAdd" to "Numpad +", "NumSubtract" to "Numpad -",
        "NumDecimal" to "Numpad .", "NumDivide" to "Numpad /", "NumEnter" to "Numpad Enter",
    )
    private val mouseLong = mapOf("Left" to "Left click", "Right" to "Right click",
        "Middle" to "Middle click", "X1" to "Back button (Mouse 4)", "X2" to "Forward button (Mouse 5)")
    private val mouseShort = mapOf("Left" to "LMB", "Right" to "RMB", "Middle" to "MMB",
        "X1" to "M4", "X2" to "M5")
    private val padLong = mapOf("LB" to "LB (left bumper)", "RB" to "RB (right bumper)",
        "Back" to "Back / View", "Start" to "Start / Menu", "LS" to "L3 (left stick click)",
        "RS" to "R3 (right stick click)", "Guide" to "Guide (Xbox button)",
        "Up" to "D-pad Up", "Down" to "D-pad Down", "Left" to "D-pad Left", "Right" to "D-pad Right")
    private val padShort = mapOf("Back" to "Back", "Start" to "Start", "LS" to "L3", "RS" to "R3",
        "Guide" to "Xbox", "Up" to "D↑", "Down" to "D↓", "Left" to "D←", "Right" to "D→")
    private val wheelShort = mapOf("Up" to "Wheel↑", "Down" to "Wheel↓", "Left" to "Wheel←", "Right" to "Wheel→")

    /**
     * Phone-only mode: key name -> (Android keycode, Linux scancode). Keys Android has no
     * code for (F13-F24) or that the phone itself would act on (volume, media) are left out.
     */
    val ANDROID_KEYS: Map<String, Pair<Int, Int>> = buildMap {
        val letterScan = mapOf('A' to 30, 'B' to 48, 'C' to 46, 'D' to 32, 'E' to 18, 'F' to 33, 'G' to 34,
            'H' to 35, 'I' to 23, 'J' to 36, 'K' to 37, 'L' to 38, 'M' to 50, 'N' to 49, 'O' to 24, 'P' to 25,
            'Q' to 16, 'R' to 19, 'S' to 31, 'T' to 20, 'U' to 22, 'V' to 47, 'W' to 17, 'X' to 45, 'Y' to 21,
            'Z' to 44)
        for (c in 'A'..'Z') put(c.toString(), KeyEvent.KEYCODE_A + (c - 'A') to letterScan.getValue(c))
        for (d in 0..9) put(d.toString(), KeyEvent.KEYCODE_0 + d to (if (d == 0) 11 else d + 1))
        val fScan = intArrayOf(59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88)
        for (f in 1..12) put("F$f", KeyEvent.KEYCODE_F1 + f - 1 to fScan[f - 1])
        val numScan = intArrayOf(82, 79, 80, 81, 75, 76, 77, 71, 72, 73)
        for (n in 0..9) put("Num$n", KeyEvent.KEYCODE_NUMPAD_0 + n to numScan[n])
        put("Space", KeyEvent.KEYCODE_SPACE to 57); put("Enter", KeyEvent.KEYCODE_ENTER to 28)
        put("Escape", KeyEvent.KEYCODE_ESCAPE to 1); put("Tab", KeyEvent.KEYCODE_TAB to 15)
        put("Backspace", KeyEvent.KEYCODE_DEL to 14); put("CapsLock", KeyEvent.KEYCODE_CAPS_LOCK to 58)
        put("LShift", KeyEvent.KEYCODE_SHIFT_LEFT to 42); put("RShift", KeyEvent.KEYCODE_SHIFT_RIGHT to 54)
        put("LCtrl", KeyEvent.KEYCODE_CTRL_LEFT to 29); put("RCtrl", KeyEvent.KEYCODE_CTRL_RIGHT to 97)
        put("LAlt", KeyEvent.KEYCODE_ALT_LEFT to 56); put("RAlt", KeyEvent.KEYCODE_ALT_RIGHT to 100)
        put("LWin", KeyEvent.KEYCODE_META_LEFT to 125); put("RWin", KeyEvent.KEYCODE_META_RIGHT to 126)
        put("Menu", KeyEvent.KEYCODE_MENU to 127)
        put("Up", KeyEvent.KEYCODE_DPAD_UP to 103); put("Down", KeyEvent.KEYCODE_DPAD_DOWN to 108)
        put("Left", KeyEvent.KEYCODE_DPAD_LEFT to 105); put("Right", KeyEvent.KEYCODE_DPAD_RIGHT to 106)
        put("Insert", KeyEvent.KEYCODE_INSERT to 110); put("Delete", KeyEvent.KEYCODE_FORWARD_DEL to 111)
        put("Home", KeyEvent.KEYCODE_MOVE_HOME to 102); put("End", KeyEvent.KEYCODE_MOVE_END to 107)
        put("PageUp", KeyEvent.KEYCODE_PAGE_UP to 104); put("PageDown", KeyEvent.KEYCODE_PAGE_DOWN to 109)
        put("PrintScreen", KeyEvent.KEYCODE_SYSRQ to 99); put("ScrollLock", KeyEvent.KEYCODE_SCROLL_LOCK to 70)
        put("Pause", KeyEvent.KEYCODE_BREAK to 119); put("NumLock", KeyEvent.KEYCODE_NUM_LOCK to 69)
        put("Minus", KeyEvent.KEYCODE_MINUS to 12); put("Equals", KeyEvent.KEYCODE_EQUALS to 13)
        put("LBracket", KeyEvent.KEYCODE_LEFT_BRACKET to 26); put("RBracket", KeyEvent.KEYCODE_RIGHT_BRACKET to 27)
        put("Semicolon", KeyEvent.KEYCODE_SEMICOLON to 39); put("Quote", KeyEvent.KEYCODE_APOSTROPHE to 40)
        put("Backquote", KeyEvent.KEYCODE_GRAVE to 41); put("Backslash", KeyEvent.KEYCODE_BACKSLASH to 43)
        put("Comma", KeyEvent.KEYCODE_COMMA to 51); put("Period", KeyEvent.KEYCODE_PERIOD to 52)
        put("Slash", KeyEvent.KEYCODE_SLASH to 53)
        put("NumMultiply", KeyEvent.KEYCODE_NUMPAD_MULTIPLY to 55); put("NumAdd", KeyEvent.KEYCODE_NUMPAD_ADD to 78)
        put("NumSubtract", KeyEvent.KEYCODE_NUMPAD_SUBTRACT to 74); put("NumDecimal", KeyEvent.KEYCODE_NUMPAD_DOT to 83)
        put("NumDivide", KeyEvent.KEYCODE_NUMPAD_DIVIDE to 98); put("NumEnter", KeyEvent.KEYCODE_NUMPAD_ENTER to 96)
    }

    /** Phone-only mode: controller button name -> Android gamepad keycode. */
    val ANDROID_PAD: Map<String, Int> = mapOf(
        "A" to KeyEvent.KEYCODE_BUTTON_A, "B" to KeyEvent.KEYCODE_BUTTON_B,
        "X" to KeyEvent.KEYCODE_BUTTON_X, "Y" to KeyEvent.KEYCODE_BUTTON_Y,
        "LB" to KeyEvent.KEYCODE_BUTTON_L1, "RB" to KeyEvent.KEYCODE_BUTTON_R1,
        "Back" to KeyEvent.KEYCODE_BUTTON_SELECT, "Start" to KeyEvent.KEYCODE_BUTTON_START,
        "LS" to KeyEvent.KEYCODE_BUTTON_THUMBL, "RS" to KeyEvent.KEYCODE_BUTTON_THUMBR,
        "Guide" to KeyEvent.KEYCODE_BUTTON_MODE,
        "Up" to KeyEvent.KEYCODE_DPAD_UP, "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "Left" to KeyEvent.KEYCODE_DPAD_LEFT, "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    fun options(kind: BindKind): List<String> = when (kind) {
        BindKind.KEY -> KEYS
        BindKind.MOUSE -> MOUSE
        BindKind.WHEEL -> WHEEL
        BindKind.PAD -> PAD
        BindKind.TRIGGER -> TRIGGER
    }

    /** Text for pickers. */
    fun longLabel(kind: BindKind, code: String): String = when (kind) {
        BindKind.KEY -> keyLong[code] ?: code
        BindKind.MOUSE -> mouseLong[code] ?: code
        BindKind.WHEEL -> "Scroll $code"
        BindKind.PAD -> padLong[code] ?: code
        BindKind.TRIGGER -> if (code == "LT") "LT (left trigger)" else "RT (right trigger)"
    }

    /** Text drawn on a control when it has no label of its own. */
    fun shortLabel(kind: BindKind, code: String): String = when (kind) {
        BindKind.KEY -> keyShort[code] ?: code
        BindKind.MOUSE -> mouseShort[code] ?: code
        BindKind.WHEEL -> wheelShort[code] ?: code
        BindKind.PAD -> padShort[code] ?: code
        BindKind.TRIGGER -> code
    }
}

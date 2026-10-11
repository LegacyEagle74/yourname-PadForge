package com.padforge.app

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

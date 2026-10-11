package com.padforge.app

/** Starter layouts. Coordinates are for a landscape phone (x = across, y = down). */
object Defaults {

    private val BLUE = COLORS[0]; private val GREEN = COLORS[1]; private val RED = COLORS[2]
    private val YELLOW = COLORS[3]; private val PURPLE = COLORS[4]; private val ORANGE = COLORS[5]
    private val WHITE = COLORS[6]; private val GRAY = COLORS[7]

    private fun button(x: Float, y: Float, size: Float, label: String, color: Int, vararg b: Binding,
                       shape: Shape = Shape.CIRCLE, h: Float = size, toggle: Boolean = false) =
        Element(type = ElementType.BUTTON, x = x, y = y, w = size, h = h, label = label, color = color,
            shape = shape, toggle = toggle, bindings = b.toMutableList())

    private fun key(x: Float, y: Float, label: String, code: String, color: Int = GRAY) =
        button(x, y, 0.15f, label, color, Binding.key(code), shape = Shape.ROUNDED, h = 0.11f)

    fun all(): MutableList<Layout> = mutableListOf(shooter(), xbox(), mouse())

    fun blank() = Layout(name = "New layout")

    /** Keyboard & mouse game: WASD stick, touchpad aiming, fire/aim/jump buttons. */
    fun shooter() = Layout(name = "Keyboard & Mouse (FPS)").apply {
        elements += Element(type = ElementType.JOYSTICK, x = 0.16f, y = 0.66f, w = 0.5f, label = "Move",
            color = BLUE, stickMode = StickMode.DIRECTIONAL,
            dirs = arrayOf(Binding.key("W"), Binding.key("S"), Binding.key("A"), Binding.key("D")),
            bindings = mutableListOf(Binding.key("LShift")), deadzone = 0.15f)
        elements += Element(type = ElementType.TOUCHPAD, x = 0.58f, y = 0.5f, w = 0.7f, h = 0.75f,
            label = "Aim", color = GRAY, sensitivity = 1.2f)
        elements += button(0.90f, 0.70f, 0.24f, "Fire", RED, Binding.mouse("Left"))
        elements += button(0.93f, 0.38f, 0.16f, "ADS", ORANGE, Binding.mouse("Right"))
        elements += button(0.80f, 0.90f, 0.15f, "Jump", GREEN, Binding.key("Space"))
        elements += button(0.80f, 0.48f, 0.13f, "Reload", YELLOW, Binding.key("R"))
        elements += button(0.07f, 0.25f, 0.13f, "Use", BLUE, Binding.key("E"))
        elements += button(0.17f, 0.25f, 0.13f, "Crouch", PURPLE, Binding.key("LCtrl"), toggle = true)
        elements += button(0.27f, 0.25f, 0.13f, "", PURPLE, Binding.key("G"))
        elements += key(0.05f, 0.08f, "Esc", "Escape")
        elements += key(0.13f, 0.08f, "Tab", "Tab")
        elements += key(0.21f, 0.08f, "1", "1")
        elements += key(0.29f, 0.08f, "2", "2")
        elements += button(0.37f, 0.08f, 0.15f, "Wheel↑", GRAY, Binding.wheel("Up"), shape = Shape.ROUNDED, h = 0.11f)
        elements += button(0.37f, 0.22f, 0.15f, "Wheel↓", GRAY, Binding.wheel("Down"), shape = Shape.ROUNDED, h = 0.11f)
    }

    /** A full virtual Xbox controller. */
    fun xbox() = Layout(name = "Xbox Controller").apply {
        elements += Element(type = ElementType.JOYSTICK, x = 0.14f, y = 0.62f, w = 0.40f, color = WHITE,
            stickMode = StickMode.PAD_LEFT, deadzone = 0.08f)
        elements += Element(type = ElementType.JOYSTICK, x = 0.66f, y = 0.72f, w = 0.36f, color = WHITE,
            stickMode = StickMode.PAD_RIGHT, deadzone = 0.08f)
        elements += button(0.88f, 0.71f, 0.13f, "A", GREEN, Binding.pad("A"))
        elements += button(0.955f, 0.55f, 0.13f, "B", RED, Binding.pad("B"))
        elements += button(0.805f, 0.55f, 0.13f, "X", BLUE, Binding.pad("X"))
        elements += button(0.88f, 0.39f, 0.13f, "Y", YELLOW, Binding.pad("Y"))
        elements += button(0.34f, 0.68f, 0.10f, "▲", GRAY, Binding.pad("Up"), shape = Shape.ROUNDED)
        elements += button(0.34f, 0.92f, 0.10f, "▼", GRAY, Binding.pad("Down"), shape = Shape.ROUNDED)
        elements += button(0.29f, 0.80f, 0.10f, "◀", GRAY, Binding.pad("Left"), shape = Shape.ROUNDED)
        elements += button(0.39f, 0.80f, 0.10f, "▶", GRAY, Binding.pad("Right"), shape = Shape.ROUNDED)
        elements += button(0.07f, 0.10f, 0.20f, "LT", GRAY, Binding.trigger("LT"), shape = Shape.ROUNDED, h = 0.13f)
        elements += button(0.20f, 0.10f, 0.20f, "LB", GRAY, Binding.pad("LB"), shape = Shape.ROUNDED, h = 0.13f)
        elements += button(0.80f, 0.10f, 0.20f, "RB", GRAY, Binding.pad("RB"), shape = Shape.ROUNDED, h = 0.13f)
        elements += button(0.93f, 0.10f, 0.20f, "RT", GRAY, Binding.trigger("RT"), shape = Shape.ROUNDED, h = 0.13f)
        elements += button(0.43f, 0.12f, 0.12f, "Back", GRAY, Binding.pad("Back"), shape = Shape.ROUNDED, h = 0.09f)
        elements += button(0.57f, 0.12f, 0.12f, "Start", GRAY, Binding.pad("Start"), shape = Shape.ROUNDED, h = 0.09f)
        elements += button(0.50f, 0.30f, 0.11f, "Xbox", WHITE, Binding.pad("Guide"))
        elements += button(0.26f, 0.45f, 0.09f, "L3", GRAY, Binding.pad("LS"))
        elements += button(0.77f, 0.90f, 0.09f, "R3", GRAY, Binding.pad("RS"))
    }

    /** Use the phone as a laptop-style touchpad. */
    fun mouse() = Layout(name = "Mouse & Touchpad").apply {
        elements += Element(type = ElementType.TOUCHPAD, x = 0.42f, y = 0.48f, w = 1.5f,
            h = 0.85f, label = "Touchpad · tap to click", color = GRAY, sensitivity = 1.4f,
            bindings = mutableListOf(Binding.mouse("Left")))
        elements += button(0.92f, 0.20f, 0.2f, "Left", BLUE, Binding.mouse("Left"), shape = Shape.ROUNDED, h = 0.3f)
        elements += button(0.92f, 0.52f, 0.2f, "Right", BLUE, Binding.mouse("Right"), shape = Shape.ROUNDED, h = 0.3f)
        elements += button(0.92f, 0.78f, 0.2f, "Scroll↑", GRAY, Binding.wheel("Up"), shape = Shape.ROUNDED, h = 0.14f)
        elements += button(0.92f, 0.93f, 0.2f, "Scroll↓", GRAY, Binding.wheel("Down"), shape = Shape.ROUNDED, h = 0.14f)
    }

    val templates = listOf("Blank" to ::blank, "Keyboard & Mouse (FPS)" to ::shooter,
        "Xbox Controller" to ::xbox, "Mouse & Touchpad" to ::mouse)
}

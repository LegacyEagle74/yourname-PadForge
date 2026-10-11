package com.padforge.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class BindKind(val id: String, val title: String) {
    KEY("key", "Keyboard key"),
    MOUSE("mouse", "Mouse button"),
    WHEEL("wheel", "Mouse wheel"),
    PAD("pad", "Controller button"),
    TRIGGER("trigger", "Controller trigger");

    companion object {
        fun byId(id: String) = values().firstOrNull { it.id == id } ?: KEY
    }
}

/** One thing a control can press on the PC. */
data class Binding(val kind: BindKind, val code: String) {
    fun shortLabel() = KeyNames.shortLabel(kind, code)
    fun longLabel() = "${kind.title}: ${KeyNames.longLabel(kind, code)}"

    fun toJson(): JSONObject = JSONObject().put("kind", kind.id).put("code", code)

    companion object {
        fun fromJson(o: JSONObject?): Binding? {
            if (o == null) return null
            val kind = BindKind.byId(o.optString("kind"))
            val code = o.optString("code")
            return if (code in KeyNames.options(kind)) Binding(kind, code) else null
        }

        fun key(code: String) = Binding(BindKind.KEY, code)
        fun mouse(code: String) = Binding(BindKind.MOUSE, code)
        fun wheel(code: String) = Binding(BindKind.WHEEL, code)
        fun pad(code: String) = Binding(BindKind.PAD, code)
        fun trigger(code: String) = Binding(BindKind.TRIGGER, code)
    }
}

enum class ElementType(val title: String) {
    BUTTON("Button"), JOYSTICK("Joystick"), TOUCHPAD("Touchpad (mouse)")
}

enum class Shape(val title: String) { CIRCLE("Circle"), ROUNDED("Rounded rectangle") }

enum class StickMode(val title: String) {
    PAD_LEFT("Controller left stick"),
    PAD_RIGHT("Controller right stick"),
    DIRECTIONAL("Keys / buttons (8-way, e.g. WASD)"),
    MOUSE("Mouse movement"),
}

val COLORS = intArrayOf(
    0xFF4FC3F7.toInt(), 0xFF81C784.toInt(), 0xFFE57373.toInt(), 0xFFFFD54F.toInt(),
    0xFFBA68C8.toInt(), 0xFFFFB74D.toInt(), 0xFFEEEEEE.toInt(), 0xFF90A4AE.toInt(),
)

/**
 * A control on screen. Position is the centre as a fraction of the screen's
 * width/height; size is a fraction of the screen's short side, so layouts keep
 * their proportions on different phones.
 */
class Element(
    var id: String = newId(),
    var type: ElementType = ElementType.BUTTON,
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var w: Float = 0.18f,
    var h: Float = 0.18f,
    var label: String = "",
    var color: Int = COLORS[0],
    var shape: Shape = Shape.CIRCLE,
    /** Button: tap once to hold, tap again to release. */
    var toggle: Boolean = false,
    /** Button: what it presses. Touchpad: what a tap presses. Joystick: what pushing it to the edge presses. */
    var bindings: MutableList<Binding> = mutableListOf(),
    var stickMode: StickMode = StickMode.PAD_LEFT,
    /** Directional joystick: up, down, left, right (each may be null). */
    var dirs: Array<Binding?> = arrayOfNulls(4),
    /** Joystick in mouse mode and touchpad: speed multiplier. */
    var sensitivity: Float = 1f,
    var deadzone: Float = 0.12f,
    /** Joystick: centre jumps to wherever the finger lands. */
    var floating: Boolean = false,
) {
    val isRound get() = type == ElementType.JOYSTICK || (type == ElementType.BUTTON && shape == Shape.CIRCLE)

    fun displayLabel(): String = when {
        label.isNotBlank() -> label
        type == ElementType.BUTTON -> bindings.joinToString("+") { it.shortLabel() }.ifEmpty { "?" }
        type == ElementType.TOUCHPAD -> "Mouse"
        else -> when (stickMode) {
            StickMode.PAD_LEFT -> "LS"
            StickMode.PAD_RIGHT -> "RS"
            StickMode.MOUSE -> "Mouse"
            StickMode.DIRECTIONAL -> dirs.filterNotNull().joinToString("") { it.shortLabel() }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type.name)
        put("x", x.toDouble()); put("y", y.toDouble()); put("w", w.toDouble()); put("h", h.toDouble())
        put("label", label); put("color", color); put("shape", shape.name); put("toggle", toggle)
        put("bindings", JSONArray().apply { bindings.forEach { put(it.toJson()) } })
        put("stickMode", stickMode.name)
        put("dirs", JSONArray().apply { dirs.forEach { put(it?.toJson() ?: JSONObject.NULL) } })
        put("sensitivity", sensitivity.toDouble()); put("deadzone", deadzone.toDouble())
        put("floating", floating)
    }

    fun copy(newId: Boolean = false): Element = fromJson(toJson()).also { if (newId) it.id = newId() }

    companion object {
        fun newId() = UUID.randomUUID().toString().substring(0, 8)

        fun fromJson(o: JSONObject): Element = Element().apply {
            id = o.optString("id").ifEmpty { newId() }
            type = enumOr(o.optString("type"), ElementType.BUTTON)
            x = o.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f)
            y = o.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
            w = o.optDouble("w", 0.18).toFloat().coerceIn(MIN_SIZE, MAX_SIZE)
            h = o.optDouble("h", w.toDouble()).toFloat().coerceIn(MIN_SIZE, MAX_SIZE)
            label = o.optString("label")
            color = o.optInt("color", COLORS[0])
            shape = enumOr(o.optString("shape"), Shape.CIRCLE)
            toggle = o.optBoolean("toggle")
            bindings = mutableListOf()
            o.optJSONArray("bindings")?.let { a ->
                for (i in 0 until a.length()) Binding.fromJson(a.optJSONObject(i))?.let { bindings.add(it) }
            }
            stickMode = enumOr(o.optString("stickMode"), StickMode.PAD_LEFT)
            dirs = arrayOfNulls(4)
            o.optJSONArray("dirs")?.let { a ->
                for (i in 0 until minOf(4, a.length())) dirs[i] = Binding.fromJson(a.optJSONObject(i))
            }
            sensitivity = o.optDouble("sensitivity", 1.0).toFloat().coerceIn(0.1f, 5f)
            deadzone = o.optDouble("deadzone", 0.12).toFloat().coerceIn(0f, 0.6f)
            floating = o.optBoolean("floating")
        }
    }
}

const val MIN_SIZE = 0.06f
const val MAX_SIZE = 2.2f

class Layout(
    var id: String = Element.newId(),
    var name: String = "New layout",
    var elements: MutableList<Element> = mutableListOf(),
    /** How visible the controls are, 0.15..1. */
    var opacity: Float = 0.85f,
    var haptics: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "padforge-layout-1")
        put("id", id); put("name", name)
        put("opacity", opacity.toDouble()); put("haptics", haptics)
        put("elements", JSONArray().apply { elements.forEach { put(it.toJson()) } })
    }

    fun copy(): Layout = fromJson(toJson())

    companion object {
        fun fromJson(o: JSONObject): Layout = Layout().apply {
            id = o.optString("id").ifEmpty { Element.newId() }
            name = o.optString("name", "Layout").ifEmpty { "Layout" }
            opacity = o.optDouble("opacity", 0.85).toFloat().coerceIn(0.15f, 1f)
            haptics = o.optBoolean("haptics", true)
            elements = mutableListOf()
            o.optJSONArray("elements")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { elements.add(Element.fromJson(it)) }
            }
        }
    }
}

inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

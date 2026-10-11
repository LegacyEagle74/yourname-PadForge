package com.padforge.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

/** All the settings dialogs, built in code. */
object Dialogs {

    private const val DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert

    fun Context.px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun Context.column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(8), px(20), px(8))
    }

    private fun Context.heading(parent: LinearLayout, s: String): TextView = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(0xFF80CBC4.toInt())
        setPadding(0, px(14), 0, px(4))
        parent.addView(this)
    }

    private fun Context.note(parent: LinearLayout, s: String): TextView = TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(0x99FFFFFF.toInt())
        parent.addView(this)
    }

    private fun Context.spinner(parent: LinearLayout, items: List<String>, selected: Int,
                                onPick: (Int) -> Unit): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(selected.coerceIn(0, items.size - 1), false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        parent.addView(this)
    }

    private fun Context.slider(parent: LinearLayout, label: String, min: Float, max: Float, value: Float,
                               format: (Float) -> String, onChange: (Float) -> Unit): View {
        val box = column().apply { setPadding(0, px(6), 0, 0) }
        val title = TextView(this).apply { textSize = 14f }
        fun show(v: Float) { title.text = "$label: ${format(v)}" }
        show(value)
        box.addView(title)
        box.addView(SeekBar(this).apply {
            this.max = 1000
            progress = (((value - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 1000f
                    show(v); onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        parent.addView(box)
        return box
    }

    private fun Context.check(parent: LinearLayout, label: String, value: Boolean, onChange: (Boolean) -> Unit) =
        CheckBox(this).apply {
            text = label
            isChecked = value
            setOnCheckedChangeListener { _, b -> onChange(b) }
            parent.addView(this)
        }

    private fun pct(v: Float) = "${(v * 100).toInt()}%"
    private fun times(v: Float) = String.format(Locale.US, "%.2f×", v)

    // ------------------------------------------------------------------ binding picker

    /** Lets the user choose any key, mouse button, wheel direction or controller input. */
    fun pickBinding(ctx: Context, current: Binding?, allowClear: Boolean, onPick: (Binding?) -> Unit) {
        val kinds = BindKind.values().toList()
        var kind = current?.kind ?: BindKind.KEY
        var code = current?.code ?: KeyNames.options(kind).first()
        val col = ctx.column()
        ctx.heading(col, "Type")
        lateinit var itemSpinner: Spinner
        fun fillItems() {
            val opts = KeyNames.options(kind)
            itemSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                opts.map { KeyNames.longLabel(kind, it) }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            itemSpinner.setSelection(opts.indexOf(code).coerceAtLeast(0), false)
        }
        ctx.spinner(col, kinds.map { it.title }, kinds.indexOf(kind)) { pos ->
            if (kinds[pos] != kind) {
                kind = kinds[pos]
                code = KeyNames.options(kind).first()
                fillItems()
            }
        }
        ctx.heading(col, "Input")
        itemSpinner = ctx.spinner(col, listOf(""), 0) { pos ->
            KeyNames.options(kind).getOrNull(pos)?.let { code = it }
        }
        fillItems()
        val b = AlertDialog.Builder(ctx, DIALOG_THEME)
            .setTitle("Bind to…")
            .setView(col)
            .setPositiveButton("OK") { _, _ -> onPick(Binding(kind, code)) }
            .setNegativeButton("Cancel", null)
        if (allowClear) b.setNeutralButton("None") { _, _ -> onPick(null) }
        b.show()
    }

    /** An editable list of bindings with add / change / remove. */
    private fun Context.bindingList(parent: LinearLayout, list: MutableList<Binding>, emptyText: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(box)
        fun refresh() {
            box.removeAllViews()
            if (list.isEmpty()) note(box, emptyText)
            list.forEachIndexed { i, binding ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this).apply {
                    text = binding.longLabel()
                    textSize = 15f
                    setPadding(0, px(8), 0, px(8))
                    setOnClickListener {
                        pickBinding(this@bindingList, binding, false) { nb -> if (nb != null) { list[i] = nb; refresh() } }
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Change"
                    setOnClickListener {
                        pickBinding(this@bindingList, binding, false) { nb -> if (nb != null) { list[i] = nb; refresh() } }
                    }
                })
                row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "✕"
                    setOnClickListener { list.removeAt(i); refresh() }
                })
                box.addView(row)
            }
            box.addView(Button(this).apply {
                text = "+ Add input"
                setOnClickListener {
                    pickBinding(this@bindingList, list.lastOrNull(), false) { nb -> if (nb != null) { list.add(nb); refresh() } }
                }
            })
        }
        refresh()
    }

    private fun Context.colorRow(parent: LinearLayout, current: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = mutableListOf<View>()
        fun paint(sel: Int) {
            COLORS.forEachIndexed { i, c ->
                swatches[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    if (c == sel) setStroke(px(3), Color.WHITE)
                }
            }
        }
        COLORS.forEach { c ->
            val v = View(this)
            v.setOnClickListener { onPick(c); paint(c) }
            swatches.add(v)
            row.addView(v, LinearLayout.LayoutParams(px(30), px(30)).apply { marginEnd = px(8) })
        }
        paint(current)
        parent.addView(row)
    }

    // ------------------------------------------------------------------ element editor

    fun editElement(ctx: Context, element: Element, onSave: (Element) -> Unit, onDelete: () -> Unit) {
        val w = element.copy()
        val col = ctx.column()
        ctx.heading(col, "Label")
        val labelEdit = EditText(ctx).apply {
            setText(w.label)
            hint = "Empty = show what it presses"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        col.addView(labelEdit)
        ctx.heading(col, "Colour")
        ctx.colorRow(col, w.color) { w.color = it }

        // Size: height only matters for rectangles.
        lateinit var heightSlider: View
        fun syncShape() { heightSlider.visibility = if (w.isRound) View.GONE else View.VISIBLE }

        when (w.type) {
            ElementType.BUTTON -> {
                ctx.heading(col, "Shape")
                ctx.spinner(col, Shape.values().map { it.title }, w.shape.ordinal) {
                    w.shape = Shape.values()[it]; syncShape()
                }
                ctx.check(col, "Toggle (tap to hold down, tap again to let go)", w.toggle) { w.toggle = it }
                ctx.heading(col, "Presses (all at once, so you can make combos like Ctrl+C)")
                ctx.bindingList(col, w.bindings, "Nothing yet")
            }
            ElementType.JOYSTICK -> {
                ctx.heading(col, "Acts as")
                val dirBox = ctx.column().apply { setPadding(0, 0, 0, 0) }
                val names = arrayOf("Up", "Down", "Left", "Right")
                fun fillDirs() {
                    dirBox.removeAllViews()
                    for (d in 0..3) {
                        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                        row.addView(TextView(ctx).apply {
                            text = "${names[d]}:  ${w.dirs[d]?.longLabel() ?: "nothing"}"
                            textSize = 15f
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                            text = "Change"
                            setOnClickListener {
                                pickBinding(ctx, w.dirs[d], true) { b -> w.dirs[d] = b; fillDirs() }
                            }
                        })
                        dirBox.addView(row)
                    }
                    dirBox.addView(Button(ctx).apply {
                        text = "Presets: WASD · Arrows · D-pad"
                        setOnClickListener {
                            AlertDialog.Builder(ctx, DIALOG_THEME).setItems(
                                arrayOf("W A S D", "Arrow keys", "Controller D-pad", "I J K L")) { _, which ->
                                val set = when (which) {
                                    0 -> listOf("W", "S", "A", "D").map { Binding.key(it) }
                                    1 -> listOf("Up", "Down", "Left", "Right").map { Binding.key(it) }
                                    2 -> listOf("Up", "Down", "Left", "Right").map { Binding.pad(it) }
                                    else -> listOf("I", "K", "J", "L").map { Binding.key(it) }
                                }
                                for (d in 0..3) w.dirs[d] = set[d]
                                fillDirs()
                            }.show()
                        }
                    })
                }
                fillDirs()
                lateinit var sens: View
                fun syncMode() {
                    dirBox.visibility = if (w.stickMode == StickMode.DIRECTIONAL) View.VISIBLE else View.GONE
                    sens.visibility = if (w.stickMode == StickMode.MOUSE) View.VISIBLE else View.GONE
                }
                ctx.spinner(col, StickMode.values().map { it.title }, w.stickMode.ordinal) {
                    w.stickMode = StickMode.values()[it]; syncMode()
                }
                col.addView(dirBox)
                sens = ctx.slider(col, "Mouse speed", 0.1f, 5f, w.sensitivity, ::times) { w.sensitivity = it }
                syncMode()
                ctx.slider(col, "Dead zone", 0f, 0.6f, w.deadzone, ::pct) { w.deadzone = it }
                ctx.check(col, "Floating (centre moves to where your thumb lands)", w.floating) { w.floating = it }
                ctx.heading(col, "Pushed all the way to the edge, also press (e.g. Shift to sprint)")
                ctx.bindingList(col, w.bindings, "Nothing")
            }
            ElementType.TOUCHPAD -> {
                ctx.note(col, "Drag on it to move the mouse.")
                ctx.slider(col, "Mouse speed", 0.1f, 5f, w.sensitivity, ::times) { w.sensitivity = it }
                ctx.heading(col, "A quick tap presses")
                ctx.bindingList(col, w.bindings, "Nothing (tapping does nothing)")
            }
        }

        ctx.heading(col, "Size")
        ctx.slider(col, if (w.type == ElementType.BUTTON || w.type == ElementType.TOUCHPAD) "Width" else "Size",
            MIN_SIZE, MAX_SIZE, w.w, ::pct) { w.w = it; if (w.isRound) w.h = it }
        heightSlider = ctx.slider(col, "Height", MIN_SIZE, MAX_SIZE, w.h, ::pct) { w.h = it }
        syncShape()

        AlertDialog.Builder(ctx, DIALOG_THEME)
            .setTitle(w.type.title)
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                w.label = labelEdit.text.toString().trim()
                onSave(w)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ -> onDelete() }
            .show()
    }

    // ------------------------------------------------------------------ layout settings

    fun editLayout(ctx: Context, layout: Layout, onSave: () -> Unit) {
        val col = ctx.column()
        ctx.heading(col, "Name")
        val name = EditText(ctx).apply { setText(layout.name); isSingleLine = true }
        col.addView(name)
        var opacity = layout.opacity
        var haptics = layout.haptics
        ctx.slider(col, "Control visibility", 0.15f, 1f, opacity, ::pct) { opacity = it }
        ctx.check(col, "Vibrate on press", haptics) { haptics = it }
        AlertDialog.Builder(ctx, DIALOG_THEME)
            .setTitle("Layout settings")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                layout.name = name.text.toString().trim().ifEmpty { layout.name }
                layout.opacity = opacity
                layout.haptics = haptics
                onSave()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun askText(ctx: Context, title: String, initial: String, onOk: (String) -> Unit) {
        val col = ctx.column()
        val edit = EditText(ctx).apply { setText(initial); isSingleLine = true; setSelection(initial.length) }
        col.addView(edit)
        AlertDialog.Builder(ctx, DIALOG_THEME)
            .setTitle(title)
            .setView(col)
            .setPositiveButton("OK") { _, _ -> onOk(edit.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun theme() = DIALOG_THEME
}

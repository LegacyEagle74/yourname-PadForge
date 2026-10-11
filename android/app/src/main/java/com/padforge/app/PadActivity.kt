package com.padforge.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.padforge.app.Dialogs.px

/** Full-screen controller. Plays a layout, or edits it when edit mode is on. */
class PadActivity : Activity() {

    companion object {
        const val EXTRA_LAYOUT = "layout"
        const val EXTRA_EDIT = "edit"
    }

    private lateinit var store: Store
    private lateinit var layout: Layout
    private lateinit var pad: PadView
    private lateinit var status: TextView
    private lateinit var menuButton: Button
    private lateinit var toolbar: View
    private val selectionButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        store = Store(this)
        layout = store.get(intent.getStringExtra(EXTRA_LAYOUT) ?: "") ?: run { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pad = PadView(this).also { it.layout = layout }
        root.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            textSize = 11f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(px(8), px(2), px(8), px(4))
        }
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        menuButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "⋯"
            textSize = 20f
            setTextColor(0xAAFFFFFF.toInt())
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(px(14), 0, px(14), px(2))
            setOnClickListener { showMenu() }
        }
        root.addView(menuButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, px(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        toolbar = buildToolbar()
        root.addView(toolbar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        setContentView(root)

        pad.onSelectionChanged = { sel -> selectionButtons.forEach { it.isEnabled = sel != null } }
        pad.onEditElement = { editElement(it) }
        pad.onLayoutChanged = { save() }
        Connection.listener = { status.text = Connection.statusText() }

        setEditMode(intent.getBooleanExtra(EXTRA_EDIT, false))
    }

    private fun buildToolbar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(4), px(6), px(4))
            background = GradientDrawable().apply {
                setColor(0xE0202428.toInt())
                cornerRadius = px(10).toFloat()
            }
        }
        fun add(label: String, needsSelection: Boolean = false, action: () -> Unit) {
            val b = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                minWidth = 0; minimumWidth = 0
                setPadding(px(10), 0, px(10), 0)
                setOnClickListener { action() }
            }
            if (needsSelection) selectionButtons.add(b)
            row.addView(b)
        }
        add("+ Button") { addElement(ElementType.BUTTON) }
        add("+ Stick") { addElement(ElementType.JOYSTICK) }
        add("+ Touchpad") { addElement(ElementType.TOUCHPAD) }
        add("Edit", true) { pad.selected?.let { editElement(it) } }
        add("Copy", true) { duplicateSelected() }
        add("To front", true) {
            pad.selected?.let { layout.elements.remove(it); layout.elements.add(it); pad.invalidate(); save() }
        }
        add("Delete", true) { pad.selected?.let { deleteElement(it) } }
        add("Settings") { Dialogs.editLayout(this, layout) { pad.invalidate(); save() } }
        add("Done") { setEditMode(false) }
        selectionButtons.forEach { it.isEnabled = false }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            setPadding(0, px(6), 0, 0)
        }
    }

    private fun setEditMode(on: Boolean) {
        pad.editMode = on
        toolbar.visibility = if (on) View.VISIBLE else View.GONE
        menuButton.visibility = if (on) View.GONE else View.VISIBLE
        status.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) save()
    }

    private fun showMenu() {
        pad.releaseAll()
        AlertDialog.Builder(this, Dialogs.theme())
            .setTitle(layout.name)
            .setItems(arrayOf("Edit layout", "Layout settings", "Reconnect", "Back to layouts")) { _, which ->
                when (which) {
                    0 -> setEditMode(true)
                    1 -> Dialogs.editLayout(this, layout) { pad.invalidate(); save() }
                    2 -> Connection.start(store.host, store.port)
                    3 -> finish()
                }
            }
            .show()
    }

    private fun addElement(type: ElementType) {
        val e = when (type) {
            ElementType.BUTTON -> Element(type = type, w = 0.16f, h = 0.16f, color = COLORS[0])
            ElementType.JOYSTICK -> Element(type = type, w = 0.4f, h = 0.4f, color = COLORS[6])
            ElementType.TOUCHPAD -> Element(type = type, w = 0.7f, h = 0.5f, color = COLORS[7])
        }
        // Drop it near the middle, nudged so repeated adds don't stack exactly.
        val n = layout.elements.size % 5
        e.x = 0.5f + (n - 2) * 0.04f
        e.y = 0.55f
        Dialogs.editElement(this, e, onSave = { saved ->
            layout.elements.add(saved)
            pad.selected = saved
            save()
        }, onDelete = {})
    }

    private fun editElement(e: Element) {
        Dialogs.editElement(this, e, onSave = { saved ->
            val i = layout.elements.indexOfFirst { it.id == e.id }
            if (i >= 0) layout.elements[i] = saved else layout.elements.add(saved)
            pad.selected = saved
            save()
        }, onDelete = { deleteElement(e) })
    }

    private fun duplicateSelected() {
        val e = pad.selected ?: return
        val c = e.copy(newId = true)
        c.x = (c.x + 0.04f).coerceAtMost(1f)
        c.y = (c.y + 0.06f).coerceAtMost(1f)
        layout.elements.add(c)
        pad.selected = c
        save()
    }

    private fun deleteElement(e: Element) {
        layout.elements.removeAll { it.id == e.id }
        pad.selected = null
        save()
    }

    private fun save() {
        pad.invalidate()
        pad.updateGestureExclusion()
        store.put(layout)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        Connection.listener = { status.text = Connection.statusText() }
        Connection.start(store.host, store.port)
        status.text = Connection.statusText()
    }

    override fun onPause() {
        pad.releaseAll()
        Connection.stop()
        if (pad.editMode) save()
        super.onPause()
    }

    override fun onDestroy() {
        if (::pad.isInitialized) Connection.listener = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else if (::pad.isInitialized) pad.releaseAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pad.editMode) setEditMode(false) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }
}

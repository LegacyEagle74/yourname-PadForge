package com.padforge.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.padforge.app.Dialogs.px
import org.json.JSONObject

/** Home screen: PC address, finding the PC, and the list of layouts. */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var findStatus: TextView
    private lateinit var list: ListView
    private var layouts = mutableListOf<Layout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(8))
        }
        // Android 15 draws apps edge to edge, so pad the content away from the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                listOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            v.setPadding(px(16) + l, px(16) + t, px(16) + r, px(8) + b)
            insets
        }

        root.addView(TextView(this).apply {
            text = "PadForge"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Custom on-screen controls for your PC"
            textSize = 14f
            setTextColor(0x99FFFFFF.toInt())
        })

        val addrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(16), 0, 0)
        }
        hostEdit = EditText(this).apply {
            hint = "PC address, e.g. 192.168.1.20"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(store.host)
        }
        portEdit = EditText(this).apply {
            hint = "Port"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(store.port.toString())
        }
        addrRow.addView(hostEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addrRow.addView(portEdit, LinearLayout.LayoutParams(px(84), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(addrRow)

        val findRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        findRow.addView(Button(this).apply {
            text = "Find PC"
            setOnClickListener { findPc() }
        })
        findStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(px(12), 0, 0, 0)
            text = "Start the companion on your PC, then tap Find PC."
        }
        findRow.addView(findStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(findRow)

        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(18), 0, 0)
        }
        headRow.addView(TextView(this).apply {
            text = "Layouts"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headRow.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Import"
            setOnClickListener { importLayout() }
        })
        headRow.addView(Button(this).apply {
            text = "+ New"
            setOnClickListener { newLayout() }
        })
        root.addView(headRow)

        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ -> play(layouts[pos], edit = false) }
        list.setOnItemLongClickListener { _, _, pos, _ -> layoutMenu(layouts[pos]); true }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = "Tap a layout to play. Long-press it to edit, rename, copy, share or delete."
            textSize = 12f
            setTextColor(0x88FFFFFF.toInt())
            setPadding(0, px(6), 0, 0)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        saveAddress()
        super.onPause()
    }

    private fun saveAddress() {
        store.host = hostEdit.text.toString()
        store.port = portEdit.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 47800
    }

    private fun refresh() {
        layouts = store.loadAll()
        // simple_list_item_2 shows the name plus a summary line.
        list.adapter = object : ArrayAdapter<Layout>(this, android.R.layout.simple_list_item_2,
            android.R.id.text1, layouts) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                val l = layouts[position]
                v.findViewById<TextView>(android.R.id.text1).text = l.name
                v.findViewById<TextView>(android.R.id.text2).text = summary(l)
                return v
            }
        }
    }

    private fun summary(l: Layout): String {
        val b = l.elements.count { it.type == ElementType.BUTTON }
        val s = l.elements.count { it.type == ElementType.JOYSTICK }
        val t = l.elements.count { it.type == ElementType.TOUCHPAD }
        val parts = mutableListOf<String>()
        if (b > 0) parts += "$b button${if (b == 1) "" else "s"}"
        if (s > 0) parts += "$s stick${if (s == 1) "" else "s"}"
        if (t > 0) parts += "$t touchpad${if (t == 1) "" else "s"}"
        return parts.joinToString(" · ").ifEmpty { "Empty" }
    }

    private fun play(l: Layout, edit: Boolean) {
        saveAddress()
        if (!edit && store.host.isBlank()) {
            Toast.makeText(this, "Set your PC's address first (or tap Find PC)", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, PadActivity::class.java)
            .putExtra(PadActivity.EXTRA_LAYOUT, l.id)
            .putExtra(PadActivity.EXTRA_EDIT, edit))
    }

    private fun findPc() {
        findStatus.text = "Searching your network…"
        Thread {
            val found = try { Connection.discover() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                when {
                    found.isEmpty() -> findStatus.text =
                        "No PC answered. Is the companion running, on the same Wi-Fi, and allowed through Windows Firewall?"
                    found.size == 1 -> pickFound(found[0])
                    else -> AlertDialog.Builder(this, Dialogs.theme())
                        .setTitle("Choose your PC")
                        .setItems(found.map { "${it.name}  (${it.address})" }.toTypedArray()) { _, i -> pickFound(found[i]) }
                        .show()
                }
            }
        }.start()
    }

    private fun pickFound(f: Connection.Found) {
        hostEdit.setText(f.address)
        portEdit.setText(f.port.toString())
        saveAddress()
        findStatus.text = "Found ${f.name}"
    }

    private fun newLayout() {
        val names = Defaults.templates.map { it.first }.toTypedArray()
        AlertDialog.Builder(this, Dialogs.theme())
            .setTitle("Start from")
            .setItems(names) { _, i ->
                val l = Defaults.templates[i].second()
                Dialogs.askText(this, "Name", if (i == 0) "My layout" else l.name) { name ->
                    l.name = name.ifEmpty { l.name }
                    store.put(l)
                    refresh()
                    play(l, edit = true)
                }
            }
            .show()
    }

    private fun layoutMenu(l: Layout) {
        AlertDialog.Builder(this, Dialogs.theme())
            .setTitle(l.name)
            .setItems(arrayOf("Play", "Edit", "Rename", "Duplicate", "Share / export", "Delete")) { _, which ->
                when (which) {
                    0 -> play(l, edit = false)
                    1 -> play(l, edit = true)
                    2 -> Dialogs.askText(this, "Rename", l.name) { n ->
                        if (n.isNotEmpty()) { l.name = n; store.put(l); refresh() }
                    }
                    3 -> {
                        val c = l.copy().apply { id = Element.newId(); name = "${l.name} copy" }
                        store.put(c); refresh()
                    }
                    4 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "PadForge layout: ${l.name}")
                        putExtra(Intent.EXTRA_TEXT, l.toJson().toString(2))
                    }, "Share layout"))
                    5 -> AlertDialog.Builder(this, Dialogs.theme())
                        .setMessage("Delete \"${l.name}\"?")
                        .setPositiveButton("Delete") { _, _ ->
                            store.saveAll(store.loadAll().filter { it.id != l.id })
                            refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun importLayout() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val edit = EditText(this).apply {
            hint = "Paste layout JSON here"
            setText(if (clip.trimStart().startsWith("{")) clip else "")
            minLines = 4
            maxLines = 10
            textSize = 12f
        }
        val box = LinearLayout(this).apply {
            setPadding(px(20), px(8), px(20), 0)
            addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this, Dialogs.theme())
            .setTitle("Import layout")
            .setView(box)
            .setPositiveButton("Import") { _, _ ->
                try {
                    val l = Layout.fromJson(JSONObject(edit.text.toString()))
                    l.id = Element.newId()
                    store.put(l)
                    refresh()
                    Toast.makeText(this, "Imported \"${l.name}\"", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "That isn't a PadForge layout", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

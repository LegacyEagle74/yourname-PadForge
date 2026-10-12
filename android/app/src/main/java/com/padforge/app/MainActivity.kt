package com.padforge.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.view.View
import rikka.shizuku.Shizuku
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
    private lateinit var codeEdit: EditText
    private lateinit var remoteEdit: EditText
    private lateinit var awayStatus: TextView
    private val testListener: () -> Unit = { onTestUpdate() }
    private lateinit var overlayCheck: CheckBox
    private lateinit var findStatus: TextView
    private lateinit var list: ListView
    private lateinit var pcBox: LinearLayout
    private lateinit var phoneBox: LinearLayout
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuButton: Button
    private val shizukuChanged = Shizuku.OnBinderReceivedListener { runOnUiThread { updateModeUi() } }
    private val shizukuGone = Shizuku.OnBinderDeadListener { runOnUiThread { updateModeUi() } }
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

        // ---- where input goes
        root.addView(TextView(this).apply {
            text = "Send input to"
            textSize = 13f
            setTextColor(0xFF80CBC4.toInt())
            setPadding(0, px(14), 0, 0)
        })
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val pcRadio = RadioButton(this).apply { id = View.generateViewId(); text = "My PC (PadForge companion over Wi-Fi / Tailscale)" }
        val phoneRadio = RadioButton(this).apply { id = View.generateViewId(); text = "This phone: keyboard & mouse into the app underneath (Shizuku)" }
        modeGroup.addView(pcRadio); modeGroup.addView(phoneRadio)
        modeGroup.check(if (store.phoneMode) phoneRadio.id else pcRadio.id)
        modeGroup.setOnCheckedChangeListener { _, id ->
            stopTest()
            store.phoneMode = id == phoneRadio.id
            updateModeUi()
        }
        root.addView(modeGroup)

        pcBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pcBox)

        val addrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(4), 0, 0)
        }
        hostEdit = EditText(this).apply {
            hint = "Home address, e.g. 192.168.1.20"
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
        pcBox.addView(addrRow)

        remoteEdit = EditText(this).apply {
            hint = "Away address (Tailscale, 100.x.x.x): fills in automatically"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(store.remoteHost)
        }
        pcBox.addView(remoteEdit)

        codeEdit = EditText(this).apply {
            hint = "Pairing code (shown on your PC)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(store.pairingCode)
        }
        pcBox.addView(codeEdit)

        val findRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        findRow.addView(Button(this).apply {
            text = "Find PC"
            setOnClickListener { findPc() }
        })
        findRow.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Test"
            setOnClickListener { testConnection() }
        })
        findStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(px(12), 0, 0, 0)
            text = "Start the companion on your PC, then tap Find PC."
        }
        findRow.addView(findStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pcBox.addView(findRow)

        awayStatus = TextView(this).apply {
            textSize = 13f
            setPadding(px(4), px(6), 0, 0)
        }
        pcBox.addView(awayStatus)

        // ---- phone mode (Shizuku)
        phoneBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(4), 0, 0) }
        shizukuStatus = TextView(this).apply { textSize = 13f; setPadding(px(4), px(4), 0, px(4)) }
        phoneBox.addView(shizukuStatus)
        shizukuButton = Button(this).apply { setOnClickListener { onShizukuButton() } }
        phoneBox.addView(shizukuButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        phoneBox.addView(TextView(this).apply {
            text = "Mouse movement"
            textSize = 13f
            setTextColor(0xFF80CBC4.toInt())
            setPadding(px(4), px(8), 0, 0)
        })
        phoneBox.addView(android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, listOf(
                "Auto: game mouse when Parsec locks the cursor, pointer otherwise",
                "Always game mouse (relative)",
                "Always visible pointer")).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(store.mouseMode.coerceIn(0, 2), false)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    store.mouseMode = pos
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        })
        root.addView(phoneBox)

        overlayCheck = CheckBox(this).apply {
            text = "Parsec mode: show controls on top of Parsec"
            isChecked = getSharedPreferences("padforge", MODE_PRIVATE).getBoolean("overlayMode", false)
            setOnCheckedChangeListener { _, on ->
                getSharedPreferences("padforge", MODE_PRIVATE).edit().putBoolean("overlayMode", on).apply()
            }
            setPadding(px(4), px(8), 0, 0)
        }
        root.addView(overlayCheck)

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
        list.setOnItemClickListener { _, _, pos, _ ->
            if (store.phoneMode || overlayCheck.isChecked) playOverlay(layouts[pos]) else play(layouts[pos], edit = false)
        }
        list.setOnItemLongClickListener { _, _, pos, _ -> layoutMenu(layouts[pos]); true }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = "Tap a layout to play. Long-press it to edit, rename, copy, share or delete. " +
                "In Parsec mode, tap the small floating button to hide/show controls and long-press it to stop."
            textSize = 12f
            setTextColor(0x88FFFFFF.toInt())
            setPadding(0, px(6), 0, 0)
        })

        setContentView(root)
        Shizuku.addBinderReceivedListenerSticky(shizukuChanged)
        Shizuku.addBinderDeadListener(shizukuGone)
        updateModeUi()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuChanged)
        Shizuku.removeBinderDeadListener(shizukuGone)
        super.onDestroy()
    }

    /** Shows the settings for the chosen output and refreshes the Shizuku status. */
    private fun updateModeUi() {
        val phone = store.phoneMode
        pcBox.visibility = if (phone) View.GONE else View.VISIBLE
        phoneBox.visibility = if (phone) View.VISIBLE else View.GONE
        overlayCheck.text = if (phone) "Open Parsec automatically when I start a layout"
            else "Parsec mode: show controls on top of Parsec"
        if (!phone) return
        val (msg, button, good) = when (LocalInjector.setupState(this)) {
            LocalInjector.Setup.NOT_INSTALLED -> Triple(
                "Shizuku isn't installed. It's a free app that lets PadForge send key presses and mouse " +
                    "input to other apps (Android doesn't allow that otherwise).", "Get Shizuku", false)
            LocalInjector.Setup.NOT_RUNNING -> Triple(
                "Shizuku is installed but not running. Open it and start it with \"Wireless debugging\" " +
                    "(needed again after every phone restart).", "Open Shizuku", false)
            LocalInjector.Setup.OLD_VERSION -> Triple("Shizuku is too old. Update it from the Play Store.",
                "Update Shizuku", false)
            LocalInjector.Setup.NEEDS_PERMISSION -> Triple(
                "Shizuku is running. Allow PadForge to use it.", "Allow PadForge", false)
            LocalInjector.Setup.READY -> Triple(
                "Ready: tap a layout to put it on top of Parsec. Key presses and mouse go into Parsec " +
                    "like a real keyboard and mouse. No PC software needed.", "Open Shizuku", true)
        }
        shizukuStatus.text = msg
        shizukuStatus.setTextColor(if (good) 0xFF81C784.toInt() else 0xFFFFB74D.toInt())
        shizukuButton.text = button
    }

    private fun onShizukuButton() {
        if (LocalInjector.setupState(this) == LocalInjector.Setup.NEEDS_PERMISSION) {
            LocalInjector.ensurePermission { runOnUiThread { updateModeUi() } }
        } else {
            LocalInjector.openShizuku(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateModeUi()
        // The overlay may have learned the Tailscale address while we were in the background.
        if (store.remoteHost.isNotEmpty() && remoteEdit.text.isBlank()) remoteEdit.setText(store.remoteHost)
        updateAwayStatus()
        refresh()
    }

    override fun onPause() {
        stopTest()
        saveAddress()
        super.onPause()
    }

    private fun saveAddress() {
        store.host = hostEdit.text.toString()
        store.port = portEdit.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 47800
        store.pairingCode = codeEdit.text.toString()
        store.remoteHost = remoteEdit.text.toString()
        updateAwayStatus()
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

    /** Checks the address and pairing code are filled in; explains what's missing if not. */
    private fun readyToConnect(): Boolean {
        saveAddress()
        val missing = when {
            store.host.isBlank() -> "Set your PC's address first (or tap Find PC)"
            store.pairingCode.isBlank() -> "Enter the pairing code shown in the PadForge window on your PC"
            else -> return true
        }
        Toast.makeText(this, missing, Toast.LENGTH_LONG).show()
        return false
    }

    private fun play(l: Layout, edit: Boolean) {
        saveAddress()
        if (!edit && store.phoneMode) { playOverlay(l); return }
        if (!edit && !readyToConnect()) return
        startActivity(Intent(this, PadActivity::class.java)
            .putExtra(PadActivity.EXTRA_LAYOUT, l.id)
            .putExtra(PadActivity.EXTRA_EDIT, edit))
    }

    /** Starts the floating controls and switches to Parsec. */
    private fun playOverlay(l: Layout) {
        saveAddress()
        if (!store.phoneMode && !readyToConnect()) return
        if (!OverlayService.canDraw(this)) {
            AlertDialog.Builder(this, Dialogs.theme())
                .setTitle("Allow drawing over other apps")
                .setMessage("To show your controls on top of Parsec, Android needs you to turn on " +
                    "\"Display over other apps\" for PadForge. Then come back and tap the layout again.")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("padforge", MODE_PRIVATE).getBoolean("askedNotif", false)) {
            // Optional: the notification holds the Hide / Edit / Stop buttons.
            getSharedPreferences("padforge", MODE_PRIVATE).edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (store.phoneMode) {
            when (LocalInjector.setupState(this)) {
                LocalInjector.Setup.READY -> {}
                LocalInjector.Setup.NEEDS_PERMISSION -> {
                    LocalInjector.ensurePermission { ok ->
                        runOnUiThread {
                            updateModeUi()
                            if (ok) launchOverlay(l)
                            else Toast.makeText(this, "Phone mode needs Shizuku permission", Toast.LENGTH_LONG).show()
                        }
                    }
                    return
                }
                else -> {
                    updateModeUi()
                    Toast.makeText(this, shizukuStatus.text, Toast.LENGTH_LONG).show()
                    return
                }
            }
        }
        launchOverlay(l)
    }

    private fun launchOverlay(l: Layout) {
        OverlayService.start(this, l.id)
        val parsec = if (store.phoneMode && !overlayCheck.isChecked) null
            else packageManager.getLaunchIntentForPackage(OverlayService.PARSEC_PACKAGE)
        if (parsec != null) {
            startActivity(parsec)
        } else {
            Toast.makeText(this, "Overlay is on. Open Parsec (or any app) and your controls will be on top.",
                Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
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
        if (f.tailscale.isNotEmpty()) remoteEdit.setText(f.tailscale)
        saveAddress()
        findStatus.text = "Found ${f.name}" + if (f.tailscale.isEmpty()) " (no Tailscale on that PC yet)" else ""
    }

    /** Shows whether playing away from home is set up, and what's missing if not. */
    private fun updateAwayStatus() {
        val remote = store.remoteHost
        val (msg, color) = when {
            remote.isEmpty() -> "Away from home: not set up. Install Tailscale on your PC and this phone, " +
                "update the PC companion, then tap Test here at home." to 0xFFFFB74D.toInt()
            !Connection.isTailscale(remote) -> "Away from home: \"$remote\" isn't a Tailscale address " +
                "(they start with 100.)." to 0xFFFFB74D.toInt()
            !Connection.phoneOnTailscale() -> "Away from home: ready ($remote), but Tailscale is off on this " +
                "phone. Turn it on before you leave." to 0xFFFFB74D.toInt()
            else -> "Away from home: ready ($remote via Tailscale)" to 0xFF81C784.toInt()
        }
        awayStatus.text = msg
        awayStatus.setTextColor(color)
    }

    /** Connects with the saved settings and reports the result (also learns the Tailscale address). */
    private fun testConnection() {
        if (!readyToConnect()) return
        if (OverlayService.running) {
            findStatus.text = "Stop the overlay first (long-press its floating button)."
            return
        }
        findStatus.text = "Testing…"
        Connection.listener = testListener
        Connection.start(store)
        findStatus.postDelayed({
            if (Connection.listener === testListener && Connection.state != Connection.State.CONNECTED) {
                findStatus.text = Connection.statusText()
                stopTest()
            }
        }, 15000)
    }

    private fun onTestUpdate() {
        when (Connection.state) {
            Connection.State.CONNECTED -> {
                findStatus.text = "✓ " + Connection.statusText()
                if (store.remoteHost.isNotEmpty() && remoteEdit.text.toString() != store.remoteHost) {
                    remoteEdit.setText(store.remoteHost)
                }
                updateAwayStatus()
                // Keep it open long enough to show latency, then hang up.
                findStatus.postDelayed({ if (Connection.listener === testListener) stopTest() }, 2500)
            }
            Connection.State.OFF -> if (Connection.lastError.isNotEmpty()) {
                findStatus.text = Connection.lastError
                stopTest()
            }
            Connection.State.CONNECTING -> if (Connection.lastError.isNotEmpty()) findStatus.text = Connection.lastError
        }
    }

    private fun stopTest() {
        if (Connection.listener === testListener) {
            Connection.listener = null
            Connection.stop()
        }
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
            .setItems(arrayOf("Play full screen", "Play over Parsec (overlay)", "Edit", "Rename", "Duplicate",
                "Share / export", "Delete")) { _, which ->
                when (which) {
                    0 -> play(l, edit = false)
                    1 -> playOverlay(l)
                    2 -> play(l, edit = true)
                    3 -> Dialogs.askText(this, "Rename", l.name) { n ->
                        if (n.isNotEmpty()) { l.name = n; store.put(l); refresh() }
                    }
                    4 -> {
                        val c = l.copy().apply { id = Element.newId(); name = "${l.name} copy" }
                        store.put(c); refresh()
                    }
                    5 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "PadForge layout: ${l.name}")
                        putExtra(Intent.EXTRA_TEXT, l.toJson().toString(2))
                    }, "Share layout"))
                    6 -> AlertDialog.Builder(this, Dialogs.theme())
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

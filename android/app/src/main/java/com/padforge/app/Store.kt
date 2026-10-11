package com.padforge.app

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Saves layouts as JSON in the app's private storage, plus a few settings. */
class Store(private val context: Context) {
    private val file = File(context.filesDir, "layouts.json")
    private val prefs = context.getSharedPreferences("padforge", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v.trim()).apply()

    var port: Int
        get() = prefs.getInt("port", 47800)
        set(v) = prefs.edit().putInt("port", v).apply()

    /** Pairing code shown by the PC companion, stored without dashes. */
    var pairingCode: String
        get() = prefs.getString("code", "") ?: ""
        set(v) = prefs.edit().putString("code", normalizeCode(v)).apply()

    /** Corner the overlay handle was last dragged to (fractions of the screen). */
    var handleX: Float
        get() = prefs.getFloat("handleX", 0.5f)
        set(v) = prefs.edit().putFloat("handleX", v).apply()
    var handleY: Float
        get() = prefs.getFloat("handleY", 0.02f)
        set(v) = prefs.edit().putFloat("handleY", v).apply()

    companion object {
        fun normalizeCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
    }

    fun loadAll(): MutableList<Layout> {
        if (!file.exists()) {
            val seeded = Defaults.all()
            saveAll(seeded)
            return seeded
        }
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { Layout.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            // A corrupt file should never brick the app; keep a copy and start over.
            file.copyTo(File(context.filesDir, "layouts.broken.json"), overwrite = true)
            Defaults.all().also { saveAll(it) }
        }
    }

    fun saveAll(list: List<Layout>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(context.filesDir, "layouts.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun get(id: String): Layout? = loadAll().firstOrNull { it.id == id }

    fun put(layout: Layout) {
        val all = loadAll()
        val i = all.indexOfFirst { it.id == layout.id }
        if (i >= 0) all[i] = layout else all.add(layout)
        saveAll(all)
    }
}

package com.anwar.walkie

import android.content.Context
import kotlin.random.Random

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("walkie", Context.MODE_PRIVATE)

    val uid: Int
        get() {
            var v = p.getInt("uid", 0)
            if (v == 0) { v = Random.nextInt(1, Int.MAX_VALUE); p.edit().putInt("uid", v).apply() }
            return v
        }
    var name: String
        get() = p.getString("name", "مستخدم") ?: "مستخدم"
        set(v) = p.edit().putString("name", v.trim().ifEmpty { "مستخدم" }).apply()
    var code: String
        get() = p.getString("code", "88888888") ?: "88888888"
        set(v) = p.edit().putString("code", v.trim().ifEmpty { "88888888" }).apply()
    var channel: Int
        get() = p.getInt("channel", 1)
        set(v) = p.edit().putInt("channel", v).apply()
    var boost: Float
        get() = p.getFloat("boost", 1.5f)
        set(v) = p.edit().putFloat("boost", v).apply()
    var keepScreen: Boolean
        get() = p.getBoolean("keep", true)
        set(v) = p.edit().putBoolean("keep", v).apply()
    var beep: Boolean
        get() = p.getBoolean("beep", true)
        set(v) = p.edit().putBoolean("beep", v).apply()
    var volPtt: Boolean
        get() = p.getBoolean("volptt", true)
        set(v) = p.edit().putBoolean("volptt", v).apply()
    var autoStart: Boolean
        get() = p.getBoolean("autostart", false)
        set(v) = p.edit().putBoolean("autostart", v).apply()

    /** الأشخاص المحفوظون تلقائياً (uid -> اسم). */
    var contacts: Map<Int, String>
        get() = (p.getString("contacts", "") ?: "").lines().mapNotNull {
            val i = it.indexOf('\t'); if (i < 1) null else it.substring(0, i).toIntOrNull()?.let { id -> id to it.substring(i + 1) }
        }.toMap()
        set(m) = p.edit().putString("contacts", m.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
}

package no.ariescontrol

import android.content.Context
import android.content.SharedPreferences

/** A streamer found on the network. [ip] may include a port ("192.168.1.20" or "192.168.1.20:8080"). */
data class Streamer(val ip: String, val name: String, val model: String = "", val source: String = "")

fun Context.prefs(): SharedPreferences = getSharedPreferences("aries", Context.MODE_PRIVATE)

/** Remembers the last streamer, so the app opens it straight away next time. */
object Prefs {
    fun save(ctx: Context, s: Streamer) {
        ctx.prefs().edit().putString("ip", s.ip).putString("name", s.name).putString("model", s.model).apply()
    }

    fun load(ctx: Context): Streamer? {
        val p = ctx.prefs()
        val ip = p.getString("ip", null) ?: return null
        return Streamer(ip, p.getString("name", null) ?: "Aries", p.getString("model", null) ?: "", "saved")
    }

    /** Accepts "192.168.1.20", "http://192.168.1.20/", "aries.local:80" etc. Returns host[:port] or null. */
    fun cleanAddress(input: String): String? {
        var s = input.trim()
        s = s.removePrefix("http://").removePrefix("https://")
        s = s.substringBefore('/').trim()
        return if (Regex("^[A-Za-z0-9.-]+(:\\d{1,5})?$").matches(s) && s.any { it.isLetterOrDigit() }) s else null
    }
}

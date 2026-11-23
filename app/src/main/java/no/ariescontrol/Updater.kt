package no.ariescontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update check without any app store: looks at the same place the APK files are published
 * and, if there is a newer version, lets the user download it in the browser.
 */
object Updater {
    private const val BASE = "https://github.com/ingelangeland/AriesControl/releases/download/siste/"
    private const val NOTIF = 45

    data class Info(val versionCode: Long, val versionName: String, val apk: String)

    /** Set by each app: which APK file in the release is this app. */
    @Volatile var apkName: String = ""
    val available = MutableStateFlow<Info?>(null)
    val status = MutableStateFlow("")
    @Volatile private var lastCheck = 0L

    fun currentVersion(ctx: Context): Pair<Long, String> = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        (if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()) to (p.versionName ?: "")
    }.getOrDefault(0L to "")

    fun autoCheck(ctx: Context) = ctx.prefs().getBoolean("autoUpdate", true)

    /** Checks for a newer version (at most every [minIntervalMs]). Returns the update, or null if up to date. */
    suspend fun check(ctx: Context, minIntervalMs: Long = 0, notify: Boolean = false): Info? = withContext(Dispatchers.IO) {
        if (SystemClock.elapsedRealtime() - lastCheck < minIntervalMs && lastCheck != 0L) return@withContext available.value
        lastCheck = SystemClock.elapsedRealtime()
        status.value = "Checking for updates …"
        try {
            val c = URL(BASE + "version.json").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "AriesControl-updater")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val vc = j.getLong("versionCode")
            val mine = currentVersion(ctx).first
            val info = if (vc > mine) Info(vc, j.optString("versionName", "1.0.$vc"), apkName) else null
            available.value = info
            status.value = if (info == null) "Up to date" else "Version ${info.versionName} is available"
            if (info != null && notify) notifyAvailable(ctx, info)
            info
        } catch (e: Exception) {
            status.value = "Couldn't check for updates (${e.message ?: "no connection"})"
            null
        }
    }

    /** Opens the new version in the browser: Android's own download and installer handle it (no installing from inside the app). */
    fun download(ctx: Context) {
        val info = available.value
        val url = if (info != null && info.apk.isNotEmpty()) BASE + info.apk else "https://github.com/ingelangeland/AriesControl/releases/latest"
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            status.value = "Downloading in the browser – open the file when it is done"
        }
        clearNotification(ctx)
    }

    private fun notifyAvailable(ctx: Context, info: Info) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        runCatching {
            nm.createNotificationChannel(NotificationChannel("update", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return)
                .putExtra("aries_update", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 6, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIF, NotificationCompat.Builder(ctx, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Aries Control update available")
                .setContentText("Version ${info.versionName} – tap to download")
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    internal fun clearNotification(ctx: Context) = runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF) }
}


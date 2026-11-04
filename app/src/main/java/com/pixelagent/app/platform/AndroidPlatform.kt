package com.pixelagent.app.platform

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pixelagent.app.R
import com.pixelagent.app.core.EncodedImage
import com.pixelagent.app.core.Platform
import com.pixelagent.app.core.ScreenController
import com.pixelagent.app.core.humanSize
import java.io.ByteArrayOutputStream
import java.io.File

/** Android implementation of the agent's [Platform] interface. */
class AndroidPlatform(private val ctx: Context) : Platform {

    private class AppEntry(val label: String, val pkg: String)

    private fun launchableApps(): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(intent, 0)
        return list.map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun startSafely(intent: Intent): String? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "No app on this phone can handle that."
        } catch (e: SecurityException) {
            "Android blocked the request: ${e.message}"
        } catch (e: Exception) {
            "Could not start: ${e.message}"
        }
    }

    override fun deviceInfo(): String {
        val sb = StringBuilder()
        sb.append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            sb.append("Battery: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
            sb.append(if (bm.isCharging) " (charging)\n" else "\n")
        } catch (e: Exception) {
            sb.append("Battery: unknown\n")
        }
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            sb.append("Storage: ${humanSize(st.availableBytes)} free of ${humanSize(st.totalBytes)}\n")
        } catch (e: Exception) {
            // ignore
        }
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "connected"
            }
            sb.append("Network: $net\n")
        } catch (e: SecurityException) {
            sb.append("Network: unknown\n")
        }
        val dm = ctx.resources.displayMetrics
        sb.append("Screen: ${dm.widthPixels}x${dm.heightPixels} px\n")
        sb.append("Phone control (accessibility): ${if (Perms.phoneControlEnabled()) "ENABLED" else "off"}\n")
        sb.append("All-files access: ${if (Perms.hasAllFiles(ctx)) "granted" else "not granted"}\n")
        sb.append("Notifications: ${if (Perms.notificationsGranted(ctx)) "allowed" else "blocked"}")
        return sb.toString()
    }

    override fun listApps(query: String): String {
        val q = query.trim().lowercase()
        val apps = launchableApps().filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        if (apps.isEmpty()) return if (q.isEmpty()) "No launchable apps found." else "No apps match '$query'."
        val shown = apps.take(150)
        return "${apps.size} app(s):\n" + shown.joinToString("\n") { "${it.label} (${it.pkg})" } +
            if (apps.size > shown.size) "\n… and ${apps.size - shown.size} more; narrow with a query." else ""
    }

    override fun openApp(app: String): String {
        val q = app.trim()
        val apps = launchableApps()
        val exact = apps.filter { it.label.equals(q, ignoreCase = true) || it.pkg.equals(q, ignoreCase = true) }
        val starts = apps.filter { it.label.startsWith(q, ignoreCase = true) }
        val contains = apps.filter { it.label.contains(q, ignoreCase = true) }
        val pick: AppEntry = when {
            exact.isNotEmpty() -> exact[0]
            starts.size == 1 -> starts[0]
            starts.size > 1 -> return "Several apps match '$q': " + starts.take(8).joinToString(", ") { "${it.label} (${it.pkg})" } + ". Be more specific or use the package id."
            contains.size == 1 -> contains[0]
            contains.size > 1 -> return "Several apps match '$q': " + contains.take(8).joinToString(", ") { "${it.label} (${it.pkg})" } + ". Be more specific or use the package id."
            else -> return "No installed app matches '$q'. Use list_apps to see what is installed."
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(pick.pkg) ?: return "${pick.label} has no launchable screen."
        return startSafely(launch) ?: "Launched ${pick.label} (${pick.pkg}). If it did not come to the front, enable 'Display over other apps' for Pixel Agent in Settings."
    }

    override fun openUrl(url: String): String {
        val u = url.trim()
        if (u.startsWith("intent:", ignoreCase = true) || u.startsWith("javascript:", ignoreCase = true)) return "That URI scheme is not allowed."
        val withScheme = if (u.contains(":")) u else "https://$u"
        return startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(withScheme))) ?: "Opened $withScheme"
    }

    override fun setAlarm(hour: Int, minute: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return startSafely(i) ?: "Alarm set for %02d:%02d%s".format(hour, minute, if (label.isNotEmpty()) " ($label)" else "")
    }

    override fun setTimer(seconds: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return startSafely(i) ?: "Timer started for $seconds s"
    }

    override fun clipboardGet(): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = try { cm.primaryClip } catch (e: Exception) { null }
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        return if (text == null) "The clipboard is empty or not readable (Android only lets the app in the foreground read it)." else text
    }

    override fun clipboardSet(text: String): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Pixel Agent", text))
        return "Copied ${text.length} characters to the clipboard."
    }

    @SuppressLint("MissingPermission")
    override fun notify(title: String, text: String): String {
        if (!Perms.notificationsGranted(ctx)) return "Notification permission is not granted. Ask the user to allow notifications for Pixel Agent."
        Notif.ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, Notif.CHANNEL_MSG)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt() + 2000, n)
        return "Notification shown."
    }

    override fun imageInfo(path: String): String {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return "not a readable image"
        return "${o.outWidth}x${o.outHeight} px, ${o.outMimeType ?: "unknown type"}"
    }

    override fun encodeImage(path: String): EncodedImage? {
        val f = File(path)
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxDim = 1568
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        val scaled = if (maxOf(bmp.width, bmp.height) > maxDim) {
            val r = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt().coerceAtLeast(1), (bmp.height * r).toInt().coerceAtLeast(1), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return EncodedImage("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    override fun screen(): ScreenController? = PixelAccessibilityService.instance?.controller
}

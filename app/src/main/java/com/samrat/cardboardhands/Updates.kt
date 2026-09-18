package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * PhoneXR updates from GitHub Releases: in the app it is "update the app", in the headset it is
 * shown like a firmware update. Only the version and size are shown, no release notes.
 */
object Updates {
    private const val REPO = "samrat1games/phonexr"
    private const val PREFS = "updates"

    data class Release(val version: String, val url: String, val size: Long)

    fun autoUpdate(context: Context) = prefs(context).getBoolean("auto", true)
    fun setAutoUpdate(context: Context, value: Boolean) = prefs(context).edit().putBoolean("auto", value).apply()
    fun beta(context: Context) = prefs(context).getBoolean("beta", false)
    fun setBeta(context: Context, value: Boolean) = prefs(context).edit().putBoolean("beta", value).apply()

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Network call: the newest release with a PhoneXR APK, if it is newer than this app. */
    fun check(context: Context): Release? {
        val releases = if (beta(context)) {
            JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=10"))
                .let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        } else {
            listOf(JSONObject(get("https://api.github.com/repos/$REPO/releases/latest")))
        }
        val current = currentVersion(context)
        for (release in releases) {
            if (release.optBoolean("draft")) continue
            val version = release.getString("tag_name").removePrefix("v")
            val assets = release.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").startsWith("PhoneXR") && it.getString("name").endsWith(".apk") } ?: continue
            return if (newer(version, current)) Release(version, apk.getString("browser_download_url"), apk.getLong("size")) else null
        }
        return null
    }

    /** Downloads the update into the shared cache, reporting progress 0..1. */
    fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        val file = File(File(context.cacheDir, "patched").apply { mkdirs() }, "PhoneXR-${release.version}.apk")
        var address = URL(release.url)
        var connection: HttpURLConnection
        while (true) {
            connection = address.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            if (connection.responseCode in 300..399) {
                address = URL(address, connection.getHeaderField("Location"))
                connection.disconnect()
            } else break
        }
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    onProgress(if (total > 0) done.toFloat() / total else -1f)
                }
            }
        }
        connection.disconnect()
        return file
    }

    fun install(activity: Activity, file: File) {
        val content = FileProvider.getUriForFile(activity, "${activity.packageName}.patched.apks", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(content, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun formatSize(bytes: Long) = if (bytes >= 1L shl 30) "%.2f ГБ".format(bytes / (1L shl 30).toDouble())
    else "%.0f МБ".format(bytes / (1L shl 20).toDouble())

    /** 1.0.10 is newer than 1.0.9. */
    fun newer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').mapNotNull { it.toIntOrNull() }
        val b = current.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun get(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("unused")
    private fun open(context: Context, url: String) =
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

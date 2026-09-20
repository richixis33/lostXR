package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Web apps (PWA) from the LostXR store and the ones the user added. They open in the LostXR
 * browser, which runs in VR.
 *
 * Store format: a file "pwa.json" in the store folder, e.g.
 *   [{"name": "YouTube", "url": "https://m.youtube.com", "icon": "https://.../icon.png"}]
 */
object WebApps {
    data class App(val name: String, val url: String, val icon: String?) {
        fun toJson(): JSONObject = JSONObject().put("name", name).put("url", url).put("icon", icon ?: "")
    }

    /** The LostXR browser build, then stock Wolvic it is built from. */
    private val browsers = listOf("com.samrat.pxrbrowser", "com.igalia.wolvic")
    private const val PREFS = "web_apps"
    private const val KEY = "installed"

    fun browserPackage(context: Context): String? = browsers.firstOrNull { name ->
        runCatching { context.packageManager.getApplicationInfo(name, 0) }.isSuccess
    }

    fun open(context: Context, url: String): Boolean {
        val browser = browserPackage(context) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(browser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openBrowser(context: Context): Boolean {
        val browser = browserPackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(browser) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    fun installed(context: Context): List<App> = parse(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
    )

    fun add(context: Context, app: App) {
        val list = installed(context).filterNot { it.url == app.url } + app
        save(context, list)
    }

    fun remove(context: Context, app: App) = save(context, installed(context).filterNot { it.url == app.url })

    private fun save(context: Context, list: List<App>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
            .apply()
    }

    /** Network call: the store's web apps. */
    fun fromStore(): List<App> = runCatching { parse(GameStore.readText("pwa.json")) }.getOrDefault(emptyList())

    /** Network call: an app icon, cached in memory for the session. */
    fun icon(app: App): Bitmap? {
        val source = app.icon?.takeIf { it.isNotBlank() } ?: return null
        cache[source]?.let { return it }
        return runCatching {
            val connection = URL(source).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.inputStream.use { BitmapFactory.decodeStream(it) }.also { connection.disconnect() }
        }.getOrNull()?.also { cache[source] = it }
    }

    private val cache = HashMap<String, Bitmap>()

    private fun parse(text: String): List<App> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            App(item.optString("name", url), url, item.optString("icon").takeIf { it.isNotBlank() })
        }
    }
}

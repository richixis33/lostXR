package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The PhoneXR app store: games are files in Supabase Storage, folder "vr_games".
 *
 * Layout the store understands:
 *   vr_games/Game.apk                     a game as a single file (.apk or .pxr)
 *   vr_games/Game Name/game.apk           a game in its own folder, which may also hold
 *   vr_games/Game Name/icon.png           an icon (icon.png / icon.jpg / icon.webp)
 *   vr_games/Game Name/description.txt    and a description shown in the store
 */
object GameStore {
    const val URL_BASE = "https://fjiostsfwfennbovpolc.supabase.co"
    /** Publishable key: made to ship inside apps, it only allows what storage policies permit. */
    private const val KEY = "sb_publishable_0we8-Uw_XxKmUINy6KOjDA_lySgpLiA"
    const val FOLDER = "vr_games"

    /** Where "vr_games" may live: its own bucket, or a folder inside a common one. */
    private val locations = listOf(
        Location("vr_games", ""),
        Location("files", "vr_games/"),
        Location("public", "vr_games/"),
        Location("storage", "vr_games/"),
    )

    private data class Location(val bucket: String, val prefix: String)

    data class Item(
        val title: String,
        /** Path inside the bucket. */
        val path: String,
        val bucket: String,
        val size: Long,
        val iconPath: String?,
        val descriptionPath: String?,
    ) {
        val extension get() = path.substringAfterLast('.', "apk").lowercase()
    }

    class StoreException(message: String) : Exception(message)

    private val installable = setOf("apk", "pxr")
    private val iconNames = setOf("icon.png", "icon.jpg", "icon.jpeg", "icon.webp")
    @Volatile private var found: Location? = null

    /** Network call, run off the main thread. */
    fun list(): List<Item> {
        val location = found ?: locate()
        val items = mutableListOf<Item>()
        for (entry in listFolder(location.bucket, location.prefix)) {
            val name = entry.getString("name")
            if (entry.isNull("id")) {
                // A folder: one game with its files.
                val folder = location.prefix + name + "/"
                val files = listFolder(location.bucket, folder)
                val game = files.firstOrNull { it.fileExtension() in installable } ?: continue
                val names = files.map { it.getString("name") }
                items += Item(
                    title = name,
                    path = folder + game.getString("name"),
                    bucket = location.bucket,
                    size = game.size(),
                    iconPath = names.firstOrNull { it.lowercase() in iconNames }?.let { folder + it },
                    descriptionPath = names.firstOrNull { it.equals("description.txt", true) }?.let { folder + it }
                )
            } else if (entry.fileExtension() in installable && name != "pwa.json") {
                items += Item(
                    title = name.substringBeforeLast('.').replace('_', ' '),
                    path = location.prefix + name,
                    bucket = location.bucket,
                    size = entry.size(),
                    iconPath = null,
                    descriptionPath = null
                )
            }
        }
        return items.sortedBy { it.title.lowercase() }
    }

    /** Network call: a text file from the store folder, such as pwa.json. */
    fun readText(name: String): String {
        val location = found ?: locate()
        return open(location.bucket, location.prefix + name).use { it.inputStream.readBytes().toString(Charsets.UTF_8) }
    }

    fun description(item: Item): String? = item.descriptionPath?.let { path ->
        runCatching { open(item.bucket, path).use { it.inputStream.readBytes().toString(Charsets.UTF_8).trim() } }.getOrNull()
    }

    fun icon(item: Item): Bitmap? = item.iconPath?.let { path ->
        runCatching { open(item.bucket, path).use { BitmapFactory.decodeStream(it.inputStream) } }.getOrNull()
    }

    /** Downloads [item] into [directory], reporting progress 0..1 (or -1 when the size is unknown). */
    fun download(item: Item, directory: File, onProgress: (Float) -> Unit): File {
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, item.title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_") + "." + item.extension)
        open(item.bucket, item.path).use { response ->
            val total = response.connection.contentLengthLong.takeIf { it > 0 } ?: item.size
            response.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            onProgress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        }
        onProgress(1f)
        return target
    }

    /**
     * Supabase answers a listing it may not show with an empty list, not an error, so the first
     * location with files wins. With none, the store stays on the "vr_games" bucket and shows it empty.
     */
    private fun locate(): Location {
        var reachable = false
        for (location in locations) {
            val (code, body) = request("POST", "/storage/v1/object/list/${location.bucket}", listBody(location.prefix))
            if (code != 200) continue
            reachable = true
            if (JSONArray(body).length() > 0) return location.also { found = it }
        }
        if (!reachable) throw StoreException("Сервер магазина недоступен. Проверьте интернет.")
        return locations.first()
    }

    /** Shown when the store is empty: either there are no games yet, or reading is not allowed. */
    const val EMPTY_HINT = "Файлы не найдены. Положите игры в bucket «$FOLDER» Supabase и разрешите всем " +
        "чтение в Storage → Policies (SELECT для anon)."

    private fun listFolder(bucket: String, prefix: String): List<JSONObject> {
        val (code, body) = request("POST", "/storage/v1/object/list/$bucket", listBody(prefix))
        if (code != 200) throw StoreException("Магазин недоступен (код $code): ${errorText(body)}")
        val array = JSONArray(body)
        return (0 until array.length()).map { array.getJSONObject(it) }
            .filterNot { it.getString("name") == ".emptyFolderPlaceholder" }
    }

    private fun listBody(prefix: String) = JSONObject()
        .put("prefix", prefix)
        .put("limit", 1000)
        .put("offset", 0)
        .put("sortBy", JSONObject().put("column", "name").put("order", "asc"))
        .toString()

    private fun JSONObject.fileExtension() = getString("name").substringAfterLast('.', "").lowercase()

    private fun JSONObject.size() = optJSONObject("metadata")?.optLong("size", -1L) ?: -1L

    private class Response(val connection: HttpURLConnection) : AutoCloseable {
        val inputStream get() = connection.inputStream
        override fun close() = connection.disconnect()
    }

    /** Public buckets serve files without a policy; private ones need the key and a read policy. */
    private fun open(bucket: String, path: String): Response {
        val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        for (endpoint in listOf("public/$bucket/$encoded", "authenticated/$bucket/$encoded")) {
            val connection = connect("GET", "/storage/v1/object/$endpoint")
            if (connection.responseCode == 200) return Response(connection)
            connection.disconnect()
        }
        throw FileNotFoundException("Файл «$path» не скачивается из Supabase")
    }

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val connection = connect(method, path, body)
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(method: String, path: String, body: String? = null): HttpURLConnection {
        val connection = URL(URL_BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("apikey", KEY)
        connection.setRequestProperty("Authorization", "Bearer $KEY")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return connection
    }

    private fun errorText(body: String) = runCatching { JSONObject(body).optString("message", body) }.getOrDefault(body)
}

package com.samrat.cardboardhands

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Friends by username, stored in Supabase (tables from supabase/phonexr.sql): a profile with a
 * unique username per account, and one row per "I added this person".
 */
object Friends {
    data class Person(val id: String, val username: String, val name: String)

    class NotReady : Exception("Друзья ещё не включены на сервере: выполните supabase/phonexr.sql в Supabase")

    /** A username is 3–20 of a–z, 0–9, "_" and ".". */
    fun validUsername(value: String) = Regex("^[a-z0-9_.]{3,20}$").matches(value)

    /** Network call: the signed-in user's profile, or null when they have no username yet. */
    fun myProfile(context: Context): Person? {
        val me = Account.current(context) ?: return null
        val rows = get(context, "phonexr_profiles?select=id,username,display_name&id=eq.${me.id}")
        return rows.optJSONObject(0)?.let(::person)
    }

    /** Network call: claims [username] for the signed-in user. Returns an error text or null. */
    fun setUsername(context: Context, username: String): String? {
        val me = Account.current(context) ?: return tr("Войдите в аккаунт")
        if (!validUsername(username)) return tr("Юзернейм: 3–20 символов, a–z, 0–9, _ и .")
        val body = JSONArray().put(JSONObject().put("id", me.id).put("username", username).put("display_name", me.name))
        val (code, answer) = request(context, "POST", "phonexr_profiles", body.toString(), "resolution=merge-duplicates")
        return when {
            code in 200..299 -> null
            answer.contains("PGRST205") -> NotReady().message
            answer.contains("23505") -> tr("Этот юзернейм уже занят")
            else -> answer.take(200)
        }
    }

    /** Network call: people whose username starts with [query]. */
    fun search(context: Context, query: String): List<Person> {
        val clean = query.trim().lowercase().removePrefix("@")
        if (clean.length < 2) return emptyList()
        val me = Account.current(context)?.id
        return list(get(context, "phonexr_profiles?select=id,username,display_name&username=ilike.${enc(clean)}*&limit=20"))
            .filter { it.id != me }
    }

    /** Network call: the people I added. */
    fun mine(context: Context): List<Person> {
        val me = Account.current(context) ?: return emptyList()
        val ids = get(context, "phonexr_friends?select=friend_id&user_id=eq.${me.id}")
        return profiles(context, (0 until ids.length()).map { ids.getJSONObject(it).getString("friend_id") })
    }

    /** Network call: people who added me and whom I have not added back. */
    fun addedMe(context: Context, mine: List<Person>): List<Person> {
        val me = Account.current(context) ?: return emptyList()
        val ids = get(context, "phonexr_friends?select=user_id&friend_id=eq.${me.id}")
        val known = mine.map { it.id }.toSet()
        return profiles(context, (0 until ids.length()).map { ids.getJSONObject(it).getString("user_id") }.filter { it !in known })
    }

    fun add(context: Context, person: Person): String? {
        val me = Account.current(context) ?: return tr("Войдите в аккаунт")
        val (code, answer) = request(context, "POST", "phonexr_friends",
            JSONArray().put(JSONObject().put("user_id", me.id).put("friend_id", person.id)).toString(), "resolution=ignore-duplicates")
        return if (code in 200..299) null else answer.take(200)
    }

    fun remove(context: Context, person: Person): String? {
        val me = Account.current(context) ?: return tr("Войдите в аккаунт")
        val (code, answer) = request(context, "DELETE", "phonexr_friends?user_id=eq.${me.id}&friend_id=eq.${person.id}", null, null)
        return if (code in 200..299) null else answer.take(200)
    }

    private fun profiles(context: Context, ids: List<String>): List<Person> {
        if (ids.isEmpty()) return emptyList()
        return list(get(context, "phonexr_profiles?select=id,username,display_name&id=in.(${ids.joinToString(",")})"))
            .sortedBy { it.username }
    }

    private fun person(row: JSONObject) = Person(row.getString("id"), row.getString("username"), row.optString("display_name"))

    private fun list(rows: JSONArray) = (0 until rows.length()).map { person(rows.getJSONObject(it)) }

    private fun get(context: Context, path: String): JSONArray {
        val (code, answer) = request(context, "GET", path, null, null)
        if (answer.contains("PGRST205")) throw NotReady()
        if (code !in 200..299) error(answer.take(200))
        return JSONArray(answer)
    }

    private fun request(context: Context, method: String, path: String, body: String?, prefer: String?): Pair<Int, String> {
        val token = Account.token(context) ?: return 401 to tr("Войдите в аккаунт")
        val connection = URL("${GameStore.URL_BASE}/rest/v1/$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("apikey", Account.KEY)
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        prefer?.let { connection.setRequestProperty("Prefer", it) }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        connection.disconnect()
        return code to text
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

package com.samrat.cardboardhands

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * LostXR accounts on Supabase Auth (e-mail and password). The user name lives in the account's
 * metadata, so no database table is needed; calls use the account id as the address.
 */
object Account {
    data class User(val id: String, val email: String, val name: String, val accessToken: String, val refreshToken: String, val expiresAt: Long)

    private const val PREFS = "account"

    fun current(context: Context): User? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("id", null) ?: return null
        return User(
            id, prefs.getString("email", "")!!, prefs.getString("name", "")!!,
            prefs.getString("access", "")!!, prefs.getString("refresh", "")!!, prefs.getLong("expires", 0L)
        )
    }

    /** Network call: creates the account and signs in. Returns an error text or null. */
    fun signUp(context: Context, email: String, password: String, name: String): String? = runCatching {
        val body = JSONObject().put("email", email).put("password", password)
            .put("data", JSONObject().put("name", name))
        val (code, answer) = post("/auth/v1/signup", body, null)
        if (code !in 200..299) return error(answer)
        val json = JSONObject(answer)
        if (!json.has("access_token")) return "Аккаунт создан. Подтвердите почту и войдите."
        save(context, json)
        Settings.setUserName(context, name)
        null
    }.getOrElse { "Нет связи с сервером" }

    /** Network call: signs in. Returns an error text or null. */
    fun signIn(context: Context, email: String, password: String): String? = runCatching {
        val (code, answer) = post("/auth/v1/token?grant_type=password", JSONObject().put("email", email).put("password", password), null)
        if (code !in 200..299) return error(answer)
        save(context, JSONObject(answer))
        current(context)?.name?.takeIf { it.isNotBlank() }?.let { Settings.setUserName(context, it) }
        null
    }.getOrElse { "Нет связи с сервером" }

    fun signOut(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    /** A fresh access token (refreshed when it is about to expire), or null when signed out. */
    fun token(context: Context): String? {
        val user = current(context) ?: return null
        if (System.currentTimeMillis() / 1000 < user.expiresAt - 60) return user.accessToken
        return runCatching {
            val (code, answer) = post("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", user.refreshToken), null)
            if (code !in 200..299) return null
            save(context, JSONObject(answer))
            current(context)?.accessToken
        }.getOrNull()
    }

    private fun save(context: Context, session: JSONObject) {
        val user = session.getJSONObject("user")
        val name = user.optJSONObject("user_metadata")?.optString("name").orEmpty()
            .ifBlank { user.optString("email").substringBefore('@') }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("id", user.getString("id"))
            .putString("email", user.optString("email"))
            .putString("name", name)
            .putString("access", session.getString("access_token"))
            .putString("refresh", session.optString("refresh_token"))
            .putLong("expires", session.optLong("expires_at", System.currentTimeMillis() / 1000 + session.optLong("expires_in", 3600)))
            .apply()
    }

    private fun error(answer: String): String {
        val json = runCatching { JSONObject(answer) }.getOrNull()
        val message = json?.optString("msg")?.ifBlank { null } ?: json?.optString("error_description")?.ifBlank { null }
            ?: json?.optString("message")?.ifBlank { null } ?: answer
        return when {
            message.contains("Invalid login", true) -> "Неверная почта или пароль"
            message.contains("already registered", true) -> "Такая почта уже зарегистрирована"
            message.contains("Password should", true) -> "Пароль слишком короткий (минимум 6 символов)"
            message.contains("valid email", true) || message.contains("invalid format", true) -> "Неверная почта"
            else -> message
        }
    }

    private fun post(path: String, body: JSONObject, token: String?): Pair<Int, String> {
        val connection = URL(GameStore.URL_BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("apikey", KEY)
        connection.setRequestProperty("Authorization", "Bearer ${token ?: KEY}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        connection.disconnect()
        return code to text
    }

    /** The project's publishable key (made to ship in apps). */
    const val KEY = "sb_publishable_0we8-Uw_XxKmUINy6KOjDA_lySgpLiA"
}

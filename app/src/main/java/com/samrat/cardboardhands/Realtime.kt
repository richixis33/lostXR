package com.samrat.cardboardhands

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small Supabase Realtime client (Phoenix channels over a WebSocket): broadcast messages and
 * presence, which is all the calls need — no database tables.
 */
class Realtime(private val token: String, private val listener: Listener) {
    interface Listener {
        fun onConnected() = Unit
        fun onBroadcast(topic: String, event: String, payload: JSONObject)
        /** Everyone present on [topic], by presence key, with what they track. */
        fun onPresence(topic: String, members: Map<String, JSONObject>) = Unit
        fun onClosed() = Unit
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private val ref = AtomicInteger(1)
    private val presence = HashMap<String, HashMap<String, JSONObject>>()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile var connected = false
        private set

    fun connect() {
        val host = GameStore.URL_BASE.removePrefix("https://")
        val request = Request.Builder().url("wss://$host/realtime/v1/websocket?apikey=${Account.KEY}&vsn=1.0.0").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Realtime failed", t)
                connected = false
                listener.onClosed()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                listener.onClosed()
            }
        })
        timer.scheduleAtFixedRate({ send("phoenix", "heartbeat", JSONObject()) }, 25, 25, TimeUnit.SECONDS)
    }

    fun close() {
        timer.shutdownNow()
        socket?.close(1000, null)
        socket = null
        connected = false
    }

    fun join(topic: String, presenceKey: String = "") {
        val config = JSONObject()
            .put("broadcast", JSONObject().put("self", false).put("ack", false))
            .put("presence", JSONObject().put("key", presenceKey))
            .put("private", false)
        send("realtime:$topic", "phx_join", JSONObject().put("config", config).put("access_token", token))
    }

    fun leave(topic: String) {
        send("realtime:$topic", "phx_leave", JSONObject())
        presence.remove(topic)
    }

    fun broadcast(topic: String, event: String, payload: JSONObject) =
        send("realtime:$topic", "broadcast", JSONObject().put("type", "broadcast").put("event", event).put("payload", payload))

    fun track(topic: String, state: JSONObject) =
        send("realtime:$topic", "presence", JSONObject().put("type", "presence").put("event", "track").put("payload", state))

    private fun send(topic: String, event: String, payload: JSONObject) {
        val message = JSONObject().put("topic", topic).put("event", event).put("payload", payload).put("ref", ref.getAndIncrement().toString())
        socket?.send(message.toString())
    }

    private fun handle(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val topic = message.optString("topic").removePrefix("realtime:")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        when (message.optString("event")) {
            "broadcast" -> listener.onBroadcast(topic, payload.optString("event"), payload.optJSONObject("payload") ?: JSONObject())
            "presence_state" -> {
                val members = HashMap<String, JSONObject>()
                payload.keys().forEach { key -> meta(payload.optJSONObject(key))?.let { members[key] = it } }
                presence[topic] = members
                listener.onPresence(topic, HashMap(members))
            }
            "presence_diff" -> {
                val members = presence.getOrPut(topic) { HashMap() }
                payload.optJSONObject("leaves")?.let { leaves -> leaves.keys().forEach { members.remove(it) } }
                payload.optJSONObject("joins")?.let { joins -> joins.keys().forEach { key -> meta(joins.optJSONObject(key))?.let { members[key] = it } } }
                listener.onPresence(topic, HashMap(members))
            }
        }
    }

    private fun meta(entry: JSONObject?): JSONObject? = entry?.optJSONArray("metas")?.optJSONObject(0)

    private companion object {
        const val TAG = "PhoneXR-Realtime"
    }
}

/** IMA ADPCM: 16-bit voice at a quarter of the size, simple enough to stream in realtime messages. */
class Adpcm {
    private var predicted = 0
    private var index = 0

    fun encode(samples: ShortArray, count: Int): ByteArray {
        val out = ByteArray((count + 1) / 2)
        for (i in 0 until count) {
            val nibble = encodeSample(samples[i].toInt())
            if (i % 2 == 0) out[i / 2] = nibble.toByte() else out[i / 2] = (out[i / 2].toInt() or (nibble shl 4)).toByte()
        }
        return out
    }

    fun decode(data: ByteArray): ShortArray {
        val out = ShortArray(data.size * 2)
        for (i in data.indices) {
            val byte = data[i].toInt()
            out[i * 2] = decodeSample(byte and 0x0f).toShort()
            out[i * 2 + 1] = decodeSample((byte shr 4) and 0x0f).toShort()
        }
        return out
    }

    private fun encodeSample(sample: Int): Int {
        val step = STEPS[index]
        var diff = sample - predicted
        var nibble = 0
        if (diff < 0) { nibble = 8; diff = -diff }
        var delta = step shr 3
        if (diff >= step) { nibble = nibble or 4; diff -= step; delta += step }
        if (diff >= step shr 1) { nibble = nibble or 2; diff -= step shr 1; delta += step shr 1 }
        if (diff >= step shr 2) { nibble = nibble or 1; delta += step shr 2 }
        predicted = (if (nibble and 8 != 0) predicted - delta else predicted + delta).coerceIn(-32768, 32767)
        index = (index + INDEX[nibble and 7]).coerceIn(0, 88)
        return nibble
    }

    private fun decodeSample(nibble: Int): Int {
        val step = STEPS[index]
        var delta = step shr 3
        if (nibble and 4 != 0) delta += step
        if (nibble and 2 != 0) delta += step shr 1
        if (nibble and 1 != 0) delta += step shr 2
        predicted = (if (nibble and 8 != 0) predicted - delta else predicted + delta).coerceIn(-32768, 32767)
        index = (index + INDEX[nibble and 7]).coerceIn(0, 88)
        return predicted
    }

    private companion object {
        val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
        val STEPS = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
            107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
            5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385,
            24623, 27086, 29794, 32767,
        )
    }
}

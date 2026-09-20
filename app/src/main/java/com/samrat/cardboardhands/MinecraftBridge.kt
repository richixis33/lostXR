package com.samrat.cardboardhands

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The link to the LostXR VR mod inside Minecraft Bedrock. Minecraft connects to this tiny
 * WebSocket server with "/connect localhost:19144" (Bedrock's own feature for companion apps);
 * LostXR then sends the head and hands as "/scriptevent phonexr:pose …", which the mod's script
 * turns into the view, hands in the world, breaking, placing and walking.
 */
object MinecraftBridge {
    const val PORT = 19144
    const val CONNECT = "/connect localhost:$PORT"
    private const val TAG = "LostXR-MCBridge"
    private const val ASSET = "minecraft/LostXR-VR.mcaddon"
    private const val PREFS = "minecraft_mod"

    @Volatile var connected = false
        private set
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var output: OutputStream? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val socket = runCatching { ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")) }
            .onFailure { Log.w(TAG, "Port busy", it) }.getOrNull() ?: return
        server = socket
        thread(name = "LostXR Minecraft bridge") {
            while (!socket.isClosed) {
                val peer = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { serve(peer) }.onFailure { Log.w(TAG, "Minecraft link ended", it) }
                connected = false
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { client?.close() }
        runCatching { server?.close() }
        server = null
        client = null
        output = null
        connected = false
    }

    /** Head yaw/pitch (degrees; yaw grows to the left, pitch up) and up to two hands. */
    fun sendPose(yaw: Float, pitch: Float, hands: Array<FloatArray?>) {
        if (!connected) return
        val h = JSONArray()
        for (hand in hands) {
            h.put(if (hand == null) JSONArray().put(0) else JSONArray().put(1)
                .put(round(hand[0])).put(round(hand[1])).put(round(hand[2])).put(hand[3].toInt()))
        }
        val pose = JSONObject().put("y", round(yaw)).put("p", round(pitch)).put("h", h)
        command("scriptevent phonexr:pose $pose")
    }

    private fun round(value: Float) = Math.round(value * 1000) / 1000.0

    private fun command(line: String) {
        val message = JSONObject()
            .put("header", JSONObject().put("version", 1).put("requestId", UUID.randomUUID().toString())
                .put("messagePurpose", "commandRequest").put("messageType", "commandRequest"))
            .put("body", JSONObject().put("version", 1).put("commandLine", line)
                .put("origin", JSONObject().put("type", "player")))
        send(message.toString())
    }

    /** WebSocket handshake, then read (and drop) Minecraft's replies until it disconnects. */
    private fun serve(peer: Socket) {
        val input = peer.getInputStream()
        val request = StringBuilder()
        while (!request.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            request.append(b.toChar())
        }
        val key = request.lines().firstOrNull { it.startsWith("Sec-WebSocket-Key:", true) }?.substringAfter(':')?.trim() ?: return
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()), Base64.NO_WRAP
        )
        val out = peer.getOutputStream()
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        synchronized(this) { client = peer; output = out }
        connected = true
        Log.i(TAG, "Minecraft connected")
        val buffer = ByteArray(8192)
        while (input.read(buffer) >= 0) Unit
        synchronized(this) { client = null; output = null }
    }

    /** One text frame, server to client (unmasked). */
    @Synchronized
    private fun send(text: String) {
        val out = output ?: return
        val payload = text.toByteArray()
        val header = when {
            payload.size < 126 -> byteArrayOf(0x81.toByte(), payload.size.toByte())
            payload.size < 65536 -> byteArrayOf(0x81.toByte(), 126, (payload.size shr 8).toByte(), payload.size.toByte())
            else -> ByteArray(10).also { h ->
                h[0] = 0x81.toByte(); h[1] = 127
                for (i in 0 until 8) h[9 - i] = (payload.size.toLong() shr (8 * i)).toByte()
            }
        }
        runCatching { out.write(header); out.write(payload); out.flush() }.onFailure { connected = false }
    }

    // ---------------------------------------------------------------- The mod itself

    fun modInstalled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("installed", false)

    /** Hands the bundled mod to Minecraft, which imports it. Returns an error text or null. */
    fun installMod(activity: android.app.Activity): String? {
        val file = File(MinecraftMods.folder(activity), "LostXR-VR.mcaddon")
        activity.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        val problem = MinecraftMods.install(activity, file)
        if (problem == null) activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("installed", true).apply()
        return problem
    }
}

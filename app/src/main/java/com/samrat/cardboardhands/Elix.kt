package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

/**
 * Elix, the PhoneXR assistant (a Claude model behind a Supabase Edge Function). Like Siri it
 * listens — ask out loud or type on the VR keyboard — but it answers in text, it does not speak.
 */
object Elix {
    data class Message(val fromUser: Boolean, val text: String)

    /** Network call: Elix's answer to the conversation so far, or an error text. */
    fun ask(context: Context, history: List<Message>): Result<String> = runCatching {
        val token = Account.token(context) ?: error(tr("Войдите в аккаунт PhoneXR, чтобы говорить с Elix"))
        val messages = JSONArray()
        history.takeLast(20).forEach { messages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
        val connection = URL("${GameStore.URL_BASE}/functions/v1/elix").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.doOutput = true
        connection.setRequestProperty("apikey", Account.KEY)
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(JSONObject().put("messages", messages).put("language", L10n.current.code).toString().toByteArray()) }
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        connection.disconnect()
        when {
            code == 404 -> error(tr("Elix ещё не включён на сервере (supabase functions deploy elix)"))
            code !in 200..299 -> error(runCatching { JSONObject(body).optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP $code")
            else -> JSONObject(body).optString("text").trim()
        }
    }
}

/** The Elix window: a glowing orb, the conversation, and buttons to speak or type. */
class ElixContent(private val context: Context) : VrWindow.Content {
    override val pixelWidth = 1400
    override val pixelHeight = 1000
    override val external = false
    private val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var fresh = true
    @Volatile private var running = true
    private val buttons = ArrayList<Pair<RectF, () -> Unit>>()
    private val history = ArrayList<Elix.Message>()
    private var input = ""
    @Volatile private var typing = false
    @Volatile private var listening = false
    @Volatile private var thinking = false
    private var level = 0f
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val start = System.nanoTime()

    override val keyboardRequested get() = typing

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        thread(name = "PhoneXR Elix") {
            while (running) {
                draw()
                Thread.sleep(if (listening || thinking) 40 else 120)
            }
        }
        onReady()
    }

    override fun takeBitmap(): Bitmap? = if (fresh) synchronized(this) { fresh = false; bitmap } else null

    override fun toolbarTitle() = "Elix"

    override fun touch(action: Int, u: Float, v: Float) {
        if (action != MotionEvent.ACTION_UP) return
        val x = u * pixelWidth; val y = v * pixelHeight
        val hit = synchronized(this) { buttons.firstOrNull { it.first.contains(x, y) }?.second } ?: return
        hit()
    }

    override fun type(key: String) {
        when (key) {
            "backspace" -> input = input.dropLast(1)
            "enter" -> send()
            else -> if (input.length < 500) input += key
        }
    }

    override fun hideKeyboard() {
        typing = false
    }

    override fun release() {
        running = false
        main.post { recognizer?.destroy(); recognizer = null }
    }

    private fun send() {
        val question = input.trim()
        if (question.isEmpty() || thinking) return
        input = ""
        typing = false
        history += Elix.Message(true, question)
        thinking = true
        thread {
            val answer = Elix.ask(context, history)
            history += Elix.Message(false, answer.getOrElse { it.message ?: "Ошибка" })
            thinking = false
        }
    }

    /** Android speech recognition in the chosen language; the words become the question. */
    private fun listen() = main.post {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            history += Elix.Message(false, tr("Распознавание речи недоступно — используйте клавиатуру"))
            return@post
        }
        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false; level = 0f }
            override fun onResults(results: Bundle?) {
                listening = false
                level = 0f
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input = it; send() }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, L10n.current.speech)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
        listening = true
    }

    @Synchronized
    private fun draw() {
        buttons.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        paint.color = Color.argb(220, 16, 16, 22)
        canvas.drawRoundRect(RectF(0f, 0f, pixelWidth.toFloat(), pixelHeight.toFloat()), 60f, 60f, paint)
        orb()
        // The conversation, newest at the bottom.
        var y = 820f
        for (message in history.reversed()) {
            val lines = wrap(message.text, 900f, 34f)
            val height = lines.size * 44f + 36f
            if (y - height < 250f) break
            y -= height
            val left = if (message.fromUser) pixelWidth - 80f - bubbleWidth(lines) else 80f
            paint.color = if (message.fromUser) Color.rgb(10, 132, 255) else Color.argb(70, 255, 255, 255)
            canvas.drawRoundRect(RectF(left, y, left + bubbleWidth(lines), y + height - 12f), 30f, 30f, paint)
            lines.forEachIndexed { i, line -> text(line, left + 26f, y + 46f + i * 44f, 34f, Color.WHITE) }
            y -= 8f
        }
        if (history.isEmpty() && !thinking) center(tr("Спросите Elix"), 560f, 52f)
        val status = when {
            listening -> tr("Слушаю…")
            thinking -> tr("Думаю…")
            input.isNotEmpty() -> input + if (typing) "|" else ""
            else -> null
        }
        status?.let { center(it, 880f - 20f, 34f, Color.rgb(200, 200, 210)) }
        button(RectF(240f, 900f, 580f, 975f), tr("Говорить"), Color.rgb(10, 132, 255)) { listen() }
        button(RectF(610f, 900f, 910f, 975f), tr("Клавиатура"), Color.argb(90, 255, 255, 255)) { typing = true }
        if (input.isNotEmpty()) button(RectF(940f, 900f, 1180f, 975f), tr("Отправить"), Color.rgb(48, 209, 88)) { send() }
        else button(RectF(940f, 900f, 1240f, 975f), tr("Новый разговор"), Color.argb(90, 255, 255, 255)) { history.clear() }
        fresh = true
    }

    /** Siri-like colours swirling around a white core; they swell with the voice. */
    private fun orb() {
        val t = (System.nanoTime() - start) / 1e9f
        val cx = pixelWidth / 2f; val cy = 140f
        val pulse = if (listening) .8f + level * .6f else if (thinking) 1f + .12f * sin(t * 6f) else 1f
        val colors = intArrayOf(Color.rgb(255, 64, 160), Color.rgb(120, 90, 255), Color.rgb(40, 200, 255), Color.rgb(255, 150, 60))
        colors.forEachIndexed { i, color ->
            val angle = t * (if (thinking) 2.4f else 1f) + i * Math.PI.toFloat() / 2
            val ox = cos(angle) * 26f * pulse; val oy = sin(angle) * 18f * pulse
            paint.shader = RadialGradient(cx + ox, cy + oy, 95f * pulse, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx + ox, cy + oy, 110f * pulse, paint)
        }
        paint.shader = RadialGradient(cx, cy, 45f * pulse, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 55f * pulse, paint)
        paint.shader = null
    }

    private fun bubbleWidth(lines: List<String>): Float {
        paint.textSize = 34f
        return (lines.maxOfOrNull { paint.measureText(it) } ?: 0f) + 52f
    }

    private fun wrap(value: String, width: Float, size: Float): List<String> {
        paint.textSize = size
        val lines = ArrayList<String>()
        for (paragraph in value.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(next) > width && line.isNotEmpty()) { lines += line; line = word } else line = next
            }
            lines += line
        }
        return lines
    }

    private fun button(rect: RectF, label: String, color: Int, action: () -> Unit) {
        paint.color = color
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        paint.textAlign = Paint.Align.CENTER
        text(label, rect.centerX(), rect.centerY() + 12f, 32f, Color.WHITE, bold = true)
        paint.textAlign = Paint.Align.LEFT
        buttons += rect to action
    }

    private fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
        paint.typeface = Typeface.DEFAULT
    }

    private fun center(value: String, y: Float, size: Float, color: Int = Color.WHITE) {
        paint.textAlign = Paint.Align.CENTER
        text(value, pixelWidth / 2f, y, size, color)
        paint.textAlign = Paint.Align.LEFT
    }
}

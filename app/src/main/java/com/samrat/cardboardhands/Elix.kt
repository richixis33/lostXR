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
 * Elix, the LostXR assistant (a Claude model behind a Supabase Edge Function). Like Siri it
 * listens — ask out loud or type on the VR keyboard — but it answers in text, it does not speak.
 */
object Elix {
    data class Message(val fromUser: Boolean, val text: String)

    /** Network call: Elix's answer to the conversation so far, or an error text. */
    fun ask(context: Context, history: List<Message>): Result<String> = runCatching {
        val token = Account.token(context) ?: error(tr("Войдите в аккаунт LostXR, чтобы говорить с Elix"))
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
class ElixContent(
    private val context: Context,
    /** Runs a quick command's action in the VR home (open an app, take a photo…). */
    private val onCommand: (String) -> Unit = {},
) : VrWindow.Content {
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
        thread(name = "LostXR Elix") {
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
        // Quick commands answer at once, on the headset.
        ElixCommands.answer(context, question)?.let { quick ->
            history += Elix.Message(false, quick.text)
            quick.action?.let(onCommand)
            return
        }
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
        // No panel: the sphere floats in the room, only the messages and buttons have backgrounds.
        val big = history.isEmpty()
        orb(pixelWidth / 2f, if (big) 400f else 140f, if (big) 300f else 110f)
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
        if (big && !thinking && !listening && input.isEmpty()) pill(tr("Спросите Elix"), 790f, 40f)
        val status = when {
            listening -> tr("Слушаю…")
            thinking -> tr("Думаю…")
            input.isNotEmpty() -> input + if (typing) "|" else ""
            else -> null
        }
        status?.let { pill(it, 860f, 34f) }
        button(RectF(240f, 900f, 580f, 975f), tr("Говорить"), Color.rgb(10, 132, 255)) { listen() }
        button(RectF(610f, 900f, 910f, 975f), tr("Клавиатура"), Color.argb(90, 255, 255, 255)) { typing = true }
        if (input.isNotEmpty()) button(RectF(940f, 900f, 1180f, 975f), tr("Отправить"), Color.rgb(48, 209, 88)) { send() }
        else button(RectF(940f, 900f, 1240f, 975f), tr("Новый разговор"), Color.argb(90, 255, 255, 255)) { history.clear() }
        fresh = true
    }

    /**
     * Elix as a glass sphere: grey and see-through, lit at the rim, with a rainbow wave of light
     * floating inside. The wave breathes when idle, follows the voice while listening and ripples
     * while thinking.
     */
    private fun orb(cx: Float, cy: Float, r: Float) {
        val t = (System.nanoTime() - start) / 1e9f
        // Glass body.
        paint.shader = RadialGradient(cx - r * .25f, cy - r * .3f, r * 1.25f,
            intArrayOf(Color.argb(150, 150, 152, 158), Color.argb(170, 96, 98, 106), Color.argb(215, 58, 60, 68)),
            floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        // Inner wave of rainbow light.
        val energy = when {
            listening -> .45f + level * .9f
            thinking -> .75f + .25f * sin(t * 7f)
            else -> .45f + .08f * sin(t * 1.6f)
        }
        val waveW = r * 1.25f
        val waveH = r * .16f * energy
        val path = android.graphics.Path()
        val steps = 40
        for (k in 0..steps) {
            val x = cx - waveW / 2 + waveW * k / steps
            val f = k.toFloat() / steps
            val envelope = sin(f * Math.PI.toFloat())
            val y = cy + sin(f * 6.3f + t * 2.2f) * waveH * .6f * envelope - envelope * waveH
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (k in steps downTo 0) {
            val x = cx - waveW / 2 + waveW * k / steps
            val f = k.toFloat() / steps
            val envelope = sin(f * Math.PI.toFloat())
            val y = cy + sin(f * 6.3f + t * 2.2f + .8f) * waveH * .6f * envelope + envelope * waveH * .7f
            path.lineTo(x, y)
        }
        path.close()
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(cx - waveW / 2, 0f, cx + waveW / 2, 0f,
                intArrayOf(Color.argb(0, 255, 60, 60), Color.rgb(255, 90, 70), Color.rgb(255, 205, 80), Color.rgb(120, 230, 140),
                    Color.rgb(80, 200, 255), Color.rgb(140, 110, 255), Color.argb(0, 200, 90, 255)),
                null, Shader.TileMode.CLAMP)
            maskFilter = android.graphics.BlurMaskFilter(r * .09f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            alpha = (180 + 60 * energy).toInt().coerceAtMost(255)
        }
        canvas.drawPath(path, glow)
        // The bright core of the wave.
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            maskFilter = android.graphics.BlurMaskFilter(r * .05f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            alpha = (150 * energy).toInt().coerceIn(40, 230)
        }
        canvas.drawOval(RectF(cx - waveW * .28f, cy - waveH * .35f, cx + waveW * .28f, cy + waveH * .45f), core)
        // Rim light and a soft reflection, like glass.
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * .03f
        paint.color = Color.argb(120, 255, 255, 255)
        canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2, paint)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx - r * .35f, cy - r * .55f, r * .45f, Color.argb(90, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx - r * .35f, cy - r * .55f, r * .45f, paint)
        paint.shader = null
    }

    /** Text on a dark rounded pill, readable over the room. */
    private fun pill(value: String, y: Float, size: Float) {
        paint.textSize = size
        val w = paint.measureText(value) / 2 + 36f
        paint.color = Color.argb(170, 20, 20, 26)
        canvas.drawRoundRect(RectF(pixelWidth / 2f - w, y - size - 14f, pixelWidth / 2f + w, y + 18f), 40f, 40f, paint)
        center(value, y, size)
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

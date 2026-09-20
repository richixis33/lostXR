package com.samrat.cardboardhands

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import kotlin.concurrent.thread

/**
 * The VR Settings app, visionOS style: a sidebar and a page. For now: About the headset, the
 * Persona (a face made from a photo) and the play-area boundary.
 */
class SettingsContent(
    private val context: Context,
    private val host: Host,
) : VrWindow.Content {
    interface Host {
        /** "6DoF · ARCore" or "3DoF" and whether the room is tracked right now. */
        fun trackingText(): String
        fun showPersona()
        fun startBoundary()
        fun clearBoundary()
        fun boundaryText(): String
    }

    private enum class Page(val title: String) { ABOUT(tr("О гарнитуре")), UPDATE(tr("Обновление ПО")), FACE(tr("Лицо")), BOUNDARY(tr("Граница")) }

    override val pixelWidth = 1600
    override val pixelHeight = 1000
    override val external = false
    private val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var fresh = true
    private var page = Page.ABOUT
    /** Photo grid while choosing a face photo. */
    private var picking = false
    private var photos = emptyList<Uri>()
    private val thumbs = HashMap<Uri, Bitmap>()
    private var status: String? = null
    private var busy = false
    private var preview: Bitmap? = null
    private val buttons = ArrayList<Pair<RectF, () -> Unit>>()

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        thread { draw(); onReady() }
    }

    override fun takeBitmap(): Bitmap? = if (fresh) synchronized(this) { fresh = false; bitmap } else null

    override fun toolbarTitle() = tr("Настройки")

    override fun touch(action: Int, u: Float, v: Float) {
        if (action != MotionEvent.ACTION_UP) return
        val x = u * pixelWidth
        val y = v * pixelHeight
        val hit = synchronized(this) { buttons.firstOrNull { it.first.contains(x, y) }?.second }
        if (hit != null) thread { hit(); draw() }
    }

    override fun release() = Unit

    private fun open(target: Page) {
        page = target
        picking = false
        status = null
        if (target == Page.UPDATE && !checked) checkUpdate()
    }

    // Firmware-style update: the same LostXR release the app would install.
    private var release: Updates.Release? = null
    private var checked = false
    private var checking = false
    private var downloadProgress: Float? = null

    private fun checkUpdate() {
        checking = true
        draw()
        release = runCatching { Updates.check(context) }.getOrNull()
        checked = true
        checking = false
    }

    private fun update() {
        text(tr("Обновление ПО"), 480f, 100f, 52f, Color.WHITE, bold = true)
        card(480f, 150f, 2)
        text(tr("Автообновление"), 510f, 200f, 34f, Color.WHITE)
        text(if (Updates.autoUpdate(context)) "Вкл." else "Выкл.", 1560f, 200f, 34f, Color.rgb(170, 170, 178), right = true)
        buttons += RectF(480f, 150f, 1580f, 228f) to { Updates.setAutoUpdate(context, !Updates.autoUpdate(context)) }
        text(tr("Бета‑обновления"), 510f, 278f, 34f, Color.WHITE)
        text(if (Updates.beta(context)) "Вкл." else "Выкл.", 1560f, 278f, 34f, Color.rgb(170, 170, 178), right = true)
        buttons += RectF(480f, 228f, 1580f, 306f) to { Updates.setBeta(context, !Updates.beta(context)); checkUpdate() }
        val found = release
        when {
            checking -> text("Проверка обновлений…", 480f, 420f, 36f, Color.rgb(170, 170, 178))
            found == null -> {
                text("LostXR ${Updates.currentVersion(context)}", 1030f, 440f, 44f, Color.WHITE, center = true, bold = true)
                text(if (checked) "Установлена последняя версия ПО" else "", 1030f, 500f, 34f, Color.rgb(170, 170, 178), center = true)
                button(RectF(830f, 560f, 1230f, 640f), "Проверить снова") { checkUpdate() }
            }
            else -> {
                paint.color = Color.rgb(52, 52, 58)
                canvas.drawRoundRect(RectF(480f, 340f, 1580f, 640f), 28f, 28f, paint)
                context.packageManager.getApplicationIcon(context.packageName).let {
                    it.setBounds(510, 370, 630, 490); it.draw(canvas)
                }
                text("LostXR ${found.version}", 660f, 420f, 44f, Color.WHITE, bold = true)
                text(Updates.formatSize(found.size), 660f, 470f, 32f, Color.rgb(170, 170, 178))
                val progress = downloadProgress
                button(RectF(510f, 530f, 1550f, 610f), when {
                    progress == null -> tr("Обновить сейчас")
                    progress < 0f -> tr("Загрузка…")
                    else -> "Загрузка ${(progress * 100).toInt()}%"
                }) {
                    if (downloadProgress == null) {
                        downloadProgress = 0f
                        var shown = -1
                        val file = runCatching {
                            Updates.download(context, found) { value ->
                                downloadProgress = value
                                val percent = (value * 100).toInt()
                                if (percent != shown && percent % 5 == 0) { shown = percent; draw() }
                            }
                        }.getOrNull()
                        downloadProgress = null
                        val activity = context as? android.app.Activity
                        if (file != null && activity != null) activity.runOnUiThread { Updates.install(activity, file) }
                        else status = "Обновление не скачалось"
                    }
                }
            }
        }
    }

    private fun choosePhoto() {
        picking = true
        status = null
        photos = runCatching {
            val list = ArrayList<Uri>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && list.size < 12) {
                    list += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun buildFace(photo: Uri) {
        picking = false
        busy = true
        status = "Изучаю лицо…"
        draw()
        val error = runCatching { Persona.build(context, photo) }.getOrElse { it.message ?: "Ошибка" }
        busy = false
        preview = null
        status = error ?: "Лицо готово. Скажите что-нибудь — рот будет двигаться."
    }

    @Synchronized
    private fun draw() {
        buttons.clear()
        canvas.drawColor(Color.rgb(38, 38, 42))
        // Sidebar.
        paint.color = Color.rgb(30, 30, 33)
        canvas.drawRect(0f, 0f, 420f, pixelHeight.toFloat(), paint)
        text(tr("Настройки"), 40f, 90f, 48f, Color.WHITE, bold = true)
        Page.values().forEachIndexed { index, item ->
            val rect = RectF(20f, 140f + index * 96f, 400f, 220f + index * 96f)
            if (item == page) {
                paint.color = Color.argb(60, 255, 255, 255)
                canvas.drawRoundRect(rect, 22f, 22f, paint)
            }
            text(item.title, 50f, rect.centerY() + 14f, 38f, Color.WHITE)
            buttons += rect to { open(item) }
        }
        when (page) {
            Page.ABOUT -> about()
            Page.FACE -> face()
            Page.BOUNDARY -> boundary()
            Page.UPDATE -> update()
        }
        fresh = true
    }

    private fun about() {
        text(tr("О гарнитуре"), 480f, 100f, 52f, Color.WHITE, bold = true)
        val metrics = context.resources.displayMetrics
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val runtime = when (PhoneXrRuntime.state(context)) {
            PhoneXrRuntime.State.READY -> "LostXR Runtime"
            PhoneXrRuntime.State.OUTDATED -> "LostXR Runtime (есть обновление)"
            PhoneXrRuntime.State.MISSING -> "не установлен"
        }
        val rows = listOf(
            "Устройство" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            "Android" to Build.VERSION.RELEASE,
            "LostXR" to (version ?: "—"),
            "Отслеживание" to host.trackingText(),
            "Экран" to "${metrics.widthPixels}×${metrics.heightPixels}, по ${metrics.widthPixels / 2}×${metrics.heightPixels} на глаз",
            "Поле зрения" to "90° по вертикали",
            "Межзрачковое" to "64 мм",
            "Руки" to "камера, 21 точка на руку",
            "OpenXR" to runtime,
            "Батарея" to (battery?.let { "$it %" } ?: "—"),
        )
        card(480f, 150f, rows.size)
        rows.forEachIndexed { i, (name, value) ->
            val y = 150f + i * 78f
            text(name, 510f, y + 50f, 34f, Color.WHITE)
            text(value, 1560f, y + 50f, 34f, Color.rgb(170, 170, 178), right = true)
        }
    }

    private fun face() {
        text(tr("Лицо"), 480f, 100f, 52f, Color.WHITE, bold = true)
        if (picking) {
            text("Выберите фото, где лицо видно спереди", 480f, 160f, 32f, Color.rgb(170, 170, 178))
            photos.forEachIndexed { index, uri ->
                val column = index % 4; val row = index / 4
                val rect = RectF(480f + column * 272f, 190f + row * 262f, 732f + column * 272f, 432f + row * 262f)
                val thumb = thumbs.getOrPut(uri) {
                    runCatching { context.contentResolver.loadThumbnail(uri, Size(300, 300), null) }.getOrNull()
                        ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
                canvas.save()
                canvas.clipRect(rect)
                val scale = maxOf(rect.width() / thumb.width, rect.height() / thumb.height)
                val w = thumb.width * scale; val h = thumb.height * scale
                canvas.drawBitmap(thumb, null, RectF(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2), paint)
                canvas.restore()
                buttons += rect to { buildFace(uri) }
            }
            if (photos.isEmpty()) text("Нет фото или нет доступа к галерее", 480f, 260f, 34f, Color.WHITE)
            button(RectF(480f, 900f, 780f, 970f), tr("Отмена")) { picking = false }
            return
        }
        val exists = Persona.exists(context)
        if (exists && preview == null) preview = Persona.load(context)?.image
        preview?.takeIf { exists }?.let { image ->
            val rect = RectF(480f, 150f, 900f, 570f)
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(rect, 40f, 40f, android.graphics.Path.Direction.CW) })
            canvas.drawBitmap(image, null, rect, paint)
            canvas.restore()
        }
        val info = if (exists) "Ваше лицо для VR: моргает само, а рот двигается, когда вы говорите. " +
            "Нейросеть отличает речь от случайных звуков, микрофон очищается от шума."
        else "Загрузите своё фото: LostXR найдёт на нём лицо и сделает живой портрет, " +
            "который моргает и говорит вашим голосом."
        wrap(info, if (exists) 940f else 480f, 190f, if (exists) 1560f else 1560f, 34f)
        status?.let { wrap(it, 480f, 640f, 1560f, 32f, Color.rgb(255, 180, 90)) }
        if (busy) return
        if (exists) {
            button(RectF(480f, 740f, 830f, 820f), tr("Показать лицо")) { host.showPersona() }
            button(RectF(860f, 740f, 1210f, 820f), "Другое фото") { choosePhoto() }
            button(RectF(1240f, 740f, 1560f, 820f), tr("Удалить"), Color.rgb(255, 69, 58)) {
                Persona.delete(context); preview = null; status = "Лицо удалено"
            }
        } else {
            button(RectF(480f, 740f, 880f, 820f), tr("Добавить лицо")) { choosePhoto() }
        }
    }

    private fun boundary() {
        text(tr("Граница"), 480f, 100f, 52f, Color.WHITE, bold = true)
        wrap(
            "Обойдите свободное место по краю — LostXR запомнит границу. Если подойдёте к ней, " +
                "появится стена, а если выйдете — предупреждение. Начинайте с того же места, где запускаете VR.",
            480f, 170f, 1560f, 34f
        )
        text(host.boundaryText(), 480f, 470f, 36f, Color.rgb(170, 170, 178))
        button(RectF(480f, 740f, 900f, 820f), tr("Настроить границу")) { host.startBoundary() }
        button(RectF(930f, 740f, 1300f, 820f), tr("Удалить границу"), Color.rgb(255, 69, 58)) { host.clearBoundary() }
    }

    private fun card(x: Float, y: Float, rows: Int) {
        paint.color = Color.rgb(52, 52, 58)
        canvas.drawRoundRect(RectF(x, y, 1580f, y + rows * 78f), 28f, 28f, paint)
        paint.color = Color.rgb(70, 70, 76)
        for (i in 1 until rows) canvas.drawRect(x + 30f, y + i * 78f, 1580f, y + i * 78f + 2f, paint)
    }

    private fun button(rect: RectF, label: String, color: Int = Color.rgb(10, 132, 255), action: () -> Unit) {
        paint.color = color
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        text(label, rect.centerX(), rect.centerY() + 12f, 34f, Color.WHITE, center = true, bold = true)
        buttons += rect to action
    }

    private fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, right: Boolean = false, center: Boolean = false) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = when { right -> Paint.Align.RIGHT; center -> Paint.Align.CENTER; else -> Paint.Align.LEFT }
        canvas.drawText(value, x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun wrap(value: String, x: Float, y: Float, right: Float, size: Float, color: Int = Color.WHITE) {
        paint.textSize = size
        var line = ""
        var row = 0
        for (word in value.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) > right - x) {
                text(line, x, y + row * size * 1.35f, size, color)
                row++
                line = word
            } else line = next
        }
        if (line.isNotEmpty()) text(line, x, y + row * size * 1.35f, size, color)
    }
}

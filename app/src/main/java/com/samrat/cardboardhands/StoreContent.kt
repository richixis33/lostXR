package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import java.io.File
import kotlin.concurrent.thread

/**
 * The store in the headset, laid out like the App Store: cards with an icon, a name, a line of
 * description and a pill button — VR modes, LostXR apps, games from the server and web apps.
 */
class StoreContent(private val context: Context, private val host: Host) : VrWindow.Content {
    interface Host {
        fun openCinema(packageName: String, scene: String)
        fun openWebApp(app: WebApps.App)
        fun openCalls()
        fun install(file: File)
        fun homeChanged()
        fun message(text: String)
    }

    private class Card(
        val title: String,
        val subtitle: String,
        val icon: () -> Any?,
        val button: () -> String,
        val action: () -> Unit,
    )

    private class Section(val title: String, val cards: List<Card>)

    override val pixelWidth = 1600
    override val pixelHeight = 1000
    override val external = false
    private val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = Color.rgb(170, 170, 178) }
    @Volatile private var fresh = true
    private val buttons = ArrayList<Pair<RectF, () -> Unit>>()
    private var sections = emptyList<Section>()
    private var page = 0
    private var loading = true
    private val progress = HashMap<String, Int>()
    private val icons = HashMap<String, Bitmap>()
    private var mods = emptyList<GameStore.Item>()

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        thread {
            build(emptyList(), emptyList())
            draw()
            onReady()
            val games = runCatching { GameStore.list() }.getOrDefault(emptyList())
            val web = runCatching { WebApps.fromStore() }.getOrDefault(emptyList())
            mods = runCatching { GameStore.mods() }.getOrDefault(emptyList())
            loading = false
            build(games, web)
            draw()
            for (game in games) {
                GameStore.icon(game)?.let { icons[game.path] = it; draw() }
            }
        }
    }

    override fun takeBitmap(): Bitmap? = if (fresh) synchronized(this) { fresh = false; bitmap } else null

    override fun toolbarTitle() = tr("Магазин")

    override fun touch(action: Int, u: Float, v: Float) {
        if (action != MotionEvent.ACTION_UP) return
        val x = u * pixelWidth; val y = v * pixelHeight
        val hit = synchronized(this) { buttons.firstOrNull { it.first.contains(x, y) }?.second } ?: return
        thread { hit(); draw() }
    }

    override fun release() = Unit

    private fun installed(name: String) = runCatching { context.packageManager.getApplicationInfo(name, 0) }.isSuccess

    private fun appIcon(name: String): Drawable? = runCatching { context.packageManager.getApplicationIcon(name) }.getOrNull()

    private fun build(games: List<GameStore.Item>, web: List<WebApps.App>) {
        val modes = listOf(
            Triple("Minecraft VR", "com.mojang.minecraftpe", CinemaActivity.SCENE_ROOM) to "Bedrock в гостиной с камином",
            Triple("Roblox VR", "com.roblox.client", CinemaActivity.SCENE_ROBLOX) to "Roblox в доме из Brookhaven",
            Triple("Brawl Stars VR", "com.supercell.brawlstars", CinemaActivity.SCENE_BRAWL) to "Посреди арены, 360°",
        ).map { (mode, subtitle) ->
            val (title, name, scene) = mode
            Card(title, subtitle, { appIcon(name) }, { if (installed(name)) tr("Играть") else tr("Скачать") }) {
                if (installed(name)) host.openCinema(name, scene)
                else context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$name"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val apps = listOf(
            Card(tr("Звонки"), "Общение персонами: голос, лицо и руки", { null }, { tr("Открыть") }) { host.openCalls() },
            Card(tr("Android‑приложения"), "Любые приложения телефона окнами в VR", { null },
                { if (AndroidAppsContent.enabled(context)) tr("Удалить") else tr("Получить") }) {
                AndroidAppsContent.setEnabled(context, !AndroidAppsContent.enabled(context))
                host.homeChanged()
            },
        )
        val gameCards = games.map { item ->
            Card(item.title, item.extension.uppercase() + if (item.size > 0) " · " + Updates.formatSize(item.size) else "",
                { icons[item.path] }, { progress[item.path]?.let { "$it%" } ?: tr("Загрузить") }) {
                if (progress.containsKey(item.path)) return@Card
                progress[item.path] = 0
                draw()
                val file = runCatching {
                    GameStore.download(item, File(context.cacheDir, "patched/store")) { value ->
                        val percent = (value * 100).toInt().coerceAtLeast(0)
                        if (percent != progress[item.path]) { progress[item.path] = percent; if (percent % 5 == 0) draw() }
                    }
                }.getOrNull()
                progress.remove(item.path)
                if (file != null) host.install(file) else host.message("«${item.title}» не скачалась")
            }
        }
        val modCards = mods.map { item ->
            Card(item.title, item.path.substringAfterLast('.').uppercase() + if (item.size > 0) " · " + Updates.formatSize(item.size) else "",
                { appIcon("") }, { progress[item.path]?.let { "$it%" } ?: tr("Установить") }) {
                if (progress.containsKey(item.path)) return@Card
                progress[item.path] = 0
                draw()
                val activity = context as? android.app.Activity
                val file = runCatching {
                    GameStore.download(item, File(context.cacheDir, "patched/mods")) { value ->
                        val percent = (value * 100).toInt().coerceAtLeast(0)
                        if (percent != progress[item.path]) { progress[item.path] = percent; if (percent % 5 == 0) draw() }
                    }
                }.getOrNull()
                progress.remove(item.path)
                val problem = if (file != null && activity != null) null else "«${item.title}» не скачался"
                if (problem != null) host.message(problem) else host.message(tr("Мод открыт в Minecraft: подтвердите импорт"))
            }
        }
        val webCards = web.map { app ->
            Card(app.name, app.url.removePrefix("https://").substringBefore('/'), { WebApps.icon(app) },
                { if (WebApps.installed(context).any { it.url == app.url }) tr("Открыть") else tr("Добавить") }) {
                if (WebApps.installed(context).any { it.url == app.url }) host.openWebApp(app)
                else { WebApps.add(context, app); host.homeChanged(); host.message("«${app.name}» на главном экране") }
            }
        }
        sections = listOfNotNull(
            Section(tr("VR‑режимы"), modes),
            Section(tr("Приложения LostXR"), apps),
            Section(if (loading) "Игры · загрузка…" else tr("Игры"), gameCards).takeIf { loading || gameCards.isNotEmpty() },
            
            Section(tr("Веб‑приложения"), webCards).takeIf { webCards.isNotEmpty() },
        )
    }

    @Synchronized
    private fun draw() {
        buttons.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        paint.color = Color.argb(225, 30, 30, 36)
        canvas.drawRoundRect(RectF(0f, 0f, pixelWidth.toFloat(), pixelHeight.toFloat()), 60f, 60f, paint)
        text(tr("Магазин"), 60f, 105f, 66f, Color.WHITE, bold = true)
        // Lay the sections out in a two-column grid and cut it into pages.
        val rows = ArrayList<Pair<Section?, List<Card>>>()
        for (section in sections) {
            rows += section to emptyList()
            section.cards.chunked(2).forEach { rows += null to it }
        }
        val perPage = 7
        val pages = maxOf(1, (rows.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        var y = 150f
        for ((section, cards) in rows.drop(page * perPage).take(perPage)) {
            if (section != null) {
                text(section.title, 60f, y + 50f, 40f, Color.WHITE, bold = true)
                y += 70f
                continue
            }
            cards.forEachIndexed { column, card -> card(card, 50f + column * 760f, y) }
            y += 130f
        }
        if (pages > 1) {
            text("${page + 1} / $pages", pixelWidth / 2f - 40f, pixelHeight - 30f, 32f, Color.rgb(170, 170, 178))
            pill(RectF(60f, pixelHeight - 80f, 220f, pixelHeight - 20f), "‹") { page = (page - 1).coerceAtLeast(0) }
            pill(RectF(pixelWidth - 220f, pixelHeight - 80f, pixelWidth - 60f, pixelHeight - 20f), "›") { page = (page + 1).coerceAtMost(pages - 1) }
        }
        fresh = true
    }

    private fun card(card: Card, x: Float, y: Float) {
        paint.color = Color.argb(55, 255, 255, 255)
        canvas.drawRoundRect(RectF(x, y, x + 740f, y + 115f), 32f, 32f, paint)
        val iconRect = RectF(x + 18f, y + 15f, x + 103f, y + 100f)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(iconRect, 22f, 22f, Path.Direction.CW) })
        when (val icon = card.icon()) {
            is Drawable -> { icon.setBounds(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt()); icon.draw(canvas) }
            is Bitmap -> canvas.drawBitmap(icon, null, iconRect, paint)
            else -> {
                paint.color = Color.rgb(10, 132, 255)
                canvas.drawRect(iconRect, paint)
                text(card.title.take(1), iconRect.centerX() - 16f, iconRect.centerY() + 18f, 50f, Color.WHITE, bold = true)
            }
        }
        canvas.restore()
        text(TextUtils.ellipsize(card.title, TextPaint(small).apply { textSize = 36f }, 400f, TextUtils.TruncateAt.END).toString(), x + 125f, y + 52f, 36f, Color.WHITE, bold = true)
        text(TextUtils.ellipsize(card.subtitle, small, 400f, TextUtils.TruncateAt.END).toString(), x + 125f, y + 92f, 28f, Color.rgb(170, 170, 178))
        pill(RectF(x + 545f, y + 32f, x + 720f, y + 84f), card.button(), card.action)
    }

    private fun pill(rect: RectF, label: String, action: () -> Unit) {
        paint.color = Color.argb(80, 255, 255, 255)
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        paint.textAlign = Paint.Align.CENTER
        text(label, rect.centerX(), rect.centerY() + 11f, 30f, Color.rgb(100, 180, 255), bold = true)
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
}

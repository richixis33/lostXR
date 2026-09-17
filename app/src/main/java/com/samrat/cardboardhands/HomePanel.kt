package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils

/**
 * visionOS-style home: round app icons floating in the room, a few rows per page with page dots.
 * [Mode.STORE] shows web apps from the PhoneXR store, [Mode.MENU] the system menu.
 * Everything is drawn into one transparent bitmap that the VR scene places in front of the user.
 */
class HomePanel {
    enum class Mode { HOME, STORE, MENU }

    data class Entry(val id: String, val label: String, val icon: Drawable?, val badge: String? = null)

    sealed class Target {
        data class App(val entry: Entry) : Target()
        data class Page(val index: Int) : Target()
    }

    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    var mode = Mode.HOME
    var page = 0
        private set
    private var home = emptyList<Entry>()
    private var store = emptyList<Entry>()
    private var menu = emptyList<Entry>()
    /** Minimized windows, shown as a dock under the icons. */
    private var dock = emptyList<Entry>()
    private val canvas = Canvas(bitmap)
    private val areas = ArrayList<Pair<RectF, Target>>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }
    private val heading = TextPaint(label).apply { textSize = 46f }

    fun setHome(entries: List<Entry>) { home = entries; page = page.coerceAtMost(pages(entries) - 1) }
    fun setStore(entries: List<Entry>) { store = entries }
    fun setMenu(entries: List<Entry>) { menu = entries }
    fun setDock(entries: List<Entry>) { dock = entries }
    fun homeIcons(): Map<String, Drawable?> = home.associate { it.id to it.icon }

    private fun current() = when (mode) {
        Mode.HOME -> home
        Mode.STORE -> store
        Mode.MENU -> menu
    }

    private fun pages(list: List<Entry>) = maxOf(1, (list.size + PER_PAGE - 1) / PER_PAGE)

    fun turnPage(delta: Int) {
        page = (page + delta).coerceIn(0, pages(current()) - 1)
    }

    fun showPage(index: Int) {
        page = index.coerceIn(0, pages(current()) - 1)
    }

    fun hit(u: Float, v: Float): Target? {
        val x = u * WIDTH
        val y = v * HEIGHT
        return areas.firstOrNull { it.first.contains(x, y) }?.second
    }

    fun draw(hovered: Target?, pressed: Boolean) {
        areas.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        val list = current()
        when (mode) {
            Mode.STORE -> canvas.drawText("Магазин веб‑приложений", WIDTH / 2f, 70f, heading)
            Mode.MENU -> canvas.drawText("Меню", WIDTH / 2f, 70f, heading)
            Mode.HOME -> Unit
        }
        // Honeycomb rows like visionOS: 4, 5, 4 icons.
        var index = page * PER_PAGE
        for ((row, count) in ROWS.withIndex()) {
            val y = 230f + row * 300f
            for (column in 0 until count) {
                val entry = list.getOrNull(index++) ?: break
                val x = WIDTH / 2f + (column - (count - 1) / 2f) * 330f
                val target = Target.App(entry)
                val hover = target == hovered
                val radius = if (hover) (if (pressed) 100f else 116f) else 104f
                canvas.drawCircle(x, y + 10f, radius, shadow)
                val circle = RectF(x - radius, y - radius, x + radius, y + radius)
                canvas.save()
                canvas.clipPath(Path().apply { addOval(circle, Path.Direction.CW) })
                val icon = entry.icon
                if (icon != null) {
                    // Adaptive and square icons are drawn slightly larger so the circle crops their edges.
                    val grow = radius * .18f
                    icon.setBounds((circle.left - grow).toInt(), (circle.top - grow).toInt(), (circle.right + grow).toInt(), (circle.bottom + grow).toInt())
                    icon.draw(canvas)
                } else {
                    fill.color = Color.rgb(90, 90, 100)
                    canvas.drawOval(circle, fill)
                }
                canvas.restore()
                if (hover) {
                    fill.style = Paint.Style.STROKE
                    fill.strokeWidth = 6f
                    fill.color = Color.argb(220, 255, 255, 255)
                    canvas.drawOval(circle, fill)
                    fill.style = Paint.Style.FILL
                }
                entry.badge?.let { badge ->
                    fill.color = Color.rgb(10, 132, 255)
                    canvas.drawCircle(x + radius * .72f, y - radius * .72f, 30f, fill)
                    canvas.drawText(badge, x + radius * .72f, y - radius * .72f + 11f, label)
                }
                val text = TextUtils.ellipsize(entry.label, label, 300f, TextUtils.TruncateAt.END).toString()
                canvas.drawText(text, x, y + 158f, label)
                areas += RectF(x - 130f, y - 130f, x + 130f, y + 175f) to target
            }
        }
        // Dock with minimized windows.
        if (mode == Mode.HOME && dock.isNotEmpty()) {
            val y = 1165f
            val span = dock.size * 150f
            fill.color = Color.argb(120, 40, 40, 48)
            canvas.drawRoundRect(RectF(WIDTH / 2f - span / 2 - 30f, y - 80f, WIDTH / 2f + span / 2 + 30f, y + 80f), 80f, 80f, fill)
            dock.forEachIndexed { i, entry ->
                val x = WIDTH / 2f - span / 2 + 75f + i * 150f
                val target = Target.App(entry)
                val radius = if (target == hovered) 64f else 56f
                val circle = RectF(x - radius, y - radius, x + radius, y + radius)
                canvas.save()
                canvas.clipPath(Path().apply { addOval(circle, Path.Direction.CW) })
                entry.icon?.let {
                    it.setBounds((circle.left - 10).toInt(), (circle.top - 10).toInt(), (circle.right + 10).toInt(), (circle.bottom + 10).toInt())
                    it.draw(canvas)
                }
                canvas.restore()
                areas += RectF(x - 70f, y - 70f, x + 70f, y + 70f) to target
            }
        }
        // Page dots.
        val count = pages(list)
        if (count > 1) {
            for (i in 0 until count) {
                val x = WIDTH / 2f + (i - (count - 1) / 2f) * 44f
                val target = Target.Page(i)
                fill.color = if (i == page) Color.WHITE else Color.argb(if (target == hovered) 200 else 110, 255, 255, 255)
                canvas.drawCircle(x, HEIGHT - 50f, 11f, fill)
                areas += RectF(x - 22f, HEIGHT - 80f, x + 22f, HEIGHT - 20f) to target
            }
        }
    }

    companion object {
        const val WIDTH = 1800
        const val HEIGHT = 1350
        private val ROWS = intArrayOf(4, 5, 4)
        private val PER_PAGE = ROWS.sum()
    }
}

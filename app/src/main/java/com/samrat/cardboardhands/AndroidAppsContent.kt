package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import kotlin.concurrent.thread

/**
 * "Android‑приложения": every installed phone app, runnable in a VR window (a Shizuku virtual
 * display, like Minecraft in the cinema). A pinch on an icon opens it next to the other windows.
 */
class AndroidAppsContent(private val context: Context, private val open: (packageName: String, label: String) -> Unit) : VrWindow.Content {
    private class App(val packageName: String, val label: String, val icon: Drawable?)

    override val pixelWidth = 1600
    override val pixelHeight = 1000
    override val external = false
    private val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER }
    @Volatile private var fresh = true
    private var apps = emptyList<App>()
    private var page = 0
    private val areas = ArrayList<Pair<RectF, () -> Unit>>()

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        thread {
            val pm = context.packageManager
            apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it }
                .distinctBy { it.first }
                .filter { it.first != context.packageName }
                .map { (name, info) -> App(name, info.loadLabel(pm).toString(), runCatching { info.loadIcon(pm) }.getOrNull()) }
                .sortedBy { it.label.lowercase() }
            draw()
            onReady()
        }
    }

    override fun takeBitmap(): Bitmap? = if (fresh) synchronized(this) { fresh = false; bitmap } else null

    override fun toolbarTitle() = "Android‑приложения"

    override fun touch(action: Int, u: Float, v: Float) {
        if (action != MotionEvent.ACTION_UP) return
        val x = u * pixelWidth; val y = v * pixelHeight
        val hit = synchronized(this) { areas.firstOrNull { it.first.contains(x, y) }?.second } ?: return
        thread { hit(); draw() }
    }

    override fun release() = Unit

    @Synchronized
    private fun draw() {
        areas.clear()
        canvas.drawColor(Color.rgb(34, 34, 40))
        val start = page * PER_PAGE
        apps.drop(start).take(PER_PAGE).forEachIndexed { i, app ->
            val column = i % COLUMNS; val row = i / COLUMNS
            val cx = 140f + column * 220f; val cy = 130f + row * 250f
            val circle = RectF(cx - 70f, cy - 70f, cx + 70f, cy + 70f)
            canvas.save()
            canvas.clipPath(Path().apply { addOval(circle, Path.Direction.CW) })
            app.icon?.let { it.setBounds((circle.left - 12).toInt(), (circle.top - 12).toInt(), (circle.right + 12).toInt(), (circle.bottom + 12).toInt()); it.draw(canvas) }
            canvas.restore()
            canvas.drawText(TextUtils.ellipsize(app.label, label, 200f, TextUtils.TruncateAt.END).toString(), cx, cy + 115f, label)
            areas += RectF(cx - 100f, cy - 90f, cx + 100f, cy + 130f) to { open(app.packageName, app.label) }
        }
        val pages = maxOf(1, (apps.size + PER_PAGE - 1) / PER_PAGE)
        paint.color = Color.WHITE
        paint.textSize = 40f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("${page + 1} / $pages", pixelWidth / 2f, pixelHeight - 40f, paint)
        canvas.drawText("‹", 80f, pixelHeight - 40f, paint)
        canvas.drawText("›", pixelWidth - 80f, pixelHeight - 40f, paint)
        areas += RectF(0f, pixelHeight - 110f, 300f, pixelHeight.toFloat()) to { page = (page - 1).coerceAtLeast(0) }
        areas += RectF(pixelWidth - 300f, pixelHeight - 110f, pixelWidth.toFloat(), pixelHeight.toFloat()) to { page = (page + 1).coerceAtMost(pages - 1) }
        fresh = true
    }

    companion object {
        private const val COLUMNS = 7
        private const val PER_PAGE = COLUMNS * 3

        private const val PREFS = "android_apps"

        /** The store's "Get" for this PhoneXR app: it then shows on the VR home screen. */
        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(context: Context, value: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
    }
}

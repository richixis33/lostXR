package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** A floating VR keyboard: Russian and English letters, digits, shift, backspace, space, enter. */
class KeyboardPanel {
    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keys = ArrayList<Pair<RectF, String>>()
    private var russian = true
    private var shift = false

    /** What a pinch at 0..1 panel coordinates types: a character, or "backspace", "enter", "hide". */
    fun press(u: Float, v: Float): String? {
        val key = keys.firstOrNull { it.first.contains(u * WIDTH, v * HEIGHT) }?.second ?: return null
        return when (key) {
            SHIFT -> { shift = !shift; null }
            LANGUAGE -> { russian = !russian; null }
            SPACE -> " "
            BACKSPACE, ENTER, HIDE -> key
            else -> (if (shift) key.uppercase() else key).also { shift = false }
        }
    }

    fun hovered(u: Float, v: Float): String? = keys.firstOrNull { it.first.contains(u * WIDTH, v * HEIGHT) }?.second

    fun draw(hover: String?) {
        keys.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        paint.color = Color.argb(215, 44, 44, 50)
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), 44f, 44f, paint)
        val rows = if (russian) RUSSIAN else ENGLISH
        val allRows = listOf(DIGITS) + rows
        val keyHeight = 78f
        allRows.forEachIndexed { rowIndex, row ->
            val keyWidth = (WIDTH - 40f) / 12f
            val start = (WIDTH - row.length * keyWidth) / 2
            row.forEachIndexed { i, char ->
                val rect = RectF(start + i * keyWidth + 4, 20f + rowIndex * (keyHeight + 8), start + (i + 1) * keyWidth - 4, 20f + rowIndex * (keyHeight + 8) + keyHeight)
                key(rect, char.toString(), if (shift) char.uppercase() else char.toString(), hover)
            }
        }
        val y = 20f + allRows.size * (keyHeight + 8)
        key(RectF(24f, y, 184f, y + keyHeight), SHIFT, if (shift) "⇧ ✓" else "⇧", hover)
        key(RectF(194f, y, 344f, y + keyHeight), LANGUAGE, if (russian) "EN" else "РУ", hover)
        key(RectF(354f, y, 1054f, y + keyHeight), SPACE, if (russian) "пробел" else "space", hover)
        key(RectF(1064f, y, 1224f, y + keyHeight), BACKSPACE, "⌫", hover)
        key(RectF(1234f, y, 1384f, y + keyHeight), ENTER, "↵", hover)
        key(RectF(1394f, y, WIDTH - 24f, y + keyHeight), HIDE, "⌄", hover)
    }

    private fun key(rect: RectF, id: String, label: String, hover: String?) {
        paint.color = if (id == hover) Color.argb(235, 255, 255, 255) else Color.argb(90, 255, 255, 255)
        canvas.drawRoundRect(rect, 18f, 18f, paint)
        paint.color = if (id == hover) Color.rgb(30, 30, 36) else Color.WHITE
        paint.textSize = 50f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, rect.centerX(), rect.centerY() + 14f, paint)
        keys += rect to id
    }

    companion object {
        const val WIDTH = 1560
        const val HEIGHT = 470
        const val SHIFT = "#shift"
        const val LANGUAGE = "#lang"
        const val SPACE = "#space"
        const val BACKSPACE = "backspace"
        const val ENTER = "enter"
        const val HIDE = "hide"
        private const val DIGITS = "1234567890-."
        private val RUSSIAN = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        private val ENGLISH = listOf("qwertyuiop", "asdfghjkl@", "zxcvbnm/:?")
    }
}

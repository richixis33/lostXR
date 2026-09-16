package com.samrat.cardboardhands

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal binary AndroidManifest.xml editor.
 *
 * Adding elements would mean rebuilding the string pool, so every edit here rewrites an existing
 * integer in place:
 *  - targetSdkVersion drops to 29, which switches package visibility filtering off, so the game can
 *    reach the OpenXR runtime broker without a <queries> block (the usual cause of a black screen);
 *  - headset features stop being required, so the installer accepts the APK on a phone;
 *  - the "install the other split APKs first" flag is cleared for APKs taken from Google Play.
 */
object AndroidManifestPatcher {
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val MAX_TARGET_SDK = 29

    data class Result(val bytes: ByteArray, val changes: List<String>)

    fun patch(manifest: ByteArray): Result {
        val buffer = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN)
        val strings = readStringPool(buffer) ?: return Result(manifest, emptyList())
        val changes = mutableListOf<String>()

        var offset = 8 // skip the file header
        while (offset + 8 <= manifest.size) {
            val type = buffer.getShort(offset).toInt() and 0xffff
            val size = buffer.getInt(offset + 4)
            if (size <= 0) break
            if (type == CHUNK_START_ELEMENT) patchElement(buffer, offset, strings, changes)
            offset += size
        }
        return Result(manifest, changes)
    }

    private fun patchElement(
        buffer: ByteBuffer,
        offset: Int,
        strings: List<String>,
        changes: MutableList<String>
    ) {
        val element = strings.getOrNull(buffer.getInt(offset + 20)) ?: return
        // Attribute offsets live in the element header and count from the namespace field at +16.
        val attributeStart = buffer.getShort(offset + 24).toInt() and 0xffff
        val attributeSize = buffer.getShort(offset + 26).toInt() and 0xffff
        val attributeCount = buffer.getShort(offset + 28).toInt() and 0xffff
        if (attributeSize < 20) return

        var name: String? = null
        var required = -1
        for (index in 0 until attributeCount) {
            val attribute = offset + 16 + attributeStart + index * attributeSize
            val attributeName = strings.getOrNull(buffer.getInt(attribute + 4)) ?: continue
            val dataType = buffer.get(attribute + 15).toInt() and 0xff
            val data = attribute + 16
            when {
                attributeName == "name" -> name = strings.getOrNull(buffer.getInt(data))
                attributeName == "required" -> required = data
                attributeName == "targetSdkVersion" && element == "uses-sdk" && dataType == TYPE_INT_DEC -> {
                    val current = buffer.getInt(data)
                    if (current > MAX_TARGET_SDK) {
                        buffer.putInt(data, MAX_TARGET_SDK)
                        changes += "targetSdk $current → $MAX_TARGET_SDK"
                    }
                }
                attributeName == "isSplitRequired" && dataType == TYPE_INT_BOOLEAN -> {
                    if (buffer.getInt(data) != 0) {
                        buffer.putInt(data, 0)
                        changes += "снят запрет на установку без дополнительных файлов"
                    }
                }
            }
        }
        // <uses-feature> carries its name and required flag in the same element, so decide afterwards.
        if (element == "uses-feature" && required >= 0 && name != null && isHeadsetFeature(name)) {
            if (buffer.getInt(required) != 0) {
                buffer.putInt(required, 0)
                changes += "$name больше не обязательна"
            }
        }
    }

    private fun isHeadsetFeature(name: String) = name.startsWith("oculus.") ||
        name.startsWith("com.oculus.") || name.startsWith("android.hardware.vr") ||
        name.startsWith("wave.feature") || name.startsWith("picovr")

    /** Reads the first string pool chunk; its strings name every element and attribute. */
    private fun readStringPool(buffer: ByteBuffer): List<String>? {
        var offset = 8
        while (offset + 8 <= buffer.capacity()) {
            val type = buffer.getShort(offset).toInt() and 0xffff
            val size = buffer.getInt(offset + 4)
            if (size <= 0) return null
            if (type == CHUNK_STRING_POOL) return parseStringPool(buffer, offset, size)
            offset += size
        }
        return null
    }

    private fun parseStringPool(buffer: ByteBuffer, offset: Int, size: Int): List<String> {
        val count = buffer.getInt(offset + 8)
        val flags = buffer.getInt(offset + 16)
        val stringsStart = offset + buffer.getInt(offset + 20)
        val utf8 = (flags and 0x100) != 0
        return (0 until count).map { index ->
            val start = stringsStart + buffer.getInt(offset + 28 + index * 4)
            if (start < 0 || start >= offset + size) return@map ""
            if (utf8) readUtf8(buffer, start) else readUtf16(buffer, start)
        }
    }

    private fun readUtf8(buffer: ByteBuffer, start: Int): String {
        var cursor = start
        // A length is one byte, or two when the high bit marks a long string.
        fun length(): Int {
            val value = buffer.get(cursor++).toInt() and 0xff
            if (value and 0x80 == 0) return value
            return ((value and 0x7f) shl 8) or (buffer.get(cursor++).toInt() and 0xff)
        }
        length() // character count, the byte count follows
        val bytes = ByteArray(length())
        val copy = buffer.duplicate()
        copy.position(cursor)
        copy.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16(buffer: ByteBuffer, start: Int): String {
        var length = buffer.getShort(start).toInt() and 0xffff
        var cursor = start + 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7fff) shl 16) or (buffer.getShort(cursor).toInt() and 0xffff)
            cursor += 2
        }
        val characters = CharArray(length) { buffer.getShort(cursor + it * 2).toInt().toChar() }
        return String(characters)
    }
}

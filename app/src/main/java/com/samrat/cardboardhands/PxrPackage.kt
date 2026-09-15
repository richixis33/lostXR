package com.samrat.cardboardhands

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object PxrPackage {
    fun androidPayload(context: Context, source: Uri): Uri {
        val name = context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: source.lastPathSegment.orEmpty()
        if (!name.lowercase().endsWith(".pxr")) return source

        val folder = File(context.cacheDir, "patched").apply { mkdirs() }
        val output = File(folder, "PhoneXR-package-game.apk").apply { delete() }
        var validManifest = false
        var foundAndroid = false
        context.contentResolver.openInputStream(source).use { raw ->
            requireNotNull(raw) { "Не удалось открыть .pxr" }
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name.removePrefix("./")) {
                        "manifest.json" -> {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            validManifest = text.contains("com.phonexr.pxr") && text.contains("\"version\"")
                        }
                        "payload/android/game.apk" -> {
                            FileOutputStream(output).use { zip.copyTo(it) }
                            foundAndroid = true
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        require(validManifest) { "Это не пакет PhoneXR .pxr" }
        require(foundAndroid && output.length() > 0) { "В .pxr нет Android APK" }
        return FileProvider.getUriForFile(context, "${context.packageName}.patched.apks", output)
    }
}

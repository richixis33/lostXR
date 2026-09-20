package com.samrat.cardboardhands

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * Mods for Minecraft Bedrock: add-ons (.mcaddon), resource and behaviour packs (.mcpack), worlds
 * (.mcworld) and world templates (.mctemplate). Minecraft imports them itself when such a file is
 * opened with it, so LostXR only has to fetch the file and hand it over.
 *
 * Mods in the store live in the "minecraft_mods" folder next to the games (vr_games/minecraft_mods/).
 */
object MinecraftMods {
    const val MINECRAFT = "com.mojang.minecraftpe"
    val EXTENSIONS = setOf("mcaddon", "mcpack", "mcworld", "mctemplate")
    const val FOLDER = "minecraft_mods"

    fun isMod(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Opens the mod with Minecraft, which shows "Import started…" and adds it to the game. */
    fun install(activity: Activity, file: File): String? {
        if (runCatching { activity.packageManager.getApplicationInfo(MINECRAFT, 0) }.isFailure) return tr("Сначала установите Minecraft")
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.patched.apks", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/octet-stream")
            .setPackage(MINECRAFT)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { activity.startActivity(intent) }.exceptionOrNull()?.let { tr("Minecraft не открыл мод") }
    }

    /** A mod picked from the phone's files: copied to the shared cache with its own name, then installed. */
    fun installFromUri(activity: Activity, source: Uri): String? {
        val name = displayName(activity, source) ?: return tr("Не удалось прочитать файл")
        if (!isMod(name)) return tr("Это не мод Minecraft: нужен .mcaddon, .mcpack, .mcworld или .mctemplate")
        val file = File(folder(activity), name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_"))
        activity.contentResolver.openInputStream(source)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: return tr("Не удалось прочитать файл")
        return install(activity, file)
    }

    fun folder(activity: Activity) = File(activity.cacheDir, "patched/mods").apply { mkdirs() }

    private fun displayName(activity: Activity, uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
}

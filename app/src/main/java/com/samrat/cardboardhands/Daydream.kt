package com.samrat.cardboardhands

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Daydream games through Opendream Services (a community build of Google VR Services 1.13, package
 * com.google.vr.vrcore). Games for Daydream look for that package; Cardboard games run without it.
 * The APK ships inside PhoneXR (assets/daydream) and is installed on request.
 */
object Daydream {
    private const val ASSET = "daydream/opendream-services.apk"

    fun servicesInstalled(context: Context) =
        runCatching { context.packageManager.getApplicationInfo(GameLibrary.VR_SERVICES, 0) }.isSuccess

    fun bundled(context: Context) = runCatching { context.assets.open(ASSET).close() }.isSuccess

    /** Opens the system installer for Opendream Services. */
    fun installServices(activity: Activity) {
        if (!bundled(activity)) {
            Toast.makeText(activity, "Opendream Services нет в этой сборке PhoneXR", Toast.LENGTH_LONG).show()
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Разрешите PhoneXR устанавливать приложения и повторите", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val file = File(File(activity.cacheDir, "patched").apply { mkdirs() }, "opendream-services.apk")
        activity.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        val content = FileProvider.getUriForFile(activity, "${activity.packageName}.patched.apks", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(content, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Saves a passthrough frame to Pictures/PhoneXR, where the Photos app finds it. */
    fun savePhoto(context: Context, bitmap: Bitmap): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PhoneXR_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PhoneXR")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: error("No output stream")
        return uri
    }
}

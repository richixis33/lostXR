package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipFile

/** Installed VR games PhoneXR can start, found by what their APK carries. */
object GameLibrary {
    enum class Kind {
        /** OpenXR game, starts as is. */
        OPENXR,
        /** Gear VR game with the PhoneXR adapter in place of libvrapi.so. */
        GEAR_VR_READY,
        /** Gear VR game as it came from the store: needs patching before it runs. */
        GEAR_VR_ORIGINAL,
        /** Gear VR game that cannot be patched: it has no ARM build. */
        GEAR_VR_UNSUPPORTED,
        /** Google VR (Daydream or Cardboard) game: draws its own stereo view, starts as is. */
        DAYDREAM
    }

    data class Game(
        val packageName: String,
        val label: String,
        val kind: Kind,
        val apk: File,
        val hasSplits: Boolean,
        /** Carries the Oculus store purchase check; it may or may not stop the game. */
        val checksPurchase: Boolean = false
    )

    private val entitlement = setOf(
        "libovrplatformloader.so", "libOVRPlatformLoader.so", "libovrplatform.so", "libOVRPlatform.so"
    )
    private val vrCategories = listOf(
        "org.khronos.openxr.intent.category.IMMERSIVE_HMD",
        "com.oculus.intent.category.VR",
        DAYDREAM_CATEGORY,
        CARDBOARD_CATEGORY
    )
    private const val DAYDREAM_CATEGORY = "com.google.intent.category.DAYDREAM"
    private const val CARDBOARD_CATEGORY = "com.google.intent.category.CARDBOARD"
    /** Google VR Services: Daydream-only games will not start without it. */
    const val VR_SERVICES = "com.google.vr.vrcore"

    /** Slow (opens every candidate APK), call off the main thread. */
    fun scan(context: Context): List<Game> {
        val packages = context.packageManager
        val vrActivities = vrCategories.flatMap { category ->
            packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL)
        }
        val declaredVr = vrActivities.map { it.activityInfo.packageName }.toSet()
        val googleVr = listOf(DAYDREAM_CATEGORY, CARDBOARD_CATEGORY).flatMap { category ->
            packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL)
        }.map { it.activityInfo.packageName }.toSet()
        // A browser counts only when the activity it launches with is itself a VR one (Wolvic);
        // Chrome declares a separate VR activity for WebXR but opens as a normal browser.
        val vrLaunchers = vrActivities.map { it.activityInfo.packageName + "/" + it.activityInfo.name }.toSet()
        fun launchesIntoVr(packageName: String) =
            packages.getLaunchIntentForPackage(packageName)?.component?.let { "${it.packageName}/${it.className}" in vrLaunchers } == true
        val browsers = packages.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.toSet()

        return packages.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.packageName in declaredVr }
            .mapNotNull { info ->
                classify(info, info.packageName in declaredVr, info.packageName in googleVr)?.let { kind -> info to kind }
            }
            .map { (info, kind) -> Triple(info, kind, hasPurchaseCheck(info)) }
            // Browsers carry the OpenXR loader for WebXR but are not games, unless they declare VR (Wolvic).
            .filterNot { (info, kind, _) -> kind == Kind.OPENXR && info.packageName in browsers && !launchesIntoVr(info.packageName) }
            .map { (info, kind, checksPurchase) ->
                Game(
                    packageName = info.packageName,
                    label = info.loadLabel(packages).toString(),
                    kind = kind,
                    apk = File(info.sourceDir),
                    hasSplits = !info.splitSourceDirs.isNullOrEmpty(),
                    checksPurchase = checksPurchase
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun hasPurchaseCheck(info: ApplicationInfo) = runCatching {
        ZipFile(info.sourceDir).use { zip ->
            zip.entries().toList().any { it.name.startsWith("lib/") && it.name.substringAfterLast('/') in entitlement }
        }
    }.getOrDefault(false)

    private fun classify(info: ApplicationInfo, declaredVr: Boolean, googleVr: Boolean): Kind? {
        val names = runCatching { ZipFile(info.sourceDir).use { zip -> zip.entries().toList().map { it.name } } }
            .getOrNull() ?: return null
        val libs = names.filter { it.startsWith("lib/") }
        val vrapi = libs.filter { it.endsWith("/libvrapi.so") }
        if (vrapi.isEmpty()) {
            val openXr = libs.any { it.endsWith("/libopenxr_loader.so") }
            val gvr = googleVr || libs.any { it.endsWith("/libgvr.so") || it.endsWith("/libgvr_audio.so") }
            return when {
                openXr -> Kind.OPENXR
                gvr -> Kind.DAYDREAM
                declaredVr -> Kind.OPENXR
                else -> null
            }
        }
        // The adapter exists for 64-bit and 32-bit ARM; anything else (x86 only) cannot run it.
        val folder = vrapi.map { it.removePrefix("lib/").substringBefore('/') }
            .firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" || it == "armeabi" }
            ?: return Kind.GEAR_VR_UNSUPPORTED
        // The patcher adds the OpenXR loader next to the adapter; store builds never carry it.
        return if ("lib/$folder/libopenxr_loader.so" in libs) Kind.GEAR_VR_READY else Kind.GEAR_VR_ORIGINAL
    }

    fun launchIntent(context: Context, game: Game): Intent? =
        // Gear VR games declare MAIN + INFO instead of a launcher entry; this finds both.
        context.packageManager.getLaunchIntentForPackage(game.packageName)
            ?: vrCategories.firstNotNullOfOrNull { category ->
                context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(game.packageName), 0
                ).firstOrNull()?.activityInfo?.let { activity ->
                    Intent(Intent.ACTION_MAIN).setClassName(activity.packageName, activity.name)
                }
            }

    fun describe(kind: Kind) = when (kind) {
        Kind.OPENXR -> "OpenXR"
        Kind.GEAR_VR_READY -> "Gear VR · через переходник PhoneXR"
        Kind.GEAR_VR_ORIGINAL -> "Gear VR · нужно пропатчить"
        Kind.GEAR_VR_UNSUPPORTED -> "Gear VR · не поддерживается"
        Kind.DAYDREAM -> "Daydream / Cardboard"
    }
}

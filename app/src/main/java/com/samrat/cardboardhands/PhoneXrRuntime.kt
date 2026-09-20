package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * LostXR Runtime: the OpenXR runtime (Monado with LostXR's hands and Joy-Con) that OpenXR games
 * talk to. It ships inside LostXR (assets/runtime) and shows up in the OpenXR Runtime Broker as
 * "LostXR Runtime", replacing a separately installed Monado.
 */
object PhoneXrRuntime {
    const val PACKAGE = "org.freedesktop.monado.openxr_runtime.out_of_process"
    private const val ASSET = "runtime/phonexr-runtime.apk"
    private const val BROKER = "org.khronos.openxr.runtime_broker"
    /** versionCode of the runtime bundled in this LostXR (openxr-runtime/build_runtime_apk.py). */
    private const val BUNDLED_VERSION = 2L

    enum class State { MISSING, OUTDATED, READY }

    fun state(context: Context): State {
        val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.getOrNull() ?: return State.MISSING
        return if (info.longVersionCode < BUNDLED_VERSION && bundled(context)) State.OUTDATED else State.READY
    }

    fun bundled(context: Context) = runCatching { context.assets.open(ASSET).close() }.isSuccess

    fun install(activity: Activity) = Daydream.installAsset(activity, ASSET, "phonexr-runtime.apk")

    fun brokerInstalled(context: Context) =
        runCatching { context.packageManager.getApplicationInfo(BROKER, 0) }.isSuccess

    /** The broker app, where the user picks "LostXR Runtime"; its Play page when it is missing. */
    fun openBroker(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(BROKER)
        activity.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$BROKER")))
    }
}

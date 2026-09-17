package com.samrat.cardboardhands

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** App side of the cinema display: Shizuku permission and the shell-user DisplayUserService. */
object VirtualScreen {
    enum class Access { NOT_RUNNING, NEEDS_PERMISSION, READY }

    const val PERMISSION_REQUEST = 4242

    fun access(): Access = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> Access.NOT_RUNNING
        Shizuku.isPreV11() -> Access.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Access.NEEDS_PERMISSION
        else -> Access.READY
    }

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_REQUEST)

    fun bind(context: Context, onReady: (IDisplayService?) -> Unit): ServiceConnection {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) =
                onReady(binder?.takeIf { it.pingBinder() }?.let { IDisplayService.Stub.asInterface(it) })

            override fun onServiceDisconnected(name: ComponentName?) = onReady(null)
        }
        Shizuku.bindUserService(arguments(context), connection)
        return connection
    }

    fun unbind(context: Context, connection: ServiceConnection) {
        runCatching { Shizuku.unbindUserService(arguments(context), connection, true) }
    }

    private fun arguments(context: Context) =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, DisplayUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("cinema")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    /** Launcher activity of [packageName] as "package/class", which "am start -n" wants. */
    fun launcherComponent(context: Context, packageName: String): String? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToShortString()
}

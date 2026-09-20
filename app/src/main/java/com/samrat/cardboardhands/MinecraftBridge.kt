package com.samrat.cardboardhands

object MinecraftBridge {
    const val connected: Boolean = false
    const val CONNECT: String = "/connect"
    fun start() {}
    fun stop() {}
    fun sendPose(yaw: Float, pitch: Float, hands: Any?) {}
    fun installMod(context: Any?): String? = null
    fun modInstalled(context: Any?): Boolean = false
}

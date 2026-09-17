package com.samrat.cardboardhands

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * The accessibility filter runs in the tracking process, the settings screen in the main one.
 * This carries live Joy-Con state to the screen and "press a button" requests back.
 */
object JoyConBridge {
    private const val ACTION_WATCH = "com.samrat.cardboardhands.JOYCON_WATCH"
    private const val ACTION_STATE = "com.samrat.cardboardhands.JOYCON_STATE"
    private const val EXTRA_WATCHING = "watching"
    private const val EXTRA_LEARNING = "learning"
    private const val EXTRA_LEARNED_KEY = "learned_key"
    private const val EXTRA_PREFIX_LEFT = "left_"
    private const val EXTRA_PREFIX_RIGHT = "right_"

    data class Snapshot(
        val left: JoyConButtons.Live = JoyConButtons.Live(false, 0, 0, 0f, 0f),
        val right: JoyConButtons.Live = JoyConButtons.Live(false, 0, 0, 0f, 0f),
        /** Key code of the button just pressed while learning, and the side it came from. */
        val learnedKey: Int? = null,
        val learnedLeft: Boolean = false
    )

    /** Called by the settings screen: start or stop receiving live state, with or without learning. */
    fun watch(context: Context, watching: Boolean, learning: Boolean) {
        context.sendBroadcast(
            Intent(ACTION_WATCH)
                .setPackage(context.packageName)
                .putExtra(EXTRA_WATCHING, watching)
                .putExtra(EXTRA_LEARNING, learning)
        )
    }

    fun listen(context: Context, onState: (Snapshot) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val learned = intent.getIntExtra(EXTRA_LEARNED_KEY, -1)
                onState(
                    Snapshot(
                        left = intent.live(EXTRA_PREFIX_LEFT),
                        right = intent.live(EXTRA_PREFIX_RIGHT),
                        learnedKey = learned.takeIf { it >= 0 },
                        learnedLeft = intent.getBooleanExtra("learned_left", false)
                    )
                )
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }

    /** Called by the accessibility filter. */
    fun watchRequests(context: Context, onRequest: (watching: Boolean, learning: Boolean) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onRequest(
                intent.getBooleanExtra(EXTRA_WATCHING, false),
                intent.getBooleanExtra(EXTRA_LEARNING, false)
            )
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_WATCH), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }

    fun publish(context: Context, learnedKey: Int? = null, learnedLeft: Boolean = false) {
        val intent = Intent(ACTION_STATE).setPackage(context.packageName)
        intent.putLive(EXTRA_PREFIX_LEFT, JoyConButtons.live(left = true))
        intent.putLive(EXTRA_PREFIX_RIGHT, JoyConButtons.live(left = false))
        if (learnedKey != null) {
            intent.putExtra(EXTRA_LEARNED_KEY, learnedKey)
            intent.putExtra("learned_left", learnedLeft)
        }
        context.sendBroadcast(intent)
    }

    private fun Intent.putLive(prefix: String, live: JoyConButtons.Live) {
        putExtra(prefix + "connected", live.connected)
        putExtra(prefix + "buttons", live.buttons)
        putExtra(prefix + "raw", live.rawKeys)
        putExtra(prefix + "x", live.stickX)
        putExtra(prefix + "y", live.stickY)
    }

    private fun Intent.live(prefix: String) = JoyConButtons.Live(
        connected = getBooleanExtra(prefix + "connected", false),
        buttons = getIntExtra(prefix + "buttons", 0),
        rawKeys = getIntExtra(prefix + "raw", 0),
        stickX = getFloatExtra(prefix + "x", 0f),
        stickY = getFloatExtra(prefix + "y", 0f)
    )
}

package com.samrat.cardboardhands

import android.opengl.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Two-hand play on the cinema screen: each hand aims a cursor at the big screen, a pinch touches
 * there. Both hands together make a two-finger multi-touch, so on-screen joysticks and buttons of
 * Roblox, Brawl Stars or Minecraft can be used at once.
 */
class CinemaHands(
    private val tracker: HeadTracker,
    /** Screen centre y, z, width and the eye height, from the renderer. */
    private val placement: () -> FloatArray,
    private val onCursors: (List<CinemaRenderer.Cursor>) -> Unit,
    /** See-through hands in head space (triangles on the plane z = -1). */
    private val onGhosts: (List<FloatArray>) -> Unit,
    private val inject: (MotionEvent) -> Unit,
) {
    private class Hand(val pointerId: Int) {
        val filterX = HandGestures.OneEuro(minCutoff = .45f, beta = 1.2f, deadZone = .0025f)
        val filterY = HandGestures.OneEuro(minCutoff = .45f, beta = 1.2f, deadZone = .0025f)
        val latch = HandGestures.PinchLatch()
        var down = false
        var u = .5f
        var v = .5f
        var seen = false
    }

    /** Left hand is pointer 0, right hand pointer 1. */
    private val hands = mapOf(true to Hand(0), false to Hand(1))
    private var downTime = 0L

    fun onResult(result: HandLandmarkerResult) {
        val now = SystemClock.elapsedRealtimeNanos()
        val frameNs = result.timestampMs() * 1_000_000L
        val head = FloatArray(16).also { tracker.copyHeadAt(frameNs, it) }
        val place = placement()
        hands.values.forEach { it.seen = false }
        val cursors = ArrayList<CinemaRenderer.Cursor>(2)
        val ghosts = ArrayList<FloatArray>(2)

        result.landmarks().forEachIndexed { index, points ->
            if (points.size < 21) return@forEachIndexed
            val physicalLeft = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().equals("Right", true)
            val hand = hands.getValue(physicalLeft)
            if (hand.seen) return@forEachIndexed
            val shape = HandGestures.shape(points, physicalLeft)
            ghosts += GhostHand.triangles(
                FloatArray(21) { (points[it].x() - .5f) * 2f * TAN_X },
                FloatArray(21) { (.5f - points[it].y()) * 2f * TAN_Y },
                -1f,
            )
            val x = hand.filterX.filter(shape.aimX, now)
            val y = hand.filterY.filter(shape.aimY, now)
            val hit = screenPoint(x, y, head, place) ?: return@forEachIndexed
            hand.seen = true
            hand.u = hit[0].coerceIn(0f, 1f)
            hand.v = hit[1].coerceIn(0f, 1f)
            val pinching = hand.latch.update(shape)
            // A touch may only start on the screen; once down it follows the hand to the edges.
            val onScreen = hit[0] in 0f..1f && hit[1] in 0f..1f
            if (pinching && !hand.down && !onScreen) hand.latch.reset()
            val pressed = pinching && (hand.down || onScreen)
            if (hit[0] in -.08f..1.08f && hit[1] in -.08f..1.08f) cursors += CinemaRenderer.Cursor(hand.u, hand.v, pressed)
            if (pressed != hand.down) {
                if (pressed) press(hand) else release(hand)
            }
        }
        // A hand that left the camera lets go of the screen.
        for (hand in hands.values) {
            if (!hand.seen) {
                hand.filterX.reset(); hand.filterY.reset(); hand.latch.reset()
                if (hand.down) release(hand)
            }
        }
        if (hands.values.any { it.down }) send(MotionEvent.ACTION_MOVE, null)
        onCursors(cursors)
        onGhosts(ghosts)
    }

    /** Lets go of everything, e.g. when the cinema pauses. */
    fun releaseAll() {
        for (hand in hands.values) if (hand.down) release(hand)
        onCursors(emptyList())
        onGhosts(emptyList())
    }

    /** Where the hand's aim ray from the eyes meets the screen, as screen u, v (may be outside 0..1). */
    private fun screenPoint(x: Float, y: Float, head: FloatArray, place: FloatArray): FloatArray? {
        val local = floatArrayOf((x - .5f) * 2f * TAN_X, (.5f - y) * 2f * TAN_Y, -1f, 0f)
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, head, 0, local, 0)
        if (world[2] >= -1e-3f) return null
        val (centerY, screenZ, width, eye) = listOf(place[0], place[1], place[2], place[3])
        val t = screenZ / world[2]
        val hx = world[0] * t
        val hy = eye + world[1] * t
        val height = width * CinemaRenderer.SCREEN_PIXELS_H / CinemaRenderer.SCREEN_PIXELS_W
        return floatArrayOf((hx + width / 2) / width, (centerY + height / 2 - hy) / height)
    }

    private fun press(hand: Hand) {
        val first = hands.values.none { it.down }
        hand.down = true
        if (first) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, hand)
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN, hand)
        }
    }

    private fun release(hand: Hand) {
        val last = hands.values.count { it.down } == 1
        send(if (last) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, hand)
        hand.down = false
    }

    /** One multi-touch event with every hand that is down; [actor] is the pointer that changes. */
    private fun send(action: Int, actor: Hand?) {
        val active = hands.values.filter { it.down }.sortedBy { it.pointerId }
        if (active.isEmpty()) return
        val properties = Array(active.size) { i ->
            MotionEvent.PointerProperties().apply { id = active[i].pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(active.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = active[i].u * CinemaRenderer.SCREEN_PIXELS_W
                y = active[i].v * CinemaRenderer.SCREEN_PIXELS_H
                pressure = 1f
                size = 1f
            }
        }
        val masked = if (actor != null && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)) {
            action or (active.indexOf(actor) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), masked, active.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        inject(event)
        event.recycle()
    }

    private companion object {
        /**
         * Half-width and half-height of the camera view as tangents, with a little extra reach so
         * the whole screen is covered without stretching the arms (4:3 analysis frames).
         */
        const val TAN_X = .95f
        const val TAN_Y = .72f
    }
}

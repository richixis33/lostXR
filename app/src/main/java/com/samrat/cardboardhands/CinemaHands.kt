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
    /** Real hands in head space: triangles with camera texture coordinates (x, y, z, u, v). */
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

    /**
     * Minecraft VR controls instead of cursors: the head turns the camera, a "finger gun" walks
     * forward, a fist breaks and hits, a pinch places and uses. Bedrock has no VR hands of its own,
     * so the gestures press its touch controls (turn on "Split controls" in Minecraft's settings).
     */
    @Volatile var minecraft = false
    /** The hands' cut-out calibration (Settings → Калибровка рук in the VR home). */
    @Volatile var mask = HandProfile.Mask(1.1f, 0f, 0f)
    private val touch = MultiTouch(inject)
    private var lookX = LOOK_START_X
    private var lookY = LOOK_START_Y
    private var lookIdleSince = 0L

    /** Head movement since the last call (degrees): drags the camera. */
    fun look(deltaYaw: Float, deltaPitch: Float) {
        if (!minecraft) return
        val now = SystemClock.uptimeMillis()
        if (kotlin.math.abs(deltaYaw) + kotlin.math.abs(deltaPitch) < .05f) {
            if (touch.isDown(LOOK) && now - lookIdleSince > 120) touch.up(LOOK)
            return
        }
        lookIdleSince = now
        if (!touch.isDown(LOOK)) {
            lookX = LOOK_START_X; lookY = LOOK_START_Y
            touch.down(LOOK, lookX, lookY)
        }
        lookX -= deltaYaw * PIXELS_PER_DEGREE
        lookY -= deltaPitch * PIXELS_PER_DEGREE
        touch.move(LOOK, lookX, lookY)
        // Near the edge of its area the finger lifts and starts again from the middle.
        if (kotlin.math.abs(lookX - LOOK_START_X) > 420f || kotlin.math.abs(lookY - LOOK_START_Y) > 300f) touch.up(LOOK)
    }

    /** One hand's gestures in Minecraft mode. */
    private fun minecraftGestures(hand: Hand, shape: HandGestures.Shape, points: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>) {
        fun d(a: Int, b: Int) = kotlin.math.hypot(points[a].x() - points[b].x(), points[a].y() - points[b].y())
        val indexOut = d(0, 8) > d(0, 6) * 1.12f
        val othersCurled = intArrayOf(12, 16, 20).zip(intArrayOf(10, 14, 18)).all { (tip, pip) -> d(0, tip) < d(0, pip) * 1.05f }
        val thumbOut = d(4, 5) > shape.palmWidth * .55f
        val gun = indexOut && othersCurled && thumbOut
        val walkId = WALK
        val mineId = MINE + hand.pointerId
        val useId = USE + hand.pointerId
        // Walking belongs to whichever hand makes the gun first.
        if (gun && !touch.isDown(walkId)) { touch.down(walkId, WALK_X, WALK_Y); walker = hand }
        if (!gun && walker === hand && touch.isDown(walkId)) { touch.up(walkId); walker = null }
        if (shape.fist && !touch.isDown(mineId)) touch.down(mineId, MINE_X, MINE_Y)
        if (!shape.fist && touch.isDown(mineId)) touch.up(mineId)
        val pinching = hand.latch.update(shape)
        if (pinching && !hand.down) { hand.down = true; touch.down(useId, MINE_X + 40f, MINE_Y); touch.up(useId) }
        if (!pinching) hand.down = false
    }

    private var walker: Hand? = null

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
            if (minecraft) {
                hand.seen = true
                minecraftGestures(hand, shape, points)
            }
            // The real hand from the camera, over everything (x, y, z, u, v per vertex).
            ghosts += RealHand.mesh(
                FloatArray(21) { points[it].x() },
                FloatArray(21) { points[it].y() },
                { u, v -> floatArrayOf((u - .5f) * 2f * TAN_X + mask.dx, (.5f - v) * 2f * TAN_Y + mask.dy) },
                { u, v -> floatArrayOf(u, v) },
                mask.grow,
            )
            if (minecraft) return@forEachIndexed
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
            if (!hand.seen && minecraft) {
                touch.up(MINE + hand.pointerId)
                if (walker === hand) { touch.up(WALK); walker = null }
                hand.down = false
                continue
            }
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
        touch.releaseAll()
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
        // Minecraft touch layout on the 1920 × 1080 virtual screen (Bedrock's default controls).
        const val LOOK = 10
        const val WALK = 11
        const val MINE = 12
        const val USE = 14
        const val LOOK_START_X = 1340f
        const val LOOK_START_Y = 460f
        const val WALK_X = 240f
        const val WALK_Y = 720f
        const val MINE_X = 1100f
        const val MINE_Y = 620f
        const val PIXELS_PER_DEGREE = 12f
        const val TAN_Y = .72f
    }
}

/** Several fingers on the virtual screen at once, each with its own id, sent as real multi-touch. */
class MultiTouch(private val inject: (MotionEvent) -> Unit) {
    private val points = LinkedHashMap<Int, FloatArray>()
    private var downTime = 0L

    @Synchronized fun isDown(id: Int) = points.containsKey(id)

    @Synchronized
    fun down(id: Int, x: Float, y: Float) {
        if (points.containsKey(id)) return
        val first = points.isEmpty()
        points[id] = floatArrayOf(x, y)
        if (first) downTime = SystemClock.uptimeMillis()
        send(if (first) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, id)
    }

    @Synchronized
    fun move(id: Int, x: Float, y: Float) {
        val point = points[id] ?: return
        point[0] = x; point[1] = y
        send(MotionEvent.ACTION_MOVE, null)
    }

    @Synchronized
    fun up(id: Int) {
        if (!points.containsKey(id)) return
        send(if (points.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id)
        points.remove(id)
    }

    @Synchronized
    fun releaseAll() = points.keys.toList().forEach { up(it) }

    private fun send(action: Int, actor: Int?) {
        val ids = points.keys.toList()
        val properties = Array(ids.size) { i -> MotionEvent.PointerProperties().apply { id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(ids.size) { i ->
            MotionEvent.PointerCoords().apply { x = points.getValue(ids[i])[0]; y = points.getValue(ids[i])[1]; pressure = 1f; size = 1f }
        }
        val masked = if (actor != null && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP))
            action or (ids.indexOf(actor) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), masked, ids.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        inject(event)
        event.recycle()
    }
}

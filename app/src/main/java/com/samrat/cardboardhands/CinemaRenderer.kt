package com.samrat.cardboardhands

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Side-by-side stereo scene for phone VR headsets: a cosy room (or an island in the sky) with a big
 * screen that shows the game running on the virtual display.
 */
class CinemaRenderer(
    /** The place (room model, panorama); loaded off the GL thread, uploaded on it. Null: a built-in scene. */
    private val model: CinemaScene?,
    private val onSurface: (Surface) -> Unit,
) : GLSurfaceView.Renderer {
    enum class Scene { ROOM, SKY }

    /** A hand's touch point on the screen: u, v in 0..1 and whether it presses. */
    class Cursor(val u: Float, val v: Float, val pressed: Boolean)

    /** Up to two cursors, one per hand; written by the hand thread. */
    @Volatile var cursors: List<Cursor> = emptyList()
    /** See-through hands in head space, drawn over everything like in the VR home. */
    @Volatile var ghosts: List<FloatArray> = emptyList()
    /** Minecraft VR: the screen stays in front of the eyes, the head turns the game's camera instead. */
    @Volatile var headLocked = false
    /** Where the screen is, for hit tests from the hand thread (centre y, z, width). */
    val screenPlacement: FloatArray get() = if (model != null) floatArrayOf(model.screenCenterY, model.screenZ, model.screenWidth, model.eyeHeight)
        else floatArrayOf(SCREEN_CENTER_Y, SCREEN_Z, SCREEN_WIDTH, EYE_HEIGHT)

    @Volatile var scene = Scene.ROOM
        set(value) { field = value; sceneDirty.set(true) }
    /** Head rotation (head to world), column-major 4x4, written by the sensor thread. */
    var head = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val sceneDirty = AtomicBoolean(true)
    private val frameReady = AtomicBoolean(false)
    private var surfaceTexture: SurfaceTexture? = null
    private var screenTexture = 0
    private var colorProgram = 0
    private var screenProgram = 0
    private var cursorProgram = 0
    private var ghostProgram = 0
    private var sceneMesh: Mesh? = null
    private var width = 1
    private var height = 1

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val eye = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val headCopy = FloatArray(16)
    private val worldToHead = FloatArray(16)

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        colorProgram = program(COLOR_VERTEX, COLOR_FRAGMENT)
        screenProgram = program(SCREEN_VERTEX, SCREEN_FRAGMENT)
        cursorProgram = program(SCREEN_VERTEX, CURSOR_FRAGMENT)
        ghostProgram = program(GHOST_VERTEX, GHOST_FRAGMENT)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        screenTexture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screenTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val texture = SurfaceTexture(screenTexture).apply {
            setDefaultBufferSize(SCREEN_PIXELS_W, SCREEN_PIXELS_H)
            setOnFrameAvailableListener { frameReady.set(true) }
        }
        surfaceTexture = texture
        model?.upload()
        sceneDirty.set(true)
        onSurface(Surface(texture))
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    override fun onDrawFrame(unused: GL10?) {
        if (frameReady.getAndSet(false)) surfaceTexture?.updateTexImage()
        if (sceneDirty.getAndSet(false)) {
            sceneMesh?.release()
            sceneMesh = when {
                model != null -> null
                scene == Scene.SKY -> buildSky()
                else -> buildRoom()
            }
        }
        val sky = scene == Scene.SKY
        val clear = model?.clearColor ?: if (sky) floatArrayOf(.55f, .75f, .98f) else floatArrayOf(.05f, .04f, .04f)
        GLES20.glClearColor(clear[0], clear[1], clear[2], 1f)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        synchronized(head) { System.arraycopy(head, 0, headCopy, 0, 16) }
        val place = screenPlacement
        // World to head: inverse rotation, then the seated eye height.
        Matrix.transposeM(worldToHead, 0, headCopy, 0)
        Matrix.translateM(worldToHead, 0, 0f, -place[3], 0f)

        val eyeWidth = width / 2
        Matrix.perspectiveM(projection, 0, FOV_Y, eyeWidth.toFloat() / height, .05f, 200f)
        for (index in 0..1) {
            GLES20.glViewport(index * eyeWidth, 0, eyeWidth, height)
            Matrix.setIdentityM(eye, 0)
            Matrix.translateM(eye, 0, if (index == 0) IPD / 2 else -IPD / 2, 0f, 0f)
            Matrix.multiplyMM(view, 0, eye, 0, worldToHead, 0)
            Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
            if (model != null) model.draw(viewProjection) else sceneMesh?.draw(colorProgram, viewProjection)
            if (headLocked) {
                val eyeOnly = FloatArray(16)
                Matrix.multiplyMM(eyeOnly, 0, projection, 0, eye, 0)
                GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
                drawScreen(eyeOnly, HEAD_SCREEN_WIDTH, 0f, -HEAD_SCREEN_DISTANCE)
            } else {
                drawScreen(viewProjection, place[2], place[0], place[1])
                drawCursors(viewProjection, place[2], place[0], place[1])
            }
            drawGhosts()
        }
    }

    private fun drawScreen(mvp: FloatArray, screenWidth: Float, cy: Float, z: Float) {
        GLES20.glUseProgram(screenProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screenTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(screenProgram, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(screenProgram, "uMvp"), 1, false, mvp, 0)
        val halfW = screenWidth / 2
        val halfH = screenWidth * SCREEN_PIXELS_H / SCREEN_PIXELS_W / 2
        // x, y, z, u, v; a virtual display's picture has t = 0 at the top.
        val quad = floatArrayOf(
            -halfW, cy - halfH, z, 0f, 1f,
            halfW, cy - halfH, z, 1f, 1f,
            -halfW, cy + halfH, z, 0f, 0f,
            halfW, cy + halfH, z, 1f, 0f,
        )
        val buffer = floatBuffer(quad)
        val position = GLES20.glGetAttribLocation(screenProgram, "aPosition")
        val uv = GLES20.glGetAttribLocation(screenProgram, "aUv")
        buffer.position(0)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
        GLES20.glEnableVertexAttribArray(position)
        buffer.position(3)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Hand cursors: soft rings just in front of the screen, filled while touching. */
    private fun drawCursors(mvp: FloatArray, screenWidth: Float, cy: Float, z: Float) {
        val list = cursors
        if (list.isEmpty()) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(cursorProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cursorProgram, "uMvp"), 1, false, mvp, 0)
        val halfW = screenWidth / 2
        val halfH = screenWidth * SCREEN_PIXELS_H / SCREEN_PIXELS_W / 2
        val r = screenWidth * .018f
        for (cursor in list) {
            val x = -halfW + cursor.u * screenWidth
            val y = cy + halfH - cursor.v * halfH * 2
            GLES20.glUniform1f(GLES20.glGetUniformLocation(cursorProgram, "uPressed"), if (cursor.pressed) 1f else 0f)
            val quad = floatArrayOf(
                x - r, y - r, z + .01f, 0f, 1f,
                x + r, y - r, z + .01f, 1f, 1f,
                x - r, y + r, z + .01f, 0f, 0f,
                x + r, y + r, z + .01f, 1f, 0f,
            )
            val buffer = floatBuffer(quad)
            val position = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
            val uv = GLES20.glGetAttribLocation(cursorProgram, "aUv")
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Hands in head space: blended once per pixel thanks to the depth test on one flat plane. */
    private fun drawGhosts() {
        val list = ghosts
        if (list.isEmpty()) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projection, 0, eye, 0)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(ghostProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ghostProgram, "uMvp"), 1, false, mvp, 0)
        val position = GLES20.glGetAttribLocation(ghostProgram, "aPosition")
        for (triangles in list) {
            val buffer = floatBuffer(triangles)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, buffer)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triangles.size / 3)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthFunc(GLES20.GL_LESS)
    }

    // ------------------------------------------------------------------ Scenes

    private fun buildRoom() = MeshBuilder().apply {
        // Wooden floor planks.
        for (i in -8..7) {
            val shade = if (i % 2 == 0) floatArrayOf(.45f, .30f, .18f) else floatArrayOf(.50f, .34f, .20f)
            box(i * .5f + .25f, -.05f, 0f, .5f, .1f, 10f, shade)
        }
        box(0f, .005f, -.6f, 3.2f, .01f, 2.2f, floatArrayOf(.55f, .14f, .12f))      // rug
        box(0f, 3.05f, 0f, 8f, .1f, 10f, floatArrayOf(.92f, .9f, .86f))             // ceiling
        box(0f, 1.5f, -4.05f, 8f, 3f, .1f, floatArrayOf(.80f, .72f, .60f))          // front wall
        box(0f, 1.5f, 4.05f, 8f, 3f, .1f, floatArrayOf(.80f, .72f, .60f))           // back wall
        box(-4.05f, 1.5f, 0f, .1f, 3f, 8.2f, floatArrayOf(.76f, .68f, .56f))        // left wall
        box(4.05f, 1.5f, 0f, .1f, 3f, 8.2f, floatArrayOf(.76f, .68f, .56f))         // right wall
        box(0f, .12f, -4f, 8f, .24f, .06f, floatArrayOf(.35f, .24f, .15f))          // skirting
        // Window with daylight on the left wall.
        box(-3.99f, 1.7f, -1f, .04f, 1.3f, 2f, floatArrayOf(.28f, .20f, .13f))
        box(-3.96f, 1.7f, -1f, .04f, 1.1f, 1.8f, floatArrayOf(.62f, .82f, 1f))
        box(-3.94f, 1.7f, -1f, .04f, 1.1f, .05f, floatArrayOf(.28f, .20f, .13f))
        // TV stand and the screen bezel.
        box(0f, .3f, SCREEN_Z - .25f, 2.6f, .6f, .5f, floatArrayOf(.30f, .20f, .13f))
        val halfH = SCREEN_WIDTH * SCREEN_PIXELS_H / SCREEN_PIXELS_W / 2
        box(0f, SCREEN_CENTER_Y, SCREEN_Z - .04f, SCREEN_WIDTH + .12f, halfH * 2 + .12f, .06f, floatArrayOf(.04f, .04f, .05f))
        // Shelf with block-like decorations on the right wall.
        box(3.8f, 1.6f, -1.5f, .4f, .06f, 2f, floatArrayOf(.35f, .24f, .15f))
        box(3.8f, 1.8f, -2.2f, .3f, .3f, .3f, floatArrayOf(.36f, .62f, .25f))
        box(3.8f, 1.8f, -1.6f, .3f, .3f, .3f, floatArrayOf(.55f, .38f, .24f))
        box(3.8f, 1.8f, -1f, .3f, .3f, .3f, floatArrayOf(.62f, .62f, .62f))
        // Floor lamp with a warm shade.
        box(-2.8f, .75f, -3.2f, .06f, 1.5f, .06f, floatArrayOf(.15f, .15f, .15f))
        box(-2.8f, 1.6f, -3.2f, .5f, .4f, .5f, floatArrayOf(1f, .85f, .55f))
        // Couch behind the viewer and a side table.
        box(0f, .25f, 1.1f, 2.4f, .5f, .9f, floatArrayOf(.25f, .32f, .45f))
        box(0f, .75f, 1.5f, 2.4f, .6f, .2f, floatArrayOf(.22f, .29f, .42f))
        box(1.6f, .3f, .6f, .5f, .6f, .5f, floatArrayOf(.35f, .24f, .15f))
    }.build()

    private fun buildSky() = MeshBuilder().apply {
        // A floating grass island under the viewer, the world far below and blocky clouds around.
        for (x in -2..2) for (z in -2..2) {
            if (kotlin.math.abs(x) == 2 && kotlin.math.abs(z) == 2) continue
            box(x * 1f, -.5f, z * 1f, 1f, .2f, 1f, floatArrayOf(.36f, .62f, .25f))
            box(x * 1f, -1.1f, z * 1f, 1f, 1f, 1f, floatArrayOf(.47f, .33f, .22f))
        }
        box(0f, -2f, 0f, 3f, 1f, 3f, floatArrayOf(.47f, .33f, .22f))
        box(0f, -60f, 0f, 400f, 1f, 400f, floatArrayOf(.33f, .55f, .28f))
        box(-30f, -59f, -60f, 40f, 2f, 30f, floatArrayOf(.25f, .45f, .75f))
        val clouds = listOf(
            floatArrayOf(-12f, 6f, -25f, 10f), floatArrayOf(15f, 9f, -30f, 14f), floatArrayOf(-25f, 3f, 5f, 8f),
            floatArrayOf(22f, 4f, 12f, 12f), floatArrayOf(5f, 12f, 30f, 16f), floatArrayOf(-8f, -10f, -18f, 9f),
            floatArrayOf(10f, -14f, -8f, 11f), floatArrayOf(-18f, -12f, 20f, 13f),
        )
        for (c in clouds) box(c[0], c[1], c[2], c[3], 1.2f, c[3] * .6f, floatArrayOf(.97f, .97f, 1f))
        // A thin frame so the floating screen reads as a panel.
        val halfH = SCREEN_WIDTH * SCREEN_PIXELS_H / SCREEN_PIXELS_W / 2
        box(0f, SCREEN_CENTER_Y, SCREEN_Z - .04f, SCREEN_WIDTH + .08f, halfH * 2 + .08f, .04f, floatArrayOf(.1f, .1f, .12f))
    }.build()

    // ------------------------------------------------------------------ GL helpers

    private class Mesh(val buffer: FloatBuffer, val count: Int) {
        fun draw(program: Int, mvp: FloatArray) {
            if (count == 0) return
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val color = GLES20.glGetAttribLocation(program, "aColor")
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 24, buffer)
            GLES20.glEnableVertexAttribArray(color)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        }

        fun release() = Unit
    }

    private class MeshBuilder {
        private val data = ArrayList<Float>(4096)

        /** Axis-aligned box; faces are shaded by direction so shapes read without lighting. */
        fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, color: FloatArray) =
            orientedBox(floatArrayOf(cx, cy, cz), null, 0f, 0f, 0f, sx, sy, sz, color)

        fun orientedBox(
            base: FloatArray, rotation: FloatArray?, ox: Float, oy: Float, oz: Float,
            sx: Float, sy: Float, sz: Float, color: FloatArray
        ) {
            val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
            fun corner(x: Float, y: Float, z: Float): FloatArray {
                val p = floatArrayOf(ox + x, oy + y, oz + z, 1f)
                if (rotation != null) {
                    val r = FloatArray(4)
                    Matrix.multiplyMV(r, 0, rotation, 0, p, 0)
                    return floatArrayOf(base[0] + r[0], base[1] + r[1], base[2] + r[2])
                }
                return floatArrayOf(base[0] + p[0], base[1] + p[1], base[2] + p[2])
            }
            val c = arrayOf(
                corner(-hx, -hy, -hz), corner(hx, -hy, -hz), corner(hx, hy, -hz), corner(-hx, hy, -hz),
                corner(-hx, -hy, hz), corner(hx, -hy, hz), corner(hx, hy, hz), corner(-hx, hy, hz)
            )
            // Counter-clockwise when seen from outside.
            face(c[4], c[5], c[6], c[7], color, .85f) // +z
            face(c[1], c[0], c[3], c[2], color, .85f) // -z
            face(c[5], c[1], c[2], c[6], color, .7f)  // +x
            face(c[0], c[4], c[7], c[3], color, .7f)  // -x
            face(c[7], c[6], c[2], c[3], color, 1f)   // +y
            face(c[0], c[1], c[5], c[4], color, .5f)  // -y
        }

        private fun face(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, color: FloatArray, light: Float) {
            for (p in listOf(a, b, c, a, c, d)) {
                data += p[0]; data += p[1]; data += p[2]
                data += color[0] * light; data += color[1] * light; data += color[2] * light
            }
        }

        fun build() = Mesh(floatBuffer(data.toFloatArray()), data.size / 6)
    }

    companion object {
        private const val HEAD_SCREEN_WIDTH = 2.6f
        private const val HEAD_SCREEN_DISTANCE = 1.9f
        const val SCREEN_PIXELS_W = 1920
        const val SCREEN_PIXELS_H = 1080
        private const val SCREEN_WIDTH = 3.4f
        private const val SCREEN_CENTER_Y = 1.45f
        private const val SCREEN_Z = -3.9f
        private const val EYE_HEIGHT = 1.15f
        private const val IPD = .064f
        private const val FOV_Y = 90f

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values); position(0)
            }

        fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, source)
                GLES20.glCompileShader(it)
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(it)
            }
        }

        private const val COLOR_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }"""
        private const val COLOR_FRAGMENT = """
            precision mediump float;
            varying vec3 vColor;
            void main() { gl_FragColor = vec4(vColor, 1.0); }"""
        private const val SCREEN_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }"""
        private const val GHOST_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val GHOST_FRAGMENT = """
            precision mediump float;
            void main() { gl_FragColor = vec4(0.93, 0.95, 1.0, 0.38); }"""
        private const val CURSOR_FRAGMENT = """
            precision mediump float;
            uniform float uPressed;
            varying vec2 vUv;
            void main() {
                float d = length(vUv - vec2(0.5)) * 2.0;
                float ring = smoothstep(1.0, 0.85, d) * smoothstep(0.45, 0.6, d);
                float fill = uPressed * smoothstep(0.62, 0.5, d);
                float alpha = max(ring * 0.95, fill * 0.9);
                gl_FragColor = vec4(1.0, 1.0, 1.0, alpha);
            }"""
        private const val SCREEN_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
    }
}

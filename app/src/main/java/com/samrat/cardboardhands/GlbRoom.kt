package com.samrat.cardboardhands

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The cinema living room: "minecraft vr Living Room" by Piethekiddev (CC-BY-4.0, Sketchfab), a single
 * textured mesh with baked lighting. It is placed so the viewer sits facing the TV recess, where
 * the game screen goes.
 */
class GlbRoom private constructor(private val vertices: FloatBuffer, private val count: Int, private val bitmapData: ByteArray) {
    private var texture = 0
    private var program = 0

    /** Must run on the GL thread. */
    fun upload() {
        val bitmap = BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.size) ?: return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        bitmap.recycle()
        program = CinemaRenderer.program(VERTEX, FRAGMENT)
    }

    fun draw(mvp: FloatArray) {
        if (program == 0) return
        // The mesh is double-sided.
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, vertices)
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(3)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, vertices)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    companion object {
        /** Model units to metres: the ceiling (835 units) ends up 3 m high. */
        private const val SCALE = 3.0f / 835f
        /** The big recess on the model's +X wall (y -1047..-23, z 114..660); the screen hangs in front of its chimney. */
        private const val SCREEN_X = 500f
        private const val SCREEN_Y = -535f
        private const val SCREEN_Z = 387f
        private const val SCREEN_W = 1024f
        /** Where the viewer sits, looking at the screen (+X in the model). */
        private const val VIEWER_X = -420f
        const val EYE_HEIGHT = 320f * SCALE

        /** The screen in cinema world space: centre y, distance in front of the viewer, width. */
        val screenCenterY = SCREEN_Z * SCALE
        val screenZ = -(SCREEN_X - 6f - VIEWER_X) * SCALE
        val screenWidth = SCREEN_W * SCALE

        fun load(context: Context, asset: String): GlbRoom {
            val data = context.assets.open(asset).use { it.readBytes() }
            val bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val jsonLength = bytes.getInt(12)
            val json = JSONObject(String(data, 20, jsonLength, Charsets.UTF_8))
            val binStart = 20 + jsonLength + 8
            val accessors = json.getJSONArray("accessors")
            val views = json.getJSONArray("bufferViews")

            fun view(index: Int): Pair<Int, Int> {
                val v = views.getJSONObject(index)
                return binStart + v.optInt("byteOffset", 0) to v.getInt("byteLength")
            }

            fun floats(index: Int, size: Int): FloatArray {
                val a = accessors.getJSONObject(index)
                val (start, _) = view(a.getInt("bufferView"))
                val offset = start + a.optInt("byteOffset", 0)
                return FloatArray(a.getInt("count") * size) { bytes.getFloat(offset + it * 4) }
            }

            fun indices(index: Int): IntArray {
                val a = accessors.getJSONObject(index)
                val (start, _) = view(a.getInt("bufferView"))
                val offset = start + a.optInt("byteOffset", 0)
                return when (a.getInt("componentType")) {
                    5125 -> IntArray(a.getInt("count")) { bytes.getInt(offset + it * 4) }
                    5123 -> IntArray(a.getInt("count")) { bytes.getShort(offset + it * 2).toInt() and 0xffff }
                    else -> IntArray(a.getInt("count")) { bytes.get(offset + it).toInt() and 0xff }
                }
            }

            val primitive = json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
            val attributes = primitive.getJSONObject("attributes")
            val position = floats(attributes.getInt("POSITION"), 3)
            val uv = floats(attributes.getInt("TEXCOORD_0"), 2)
            val index = indices(primitive.getInt("indices"))

            val out = ArrayList<Float>(index.size * 5)
            for (t in index.indices step 3) {
                val corners = IntArray(3) { index[t + it] }
                // Skip the placeholder picture that hangs in front of the TV recess.
                if (corners.all {
                        position[it * 3] in 520f..532f && position[it * 3 + 1] in -710f..-345f && position[it * 3 + 2] in 230f..560f
                    }) continue
                for (k in corners) {
                    val mx = position[k * 3]; val my = position[k * 3 + 1]; val mz = position[k * 3 + 2]
                    // Model: +X toward the screen, +Z up. Cinema: -Z toward the screen, +Y up.
                    out += -(my - SCREEN_Y) * SCALE
                    out += mz * SCALE
                    out += -(mx - VIEWER_X) * SCALE
                    out += uv[k * 2]
                    out += uv[k * 2 + 1]
                }
            }
            val buffer = ByteBuffer.allocateDirect(out.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            out.forEach { buffer.put(it) }
            buffer.position(0)

            val image = json.getJSONArray("images").getJSONObject(0)
            val (imageStart, imageLength) = view(image.getInt("bufferView"))
            return GlbRoom(buffer, out.size / 5, data.copyOfRange(imageStart, imageStart + imageLength))
        }

        private const val VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }"""
        // The texture has very dark baked lighting; lift it so the room reads on a phone screen.
        private const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() {
                vec3 color = texture2D(uTexture, vUv).rgb;
                gl_FragColor = vec4(pow(min(color * 3.2, vec3(1.0)), vec3(0.85)), 1.0);
            }"""
    }
}

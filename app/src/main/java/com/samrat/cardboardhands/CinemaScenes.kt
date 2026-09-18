package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A place for the cinema: a room model or a panorama, with where the viewer's eyes and the big
 * screen are. Cinema world: the viewer stands at the origin on the floor, looking along -z, y up.
 */
interface CinemaScene {
    val eyeHeight: Float
    val screenWidth: Float
    val screenCenterY: Float
    val screenZ: Float
    val clearColor: FloatArray get() = floatArrayOf(.05f, .04f, .04f)
    /** Runs on the GL thread once the context exists. */
    fun upload()
    fun draw(viewProjection: FloatArray)
}

/** The Minecraft living room keeps its own tuned loader. */
class LivingRoomScene(private val room: GlbRoom) : CinemaScene {
    override val eyeHeight get() = GlbRoom.EYE_HEIGHT
    override val screenWidth get() = GlbRoom.screenWidth
    override val screenCenterY get() = GlbRoom.screenCenterY
    override val screenZ get() = GlbRoom.screenZ
    override fun upload() = room.upload()
    override fun draw(viewProjection: FloatArray) = room.draw(viewProjection)
}

/**
 * Any glTF binary with several materials (colour factors, textures, alpha blending), placed by
 * [Placement]: model units to metres, the viewer's spot and where the screen hangs.
 */
class GlbScene private constructor(
    private val parts: List<Part>,
    private val images: List<ByteArray>,
    private val placement: Placement,
) : CinemaScene {
    /** All in model units, after the model's own node transforms (y up). */
    data class Placement(
        val scale: Float,
        val viewerX: Float, val floorY: Float, val viewerZ: Float,
        val eyeAbove: Float,
        val screenX: Float, val screenY: Float, val screenZ: Float, val screenWidth: Float,
    )

    private class Part(val vertices: FloatBuffer, val count: Int, val color: FloatArray, val image: Int, val blend: Boolean)

    private var program = 0
    private var textures = IntArray(0)

    override val eyeHeight get() = placement.eyeAbove * placement.scale
    override val screenWidth get() = placement.screenWidth * placement.scale
    override val screenCenterY get() = (placement.screenY - placement.floorY) * placement.scale
    override val screenZ get() = (placement.screenZ - placement.viewerZ) * placement.scale
    override val clearColor get() = floatArrayOf(.55f, .75f, .95f)

    override fun upload() {
        program = CinemaRenderer.program(VERTEX, FRAGMENT)
        textures = IntArray(images.size)
        if (images.isNotEmpty()) GLES20.glGenTextures(images.size, textures, 0)
        images.forEachIndexed { index, bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@forEachIndexed
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            bitmap.recycle()
        }
    }

    override fun draw(viewProjection: FloatArray) {
        if (program == 0) return
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, viewProjection, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        val colorLocation = GLES20.glGetUniformLocation(program, "uColor")
        val texturedLocation = GLES20.glGetUniformLocation(program, "uTextured")
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val normal = GLES20.glGetAttribLocation(program, "aNormal")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        // Opaque parts first; then decals and glass, blended over them without writing depth.
        for (pass in 0..1) {
            val blending = pass == 1
            if (blending) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDepthMask(false)
            }
            for (part in parts) {
                if (part.blend != blending) continue
                GLES20.glUniform4fv(colorLocation, 1, part.color, 0)
                val textured = part.image >= 0 && part.image < textures.size
                GLES20.glUniform1f(texturedLocation, if (textured) 1f else 0f)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (textured) textures[part.image] else 0)
                part.vertices.position(0)
                GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 32, part.vertices)
                GLES20.glEnableVertexAttribArray(position)
                part.vertices.position(3)
                GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 32, part.vertices)
                GLES20.glEnableVertexAttribArray(normal)
                part.vertices.position(6)
                GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 32, part.vertices)
                GLES20.glEnableVertexAttribArray(uv)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, part.count)
            }
            if (blending) {
                GLES20.glDepthMask(true)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
        }
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    companion object {
        /**
         * "roblox house" (a Brookhaven-style house from Sketchfab). The living room has a fireplace
         * with a picture above it on the back wall; the screen replaces the picture and the viewer
         * sits on the couch facing it. Ground floor is at y 23, the ceiling at 39 (model units).
         */
        val ROBLOX_HOUSE = Placement(
            scale = .225f,
            viewerX = 11f, floorY = 23f, viewerZ = -17f, eyeAbove = 5.5f,
            screenX = 11f, screenY = 31f, screenZ = -37.4f, screenWidth = 12f,
        )

        fun load(context: Context, asset: String, placement: Placement): GlbScene {
            val data = context.assets.open(asset).use { it.readBytes() }
            val bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val jsonLength = bytes.getInt(12)
            val json = JSONObject(String(data, 20, jsonLength, Charsets.UTF_8))
            val binStart = 20 + jsonLength + 8
            val accessors = json.getJSONArray("accessors")
            val views = json.getJSONArray("bufferViews")
            val materials = json.optJSONArray("materials")
            val textures = json.optJSONArray("textures")

            fun floats(index: Int, size: Int): FloatArray {
                val a = accessors.getJSONObject(index)
                val v = views.getJSONObject(a.getInt("bufferView"))
                val stride = v.optInt("byteStride", size * 4).takeIf { it > 0 } ?: size * 4
                val offset = binStart + v.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
                val count = a.getInt("count")
                return FloatArray(count * size) { bytes.getFloat(offset + (it / size) * stride + (it % size) * 4) }
            }

            fun indices(index: Int): IntArray {
                val a = accessors.getJSONObject(index)
                val v = views.getJSONObject(a.getInt("bufferView"))
                val offset = binStart + v.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
                return when (a.getInt("componentType")) {
                    5125 -> IntArray(a.getInt("count")) { bytes.getInt(offset + it * 4) }
                    5123 -> IntArray(a.getInt("count")) { bytes.getShort(offset + it * 2).toInt() and 0xffff }
                    else -> IntArray(a.getInt("count")) { bytes.get(offset + it).toInt() and 0xff }
                }
            }

            // Model to cinema: the nodes' own transforms, then the viewer's spot at the origin, in metres.
            val toCinema = FloatArray(16)
            Matrix.setIdentityM(toCinema, 0)
            Matrix.scaleM(toCinema, 0, placement.scale, placement.scale, placement.scale)
            Matrix.translateM(toCinema, 0, -placement.viewerX, -placement.floorY, -placement.viewerZ)

            val parts = ArrayList<Part>()
            val nodes = json.getJSONArray("nodes")
            fun visit(nodeIndex: Int, parent: FloatArray) {
                val node = nodes.getJSONObject(nodeIndex)
                val local = nodeMatrix(node)
                val world = FloatArray(16)
                Matrix.multiplyMM(world, 0, parent, 0, local, 0)
                if (node.has("mesh")) {
                    val normalMatrix = FloatArray(16).also { Matrix.invertM(it, 0, world, 0) }.let { inverse ->
                        FloatArray(16).also { Matrix.transposeM(it, 0, inverse, 0) }
                    }
                    val primitives = json.getJSONArray("meshes").getJSONObject(node.getInt("mesh")).getJSONArray("primitives")
                    for (p in 0 until primitives.length()) {
                        val primitive = primitives.getJSONObject(p)
                        if (primitive.optInt("mode", 4) != 4) continue
                        val attributes = primitive.getJSONObject("attributes")
                        val position = floats(attributes.getInt("POSITION"), 3)
                        val normal = if (attributes.has("NORMAL")) floats(attributes.getInt("NORMAL"), 3) else null
                        val uv = if (attributes.has("TEXCOORD_0")) floats(attributes.getInt("TEXCOORD_0"), 2) else null
                        val vertexCount = position.size / 3
                        val index = if (primitive.has("indices")) indices(primitive.getInt("indices")) else IntArray(vertexCount) { it }
                        val material = materials?.optJSONObject(primitive.optInt("material", -1))
                        val pbr = material?.optJSONObject("pbrMetallicRoughness")
                        val factor = pbr?.optJSONArray("baseColorFactor")
                        val color = FloatArray(4) { factor?.optDouble(it, 1.0)?.toFloat() ?: 1f }
                        val image = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1)
                            ?.let { textures?.optJSONObject(it)?.optInt("source", -1) } ?: -1
                        val blend = material?.optString("alphaMode") == "BLEND"

                        val buffer = ByteBuffer.allocateDirect(index.size * 32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        val p4 = FloatArray(4)
                        val out = FloatArray(4)
                        for (k in index) {
                            p4[0] = position[k * 3]; p4[1] = position[k * 3 + 1]; p4[2] = position[k * 3 + 2]; p4[3] = 1f
                            Matrix.multiplyMV(out, 0, world, 0, p4, 0)
                            Matrix.multiplyMV(p4, 0, toCinema, 0, out, 0)
                            buffer.put(p4[0]); buffer.put(p4[1]); buffer.put(p4[2])
                            if (normal != null) {
                                p4[0] = normal[k * 3]; p4[1] = normal[k * 3 + 1]; p4[2] = normal[k * 3 + 2]; p4[3] = 0f
                                Matrix.multiplyMV(out, 0, normalMatrix, 0, p4, 0)
                                buffer.put(out[0]); buffer.put(out[1]); buffer.put(out[2])
                            } else {
                                buffer.put(0f); buffer.put(1f); buffer.put(0f)
                            }
                            buffer.put(uv?.get(k * 2) ?: 0f); buffer.put(uv?.get(k * 2 + 1) ?: 0f)
                        }
                        buffer.position(0)
                        parts += Part(buffer, index.size, color, image, blend)
                    }
                }
                val children = node.optJSONArray("children")
                if (children != null) for (c in 0 until children.length()) visit(children.getInt(c), world)
            }
            val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            val scene = json.getJSONArray("scenes").getJSONObject(json.optInt("scene", 0))
            val roots = scene.getJSONArray("nodes")
            for (r in 0 until roots.length()) visit(roots.getInt(r), identity)

            val imageArray = json.optJSONArray("images")
            val images = (0 until (imageArray?.length() ?: 0)).map { i ->
                val image = imageArray!!.getJSONObject(i)
                val v = views.getJSONObject(image.getInt("bufferView"))
                val start = binStart + v.optInt("byteOffset", 0)
                data.copyOfRange(start, start + v.getInt("byteLength"))
            }
            return GlbScene(parts, images, placement)
        }

        /** glTF node transform: a matrix, or translation * rotation * scale. */
        private fun nodeMatrix(node: JSONObject): FloatArray {
            val m = FloatArray(16)
            node.optJSONArray("matrix")?.let { a ->
                for (i in 0 until 16) m[i] = a.getDouble(i).toFloat()
                return m
            }
            Matrix.setIdentityM(m, 0)
            node.optJSONArray("translation")?.let { t ->
                Matrix.translateM(m, 0, t.getDouble(0).toFloat(), t.getDouble(1).toFloat(), t.getDouble(2).toFloat())
            }
            node.optJSONArray("rotation")?.let { q ->
                val x = q.getDouble(0).toFloat(); val y = q.getDouble(1).toFloat()
                val z = q.getDouble(2).toFloat(); val w = q.getDouble(3).toFloat()
                val r = floatArrayOf(
                    1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0f,
                    2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0f,
                    2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0f,
                    0f, 0f, 0f, 1f,
                )
                val out = FloatArray(16)
                Matrix.multiplyMM(out, 0, m, 0, r, 0)
                System.arraycopy(out, 0, m, 0, 16)
            }
            node.optJSONArray("scale")?.let { s ->
                Matrix.scaleM(m, 0, s.getDouble(0).toFloat(), s.getDouble(1).toFloat(), s.getDouble(2).toFloat())
            }
            return m
        }

        private const val VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec2 aUv;
            varying vec2 vUv;
            varying vec3 vNormal;
            void main() {
                vUv = aUv;
                vNormal = aNormal;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }"""
        // glTF colours are linear: light them simply, then encode for the screen.
        private const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uColor;
            uniform float uTextured;
            varying vec2 vUv;
            varying vec3 vNormal;
            void main() {
                vec4 base = uColor;
                if (uTextured > 0.5) base *= texture2D(uTexture, vUv);
                vec3 n = normalize(vNormal + vec3(0.0001));
                float light = 0.62 + 0.38 * abs(dot(n, normalize(vec3(0.35, 0.85, 0.4))));
                gl_FragColor = vec4(pow(base.rgb * light, vec3(1.0 / 2.2)), base.a);
            }"""
    }
}

/**
 * A 360° picture (equirectangular) around the viewer, e.g. the Brawl Stars arena. The screen floats
 * in front; the picture is turned so its centrepiece sits to the viewer's left, not behind the screen.
 */
class PanoramaScene(private val bitmap: Bitmap, private val yawDegrees: Float = 70f) : CinemaScene {
    private var program = 0
    private var texture = 0
    private val sphere: FloatBuffer = buildSphere()
    private val count = STACKS * SLICES * 6

    override val eyeHeight = 1.15f
    override val screenWidth = 3.4f
    override val screenCenterY = 1.45f
    override val screenZ = -3.9f

    override fun upload() {
        program = CinemaRenderer.program(VERTEX, FRAGMENT)
        texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun draw(viewProjection: FloatArray) {
        if (program == 0) return
        // Centred on the eyes and far away: the picture has no parallax, like the real sky.
        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, eyeHeight, 0f)
        Matrix.rotateM(model, 0, yawDegrees, 0f, 1f, 0f)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        sphere.position(0)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, sphere)
        GLES20.glEnableVertexAttribArray(position)
        sphere.position(3)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, sphere)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun buildSphere(): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(STACKS * SLICES * 6 * 20).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun put(u: Float, v: Float) {
            // u = 0.5 straight ahead (-z), v = 0 straight up.
            val lon = (u - .5f) * 2f * PI.toFloat()
            val lat = (.5f - v) * PI.toFloat()
            buffer.put(sin(lon) * cos(lat) * RADIUS)
            buffer.put(sin(lat) * RADIUS)
            buffer.put(-cos(lon) * cos(lat) * RADIUS)
            buffer.put(u); buffer.put(v)
        }
        for (i in 0 until STACKS) for (j in 0 until SLICES) {
            val u0 = j.toFloat() / SLICES; val u1 = (j + 1f) / SLICES
            val v0 = i.toFloat() / STACKS; val v1 = (i + 1f) / STACKS
            put(u0, v0); put(u0, v1); put(u1, v1)
            put(u0, v0); put(u1, v1); put(u1, v0)
        }
        buffer.position(0)
        return buffer
    }

    private companion object {
        const val STACKS = 32
        const val SLICES = 64
        const val RADIUS = 80f
        const val VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
    }
}

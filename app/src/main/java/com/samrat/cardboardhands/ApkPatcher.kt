package com.samrat.cardboardhands

import android.content.Context
import android.net.Uri
import com.android.apksig.ApkSigner
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ApkPatcher {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val LOADER = "lib/arm64-v8a/libopenxr_loader.so"

    data class Result(val apk: File, val changes: List<String>)

    fun patch(context: Context, source: Uri): Result {
        val directory = File(context.cacheDir, "patched").apply { mkdirs() }
        val unsigned = File(directory, "unsigned.apk")
        val output = File(directory, "PhoneXR-patched.apk")
        unsigned.delete()
        output.delete()
        val loader = context.assets.open("libopenxr_loader.so").use { it.readBytes() }
        val changes = mutableListOf<String>()
        var sawManifest = false
        var sawArm64 = false

        context.contentResolver.openInputStream(source).use { rawInput ->
            requireNotNull(rawInput) { "Не удалось открыть файл" }
            val counting = CountingOutputStream(BufferedOutputStream(FileOutputStream(unsigned)))
            ZipInputStream(BufferedInputStream(rawInput)).use { input ->
                ZipOutputStream(counting).use { zip ->
                    while (true) {
                        val original = input.nextEntry ?: break
                        val name = original.name
                        if (isOldSignature(name)) continue
                        if (name.startsWith("lib/arm64-v8a/")) sawArm64 = true

                        val data: ByteArray? = when {
                            name == MANIFEST -> {
                                sawManifest = true
                                val patched = AndroidManifestPatcher.patch(input.readBytes())
                                changes += patched.changes
                                patched.bytes
                            }
                            name == LOADER -> {
                                changes += "OpenXR loader заменён на сборку PhoneXR"
                                loader
                            }
                            original.method == ZipEntry.STORED -> input.readBytes()
                            else -> null
                        }

                        val stored = data != null && (name.endsWith(".so") || original.method == ZipEntry.STORED)
                        val entry = ZipEntry(name).apply { time = original.time }
                        if (stored) {
                            entry.method = ZipEntry.STORED
                            entry.size = data!!.size.toLong()
                            entry.compressedSize = data.size.toLong()
                            entry.crc = CRC32().apply { update(data) }.value
                            if (name.startsWith("lib/") && name.endsWith(".so")) {
                                entry.extra = alignmentExtra(counting.count, name, 16_384)
                            }
                        }
                        zip.putNextEntry(entry)
                        if (data != null) zip.write(data) else input.copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
        }
        require(sawManifest) { "Это не APK: внутри нет AndroidManifest.xml" }
        require(sawArm64) { "В APK нет 64-битных библиотек (arm64-v8a) — такая сборка не запустится" }
        sign(context, unsigned, output)
        unsigned.delete()
        if (changes.isEmpty()) changes += "APK уже подходит, изменена только подпись"
        return Result(output, changes)
    }

    private fun sign(context: Context, input: File, output: File) {
        val store = KeyStore.getInstance("PKCS12")
        context.assets.open("phonexr-signing.p12").use { store.load(it, "android".toCharArray()) }
        val key = store.getKey("androiddebugkey", "android".toCharArray()) as java.security.PrivateKey
        val certificate = store.getCertificate("androiddebugkey") as X509Certificate
        val signer = ApkSigner.SignerConfig.Builder("PhoneXR", key, listOf(certificate)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    private fun isOldSignature(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith("META-INF/") &&
            (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") ||
                upper.endsWith(".SF") || upper == "META-INF/MANIFEST.MF")
    }

    private fun alignmentExtra(offset: Long, name: String, alignment: Int): ByteArray {
        val nameLength = name.toByteArray(Charsets.UTF_8).size
        val base = offset + 30 + nameLength + 4
        val payload = ((alignment - (base % alignment)) % alignment).toInt()
        return ByteArray(payload + 4).also {
            it[0] = 0x35
            it[1] = 0xD9.toByte()
            it[2] = (payload and 0xff).toByte()
            it[3] = ((payload ushr 8) and 0xff).toByte()
        }
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
            private set
        override fun write(value: Int) { out.write(value); count++ }
        override fun write(data: ByteArray, offset: Int, length: Int) {
            out.write(data, offset, length)
            count += length
        }
    }
}

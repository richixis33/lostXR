package com.samrat.cardboardhands

import android.content.Context
import android.net.Uri
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkPatcher {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val LOADER = "libopenxr_loader.so"
    private const val VRAPI = "libvrapi.so"
    /** Oculus store purchase check libraries. Left in place untouched; the user is only warned. */
    private val ENTITLEMENT = setOf(
        "libovrplatformloader.so", "libOVRPlatformLoader.so", "libovrplatform.so", "libOVRPlatform.so"
    )

    /**
     * ABI folders PhoneXR can serve, with the assets that go into them: the OpenXR loader and the
     * Gear VR adapter (gearvr-shim: VrApi served through OpenXR, so Gear VR games need no Samsung phone).
     * Old 32-bit games keep their libraries in "armeabi"; a 64-bit phone runs them with the v7a build.
     */
    private enum class Abi(val folder: String, val loaderAsset: String, val vrapiAsset: String, val title: String) {
        ARM64("arm64-v8a", "libopenxr_loader.so", "libvrapi.so", "64 бита"),
        ARMV7("armeabi-v7a", "libopenxr_loader32.so", "libvrapi32.so", "32 бита"),
        ARMEABI("armeabi", "libopenxr_loader32.so", "libvrapi32.so", "32 бита");

        companion object {
            fun of(name: String): Abi? =
                if (!name.startsWith("lib/")) null
                else entries.firstOrNull { name.startsWith("lib/${it.folder}/") }
        }
    }

    /** [gearVr] is true when the game draws through VrApi and got the PhoneXR adapter. */
    data class Result(val apk: File, val changes: List<String>, val gearVr: Boolean)

    fun patch(context: Context, source: Uri): Result {
        val directory = File(context.cacheDir, "patched").apply { mkdirs() }
        val unsigned = File(directory, "unsigned.apk")
        val output = File(directory, "PhoneXR-patched.apk")
        unsigned.delete()
        output.delete()
        val assets = HashMap<String, ByteArray?>()
        fun asset(name: String) = assets.getOrPut(name) {
            runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull()
        }
        val changes = mutableListOf<String>()
        var sawManifest = false
        var gearVr = false
        var checksPurchase = false
        val abis = sortedSetOf<Abi>()
        val loaderIn = HashSet<Abi>()
        val vrapiIn = HashSet<Abi>()
        var otherLibs = false

        // The archive is read through its central directory, the way Android's installer reads it.
        // Walking local headers instead breaks on APKs that carry stray duplicate entries.
        val copy = File(directory, "source.apk")
        val input = sourceFile(context, source, copy)
        try {
            val counting = CountingOutputStream(BufferedOutputStream(FileOutputStream(unsigned)))
            ZipFile(input).use { archive ->
                ZipOutputStream(counting).use { zip ->
                    val written = HashSet<String>()
                    for (original in archive.entries()) {
                        val name = original.name
                        if (original.isDirectory || isOldSignature(name) || !written.add(name)) continue
                        val fileName = name.substringAfterLast('/')
                        val abi = Abi.of(name)
                        if (abi != null) abis += abi
                        else if (name.startsWith("lib/") && name.endsWith(".so")) otherLibs = true
                        if (name.startsWith("lib/") && fileName in ENTITLEMENT && !checksPurchase) {
                            checksPurchase = true
                            changes += "в игре есть проверка покупки Oculus ($fileName). PhoneXR её не трогает: " +
                                "если игра действительно её требует, она не запустится"
                        }

                        val data: ByteArray? = when {
                            name == MANIFEST -> {
                                sawManifest = true
                                val patched = AndroidManifestPatcher.patch(archive.getInputStream(original).use { it.readBytes() })
                                changes += patched.changes
                                patched.bytes
                            }
                            abi != null && name == "lib/${abi.folder}/$LOADER" -> {
                                loaderIn += abi
                                changes += "OpenXR loader (${abi.title}) заменён на сборку PhoneXR"
                                requireNotNull(asset(abi.loaderAsset)) { "В PhoneXR нет OpenXR loader для ${abi.title}" }
                            }
                            abi != null && name == "lib/${abi.folder}/$VRAPI" -> {
                                vrapiIn += abi
                                gearVr = true
                                changes += "libvrapi.so (${abi.title}) заменён переходником Gear VR → OpenXR"
                                requireNotNull(asset(abi.vrapiAsset)) {
                                    "Это игра Gear VR (${abi.title}), а в эту сборку PhoneXR не вложен переходник для неё"
                                }
                            }
                            else -> null
                        }

                        val entry = ZipEntry(name).apply { time = original.time }
                        val nativeLib = name.startsWith("lib/") && name.endsWith(".so")
                        if (data != null) {
                            if (nativeLib || original.method == ZipEntry.STORED) {
                                entry.method = ZipEntry.STORED
                                entry.size = data.size.toLong()
                                entry.compressedSize = data.size.toLong()
                                entry.crc = CRC32().apply { update(data) }.value
                            }
                        } else if (original.method == ZipEntry.STORED) {
                            // Stored game data can be hundreds of megabytes: copy it as a stream, never into memory.
                            entry.method = ZipEntry.STORED
                            entry.size = original.size
                            entry.compressedSize = original.size
                            entry.crc = original.crc
                        }
                        if (nativeLib && entry.method == ZipEntry.STORED) {
                            entry.extra = alignmentExtra(counting.count, name, 16_384)
                        }
                        zip.putNextEntry(entry)
                        if (data != null) zip.write(data) else archive.getInputStream(original).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    // A Gear VR game ships without OpenXR; the adapter loads it from the game's lib folder.
                    for (abi in vrapiIn - loaderIn) {
                        val loader = requireNotNull(asset(abi.loaderAsset)) { "В PhoneXR нет OpenXR loader для ${abi.title}" }
                        val name = "lib/${abi.folder}/$LOADER"
                        zip.putNextEntry(storedEntry(name, loader, counting.count))
                        zip.write(loader)
                        zip.closeEntry()
                        changes += "добавлен OpenXR loader PhoneXR (${abi.title})"
                    }
                }
            }
        } finally {
            copy.delete()
        }
        require(sawManifest) { "Это не APK: внутри нет AndroidManifest.xml" }
        require(abis.isNotEmpty() || !otherLibs) {
            "В APK нет библиотек для ARM (arm64-v8a или armeabi-v7a) — на телефоне такая сборка не запустится"
        }
        if (Abi.ARM64 !in abis && abis.isNotEmpty()) changes += "32-битная игра: PhoneXR запустит её в 32-битном режиме"
        sign(context, unsigned, output)
        unsigned.delete()
        if (changes.isEmpty()) changes += "APK уже подходит, изменена только подпись"
        return Result(output, changes, gearVr)
    }

    /** A file to open as a zip: installed games already are files, picked documents are copied first. */
    private fun sourceFile(context: Context, source: Uri, copy: File): File {
        if (source.scheme == "file") return File(requireNotNull(source.path))
        copy.delete()
        context.contentResolver.openInputStream(source).use { raw ->
            requireNotNull(raw) { "Не удалось открыть файл" }
            FileOutputStream(copy).use { raw.copyTo(it) }
        }
        return copy
    }

    private fun storedEntry(name: String, data: ByteArray, offset: Long) = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = data.size.toLong()
        compressedSize = data.size.toLong()
        crc = CRC32().apply { update(data) }.value
        extra = alignmentExtra(offset, name, 16_384)
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

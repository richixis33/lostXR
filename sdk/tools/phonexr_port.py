#!/usr/bin/env python3
"""
Готовит Android-сборку OpenXR-игры к запуску на PhoneXR.

Что делает:
  * правит бинарный AndroidManifest.xml так, чтобы игра видела OpenXR-брокер и ставилась на телефон;
  * подменяет libopenxr_loader.so на сборку PhoneXR (по желанию);
  * выравнивает и подписывает APK;
  * сообщает, если игра опирается на закрытый рантайм Meta и работать не будет.

Инструмент только для сборок, которые вы вправе запускать: свои, открытые, купленные вне магазина Meta.
"""

import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

MAX_TARGET_SDK = 29
LOADER_PATH = "lib/arm64-v8a/libopenxr_loader.so"
# Библиотеки закрытого рантайма Meta: без сервисов Meta игра с ними не запустится.
META_ONLY = ("libvrapi.so", "libovrplatformloader.so", "libOVRPlatformLoader.so", "libvrplatform.so")
HEADSET_FEATURE_PREFIXES = ("oculus.", "com.oculus.", "android.hardware.vr", "wave.feature", "picovr")


class Axml:
    """Читает бинарный AndroidManifest.xml и меняет числовые значения на месте."""

    CHUNK_STRING_POOL = 0x0001
    CHUNK_START_ELEMENT = 0x0102
    TYPE_INT_DEC = 0x10
    TYPE_INT_BOOLEAN = 0x12
    TYPE_STRING = 0x03

    def __init__(self, data: bytes):
        self.data = bytearray(data)
        self.strings = self._read_string_pool()

    def _u16(self, offset): return struct.unpack_from("<H", self.data, offset)[0]

    def _u32(self, offset): return struct.unpack_from("<I", self.data, offset)[0]

    def _read_string_pool(self):
        offset = 8
        while offset + 8 <= len(self.data):
            chunk, size = self._u16(offset), self._u32(offset + 4)
            if size <= 0:
                break
            if chunk == self.CHUNK_STRING_POOL:
                return self._parse_string_pool(offset)
            offset += size
        raise ValueError("В манифесте нет таблицы строк")

    def _parse_string_pool(self, offset):
        count, flags = self._u32(offset + 8), self._u32(offset + 16)
        start = offset + self._u32(offset + 20)
        utf8 = bool(flags & 0x100)
        strings = []
        for index in range(count):
            cursor = start + self._u32(offset + 28 + index * 4)
            if utf8:
                cursor, _ = self._read_length(cursor)
                cursor, length = self._read_length(cursor)
                strings.append(bytes(self.data[cursor:cursor + length]).decode("utf-8", "replace"))
            else:
                length = self._u16(cursor)
                cursor += 2
                if length & 0x8000:
                    length = ((length & 0x7FFF) << 16) | self._u16(cursor)
                    cursor += 2
                strings.append(bytes(self.data[cursor:cursor + length * 2]).decode("utf-16-le", "replace"))
        return strings

    def _read_length(self, cursor):
        value = self.data[cursor]
        cursor += 1
        if value & 0x80:
            value = ((value & 0x7F) << 8) | self.data[cursor]
            cursor += 1
        return cursor, value

    def elements(self):
        offset = 8
        while offset + 8 <= len(self.data):
            chunk, size = self._u16(offset), self._u32(offset + 4)
            if size <= 0:
                break
            if chunk == self.CHUNK_START_ELEMENT:
                name = self.strings[self._u32(offset + 20)]
                attribute_start = self._u16(offset + 24)
                attribute_size = self._u16(offset + 26)
                attribute_count = self._u16(offset + 28)
                attributes = {}
                for index in range(attribute_count):
                    attribute = offset + 16 + attribute_start + index * attribute_size
                    key = self.strings[self._u32(attribute + 4)]
                    attributes[key] = (self.data[attribute + 15], attribute + 16)
                yield name, attributes
            offset += size

    def value(self, slot):
        return self._u32(slot[1])

    def string_value(self, slot):
        data_type, offset = slot
        if data_type != self.TYPE_STRING:
            return None
        return self.strings[self._u32(offset)]

    def set_value(self, slot, value):
        struct.pack_into("<I", self.data, slot[1], value & 0xFFFFFFFF)


def patch_manifest(manifest: bytes):
    axml = Axml(manifest)
    changes = []
    for element, attributes in axml.elements():
        if element == "uses-sdk" and "targetSdkVersion" in attributes:
            slot = attributes["targetSdkVersion"]
            current = axml.value(slot)
            if slot[0] == Axml.TYPE_INT_DEC and current > MAX_TARGET_SDK:
                axml.set_value(slot, MAX_TARGET_SDK)
                changes.append(f"targetSdk {current} -> {MAX_TARGET_SDK} (игра снова видит OpenXR-брокер)")
        if "isSplitRequired" in attributes:
            slot = attributes["isSplitRequired"]
            if slot[0] == Axml.TYPE_INT_BOOLEAN and axml.value(slot) != 0:
                axml.set_value(slot, 0)
                changes.append("снят флаг обязательных дополнительных APK из Google Play")
        if element == "uses-feature" and "name" in attributes and "required" in attributes:
            name = axml.string_value(attributes["name"]) or ""
            if name.startswith(HEADSET_FEATURE_PREFIXES) and axml.value(attributes["required"]) != 0:
                axml.set_value(attributes["required"], 0)
                changes.append(f"{name} больше не обязательна")
    return bytes(axml.data), changes


def repack(source, destination, loader):
    changes, meta_libs, has_arm64 = [], set(), False
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as output:
        for entry in original.infolist():
            name = entry.filename
            upper = name.upper()
            if upper.startswith("META-INF/") and upper.rsplit(".", 1)[-1] in ("RSA", "DSA", "EC", "SF"):
                continue
            if upper == "META-INF/MANIFEST.MF":
                continue
            if name.startswith("lib/arm64-v8a/"):
                has_arm64 = True
            if os.path.basename(name) in META_ONLY:
                meta_libs.add(os.path.basename(name))

            data = original.read(entry)
            if name == "AndroidManifest.xml":
                data, manifest_changes = patch_manifest(data)
                changes += manifest_changes
            elif name == LOADER_PATH and loader:
                data = loader
                changes.append("OpenXR loader заменён на сборку PhoneXR")
            method = zipfile.ZIP_STORED if name.endswith(".so") else entry.compress_type
            output.writestr(zipfile.ZipInfo(name, date_time=entry.date_time), data, compress_type=method)
    return changes, meta_libs, has_arm64


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    versions = sorted((os.path.join(sdk, "build-tools", v) for v in os.listdir(os.path.join(sdk, "build-tools"))))
    for directory in reversed(versions):
        candidate = os.path.join(directory, name)
        if os.path.exists(candidate):
            return candidate
    raise SystemExit(f"Не найден {name}. Установите Android SDK build-tools или задайте ANDROID_HOME.")


def main():
    parser = argparse.ArgumentParser(description="Готовит OpenXR-игру к запуску на PhoneXR")
    parser.add_argument("apk", help="исходный APK")
    parser.add_argument("-o", "--output", help="куда сохранить готовый APK")
    parser.add_argument("--keystore", help="PKCS12 с ключом подписи", default=None)
    parser.add_argument("--keystore-pass", default="android")
    parser.add_argument("--key-alias", default="androiddebugkey")
    parser.add_argument("--loader", help="libopenxr_loader.so для подмены", default=None)
    parser.add_argument("--check", action="store_true", help="только проверить, ничего не записывать")
    arguments = parser.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    project = os.path.dirname(root)
    loader_path = arguments.loader or os.path.join(project, "app/src/main/assets/libopenxr_loader.so")
    keystore = arguments.keystore or os.path.join(project, "app/src/main/assets/phonexr-signing.p12")
    loader = open(loader_path, "rb").read() if os.path.exists(loader_path) else None
    output = arguments.output or arguments.apk.rsplit(".", 1)[0] + "-phonexr.apk"

    with tempfile.TemporaryDirectory() as workspace:
        staged = os.path.join(workspace, "staged.apk")
        aligned = os.path.join(workspace, "aligned.apk")
        changes, meta_libs, has_arm64 = repack(arguments.apk, staged, loader)

        print("Изменения:")
        for change in changes or ["ничего менять не потребовалось"]:
            print(f"  - {change}")
        if not has_arm64:
            print("\nОшибка: в APK нет 64-битных библиотек (arm64-v8a), такая сборка не запустится.")
            return 1
        if meta_libs:
            print("\nОшибка: игра собрана под закрытый рантайм Meta (" + ", ".join(sorted(meta_libs)) + ").")
            print("Она проверяет покупку через сервисы Meta и на PhoneXR работать не будет.")
            print("Подойдут игры на OpenXR: свои сборки, открытые проекты, версии вне магазина Meta.")
            return 2
        if arguments.check:
            print("\nПроверка пройдена, файл не записан (--check).")
            return 0

        subprocess.run([build_tool("zipalign"), "-P", "16", "-f", "4", staged, aligned], check=True)
        subprocess.run([
            build_tool("apksigner"), "sign",
            "--ks", keystore, "--ks-pass", f"pass:{arguments.keystore_pass}",
            "--ks-key-alias", arguments.key_alias, "--key-pass", f"pass:{arguments.keystore_pass}",
            "--out", output, aligned,
        ], check=True)
    print(f"\nГотово: {output}")
    print("Установите его и запускайте из PhoneXR.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

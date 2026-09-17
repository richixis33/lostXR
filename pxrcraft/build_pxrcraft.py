#!/usr/bin/env python3
"""
Собирает PXRCraft — QuestCraft (Minecraft: Java Edition в VR) для телефона с PhoneXR.

QuestCraft распространяется под LGPL-3.0 (https://github.com/QuestCraftPlusPlus). Лаунчер у него
собран в Unity, а для сборки из исходников нужен Unity 2022.3.62f3 с Android-модулем. Этот скрипт
переносит официальный релиз без Unity:
  * пакет com.qcxr.qcxr -> com.samrat.pxrcraft, название PXRCraft, своя иконка;
  * снимает требования Quest (трекинг головы, Vulkan, отслеживание глаз), чтобы APK ставился на телефон;
  * добавляет категорию OpenXR, чтобы PhoneXR показывал игру в списке;
  * заменяет OpenXR loader на сборку PhoneXR и подписывает ключом PhoneXR.

Играть можно только со своей купленной копией Minecraft: Java Edition (вход через Microsoft).

Нужно: apktool, Java, Android SDK build-tools, Pillow.
  python3 pxrcraft/build_pxrcraft.py                      # скачает релиз 6.0.0
  python3 pxrcraft/build_pxrcraft.py --apk QCXR-6.0.0.apk # из готового файла
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

PACKAGE = "com.samrat.pxrcraft"
NAME = "PXRCraft"
RELEASE_URL = "https://github.com/QuestCraftPlusPlus/QuestCraft/releases/download/{0}/QCXR-{0}.apk"
QUEST_ONLY_FEATURES = ("android.hardware.vr.headtracking", "android.hardware.vulkan.version", "oculus.software.eye_tracking")
ICON_SIZES = {"ldpi": 36, "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    folder = os.path.join(sdk, "build-tools")
    for version in sorted(os.listdir(folder), reverse=True):
        candidate = os.path.join(folder, version, name)
        if os.path.exists(candidate):
            return candidate
    raise SystemExit(f"Не найден {name} в Android SDK build-tools")


def patch_manifest(path):
    text = open(path, encoding="utf-8").read()
    # Every class in the manifest is fully qualified, so only the package itself changes.
    # Resource ids stay the same, so the app's R class still matches.
    text = text.replace('package="com.qcxr.qcxr"', f'package="{PACKAGE}"')
    for feature in QUEST_ONLY_FEATURES:
        text = re.sub(
            r'(<uses-feature[^>]*android:name="%s"[^>]*android:required=)"true"' % re.escape(feature),
            r'\1"false"', text)
    # Oculus/Pico-only permissions do nothing on a phone.
    text = re.sub(r'\s*<uses-permission android:name="com\.(pvr|oculus)\.[^"]*"/>', "", text)
    # The Pico build step repeated these tags dozens of times.
    text = re.sub(r'(\s*<meta-data android:name="pvr\.app\.id"[^>]*/>\s*<meta-data android:name="use_record_highlight_feature"[^>]*/>)+',
                  "", text)
    # Show in recents like any phone app, and let PhoneXR and the OpenXR broker find it as an immersive app.
    text = text.replace('android:excludeFromRecents="true" ', "")
    text = text.replace(
        '<category android:name="com.oculus.intent.category.VR"/>\n                <category android:name="android.intent.category.LAUNCHER"/>',
        '<category android:name="com.oculus.intent.category.VR"/>\n'
        '                <category android:name="org.khronos.openxr.intent.category.IMMERSIVE_HMD"/>\n'
        '                <category android:name="android.intent.category.LAUNCHER"/>')
    open(path, "w", encoding="utf-8").write(text)


def patch_name(folder):
    for values in os.listdir(os.path.join(folder, "res")):
        strings = os.path.join(folder, "res", values, "strings.xml")
        if os.path.exists(strings):
            text = open(strings, encoding="utf-8").read()
            text = re.sub(r'(<string name="app_name">)[^<]*(</string>)', rf"\g<1>{NAME}\g<2>", text)
            open(strings, "w", encoding="utf-8").write(text)


def draw_icon(size):
    """A grass block with a VR visor in PhoneXR orange."""
    from PIL import Image, ImageDraw
    scale = 4
    s = size * scale
    image = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    radius = s // 5
    draw.rounded_rectangle((0, 0, s - 1, s - 1), radius, fill=(121, 85, 58))
    # Dirt speckles and a grass top.
    step = s // 8
    for y in range(3, 8):
        for x in range(8):
            if (x * 7 + y * 3) % 5 == 0:
                draw.rectangle((x * step, y * step, (x + 1) * step, (y + 1) * step), fill=(94, 64, 42))
    top = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(top).rounded_rectangle((0, 0, s - 1, int(s * .42)), radius, fill=(95, 159, 53))
    ImageDraw.Draw(top).rectangle((0, radius, s - 1, int(s * .42)), fill=(95, 159, 53))
    for x in range(8):
        if x % 2 == 0:
            ImageDraw.Draw(top).rectangle((x * step, int(s * .42), (x + 1) * step, int(s * .42) + step // 2), fill=(95, 159, 53))
    image.alpha_composite(top)
    # Visor.
    visor = (int(s * .14), int(s * .52), int(s * .86), int(s * .80))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle(visor, s // 12, fill=(255, 122, 26))
    for lens in ((int(s * .22), int(s * .58), int(s * .46), int(s * .74)), (int(s * .54), int(s * .58), int(s * .78), int(s * .74))):
        draw.rounded_rectangle(lens, s // 24, fill=(30, 22, 18))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius, fill=255)
    image.putalpha(mask)
    return image.resize((size, size), Image.LANCZOS)


def patch_icons(folder):
    res = os.path.join(folder, "res")
    for density, size in ICON_SIZES.items():
        directory = os.path.join(res, f"mipmap-{density}")
        if not os.path.isdir(directory):
            continue
        icon = draw_icon(size)
        for name in ("app_icon.png", "app_icon_round.png"):
            if os.path.exists(os.path.join(directory, name)):
                icon.save(os.path.join(directory, name))
        foreground = os.path.join(directory, "ic_launcher_foreground.png")
        if os.path.exists(foreground):
            # Adaptive foreground: the icon in the middle 2/3 of a transparent canvas.
            from PIL import Image
            canvas_size = Image.open(foreground).size[0]
            canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
            inner = draw_icon(int(canvas_size * .66))
            offset = (canvas_size - inner.size[0]) // 2
            canvas.alpha_composite(inner, (offset, offset))
            canvas.save(foreground)
    # Adaptive background colour.
    for values in os.listdir(res):
        colors = os.path.join(res, values, "colors.xml")
        if os.path.exists(colors):
            text = open(colors, encoding="utf-8").read()
            text = re.sub(r'(<color name="ic_launcher_background">)[^<]*(</color>)', r"\g<1>#FF79553A\g<2>", text)
            open(colors, "w", encoding="utf-8").write(text)


def main():
    parser = argparse.ArgumentParser(description="Собирает PXRCraft из релиза QuestCraft")
    parser.add_argument("--apk", help="готовый QCXR-*.apk; без него скачивается релиз")
    parser.add_argument("--version", default="6.0.0", help="версия QuestCraft для скачивания")
    parser.add_argument("-o", "--output", default=os.path.join(ROOT, "Builds", "PXRCraft.apk"))
    arguments = parser.parse_args()

    for tool in ("apktool", "java"):
        if shutil.which(tool) is None:
            raise SystemExit(f"Нужен {tool}")

    with tempfile.TemporaryDirectory() as work:
        source = arguments.apk
        if not source:
            source = os.path.join(work, "questcraft.apk")
            url = RELEASE_URL.format(arguments.version)
            print(f"Скачиваю {url}")
            urllib.request.urlretrieve(url, source)

        decoded = os.path.join(work, "decoded")
        subprocess.run(["apktool", "d", "-q", "-f", source, "-o", decoded], check=True)
        patch_manifest(os.path.join(decoded, "AndroidManifest.xml"))
        patch_name(decoded)
        patch_icons(decoded)
        loader = os.path.join(ROOT, "app", "src", "main", "assets", "libopenxr_loader.so")
        shutil.copy(loader, os.path.join(decoded, "lib", "arm64-v8a", "libopenxr_loader.so"))

        unsigned = os.path.join(work, "unsigned.apk")
        subprocess.run(["apktool", "b", "-q", decoded, "-o", unsigned], check=True)
        aligned = os.path.join(work, "aligned.apk")
        subprocess.run([build_tool("zipalign"), "-P", "16", "-f", "4", unsigned, aligned], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(arguments.output)), exist_ok=True)
        keystore = os.path.join(ROOT, "app", "src", "main", "assets", "phonexr-signing.p12")
        subprocess.run([
            build_tool("apksigner"), "sign", "--ks", keystore, "--ks-pass", "pass:android",
            "--ks-key-alias", "androiddebugkey", "--key-pass", "pass:android",
            "--out", arguments.output, aligned,
        ], check=True)
    print(f"Готово: {arguments.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

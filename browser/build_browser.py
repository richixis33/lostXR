#!/usr/bin/env python3
"""
Собирает браузер PhoneXR из Wolvic (https://github.com/Igalia/wolvic, MPL-2.0).

Меняет пакет (ставится рядом с Wolvic), название, иконку, OpenXR loader и подписывает ключом
PhoneXR. Движок и интерфейс — Wolvic; его лицензия и ссылка на исходники остаются в «О браузере».

  python3 browser/build_browser.py                    # скачает Wolvic (сборка lynx, OpenXR)
  python3 browser/build_browser.py --apk Wolvic.apk
"""

import argparse
import math
import os
import re
import shutil
import subprocess
import tempfile
import urllib.request

PACKAGE = "com.samrat.pxrbrowser"
ORIGINAL = "com.igalia.wolvic"
NAME = "Браузер"
RELEASE = "https://github.com/Igalia/wolvic/releases/download/v1.9/Wolvic-lynx-arm64-gecko-generic-release.apk"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    folder = os.path.join(sdk, "build-tools")
    for version in sorted(os.listdir(folder), reverse=True):
        path = os.path.join(folder, version, name)
        if os.path.exists(path):
            return path
    raise SystemExit(f"Не найден {name}")


def patch_manifest(path):
    text = open(path, encoding="utf-8").read()
    # Relative class names would resolve against the new package; spell them out first.
    text = re.sub(r'android:name="\.([^"]+)"', rf'android:name="{ORIGINAL}.\1"', text)
    text = text.replace(f'package="{ORIGINAL}"', f'package="{PACKAGE}"')
    # Content provider authorities and custom permissions must not clash with an installed Wolvic.
    text = re.sub(rf'android:authorities="{re.escape(ORIGINAL)}', f'android:authorities="{PACKAGE}', text)
    text = re.sub(rf'"{re.escape(ORIGINAL)}\.permission', f'"{PACKAGE}.permission', text)
    open(path, "w", encoding="utf-8").write(text)


def patch_label(folder):
    for values in os.listdir(os.path.join(folder, "res")):
        strings = os.path.join(folder, "res", values, "strings.xml")
        if os.path.exists(strings):
            text = open(strings, encoding="utf-8").read()
            text = re.sub(r'(<string name="app_name">)[^<]*(</string>)', rf"\g<1>{NAME}\g<2>", text)
            open(strings, "w", encoding="utf-8").write(text)


def draw_icon(size):
    """PhoneXR browser icon: a blue disc with a compass needle."""
    from PIL import Image, ImageDraw
    s = size * 4
    image = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for y in range(s):
        t = y / s
        draw.line((0, y, s, y), fill=(int(90 * (1 - t)), int(200 * (1 - t) + 122 * t), 250 if t < .5 else 255))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, s - 1, s - 1), fill=255)
    image.putalpha(mask)
    c = s / 2
    draw.ellipse((c - s * .34, c - s * .34, c + s * .34, c + s * .34), outline="white", width=int(s * .035))
    draw.polygon([(c + s * .2, c - s * .2), (c - s * .05, c - s * .05), (c + s * .05, c + s * .05)], fill=(255, 59, 48))
    draw.polygon([(c - s * .2, c + s * .2), (c - s * .05, c - s * .05), (c + s * .05, c + s * .05)], fill="white")
    return image.resize((size, size), Image.LANCZOS)


def patch_icons(folder):
    res = os.path.join(folder, "res")
    for directory in os.listdir(res):
        if not directory.startswith("mipmap-"):
            continue
        density = directory.split("-")[1]
        size = ICON_SIZES.get(density)
        for file in os.listdir(os.path.join(res, directory)):
            if size and file.endswith(".png") and file.startswith(("ic_launcher", "ic_icon", "logo")):
                draw_icon(size).save(os.path.join(res, directory, file))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk")
    parser.add_argument("-o", "--output", default=os.path.join(ROOT, "Builds", "PhoneXR-Browser.apk"))
    arguments = parser.parse_args()
    with tempfile.TemporaryDirectory() as work:
        source = arguments.apk
        if not source:
            source = os.path.join(work, "wolvic.apk")
            print("Скачиваю", RELEASE)
            urllib.request.urlretrieve(RELEASE, source)
        decoded = os.path.join(work, "decoded")
        subprocess.run(["apktool", "d", "-q", "-s", "-f", source, "-o", decoded], check=True)
        patch_manifest(os.path.join(decoded, "AndroidManifest.xml"))
        patch_label(decoded)
        patch_icons(decoded)
        loader = os.path.join(decoded, "lib", "arm64-v8a", "libopenxr_loader.so")
        if os.path.exists(loader):
            shutil.copy(os.path.join(ROOT, "app", "src", "main", "assets", "libopenxr_loader.so"), loader)
        unsigned = os.path.join(work, "unsigned.apk")
        subprocess.run(["apktool", "b", "-q", decoded, "-o", unsigned], check=True)
        aligned = os.path.join(work, "aligned.apk")
        subprocess.run([build_tool("zipalign"), "-P", "16", "-f", "4", unsigned, aligned], check=True)
        os.makedirs(os.path.dirname(arguments.output), exist_ok=True)
        keystore = os.path.join(ROOT, "app", "src", "main", "assets", "phonexr-signing.p12")
        subprocess.run([build_tool("apksigner"), "sign", "--ks", keystore, "--ks-pass", "pass:android",
                        "--ks-key-alias", "androiddebugkey", "--key-pass", "pass:android",
                        "--out", arguments.output, aligned], check=True)
    print("Готово:", arguments.output)


if __name__ == "__main__":
    main()
